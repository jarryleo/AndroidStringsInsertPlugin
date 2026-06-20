package cn.jarryleo.insert_strings.ai

sealed class AiAction {
    data class InsertStrings(
        val module: String?,
        val name: String,
        val translations: Map<String, String>
    ) : AiAction()

    data class AskUser(
        val question: String,
        val options: List<String> = emptyList()
    ) : AiAction()

    /**
     * AI 请求加载某个工具的详细使用文档（按需加载机制）。
     * 系统会把 [tool] 对应的完整文档注入为 tool 消息，AI 据此继续返回实际执行动作。
     * @param tool 工具名，对应 AITranslator.TOOL_DOCS 的 key
     */
    data class LoadToolDoc(
        val tool: String
    ) : AiAction()

    data class SheetsOperation(
        val operation: Operation,
        val spreadsheetId: String?,
        val sheetName: String?,
        val range: String?,
        val key: String?,
        val rowNumber: Int?,
        val rows: List<List<String>>?,
        val columnIndex: Int?,
        val columnHeader: String?,
        val columnValues: List<String>?,
        val freezeRowCount: Int? = null,
        val freezeColumnCount: Int? = null
    ) : AiAction() {
        enum class Operation {
            READ,
            WRITE,
            SEARCH,
            LIST_SHEETS,
            INSERT_ROW,
            UPDATE_ROW,
            DELETE_ROW,
            APPEND_ROW,
            CLEAR_ROW,
            INSERT_COLUMN,
            APPEND_COLUMN,
            DELETE_COLUMN,
            CLEAR_COLUMN,
            UPDATE_COLUMN,
            CHECK_TRANSLATIONS,
            FIX_TRANSLATIONS,
            FREEZE_ROWS,
            FREEZE_COLUMNS
        }
    }

    /**
     * AI 声明任务完成。
     * 采用 function calling 后,这是唯一的「合法终止」信号 ——
     * 没有调用本动作就代表 AI 仍在执行,系统不会停止对话循环。
     *
     * @param summary 给用户看的最终总结
     * @param status 任务状态: success 完全达成 / partial 部分达成(目标被中断) / failed 执行失败
     * @param notes  可选的补充说明,例如「用户拒绝」或「缺少必要信息」
     */
    data class TaskComplete(
        val summary: String,
        val status: String,
        val notes: String? = null
    ) : AiAction()
}

data class AiReply(
    val reply: String,
    val actions: List<AiAction>,
    /**
     * 模型返回的原始 tool_calls,与 [actions] 按下标对齐(parse 失败的位置会被丢弃)。
     * UI 层在构造 tool result 消息时,需要用这里的 id 回传给 API。
     */
    val toolCalls: List<ToolCall> = emptyList()
)

/**
 * 批量翻译审查的单条问题/修正项。
 */
data class ReviewIssue(
    val row: Int,
    val col: Int,
    val current: String,
    val suggested: String,
    val reason: String
)

/**
 * 批量翻译修正项：整行新值。
 */
data class ReviewFix(
    val row: Int,
    val values: List<String>
)

/**
 * 批量翻译审查结果。
 */
data class ReviewResult(
    val issues: List<ReviewIssue>,
    val fixes: List<ReviewFix>,
    val summary: String
) {
    companion object {
        fun empty(summary: String) = ReviewResult(emptyList(), emptyList(), summary)
    }
}
