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
    val options: List<String> = emptyList()
)

object AITranslator {
    private const val SYSTEM_PROMPT =
        "你是一个专业的翻译，为开发安卓APP提供国际化翻译服务，我传给你需要翻译的文本，和目标语言的缩写代码，帮我翻译成目标语言，请返回对应的翻译结果文本，不需要额外的解释，请返回纯文本结果"
    private const val BATCH_TRANSLATE_SYSTEM_PROMPT =
        "你是一个专业的翻译，为开发安卓APP提供国际化翻译服务。我会给你多条文本和目标语言代码，请翻译成目标语言，并严格以 JSON 对象返回，key 为我给出的标识（原样保留），value 为对应翻译结果纯文本。不要 markdown 代码块，不要任何解释。"
    private const val CHAT_SYSTEM_PROMPT =
        """你是一个 Android 应用国际化字符串管理助手。你必须始终只返回一个纯 JSON 对象（以 { 开头、以 } 结尾），禁止 markdown 代码块、禁止 XML/HTML 自定义标签、禁止 JSON 以外的任何文字。

回复 JSON 结构：
{
  "reply": "给用户看的中文回复文本",
  "actions": [
    {"type": "insert_strings", "module": "模块名(可选)", "name": "snake_case key", "translations": {"values": "默认语言", "values-zh-rCN": "简体中文"}},
    {"type": "sheets_operation", "operation": "operation名", ...字段},
    {"type": "ask_user", "question": "问题文本", "options": ["选项1", "选项2"]},
    {"type": "load_tool_doc", "tool": "工具名"}
  ]
}

## 可用工具一览（仅名称与简述，需要详细用法时用 load_tool_doc 加载）

### insert_strings（直接可用，无需加载文档）
向 Android strings.xml 插入/修改翻译。字段：module(可选)、name、translations(键为语言目录名如 values/values-zh-rCN)。

### sheets_operation（Google 表格操作，operation 取值如下）
- list_sheets — 列出所有工作表名称
- read — 读取表格数据
- search — 按 key 查找行号
- write — 覆盖写入指定范围
- append_row — 末尾追加行
- insert_row — 指定位置插入行
- update_row — 更新指定行
- delete_row — 删除指定行
- clear_row — 清空指定行
- insert_column — 插入列(需用户确认)
- append_column — 末尾追加列(需用户确认)
- update_column — 更新列(需用户确认)
- delete_column — 删除列(需用户确认)
- clear_column — 清空列(需用户确认)
- freeze_rows — 冻结行
- freeze_columns — 冻结列
- check_translations — 全表翻译检查(系统分批处理)
- fix_translations — 全表翻译修正(系统分批处理并写入)

### ask_user（直接可用）
向用户提问，options 非空时展示按钮供用户选择。

### load_tool_doc（按需加载工具文档）
当你不确定某个工具的完整字段、用法或工作流时，返回此动作请求加载详细文档。系统会把该工具的完整说明注入对话，你据此继续操作。
字段：tool（工具名，取值：insert_strings / sheets_row_ops / sheets_column_ops / sheets_freeze / sheets_review / sheets_basic）
建议：首次使用某类操作前先 load_tool_doc，拿到详细文档后再返回实际执行动作。

## 核心规则
1. 需要执行操作时必须返回 actions，禁止只用文字描述操作。
2. insert_strings 的 translations 键必须覆盖上下文 availableLanguages 列出的所有语言。
3. module 必须用上下文 modules 中的 moduleName；若上下文有 currentModule 则默认用它，无需询问。
4. 若无 currentModule 且用户未指定模块，用 ask_user 询问是否插入到 moduleWithMostLines。
5. XML 特殊字符需转义：&amp; &lt; &gt; &quot; &apos;。
6. 返回 sheets_operation 后系统自动执行并回传结果，据此在 reply 中总结。收到结果后不要重复返回已执行的 actions。
7. append_row 重复 key 由系统自动检测并询问用户，你无需自行检查。
8. sheets_operation 的 spreadsheetId/sheetName 可省略，默认用上下文 googleSheets 配置。availableSheets 非空时 sheetName 须从中选取。
9. 若 googleSheets.configured 为 false，提示用户先配置，不要返回 sheets_operation。
10. 安全约束：修改/删除行前先用 search 定位行号；禁止擅自增删列；写入前列对齐表头；全表检查/修正用 check_translations/fix_translations 而非 read 整表。"""

