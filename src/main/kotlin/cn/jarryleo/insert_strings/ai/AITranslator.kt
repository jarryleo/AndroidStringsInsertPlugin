package cn.jarryleo.insert_strings.ai

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class ChatMessage(
    val role: String,
    val content: String,
)

object AITranslator {
    private const val SYSTEM_PROMPT =
        "你是一个专业的翻译，为开发安卓APP提供国际化翻译服务，我传给你需要翻译的文本，和目标语言的缩写代码，帮我翻译成目标语言，请返回对应的翻译结果文本，不需要额外的解释，请返回纯文本结果"
    private const val BATCH_TRANSLATE_SYSTEM_PROMPT =
        "你是一个专业的翻译，为开发安卓APP提供国际化翻译服务。我会给你多条文本和目标语言代码，请翻译成目标语言，并严格以 JSON 对象返回，key 为我给出的标识（原样保留），value 为对应翻译结果纯文本。不要 markdown 代码块，不要任何解释。"
    private const val CHAT_SYSTEM_PROMPT =
        """你是一个 Android 应用国际化字符串管理助手。你必须始终以纯 JSON 格式回复，不要添加 markdown 代码块标记，不要包含 JSON 之外的说明文字。

回复 JSON 结构如下：
{
  "reply": "给用户看的中文回复文本（简洁清晰）",
  "actions": [
    {
      "type": "insert_strings",
      "module": "目标模块名称（可选，如 app）",
      "name": "字符串key，使用snake_case",
      "translations": {
        "values": "默认语言文本",
        "values-zh-rCN": "简体中文翻译",
        "values-fr": "法语翻译"
      }
    },
    {
      "type": "sheets_operation",
      "operation": "list_sheets",
      "spreadsheetId": "Google表格ID（可选，默认使用设置中的表格ID）"
    },
    {
      "type": "sheets_operation",
      "operation": "read",
      "spreadsheetId": "Google表格ID（可选）",
      "sheetName": "工作表名称（可选，默认使用设置中的 defaultSheetName）",
      "range": "工作表范围（可选，例如 A1:Z1000；若不填则读取整个工作表）",
      "key": "要查找的字符串key（可选，为空则读取整个范围）"
    },
    {
      "type": "sheets_operation",
      "operation": "search",
      "spreadsheetId": "Google表格ID（可选）",
      "sheetName": "工作表名称（可选）",
      "key": "要查找的字符串key（必填）"
    },
    {
      "type": "sheets_operation",
      "operation": "write",
      "spreadsheetId": "Google表格ID（可选）",
      "range": "工作表范围，例如 Sheet1!A1:Z100",
      "rows": [["key", "values", "values-zh-rCN"], ["hello", "Hello", "你好"]]
    },
    {
      "type": "sheets_operation",
      "operation": "append_row",
      "spreadsheetId": "Google表格ID（可选）",
      "sheetName": "工作表名称（可选）",
      "rows": [["hello", "Hello", "你好"]]
    },
    {
      "type": "sheets_operation",
      "operation": "insert_row",
      "spreadsheetId": "Google表格ID（可选）",
      "sheetName": "工作表名称（可选）",
      "rowNumber": 5,
      "rows": [["hello", "Hello", "你好"]]
    },
    {
      "type": "sheets_operation",
      "operation": "update_row",
      "spreadsheetId": "Google表格ID（可选）",
      "sheetName": "工作表名称（可选）",
      "rowNumber": 5,
      "rows": [["hello", "Hello", "你好"]]
    },
    {
      "type": "sheets_operation",
      "operation": "delete_row",
      "spreadsheetId": "Google表格ID（可选）",
      "sheetName": "工作表名称（可选）",
      "rowNumber": 5
    },
    {
      "type": "sheets_operation",
      "operation": "clear_row",
      "spreadsheetId": "Google表格ID（可选）",
      "sheetName": "工作表名称（可选）",
      "rowNumber": 5
    },
    {
      "type": "sheets_operation",
      "operation": "insert_column",
      "spreadsheetId": "Google表格ID（可选）",
      "sheetName": "工作表名称（可选）",
      "columnIndex": 3,
      "columnHeader": "values-fr",
      "columnValues": ["values-fr", "Bonjour", "Au revoir"]
    },
    {
      "type": "sheets_operation",
      "operation": "append_column",
      "spreadsheetId": "Google表格ID（可选）",
      "sheetName": "工作表名称（可选）",
      "columnHeader": "values-ja",
      "columnValues": ["values-ja", "こんにちは", "さようなら"]
    },
    {
      "type": "sheets_operation",
      "operation": "update_column",
      "spreadsheetId": "Google表格ID（可选）",
      "sheetName": "工作表名称（可选）",
      "columnIndex": 3,
      "columnValues": ["values-fr", "Bonjour", "Au revoir"]
    },
    {
      "type": "sheets_operation",
      "operation": "delete_column",
      "spreadsheetId": "Google表格ID（可选）",
      "sheetName": "工作表名称（可选）",
      "columnIndex": 3
    },
    {
      "type": "sheets_operation",
      "operation": "clear_column",
      "spreadsheetId": "Google表格ID（可选）",
      "sheetName": "工作表名称（可选）",
      "columnIndex": 3
    },
    {
      "type": "sheets_operation",
      "operation": "check_translations",
      "spreadsheetId": "Google表格ID（可选）",
      "sheetName": "工作表名称（可选）",
      "range": "工作表范围（可选，例如 A1:Z1000；不填则检查整个工作表）"
    },
    {
      "type": "sheets_operation",
      "operation": "fix_translations",
      "spreadsheetId": "Google表格ID（可选）",
      "sheetName": "工作表名称（可选）",
      "range": "工作表范围（可选，不填则修正整个工作表）"
    }
  ]
}

规则：
1. 当用户要求插入、添加、写入、修改翻译时，必须在 `actions` 中返回一个或多个 `insert_strings` 动作。
2. `name` 是 Android strings.xml 中的 string name，使用 snake_case。
3. `translations` 的键必须对应上下文 `availableLanguages` 中列出的所有语言目录名，如 values、values-zh-rCN、values-fr 等，不能遗漏任何一种语言。
4. `module` 必须是上下文中 `modules` 列表里的 `moduleName`（例如 app），不要使用 projectName 或 originalModuleName。
5. 如果上下文提供了 currentKeys（数组，每项含 key 和 translations），优先使用其中第一个 key 作为 `name`；用户选中了多个 key 时，可以为每个 key 分别返回一个 insert_strings 动作。
6. 如果上下文提供了 currentModule，默认将翻译插入/修改到 currentModule.moduleName，不需要再询问用户。
7. 如果上下文没有 currentModule，且用户没有明确指定模块，请询问用户是否插入到翻译行数最多的模块（moduleWithMostLines.moduleName），不要直接返回 insert_strings 动作。
8. 可以同时返回多个 insert_strings 动作来插入多个字符串。
9. 翻译内容中如需使用 XML 特殊字符，请转义：&amp; &lt; &gt; &quot; &apos;。
10. 如果用户只是普通聊天或询问，不需要返回 actions。
11. 当你返回了包含 sheets_operation 的 actions 后，系统会自动执行这些操作并将结果以「工具执行结果」的格式发回给你。请根据执行结果继续回复用户，说明操作是否成功以及读取到的内容。不要回复「请稍候」之类的等待语，因为操作已经执行完毕。
12. 收到工具执行结果后，只需在 reply 中总结结果即可，不要再重复返回已执行过的 actions。
13. 如果工具执行结果显示失败，请在 reply 中告知用户失败原因，并建议可能的解决方案（如配置代理、检查表格ID等）。

## Google Sheets 操作详细说明

所有 `sheets_operation` 动作的 `spreadsheetId` 与 `sheetName` 字段均可省略，省略时默认使用上下文 `googleSheets.defaultSpreadsheetId` 与 `googleSheets.defaultSheetName`。若上下文 `googleSheets.availableSheets` 不为空，请只在该列表中的工作表名中选择 `sheetName`。

可用 operation 取值及含义：

- `list_sheets`：列出表格文件中所有工作表名称及尺寸。不需要 `range`/`key`/`rows`/`rowNumber`/`sheetName`。
  使用场景：用户问「表格里有哪些 sheet」、「有哪些工作表」、「看一下表格结构」，或当你不确定目标工作表是否存在时。

- `read`：读取数据。可指定 `sheetName`/`range` 精确读取某个区域；可指定 `key` 在第一列中查找匹配行。若全部省略，则读取默认工作表的全部内容。
  使用场景：用户问「读一下表格」、「表格里有什么」、「看一下 hello 这个 key 的翻译」。

- `search`：在指定工作表（或默认工作表）的第一列中查找 `key` 匹配的行，返回 1-based 行号与整行内容。`key` 必填。
  使用场景：需要精确定位某 key 所在行号，以便后续 update_row / delete_row / insert_row 操作。

- `write`：向指定 `range` 整体写入数据（覆盖该范围内已有内容）。`rows` 第一行通常为表头。适用于初始化表头或重写某个完整区域。
  注意：`write` 会覆盖范围内所有单元格，请谨慎使用，不要覆盖已有翻译。

- `append_row`：在工作表末尾追加一行。`rows` 为单行数据的二维数组（外层只有一个元素）。
  使用场景：插入一条新翻译到表格末尾，最常用、最安全的写入方式。

- `insert_row`：在 `rowNumber` 指定的行号位置插入一个新行，原该行及之后的数据整体下移。`rowNumber` 为 1-based。
  使用场景：用户要求「把这条翻译插到第 5 行」、「在表头下面插入一行」等需要在特定位置插入的场景。
  `rows` 为单行数据的二维数组。

- `update_row`：精确更新 `rowNumber` 指定的某一行内容，不影响其它行。`rowNumber` 为 1-based。
  使用场景：用户要求「修改第 5 行的翻译」、「把 hello 这一行的中文改成你好」等。建议先 search 定位行号再 update_row。

- `delete_row`：删除 `rowNumber` 指定的行，后续行整体上移。`rowNumber` 为 1-based。不需要 `rows`。
  使用场景：用户要求「删掉第 5 行」、「删除 hello 这条翻译在表格里的行」等。

- `clear_row`：清空 `rowNumber` 指定行的内容但保留空行，不改变行数。`rowNumber` 为 1-based。不需要 `rows`。
  使用场景：用户要求「清空第 5 行」但不想删除整行时。

### 列操作（需要用户确认）

下列操作会改变表格列结构，系统在执行前会弹出确认对话框请用户允许。若用户拒绝，系统会返回失败结果，请在 reply 中告知用户操作已被取消。

- `insert_column`：在 `columnIndex`（1-based）位置插入一个新列，原该列及之后整体右移。
  字段：`columnIndex`（1-based 列号，必填）、`columnHeader`（新列表头名，可选，若提供会作为 columnValues 的第一个元素被覆盖）、`columnValues`（一维数组，第一个元素为表头，其余为各行的值，必填）。
  使用场景：用户要求「在第 3 列前面插入一列法语翻译」、「在 key 列后插入 values-fr 列」。

- `append_column`：在工作表末尾追加一个新列。
  字段：`columnHeader`（新列表头名，建议填写，作为列标识）、`columnValues`（一维数组，第一个元素为表头，其余为各行值，必填）。
  使用场景：用户要求「在表格最后新增一列日语翻译」、「加一列 values-ja」。
  注意：`columnValues[0]` 应与 `columnHeader` 一致，作为表头行写入。

- `update_column`：精确更新 `columnIndex` 指定的某列内容，不影响其它列。
  字段：`columnIndex`（1-based，必填）、`columnValues`（一维数组，第一个元素为表头，必填）。
  使用场景：用户要求「把第 3 列法语全部重新翻译」、「更新 values-fr 这一列」。

- `delete_column`：删除 `columnIndex` 指定的列，后续列整体左移。不需要 `columnValues`。
  字段：`columnIndex`（1-based，必填）。
  使用场景：用户要求「删掉第 3 列」、「删除 values-fr 这一列」。

- `clear_column`：清空 `columnIndex` 指定列的内容但保留空列，不改变列数。不需要 `columnValues`。
  字段：`columnIndex`（1-based，必填）。
  使用场景：用户要求「清空第 3 列内容」但不想删除整列时。

### 批量翻译审查/修正（最小 token，不会溢出）

这两个操作由系统在本地分批调用 AI 完成全表检查或修正，**主聊天只收到最终总结报告**，不会把整张表塞进上下文，因此 token 不会溢出。你只需要返回这一个动作，系统会自动读取整表、分批处理、汇总报告并回传给你。

- `check_translations`：检查整个工作表（或指定 `range`）所有行的翻译质量，只报告有问题的条目并生成总结报告。
  字段：`sheetName`（可选）、`range`（可选，不填则检查整个工作表）。
  使用场景：用户要求「帮我检查表格全部的翻译」、「检查所有翻译有没有错」、「审查一下表格」。
  系统返回的工具结果即为审查总结报告（包含问题清单与统计），请据此在 reply 中向用户汇报。

- `fix_translations`：检查并直接修正整个工作表（或指定 `range`）的翻译，自动写入修正后的行，并生成总结报告。
  字段：`sheetName`（可选）、`range`（可选，不填则修正整个工作表）。
  使用场景：用户要求「帮我修正完善表格全部翻译」、「补全并修正所有翻译」、「修复表格翻译问题」。
  系统会自动执行修正写入并返回总结报告（含修正行数），请据此在 reply 中向用户汇报。不要在 actions 中再重复返回这些行的 update_row 动作。

### 典型工作流

1. **插入新翻译到表格末尾**（最常见）：
   - 先 `read` 读取表头与现有数据，确认列顺序与末尾位置。
   - 再 `append_row` 追加新行，列顺序与表头一致。

2. **修改已有 key 的翻译**：
   - 先 `search` 用 key 定位行号 N。
   - 再 `update_row` 传入 rowNumber=N 与新的整行数据。

3. **在某个 key 前插入新行**：
   - 先 `search` 定位该 key 的行号 N。
   - 再 `insert_row` 传入 rowNumber=N，新行会插到原第 N 行之前，原第 N 行及之后下移。

4. **删除某个 key 的翻译行**：
   - 先 `search` 定位行号 N。
   - 再 `delete_row` 传入 rowNumber=N。

5. **批量查看表格结构**：
   - `list_sheets` 查看所有工作表。
   - 然后 `read` 指定 `sheetName` 读取目标工作表内容。

6. **新增一列语言翻译**：
   - 先 `read` 表头确认现有列顺序与列数。
   - 若加在末尾用 `append_column`；若插在某列前用 `insert_column` 指定 `columnIndex`。
   - `columnValues` 第一个元素为表头（如 values-fr），其余为各 key 对应的翻译，行数应与现有数据行一致，缺位填空字符串。

7. **删除/清空一列**：
   - 先 `read` 表头定位目标列是第几列（1-based columnIndex）。
   - 删除用 `delete_column`，只清空保留列用 `clear_column`。

8. **检查/修正全表翻译**（最省 token）：
   - 检查：直接返回 `check_translations`，系统分批审查全表并返回总结报告。
   - 修正：直接返回 `fix_translations`，系统分批审查并自动写入修正，再返回总结报告。
   - 不要用 `read` 把整表读进上下文再逐行分析，那会浪费大量 token 且可能溢出。

### 安全约束

a. **禁止覆盖其它翻译行**：如果只是插入新翻译，优先使用 `append_row` 在末尾追加，或先用 `read` 读取现有内容找到第一个空行再用 `insert_row` 插入，绝不覆盖已有数据的行。
b. **禁止擅自新增语言列**：除非用户明确要求增删列，否则不要返回 insert_column/append_column/delete_column/clear_column/update_column 动作。新增列前应先 `read` 表头确认列数与顺序。
c. **禁止擅自变动表格列结构**：除非用户明确要求增删列或改变列顺序，否则表头行必须与表格现有表头保持完全一致，不得修改、重排或新增列。
d. 写入前应先读取表头，将翻译的 key 与各语言列按现有列名对齐；若某语言列在安卓国际化中不存在对应的翻译，该列填空字符串。
e. 修改、删除、清空某行前，必须先用 `search` 精确定位行号，避免误操作其它行。
f. 如果上下文 `googleSheets.configured` 为 false，或 `defaultSpreadsheetId` 为空，请在 reply 中提示用户先在设置中配置 Google Sheets，不要返回 sheets_operation 动作。
g. 当上下文 `googleSheets.availableSheets` 中包含工作表名称列表时，`sheetName` 必须从该列表中选取；若用户提到的 sheet 不在列表中，请先 `list_sheets` 确认，或提示用户该工作表不存在。
h. 当用户要求「检查全部翻译」或「修正全部翻译」时，优先返回 `check_translations` 或 `fix_translations`，而不是 `read` 整表后逐行处理，以避免 token 溢出。"""

