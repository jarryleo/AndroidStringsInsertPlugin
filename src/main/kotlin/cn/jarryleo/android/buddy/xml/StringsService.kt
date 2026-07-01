package cn.jarryleo.android.buddy.xml

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * strings.xml 读写服务,供 AI 工具调用。
 *
 * 提供 4 类原子操作:
 * - [listKeys]   列出模块内所有 key(支持 offset/limit 分页)
 * - [searchKeys] 按正则 pattern 搜索匹配的 key
 * - [readKey]    读取指定 key 在所有语言的当前翻译
 * - [updateKey]  部分语言更新(只动提供的语言,其他语言保持原样)
 *
 * 设计目标:让 AI 能像编辑代码一样精准修改某 key 的某语言翻译,
 * 避免「全量覆写」语义导致其他语言被错误覆盖。
 */
object StringsService {

    /**
     * 列出模块内所有 key(从默认语言 values/strings.xml 解析,按文件中出现顺序)。
     */
    fun listKeys(
        project: Project,
        moduleName: String,
        limit: Int = 200,
        offset: Int = 0
    ): List<String> {
        val defaultFile = resolveDefaultStringsFile(project, moduleName) ?: return emptyList()
        val allKeys = parseKeysFromFile(defaultFile)
        val safeOffset = offset.coerceAtLeast(0)
        val safeLimit = limit.coerceAtLeast(1).coerceAtMost(2000)
        return allKeys.drop(safeOffset).take(safeLimit)
    }

    /**
     * 按正则 pattern 搜索 key(对模块默认语言文件中的 key 名做匹配,或扩展为多语种翻译文本匹配)。
     *
     * @param pattern            正则字符串。为空/null 时退化为 [listKeys]。
     * @param limit              最大返回条数
     * @param offset             分页偏移
     * @param includeTranslations 是否在结果中带各语言当前翻译(默认 false,节省 token)
     * @param searchIn           搜索范围:
     *                            - [SearchIn.KEY]  (默认)只匹配 key 名,完全等价于旧行为
     *                            - [SearchIn.TEXT] 跨多语言文件,匹配任一语言的翻译文本
     *                            - [SearchIn.BOTH] key 名 / 翻译文本 任一命中即可
     * @return 匹配结果:[key, language -> text, filePath] 四元组
     */
    fun searchKeys(
        project: Project,
        moduleName: String,
        pattern: String?,
        limit: Int = 50,
        offset: Int = 0,
        includeTranslations: Boolean = false,
        searchIn: SearchIn = SearchIn.KEY,
    ): List<KeySearchResult> {
        val defaultFile = resolveDefaultStringsFile(project, moduleName) ?: return emptyList()
        val allKeys = parseKeysFromFile(defaultFile)
        val regex = if (pattern.isNullOrBlank()) {
            null
        } else {
            runCatching { Regex(pattern) }.getOrElse { return emptyList() }
        }
        val matchedKeys: List<String> = when {
            regex == null -> allKeys
            searchIn == SearchIn.KEY -> allKeys.filter { regex.containsMatchIn(it) }
            else -> {
                // TEXT / BOTH: 跨多语言文件扫翻译文本,任一文件命中即可。
                // 复用 parseStringEntriesFromFile,避免重复正则。
                val context = ContextManager.getInstance(project)
                val perFile = context.getModuleFiles(moduleName)
                val hit = LinkedHashSet<String>()
                for ((valuesDir, stringsFile) in perFile) {
                    val entries = parseStringEntriesFromFile(stringsFile)
                    for ((key, value) in entries) {
                        if (regex.containsMatchIn(value)) {
                            hit.add(key)
                            if (searchIn == SearchIn.TEXT && hit.size >= limit) break
                        }
                    }
                    if (searchIn == SearchIn.TEXT && hit.size >= limit) break
                }
                // BOTH: 还要把 key 名命中的补进来
                if (searchIn == SearchIn.BOTH) {
                    allKeys.filter { regex.containsMatchIn(it) }.forEach { hit.add(it) }
                }
                // 保持 defaultFile 中出现顺序,让结果可读
                allKeys.filter { it in hit }
            }
        }
        val safeOffset = offset.coerceAtLeast(0)
        val safeLimit = limit.coerceAtLeast(1).coerceAtMost(500)
        val page = matchedKeys.drop(safeOffset).take(safeLimit)
        if (!includeTranslations) {
            return page.map { KeySearchResult(it, emptyMap(), "") }
        }
        val files = ContextManager.getInstance(project).getModuleFiles(moduleName)
        return page.map { key ->
            val translations = files.associate { (valuesDir, stringsFile) ->
                valuesDir.name to getStringText(stringsFile, key)
            }
            KeySearchResult(key, translations, defaultFile.path)
        }
    }

