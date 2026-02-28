package cn.jarryleo.insert_strings

import com.alibaba.dashscope.aigc.generation.Generation
import com.alibaba.dashscope.aigc.generation.GenerationParam
import com.alibaba.dashscope.common.Message
import com.alibaba.dashscope.common.Role
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

object AITranslator {
    const val API_KEY = "sk-007cac5b259149738c5b8f238fc54d2b"

    /**
     * https://bailian.console.aliyun.com/cn-beijing/?tab=model#/api-key
     */
    suspend fun translate(code: String, text: String): String {
        val systemMsg = Message.builder()
            .role(Role.SYSTEM.value)
            .content("你是一个专业的翻译，为开发安卓APP提供国际化翻译服务，我传给你需要翻译的文本，和目标语言的缩写代码，帮我翻译成目标语言，请返回对应的翻译结果文本，不需要额外的解释，请返回纯文本结果")
            .build()
        val userMsg = Message.builder()
            .role(Role.USER.value)
            .content("目标语言代码：$code，需要翻译文本：$text")
            .build()
        val param = GenerationParam.builder()
            .apiKey(API_KEY)
            // 模型列表：https://help.aliyun.com/model-studio/getting-started/models
            .model("qwen-plus")
            .messages(listOf(systemMsg, userMsg))
            .resultFormat(GenerationParam.ResultFormat.MESSAGE)
            .build()
        val gen = Generation()
        val result = runCatching { gen.call(param) }
        return if (result.isSuccess) {
            result.getOrNull()?.output?.choices?.firstOrNull()?.message?.content ?: ""
        } else {
            result.exceptionOrNull()?.message ?: ""
        }
    }

    fun translate(code: String, text: String, callback: (String) -> Unit) {
        GlobalScope.launch {
            val result = translate(code, text)
            callback(result)
        }
    }
}