    private const val ANTHROPIC_VERSION = "2023-06-01"
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    /**
     * 批量翻译审查的专用系统提示词（独立于主聊天，最小化 token 使用）。
     * 只要求模型输出有问题的行，避免回吐整张表。
     */
    private const val REVIEW_SYSTEM_PROMPT =
        """你是 Android 国际化翻译质量审查助手。我会给你一段表格数据（TSV 风格，竖线分隔，第一行为表头，第一列为字符串 key，其余列为各语言翻译）。
请检查翻译的准确性、一致性、完整性（缺失翻译）、占位符 (%s/%d 等) 与 XML 转义是否正确。
只返回纯 JSON，不要 markdown 代码块，不要任何额外说明。

检查模式（check）返回格式：
{"issues":[{"row":<表格中该数据的行号，整数>,"col":<列序号，从0开始，0=key列>,"current":"<当前值>","suggested":"<建议值>","reason":"<简短中文原因>"}],"summary":"<一句话中文总结，说明共检查多少行、发现多少处问题及主要类型>"}
- 只列出有问题的条目，没有问题就返回空 issues 数组。
- row 必须是该行数据片段开头标注的行号，原样回填，不要重新编号。
- col 为该列在整行中的 0-based 索引（含 key 列）。

修正模式（fix）返回格式：
{"fixes":[{"row":<行号>,"values":[<整行所有列的新值，顺序与列数必须与表头一致>]}],"summary":"<一句话中文总结，说明共修正多少行>"}
- 只返回需要修正的行，每行 values 必须包含全部列（与表头列数相同）。
- 没有问题的行不要返回。row 为该行数据片段开头标注的行号。
- 不要修改 key 列的值，原样保留。"""