    enum class SearchIn { KEY, TEXT, BOTH }

    /**
     * 读取指定 key 在模块所有语言的当前翻译。
     */
    fun readKey(
        project: Project,
        moduleName: String,
        key: String
    ): KeyReadResult? {
        if (key.isEmpty()) return null
        val files = ContextManager.getInstance(project).getModuleFiles(moduleName)
        if (files.isEmpty()) return null
        val translations = files.associate { (valuesDir, stringsFile) ->
            valuesDir.name to getStringText(stringsFile, key)
        }
        val filePaths = files.associate { (valuesDir, stringsFile) ->
            valuesDir.name to stringsFile.path
        }
        return KeyReadResult(key, moduleName, translations, filePaths)
    }

    /**
     * 合法 Android 语言目录名:values / values-zh-rCN / values-b+en+US 等。
     * 用来兜住 AI 偶发把语言名包成 `<|"|<values-ar|"|>` 之类的脏 key —— 这种串
     * 不能直接落盘成目录,但如果只校验「必须以 values 开头 + 合法目录字符」太松,
     * 「values-ar]]>」之类的尾巴也会过;这里用完整正则一次性收口,失败时给 AI
     * 一条明确「应该长啥样」的错误信息,让它下一轮自己改。
     */
    private val validLanguagePattern = Regex("^values[A-Za-z0-9+_-]*$")

    /**
     * 部分语言更新:只更新 [translations] 中列出的语言,其他语言保持原样。
     * 若 key 在某个语言文件中不存在,则在该语言文件中创建新条目。
     * 若 key 在所有文件都不存在,则视为新增(各语言按 translations 写入)。
     *
     * 2026.x:目标语言文件不存在时会自动通过 [ContextManager.ensureLanguageFile]
     * 在模块的 res 目录下补齐 `values[-xxx]/strings.xml`,而不是像旧版那样
     * 静默跳过导致 AI 看到「部分失败 0/1 跳过(无该语言文件)」。这与
     * android.buddy 批处理在 [cn.jarryleo.android.buddy.ui.InsertStringsChatDriver]
     * 里的补齐行为对齐 —— 写动作一律「缺啥建啥」。
     *
     * @return 每个语言的更新结果,key 为语言目录名(例: "values-zh-rTW"),
     *         value 为 "成功" / "失败: <原因>"
     */
    fun updateKey(
        project: Project,
        moduleName: String,
        key: String,
        translations: Map<String, String>
    ): Map<String, String> {
        if (key.isEmpty()) return mapOf("" to "失败: key 为空")
        if (translations.isEmpty()) return mapOf("" to "失败: translations 为空,未提供任何语言")
        val context = ContextManager.getInstance(project)
        val files = context.getModuleFiles(moduleName)
        if (files.isEmpty()) {
            return mapOf("" to "失败: 模块 '$moduleName' 不存在或没有 strings.xml")
        }
        val fileByLang = files.associate { (valuesDir, stringsFile) -> valuesDir.name to stringsFile }
        val results = linkedMapOf<String, String>()
        WriteCommandAction.runWriteCommandAction(project) {
            translations.forEach { (lang, newText) ->
                if (!validLanguagePattern.matches(lang)) {
                    results[lang] = "失败: 语言目录名 '$lang' 不合法,应为 'values' 或 'values-<bcp47>'(如 values-ar、values-zh-rCN、values-b+en+US)"
                    return@forEach
                }
                val stringsFile = fileByLang[lang] ?: context.ensureLanguageFile(moduleName, lang)
                if (stringsFile == null) {
                    results[lang] = "失败: 模块 '$moduleName' 缺少 res 目录,无法创建 '$lang/strings.xml'"
                    return@forEach
                }
                try {
                    val ok = updateOrCreateInFile(stringsFile, key, newText)
                    results[lang] = if (ok) "成功" else "失败: 写入失败"
                } catch (e: Exception) {
                    results[lang] = "失败: ${e.message ?: "unknown"}"
                }
            }
        }
        return results
    }

