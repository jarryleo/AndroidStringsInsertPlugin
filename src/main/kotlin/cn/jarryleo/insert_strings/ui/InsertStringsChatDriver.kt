package cn.jarryleo.insert_strings.ui

import cn.jarryleo.insert_strings.ai.AITranslator
import cn.jarryleo.insert_strings.ai.AiAction
import cn.jarryleo.insert_strings.ai.AiReply
import cn.jarryleo.insert_strings.ai.ChatMessage
import cn.jarryleo.insert_strings.ai.RetrySupport
import cn.jarryleo.insert_strings.ai.ToolCall
import cn.jarryleo.insert_strings.ai.ToolDefinitions
import cn.jarryleo.insert_strings.sheets.SheetsManager
import cn.jarryleo.insert_strings.xml.ContextManager
import cn.jarryleo.insert_strings.xml.KeyedStringsInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import javax.swing.SwingUtilities

/**
 * AI chat 域的总驱动器:
 *  - 工具调用主循环(最多 MAX_ITERATIONS 轮,或 task_complete / 用户停止)
 *  - action 分发(insert / sheets / strings / load_tool_doc / ask_user / task_complete)
 *  - 用户/AI 之间的消息收发(stop / quick send / option click / new chat)
 *  - Anthropic 协议所需的 tool_use/tool_result 配对补齐
 *  - 重复 key 插入的二次确认流程
 *
 * 拆分理由:这是整个类里逻辑最复杂、最容易出 bug 的一段;独立成类后,
 * InsertStringsUI 类本身只剩 state + 装配,主类体积会从 ~2900 行降到 ~300 行。
 */
