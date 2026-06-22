package cn.jarryleo.insert_strings.ai

import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * SSE(Server-Sent Events)流解析器,按行读取 [InputStream] 解析 `data: ...` 事件。
 *
 * 支持 OpenAI / Anthropic 两种 chat completion 的流式响应,统一抽象成:
 * - 文本增量 → 调 [onDelta] 回调(参数是新增的字符串,调用方自己累加)
 * - 工具调用 → 内部按 protocol 累积,流结束后通过 [toolCalls] 一次性读出
 *
 * 为什么不把累加后的全文再 push 一次(全量回调):
 * - 全量回调在大回复时会重复构造整段字符串,Compose state diff 也会增大;
 * - 增量回调让调用方自己实现累加 + 节流策略,更灵活。
 *
 * 错误策略:解析过程遇到任何异常,只记日志不抛,流结束后仍按已累积内容返回。
 * 这样调用方拿到的 (text, toolCalls) 永远是「截至解析失败时」的最佳努力结果。
 */
internal class SseStreamParser(
    private val protocol: AiProtocol,
    private val onDelta: (String) -> Unit
) {
    /**
     * 累积到的 tool_call 列表(按 tool_call 出现顺序)。
     * 暴露 [id] / [name] / [arguments] 三段,含义与 [ToolCall] 相同。
     */
    data class StreamedToolCall(
        val id: String,
        val name: String,
        val arguments: String
    )

    val toolCalls: MutableList<StreamedToolCall> = mutableListOf()

    /** 解析 SSE 流,读完即返回。 */
    fun parse(stream: InputStream) {
        val reader = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8))
        var currentEvent: String? = null
        val dataBuffer = StringBuilder()
        var line: String?

        while (reader.readLine().also { line = it } != null) {
            val raw = line ?: continue
            // SSE 注释行 / 空行:跳过。空行表示「当前 event 结束」
            if (raw.isEmpty()) {
                if (dataBuffer.isNotEmpty()) {
                    val payload = dataBuffer.toString().trimEnd()
                    handleDataLine(payload, currentEvent)
                    dataBuffer.clear()
                }
                currentEvent = null
                continue
            }
            if (raw.startsWith(":")) continue
            val colonIdx = raw.indexOf(':')
            if (colonIdx < 0) continue
            val field = raw.substring(0, colonIdx)
            val value = if (colonIdx == raw.length - 1) "" else raw.substring(colonIdx + 1).trimStart()
            when (field) {
                "event" -> currentEvent = value
                "data" -> {
                    if (dataBuffer.isNotEmpty()) dataBuffer.append('\n')
                    dataBuffer.append(value)
                }
                "id", "retry" -> { /* 暂不处理 */ }
            }
        }
        // 收尾:有些服务端不会在末尾发空行(尤其非标准实现)
        if (dataBuffer.isNotEmpty()) {
            handleDataLine(dataBuffer.toString().trimEnd(), currentEvent)
        }
    }

    private fun handleDataLine(payload: String, event: String?) {
        // OpenAI 终止标志
        if (payload == "[DONE]") return
        if (payload.isEmpty()) return
        val root = runCatching { JsonParser.parseString(payload).asJsonObject }.getOrNull() ?: return
        when (protocol) {
            AiProtocol.OPENAI -> handleOpenAiDelta(root)
            AiProtocol.ANTHROPIC -> handleAnthropicDelta(root, event)
        }
    }

    // ===== OpenAI =====

    private fun handleOpenAiDelta(root: com.google.gson.JsonObject) {
        // 顶层 error 字段:直接当作整流错误,既不解析内容也不累积后续
        root.get("error")?.let { err ->
            val msg = when {
                err.isJsonObject -> err.asJsonObject.get("message")?.asString ?: err.toString()
                err.isJsonPrimitive -> err.asString
                else -> err.toString()
            }
            onDelta("\n[stream error] $msg")
            return
        }
        val choice = root.getAsJsonArray("choices")
            ?.firstOrNull { it.isJsonObject }
            ?.asJsonObject ?: return
        val delta = choice.getAsJsonObject("delta") ?: return

        // 文本增量
        delta.get("content")?.let { contentEl ->
            val text = when {
                contentEl.isJsonNull -> null
                contentEl.isJsonPrimitive -> contentEl.asString
                else -> contentEl.toString()
            }
            if (!text.isNullOrEmpty()) onDelta(text)
        }

        // 工具调用增量:delta.tool_calls 是稀疏数组(每项可能只含部分字段),按 index 合并
        delta.getAsJsonArray("tool_calls")?.forEach { el ->
            if (!el.isJsonObject) return@forEach
            val obj = el.asJsonObject
            val index = obj.get("index")?.let { runCatching { it.asInt }.getOrNull() } ?: 0
            while (toolCalls.size <= index) {
                toolCalls.add(StreamedToolCall("", "", ""))
            }
            val tc = toolCalls[index]
            val newId = obj.get("id")?.takeIf { !it.isJsonNull }?.asString
            val functionObj = obj.getAsJsonObject("function")
            val newName = functionObj?.get("name")?.takeIf { !it.isJsonNull }?.asString
            val newArgs = functionObj?.get("arguments")?.takeIf { !it.isJsonNull }?.asString
            toolCalls[index] = tc.copy(
                id = newId ?: tc.id,
                name = newName ?: tc.name,
                arguments = tc.arguments + (newArgs ?: "")
            )
        }
    }

    // ===== Anthropic =====

    /**
     * Anthropic SSE 事件按 event 字段分发:
     * - content_block_start  → 工具调用开始(id + name),或文本 block 初始化
     * - content_block_delta  → 文本 / 工具参数增量
     * - content_block_stop   → 工具参数收尾(如有 input_json_delta 累积)
     * - message_delta        → stop_reason(不影响内容,记录即可)
     * - message_stop         → 终止
     * - error                → 错误
     */
    private val anthropicBlocks: MutableList<AnthropicBlock> = mutableListOf()

    private data class AnthropicBlock(
        val type: String,        // "text" 或 "tool_use"
        var id: String = "",
        var name: String = "",
        var textBuffer: StringBuilder = StringBuilder(),
        var argsBuffer: StringBuilder = StringBuilder()
    )

    private fun handleAnthropicDelta(root: com.google.gson.JsonObject, event: String?) {
        val type = root.get("type")?.asString ?: return
        when (type) {
            "error" -> {
                val err = root.get("error")
                val msg = if (err != null && err.isJsonObject) {
                    err.asJsonObject.get("message")?.asString ?: err.toString()
                } else err.toString()
                onDelta("\n[stream error] $msg")
            }
            "content_block_start" -> {
                val index = root.get("index")?.let { runCatching { it.asInt }.getOrNull() } ?: 0
                val block = root.getAsJsonObject("content_block")
                val blockType = block?.get("type")?.asString ?: return
                while (anthropicBlocks.size <= index) anthropicBlocks.add(AnthropicBlock("text"))
                val b = anthropicBlocks[index]
                anthropicBlocks[index] = b.copy(
                    type = blockType,
                    id = block?.get("id")?.asString ?: b.id,
                    name = block?.get("name")?.asString ?: b.name
                )
            }
            "content_block_delta" -> {
                val index = root.get("index")?.let { runCatching { it.asInt }.getOrNull() } ?: 0
                val delta = root.getAsJsonObject("delta") ?: return
                val deltaType = delta.get("type")?.asString ?: return
                while (anthropicBlocks.size <= index) anthropicBlocks.add(AnthropicBlock("text"))
                val b = anthropicBlocks[index]
                when (deltaType) {
                    "text_delta" -> {
                        val t = delta.get("text")?.asString ?: return
                        b.textBuffer.append(t)
                        onDelta(t)
                    }
                    "input_json_delta" -> {
                        val p = delta.get("partial_json")?.asString ?: return
                        b.argsBuffer.append(p)
                    }
                }
            }
            "content_block_stop" -> {
                // 工具调用 block 收尾:把累积的 partial_json 转成 tool_call
                val index = root.get("index")?.let { runCatching { it.asInt }.getOrNull() } ?: 0
                if (index in anthropicBlocks.indices) {
                    val b = anthropicBlocks[index]
                    if (b.type == "tool_use") {
                        toolCalls.add(
                            StreamedToolCall(
                                id = b.id,
                                name = b.name,
                                arguments = b.argsBuffer.toString()
                            )
                        )
                    }
                }
            }
            "message_delta", "message_stop", "ping", "message_start" -> {
                // 不影响 text / toolCalls,忽略
            }
        }
    }
}
