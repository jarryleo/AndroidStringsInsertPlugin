package cn.jarryleo.insert_strings.ui

import cn.jarryleo.insert_strings.ai.AiAction
import cn.jarryleo.insert_strings.xml.ContextManager
import cn.jarryleo.insert_strings.xml.KeySearchResult
import cn.jarryleo.insert_strings.xml.KeyTextSearchResult
import cn.jarryleo.insert_strings.xml.StringsService
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
     * 解析 "AI 显式 module / currentModule / 行数最多模块" 的优先级,得到本次操作的目标模块。
     * 处理了项目名误传、模块名拼写错误两种边界情况。
     */
    fun resolveTargetModule(
        actionModule: String?,
        currentModuleName: String?,
        moduleWithMostLinesName: String?
    ): String? {
        val context = ContextManager.getInstance(project)
        val contextInfo = context.contextInfo
        val projectName = contextInfo?.projectName
        val realModuleNames = contextInfo?.modules?.map { it.moduleName }?.toSet().orEmpty()
        val actionModuleSanitized = actionModule?.trim()?.takeIf { it.isNotBlank() }?.let { candidate ->
            if (candidate == projectName) {
                null
            } else if (candidate in realModuleNames) {
                candidate
            } else {
                context.resolveDisplayModuleName(candidate)
            }
        }
        return actionModuleSanitized
            ?: currentModuleName?.takeIf { it.isNotBlank() }
            ?: moduleWithMostLinesName?.takeIf { it.isNotBlank() }
    }

    fun runQueryKeys(action: AiAction.QueryKeys): String {
        val context = ContextManager.getInstance(project)
        val contextInfo = context.contextInfo
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
                includeTranslations = action.includeTranslations
            )
            totalMatched = results.size
        }
        return buildString {
            append("[工具执行结果] 类型:query_keys 模块:").append(moduleName)
            append(" pattern:").append(action.pattern ?: "(无)")
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
