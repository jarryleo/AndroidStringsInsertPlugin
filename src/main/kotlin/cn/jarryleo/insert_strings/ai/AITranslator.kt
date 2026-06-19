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
      "operation": "write",
      "spreadsheetId": "Google表格ID（可选，默认使用设置中的表格ID）",
      "range": "工作表范围，例如 Sheet1!A1:Z100",
      "rows": [["key", "values", "values-zh-rCN"], ["hello", "Hello", "你好"]]
    },
    {
      "type": "sheets_operation",
      "operation": "read",
      "spreadsheetId": "Google表格ID（可选）",
      "range": "工作表范围，例如 Sheet1!A1:Z100",
      "key": "要查找的字符串key（可选，为空则读取整个范围）"
    }
  ]
}

规则：
1. 当用户要求插入、添加、写入、修改翻译时，必须在 `actions` 中返回一个或多个 `insert_strings` 动作。
2. `name` 是 Android strings.xml 中的 string name，使用 snake_case。
3. `translations` 的键必须对应上下文 `availableLanguages` 中列出的所有语言目录名，如 values、values-zh-rCN、values-fr 等，不能遗漏任何一种语言。
4. `module` 必须是上下文中 `modules` 列表里的 `moduleName`（例如 app），不要使用 projectName 或 originalModuleName。
5. 如果上下文提供了 currentKey，优先使用该 key 作为 `name`。
6. 如果上下文提供了 currentModule，默认将翻译插入/修改到 currentModule.moduleName，不需要再询问用户。
7. 如果上下文没有 currentModule，且用户没有明确指定模块，请询问用户是否插入到翻译行数最多的模块（moduleWithMostLines.moduleName），不要直接返回 insert_strings 动作。
8. 可以同时返回多个 insert_strings 动作来插入多个字符串。
9. 翻译内容中如需使用 XML 特殊字符，请转义：&amp; &lt; &gt; &quot; &apos;。
10. 当用户要求把翻译写入 Google Sheets、同步到表格、导出到表格时，返回 `sheets_operation` 动作，`operation` 为 `write`，`rows` 第一行为表头（key + 语言目录名），后续每行是一条翻译。
11. 当用户要求从 Google Sheets 读取翻译、查找表格中的翻译时，返回 `sheets_operation` 动作，`operation` 为 `read`，可指定 `key` 只读取匹配行。
12. 如果用户只是普通聊天或询问，不需要返回 actions。
13. 当你返回了包含 sheets_operation 的 actions 后，系统会自动执行这些操作并将结果以「工具执行结果」的格式发回给你。请根据执行结果继续回复用户，说明操作是否成功以及读取到的内容。不要回复「请稍候」之类的等待语，因为操作已经执行完毕。
14. 收到工具执行结果后，只需在 reply 中总结结果即可，不要再重复返回已执行过的 actions。
15. 如果工具执行结果显示失败，请在 reply 中告知用户失败原因，并建议可能的解决方案（如配置代理、检查表格ID等）。"""

    private const val ANTHROPIC_VERSION = "2023-06-01"
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build()

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
                            addProperty("role", msg.role)
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
                            addProperty("role", msg.role)
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
                val range = obj.get("range")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                val key = obj.get("key")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                val rowsArray = obj.getAsJsonArray("rows")
                val rows = rowsArray?.mapNotNull { rowElement ->
                    if (!rowElement.isJsonArray) return@mapNotNull null
                    rowElement.asJsonArray.map { it?.extractText().orEmpty() }
                }
                AiAction.SheetsOperation(operation, spreadsheetId, range, key, rows)
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
