package cn.jarryleo.insert_strings

import ai.z.openapi.ZhipuAiClient
import ai.z.openapi.service.model.ChatCompletionCreateParams
import ai.z.openapi.service.model.ChatMessage
import ai.z.openapi.service.model.ChatMessageRole
import ai.z.openapi.service.model.MessageContent
import kotlinx.coroutines.*


object AITranslator : AITranslatorInterface, CoroutineScope by MainScope() {
    const val API_KEY = "d88655ea4b14430d8e91bbade358c768.Did1MROvve4K3Hhg"

    private val client by lazy {
        ZhipuAiClient.builder().ofZHIPU()
            .apiKey(API_KEY)
            .build()
    }

    override fun translate(code: String, text: String, callback: AiCallback) {
        launch(Dispatchers.IO) {
            val result = runCatching {
                val systemMsg = ChatMessage.builder()
                    .role(ChatMessageRole.SYSTEM.value())
                    .content("你是一个专业的翻译，为开发安卓APP提供国际化翻译服务，我传给你需要翻译的文本，和目标语言的缩写代码，帮我翻译成目标语言，翻译风格要符合APP使用环境；请返回对应的翻译结果文本，不需要额外的解释，请返回纯文本结果")
                    .build()
                val userMsg = ChatMessage.builder()
                    .role(ChatMessageRole.USER.value())
                    .content("目标语言代码：$code，需要翻译文本：$text")
                    .build()
                val request = ChatCompletionCreateParams.builder()
                    .model("GLM-4.7-Flash")
                    .messages(listOf(systemMsg, userMsg))
                    .stream(false)
                    .temperature(0.3f)
                    .maxTokens(4096)
                    .build()
                val response = client.chat().createChatCompletion(request)
                val content = response.data.choices.firstOrNull()?.message?.content ?: ""
                when (content) {
                    is String -> content
                    is MessageContent -> content.text
                    else -> ""
                }
            }.getOrNull() ?: ""
            withContext(Dispatchers.Main) {
                callback.onAiTranslateComplete(result)
            }
        }
    }

}