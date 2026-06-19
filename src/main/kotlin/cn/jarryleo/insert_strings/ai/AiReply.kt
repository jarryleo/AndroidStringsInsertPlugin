package cn.jarryleo.insert_strings.ai

sealed class AiAction {
    data class InsertStrings(
        val module: String?,
        val name: String,
        val translations: Map<String, String>
    ) : AiAction()

    data class AskUser(
        val question: String
    ) : AiAction()

    data class SheetsOperation(
        val operation: Operation,
        val spreadsheetId: String?,
        val range: String?,
        val key: String?,
        val rows: List<List<String>>?
    ) : AiAction() {
        enum class Operation {
            WRITE, READ, SEARCH
        }
    }
}

data class AiReply(
    val reply: String,
    val actions: List<AiAction>
)
