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

/**
 * function calling 协议下的一次工具调用(assistant 消息中)。
 * @param id        模型返回的 tool_call_id / tool_use_id,需要在 tool result 中回传以关联。
 * @param name      工具名。
 * @param arguments 工具参数的 JSON 字符串(由模型原样返回,driver 自行解析)。
 */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String
)

/**
 * 聊天消息(驱动层 + UI 共享)。
 *
 * 多模字段(用于 function calling):
 * - [toolCalls]  助手消息携带的工具调用列表。
 * - [toolCallId] 工具结果消息关联的 tool_call_id(OpenAI 协议);Anthropic 协议下用同样的字段映射到 tool_use_id。
 * - [options]    UI 层 AskUser 按钮选项,与 AI 协议无关。
 */
data class ChatMessage(
    val role: String,
    val content: String,
    val options: List<String> = emptyList(),
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null
)

object AITranslator {
    private const val SYSTEM_PROMPT =
        "你是一个专业的翻译，为开发安卓APP提供国际化翻译服务，我传给你需要翻译的文本，和目标语言的缩写代码，帮我翻译成目标语言，请返回对应的翻译结果文本，不需要额外的解释，请返回纯文本结果"
    private const val BATCH_TRANSLATE_SYSTEM_PROMPT =
        "你是一个专业的翻译，为开发安卓APP提供国际化翻译服务。我会给你多条文本和目标语言代码，请翻译成目标语言，并严格以 JSON 对象返回，key 为我给出的标识（原样保留），value 为对应翻译结果纯文本。不要 markdown 代码块，不要任何解释。"
    private const val CHAT_SYSTEM_PROMPT =
        """你是一个 Android 应用国际化字符串管理助手。通过 function calling 与系统协作:调用工具执行操作,调用 task_complete 结束任务。

## 强制终止规则(最重要)
- 唯一的「合法终止」信号是调用 task_complete 工具。
- 没有调用 task_complete = 你仍在执行 = 系统会持续驱动你继续调用工具。
- 在用户的目标完整达成前,不要调用 task_complete。即使用户消息只是「读取表格」,也要在拿到结果、给出总结后才算完成。
- 多步骤任务(如「检查 X 翻译,不准则修正」)必须按顺序执行每一步,直到真正完成或必须 ask_user 等待用户输入。

## 工具一览
### strings.xml 操作
- query_keys: 列出/搜索模块内的字符串 key(pattern 正则,可选 includeTranslations)。
- read_string: 读取指定 key 在所有语言的当前翻译。
- find_keys_by_text: 反查 — 通过翻译文本查找 key(exact/contains/regex,可选 module/language 限定)。
- insert_strings: 插入/全量覆盖翻译(translations 需覆盖所有语言,适合新增 key)。
- update_string: 精准修改指定 key 的部分语言翻译,只动提供的语言,其他保持原样(适合「修一个语言」「修个别语言」场景)。
- 主动发现流程:用户给的 key 不明确时,先用 query_keys 搜索;修改前先 read_string 确认原文;用 update_string 精准修改。看到一段翻译想反查 key,用 find_keys_by_text。

### Google 表格操作
- sheets_operation: 详见工具参数(枚举)。列操作需用户确认;修改/删除行前先 search 定位行号;全表检查/修正优先用 check_translations/fix_translations。
- find_rows_by_text: 反查 — 在表格中按文本搜索行(exact/contains/regex,可选 column 限定)。

### 通用
- ask_user: 向用户提问,options 非空时显示按钮。
- load_tool_doc: 按需加载工具详细文档。
- task_complete: 结束对话,status 取 success / partial / failed。

## 行为规则
1. 操作必须通过工具调用完成,不要只在文字里描述。
2. 每次回复可以同时包含文字(给用户看)和多个工具调用。
3. 收到工具结果后,如果目标尚未达成,必须继续调用工具推进。
4. 区分 insert_strings 与 update_string:新增/全量覆盖用 insert_strings,部分语言修改用 update_string。
5. 修改前若不确定当前翻译,先 read_string。
6. module 必须是 Android 模块名,取上下文 modules[].moduleName(**不是** androidProject.name,也**不是** originalModuleName);若上下文有 currentModule 则默认用它。
7. 【重要】同一 AI 回合内的所有 insert_strings / update_string 写入动作必须在同一模块:
   - 全部省略 module 参数(系统用 currentModule)
   - 或全部显式指定同一个 module
   - 不可一次 insert A 到 module1、insert B 到 module2 — 系统会整批拒绝并要求修正
   - 确实需要跨模块写入时,拆成多个 AI 回合
8. XML 特殊字符需转义:&amp; &lt; &gt; &quot; &apos;。
9. append_row 重复 key 由系统自动检测并询问用户,你无需自行检查。
10. sheets_operation 的 spreadsheetId/sheetName 可省略,默认用上下文 googleSheets 配置。
11. 若 googleSheets.configured 为 false,提示用户先配置,不要调用 sheets_operation。
12. 安全约束:禁止擅自增删列;写入前列对齐表头;全表检查/修正用 check_translations/fix_translations 而非 read 整表。"""

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
            - module（可选）：目标 Android 模块名，取上下文 modules[].moduleName(**不是** androidProject.name,也**不是** originalModuleName)。省略时用 currentModule.moduleName。
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
            // function calling 响应同时包含文本与 tool_calls,需解析完整 JSON。
            // 优先用专用解析;若失败,退化到抽取纯文本以便用户至少看到回复。
            parseAiReply(response.body())
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
            add("tools", ToolDefinitions.openAiTools)
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
                    messages.forEach { msg -> add(msg.toOpenAiMessage()) }
                }
            )
        }
        return root.toString()
    }

    private fun anthropicChatBody(model: String, messages: List<ChatMessage>, context: String): String {
        val root = JsonObject().apply {
            addProperty("model", model)
            addProperty("max_tokens", 4096)
            val systemParts = buildString {
                append(CHAT_SYSTEM_PROMPT)
                if (context.isNotBlank()) {
                    append("\n## 当前项目上下文（JSON）\n").append(context)
                }
            }
            addProperty("system", systemParts)
            add("tools", ToolDefinitions.anthropicTools)
            add(
                "messages",
                JsonArray().apply {
                    messages.forEach { msg -> add(msg.toAnthropicMessage()) }
                }
            )
        }
        return root.toString()
    }

    /**
     * 把 [ChatMessage] 转换为 OpenAI Chat Completions 的 messages 元素。
     * 重点处理三种特殊情况:
     * - assistant 消息携带 tool_calls(原生 function calling)
     * - tool 消息携带 tool_call_id(回传给模型以关联结果)
     * - 普通 user/assistant 消息保持原样
     */
    private fun ChatMessage.toOpenAiMessage(): JsonObject {
        // tool result message
        if (role == "tool" || (role == "user" && toolCallId != null)) {
            return JsonObject().apply {
                addProperty("role", "tool")
                addProperty("tool_call_id", toolCallId.orEmpty())
                addProperty("content", content)
            }
        }
        // assistant message with tool calls (function calling)
        if (role == "assistant" && toolCalls.isNotEmpty()) {
            return JsonObject().apply {
                addProperty("role", "assistant")
                if (content.isNotEmpty()) {
                    addProperty("content", content)
                } else {
                    addProperty("content", "")
                }
                add("tool_calls", JsonArray().apply {
                    toolCalls.forEach { tc ->
                        add(JsonObject().apply {
                            addProperty("id", tc.id)
                            addProperty("type", "function")
                            add("function", JsonObject().apply {
                                addProperty("name", tc.name)
                                addProperty("arguments", tc.arguments)
                            })
                        })
                    }
                })
            }
        }
        // plain text message
        return JsonObject().apply {
            addProperty("role", role)
            addProperty("content", content)
        }
    }

    /**
     * 把 [ChatMessage] 转换为 Anthropic Messages API 的 messages 元素。
     * - assistant 消息含 toolCalls 时用 content 数组(text + tool_use 块)
     * - tool 结果统一用 user 角色的 tool_result 块
     */
    private fun ChatMessage.toAnthropicMessage(): JsonObject {
        // tool result → user with tool_result block(s)
        if (role == "tool" || (role == "user" && toolCallId != null)) {
            return JsonObject().apply {
                addProperty("role", "user")
                add("content", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("type", "tool_result")
                        addProperty("tool_use_id", toolCallId.orEmpty())
                        addProperty("content", content)
                    })
                })
            }
        }
        if (role == "assistant") {
            if (toolCalls.isNotEmpty()) {
                return JsonObject().apply {
                    addProperty("role", "assistant")
                    add("content", JsonArray().apply {
                        if (content.isNotEmpty()) {
                            add(JsonObject().apply {
                                addProperty("type", "text")
                                addProperty("text", content)
                            })
                        }
                        toolCalls.forEach { tc ->
                            add(JsonObject().apply {
                                addProperty("type", "tool_use")
                                addProperty("id", tc.id)
                                addProperty("name", tc.name)
                                add("input", runCatching {
                                    JsonParser.parseString(tc.arguments)
                                }.getOrElse { JsonObject() })
                            })
                        }
                    })
                }
            }
            return JsonObject().apply {
                addProperty("role", "assistant")
                addProperty("content", content)
            }
        }
        return JsonObject().apply {
            addProperty("role", role)
            addProperty("content", content)
        }
    }

    private fun parseAiReply(responseText: String): AiReply {
        val root = runCatching { JsonParser.parseString(responseText).asJsonObject }.getOrNull()
            ?: return AiReply(responseText, emptyList())
        val text = extractAssistantText(root)
        val toolCalls = extractToolCalls(root)
        val actions = toolCalls.mapNotNull { call -> toolCallToAction(call) }
        return AiReply(text, actions, toolCalls)
    }

    /**
     * 从模型响应中提取助手文本内容。
     * 兼容三种格式:OpenAI chat.completions / Anthropic messages / 退化到 JSON 文本(老协议)。
     */
    private fun extractAssistantText(root: JsonObject): String {
        // OpenAI 格式
        root.getAsJsonArray("choices")?.firstObject()?.getAsJsonObject("message")
            ?.get("content")?.let { return it.extractText() }
        // Anthropic 格式
        root.getAsJsonArray("content")?.let { contentArray ->
            if (contentArray.any { it.isJsonObject && it.asJsonObject.get("type")?.asString == "text" }) {
                return contentArray.joinToString("") { element ->
                    if (element.isJsonObject && element.asJsonObject.get("type")?.asString == "text") {
                        element.asJsonObject.get("text")?.asString.orEmpty()
                    } else ""
                }
            }
        }
        return ""
    }

    /**
     * 从模型响应中提取 tool_calls(统一成内部 [ToolCall] 格式)。
     */
    private fun extractToolCalls(root: JsonObject): List<ToolCall> {
        val result = mutableListOf<ToolCall>()
        // OpenAI: choices[0].message.tool_calls
        root.getAsJsonArray("choices")?.firstObject()?.getAsJsonObject("message")
            ?.getAsJsonArray("tool_calls")?.forEach { element ->
                if (!element.isJsonObject) return@forEach
                val obj = element.asJsonObject
                val id = obj.get("id")?.asString ?: return@forEach
                val functionObj = obj.getAsJsonObject("function") ?: return@forEach
                val name = functionObj.get("name")?.asString ?: return@forEach
                val arguments = functionObj.get("arguments")?.asString ?: "{}"
                result.add(ToolCall(id, name, arguments))
            }
        // Anthropic: content[].type=tool_use
        root.getAsJsonArray("content")?.forEach { element ->
            if (!element.isJsonObject) return@forEach
            val obj = element.asJsonObject
            if (obj.get("type")?.asString != "tool_use") return@forEach
            val id = obj.get("id")?.asString ?: return@forEach
            val name = obj.get("name")?.asString ?: return@forEach
            val input = obj.get("input")
            val arguments = input?.toString() ?: "{}"
            result.add(ToolCall(id, name, arguments))
        }
        return result
    }

    /**
     * 解析 matchType 字符串(不区分大小写),失败回退 CONTAINS。
     */
    private fun parseMatchType(raw: String?): AiAction.TextMatchType {
        if (raw.isNullOrBlank()) return AiAction.TextMatchType.CONTAINS
        return runCatching {
            AiAction.TextMatchType.valueOf(raw.trim().uppercase())
        }.getOrDefault(AiAction.TextMatchType.CONTAINS)
    }

    /**
     * 把单个 tool call 转换为 [AiAction]。
     * 解析失败时返回 null,由调用方决定如何兜底(通常在 reply 中提示用户)。
     */
    private fun toolCallToAction(call: ToolCall): AiAction? {
        val args: JsonObject = runCatching { JsonParser.parseString(call.arguments).asJsonObject }
            .getOrNull() ?: return null
        return when (call.name) {
            ToolDefinitions.TOOL_QUERY_KEYS -> {
                val module = args.get("module")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                val pattern = args.get("pattern")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                val limit = args.get("limit")?.let { runCatching { it.asInt }.getOrNull() }
                val offset = args.get("offset")?.let { runCatching { it.asInt }.getOrNull() }
                val includeTranslations = args.get("includeTranslations")?.let {
                    runCatching { it.asBoolean }.getOrNull()
                } ?: false
                AiAction.QueryKeys(module, pattern, limit, offset, includeTranslations)
            }
            ToolDefinitions.TOOL_READ_STRING -> {
                val name = args.get("name")?.asString?.trim() ?: return null
                if (name.isEmpty()) return null
                val module = args.get("module")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                AiAction.ReadString(module, name)
            }
            ToolDefinitions.TOOL_UPDATE_STRING -> {
                val name = args.get("name")?.asString?.trim() ?: return null
                if (name.isEmpty()) return null
                val module = args.get("module")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                val translationsObj = args.getAsJsonObject("translations") ?: return null
                val translations = translationsObj.entrySet()
                    .associate { it.key to it.value.extractText() }
                    .filterValues { it.isNotEmpty() }
                if (translations.isEmpty()) return null
                AiAction.UpdateString(module, name, translations)
            }
            ToolDefinitions.TOOL_FIND_KEYS_BY_TEXT -> {
                val text = args.get("text")?.asString?.trim() ?: return null
                if (text.isEmpty()) return null
                val module = args.get("module")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                val language = args.get("language")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                val matchType = parseMatchType(args.get("matchType")?.asString)
                val caseSensitive = args.get("caseSensitive")?.let { runCatching { it.asBoolean }.getOrNull() } ?: false
                val limit = args.get("limit")?.let { runCatching { it.asInt }.getOrNull() } ?: 30
                AiAction.FindKeysByText(text, module, language, matchType, caseSensitive, limit)
            }
            ToolDefinitions.TOOL_FIND_ROWS_BY_TEXT -> {
                val text = args.get("text")?.asString?.trim() ?: return null
                if (text.isEmpty()) return null
                val spreadsheetId = args.get("spreadsheetId")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                val sheetName = args.get("sheetName")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                val column = args.get("column")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                val matchType = parseMatchType(args.get("matchType")?.asString)
                val caseSensitive = args.get("caseSensitive")?.let { runCatching { it.asBoolean }.getOrNull() } ?: false
                val limit = args.get("limit")?.let { runCatching { it.asInt }.getOrNull() } ?: 30
                AiAction.FindRowsByText(text, spreadsheetId, sheetName, column, matchType, caseSensitive, limit)
            }
            ToolDefinitions.TOOL_INSERT_STRINGS -> {
                val name = args.get("name")?.asString?.trim() ?: return null
                val module = args.get("module")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                val translationsObj = args.getAsJsonObject("translations") ?: return null
                val translations = translationsObj.entrySet().associate { it.key to it.value.extractText() }
                if (name.isNotEmpty() && translations.isNotEmpty()) {
                    AiAction.InsertStrings(module, name, translations)
                } else null
            }
            ToolDefinitions.TOOL_ASK_USER -> {
                val question = args.get("question")?.asString ?: return null
                val optionsArray = args.getAsJsonArray("options")
                val options = optionsArray?.mapNotNull {
                    it?.extractText()?.takeIf { o -> o.isNotEmpty() }
                } ?: emptyList()
                AiAction.AskUser(question, options)
            }
            ToolDefinitions.TOOL_LOAD_TOOL_DOC -> {
                val tool = args.get("tool")?.asString?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                AiAction.LoadToolDoc(tool)
            }
            ToolDefinitions.TOOL_TASK_COMPLETE -> {
                val summary = args.get("summary")?.asString?.trim().orEmpty()
                val status = args.get("status")?.asString?.trim().orEmpty()
                if (summary.isEmpty() || status.isEmpty()) return null
                val notes = args.get("notes")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                AiAction.TaskComplete(summary, status, notes)
            }
            ToolDefinitions.TOOL_SHEETS_OPERATION -> {
                val operationText = args.get("operation")?.asString ?: return null
                val operation = runCatching {
                    AiAction.SheetsOperation.Operation.valueOf(operationText.uppercase())
                }.getOrNull() ?: return null
                val spreadsheetId = args.get("spreadsheetId")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                val sheetName = args.get("sheetName")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                val range = args.get("range")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                val key = args.get("key")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                val rowNumber = args.get("rowNumber")?.let {
                    runCatching { it.asInt }.getOrNull()
                }?.takeIf { it != null && it > 0 }
                val rowsArray = args.getAsJsonArray("rows")
                val rows = rowsArray?.mapNotNull { rowElement ->
                    if (!rowElement.isJsonArray) return@mapNotNull null
                    rowElement.asJsonArray.map { it?.extractText().orEmpty() }
                }
                val columnIndex = args.get("columnIndex")?.let {
                    runCatching { it.asInt }.getOrNull()
                }?.takeIf { it != null && it > 0 }
                val columnHeader = args.get("columnHeader")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                val columnValuesArray = args.getAsJsonArray("columnValues")
                val columnValues = columnValuesArray?.mapNotNull { el ->
                    when {
                        el.isJsonPrimitive -> el.asString
                        el.isJsonNull -> null
                        else -> el.extractText().ifEmpty { null }
                    }
                }
                val freezeRowCount = args.get("freezeRowCount")?.let {
                    runCatching { it.asInt }.getOrNull()
                }?.takeIf { it != null && it >= 0 }
                val freezeColumnCount = args.get("freezeColumnCount")?.let {
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
