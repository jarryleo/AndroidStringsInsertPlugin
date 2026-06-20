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
        val freezeColumnCount: Int? = null,
        val color: String? = null,
        val textColor: String? = null,
        val rowTextColors: List<List<String?>>? = null,
        val columnTextColors: List<String?>? = null
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
            FREEZE_COLUMNS,
            FILL_COLOR,
            CLEAR_COLOR,
            SET_TEXT_COLOR,
            CLEAR_TEXT_COLOR
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

    /**
     * 列出/搜索模块内的字符串 key(AI 主动发现能力)。
     *
     * @param module             目标模块名,省略时用 currentModule
     * @param pattern            可选正则;为空时退化为全量列表
     * @param limit              最大返回条数,默认 50
     * @param offset             分页偏移,默认 0
     * @param includeTranslations 是否在结果中带各语言当前翻译(默认 false,节省 token)
     */
    data class QueryKeys(
        val module: String?,
        val pattern: String?,
        val limit: Int?,
        val offset: Int?,
        val includeTranslations: Boolean
    ) : AiAction()

    /**
     * 读取指定 key 在模块所有语言的当前翻译(AI 精确读取能力)。
     * @param module 目标模块名,省略时用 currentModule
     * @param name   必填,字符串 key
     */
    data class ReadString(
        val module: String?,
        val name: String
    ) : AiAction()

    /**
     * 部分语言更新指定 key 的翻译(精准修改,不覆写未提供的语言)。
     * 与 [InsertStrings] 的「全量覆盖」语义不同,本动作只动 [translations] 中列出的语言。
     *
     * @param module       目标模块名,省略时用 currentModule
     * @param name         必填,字符串 key
     * @param translations 键为语言目录名(例: "values-zh-rTW"),值为新翻译
     */
    data class UpdateString(
        val module: String?,
        val name: String,
        val translations: Map<String, String>
    ) : AiAction()

    /** 文本匹配模式(枚举值与 StringsService / SheetsManager 的 TextMatchType 对齐)。 */
    enum class TextMatchType { EXACT, CONTAINS, REGEX }

    /**
     * strings.xml 反查:通过翻译文本查找 key。
     *
     * @param text           必填,要查找的翻译文本
     * @param module         限定模块;为 null 时搜索项目中所有模块
     * @param language       限定语言目录(例: values-zh-rTW);为 null 时搜索所有语言
     * @param matchType      匹配模式(默认 CONTAINS)
     * @param caseSensitive  是否区分大小写(默认 false)
     * @param limit          最大返回条数(默认 30)
     */
    data class FindKeysByText(
        val text: String,
        val module: String?,
        val language: String?,
        val matchType: TextMatchType,
        val caseSensitive: Boolean,
        val limit: Int
    ) : AiAction()

    /**
     * Google Sheets 反查:通过文本查找行。
     *
     * @param text            必填,要查找的文本
     * @param spreadsheetId   可选,默认用上下文 googleSheets 配置
     * @param sheetName       可选,默认用 defaultSheetName
     * @param column          限定列名(例: values-zh-rTW);为 null 时搜索所有列
     * @param matchType       匹配模式(默认 CONTAINS)
     * @param caseSensitive   是否区分大小写(默认 false)
     * @param limit           最大返回条数(默认 30)
     */
    data class FindRowsByText(
        val text: String,
        val spreadsheetId: String?,
        val sheetName: String?,
        val column: String?,
        val matchType: TextMatchType,
        val caseSensitive: Boolean,
        val limit: Int
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