    /**
     * 按需加载的工具详细文档（key = tool 名，value = 完整说明）。
     * AI 通过 load_tool_doc action 请求加载对应文档，系统注入为 tool 消息。
     * 这样主聊天 system prompt 只含工具清单，按需加载详细用法，大幅减少每轮 token。
     */
    private val TOOL_DOCS: Map<String, String> = mapOf(
        "insert_strings" to """
            ## insert_strings 详细用法
            向 Android strings.xml 插入或修改翻译字符串。
            字段：
            - module（可选）：目标模块名，必须是上下文 modules 中的 moduleName。省略时用 currentModule。
            - name（必填）：字符串 key，snake_case。
            - translations（必填）：键为语言目录名（如 values、values-zh-rCN、values-fr），值为对应翻译文本。
            规则：
            - translations 必须覆盖上下文 availableLanguages 中的所有语言，不能遗漏。
            - 若上下文有 currentKeys，优先用第一个 key 作为 name；多 key 时可为每个 key 分别返回 insert_strings。
            - 可以同时返回多个 insert_strings 动作插入多个字符串。
            - 翻译内容中 XML 特殊字符需转义：&amp; &lt; &gt; &quot; &apos;。
            示例：
            {"type":"insert_strings","module":"app","name":"hello_world","translations":{"values":"Hello","values-zh-rCN":"你好","values-fr":"Bonjour"}}
        """.trimIndent(),

        "sheets_basic" to """
            ## sheets_operation 基础操作（list_sheets / read / search / write）
            公共字段：spreadsheetId（可选，默认用设置中的表格ID）、sheetName（可选，默认用设置中的 defaultSheetName）。

            ### list_sheets
            列出表格文件中所有工作表名称及尺寸。不需要 range/key/rows/rowNumber/sheetName。
            场景：用户问「有哪些 sheet」「看一下表格结构」，或不确定目标工作表是否存在时。
            示例：{"type":"sheets_operation","operation":"list_sheets"}

            ### read
            读取数据。可指定 sheetName/range 精确读取某区域；可指定 key 在第一列查找匹配行。全省略则读默认工作表全部内容。
            场景：用户问「读一下表格」「表格里有什么」「看 hello 的翻译」。
            示例：{"type":"sheets_operation","operation":"read","sheetName":"1.0.3.0"}

            ### search
            在工作表第一列查找 key 匹配的行，返回 1-based 行号与整行内容。key 必填。
            场景：精确定位某 key 行号，以便后续 update_row/delete_row/insert_row。
            示例：{"type":"sheets_operation","operation":"search","key":"hello"}

            ### write
            向指定 range 整体写入数据（覆盖该范围已有内容）。rows 第一行通常为表头。
            注意：write 会覆盖范围内所有单元格，谨慎使用，不要覆盖已有翻译。
            示例：{"type":"sheets_operation","operation":"write","range":"Sheet1!A1:Z100","rows":[["key","values"],["hello","Hello"]]}
        """.trimIndent(),

        "sheets_row_ops" to """
            ## sheets_operation 行操作（append_row / insert_row / update_row / delete_row / clear_row）
            公共字段：spreadsheetId（可选）、sheetName（可选）。rowNumber 为 1-based。

            ### append_row（最常用、最安全的写入方式）
            在工作表末尾追加一行。rows 为单行数据的二维数组（外层只有一个元素）。
            场景：插入一条新翻译到表格末尾。
            示例：{"type":"sheets_operation","operation":"append_row","rows":[["hello","Hello","你好"]]}
            注：重复 key 由系统自动检测并询问用户覆盖/追加/取消，你无需自行检查。

            ### insert_row
            在 rowNumber 位置插入新行，原该行及之后下移。rows 为单行数据二维数组。
            场景：「插到第 5 行」「在表头下面插入一行」。
            示例：{"type":"sheets_operation","operation":"insert_row","rowNumber":5,"rows":[["hello","Hello","你好"]]}

            ### update_row
            精确更新 rowNumber 指定行的内容，不影响其它行。rows 为单行数据二维数组。
            场景：「修改第 5 行的翻译」「把 hello 这一行的中文改成你好」。建议先 search 定位行号。
            示例：{"type":"sheets_operation","operation":"update_row","rowNumber":7,"rows":[["hello","Hello","你好"]]}

            ### delete_row
            删除 rowNumber 指定的行，后续行上移。不需要 rows。
            示例：{"type":"sheets_operation","operation":"delete_row","rowNumber":5}

            ### clear_row
            清空 rowNumber 指定行的内容但保留空行。不需要 rows。
            示例：{"type":"sheets_operation","operation":"clear_row","rowNumber":5}

            安全约束：
            - 修改/删除/清空某行前必须先 search 定位行号，避免误操作其它行。
            - 禁止覆盖其它翻译行，插入新翻译优先用 append_row。
        """.trimIndent(),

        "sheets_column_ops" to """
            ## sheets_operation 列操作（insert_column / append_column / update_column / delete_column / clear_column）
            以下操作会改变表格列结构，系统执行前会弹确认对话框。用户拒绝则返回失败，请在 reply 中告知取消。
            公共字段：spreadsheetId（可选）、sheetName（可选）。columnIndex 为 1-based。
            安全约束：除非用户明确要求增删列，否则不要返回这些动作。操作前应先 read 表头确认列数与顺序。

            ### insert_column
            在 columnIndex 位置插入新列，原该列及之后右移。
            字段：columnIndex（必填）、columnHeader（可选）、columnValues（一维数组，首元素为表头，其余为各行值，必填）。
            示例：{"type":"sheets_operation","operation":"insert_column","columnIndex":3,"columnValues":["values-fr","Bonjour","Au revoir"]}

            ### append_column
            在末尾追加新列。
            字段：columnHeader（建议填写）、columnValues（一维数组，首元素为表头，必填）。columnValues[0] 应与 columnHeader 一致。
            示例：{"type":"sheets_operation","operation":"append_column","columnHeader":"values-ja","columnValues":["values-ja","こんにちは","さようなら"]}

            ### update_column
            精确更新 columnIndex 指定列内容，不影响其它列。
            字段：columnIndex（必填）、columnValues（一维数组，首元素为表头，必填）。
            示例：{"type":"sheets_operation","operation":"update_column","columnIndex":3,"columnValues":["values-fr","Bonjour","Au revoir"]}

            ### delete_column
            删除 columnIndex 指定列，后续列左移。不需要 columnValues。
            示例：{"type":"sheets_operation","operation":"delete_column","columnIndex":3}

            ### clear_column
            清空 columnIndex 指定列内容但保留空列。不需要 columnValues。
            示例：{"type":"sheets_operation","operation":"clear_column","columnIndex":3}
        """.trimIndent(),

        "sheets_freeze" to """
            ## sheets_operation 冻结行列（freeze_rows / freeze_columns）
            字段：spreadsheetId（可选）、sheetName（可选）。

            ### freeze_rows
            冻结表格顶部指定行数，滚动时保持可见。freezeRowCount >= 0，填 0 取消冻结。
            示例：{"type":"sheets_operation","operation":"freeze_rows","freezeRowCount":2}

            ### freeze_columns
            冻结表格左侧指定列数，滚动时保持可见。freezeColumnCount >= 0，填 0 取消冻结。
            示例：{"type":"sheets_operation","operation":"freeze_columns","freezeColumnCount":1}
        """.trimIndent(),

        "sheets_review" to """
            ## sheets_operation 批量翻译审查/修正（check_translations / fix_translations）
            这两个操作由系统在本地分批调用 AI 完成全表检查或修正，主聊天只收到最终总结报告，不会把整表塞进上下文，token 不会溢出。你只需返回这一个动作，系统自动读取整表、分批处理、汇总报告并回传。
            字段：sheetName（可选）、range（可选，不填则处理整个工作表）。

            ### check_translations
            检查所有行翻译质量，只报告有问题的条目并生成总结报告。
            场景：用户要求「检查全部翻译」「审查表格」。
            系统返回的工具结果即为审查总结报告（含问题清单与统计），据此在 reply 中向用户汇报。
            示例：{"type":"sheets_operation","operation":"check_translations"}

            ### fix_translations
            检查并直接修正所有行翻译，自动写入修正后的行，并生成总结报告。
            场景：用户要求「修正全部翻译」「补全并修正所有翻译」。
            系统自动执行修正写入并返回总结报告（含修正行数），据此汇报。不要在 actions 中再重复返回这些行的 update_row。
            示例：{"type":"sheets_operation","operation":"fix_translations"}

            重要：当用户要求检查/修正全部翻译时，优先用这两个操作，不要用 read 整表后逐行分析。
        """.trimIndent()
    )

