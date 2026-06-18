package cn.jarryleo.insert_strings.ai.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject

object GoogleSheetsMcpTools {

    const val READ_RANGE = "read_sheet_range"
    const val WRITE_RANGE = "write_sheet_range"
    const val APPEND_ROWS = "append_sheet_rows"
    const val SEARCH = "search_sheet"

    fun buildReadRangeArguments(
        spreadsheetId: String,
        sheetName: String,
        range: String
    ): JsonObject = JsonObject().apply {
        addProperty("spreadsheetId", spreadsheetId)
        addProperty("sheet", sheetName)
        addProperty("range", range)
    }

    fun buildWriteRangeArguments(
        spreadsheetId: String,
        sheetName: String,
        range: String,
        values: List<List<String>>
    ): JsonObject = JsonObject().apply {
        addProperty("spreadsheetId", spreadsheetId)
        addProperty("sheet", sheetName)
        addProperty("range", range)
        add("values", values.toJsonArray())
    }

    fun buildAppendRowsArguments(
        spreadsheetId: String,
        sheetName: String,
        values: List<List<String>>
    ): JsonObject = JsonObject().apply {
        addProperty("spreadsheetId", spreadsheetId)
        addProperty("sheet", sheetName)
        add("values", values.toJsonArray())
    }

    fun buildSearchArguments(
        spreadsheetId: String,
        sheetName: String,
        query: String
    ): JsonObject = JsonObject().apply {
        addProperty("spreadsheetId", spreadsheetId)
        addProperty("sheet", sheetName)
        addProperty("query", query)
    }

    fun buildDefaultSpreadsheetArguments(
        spreadsheetId: String,
        sheetName: String
    ): JsonObject = JsonObject().apply {
        addProperty("spreadsheetId", spreadsheetId)
        addProperty("sheet", sheetName)
    }

    val toolDescriptions: String = """
可用 Google Sheets MCP 工具（参数均为 JSON 对象）：

1. $READ_RANGE
   读取 Google Sheet 指定范围的单元格数据。
   参数：{"spreadsheetId": "表格ID", "sheet": "工作表名称", "range": "A1:C10"}

2. $WRITE_RANGE
   将数据写入 Google Sheet 指定范围。
   参数：{"spreadsheetId": "表格ID", "sheet": "工作表名称", "range": "A1:C2", "values": [["key","en","zh"],["hello","Hello","你好"]]}

3. $APPEND_ROWS
   在 Google Sheet 末尾追加若干行。
   参数：{"spreadsheetId": "表格ID", "sheet": "工作表名称", "values": [["key","en","zh"],["hello","Hello","你好"]]}

4. $SEARCH
   在 Google Sheet 中搜索文本，返回匹配的行。
   参数：{"spreadsheetId": "表格ID", "sheet": "工作表名称", "query": "要搜索的文本"}

默认表格配置（如用户未在消息中指定，请使用这些默认值）：
- spreadsheetId: 由上下文 mcpSpreadsheetId 提供
- sheet: 由上下文 mcpSheetName 提供
""".trimIndent()

    private fun List<List<String>>.toJsonArray(): JsonArray = JsonArray().apply {
        this@toJsonArray.forEach { row ->
            add(JsonArray().apply {
                row.forEach { add(it) }
            })
        }
    }
}
