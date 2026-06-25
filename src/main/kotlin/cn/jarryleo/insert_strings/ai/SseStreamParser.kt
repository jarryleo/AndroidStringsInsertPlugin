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
 * - 文本增量 → 调 [onDelta] 回调,两个参数分别为「截至当前的 content 累计」与
 *   「截至当前的 reasoning 累计」(不是 delta)。调用方自己根据累计值切 EDT 写 UI。
 * - 工具调用 → 内部按 protocol 累积,流结束后通过 [toolCalls] 一次性读出
 *
 * 拆分 content / reasoning 的目的:
 * - 推理模型(DeepSeek-R1、QwQ、OpenAI o1/o3 等)会先在 `reasoning_content` /
 *   `thinking_delta` 里吐出思考过程,再在 `content` / `text_delta` 里给出最终回答。
 * - 旧版把两者合到同一个累加器,导致 UI 上 Thought 折叠区与主回复区显示同一段
 *   「思考+回答」混合文本,翻译/解释/总结这类「直接给答案」的场景,答案会被折进
 *   折叠区。拆开后,reply 字段(content)放最终回答,reasoning 字段(thinking)放
 *   思考过程,各司其职,UI 上 Thinking 区仅在 reasoning 非空时出现。
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
    /**
     * 增量文本回调。每收到一段 SSE 增量就触发一次,两个参数分别为:
     * - contentCumulative: 截至目前**最终回答**文本的累计全文
     * - reasoningCumulative: 截至目前**思考/推理**文本的累计全文
     * 跑在后台线程,调用方自行切回 EDT。非推理模型的 reasoningCumulative 始终为 ""。
     */
    private val onDelta: (contentCumulative: String, reasoningCumulative: String) -> Unit
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

    /** 内部累加器:content(最终回答)与 reasoning(思考/推理)分开维护。 */
    private val contentBuf = StringBuilder()
    private val reasoningBuf = StringBuilder()

    /** 流解析结束后供调用方读取的「最终回答」全文(OpenAI content / Anthropic text)。 */
    val contentText: String
        get() = contentBuf.toString()

    /** 流解析结束后供调用方读取的「思考/推理」全文(OpenAI reasoning_content / Anthropic thinking)。 */
    val reasoningText: String
        get() = reasoningBuf.toString()

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
            // 错误信息作为「最终回答」推到 content,UI 上当作文本展示
            contentBuf.append("\n[stream error] $msg")
            onDelta(contentBuf.toString(), reasoningBuf.toString())
            return
        }
        val choice = root.getAsJsonArray("choices")
            ?.firstOrNull { it.isJsonObject }
            ?.asJsonObject ?: return
        val delta = choice.getAsJsonObject("delta") ?: return

        // 文本增量(最终回答)—— 进 content 累加器
        delta.get("content")?.let { contentEl ->
            val text = when {
                contentEl.isJsonNull -> null
                contentEl.isJsonPrimitive -> contentEl.asString
                else -> contentEl.toString()
            }
            if (!text.isNullOrEmpty()) {
                contentBuf.append(text)
                onDelta(contentBuf.toString(), reasoningBuf.toString())
            }
        }
        // OpenAI-compatible reasoning models(DeepSeek-R1、OpenAI o1/o3、部分
        // OpenRouter 模型)把思考过程放在 reasoning_content,普通 content 只保留
        // 最终回答。两者必须分开累加,否则 UI 上 Thought 折叠区会与主回复区
        // 显示同一段「思考+回答」混合文本,把翻译/解释/总结的答案藏进折叠区。
        delta.get("reasoning_content")?.let { reasoningEl ->
            val text = when {
                reasoningEl.isJsonNull -> null
                reasoningEl.isJsonPrimitive -> reasoningEl.asString
                else -> reasoningEl.toString()
            }
            if (!text.isNullOrEmpty()) {
                reasoningBuf.append(text)
                onDelta(contentBuf.toString(), reasoningBuf.toString())
            }
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
        val type: String,        // "text" / "thinking" / "tool_use"
        var id: String = "",
        var name: String = "",
        // text_delta 进 textBuffer;thinking_delta 进 thinkingBuffer。
        // 拆开两个 buffer,避免思考过程与最终回答被混进同一个累加器。
        var textBuffer: StringBuilder = StringBuilder(),
        var thinkingBuffer: StringBuilder = StringBuilder(),
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
                // 错误信息作为「最终回答」推到 content,UI 上当作文本展示
                contentBuf.append("\n[stream error] $msg")
                onDelta(contentBuf.toString(), reasoningBuf.toString())
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
                        contentBuf.append(t)
                        onDelta(contentBuf.toString(), reasoningBuf.toString())
                    }
                    "thinking_delta" -> {
                        val t = delta.get("thinking")?.asString ?: return
                        b.thinkingBuffer.append(t)
                        reasoningBuf.append(t)
                        onDelta(contentBuf.toString(), reasoningBuf.toString())
                    }
                    "input_json_delta" -> {
                        val p = delta.get("partial_json")?.asString ?: return
                        b.argsBuffer.append(p)
                    }
                    "signature_delta" -> {
                        // Anthropic extended thinking signature; not useful for UI.
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