    /**
     * 获取工具详细文档。供 UI 层在处理 load_tool_doc action 时调用。
     * @param toolName 工具名（如 sheets_row_ops）
     * @return 对应的完整文档文本，若不存在返回 null
     */
    @JvmStatic
    fun getToolDoc(toolName: String): String? = TOOL_DOCS[toolName.trim()]

    /** 列出所有可加载文档的工具名（调试/展示用）。 */
    @JvmStatic
    fun availableToolDocs(): List<String> = TOOL_DOCS.keys.toList()

    private const val ANTHROPIC_VERSION = "2023-06-01"
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    /**
     * 批量翻译审查的专用系统提示词（独立于主聊天，最小化 token 使用）。
     * 只要求模型输出有问题的行，避免回吐整张表。
     */
    private const val REVIEW_SYSTEM_PROMPT =
        """你是 Android 国际化翻译质量审查助手。输入格式：
表头(N列): 列1|列2|...|列N
行X: 值1|值2|...|值N
「行X:」是行号标注，非数据列，不计入列数或 values。竖线分隔的为数据列，第一列是 key，其余为翻译。
检查准确性、一致性、完整性、占位符(%s/%d)与 XML 转义。只返回纯 JSON，无 markdown，无额外说明。

check 模式：{"issues":[{"row":<行号>,"col":<0-based列号,0=key列>,"current":"<当前值>","suggested":"<建议值>","reason":"<原因>"}],"summary":"<中文总结>"}
- 只列有问题的条目，无问题返回空 issues。row 用「行X:」标注的行号原样回填。

fix 模式：{"fixes":[{"row":<行号>,"values":[<整行新值,列数同表头>]}],"summary":"<中文总结>"}
- 只返回需修正的行。values 只含数据列，长度等于表头列数，不含行号。
- 不修改 key 列原样保留。row 用「行X:」标注的行号。"""

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
        val root = extractJsonObject(responseText)
        return try {
            if (root == null) throw IllegalStateException("No JSON object found in batch translate result")
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
        sb.append("表头(").append(header.size).append("列): ")
            .append(header.joinToString("|") { truncate(it, maxCellChars) })
            .append("\n")
        batchRows.forEachIndexed { index, row ->
            val rowNumber = startRowNumber + index
            sb.append("行").append(rowNumber).append(": ")
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
        val root = extractJsonObject(responseText)
        return try {
            if (root == null) throw IllegalStateException("No JSON object found in review result")
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
        val root = extractJsonObject(responseText)
        return try {
            if (root == null) throw IllegalStateException("No JSON object found in AI reply")
            val reply = root.get("reply")?.extractText().orEmpty()
            val actions = root.getAsJsonArray("actions")?.mapNotNull { parseAction(it) } ?: emptyList()
            AiReply(reply, actions)
        } catch (e: Exception) {
            AiReply(responseText, emptyList())
        }
    }

    /**
     * 从 AI 回复文本中提取 JSON 对象，兼容以下常见格式：
     * 1. 纯 JSON 文本
     * 2. ```json ... ``` 或 ``` ... ``` 代码块包裹的 JSON（代码块前后可能有说明文字）
     * 3. 前后带有说明文字的裸 JSON
     * 通过匹配花括号定位完整的 JSON 对象，正确处理字符串内的花括号与转义字符。
     */
    private fun extractJsonObject(text: String): JsonObject? {
        val trimmed = text.trim()
        // 1. 直接尝试解析整段文本
        trimmed.parseAsJsonObject()?.let { return it }

        // 2. 逐个尝试从花括号位置匹配完整 JSON 对象
        //    覆盖代码块包裹、前后有说明文字等情况
        var searchFrom = 0
        while (true) {
            val startIdx = trimmed.indexOf('{', searchFrom)
            if (startIdx < 0) break
            val jsonStr = extractBalancedJson(trimmed, startIdx)
            if (jsonStr != null) {
                jsonStr.parseAsJsonObject()?.let { return it }
            }
            searchFrom = startIdx + 1
        }
        return null
    }

    /**
     * 从 startIdx 位置的 '{' 开始，匹配花括号找到完整的 JSON 对象字符串。
     * 正确处理字符串内的花括号和转义字符，避免误匹配。
     */
    private fun extractBalancedJson(text: String, startIdx: Int): String? {
        if (startIdx < 0 || startIdx >= text.length || text[startIdx] != '{') return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in startIdx until text.length) {
            val c = text[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (inString) {
                when (c) {
                    '\\' -> escaped = true
                    '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return text.substring(startIdx, i + 1)
                    }
                }
            }
        }
        return null
    }

    private fun String.parseAsJsonObject(): JsonObject? {
        return try {
            JsonParser.parseString(this).asJsonObject
        } catch (e: Exception) {
            null
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
                val optionsArray = obj.getAsJsonArray("options")
                val options = optionsArray?.mapNotNull { it?.extractText()?.takeIf { o -> o.isNotEmpty() } } ?: emptyList()
                AiAction.AskUser(question, options)
            }
            "load_tool_doc" -> {
                val tool = obj.get("tool")?.asString?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                AiAction.LoadToolDoc(tool)
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
                val freezeRowCount = obj.get("freezeRowCount")?.let {
                    runCatching { it.asInt }.getOrNull()
                }?.takeIf { it != null && it >= 0 }
                val freezeColumnCount = obj.get("freezeColumnCount")?.let {
                    runCatching { it.asInt }.getOrNull()
                }?.takeIf { it != null && it >= 0 }
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
                    columnValues,
                    freezeRowCount,
                    freezeColumnCount
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