    @JvmStatic
    fun translate(code: String, text: String): String {
        val settings = AiSettingsService.getInstance().state
        val protocol = AiProtocol.fromName(settings.protocol)
        val endpoint = AiEndpoint.completeChatEndpoint(settings.url, protocol)
        val model = settings.model.trim()
        val apiKey = settings.apiKey.trim()

        if (settings.url.isBlank()) return "Please configure the AI URL first."
        if (apiKey.isBlank()) return "Please configure the AI API key first."
        if (model.isBlank()) return "Please configure the AI model first."

        return runCatching {
            val body = when (protocol) {
                AiProtocol.OPENAI -> openAiTranslateBody(model, code, text)
                AiProtocol.ANTHROPIC -> anthropicTranslateBody(model, code, text)
            }
            val request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .applyAuthHeaders(protocol, apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                throw IllegalStateException("HTTP ${response.statusCode()}: ${response.body().limitForMessage()}")
            }
            when (protocol) {
                AiProtocol.OPENAI -> parseOpenAiText(response.body())
                AiProtocol.ANTHROPIC -> parseAnthropicText(response.body())
            }
        }.getOrElse {
            it.message ?: "AI translate failed."
        }
    }

    /**
     * 批量翻译多个 key 的源文本到同一目标语言，单次 AI 调用完成。
     * 返回 key -> 翻译结果 的映射；翻译失败或未返回的 key 回退为空串。
     */
    fun translateBatch(code: String, items: List<Pair<String, String>>): Map<String, String> {
        if (items.isEmpty()) return emptyMap()
        if (items.size == 1) {
            val (key, text) = items.first()
            return mapOf(key to translate(code, text))
        }
        val settings = AiSettingsService.getInstance().state
        val protocol = AiProtocol.fromName(settings.protocol)
        val endpoint = AiEndpoint.completeChatEndpoint(settings.url, protocol)
        val model = settings.model.trim()
        val apiKey = settings.apiKey.trim()

        if (settings.url.isBlank() || apiKey.isBlank() || model.isBlank()) {
            return items.associate { (k, _) -> k to "" }
        }

        val userContent = buildString {
            append("目标语言代码：").append(code).append("\n")
            append("请将以下每条文本翻译为目标语言，按 JSON 对象返回，key 保持原样，value 为翻译结果，不要任何额外说明或 markdown：\n")
            items.forEach { (key, text) ->
                append(key).append("：").append(text).append("\n")
            }
        }

        return runCatching {
            val body = when (protocol) {
                AiProtocol.OPENAI -> openAiBatchTranslateBody(model, userContent)
                AiProtocol.ANTHROPIC -> anthropicBatchTranslateBody(model, userContent)
            }
            val request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .applyAuthHeaders(protocol, apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                throw IllegalStateException("HTTP ${response.statusCode()}: ${response.body().limitForMessage()}")
            }
            val responseText = when (protocol) {
                AiProtocol.OPENAI -> parseOpenAiText(response.body())
                AiProtocol.ANTHROPIC -> parseAnthropicText(response.body())
            }
            parseBatchTranslateResult(responseText, items)
        }.getOrElse {
            items.associate { (k, _) -> k to "" }
        }
    }