internal class InsertStringsChatDriver(
    private val state: ChatStateHolder,
    private val stringsOps: InsertStringsStringsOpsController,
    private val sheetsOps: InsertStringsSheetsOpsController,
    private val fileOps: InsertStringsFileOpsController,
    private val chatContextBuilder: InsertStringsChatContextBuilder,
) {

    private val project: Project get() = state.project

    companion object {
        // 单次对话中 AI 调用工具的最大轮数。超过则强制结束,防止死循环。
        // 设 30 足以覆盖现实中的多步操作(检查+修正等),又能及时止损。
        private const val MAX_ITERATIONS = 30
        // load_tool_doc 按需加载工具文档的最大连续次数,防止 AI 反复加载文档而不执行操作。
        private const val MAX_TOOL_DOC_LOADS = 4
        // 单轮对话中 ask_user 的最大连续调用次数,防止 AI 反复追问形成死循环。
        // 每次用户实际回复(发送消息 / 点击选项)后重置为 0。
        private const val MAX_ASK_USER_CALLS = 3
        // 默认语言目录名(对应 values/ 目录,Android 默认英语资源)。
        // 作为兜底确保插入翻译时一定包含 values 键,避免英语写空。
        const val DEFAULT_LANGUAGE = "values"
    }

    /**
     * action 与其对应的 tool_call_id 配对,用于 execute 方法的精确回传。
     * 取代之前 `actions: List<T> + actionToolCallIds: List<String>` 的下标对齐模式,
     * 避免过滤后下标错位的 bug。
     */
    data class ActionWithToolCall(
        val action: AiAction,
        val toolCallId: String
    )

    private enum class DuplicateStringsChoice {
        USE_EXISTING,
        INSERT_NEW,
        CANCEL
    }

    private val duplicateStringsOptions = listOf(
        "使用现有翻译(可顺带检查/修正)",
        "插入新的翻译",
        "取消操作"
    )

    // PendingSheetsInsert 已抽到顶层 PendingSheetsInsert.kt,
    // 以便 ChatStateHolder 接口(以及其它跨 controller 类型)可以安全引用。

    fun sendChat() {
        val text = state.chatInput.trim()
        if (text.isEmpty() || state.chatSending) return
        state.chatInput = ""
        sendChatMessage(text)
    }

    fun quickSend(text: String) {
        if (text.isBlank() || state.chatSending) return
        sendChatMessage(text.trim())
    }

    fun sendChatMessage(text: String) {
        val askToolCallId = state.pendingAskUserToolCallId
        if (askToolCallId != null) {
            // 这是对 ask_user(无 options 场景)的回复,作为 tool_result 回传给 AI,
            // 而非新增 user 消息,否则会破坏 tool_use/tool_result 的配对语义。
            state.pendingAskUserToolCallId = null
            state.chatMessages.add(ChatMessage(role = "tool", content = text, toolCallId = askToolCallId))
        } else {
            state.chatMessages.add(ChatMessage(role = "user", content = text))
        }
        state.toolDocLoadCount = 0
        state.askUserCallCount = 0
        state.stopRequested = false
        bindRetryHooks()
        continueChatWithAi()
    }

    /**
     * 把 AITranslator 的重试回调 / 终止信号绑到当前 state。
     * - onRetryListener:每次重试时往 chatMessages 推一条「⏳ 网络异常,第 N 次重试(等待 X 秒)」气泡。
     *   用 [ChatMessage] 形式展示在 UI 上,用户能直观看到「卡在哪一步」「还要等多久」。
     * - retryShouldContinue:让 RetrySupport 在用户点 Stop 时能 100ms 醒一次检查,
     *   而不必等满退避时间,改善 Stop 体感。
     *
     * 必须在每次新对话开始时调一次(在 [sendChatMessage] 中已对接),
     * 旧对话的回调会在 [newChat] / [stopChat] / chatSending=false 路径自然解绑。
     */
    private fun bindRetryHooks() {
        AITranslator.retryShouldContinue = { !state.stopRequested }
        AITranslator.onRetryListener = { label, attempt, waitSeconds ->
            // RetrySupport 在后台线程触发,必须切回 EDT 后再写 chatMessages
            SwingUtilities.invokeLater {
                val content = "⏳ $label 失败,${attempt}/${RetrySupport.DEFAULT_MAX_RETRIES} 次重试中(约 ${waitSeconds}s 后重发请求)..."
                // 用 assistant role 渲染为左侧气泡;⏳ emoji + "失败/重试" 文本已能让用户一眼区分这是系统提示,
                // 而不是 AI 的真实回复(后者通常是正常段落或 "执行操作:xxx")。
                state.chatMessages.add(
                    ChatMessage(role = "assistant", content = content, protocolVisible = false)
                )
            }
        }
    }

    /**
     * 用户点击「停止」按钮:标记停止请求,让当前/下一轮 tool loop 检测到后立即终止。
     * 由于 AI HTTP 请求是阻塞的,正在进行的网络请求会等其完成,但其返回的 tool_call 不会再驱动新轮次。
     * 不可重复点击(无副作用但也无意义)。
     *
     * 同时也覆盖 ask_user 等待用户响应的场景:此时 chatSending=false 但 pendingAskUserToolCallId
     * 非空,需要补全 tool_result 以满足 Anthropic 协议要求,否则下次发送新消息时会 HTTP 400。
     */
    fun stopChat() {
        val hasPendingAsk = state.pendingAskUserToolCallId != null
        if (!state.chatSending && !hasPendingAsk) return
        state.stopRequested = true
        state.chatSending = false
        if (hasPendingAsk) {
            fillMissingToolResults("[已取消] 用户点击了停止按钮")
            state.pendingAskUserToolCallId = null
            state.chatMessages.add(ChatMessage(role = "assistant", content = "⏹ 已停止生成。"))
        }
        state.showToast("已停止生成")
    }

    /**
     * 打开「AI 上下文」弹窗:按需构造当前上下文(调用 buildChatContext),
     * 并尝试 pretty-print 成多行 JSON,方便用户直接查看 AI 真实收到的字段。
     */
    fun openContextPopup() {
        val raw = chatContextBuilder.build()
        state.chatContextText = runCatching {
            val element = com.google.gson.JsonParser.parseString(raw)
            com.google.gson.GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(element)
        }.getOrElse { raw }
        state.showContextPopup = true
    }

    fun closeContextPopup() {
        state.showContextPopup = false
    }

    fun newChat() {
        // 标记停止,防止正在运行的 tool loop 在清空后继续向 chatMessages 追加新消息
        state.stopRequested = true
        state.chatMessages.clear()
        state.chatInput = ""
        state.chatSending = false
        state.pendingAskUserToolCallId = null
        state.askUserCallCount = 0
        // 清理挂起的重复 key 询问,避免新会话中误把旧 option 路由到旧 pending
        state.pendingSheetsInsert = null
        state.pendingStringsInsert = null
        // 清掉 AITranslator 上挂的旧回调,避免下次新会话的 RetrySupport 还把提示塞到这条旧 chat
        AITranslator.onRetryListener = null
    }

    /**
     * 对话气泡选项按钮点击回调。
     * 优先级:待响应的 ask_user 工具调用 > 系统发起的重复 key 询问 > 兜底普通消息。
     */
    fun onChatOptionClick(messageIndex: Int, optionIndex: Int, option: String) {
        // 清除该消息的 options 使按钮消失
        if (messageIndex in state.chatMessages.indices) {
            val msg = state.chatMessages[messageIndex]
            state.chatMessages[messageIndex] = msg.copy(options = emptyList())
        }

        // 重置停止标志:用户主动操作意味着继续对话
        state.stopRequested = false

        // Priority 1:ask_user 工具调用 → 作为 tool result 回传给 AI
        val askToolCallId = state.pendingAskUserToolCallId
        if (askToolCallId != null) {
            state.pendingAskUserToolCallId = null
            state.askUserCallCount = 0
            state.chatMessages.add(
                ChatMessage(role = "tool", content = option, toolCallId = askToolCallId)
            )
            val context = chatContextBuilder.build()
            continueToolLoopInBackground(context, iteration = 0)
            return
        }

        // Priority 2:系统发起的重复 key 询问
        val pending = state.pendingSheetsInsert
        if (pending != null) {
            state.pendingSheetsInsert = null
            resolveDuplicateInsert(option, pending)
            return
        }

        // Priority 3:系统发起的 strings.xml 重复 key 询问
        val pendingStrings = state.pendingStringsInsert
        if (pendingStrings != null) {
            state.pendingStringsInsert = null
            resolveDuplicateStringsInsert(
                choice = duplicateStringsChoiceFromOptionIndex(optionIndex),
                pending = pendingStrings
            )
            return
        }

        // 兜底:作为普通用户消息发回(保持旧逻辑兼容)
        state.chatMessages.add(ChatMessage(role = "user", content = option))
        state.toolDocLoadCount = 0
        continueChatWithAi()
    }

    /**
     * 公共 AI 调用入口。供 sendChatMessage 和 onChatOptionClick 共用。
     * 调用前应已把用户消息(或 tool result)加入 chatMessages。
     *
     * 关键设计:采用原生 function calling 协议后,AI 必须调用 task_complete 才能终止。
     * runToolLoop 在后台持续驱动 AI 调用工具,直到达成 task_complete 或达到 MAX_ITERATIONS。
     */
    private fun continueChatWithAi() {
        state.chatSending = true
        val context = chatContextBuilder.build()
        continueToolLoopInBackground(context, iteration = 0)
    }

    private fun continueToolLoopInBackground(context: String, iteration: Int) {
        ApplicationManager.getApplication().executeOnPooledThread {
            runToolLoop(context, iteration)
        }
    }

    /**
     * 工具调用主循环。后台线程上执行:
     * 1. 调一次 AI
     * 2. 切回 EDT,记入 assistant 消息(含 tool_calls）
     * 3. processAiReply 处理 actions
     * 4. 若仍有未完成动作,继续循环
     *
     * 终止条件:AI 调用 task_complete / 用户主动停止 / 达到 MAX_ITERATIONS。
     * 停止请求检查:每轮开头与 AI 调用返回后各检查一次 stopRequested,
     * 命中时立即结束(正在进行的网络请求会等其完成,但其返回结果会被丢弃)。
     */
    private fun runToolLoop(context: String, iteration: Int) {
        if (state.stopRequested) {
            // 在 AI 调用之前就已停止(用户在迭代间隙点击了 Stop),不发起任何请求
            handleStoppedByUser()
            return
        }

        if (iteration >= MAX_ITERATIONS) {
            SwingUtilities.invokeLater {
                state.chatMessages.add(
                    ChatMessage(
                        role = "assistant",
                        content = "已达到最大工具调用轮数($MAX_ITERATIONS),强制结束。请检查任务后重试。"
                    )
                )
                state.chatSending = false
            }
            return
        }

        // 兜底安全网:在 AI 调用前扫描 chatMessages,补齐所有尚未配对的 tool_result。
        // 这一步能挡住所有前序流程(用户主动操作 / 后台线程竞态 / Stop 按钮等)
        // 引入的悬挂 tool_use,确保每次 API 请求都满足 Anthropic 协议要求
        // (tool_use 必须紧跟 tool_result,否则 HTTP 400)。
        try {
            SwingUtilities.invokeAndWait {
                fillMissingToolResults("[自动补全] 上一轮未配对的工具调用")
            }
        } catch (e: Exception) {
            // invokeAndWait 异常不应阻塞 AI 调用,继续走原流程
        }

        // === 流式 AI 调用 ===
        // 关键时序:
        //  1) 在 EDT 上同步快照 chatMessages(此刻是「干净」的历史,不含本轮 assistant 占位),
        //     并预占一条 assistant 空消息,拿到下标 placeholderIdx
        //     (snapshot 必须在 EDT 上做,SnapshotStateList 在非 EDT 线程读可能拿到过期数据)
        //  2) 后台线程发起 stream=true 请求,SSE 增量文本通过 onPartialText 回调
        //     切到 EDT 实时更新 placeholderIdx 位置的消息 content
        //  3) 流结束后用最终 AiReply(可能含 toolCalls)更新该消息的 toolCalls 字段,
        //     然后继续走 processAiReply 分发
        data class StreamSetup(val messages: List<ChatMessage>, val placeholderIdx: Int)
        val setup: StreamSetup = try {
            val captured = java.util.concurrent.atomic.AtomicReference<StreamSetup>()
            SwingUtilities.invokeAndWait {
                val snapshot = state.chatMessages.toList()
                val newIdx = state.chatMessages.size
                // 预占流式 assistant 消息:
                // - thinking = ""(流开始时为空,边流边塞)
                // - content = ""(最终回复,留给 task_complete summary 或纯文本)
                // - streaming = true(UI 据此显示「Thinking」+ loading + 滚动信息流)
                state.chatMessages.add(
                    ChatMessage(role = "assistant", content = "", streaming = true)
                )
                captured.set(StreamSetup(snapshot, newIdx))
            }
            captured.get() ?: return
        } catch (e: Exception) {
            // invokeAndWait 失败(中断/异常):退回非流式
            val snapshot = state.chatMessages.toList()
            val reply = runCatching { AITranslator.chat(snapshot, context) }
                .getOrElse {
                    SwingUtilities.invokeLater {
                        // content 直接放错误文案,UI 端会作为回复区渲染(无 thinking 折叠区)
                        state.chatMessages.add(
                            ChatMessage(
                                role = "assistant",
                                content = "AI 请求失败:${it.message ?: "unknown"}"
                            )
                        )
                        // 兜底报告:重试耗尽后明确告诉用户本轮已停止
                        if (it is InterruptedException) {
                            state.chatMessages.add(
                                ChatMessage(role = "assistant", content = "⏹ 已停止生成。")
                            )
                        } else if (it.message?.contains("cancelled", ignoreCase = true) != true) {
                            state.chatMessages.add(
                                ChatMessage(
                                    role = "assistant",
                                    content = "❌ AI 请求多次重试仍失败(${RetrySupport.DEFAULT_MAX_RETRIES + 1} 次尝试)," +
                                        "已停止本轮对话。\n请检查网络/AI 服务后重新发送消息。\n错误信息:${it.message ?: "unknown"}"
                                )
                            )
                        }
                        state.chatSending = false
                    }
                    return
                }
            finishWithReply(reply, context, iteration, snapshot, -1)
            return
        }
        val messagesSnapshot = setup.messages
        val placeholderIdx = setup.placeholderIdx

        val reply: AiReply = try {
            AITranslator.chatStream(
                messages = messagesSnapshot,
                context = context,
                onPartialText = { fullText ->
                    // 跑在后台线程,先做轻量 stop 检查
                    if (state.stopRequested) return@chatStream
                    // 把最新思考文本切回 EDT 写入 placeholder 的 thinking 字段
                    // (注意:不写 content,留给最终 task_complete summary 或纯文本回复用)
                    SwingUtilities.invokeLater {
                        if (placeholderIdx < state.chatMessages.size) {
                            val current = state.chatMessages[placeholderIdx]
                            if (current.thinking != fullText) {
                                state.chatMessages[placeholderIdx] = current.copy(thinking = fullText)
                            }
                        }
                    }
                }
            )
        } catch (e: Exception) {
            SwingUtilities.invokeLater {
                if (placeholderIdx < state.chatMessages.size) {
                    val current = state.chatMessages[placeholderIdx]
                    // 错误时把错误文案放在 content(回复区),thinking 清空避免与 content 重复
                    state.chatMessages[placeholderIdx] = current.copy(
                        content = "AI 请求失败:${e.message ?: "unknown"}",
                        thinking = "",
                        streaming = false
                    )
                }
                // 兜底:若是 RetrySupport 抛出的重试耗尽异常,明确告诉用户本轮已停止。
                // RetrySupport 用 InterruptedException 表示用户点 Stop,用普通 Exception 表示重试 N 次后失败。
                if (e is InterruptedException) {
                    state.chatMessages.add(
                        ChatMessage(
                            role = "assistant",
                            content = "⏹ 已停止生成。"
                        )
                    )
                } else if (e.message?.contains("cancelled", ignoreCase = true) != true) {
                    // 排除"用户主动取消"语义后,剩下的就是"重试耗尽仍失败",报告给用户
                    state.chatMessages.add(
                        ChatMessage(
                            role = "assistant",
                            content = "❌ AI 请求多次重试仍失败(${RetrySupport.DEFAULT_MAX_RETRIES + 1} 次尝试)," +
                                "已停止本轮对话。\n请检查网络/AI 服务后重新发送消息。\n错误信息:${e.message ?: "unknown"}"
                        )
                    )
                }
                state.chatSending = false
            }
            return
        }

        // 用户在 AI 流式请求期间点击了 Stop:丢弃本次响应,不再处理 tool_calls,
        // 避免已请求到的 tool_use 在下次发送时因缺少 tool_result 而触发 Anthropic 报错。
        if (state.stopRequested) {
            handleStoppedByUserWithPlaceholder(placeholderIdx)
            return
        }

        finishWithReply(reply, context, iteration, messagesSnapshot, placeholderIdx)
    }

    /**
     * 把 AI 最终回复合并到流式生成的 placeholder 消息上,然后走 [processAiReply] 分发。
     *
     * 关键设计:
     * - 思考文本(thinking)由流式回调实时写入,本方法不再动它;
     * - content 只有在「纯文本回复」或「task_complete summary」时才有值,
     *   「带真实工具调用」的中间回合 content 保持为空,仅由 thinking + 后续 tool 气泡承担展示;
     * - 过滤掉 task_complete 后再决定「执行操作:」占位文案,避免终止信号被显示成工具名;
     * - 末尾把 processAiReply 跑一遍,其中的 handleTaskComplete 会通过
     *   state.chatMessages.indexOfLast 定位当前流式消息并把 summary 原地写进去,
     *   不再追加新消息造成重复气泡。
     */
    private fun finishWithReply(
        reply: AiReply,
        context: String,
        iteration: Int,
        @Suppress("UNUSED_PARAMETER") messagesSnapshot: List<ChatMessage>,
        placeholderIdx: Int = -1
    ) {
        SwingUtilities.invokeLater {
            // 1) 把流式累积到的文本 / toolCalls 合并到 placeholder(或新建一条)
            //    字段布局原则:
            //      - thinking: 模型在调用工具前的中间发言(「思考」语义),
            //        流式时实时显示,流结束后折叠为可展开区。
            //      - content: 给用户看的「最终回复」——纯文本对话回合就是模型全文,
            //        function-calling 回合则是「执行操作:xxx」或 task_complete summary。
            //    在纯文本回合里两者可能重复,但 Thinking 区默认折叠,保留它能让用户
            //    在每个 AI 回复气泡里回看流式思考文字。
            val realToolCalls = reply.toolCalls.filter { it.name != ToolDefinitions.TOOL_TASK_COMPLETE }
            val hasTaskComplete = reply.toolCalls.any { it.name == ToolDefinitions.TOOL_TASK_COMPLETE }
            val existingThinking = if (placeholderIdx in 0 until state.chatMessages.size) {
                state.chatMessages[placeholderIdx].thinking
            } else {
                ""
            }
            // 关键修复:必须把「解析失败」的 tool_call 一起写入 assistant 消息的 toolCalls 字段,
            // 否则下一轮 OpenAI/DeepSeek 协议校验会失败:
            //   "Messages with role 'tool' must be a response to a preceding message with 'tool_calls'"
            // —— 之前 [finalToolCalls] 只用 realToolCalls(排除 failed),导致下面追加的
            // "解析失败" tool_result 的 tool_call_id 在上一条 assistant 消息里找不到对应 tool_use,
            // DeepSeek / OpenAI 立即返回 HTTP 400。
            // UI 层只对 realToolCalls 做"summarizeToolCalls"展示用,真正驱动 processAiReply 的是
            // reply.actions(只含解析成功的),所以混入 failed 不会重复执行。
            val finalToolCalls = realToolCalls + reply.failedToolCalls
            val (finalContent, finalThinking) = when {
                // 情况 A:模型纯文本回复(无工具调用)——内容就是回复本身,
                // 流式累积文本保留在折叠 Thought 区。
                finalToolCalls.isEmpty() -> {
                    Pair(reply.reply, existingThinking)
                }
                // 情况 B:只有 task_complete 终止信号
                // content 留空,由 handleTaskComplete 写入 summary;
                // thinking 保留流式累积文本,作为「思考过程」展示
                realToolCalls.isEmpty() && hasTaskComplete -> {
                    Pair("", reply.reply)
                }
                // 情况 C:有真实工具调用(可叠加 task_complete,但 task_complete 之后会被前面分支截走,
                //    所以这里只可能是纯真实工具调用)
                else -> {
                    val summary = if (realToolCalls.isNotEmpty()) {
                        "执行操作: ${summarizeToolCalls(realToolCalls)}"
                    } else if (reply.failedToolCalls.isNotEmpty()) {
                        // 全部解析失败:在 content 上提示一下,用户能直接看到失败信息(否则 UI 上只看到一行「执行操作:」)
                        "执行操作失败: ${summarizeToolCalls(reply.failedToolCalls)} 参数无法解析"
                    } else {
                        ""
                    }
                    Pair(summary, reply.reply)
                }
            }

            if (placeholderIdx in 0 until state.chatMessages.size) {
                val current = state.chatMessages[placeholderIdx]
                if (current.content != finalContent ||
                    current.toolCalls != finalToolCalls ||
                    current.thinking != finalThinking ||
                    current.streaming
                ) {
                    state.chatMessages[placeholderIdx] = current.copy(
                        content = finalContent,
                        toolCalls = finalToolCalls,
                        thinking = finalThinking,
                        streaming = false
                    )
                }
            } else {
                state.chatMessages.add(
                    ChatMessage(
                        role = "assistant",
                        content = finalContent,
                        toolCalls = finalToolCalls,
                        thinking = finalThinking,
                    )
                )
            }

            // 2) 为「解析失败」的 tool_use 补 tool_result(防止 Anthropic 报
            //    "tool_use.id 'xxx' was found without a corresponding tool_result
            //    block immediately after")。这些 tool_use 占着 assistant 的位置,
            //    但 actions 中没有对应项,会被 processAiReply 漏掉,必须在此处补齐。
            reply.failedToolCalls.forEach { failed ->
                state.chatMessages.add(
                    ChatMessage(
                        role = "tool",
                        content = "[工具执行结果] 类型:unknown(${failed.name}) 状态:解析失败 " +
                            "信息:该工具调用的参数无法解析或工具名未注册。请检查调用格式后重试。",
                        toolCallId = failed.id
                    )
                )
            }

            // 3) 建立 action → toolCallId 的下标映射。
            //    parseAiReply 已保证 reply.actions 与 reply.toolCalls 严格 1:1 对齐,
            //    这里的下标取 id 才是正确的(之前 mapNotNull 会丢项导致错位)。
            val actionToolCallIds = reply.actions.indices.map { i ->
                reply.toolCalls.getOrNull(i)?.id.orEmpty()
            }

            // 4) 分发处理
            //    handleTaskComplete 内部会通过 state.chatMessages.indexOfLast 定位当前流式消息,
            //    把 summary 原地写进去,避免再追加一条 assistant 消息造成重复气泡
            processAiReply(reply, actionToolCallIds, context, iteration)

            // 5) 解析失败兜底:如果本轮 AI 调用了 tool_use,但所有 tool_call 全部解析失败
            //    (actions 为空、failedToolCalls 非空),processAiReply 会走到
            //    "没有任何可执行 tool call"的分支,把 chatSending = false 等待用户输入。
            //    这会让用户看到一个空气泡后对话卡死,直到手动重新发送。
            //    此场景下应当主动继续 tool loop,把错误 tool_result 喂回给 AI,
            //    让 AI 看到失败信息后自动修正并重新调用合法工具,无需用户介入。
            //
            // 关键约束(配合上面 finalToolCalls 含 failed 的修复):assistant 消息的
            // toolCalls 现在保留了 failed 项,所以"解析失败" tool_result 的 tool_call_id
            // 能匹配上 tool_use,DeepSeek 不会再因 "Messages with role 'tool' must be a
            // response to a preceding message with 'tool_calls'" 报 HTTP 400 而终止对话。
            //
            // 当 actions 非空 + failedToolCalls 非空(混合)时,processAiReply 已根据
            // 实际成功的 action 走完分支(task_complete 终止 / ask_user 暂停 / 写操作继续),
            // 不需要我们再额外 continue。
            if (reply.actions.isEmpty() && reply.failedToolCalls.isNotEmpty()) {
                continueToolLoopInBackground(context, iteration + 1)
            }
        }
    }

    /**
     * 处理一次 AI 回复:按优先级分发到 task_complete / ask_user / load_tool_doc / insert / sheets 各分支。
     * 各分支处理完后再决定是否继续 tool loop。
     *
     * 关键设计:统一用 [ActionWithToolCall] 配对列表传递 action 与其 tool_call_id,
     * 杜绝「过滤后下标错位」的历史 bug。写操作(insert/update)还要先做模块一致性校验,
     * 防止 AI 一次回合内把不同 strings 插入到不同模块。
     */
    private fun processAiReply(
        reply: AiReply,
        actionToolCallIds: List<String>,
        context: String,
        iteration: Int
    ) {
        // 构造精确配对:每个 action 携带自己的 tool_call_id,不再依赖下标
        val pairs: List<ActionWithToolCall> = reply.actions.mapIndexedNotNull { i, action ->
            val toolCallId = actionToolCallIds.getOrNull(i).orEmpty()
            if (toolCallId.isEmpty()) null else ActionWithToolCall(action, toolCallId)
        }
        // 关键:跟踪本回合尚未被处理的 pairs。每个优先级处理完自己的子集后,
        // 必须为剩余 pairs 补上「已跳过」tool_result。否则 Anthropic 协议下,
        // assistant 消息中的 tool_use 在下一轮会因「缺少 tool_result」而 HTTP 400:
        //   tool_use blocks must be immediately followed by tool_result blocks
        //   in the next user message
        // OpenAI 协议虽然容错,但同样会污染上下文;统一补 skipped 最稳妥。
        val unprocessed = pairs.toMutableList()

        // Priority 1: task_complete 终止对话
        val taskEntry = unprocessed.firstOrNull { it.action is AiAction.TaskComplete }
        if (taskEntry != null) {
            unprocessed.remove(taskEntry)
            handleTaskComplete(taskEntry.action as AiAction.TaskComplete)
            // task_complete 与其它 tool_use 同时返回时,其它 tool_use 视为被取消
            addSkippedToolResults(unprocessed, "因 task_complete 终止对话而跳过")
            return
        }

        // Priority 2: ask_user
        val askUserEntry = unprocessed.firstOrNull { it.action is AiAction.AskUser }
        if (askUserEntry != null) {
            unprocessed.remove(askUserEntry)
            val askAction = askUserEntry.action as AiAction.AskUser
            val askToolCallId = askUserEntry.toolCallId.takeIf { it.isNotEmpty() }

            // 安全网:限制单轮内 ask_user 连续调用次数,防止 AI 反复追问形成死循环。
            state.askUserCallCount++
            if (state.askUserCallCount > MAX_ASK_USER_CALLS) {
                if (askToolCallId != null) {
                    state.chatMessages.add(
                        ChatMessage(
                            role = "tool",
                            content = "[已取消] ask_user 调用次数已达上限($MAX_ASK_USER_CALLS 次),系统为防止死循环自动终止。" +
                                "请基于已有信息完成任务(task_complete),或直接采取合理操作。",
                            toolCallId = askToolCallId
                        )
                    )
                }
                addSkippedToolResults(
                    unprocessed,
                    "因 ask_user 调用次数超限被强制终止"
                )
                state.chatMessages.add(
                    ChatMessage(
                        role = "assistant",
                        content = "⏹ ask_user 调用次数已达上限($MAX_ASK_USER_CALLS 次),已自动终止。请基于已有信息继续。"
                    )
                )
                state.chatSending = false
                state.pendingAskUserToolCallId = null
                return
            }

            if (askAction.options.isNotEmpty()) {
                // 带 options:把 question 写入 assistant 消息的 content(让用户看到问题),
                // options 挂到 options 字段(让 UI 渲染按钮)。记录 toolCallId,等待用户点击。
                // 修复:之前只挂 options,question 文本被吞掉,用户只看到「执行操作: ask_user」+ 按钮,
                //      完全不知道 AI 在问什么(流式累积的 thinking 在回合结束后被折叠,看不到)。
                // 保留 thinking:用户希望每个 AI 回复气泡都能展开查看思考文字。
                // question 写入 content 后,折叠态仍以问题本身为主,思考只在用户展开时出现。
                val lastIdx = state.chatMessages.lastIndex
                if (lastIdx >= 0) {
                    state.chatMessages[lastIdx] = state.chatMessages[lastIdx].copy(
                        content = askAction.question,
                        options = askAction.options
                    )
                }
            } else {
                // 无 options:把 question 写入 assistant 消息的 content,
                // 提示用户到输入框中回复;toast 作为辅助提示。thinking 保留为可展开详情。
                val lastIdx = state.chatMessages.lastIndex
                if (lastIdx >= 0) {
                    state.chatMessages[lastIdx] = state.chatMessages[lastIdx].copy(
                        content = askAction.question
                    )
                }
                state.showToast(askAction.question)
            }
            // 无论是否带 options,都暂停 loop 等待用户响应。
            // 修复:之前无 options 时自动回传「已向用户显示问题,无需回复」并继续 loop,
            //      导致 AI 反复调用 ask_user 而用户根本没机会回复,形成死循环。
            state.pendingAskUserToolCallId = askToolCallId
            addSkippedToolResults(
                unprocessed,
                "因 ask_user 等待用户响应而跳过(用户点击选项或发送消息后 AI 可继续)"
            )
            state.chatSending = false
            return
        }

        // Priority 3: load_tool_doc
        val loadDocEntries = unprocessed.filter { it.action is AiAction.LoadToolDoc }
        if (loadDocEntries.isNotEmpty()) {
            unprocessed.removeAll(loadDocEntries)
            handleLoadToolDoc(loadDocEntries, context, iteration)
            addSkippedToolResults(unprocessed, "因 load_tool_doc 加载文档而跳过")
            return
        }

        // Priority 4-5: strings.xml 写操作(insert_strings / update_string / delete_string)统一做模块一致性校验
        val writeEntries = unprocessed.filter {
            it.action is AiAction.InsertStrings ||
                it.action is AiAction.UpdateString ||
                it.action is AiAction.DeleteString
        }
        if (writeEntries.isNotEmpty()) {
            val conflict = detectWriteModuleConflict(writeEntries)
            if (conflict != null) {
                unprocessed.removeAll(writeEntries)
                // 跨模块冲突:整批拒绝,把错误回传给 AI 让其修正
                rejectWriteModuleConflict(writeEntries, conflict, context, iteration)
                addSkippedToolResults(
                    unprocessed,
                    "因 strings 写动作跨模块冲突被拒,本批其它 action 一起跳过"
                )
                return
            }
        }

        // Priority 4: query_keys / read_string / update_string / delete_string / find_keys_by_text(strings.xml 主动操作能力)
        // 注意:update_string / delete_string 已被上面的模块一致性校验拦截(若有问题就 reject),此处仅含合法动作
        val stringReadEntries = unprocessed.filter {
            it.action is AiAction.QueryKeys ||
                it.action is AiAction.ReadString ||
                it.action is AiAction.FindKeysByText
        }
        val stringWriteEntries = unprocessed.filter {
            it.action is AiAction.UpdateString || it.action is AiAction.DeleteString
        }
        val stringsEntries = stringReadEntries + stringWriteEntries
        if (stringsEntries.isNotEmpty()) {
            unprocessed.removeAll(stringsEntries)
            executeStringsOps(stringsEntries, context, iteration)
            addSkippedToolResults(unprocessed, "因已执行 strings.xml 操作而跳过")
            return
        }

        // Priority 5: insert_strings
        val insertEntries = unprocessed.filter { it.action is AiAction.InsertStrings }
        if (insertEntries.isNotEmpty()) {
            unprocessed.removeAll(insertEntries)
            executeInsertActions(insertEntries, context, iteration)
            addSkippedToolResults(unprocessed, "因已执行 insert_strings 而跳过")
            return
        }

        // Priority 6: sheets_operation / find_rows_by_text
        val sheetsEntries = unprocessed.filter {
            it.action is AiAction.SheetsOperation || it.action is AiAction.FindRowsByText
        }
        if (sheetsEntries.isNotEmpty()) {
            unprocessed.removeAll(sheetsEntries)
            executeSheetsActions(sheetsEntries, context, iteration)
            addSkippedToolResults(unprocessed, "因已执行 sheets_operation 而跳过")
            return
        }

        // Priority 7: 文件操作域(get_editor_file / read_file / edit_file /
        //   create_file / search_in_files / find_references / list_files)
        val fileEntries = unprocessed.filter {
            it.action is AiAction.GetEditorFile ||
                it.action is AiAction.ReadFile ||
                it.action is AiAction.EditFile ||
                it.action is AiAction.CreateFile ||
                it.action is AiAction.SearchInFiles ||
                it.action is AiAction.FindReferences ||
                it.action is AiAction.ListFiles
        }
        if (fileEntries.isNotEmpty()) {
            unprocessed.removeAll(fileEntries)
            executeFileOps(fileEntries, context, iteration)
            addSkippedToolResults(unprocessed, "因已执行文件操作而跳过")
            return
        }

        // 没有任何可执行 tool call:AI 只是在说,等用户输入
        addSkippedToolResults(unprocessed, "本轮 AI 未调用可识别的工具")
        state.chatSending = false
    }

    /**
     * 为指定 [unprocessedPairs] 中的每个 action 添加一条「已跳过」的 tool_result,
     * 用于在 Anthropic / OpenAI 协议下满足「每个 tool_use 必须紧跟一个 tool_result」的要求。
     * 没有 tool_result 的 tool_use 会让下一轮 API 调用直接返回 HTTP 400。
     *
     * 调用方应仅在确认这些 action 不会在本轮被执行时调用;已经发了「成功/失败」tool_result 的不必重复。
     */
    private fun addSkippedToolResults(
        unprocessedPairs: List<ActionWithToolCall>,
        reason: String
    ) {
        unprocessedPairs.forEach { (action, toolCallId) ->
            if (toolCallId.isEmpty()) return@forEach
            val actionLabel = when (action) {
                is AiAction.InsertStrings -> "insert_strings"
                is AiAction.UpdateString -> "update_string"
                is AiAction.DeleteString -> "delete_string"
                is AiAction.QueryKeys -> "query_keys"
                is AiAction.ReadString -> "read_string"
                is AiAction.FindKeysByText -> "find_keys_by_text"
                is AiAction.FindRowsByText -> "find_rows_by_text"
                is AiAction.AskUser -> "ask_user"
                is AiAction.LoadToolDoc -> "load_tool_doc"
                is AiAction.SheetsOperation -> "sheets_operation(${action.operation.name.lowercase()})"
                is AiAction.GetEditorFile -> "get_editor_file"
                is AiAction.ReadFile -> "read_file"
                is AiAction.EditFile -> "edit_file"
                is AiAction.CreateFile -> "create_file"
                is AiAction.SearchInFiles -> "search_in_files"
                is AiAction.FindReferences -> "find_references"
                is AiAction.ListFiles -> "list_files"
                is AiAction.TaskComplete -> "task_complete"
            }
            state.chatMessages.add(
                ChatMessage(
                    role = "tool",
                    content = "[工具执行结果] 类型:$actionLabel 状态:已跳过 信息:$reason。如需执行,请在下一轮重新调用。",
                    toolCallId = toolCallId
                )
            )
        }
    }

    /**
     * 检测 write actions(insert_strings + update_string + delete_string)是否指定了不同模块。
     * @return 冲突描述(列出所有显式指定的 module);若全部一致或仅有 0~1 个显式 module,返回 null
     */
    private fun detectWriteModuleConflict(
        writeEntries: List<ActionWithToolCall>
    ): String? {
        val explicitModules = writeEntries
            .mapNotNull { entry ->
                val m = when (val action = entry.action) {
                    is AiAction.InsertStrings -> action.module
                    is AiAction.UpdateString -> action.module
                    is AiAction.DeleteString -> action.module
                    else -> null
                }?.trim()?.takeIf { it.isNotEmpty() }
                normalizeExplicitWriteModule(m)
            }
            .distinct()
        return if (explicitModules.size > 1) explicitModules.joinToString(", ") else null
    }

    private fun normalizeExplicitWriteModule(moduleName: String?): String? {
        val candidate = moduleName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val context = ContextManager.getInstance(project)
        if (candidate == context.contextInfo?.projectName) return null
        return context.resolveDisplayModuleName(candidate) ?: candidate
    }

    /**
     * 整批拒绝跨模块的 write actions,给每个 action 发错误 tool_result。
     * AI 会在下一轮看到错误并修正(统一 module 或全部省略 module 让系统用 currentModule)。
     */
    private fun rejectWriteModuleConflict(
        writeEntries: List<ActionWithToolCall>,
        explicitModulesCsv: String,
        context: String,
        iteration: Int
    ) {
        val errorMsg = buildString {
            append("[工具执行异常] 跨模块冲突:本轮内 insert_strings/update_string/delete_string 动作指定了多个不同 module(")
            append(explicitModulesCsv)
            append(")。同一 AI 回合内的所有字符串写入必须在同一模块。")
            append("请重新组织 actions:全部省略 module 参数(系统使用 currentModule),")
            append("或全部显式指定同一 module。若确实需要写入多个模块,请拆成多个 AI 回合。")
        }
        state.chatMessages.add(
            ChatMessage(role = "tool", content = errorMsg, toolCallId = writeEntries.first().toolCallId)
        )
        // 其余 action 同样发错误,避免协议错位
        writeEntries.drop(1).forEach { entry ->
            state.chatMessages.add(
                ChatMessage(role = "tool", content = errorMsg, toolCallId = entry.toolCallId)
            )
        }
        continueToolLoopInBackground(context, iteration + 1)
    }

    private fun handleTaskComplete(complete: AiAction.TaskComplete) {
        val icon = when (complete.status.lowercase()) {
            "success" -> "✅"
            "partial" -> "⚠️"
            "failed" -> "❌"
            else -> "ℹ️"
        }
        val text = buildString {
            append(icon).append(' ').append(complete.summary)
            if (!complete.notes.isNullOrBlank()) {
                append("\n\n").append(complete.notes)
            }
        }
        // 把 task_complete summary 写入当前流式 assistant 气泡(同一条消息),
        // 而不是再追加一条新消息——这样思考(流式累积的 thinking)和回复(content)
        // 会落在同一个气泡内,符合"思考和回复在同一个气泡内"的设计。
        // 若对话历史里没有 assistant 消息(理论上不会发生,只是兜底),才追加新消息。
        val lastIdx = state.chatMessages.indexOfLast { it.role == "assistant" }
        if (lastIdx >= 0) {
            val current = state.chatMessages[lastIdx]
            state.chatMessages[lastIdx] = current.copy(
                content = text,
                streaming = false,
                toolCalls = emptyList()
            )
        } else {
            state.chatMessages.add(ChatMessage(role = "assistant", content = text))
        }
        state.chatSending = false
    }

    /**
     * 用户在 tool loop 中点击「停止」后的统一收尾:
     * - 追加一条 assistant 消息提示已停止
     * - 重置 stopRequested,以便下一轮用户发送新消息时重新进入循环
     * - chatSending 已由 stopChat() 设为 false,这里不再重复设置
     */
    private fun handleStoppedByUser() {
        SwingUtilities.invokeLater {
            // 防御性:在添加「已停止」消息前,先把对话历史中所有尚未配对的 tool_use
            // 补上「已取消」tool_result,避免下一轮 AI 请求时 Anthropic 报:
            //   tool_use.id 'xxx' was found without a corresponding tool_result
            //   block immediately after
            // 这覆盖了 ask_user 等待用户点选项、用户却点 Stop 等导致 tool_use 悬挂的场景。
            fillMissingToolResults("[已取消] 用户点击了停止按钮,该工具调用未执行。如需执行,请在下一轮重新发起。")
            state.chatMessages.add(ChatMessage(role = "assistant", content = "⏹ 已停止生成。"))
            state.stopRequested = false
        }
    }

    /**
     * 流式场景下的停止收尾:与 [handleStoppedByUser] 类似,但因为 placeholder 已经把
     * AI 正在写的文本实时渲染出来了,不需要再额外追加「⏹ 已停止」消息(避免重复占两行),
     * 改为把 placeholder 内容拼接上停止提示并清空 toolCalls。
     *
     * 之所以丢弃 toolCalls:流被打断时,模型返回的 tool_use 块可能不完整(id/name/args 缺失),
     * 把它原样写回 chatMessages 会导致下一轮 Anthropic 协议校验失败。
     * 配合 [fillMissingToolResults] 的兜底,可确保下次发送新消息时无悬挂 tool_use。
     */
    private fun handleStoppedByUserWithPlaceholder(placeholderIdx: Int) {
        SwingUtilities.invokeLater {
            fillMissingToolResults("[已取消] 用户点击了停止按钮,该工具调用未执行。如需执行,请在下一轮重新发起。")
            if (placeholderIdx in 0 until state.chatMessages.size) {
                val current = state.chatMessages[placeholderIdx]
                val stopMarker = "\n\n⏹ 已停止生成。"
                val finalContent = current.content + stopMarker
                state.chatMessages[placeholderIdx] = current.copy(
                    content = finalContent,
                    toolCalls = emptyList(),
                    streaming = false
                )
            } else {
                state.chatMessages.add(ChatMessage(role = "assistant", content = "⏹ 已停止生成。"))
            }
            state.stopRequested = false
        }
    }

    /**
     * 扫描 chatMessages,找出所有尚未配对的 tool_use 块(assistant.toolCalls 中存在、
     * 但 chatMessages 中没有对应 toolCallId 的 tool 消息),为它们补一条 tool_result
     * 以满足 Anthropic 协议要求(每个 tool_use 必须紧跟 tool_result,否则 HTTP 400)。
     *
     * 关键:合成 tool_result 必须**插入到产生 tool_use 的 assistant 消息所在 block 内**,
     * 而且要排在 block 内所有非 tool_result 消息之前,否则会被 user 文本消息"截胡"
     * —— 那时 tool_result 落到 user 消息之后,Anthropic 仍会拒绝
     * (tool_use blocks must be immediately followed by tool_result blocks in the next user message)。
     *
     * 插入位置算法:对每个含 tool_uses 的 assistant 消息,确定其 block(到下一个 assistant
     * 或 chatMessages 末尾),在 block 内**第一个非 tool_result 消息之前**(若 block 全部是
     * tool_result 则在 block 末尾)插入合成 tool_result;按 insertAt 降序插入以避免下标错位。
     *
     * @param reason 写入 tool_result content 的说明,告诉 AI 为什么这个 action 没被执行
     */
    private fun fillMissingToolResults(reason: String) {
        if (state.chatMessages.isEmpty()) return

        // 1) 收集所有 (insertAt, toolResultMessage),稍后按 insertAt 降序插入
        val toInsert = mutableListOf<Pair<Int, ChatMessage>>()

        // 2) 助手查名字表(toolCallId -> 工具名),用于合成 tool_result 的内容描述
        val nameById = mutableMapOf<String, String>()
        state.chatMessages.forEach { msg ->
            if (msg.protocolVisible && msg.role == "assistant") {
                msg.toolCalls.forEach { tc -> nameById.putIfAbsent(tc.id, tc.name) }
            }
        }

        // 3) 遍历每个含 tool_uses 的 assistant,计算其 block 与插入点
        state.chatMessages.forEachIndexed { idx, msg ->
            if (!msg.protocolVisible || msg.role != "assistant" || msg.toolCalls.isEmpty()) return@forEachIndexed

            // block = [idx+1, endOfBlock),endOfBlock 是下一个参与协议的 assistant 或 chatMessages 末尾
            var endOfBlock = idx + 1
            while (
                endOfBlock < state.chatMessages.size &&
                !(state.chatMessages[endOfBlock].protocolVisible && state.chatMessages[endOfBlock].role == "assistant")
            ) {
                endOfBlock++
            }

            // 在 block 内找到所有已有的 tool_result(tool 消息 + user 带 toolCallId)
            val pairedIds = mutableSetOf<String>()
            for (i in (idx + 1) until endOfBlock) {
                val m = state.chatMessages[i]
                if (!m.protocolVisible) continue
                if (m.role == "tool" || (m.role == "user" && m.toolCallId != null)) {
                    m.toolCallId?.let { pairedIds.add(it) }
                }
            }

            // 找出本 assistant 的未配对 tool_use
            val unpaired = msg.toolCalls.filter { it.id !in pairedIds }
            if (unpaired.isEmpty()) return@forEachIndexed

            // 在 block 内找第一个非 tool_result 的位置(没有则用 block 末尾)
            var insertAt = endOfBlock
            for (i in (idx + 1) until endOfBlock) {
                val m = state.chatMessages[i]
                if (!m.protocolVisible) continue
                val isToolResult = m.role == "tool" || (m.role == "user" && m.toolCallId != null)
                if (!isToolResult) {
                    insertAt = i
                    break
                }
            }

            unpaired.forEach { tc ->
                toInsert.add(
                    insertAt to ChatMessage(
                        role = "tool",
                        content = "[工具执行结果] 类型:${tc.name} 状态:已取消 信息:$reason",
                        toolCallId = tc.id
                    )
                )
            }
        }

        // 4) 按 insertAt 降序插入,避免下标位移影响后续插入位置
        toInsert.sortedByDescending { it.first }.forEach { (insertAt, message) ->
            state.chatMessages.add(insertAt, message)
        }
    }

    /**
     * 处理 load_tool_doc:为每个 load_tool_doc 调用添加对应的 tool result 注入文档,
     * 然后继续 tool loop 让 AI 返回实际执行动作。
     */
    private fun handleLoadToolDoc(
        entries: List<ActionWithToolCall>,
        context: String,
        iteration: Int
    ) {
        if (state.toolDocLoadCount >= MAX_TOOL_DOC_LOADS) {
            state.chatMessages.add(
                ChatMessage(
                    role = "assistant",
                    content = "工具文档加载次数已达上限($MAX_TOOL_DOC_LOADS 次),请直接执行操作或向用户说明情况。"
                )
            )
            state.chatSending = false
            return
        }
        state.toolDocLoadCount++

        val requestedTools = entries.mapNotNull { (action, _) ->
            (action as? AiAction.LoadToolDoc)?.tool
        }.distinct()
        val docsToInject = requestedTools.mapNotNull { tool ->
            AITranslator.getToolDoc(tool)?.let { doc -> tool to doc }
        }

        val summary = if (docsToInject.isEmpty()) {
            val available = AITranslator.availableToolDocs().joinToString(", ")
            "[工具文档加载失败] 请求的工具名不存在。可用工具:$available。" +
                "请用正确的工具名重新请求,或直接返回可执行的工具调用。"
        } else {
            buildString {
                docsToInject.forEach { (_, doc) ->
                    appendLine(doc)
                    appendLine()
                }
                appendLine("以上是你请求加载的工具文档。请据此直接返回正确的工具调用执行操作,不要再请求加载文档。")
            }
        }

        // 为每个 load_tool_doc 调用添加对应的 tool result(一对一关联 toolCallId)
        entries.forEach { (_, toolCallId) ->
            if (toolCallId.isNotEmpty()) {
                state.chatMessages.add(
                    ChatMessage(role = "tool", content = summary, toolCallId = toolCallId)
                )
            }
        }

        // 继续 tool loop:让 AI 拿到文档后返回实际工具调用
        continueToolLoopInBackground(context, iteration + 1)
    }

    /**
     * 统一执行 query_keys / read_string / update_string / delete_string / find_keys_by_text 五类 strings.xml 操作。
     * 每个 entry 自带 toolCallId,内部按 entry 解构后独立生成 tool result。
     * 模块一致性已在 processAiReply 校验,此处直接执行。
     */
    private fun executeStringsOps(
        entries: List<ActionWithToolCall>,
        context: String,
        iteration: Int
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val pendingResults = mutableListOf<Pair<String, String>>()
                entries.forEach { (action, toolCallId) ->
                    val resultText = when (action) {
                        is AiAction.QueryKeys -> stringsOps.runQueryKeys(action)
                        is AiAction.ReadString -> stringsOps.runReadString(action)
                        is AiAction.UpdateString -> stringsOps.runUpdateString(action)
                        is AiAction.DeleteString -> stringsOps.runDeleteString(action)
                        is AiAction.FindKeysByText -> stringsOps.runFindKeysByText(action)
                        else -> return@forEach
                    }
                    pendingResults.add(toolCallId to resultText)
                }
                SwingUtilities.invokeLater {
                    pendingResults.forEach { (toolCallId, content) ->
                        state.chatMessages.add(
                            ChatMessage(role = "tool", content = content, toolCallId = toolCallId)
                        )
                    }
                    continueToolLoopInBackground(context, iteration + 1)
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    state.chatMessages.add(
                        ChatMessage(
                            role = "assistant",
                            content = "strings.xml 操作执行异常:${e.message ?: "unknown"}"
                        )
                    )
                    state.chatSending = false
                }
            }
        }
    }

    /**
     * 执行 sheets 域动作(SheetsOperation + FindRowsByText)并把每个结果作为 tool result 回传。
     * 在执行前自动检测 append_row 动作是否有重复 key,若有则暂停并询问用户。
     * 每个 entry 自带 toolCallId,内部按 entry 解构后独立生成 tool result。
     */
    private fun executeSheetsActions(
        entries: List<ActionWithToolCall>,
        context: String,
        iteration: Int
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val actions = entries.map { it.action }
                // 仅 SheetsOperation 需要重复 key 检测;FindRowsByText 是只读操作
                val sheetsOpsList = actions.filterIsInstance<AiAction.SheetsOperation>()
                val appendActions = sheetsOpsList.filter {
                    it.operation == AiAction.SheetsOperation.Operation.APPEND_ROW
                }
                if (appendActions.isNotEmpty()) {
                    val spreadsheetId = SheetsManager.resolveSpreadsheetId(
                        project, appendActions.first().spreadsheetId
                    )
                    val sheetName = appendActions.first().sheetName
                        ?: SheetsManager.defaultSheetName(project)
                    val newKeys = appendActions.mapNotNull {
                        it.rows?.firstOrNull()?.firstOrNull()?.takeIf { k -> k.isNotBlank() }
                    }
                    if (spreadsheetId.isNotBlank() && newKeys.isNotEmpty()) {
                        val existingKeys = SheetsManager.readSheet(project, spreadsheetId, sheetName)
                            .getOrNull()
                            ?.mapNotNull {
                                it.firstOrNull()?.trim()?.takeIf { k -> k.isNotBlank() }
                            }
                            ?.toSet() ?: emptySet()
                        val duplicates = newKeys.filter { it in existingKeys }.toSet()
                        if (duplicates.isNotEmpty()) {
                            // 重复 key 场景:把 entry 列表投影回 SheetsOperation + 对应 toolCallId
                            val sheetsOpsWithIds = entries
                                .mapNotNull { (action, toolCallId) ->
                                    if (action is AiAction.SheetsOperation) action to toolCallId else null
                                }
                            val pending = PendingSheetsInsert(
                                actions = sheetsOpsList,
                                actionToolCallIds = sheetsOpsWithIds.map { it.second },
                                duplicateKeys = duplicates,
                                spreadsheetId = spreadsheetId,
                                sheetName = sheetName,
                                context = context,
                                iteration = iteration
                            )
                            SwingUtilities.invokeLater {
                                state.pendingSheetsInsert = pending
                                state.chatMessages.add(
                                    ChatMessage(
                                        role = "assistant",
                                        content = "检测到以下 key 已存在于表格中:" +
                                            "${duplicates.joinToString(", ")}。请选择如何处理:",
                                        options = listOf(
                                            "覆盖相同key的内容",
                                            "在列表末尾插入相同的key",
                                            "取消操作"
                                        )
                                    )
                                )
                                state.chatSending = false
                            }
                            return@executeOnPooledThread
                        }
                    }
                }

                // 准备所有 toolCallId→结果 映射,统一回 EDT
                val pendingResults = mutableListOf<Pair<String, String>>()
                entries.forEach { (action, toolCallId) ->
                    val content = when (action) {
                        is AiAction.SheetsOperation -> {
                            val result = sheetsOps.executeSheetsOperationSync(action)
                            buildString {
                                append("[工具执行结果] 操作:${result.operation} 状态:${if (result.success) "成功" else "失败"} 信息:${result.message}")
                                if (result.rowNumber != null) append(" 行号:${result.rowNumber}")
                                if (!result.sheetNames.isNullOrEmpty()) {
                                    append(" 工作表列表:${result.sheetNames.joinToString(", ")}")
                                }
                                if (!result.data.isNullOrEmpty()) {
                                    append("\n数据:")
                                    result.data.forEachIndexed { idx, row ->
                                        append("\n  行${idx + 1}: ").append(row.joinToString(" | "))
                                    }
                                }
                            }
                        }
                        is AiAction.FindRowsByText -> sheetsOps.runFindRowsByText(action)
                        else -> return@forEach
                    }
                    pendingResults.add(toolCallId to content)
                }

                SwingUtilities.invokeLater {
                    pendingResults.forEach { (toolCallId, content) ->
                        state.chatMessages.add(
                            ChatMessage(role = "tool", content = content, toolCallId = toolCallId)
                        )
                    }
                    continueToolLoopInBackground(context, iteration + 1)
                }
            } catch (e: Exception) {
                // 异常兜底:为所有未响应的 tool_call 添加错误 tool result,避免协议错位
                SwingUtilities.invokeLater {
                    entries.forEach { (action, toolCallId) ->
                        if (toolCallId.isNotEmpty()) {
                            val typeLabel = when (action) {
                                is AiAction.SheetsOperation -> action.operation.name
                                is AiAction.FindRowsByText -> "find_rows_by_text"
                                else -> "unknown"
                            }
                            state.chatMessages.add(
                                ChatMessage(
                                    role = "tool",
                                    content = "[工具执行异常] $typeLabel 失败:${e.message ?: "unknown"}",
                                    toolCallId = toolCallId
                                )
                            )
                        }
                    }
                    state.chatMessages.add(
                        ChatMessage(
                            role = "assistant",
                            content = "操作执行过程中发生异常,未完成:${e.message ?: "unknown"}"
                        )
                    )
                    state.chatSending = false
                }
            }
        }
    }

    /**
     * 执行文件操作域动作(7 个新工具)并把每个结果作为 tool result 回传。
     *
     * 与 sheets 域不同:文件操作是同步操作(无网络),不需要后台线程 + 重复 key 确认流程,
     * 但读 / 搜索 / find_references 仍然可能耗时长(扫描大目录),所以也丢到后台线程。
     *
     * 异常处理:任一动作抛异常时,为已成功的动作保留成功 tool_result,为失败的动作添加错误 tool_result,
     * 同时在 chat 里追加一条 assistant 提示"部分动作失败"。不会让整批 tool_call 全部失败。
     */
    private fun executeFileOps(
        entries: List<ActionWithToolCall>,
        context: String,
        iteration: Int
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val pendingResults = mutableListOf<Pair<String, String>>()
            var firstError: String? = null
            entries.forEach { (action, toolCallId) ->
                if (toolCallId.isEmpty()) return@forEach
                val resultText = try {
                    when (action) {
                        is AiAction.GetEditorFile -> fileOps.runGetEditorFile(action)
                        is AiAction.ReadFile -> fileOps.runReadFile(action)
                        is AiAction.EditFile -> fileOps.runEditFile(action)
                        is AiAction.CreateFile -> fileOps.runCreateFile(action)
                        is AiAction.SearchInFiles -> fileOps.runSearchInFiles(action)
                        is AiAction.FindReferences -> fileOps.runFindReferences(action)
                        is AiAction.ListFiles -> fileOps.runListFiles(action)
                        else -> return@forEach
                    }
                } catch (e: Exception) {
                    val typeLabel = when (action) {
                        is AiAction.GetEditorFile -> "get_editor_file"
                        is AiAction.ReadFile -> "read_file"
                        is AiAction.EditFile -> "edit_file"
                        is AiAction.CreateFile -> "create_file"
                        is AiAction.SearchInFiles -> "search_in_files"
                        is AiAction.FindReferences -> "find_references"
                        is AiAction.ListFiles -> "list_files"
                        else -> "file_op"
                    }
                    "[工具执行异常] $typeLabel 失败:${e.message ?: "unknown"}".also {
                        if (firstError == null) firstError = e.message ?: "unknown"
                    }
                }
                pendingResults.add(toolCallId to resultText)
            }
            SwingUtilities.invokeLater {
                pendingResults.forEach { (toolCallId, content) ->
                    state.chatMessages.add(
                        ChatMessage(role = "tool", content = content, toolCallId = toolCallId)
                    )
                }
                continueToolLoopInBackground(context, iteration + 1)
            }
        }
    }

    /**
     * 重复 key 插入的用户选择处理(系统发起的询问,不是 AI 调用的 ask_user 工具)。
     * - 覆盖:将重复 key 的 append_row 转为 update_row(用 search 定位行号),非重复的保持 append_row
     * - 末尾插入:原样执行所有 append_row
     * - 取消:不执行,为每个 pending tool_call 添加「用户已取消」的 tool result
     */
    private fun resolveDuplicateInsert(option: String, pending: PendingSheetsInsert) {
        when {
            option.contains("覆盖") -> {
                state.chatSending = true
                ApplicationManager.getApplication().executeOnPooledThread {
                    try {
                        val resolvedActions = pending.actions.map { action ->
                            if (action.operation == AiAction.SheetsOperation.Operation.APPEND_ROW) {
                                val key = action.rows?.firstOrNull()?.firstOrNull()
                                if (key != null && key in pending.duplicateKeys) {
                                    val searchResult = SheetsManager.searchRowInSheet(
                                        project, pending.spreadsheetId, pending.sheetName, key
                                    )
                                    searchResult.fold(
                                        onSuccess = { (rowNum, _) ->
                                            action.copy(
                                                operation = AiAction.SheetsOperation.Operation.UPDATE_ROW,
                                                rowNumber = rowNum
                                            )
                                        },
                                        onFailure = { action }
                                    )
                                } else {
                                    action
                                }
                            } else {
                                action
                            }
                        }
                        executeSheetsActions(
                            buildSheetsActionEntries(resolvedActions, pending.actionToolCallIds),
                            pending.context,
                            pending.iteration
                        )
                    } catch (e: Exception) {
                        SwingUtilities.invokeLater {
                            state.chatMessages.add(
                                ChatMessage(
                                    role = "assistant",
                                    content = "覆盖操作执行异常:${e.message ?: "unknown"}"
                                )
                            )
                            state.chatSending = false
                        }
                    }
                }
            }
            option.contains("末尾") -> {
                state.chatSending = true
                ApplicationManager.getApplication().executeOnPooledThread {
                    try {
                        executeSheetsActions(
                            buildSheetsActionEntries(pending.actions, pending.actionToolCallIds),
                            pending.context,
                            pending.iteration
                        )
                    } catch (e: Exception) {
                        SwingUtilities.invokeLater {
                            state.chatMessages.add(
                                ChatMessage(
                                    role = "assistant",
                                    content = "追加操作执行异常:${e.message ?: "unknown"}"
                                )
                            )
                            state.chatSending = false
                        }
                    }
                }
            }
            else -> {
                // 取消:为每个 pending tool_call 添加取消 tool result,继续 tool loop 让 AI 知道结果
                SwingUtilities.invokeLater {
                    pending.actions.forEachIndexed { i, action ->
                        val toolCallId = pending.actionToolCallIds.getOrNull(i).orEmpty()
                        if (toolCallId.isNotEmpty()) {
                            state.chatMessages.add(
                                ChatMessage(
                                    role = "tool",
                                    content = "[用户取消] ${action.operation} 未执行。",
                                    toolCallId = toolCallId
                                )
                            )
                        }
                    }
                    state.chatMessages.add(ChatMessage(role = "assistant", content = "已取消插入操作。"))
                    continueToolLoopInBackground(pending.context, pending.iteration + 1)
                }
            }
        }
    }

    /**
     * 把 SheetsOperation 列表 + 平行的 toolCallId 列表打包成 [ActionWithToolCall] 列表,
     * 供 executeSheetsActions 使用。
     */
    private fun buildSheetsActionEntries(
        actions: List<AiAction.SheetsOperation>,
        toolCallIds: List<String>
    ): List<ActionWithToolCall> {
        return actions.mapIndexed { i, action ->
            ActionWithToolCall(action, toolCallIds.getOrNull(i).orEmpty())
        }.filter { it.toolCallId.isNotEmpty() }
    }

    private fun executeInsertActions(
        entries: List<ActionWithToolCall>,
        context: String,
        iteration: Int
    ) {
        val actions = entries.mapNotNull { it.action as? AiAction.InsertStrings }
        if (actions.isEmpty()) {
            continueToolLoopInBackground(context, iteration + 1)
            return
        }
        val contextInfo = ContextManager.getInstance(project).contextInfo
        val currentModuleName = contextInfo?.currentModule?.moduleName
        val moduleWithMostLines = contextInfo?.moduleWithMostLines
        val recommendedDefault = contextInfo?.recommendedDefaultModule
        val moduleList = contextInfo?.modules?.map { it.moduleName }

        // 决定本批次的统一目标模块(模块一致性已由 processAiReply 校验过,此处安全取第一个非空 module)
        // 关键:排除项目名(与 normalizeExplicitWriteModule 同步),避免项目名被当作模块名
        val projectName = contextInfo?.projectName
        val batchModule = stringsOps.resolveTargetModule(
            actions.firstNotNullOfOrNull { it.module?.takeIf { m -> m.isNotBlank() && m != projectName } },
            currentModuleName,
            moduleWithMostLines?.moduleName
        )
        if (batchModule == null) {
            // 理论上不会走到这里(校验时已拦截),兜底
            entries.forEach { (_, toolCallId) ->
                if (toolCallId.isNotEmpty()) {
                    state.chatMessages.add(
                        ChatMessage(
                            role = "tool",
                            content = "[工具执行异常] insert_strings 未指定目标模块且无 currentModule , moduleList = $moduleList",
                            toolCallId = toolCallId
                        )
                    )
                }
            }
            continueToolLoopInBackground(context, iteration + 1)
            return
        }

        if (batchModule == projectName) {
            // 理论上不会走到这里(校验时已拦截),兜底
            entries.forEach { (_, toolCallId) ->
                if (toolCallId.isNotEmpty()) {
                    state.chatMessages.add(
                        ChatMessage(
                            role = "tool",
                            content = "[工具执行异常] insert_strings 模块名称 = 项目名称,请勿使用项目名插入翻译 , moduleList = $moduleList",
                            toolCallId = toolCallId
                        )
                    )
                }
            }
            continueToolLoopInBackground(context, iteration + 1)
            return
        }

        // 逐个执行到 batchModule
        // 关键改动:不再用 keyEntries 的 stringsInfoList(它的 filePath 是用户原来选中的模块,
        // 跟 batchModule 可能是两个模块),改为:
        //   1) 提前在 batchModule 下补齐所有需要的语言文件(缺哪个建哪个)
        //   2) 总是走 insertIntoModule,以 batchModule 的 stringsInfoList 为准
        //   3) merged 基础值用 batchModule 现有翻译(用 scanModuleForKey 读真实文件),
        //      保留用户已存在的翻译不被 AI 漏写的语言「清空」

        // 预聚合:本批所有 action 中要写入的语言并集,提前在 batchModule 下补齐缺失文件。
        // 这是修复「values 写到别的模块」的关键 — targetModule 没有 values/ 时,直接建一个,
        // 所有语言落在同一模块,不会再让用户看到 values 在 module A、其它语言在 module B。
        val allLanguagesNeeded = (actions.flatMap { it.translations.keys } + DEFAULT_LANGUAGE).toSet()
        val contextMgr = ContextManager.getInstance(project)
        allLanguagesNeeded.forEach { lang ->
            if (!lang.startsWith("values")) return@forEach
            contextMgr.ensureLanguageFile(batchModule, lang)
        }

        // 写之前查重:扫描目标模块 values 译文 + 跨模块索引,
        // 命中"已有 key 与待插入 values 译文完全一致"时挂起,让用户选择「使用现有 / 插入新 / 取消」。
        val duplicateMap = stringsOps.checkDuplicateKeys(actions, batchModule)
        if (duplicateMap.isNotEmpty()) {
            val existing = mutableListOf<ExistingKeyMatch>()
            duplicateMap.values.forEach { existing.addAll(it) }
            val pending = PendingStringsInsert(
                actions = actions,
                actionToolCallIds = entries.map { it.toolCallId },
                existingKeys = existing,
                existingKeysByAction = duplicateMap,
                targetModule = batchModule,
                context = context,
                iteration = iteration,
            )
            SwingUtilities.invokeLater {
                state.pendingStringsInsert = pending
                val msg = buildDuplicatePrompt(duplicateMap, batchModule, existing)
                val lastIdx = state.chatMessages.lastIndex
                if (lastIdx >= 0) {
                    state.chatMessages[lastIdx] = state.chatMessages[lastIdx].copy(
                        content = msg,
                        thinking = "",
                        options = listOf(
                            duplicateStringsOptions[0],
                            duplicateStringsOptions[1],
                            duplicateStringsOptions[2]
                        )
                    )
                } else {
                    state.chatMessages.add(
                        ChatMessage(
                            role = "assistant",
                            content = msg,
                            options = duplicateStringsOptions
                        )
                    )
                }
                state.chatSending = false
            }
            return
        }

        val results = actions.map { action ->
            val targetModule = batchModule
            val moduleStringsInfo = contextMgr.getModuleStringsInfo(targetModule)
            if (moduleStringsInfo.isEmpty()) {
                state.showToast("Module $targetModule has no strings.xml")
                return@map "模块 $targetModule 没有 strings.xml 或缺少 res/ 目录" to false
            }

            // 以 batchModule 现有翻译为底,AI 没写的语言不覆盖(避免漏写时把已有翻译清空)。
            // 注意:此处只对「已存在的 key」保留翻译;对新增 key 来说,scanModuleForKey 全部返回空串,
            // 等价于空白底,行为与之前一致。
            val existingInfo = contextMgr.scanModuleForKey(targetModule, action.name)
            val existingTranslations = existingInfo.associate { it.language to it.text }
            val merged = existingTranslations.toMutableMap()
            merged.putAll(action.translations)
            // 兜底:确保 values(默认英语)一定存在,避免漏写导致 values/strings.xml 被清空
            if (DEFAULT_LANGUAGE !in merged) merged[DEFAULT_LANGUAGE] = ""

            // 语种裁剪 + 兜底:以目标模块实际 xmlFiles 为准,删除模块中不存在的语种,
            // 并补齐 AI 漏写的语种,确保最终写入 = 模块的完整语种集合。
            // 兜底文本按以下优先级选取 —— 确保所有语种都有非空值,UI 不会显示空白:
            //   1) values(默认英语)译文
            //   2) AI 提供的任何其他语种译文
            //   3) 模块里该 key 现有翻译(已有翻译优先保留,即使 AI 漏给)
            //   4) 空前缀 —— 仅在以上都没有时使用,配合新增空 values 目录的占位。
            // 这样既不会让任何语种"完全没条目"(导致运行时 key 不存在),
            // 也不会清空用户/AI 已提供的翻译。
            val targetModuleLanguages = contextMgr.getModuleFiles(targetModule).map { it.first.name }
            val droppedLanguages = mutableListOf<String>()
            val filledLanguages = mutableListOf<String>()
            if (targetModuleLanguages.isNotEmpty()) {
                // 1) 裁剪:删掉 AI 提供但目标模块没有的语种(避免写入到不存在的文件)
                val keysToRemove = merged.keys - targetModuleLanguages.toSet() - DEFAULT_LANGUAGE
                keysToRemove.forEach { droppedLanguages.add(it); merged.remove(it) }
                // 2) 兜底:补齐 AI 漏写的语种
                val fallbackSource = merged[DEFAULT_LANGUAGE]?.takeIf { it.isNotBlank() }
                    ?: action.translations.values.firstOrNull { it.isNotBlank() }
                    ?: existingTranslations.values.firstOrNull { it.isNotBlank() }
                    ?: ""
                for (lang in targetModuleLanguages) {
                    if (lang !in merged) {
                        merged[lang] = fallbackSource
                        filledLanguages.add(lang)
                    }
                }
                if (filledLanguages.isNotEmpty() || droppedLanguages.isNotEmpty()) {
                    DebugLog.log(
                        "InsertStringsChatDriver",
                        "insert_strings key=${action.name} module=$targetModule " +
                            "filled=$filledLanguages dropped=$droppedLanguages " +
                            "(fallback source length=${fallbackSource.length})"
                    )
                }
            }

            // 写入文件
            var writeOk: Boolean
            var writeErr: String?
            try {
                state.insertStringsManager.insertIntoModule(
                    project, targetModule, mapOf(action.name to merged)
                )
                writeOk = true
                writeErr = null
            } catch (e: Exception) {
                writeOk = false
                writeErr = e.message ?: "unknown"
            }

            // 组装 result 描述,后续透传给 AI,让它看到:
            //   - 实际生效的模块 + 该模块已有的全部语种
            //   - 自己漏写被兜底的语种(以及兜底来源)
            //   - 自己多写被丢弃的语种(目标模块没有对应文件)
            val targetLangsDesc = if (targetModuleLanguages.isEmpty()) "(无)" else targetModuleLanguages.joinToString(",")
            val msg = buildString {
                if (writeOk) {
                    append("成功")
                } else {
                    append("失败:").append(writeErr ?: "unknown")
                }
                append(" 目标模块:").append(targetModule)
                append(" 语种(").append(targetModuleLanguages.size).append("):").append(targetLangsDesc)
                if (filledLanguages.isNotEmpty()) {
                    append(" 兜底补语种:").append(filledLanguages.joinToString(","))
                }
                if (droppedLanguages.isNotEmpty()) {
                    append(" 丢弃(模块无文件):").append(droppedLanguages.joinToString(","))
                }
            }
            msg to writeOk
        }

        // 刷新 UI(全部用 batchModule,保证一致性)
        val allEntries = actions.map { action ->
            KeyedStringsInfo(
                action.name,
                "",
                contextMgr.scanModuleForKey(batchModule, action.name)
            )
        }
        state.insertStringsManager.updateUI(allEntries)
        val names = actions.joinToString(", ") { it.name }
        state.showToast("Inserted: $names")

        // 通知 state 有 insert_strings 已完成(供 ExtractStringsChatHolder 这类入口
        // 拿到 key 后回填编辑器选区)。默认空实现,只有需要的入口会覆写。
        results.forEachIndexed { i, (_, ok) ->
            if (ok) {
                actions.getOrNull(i)?.let { a ->
                    state.onInsertStringsInserted(a.name, batchModule)
                }
            }
        }

        state.closeChatView()

        // 为每个 insert_strings 调用添加对应的 tool result(使用 entry 自带的 toolCallId,避免下标错位)
        entries.forEachIndexed { i, (_, toolCallId) ->
            val action = actions.getOrNull(i)
            val (msg, _) = results.getOrNull(i) ?: ("" to false)
            if (action != null && toolCallId.isNotEmpty()) {
                state.chatMessages.add(
                    ChatMessage(
                        role = "tool",
                        content = "[工具执行结果] insert_strings module=$batchModule name=${action.name} 状态:$msg",
                        toolCallId = toolCallId
                    )
                )
            }
        }

        // 继续 tool loop:让 AI 拿到结果后调用 task_complete 或继续下一步
        continueToolLoopInBackground(context, iteration + 1)
    }

    /**
     * 把一批 tool_call 折叠成一行简短描述,用于 assistant 消息占位文案。
     * 重复同名工具会合并计数,避免长列表刷屏。
     */
    private fun summarizeToolCalls(toolCalls: List<ToolCall>): String {
        if (toolCalls.isEmpty()) return ""
        val grouped = toolCalls.groupingBy { it.name }.eachCount()
        return grouped.entries.joinToString("、") { (name, count) ->
            if (count > 1) "$name×$count" else name
        }
    }

    /**
     * 构造"重复 key"询问气泡的文本,告诉用户:
     *  - 哪些待插入 key 与现有 key 重复(按 values 默认语言比对)
     *  - 现有 key 在哪个模块(目标模块 / 其它模块)
     *  - 给三个选项:使用现有 / 插入新 / 取消
     */
    private fun buildDuplicatePrompt(
        duplicateMap: Map<String, List<ExistingKeyMatch>>,
        targetModule: String,
        existing: List<ExistingKeyMatch>
    ): String {
        val lines = mutableListOf<String>()
        lines += "检测到待插入翻译在 strings.xml 中已存在相同文案的 key:"
        duplicateMap.forEach { (newKey, matches) ->
            val matchDesc = matches.joinToString(" / ") { m ->
                if (m.module == targetModule) "key=${m.key}(本模块)" else "key=${m.key}(模块:${m.module})"
            }
            lines += "  • 待插入 newKey=$newKey → 已存在: $matchDesc"
        }
        if (existing.any { it.module != targetModule }) {
            lines += "(其中包含其它模块的同名翻译,可考虑复用或合并)"
        }
        lines += ""
        lines += "请选择:「使用现有翻译」保留现有 key,顺带让 AI 检查翻译是否需要修正;"
        lines += "「插入新的翻译」按原计划新增 key;"
        lines += "「取消操作」放弃本次插入。"
        return lines.joinToString("\n")
    }

    /**
     * 用户选择复用现有 key 时,为单个待插入 action 找到最合适的命中项。
     * 优先复用目标模块内的 key,避免 Extract 场景把当前文件替换成其它模块才有的 key。
     */
    private fun selectExistingKeyForAction(
        pending: PendingStringsInsert,
        action: AiAction.InsertStrings
    ): ExistingKeyMatch {
        val matches = pending.existingKeysByAction[action.name].orEmpty()
            .ifEmpty { pending.existingKeys }
        return matches.firstOrNull { it.module == pending.targetModule }
            ?: matches.first()
    }

    /**
     * 处理 strings.xml 重复 key 询问的用户选择:
     *  - "使用现有翻译":跳过 insert_strings 的写操作,但仍要:
     *      1) 触发 onInsertStringsInserted 回调(让 ExtractStrings 等入口把选区替换为现有 key)
     *      2) 让 AI 拿到现有 key + 现有翻译,询问是否需要修正(走 update_string 修复缺漏)
     *  - "插入新翻译":继续按原流程执行 insert_strings
     *  - "取消":为每个 pending tool_call 添加「用户已取消」tool result
     */
    private fun duplicateStringsChoiceFromOptionIndex(index: Int): DuplicateStringsChoice =
        when (index) {
            0 -> DuplicateStringsChoice.USE_EXISTING
            1 -> DuplicateStringsChoice.INSERT_NEW
            2 -> DuplicateStringsChoice.CANCEL
            else -> DuplicateStringsChoice.CANCEL
        }

    private fun resolveDuplicateStringsInsert(
        choice: DuplicateStringsChoice,
        pending: PendingStringsInsert
    ) {
        when (choice) {
            DuplicateStringsChoice.USE_EXISTING -> {
                state.chatSending = true
                SwingUtilities.invokeLater {
                    val contextMgr = ContextManager.getInstance(project)
                    val firstAction = pending.actions.firstOrNull()
                    val firstMatch = firstAction?.let { selectExistingKeyForAction(pending, it) }
                        ?: pending.existingKeys.first()
                    val existingKey = firstMatch.key
                    val existingModule = firstMatch.module

                    // 把现有 key 的全语种翻译回读给 AI,让 AI 比对并修正缺漏
                    val existingTranslations = contextMgr.scanModuleForKey(existingModule, existingKey)
                        .associate { it.language to it.text }
                        .filter { it.value.isNotEmpty() }

                    // 为每个 pending tool_call 添加成功 tool result(语义:沿用现有 key)
                    pending.actionToolCallIds.forEachIndexed { i, toolCallId ->
                        if (toolCallId.isNotEmpty()) {
                            val action = pending.actions.getOrNull(i)
                            val actionMatch = action?.let { selectExistingKeyForAction(pending, it) } ?: firstMatch
                            val actionName = action?.name ?: "?"
                            val content = buildString {
                                append("[工具执行结果] insert_strings module=${pending.targetModule} ")
                                append("name=$actionName 状态:跳过(使用现有 key=${actionMatch.key} @模块=${actionMatch.module}) ")
                                append("理由:用户选择「使用现有翻译」")
                                if (actionName != actionMatch.key) {
                                    append(" 待插入新 key=$actionName 已忽略")
                                }
                            }
                            state.chatMessages.add(
                                ChatMessage(
                                    role = "tool",
                                    content = content,
                                    toolCallId = toolCallId
                                )
                            )
                        }
                    }

                    // 触发入口回调(让 ExtractStrings 把选区替换为 @string/$existingKey)
                    pending.actions.forEach { a ->
                        val actionMatch = selectExistingKeyForAction(pending, a)
                        state.onInsertStringsInserted(actionMatch.key, actionMatch.module)
                    }
                    state.closeChatView()

                    // 追加 assistant 消息:把现有翻译交给 AI,问是否需要修正
                    val transDesc = existingTranslations.entries.joinToString(", ") { (lang, text) ->
                        val short = if (text.length > 60) text.take(60) + "…" else text
                        "$lang=\"$short\""
                    }
                    state.chatMessages.add(
                        ChatMessage(
                            role = "assistant",
                            content = "已沿用现有 key=$existingKey @模块=$existingModule(用户选择「使用现有翻译」)。\n" +
                                "现有翻译:$transDesc\n" +
                                "接下来请调用 read_string($existingKey) 取完整内容,对比用户期望:\n" +
                                "  - 若翻译齐全且准确,直接 task_complete 即可;\n" +
                                "  - 若需要补齐缺失语言或修正某语言,用 update_string 精准更新。\n" +
                                "如果是在布局/代码里替换的 key,已通过回调写入编辑器选区。"
                        )
                    )
                    state.chatSending = false
                }
            }
            DuplicateStringsChoice.INSERT_NEW -> {
                // 按原计划继续 insert_strings:重新走 executeInsertActions 的后续流程。
                // 简单地重新构造 entries 调用私有方法不便,改为:让用户再次发消息告诉 AI「忽略重复检查,继续插入」,
                // 或者这里直接当作"无重复命中"重跑。这里采取最直接的做法:回退为重新走 driver 的继续流程,
                // 但本次不再查重 — 改用一个标记状态来跳过本批的查重。
                // 实现上:通过再次调用执行路径,但用临时变量 disableCheckDuplicate 跳过查重。
                executeInsertActionsWithPending(
                    actions = pending.actions,
                    actionToolCallIds = pending.actionToolCallIds,
                    targetModule = pending.targetModule,
                    context = pending.context,
                    iteration = pending.iteration,
                    skipDuplicateCheck = true,
                )
            }
            DuplicateStringsChoice.CANCEL -> {
                // 取消
                SwingUtilities.invokeLater {
                    pending.actionToolCallIds.forEachIndexed { i, toolCallId ->
                        if (toolCallId.isNotEmpty()) {
                            val action = pending.actions.getOrNull(i)
                            val name = action?.name ?: "?"
                            state.chatMessages.add(
                                ChatMessage(
                                    role = "tool",
                                    content = "[用户取消] insert_strings name=$name 未执行(检测到重复 key,用户取消)。",
                                    toolCallId = toolCallId
                                )
                            )
                        }
                    }
                    state.chatMessages.add(
                        ChatMessage(
                            role = "assistant",
                            content = "已取消本次插入操作。"
                        )
                    )
                    state.closeChatView()
                    state.chatSending = false
                }
            }
        }
    }

    /**
     * 跳过查重的插入执行入口 — 当用户在"重复 key 询问"中选择了「插入新的翻译」时调用。
     * 内部直接走原 executeInsertActions 的写文件逻辑,但不再次查重。
     */
    private fun executeInsertActionsWithPending(
        actions: List<AiAction.InsertStrings>,
        actionToolCallIds: List<String>,
        targetModule: String,
        context: String,
        iteration: Int,
        skipDuplicateCheck: Boolean,
    ) {
        val contextMgr = ContextManager.getInstance(project)
        state.chatSending = true

        // 预聚合语言,补齐文件
        val allLanguagesNeeded = (actions.flatMap { it.translations.keys } + DEFAULT_LANGUAGE).toSet()
        allLanguagesNeeded.forEach { lang ->
            if (!lang.startsWith("values")) return@forEach
            contextMgr.ensureLanguageFile(targetModule, lang)
        }
        if (!skipDuplicateCheck) {
            // 理论上只有 skipDuplicateCheck=true 才会调到这里,这里做兜底
            val dup = stringsOps.checkDuplicateKeys(actions, targetModule)
            if (dup.isNotEmpty()) {
                return resolveDuplicateStringsInsert(
                    DuplicateStringsChoice.CANCEL,
                    PendingStringsInsert(
                        actions = actions,
                        actionToolCallIds = actionToolCallIds,
                        existingKeys = dup.values.flatten(),
                        existingKeysByAction = dup,
                        targetModule = targetModule,
                        context = context,
                        iteration = iteration,
                    )
                )
            }
        }

        val results = actions.map { action ->
            val moduleStringsInfo = contextMgr.getModuleStringsInfo(targetModule)
            if (moduleStringsInfo.isEmpty()) {
                return@map "模块 $targetModule 没有 strings.xml 或缺少 res/ 目录" to false
            }
            val existingInfo = contextMgr.scanModuleForKey(targetModule, action.name)
            val existingTranslations = existingInfo.associate { it.language to it.text }
            val merged = existingTranslations.toMutableMap()
            merged.putAll(action.translations)
            if (DEFAULT_LANGUAGE !in merged) merged[DEFAULT_LANGUAGE] = ""
            val targetModuleLanguages = contextMgr.getModuleFiles(targetModule).map { it.first.name }
            val droppedLanguages = mutableListOf<String>()
            val filledLanguages = mutableListOf<String>()
            if (targetModuleLanguages.isNotEmpty()) {
                val keysToRemove = merged.keys - targetModuleLanguages.toSet() - DEFAULT_LANGUAGE
                keysToRemove.forEach { droppedLanguages.add(it); merged.remove(it) }
                val fallbackSource = merged[DEFAULT_LANGUAGE]?.takeIf { it.isNotBlank() }
                    ?: action.translations.values.firstOrNull { it.isNotBlank() }
                    ?: existingTranslations.values.firstOrNull { it.isNotBlank() }
                    ?: ""
                for (lang in targetModuleLanguages) {
                    if (lang !in merged) {
                        merged[lang] = fallbackSource
                        filledLanguages.add(lang)
                    }
                }
            }
            val (msg, ok) = try {
                state.insertStringsManager.insertIntoModule(project, targetModule, mapOf(action.name to merged))
                "成功 目标模块:$targetModule 兜底补语种:${filledLanguages.joinToString(",").ifEmpty { "(无)" }} 丢弃:${droppedLanguages.joinToString(",").ifEmpty { "(无)" }}" to true
            } catch (e: Exception) {
                "失败:${e.message ?: "unknown"}" to false
            }
            msg to ok
        }
        val allEntries = actions.map { action ->
            KeyedStringsInfo(
                action.name,
                "",
                contextMgr.scanModuleForKey(targetModule, action.name)
            )
        }
        state.insertStringsManager.updateUI(allEntries)
        val names = actions.joinToString(", ") { it.name }
        state.showToast("Inserted: $names")
        results.forEachIndexed { i, (_, ok) ->
            if (ok) {
                actions.getOrNull(i)?.let { a ->
                    state.onInsertStringsInserted(a.name, targetModule)
                }
            }
        }
        state.closeChatView()
        actionToolCallIds.forEachIndexed { i, toolCallId ->
            val action = actions.getOrNull(i)
            val (msg, _) = results.getOrNull(i) ?: ("" to false)
            if (action != null && toolCallId.isNotEmpty()) {
                state.chatMessages.add(
                    ChatMessage(
                        role = "tool",
                        content = "[工具执行结果] insert_strings module=$targetModule name=${action.name} 状态:$msg",
                        toolCallId = toolCallId
                    )
                )
            }
        }
        continueToolLoopInBackground(context, iteration + 1)
    }
}
