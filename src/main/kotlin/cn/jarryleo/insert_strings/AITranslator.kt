package cn.jarryleo.insert_strings

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

object AITranslator {
    private const val SYSTEM_PROMPT =
        "你是一个专业的翻译，为开发安卓APP提供国际化翻译服务，我传给你需要翻译的文本，和目标语言的缩写代码，帮我翻译成目标语言，请返回对应的翻译结果文本，不需要额外的解释，请返回纯文本结果"
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
