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
}

data class AiReply(
    val reply: String,
    val actions: List<AiAction>
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
