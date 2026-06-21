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
        val columnTextColors: List<String?>? = null,
        /**
         * 批量修改:仅当 [operation] == BATCH_MODIFY 时使用。
         * 一次工具调用执行多种修改(改值、填色、删行等),内部尽量合并为最少的 Google API 请求。
         */
        val batchEdits: List<BatchEdit>? = null
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
            CLEAR_TEXT_COLOR,
            /**
             * 批量修改:把多种操作(改值、填色、删行…)合并在一次工具调用里,
             * 后端会自动分组成最少的 Google API 请求,避免逐格操作触发工具调用次数上限。
             */
            BATCH_MODIFY
        }
    }

    /**
     * 批量修改的单个动作项,见 [SheetsOperation.Operation.BATCH_MODIFY]。
     *
     * 所有字段均按需使用(每个 type 只用其中一部分)。
     *
     * - [SET_VALUES]:    覆盖式写入任意矩形范围。 [range] + [rows](二维) 必填。
     * - [FILL_COLOR]:    在 [range] 上填充背景色, [color] 必填(hex 或命名色)。
     * - [CLEAR_COLOR]:   清除 [range] 的背景色。
     * - [SET_TEXT_COLOR]:在 [range] 上设置文字色, [color] 必填(hex 或命名色)。
     * - [CLEAR_TEXT_COLOR]:清除 [range] 的文字色。
     * - [UPDATE_ROWS]:   连续更新多行(从 [rowNumber] 起的 [rows] 行, 起点 1-based)。
     *                    [rowTextColors] / [rowBackgroundColors] 是与 [rows] 同形的二维矩阵,
     *                    null 元素表示该格不上色。
     * - [APPEND_ROWS]:   在工作表末尾追加多行, [rows] 二维数组, [rowTextColors] / [rowBackgroundColors] 可选。
     * - [INSERT_ROWS]:   在 [rowNumber] 位置插入多行(原行及之后下移), [rows] 二维数组。
     * - [DELETE_ROWS]:   删除 [rowNumbers] 指定的多个行(1-based 列表)。
     * - [CLEAR_ROWS]:    清空 [rowNumbers] 指定的多个行(保留行)。
     */
    data class BatchEdit(
        val type: BatchEditType,
        val range: String? = null,
        val rows: List<List<String>>? = null,
        val rowNumber: Int? = null,
        val rowNumbers: List<Int>? = null,
        val color: String? = null,
        val rowTextColors: List<List<String?>>? = null,
        val rowBackgroundColors: List<List<String?>>? = null
    )

    /** 批量修改的子操作类型。 */
    enum class BatchEditType {
        SET_VALUES,
        FILL_COLOR,
        CLEAR_COLOR,
        SET_TEXT_COLOR,
        CLEAR_TEXT_COLOR,
        UPDATE_ROWS,
        APPEND_ROWS,
        INSERT_ROWS,
        DELETE_ROWS,
        CLEAR_ROWS
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

    /**
     * 删除指定 key 的翻译。
     * - [languages] 为空列表时,删除 key 在所有语言的翻译(整 key 被移除)。
     * - [languages] 非空时,仅删除列表中指定语言的翻译,其他语言保持原样。
     *
     * @param module    目标模块名,省略时用 currentModule
     * @param name      必填,字符串 key
     * @param languages 要删除的语言目录名列表(例: ["values-fr", "values-zh-rCN"]);空表示全语言
     */
    data class DeleteString(
        val module: String?,
        val name: String,
        val languages: List<String>
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
     * 与 [actions] 严格按下标一一对应的原始 tool_calls(只有解析成功的 tool_call
     * 才会同时出现在 actions 和这里)。UI 层在构造 tool result 消息时,
     * 直接按 [actions] 的下标取这里的 id 即可,不会错位。
     */
    val toolCalls: List<ToolCall> = emptyList(),
    /**
     * 模型返回的 tool_calls 中,参数无法解析或工具名未知的子集。
     * 这些 tool_use 仍占据 assistant 消息的 tool_use 块,必须配对以 tool_result
     * 才能继续对话(否则 Anthropic 会 HTTP 400:
     * `tool_use.id 'xxx' was found without a corresponding tool_result block
     * immediately after`)。driver 层需要为这些 id 添加「解析失败」的 tool_result。
     */
    val failedToolCalls: List<ToolCall> = emptyList()
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