    private fun parseBatchTranslateResult(responseText: String, items: List<Pair<String, String>>): Map<String, String> {
        val cleaned = responseText.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        return try {
            val root = JsonParser.parseString(cleaned).asJsonObject
            items.associate { (key, _) ->
                key to (root.get(key)?.extractText() ?: "")
            }
        } catch (e: Exception) {
            items.associate { (key, _) -> key to "" }
        }
    }

    private fun openAiBatchTranslateBody(model: String, userContent: String): String {
        val root = JsonObject().apply {
            addProperty("model", model)
            addProperty("temperature", 0)
            add(
                "messages",
                JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("role", "system")
                        addProperty("content", BATCH_TRANSLATE_SYSTEM_PROMPT)
                    })
                    add(JsonObject().apply {
                        addProperty("role", "user")
                        addProperty("content", userContent)
                    })
                }
            )
        }
        return root.toString()
    }

    private fun anthropicBatchTranslateBody(model: String, userContent: String): String {
        val root = JsonObject().apply {
            addProperty("model", model)
            addProperty("system", BATCH_TRANSLATE_SYSTEM_PROMPT)
            addProperty("max_tokens", 4096)
            add(
                "messages",
                JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("role", "user")
                        addProperty("content", userContent)
                    })
                }
            )
        }
        return root.toString()
    }

    @JvmStatic
    fun chat(messages: List<ChatMessage>, context: String = ""): AiReply {
        val settings = AiSettingsService.getInstance().state
        val protocol = AiProtocol.fromName(settings.protocol)
        val endpoint = AiEndpoint.completeChatEndpoint(settings.url, protocol)
        val model = settings.model.trim()
        val apiKey = settings.apiKey.trim()

        if (settings.url.isBlank()) return AiReply("Please configure the AI URL first.", emptyList())
        if (apiKey.isBlank()) return AiReply("Please configure the AI API key first.", emptyList())
        if (model.isBlank()) return AiReply("Please configure the AI model first.", emptyList())

        return runCatching {
            val body = when (protocol) {
                AiProtocol.OPENAI -> openAiChatBody(model, messages, context)
                AiProtocol.ANTHROPIC -> anthropicChatBody(model, messages, context)
            }
            val request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .applyAuthHeaders(protocol, apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                throw IllegalStateException("HTTP ${response.statusCode()}: ${response.body().limitForMessage()}")
            }
            val responseText = when (protocol) {
                AiProtocol.OPENAI -> parseOpenAiText(response.body())
                AiProtocol.ANTHROPIC -> parseAnthropicText(response.body())
            }
            parseAiReply(responseText)
        }.getOrElse {
            AiReply(it.message ?: "AI chat failed.", emptyList())
        }
    }

    /**
     * 批量翻译审查/修正。使用独立的极简提示词与消息列表，不携带主聊天历史，
     * 以最小 token 完成对一段表格数据的检查或修正。
     *
     * @param header 表头行（含 key 列）。
     * @param batchRows 该批数据行（不含表头），每行包含 key 列在内的所有列。
     * @param startRowNumber batchRows 第一行在原表格中的 1-based 行号（含表头偏移）。
     * @param mode "check" 只报告问题；"fix" 返回需要修正的整行新值。
     * @param maxCellChars 单元格内容截断长度，控制 token。
     */
    fun reviewTranslations(
        header: List<String>,
        batchRows: List<List<String>>,
        startRowNumber: Int,
        mode: String,
        maxCellChars: Int = 200
    ): ReviewResult {
        if (header.isEmpty() || batchRows.isEmpty()) {
            return ReviewResult.empty("空数据，无需检查。")
        }
        val settings = AiSettingsService.getInstance().state
        val protocol = AiProtocol.fromName(settings.protocol)
        val endpoint = AiEndpoint.completeChatEndpoint(settings.url, protocol)
        val model = settings.model.trim()
        val apiKey = settings.apiKey.trim()

        if (settings.url.isBlank() || apiKey.isBlank() || model.isBlank()) {
            return ReviewResult.empty("AI 未配置，无法审查。")
        }

        val compactData = buildCompactReviewData(header, batchRows, startRowNumber, maxCellChars)
        val modeDesc = if (mode == "fix") "修正模式（fix）" else "检查模式（check）"
        val userContent = "模式：$modeDesc\n表头列数：${header.size}\n数据行数：${batchRows.size}\n\n$compactData"

        return runCatching {
            val body = when (protocol) {
                AiProtocol.OPENAI -> openAiReviewBody(model, userContent)
                AiProtocol.ANTHROPIC -> anthropicReviewBody(model, userContent)
            }
            val request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .applyAuthHeaders(protocol, apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                throw IllegalStateException("HTTP ${response.statusCode()}: ${response.body().limitForMessage()}")
            }
            val responseText = when (protocol) {
                AiProtocol.OPENAI -> parseOpenAiText(response.body())
                AiProtocol.ANTHROPIC -> parseAnthropicText(response.body())
            }
            parseReviewResult(responseText)
        }.getOrElse {
            ReviewResult.empty("审查请求失败：${it.message ?: "unknown"}")
        }
    }

    private fun buildCompactReviewData(
        header: List<String>,
        batchRows: List<List<String>>,
        startRowNumber: Int,
        maxCellChars: Int
    ): String {
        val sb = StringBuilder()
        sb.append("row|").append(header.joinToString("|") { truncate(it, maxCellChars) }).append("\n")
        batchRows.forEachIndexed { index, row ->
            val rowNumber = startRowNumber + index
            sb.append(rowNumber).append("|")
                .append(header.indices.joinToString("|") { colIdx ->
                    truncate(row.getOrNull(colIdx) ?: "", maxCellChars)
                })
                .append("\n")
        }
        return sb.toString()
    }

    private fun truncate(text: String, maxChars: Int): String {
        return if (text.length <= maxChars) text else text.take(maxChars) + "…"
    }

    private fun openAiReviewBody(model: String, userContent: String): String {
        val root = JsonObject().apply {
            addProperty("model", model)
            addProperty("temperature", 0)
            add(
                "messages",
                JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("role", "system")
                        addProperty("content", REVIEW_SYSTEM_PROMPT)
                    })
                    add(JsonObject().apply {
                        addProperty("role", "user")
                        addProperty("content", userContent)
                    })
                }
            )
        }
        return root.toString()
    }

    private fun anthropicReviewBody(model: String, userContent: String): String {
        val root = JsonObject().apply {
            addProperty("model", model)
            addProperty("system", REVIEW_SYSTEM_PROMPT)
            addProperty("max_tokens", 2048)
            add(
                "messages",
                JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("role", "user")
                        addProperty("content", userContent)
                    })
                }
            )
        }
        return root.toString()
    }

    private fun parseReviewResult(responseText: String): ReviewResult {
        val cleaned = responseText.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        return try {
            val root = JsonParser.parseString(cleaned).asJsonObject
            val summary = root.get("summary")?.extractText() ?: ""
            val issues = root.getAsJsonArray("issues")?.mapNotNull { el ->
                if (!el.isJsonObject) return@mapNotNull null
                val o = el.asJsonObject
                ReviewIssue(
                    row = o.get("row")?.let { runCatching { it.asInt }.getOrNull() } ?: -1,
                    col = o.get("col")?.let { runCatching { it.asInt }.getOrNull() } ?: -1,
                    current = o.get("current")?.extractText().orEmpty(),
                    suggested = o.get("suggested")?.extractText().orEmpty(),
                    reason = o.get("reason")?.extractText().orEmpty()
                )
            }?.filter { it.row > 0 } ?: emptyList()
            val fixes = root.getAsJsonArray("fixes")?.mapNotNull { el ->
                if (!el.isJsonObject) return@mapNotNull null
                val o = el.asJsonObject
                val row = o.get("row")?.let { runCatching { it.asInt }.getOrNull() } ?: -1
                if (row <= 0) return@mapNotNull null
                val valuesArray = o.getAsJsonArray("values") ?: return@mapNotNull null
                ReviewFix(row, valuesArray.map { it?.extractText().orEmpty() })
            } ?: emptyList()
            ReviewResult(issues, fixes, summary)
        } catch (e: Exception) {
            ReviewResult.empty("解析审查结果失败：${responseText.take(200)}")
        }
    }

    fun fetchModels(rawUrl: String, protocol: AiProtocol, apiKey: String): Result<List<String>> {
        if (rawUrl.isBlank()) return Result.failure(IllegalArgumentException("Please configure the AI URL first."))
        if (apiKey.isBlank()) return Result.failure(IllegalArgumentException("Please configure the AI API key first."))

        return runCatching {
            val endpoint = AiEndpoint.completeModelsEndpoint(rawUrl, protocol)
            val request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json")
                .applyAuthHeaders(protocol, apiKey.trim())
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                throw IllegalStateException("HTTP ${response.statusCode()}: ${response.body().limitForMessage()}")
            }
            parseModelIds(response.body()).ifEmpty {
                throw IllegalStateException("No model id found in response.")
            }
        }
    }

    private fun HttpRequest.Builder.applyAuthHeaders(protocol: AiProtocol, apiKey: String): HttpRequest.Builder {
        return when (protocol) {
            AiProtocol.OPENAI -> header("Authorization", "Bearer $apiKey")
            AiProtocol.ANTHROPIC -> header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
        }
    }

    private fun openAiChatBody(model: String, messages: List<ChatMessage>, context: String): String {
        val root = JsonObject().apply {
            addProperty("model", model)
            add(
                "messages",
                JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("role", "system")
                        addProperty("content", CHAT_SYSTEM_PROMPT)
                    })
                    if (context.isNotBlank()) {
                        add(JsonObject().apply {
                            addProperty("role", "system")
                            addProperty("content", "## 当前项目上下文（JSON）\n$context")
                        })
                    }
                    messages.forEachIndexed { index, msg ->
                        val content = if (index == messages.lastIndex && msg.role == "user" && context.isNotBlank()) {
                            buildUserJsonMessage(msg.content, context)
                        } else {
                            msg.content
                        }
                        add(JsonObject().apply {
                            addProperty("role", if (msg.role == "tool") "user" else msg.role)
                            addProperty("content", content)
                        })
                    }
                }
            )
        }
        return root.toString()
    }

    private fun anthropicChatBody(model: String, messages: List<ChatMessage>, context: String): String {
        val root = JsonObject().apply {
            addProperty("model", model)
            addProperty("system", CHAT_SYSTEM_PROMPT + if (context.isNotBlank()) "\n## 当前项目上下文（JSON）\n$context" else "")
            addProperty("max_tokens", 4096)
            add(
                "messages",
                JsonArray().apply {
                    messages.forEachIndexed { index, msg ->
                        val content = if (index == messages.lastIndex && msg.role == "user" && context.isNotBlank()) {
                            buildUserJsonMessage(msg.content, context)
                        } else {
                            msg.content
                        }
                        add(JsonObject().apply {
                            addProperty("role", if (msg.role == "tool") "user" else msg.role)
                            addProperty("content", content)
                        })
                    }
                }
            )
        }
        return root.toString()
    }

    private fun buildUserJsonMessage(message: String, context: String): String {
        return try {
            val contextObj = JsonParser.parseString(context).asJsonObject
            contextObj.addProperty("userMessage", message)
            contextObj.toString()
        } catch (e: Exception) {
            JsonObject().apply {
                addProperty("userMessage", message)
                addProperty("context", context)
            }.toString()
        }
    }

    private fun parseAiReply(responseText: String): AiReply {
        val cleaned = responseText.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        return try {
            val root = JsonParser.parseString(cleaned).asJsonObject
            val reply = root.get("reply")?.extractText().orEmpty()
            val actions = root.getAsJsonArray("actions")?.mapNotNull { parseAction(it) } ?: emptyList()
            AiReply(reply, actions)
        } catch (e: Exception) {
            AiReply(responseText, emptyList())
        }
    }

    private fun parseAction(element: JsonElement): AiAction? {
        if (!element.isJsonObject) return null
        val obj = element.asJsonObject
        val type = obj.get("type")?.asString ?: return null
        return when (type) {
            "insert_strings" -> {
                val name = obj.get("name")?.asString?.trim() ?: return null
                val module = obj.get("module")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                val translationsObj = obj.getAsJsonObject("translations") ?: return null
                val translations = translationsObj.entrySet().associate { it.key to it.value.extractText() }
                if (name.isNotEmpty() && translations.isNotEmpty()) {
                    AiAction.InsertStrings(module, name, translations)
                } else null
            }
            "ask_user" -> {
                val question = obj.get("question")?.asString ?: return null
                AiAction.AskUser(question)
            }
            "sheets_operation" -> {
                val operationText = obj.get("operation")?.asString ?: return null
                val operation = runCatching {
                    AiAction.SheetsOperation.Operation.valueOf(operationText.uppercase())
                }.getOrNull() ?: return null
                val spreadsheetId = obj.get("spreadsheetId")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                val sheetName = obj.get("sheetName")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                val range = obj.get("range")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                val key = obj.get("key")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                val rowNumber = obj.get("rowNumber")?.let {
                    runCatching { it.asInt }.getOrNull()
                }?.takeIf { it != null && it > 0 }
                val rowsArray = obj.getAsJsonArray("rows")
                val rows = rowsArray?.mapNotNull { rowElement ->
                    if (!rowElement.isJsonArray) return@mapNotNull null
                    rowElement.asJsonArray.map { it?.extractText().orEmpty() }
                }
                val columnIndex = obj.get("columnIndex")?.let {
                    runCatching { it.asInt }.getOrNull()
                }?.takeIf { it != null && it > 0 }
                val columnHeader = obj.get("columnHeader")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                val columnValuesArray = obj.getAsJsonArray("columnValues")
                val columnValues = columnValuesArray?.mapNotNull { el ->
                    when {
                        el.isJsonPrimitive -> el.asString
                        el.isJsonNull -> null
                        else -> el.extractText().ifEmpty { null }
                    }
                }
                AiAction.SheetsOperation(
                    operation,
                    spreadsheetId,
                    sheetName,
                    range,
                    key,
                    rowNumber,
                    rows,
                    columnIndex,
                    columnHeader,
                    columnValues
                )
            }
            else -> null
        }
    }

    private fun openAiTranslateBody(model: String, code: String, text: String): String {
        val root = JsonObject().apply {
            addProperty("model", model)
            addProperty("temperature", 0)
            add(
                "messages",
                JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("role", "system")
                        addProperty("content", SYSTEM_PROMPT)
                    })
                    add(JsonObject().apply {
                        addProperty("role", "user")
                        addProperty("content", "目标语言代码：$code，需要翻译文本：$text")
                    })
                }
            )
        }
        return root.toString()
    }

    private fun anthropicTranslateBody(model: String, code: String, text: String): String {
        val root = JsonObject().apply {
            addProperty("model", model)
            addProperty("system", SYSTEM_PROMPT)
            addProperty("max_tokens", 1024)
            add(
                "messages",
                JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("role", "user")
                        addProperty("content", "目标语言代码：$code，需要翻译文本：$text")
                    })
                }
            )
        }
        return root.toString()
    }

    private fun parseOpenAiText(body: String): String {
        val root = JsonParser.parseString(body).asJsonObject
        root.errorMessage()?.let { return it }
        val message = root.getAsJsonArray("choices")
            ?.firstObject()
            ?.getAsJsonObject("message")
        return message?.get("content")?.extractText().orEmpty()
    }

    private fun parseAnthropicText(body: String): String {
        val root = JsonParser.parseString(body).asJsonObject
        root.errorMessage()?.let { return it }
        return root.get("content")?.extractText().orEmpty()
    }

    private fun parseModelIds(body: String): List<String> {
        val root = JsonParser.parseString(body).asJsonObject
        root.errorMessage()?.let { throw IllegalStateException(it) }
        val data = root.getAsJsonArray("data") ?: return emptyList()
        return data.mapNotNull { element ->
            when {
                element.isJsonObject -> element.asJsonObject.get("id")?.asString
                element.isJsonPrimitive -> element.asString
                else -> null
            }
        }.distinct()
    }

    private fun JsonObject.errorMessage(): String? {
        val error = get("error") ?: return null
        return when {
            error.isJsonObject -> error.asJsonObject.get("message")?.asString ?: error.toString()
            error.isJsonPrimitive -> error.asString
            else -> error.toString()
        }
    }

    private fun JsonElement.extractText(): String {
        return when {
            isJsonPrimitive -> asString
            isJsonArray -> asJsonArray.mapNotNull { element ->
                when {
                    element.isJsonPrimitive -> element.asString
                    element.isJsonObject -> element.asJsonObject.get("text")?.asString
                        ?: element.asJsonObject.get("content")?.extractText()
                    else -> null
                }
            }.joinToString("")
            isJsonObject -> asJsonObject.get("text")?.asString
                ?: asJsonObject.get("content")?.extractText()
                ?: toString()
            else -> ""
        }
    }

    private fun JsonArray.firstObject(): JsonObject? {
        return firstOrNull { it.isJsonObject }?.asJsonObject
    }

    private fun String.limitForMessage(): String {
        return if (length <= 500) this else take(500) + "..."
    }
}
