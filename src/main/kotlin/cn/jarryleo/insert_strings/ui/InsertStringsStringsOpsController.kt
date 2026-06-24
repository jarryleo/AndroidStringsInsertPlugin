package cn.jarryleo.insert_strings.ui

import cn.jarryleo.insert_strings.ai.AiAction
import cn.jarryleo.insert_strings.xml.ContextManager
import cn.jarryleo.insert_strings.xml.KeySearchResult
import cn.jarryleo.insert_strings.xml.KeyTextSearchResult
import cn.jarryleo.insert_strings.xml.StringsService
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import javax.swing.SwingUtilities

/**
 * AI 驱动的 strings.xml 读/写/反查动作。
 *
 * 拆分理由:每个动作是独立的小功能,合并在一个类里读起来太长;抽离后 controller 只关心
 * 「把 action 跑一遍拿到结果文本」,适合按动作拆分。
 *
 * 跨模块写入的校验由上层 ChatDriver 在 processAiReply 阶段统一处理,本类不做。
 */
internal class InsertStringsStringsOpsController(
    private val state: ChatStateHolder,
) {

    private val project: Project get() = state.project

    /**
     * 解析目标模块的统一入口。
     *
     * 优先级(对应产品需求 1-3):
     * 1. [explicitModule] — AI 显式传入,代表用户**指定**的翻译模块(最高优先级)。
     * 2. [contextInfo] 中的 `recommendedDefaultModule` — 智能推荐默认模块
     *    (见 [cn.jarryleo.insert_strings.xml.ContextInfo.recommendedDefaultModule]):
     *    优先 currentModule,currentModule 语种/行数明显不足时退回语种最多+行数最多模块。
     * 3. 兜底 [fallbackModuleName] — 行数最多模块,保证一定拿到一个可用模块。
     *
     * 处理边界:
     * - AI 误传项目名(应作为显式 module 失败,降级到推荐)
     * - AI 传错拼写(通过 [ContextManager.resolveDisplayModuleName] 模糊匹配)
     * - currentModule 缺失(未打开 Android 文件)
     */
    fun resolveTargetModule(
        explicitModule: String?,
        currentModuleName: String?,
        fallbackModuleName: String?
    ): String? {
        val context = ContextManager.getInstance(project)
        val contextInfo = context.contextInfo
        val projectName = contextInfo?.projectName
        val realModuleNames = contextInfo?.modules?.map { it.moduleName }?.toSet().orEmpty()
        val explicitSanitized = explicitModule?.trim()?.takeIf { it.isNotBlank() }?.let { candidate ->
            if (candidate == projectName) {
                null
            } else if (candidate in realModuleNames) {
                candidate
            } else {
                context.resolveDisplayModuleName(candidate)
            }
        }
        if (explicitSanitized != null) return explicitSanitized
        return contextInfo?.recommendedDefaultModule?.moduleName
            ?: currentModuleName?.takeIf { it.isNotBlank() }
            ?: fallbackModuleName?.takeIf { it.isNotBlank() }
    }

    /**
     * 推断"用户从选中行里指定的翻译语种所在的模块"。
     *
     * 适用场景:用户从 AI 聊天输入框上方表格里**选中了一行翻译**(某条 [StringRow]),
     * 但没有在 AI 消息中显式说"插到 module X" — 这种情况下,我们需要猜测
     * "用户选择的那行翻译来自哪个模块",优先插入到该模块。
     *
     * 思路:扫描所有模块,看谁的 `xmlFiles[].filePath` 包含用户选中的语种,
     * 同时该文件有非空翻译。
     * 找不到(用户选中的语种是 AI 翻译产生/临时行)时返回 null,由上层走推荐默认模块。
     */
    fun guessModuleForSelectedTranslation(
        language: String,
        text: String
    ): String? {
        if (language.isBlank() || text.isBlank()) return null
        val context = ContextManager.getInstance(project)
        val contextInfo = context.contextInfo ?: return null
        contextInfo.modules.forEach { module ->
            module.xmlFiles.firstOrNull { it.language == language }?.let { fileInfo ->
                val key = context.scanModuleForKey(module.moduleName, "").firstOrNull()
                // 不能用 key="" 读,改用直接读文件
                if (containsExactText(fileInfo.filePath, text)) {
                    return module.moduleName
                }
            }
        }
        return null
    }

    private fun containsExactText(filePath: String, text: String): Boolean {
        val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .findFileByPath(filePath) ?: return false
        return try {
            vf.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().contains(text) }
        } catch (_: Exception) {
            false
        }
    }

    fun runQueryKeys(action: AiAction.QueryKeys): String {
        val context = ContextManager.getInstance(project)
        val contextInfo = context.contextInfo
        // 默认走「推荐默认模块」,与 insert_strings 一致:用户没指定时优先 currentModule,
        // 偏弱时退回项目最强模块(语种最多+行数最多)。这样 AI 不显式传 module 也能查到对的内容。
        val moduleName = resolveTargetModule(
            action.module,
            contextInfo?.currentModule?.moduleName,
            contextInfo?.moduleWithMostLines?.moduleName
        ) ?: return "[工具执行结果] 类型:query_keys 状态:失败 信息:未指定目标模块且无 currentModule"

        val results: List<KeySearchResult>
        val totalMatched: Int
        if (action.pattern.isNullOrBlank()) {
            val keys = StringsService.listKeys(project, moduleName, action.limit ?: 50, action.offset ?: 0)
            results = keys.map { KeySearchResult(it, emptyMap(), "") }
            totalMatched = keys.size
        } else {
            results = StringsService.searchKeys(
                project = project,
                moduleName = moduleName,
                pattern = action.pattern,
                limit = action.limit ?: 50,
                offset = action.offset ?: 0,
                includeTranslations = action.includeTranslations,
                searchIn = StringsService.SearchIn.valueOf(action.searchIn.name)
            )
            totalMatched = results.size
        }
        return buildString {
            append("[工具执行结果] 类型:query_keys 模块:").append(moduleName)
            append(" pattern:").append(action.pattern ?: "(无)")
            append(" searchIn:").append(action.searchIn.name.lowercase())
            append(" 状态:成功 命中:").append(totalMatched)
            if (results.isEmpty()) {
                appendLine("\n未找到匹配的 key。")
            } else {
                appendLine()
                results.forEachIndexed { idx, r ->
                    append(idx + 1).append(". ").append(r.key)
                    if (action.includeTranslations && r.translations.isNotEmpty()) {
                        appendLine()
                        r.translations.forEach { (lang, text) ->
                            append("    ").append(lang).append(": ")
                            appendLine(if (text.length > 80) text.take(80) + "…" else text)
                        }
                    } else {
                        appendLine()
                    }
                }
                if (action.pattern.isNullOrBlank() && totalMatched >= (action.limit ?: 50)) {
                    appendLine("… 可能还有更多 key,可用 offset 分页。")
                }
            }
        }
    }

    fun runReadString(action: AiAction.ReadString): String {
        val context = ContextManager.getInstance(project)
        val contextInfo = context.contextInfo
        val moduleName = resolveTargetModule(
            action.module,
            contextInfo?.currentModule?.moduleName,
            contextInfo?.moduleWithMostLines?.moduleName
        ) ?: return "[工具执行结果] 类型:read_string 状态:失败 信息:未指定目标模块"
        val result = StringsService.readKey(project, moduleName, action.name)
            ?: return "[工具执行结果] 类型:read_string key:${action.name} 模块:$moduleName 状态:失败 信息:模块不存在或没有 strings.xml"
        if (result.translations.isEmpty()) {
            return "[工具执行结果] 类型:read_string key:${action.name} 模块:$moduleName 状态:成功 信息:该 key 不存在"
        }
        return buildString {
            append("[工具执行结果] 类型:read_string key:").append(result.key)
            append(" 模块:").append(moduleName)
            append(" 状态:成功")
            appendLine()
            result.translations.forEach { (lang, text) ->
                append("  ").append(lang).append(": ")
                appendLine(if (text.isEmpty()) "(空)" else text)
            }
        }
    }

    fun runUpdateString(action: AiAction.UpdateString): String {
        val context = ContextManager.getInstance(project)
        val contextInfo = context.contextInfo
        val moduleName = resolveTargetModule(
            action.module,
            contextInfo?.currentModule?.moduleName,
            contextInfo?.moduleWithMostLines?.moduleName
        ) ?: return "[工具执行结果] 类型:update_string 状态:失败 信息:未指定目标模块"
        val results = StringsService.updateKey(project, moduleName, action.name, action.translations)
        val successCount = results.values.count { it == "成功" }
        val totalTarget = action.translations.size
        val updated = results.size
        val skipped = totalTarget - updated
        return buildString {
            append("[工具执行结果] 类型:update_string key:").append(action.name)
            append(" 模块:").append(moduleName)
            append(" 状态:").append(if (successCount == updated && updated == totalTarget) "成功" else "部分失败")
            append(" 成功:").append(successCount).append('/').append(totalTarget)
            if (skipped > 0) append(" 跳过(无该语言文件):").append(skipped)
            appendLine()
            results.forEach { (lang, msg) ->
                append("  ").append(lang).append(": ").appendLine(msg)
            }
        }
    }

    fun runDeleteString(action: AiAction.DeleteString): String {
        val context = ContextManager.getInstance(project)
        val contextInfo = context.contextInfo
        val moduleName = resolveTargetModule(
            action.module,
            contextInfo?.currentModule?.moduleName,
            contextInfo?.moduleWithMostLines?.moduleName
        ) ?: return "[工具执行结果] 类型:delete_string 状态:失败 信息:未指定目标模块"
        val results = StringsService.deleteKey(project, moduleName, action.name, action.languages)
        val successCount = results.values.count { it == "成功" }
        val notFoundCount = results.values.count { it == "未找到该 key" }
        val totalTarget = results.size
        val scope = if (action.languages.isEmpty()) "all-languages" else action.languages.joinToString(",")
        // 同步刷新 UI:若该 key 正在 keyEntries 中,以最新翻译内容覆盖
        SwingUtilities.invokeLater { refreshKeyEntryAfterDelete(moduleName, action.name) }
        return buildString {
            append("[工具执行结果] 类型:delete_string key:").append(action.name)
            append(" 模块:").append(moduleName)
            append(" 范围:").append(scope)
            append(" 状态:").append(if (successCount == totalTarget) "成功" else "部分失败")
            append(" 成功:").append(successCount).append('/').append(totalTarget)
            if (notFoundCount > 0) append(" 未找到:").append(notFoundCount)
            appendLine()
            results.forEach { (lang, msg) ->
                append("  ").append(lang).append(": ").appendLine(msg)
            }
        }
    }

    /**
     * 删除后刷新 UI:重新扫描目标 key 在模块中的最新翻译,
     * 若 keyEntries 中包含该 key 则就地替换(并按需刷新当前选中行的 rows)。
     */
    private fun refreshKeyEntryAfterDelete(moduleName: String, key: String) {
        val idx = state.keyEntries.indexOfFirst { it.key == key }
        if (idx < 0) return
        val updated = ContextManager.getInstance(project).scanModuleForKey(moduleName, key)
        state.keyEntries[idx] = cn.jarryleo.insert_strings.xml.KeyedStringsInfo(key, "", updated)
        if (state.selectedKeyIndex == idx) {
            state.updateRowsForSelectedKey()
        }
    }

    /**
     * 检查一组待插入的 insert_strings 动作是否会在目标模块的默认语言(values)上
     * 出现"已有 key 的翻译文本与待插入的 values 译文一致"的重复情况。
     *
     * 用于驱动层在写文件前给用户二次确认,避免重复入库。
     *
     * 规则:
     * 1) 比对 values 语言文件(默认英语),因为这是用户在 UI 上最直观看到的源语言。
     * 2) 若用户传入的 translations 没有 values,跳过该 action(后续系统会兜底)。
     * 3) 跨模块兜底:如果目标模块没有重复,也扫一下其它模块,告诉用户"该项目里已有同样的 key"。
     *    (因为同名翻译也常出现在不同模块,让用户决定是否复用。)
     *
     * @return 每个 action 的检测结果 + (有重复时)其它模块中的命中;空集合表示没有重复。
     */
    fun checkDuplicateKeys(
        actions: List<AiAction.InsertStrings>,
        targetModule: String
    ): Map<String, List<ExistingKeyMatch>> {
        if (actions.isEmpty()) return emptyMap()
        val contextMgr = ContextManager.getInstance(project)
        val contextInfo = contextMgr.contextInfo ?: return emptyMap()
        // 目标模块的 values 译文 -> [(key, text)] 索引
        val targetValuesIndex = buildValuesIndex(contextMgr, targetModule)

        // 跨模块扫描结果(用于"是否复用其它模块现有 key"提示)
        val crossModuleIndex: MutableMap<String, MutableList<Pair<String, String>>> = mutableMapOf()
        contextInfo.modules.forEach { module ->
            if (module.moduleName == targetModule) return@forEach
            buildValuesIndex(contextMgr, module.moduleName).forEach { (text, keyList) ->
                val list = crossModuleIndex.getOrPut(text) { mutableListOf() }
                list += module.moduleName to keyList.first()
            }
        }

        val out: MutableMap<String, List<ExistingKeyMatch>> = mutableMapOf()
        actions.forEach { action ->
            val textToCheck = action.translations[DEFAULT_LANGUAGE]?.trim().orEmpty()
            if (textToCheck.isEmpty()) return@forEach
            val matches = mutableListOf<ExistingKeyMatch>()
            // 目标模块内重复
            targetValuesIndex[textToCheck]?.forEach { key ->
                matches.add(
                    ExistingKeyMatch(
                        key = key,
                        module = targetModule,
                        language = DEFAULT_LANGUAGE,
                        existingText = textToCheck,
                    )
                )
            }
            // 跨模块重复(同翻译,不同模块的 key)
            crossModuleIndex[textToCheck]?.forEach { (otherModule, key) ->
                matches.add(
                    ExistingKeyMatch(
                        key = key,
                        module = otherModule,
                        language = DEFAULT_LANGUAGE,
                        existingText = textToCheck,
                    )
                )
            }
            if (matches.isNotEmpty()) {
                out[action.name] = matches
            }
        }
        return out
    }

    /**
     * 把指定模块的 values 语言文件的 (译文文本 -> key 列表) 索引出来,
     * 供查重使用。空文本不会建索引,避免误命中。
     */
    private fun buildValuesIndex(
        contextMgr: ContextManager,
        moduleName: String
    ): Map<String, List<String>> {
        val files = contextMgr.getModuleFiles(moduleName)
        val valuesFile = files.firstOrNull { it.first.name == DEFAULT_LANGUAGE }?.second ?: return emptyMap()
        return try {
            val doc = FileDocumentManager.getInstance().getDocument(valuesFile) ?: return emptyMap()
            val xml = doc.text
            parseAllStringNodes(xml)
                .filter { it.value.isNotEmpty() }
                .groupBy({ it.value.trim() }, { it.key })
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private data class ParsedStringNode(val key: String, val value: String)

    private fun parseAllStringNodes(xml: String): List<ParsedStringNode> {
        val regex = """<string\b([^>]*?)>([\s\S]*?)</string>""".toRegex()
        return regex.findAll(xml).map { match ->
            val attrs = match.groupValues[1]
            val key = """\bname\s*=\s*(['"])(.*?)\1""".toRegex()
                .find(attrs)?.groupValues?.get(2).orEmpty()
            ParsedStringNode(key, match.groupValues[2])
        }.filter { it.key.isNotEmpty() }.toList()
    }

    /**
     * 用 find_keys_by_text 同样的精确匹配能力,作为跨模块/跨语言去重的备选入口。
     * 供 AI 通过 find_keys_by_text 工具调用使用 — 这里仅暴露给 driver 内部,
     * 不参与 schema。
     */
    fun runFindKeysByText(action: AiAction.FindKeysByText): String {
        val matchType = mapToServiceMatchType(action.matchType)
        val results: List<KeyTextSearchResult> = try {
            StringsService.findKeysByText(
                project = project,
                text = action.text,
                moduleName = action.module,
                language = action.language,
                matchType = matchType,
                caseSensitive = action.caseSensitive,
                limit = action.limit
            )
        } catch (e: Exception) {
            return "[工具执行结果] 类型:find_keys_by_text 状态:失败 信息:${e.message ?: "unknown"}"
        }
        if (results.isEmpty()) {
            val scope = buildString {
                append("text:\"").append(action.text).append("\"")
                if (action.module != null) append(" module:").append(action.module)
                if (action.language != null) append(" language:").append(action.language)
                append(" match:").append(action.matchType.name.lowercase())
            }
            return "[工具执行结果] 类型:find_keys_by_text 状态:成功 命中:0 范围:$scope 未找到匹配 key"
        }
        return buildString {
            append("[工具执行结果] 类型:find_keys_by_text 状态:成功 命中:").append(results.size)
            append(" match:").append(action.matchType.name.lowercase())
            appendLine()
            results.forEachIndexed { idx, r ->
                append(idx + 1).append(". ")
                append("module=").append(r.module)
                append(" lang=").append(r.language)
                append(" key=").append(r.key)
                append(" text=\"").append(truncateForLog(r.text, 60)).append("\"")
                appendLine()
            }
            if (results.size >= action.limit) {
                appendLine("… 已达返回上限,可能还有更多匹配。")
            }
        }
    }

    private fun mapToServiceMatchType(type: AiAction.TextMatchType): StringsService.TextMatchType {
        return when (type) {
            AiAction.TextMatchType.EXACT -> StringsService.TextMatchType.EXACT
            AiAction.TextMatchType.CONTAINS -> StringsService.TextMatchType.CONTAINS
            AiAction.TextMatchType.REGEX -> StringsService.TextMatchType.REGEX
        }
    }
}

/**
 * 长日志裁剪:超过 max 截断并加省略号。
 */
internal fun truncateForLog(text: String, max: Int): String =
    if (text.length <= max) text else text.take(max) + "…"

private const val DEFAULT_LANGUAGE = "values"
