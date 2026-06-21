package cn.jarryleo.insert_strings.xml

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
     * 按正则 pattern 搜索 key(对模块默认语言文件中的 key 名做匹配)。
     *
     * @param pattern 正则字符串。为空/null 时退化为 [listKeys]。
     * @param limit   最大返回条数
     * @param offset  分页偏移
     * @return 匹配结果:[key, language -> text, filePath] 三元组
     */
    fun searchKeys(
        project: Project,
        moduleName: String,
        pattern: String?,
        limit: Int = 50,
        offset: Int = 0,
        includeTranslations: Boolean = false
    ): List<KeySearchResult> {
        val defaultFile = resolveDefaultStringsFile(project, moduleName) ?: return emptyList()
        val allKeys = parseKeysFromFile(defaultFile)
        val regex = if (pattern.isNullOrBlank()) {
            null
        } else {
            runCatching { Regex(pattern) }.getOrElse { return emptyList() }
        }
        val matchedKeys = if (regex == null) allKeys else allKeys.filter { regex.containsMatchIn(it) }
        val safeOffset = offset.coerceAtLeast(0)
        val safeLimit = limit.coerceAtLeast(1).coerceAtMost(500)
        val page = matchedKeys.drop(safeOffset).take(safeLimit)
        if (!includeTranslations) {
            return page.map { KeySearchResult(it, emptyMap(), "") }
        }
        val files = ContextManager.getModuleFiles(project, moduleName)
        return page.map { key ->
            val translations = files.associate { (valuesDir, stringsFile) ->
                valuesDir.name to getStringText(stringsFile, key)
            }
            KeySearchResult(key, translations, defaultFile.path)
        }
    }

    /**
     * 读取指定 key 在模块所有语言的当前翻译。
     */
    fun readKey(
        project: Project,
        moduleName: String,
        key: String
    ): KeyReadResult? {
        if (key.isEmpty()) return null
        val files = ContextManager.getModuleFiles(project, moduleName)
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
     * 部分语言更新:只更新 [translations] 中列出的语言,其他语言保持原样。
     * 若 key 在某个语言文件中不存在,则在该语言文件中创建新条目。
     * 若 key 在所有文件都不存在,则视为新增(各语言按 translations 写入)。
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
        val files = ContextManager.getModuleFiles(project, moduleName)
        if (files.isEmpty()) {
            return mapOf("" to "失败: 模块 '$moduleName' 不存在或没有 strings.xml")
        }
        val results = mutableMapOf<String, String>()
        WriteCommandAction.runWriteCommandAction(project) {
            files.forEach { (valuesDir, stringsFile) ->
                val lang = valuesDir.name
                val newText = translations[lang] ?: return@forEach
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
        val files = ContextManager.getModuleFiles(project, moduleName)
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
        val targets: List<Triple<String, VirtualFile, String>> = run {
            if (moduleName != null) {
                ContextManager.getModuleFiles(project, moduleName).map { (valuesDir, file) ->
                    Triple(valuesDir.name, file, moduleName)
                }
            } else {
                ContextManager.getAllModuleFiles(project).map { (mod, valuesDir, file) ->
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
        val files = ContextManager.getModuleFiles(project, moduleName)
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
        val escapedText = AndroidStringEscaper.escape(newText)
        val newNode = "<string name=\"$key\">$escapedText</string>"

        // 已存在则替换
        val pattern =
            """<string\b(?=[^>]*\bname\s*=\s*(['"])$escapedKey\1)[^>]*>[\s\S]*?</string>""".toRegex()
        val match = pattern.find(xml)
        if (match != null) {
            document.replaceString(match.range.first, match.range.last + 1, newNode)
            return true
        }
        // 不存在则插入到 </resources> 之前
        val insertPos = xml.indexOf("</resources>")
        if (insertPos == -1) {
            // 末尾追加
            document.insertString(xml.length, "\n\t$newNode\n")
            return true
        }
        document.insertString(insertPos, "\n\t$newNode\n")
        return true
    }

    /**
     * 从单个文件中删除指定 key 对应的 <string> 节点,包括所在行整行删除以保持排版整洁。
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
        // 扩展到所在整行(含行首缩进与行尾换行),保证删除后不留空白行
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