    /**
     * 删除指定 key 的翻译(破坏性操作)。
     * - [languages] 为空时,删除 key 在所有语言的翻译(整 key 被移除)。
     * - [languages] 非空时,仅删除列表中指定语言的翻译,其他语言保持原样。
     *
     * @return 每个目标语言的删除结果,key 为语言目录名(例: "values-fr"),
     *         value 为 "成功" / "未找到该 key" / "失败: <原因>"
     */
    fun deleteKey(
        project: Project,
        moduleName: String,
        key: String,
        languages: List<String> = emptyList()
    ): Map<String, String> {
        if (key.isEmpty()) return mapOf("" to "失败: key 为空")
        val files = ContextManager.getInstance(project).getModuleFiles(moduleName)
        if (files.isEmpty()) {
            return mapOf("" to "失败: 模块 '$moduleName' 不存在或没有 strings.xml")
        }
        val results = mutableMapOf<String, String>()
        WriteCommandAction.runWriteCommandAction(project) {
            files.forEach { (valuesDir, stringsFile) ->
                val lang = valuesDir.name
                if (languages.isNotEmpty() && lang !in languages) return@forEach
                try {
                    val removed = removeFromFile(stringsFile, key)
                    results[lang] = if (removed) "成功" else "未找到该 key"
                } catch (e: Exception) {
                    results[lang] = "失败: ${e.message ?: "unknown"}"
                }
            }
        }
        return results
    }

    /**
     * 反查:根据翻译文本查找包含该文本的 key。
     *
     * @param text           要查找的翻译文本(必填,非空)
     * @param moduleName     限定模块;为 null 时扫描项目中所有模块
     * @param language       限定语言目录(如 values-zh-rTW);为 null 时扫描所有语言
     * @param matchType      匹配模式:contains(默认,子串)/ exact(完全相等)/ regex(正则)
     * @param caseSensitive  是否区分大小写,默认 false
     * @param limit          最大返回条数,默认 30
     * @return 命中的 [KeyTextSearchResult] 列表,按 (module, language, key) 排序
     */
    fun findKeysByText(
        project: Project,
        text: String,
        moduleName: String?,
        language: String?,
        matchType: TextMatchType,
        caseSensitive: Boolean,
        limit: Int
    ): List<KeyTextSearchResult> {
        if (text.isBlank()) return emptyList()
        val matcher = buildMatcher(text, matchType, caseSensitive) ?: return emptyList()

        // 收集要扫描的 (valuesDir, stringsFile, moduleName) 三元组
        val context = ContextManager.getInstance(project)
        val targets: List<Triple<String, VirtualFile, String>> = run {
            if (moduleName != null) {
                context.getModuleFiles(moduleName).map { (valuesDir, file) ->
                    Triple(valuesDir.name, file, moduleName)
                }
            } else {
                context.getAllModuleFiles().map { (mod, valuesDir, file) ->
                    Triple(valuesDir.name, file, mod)
                }
            }
        }.let { list ->
            if (language != null) list.filter { it.first == language } else list
        }
        if (targets.isEmpty()) return emptyList()

        val results = mutableListOf<KeyTextSearchResult>()
        for ((lang, file, mod) in targets) {
            val entries = parseStringEntriesFromFile(file)
            for ((key, value) in entries) {
                if (matcher(value)) {
                    results.add(
                        KeyTextSearchResult(
                            key = key,
                            module = mod,
                            language = lang,
                            text = value,
                            filePath = file.path
                        )
                    )
                    if (results.size >= limit) return results
                }
            }
        }
        return results
    }

    /** 文本匹配模式。 */
    enum class TextMatchType { EXACT, CONTAINS, REGEX }

    private fun buildMatcher(
        text: String,
        matchType: TextMatchType,
        caseSensitive: Boolean
    ): ((String) -> Boolean)? {
        return when (matchType) {
            TextMatchType.EXACT -> exactMatcher(text, caseSensitive)
            TextMatchType.CONTAINS -> containsMatcher(text, caseSensitive)
            TextMatchType.REGEX -> regexMatcher(text)
        }
    }

    private fun exactMatcher(text: String, caseSensitive: Boolean): (String) -> Boolean {
        return if (caseSensitive) {
            fun match(v: String): Boolean = v == text
            ::match
        } else {
            val needle = text.lowercase()
            fun match(v: String): Boolean = v.lowercase() == needle
            ::match
        }
    }

    private fun containsMatcher(text: String, caseSensitive: Boolean): (String) -> Boolean {
        return if (caseSensitive) {
            val needle = text
            fun match(v: String): Boolean = v.contains(needle)
            ::match
        } else {
            val needle = text.lowercase()
            fun match(v: String): Boolean = v.lowercase().contains(needle)
            ::match
        }
    }

    private fun regexMatcher(text: String): ((String) -> Boolean)? {
        val regex = runCatching { Regex(text) }.getOrNull() ?: return null
        return { v -> regex.containsMatchIn(v) }
    }

    // region 内部工具

    private fun resolveDefaultStringsFile(
        project: Project,
        moduleName: String
    ): VirtualFile? {
        val files = ContextManager.getInstance(project).getModuleFiles(moduleName)
        return files.firstOrNull { it.first.name == "values" }?.second
            ?: files.firstOrNull()?.second
    }

    private fun parseKeysFromFile(file: VirtualFile): List<String> {
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return emptyList()
        val xml = document.text
        // 用 <string\s 排除 <string-array>/<plurals>;用 [^>]*\bname 允许 name 前有其它属性
        val regex = """<string\s[^>]*\bname\s*=\s*(['"])(.*?)\1""".toRegex()
        return regex.findAll(xml).map { it.groupValues[2] }
            .filter { it.isNotEmpty() }
            .toList()
    }

    /**
     * 解析文件中所有 <string name="...">text</string> 条目,返回 [(key, text)] 列表。
     * 复用同一个正则提取 name 与 inner text,避免重复扫描。
     */
    private fun parseStringEntriesFromFile(file: VirtualFile): List<Pair<String, String>> {
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return emptyList()
        val xml = document.text
        // 一次匹配同时拿到 name 与 inner text(非贪婪);[^>]* 允许 name 前有其它属性
        val regex = """<string\s[^>]*\bname\s*=\s*(['"])(.*?)\1\s*>([\s\S]*?)</string>""".toRegex()
        return regex.findAll(xml).map { m ->
            val key = m.groupValues[2]
            val text = m.groupValues[3].trim()
            key to text
        }.filter { it.first.isNotEmpty() }
            .toList()
    }

    private fun getStringText(file: VirtualFile, key: String): String {
        if (key.isEmpty()) return ""
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return ""
        val xml = document.text
        val escapedKey = Regex.escape(key)
        val regex =
            """<string\b(?=[^>]*\bname\s*=\s*(['"])$escapedKey\1)[^>]*>([\s\S]*?)</string>""".toRegex()
        return regex.find(xml)?.groupValues?.get(2).orEmpty()
    }

    /**
     * 在单个文件中更新或创建指定 key。
     * @return 成功写入返回 true
     */
    private fun updateOrCreateInFile(
        file: VirtualFile,
        key: String,
        newText: String
    ): Boolean {
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return false
        val xml = document.text
        val escapedKey = Regex.escape(key)
        // 2026.x:key 已存在时,若原值有 <![CDATA[]]> / <Data></Data> 包裹,
        // 自动给 newText 套上同样的包裹 — 防止 AI 翻译/修改时漏写导致丢格式标记。
        // 详见 [AndroidStringEscaper.preserveWrapping] 注释。
        val existingText = getStringText(file, key)
        val finalText = AndroidStringEscaper.preserveWrapping(existingText, newText)
        val escapedText = AndroidStringEscaper.escape(finalText)
        val newNode = "<string name=\"$key\">$escapedText</string>"

        // 已存在则替换(只动节点内容,前后空白与缩进保持不变)
        val pattern =
            """<string\b(?=[^>]*\bname\s*=\s*(['"])$escapedKey\1)[^>]*>[\s\S]*?</string>""".toRegex()
        val match = pattern.find(xml)
        if (match != null) {
            document.replaceString(match.range.first, match.range.last + 1, newNode)
            return true
        }
        // 不存在则插入到 </resources> 之前;缩进跟随文件自身风格,不再硬编码 \t
        val indent = StringsXmlFormatter.detectIndent(xml)
        val insertLine = StringsXmlFormatter.buildInsertLine(indent, newNode)
        val insertPos = xml.indexOf("</resources>")
        if (insertPos == -1) {
            // 文件没有 </resources> 闭合:直接追加到末尾(appendLine 末尾自带 \n)
            document.insertString(xml.length, insertLine)
            return true
        }
        document.insertString(insertPos, insertLine)
        return true
    }

    /**
     * 从单个文件中删除指定 key 对应的 <string> 节点所在的整行
     * (含行首缩进与行尾换行),下一行内容字节不动、缩进自然保持。
     *
     * 设计原则:只删目标行,不改文件其它部分的空行 / 排版。多次删除也不会互相影响。
     * @return 实际删除了一条记录返回 true;若 key 在该文件中不存在返回 false
     */
    private fun removeFromFile(
        file: VirtualFile,
        key: String
    ): Boolean {
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return false
        val xml = document.text
        val escapedKey = Regex.escape(key)
        val pattern =
            """<string\b(?=[^>]*\bname\s*=\s*(['"])$escapedKey\1)[^>]*>[\s\S]*?</string>""".toRegex()
        val match = pattern.find(xml) ?: return false
        // 扩展到所在整行:行首从上一个 \n 之后开始,行尾到下一个 \n 之后结束
        val matchStart = match.range.first
        val matchEnd = match.range.last + 1
        val lineStart = xml.lastIndexOf('\n', matchStart - 1).let { if (it < 0) 0 else it + 1 }
        val lineEnd = xml.indexOf('\n', matchEnd).let { if (it < 0) xml.length else it + 1 }
        document.replaceString(lineStart, lineEnd, "")
        return true
    }

    // endregion
}

/** [StringsService.searchKeys] 单条结果。 */
data class KeySearchResult(
    val key: String,
    val translations: Map<String, String>,
    val filePath: String
)

/** [StringsService.readKey] 返回结构。 */
data class KeyReadResult(
    val key: String,
    val module: String,
    val translations: Map<String, String>,
    val filePaths: Map<String, String>
)

/** [StringsService.findKeysByText] 单条结果。 */
data class KeyTextSearchResult(
    val key: String,
    val module: String,
    val language: String,
    val text: String,
    val filePath: String
)
