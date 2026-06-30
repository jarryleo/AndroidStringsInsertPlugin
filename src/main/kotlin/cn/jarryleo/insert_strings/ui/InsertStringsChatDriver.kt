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
 *  - ask_user 触发"使用现有 key"时拦截选项,触发 onInsertStringsInserted 替换硬编码文本
 *
 * 拆分理由:这是整个类里逻辑最复杂、最容易出 bug 的一段;独立成类后,
 * InsertStringsUI 类本身只剩 state + 装配,主类体积会从 ~2900 行降到 ~300 行。
 */
internal class InsertStringsChatDriver(
    private val state: ChatStateHolder,
    private val stringsOps: InsertStringsStringsOpsController,
    private val sheetsOps: InsertStringsSheetsOpsController,
    private val fileOps: InsertStringsFileOpsController,
    private val editorOps: InsertStringsEditorOpsController,
    private val chatContextBuilder: InsertStringsChatContextBuilder,
) {

    private val project: Project get() = state.project

    /**
     * Shell 执行域(run_shell 工具)的运行时控制器。
     * lazy 持有 — 多数对话不会触发,等真要用再实例化,省内存。
     */
    private val shellOps: InsertStringsShellOpsController by lazy { InsertStringsShellOpsController(state) }

    /**
     * 编辑器诊断域(read_diagnostics 工具)的运行时控制器。
     * lazy 持有 — 多数对话不会触发,等真要用再实例化,省内存。
     */
    private val diagnosticsOps: InsertStringsDiagnosticsController by lazy { InsertStringsDiagnosticsController(state) }

    /**
     * URL 拉取域(fetch_url 工具)的运行时控制器。
     * lazy 持有 — 多数对话不会触发,等真要用再实例化,省内存。
     */
    private val fetchUrlOps: InsertStringsFetchUrlController by lazy { InsertStringsFetchUrlController(state) }

    companion object {
        // 单次对话中 AI 调用工具的最大轮数。超过则强制结束,防止死循环。
        // 设 30 足以覆盖现实中的多步操作(检查+修正等),又能及时止损。
        private const val MAX_ITERATIONS = 30
        // load_tool_doc 按需加载工具文档的最大连续次数,防止 AI 反复加载文档而不执行操作。
        private const val MAX_TOOL_DOC_LOADS = 4
        // 单轮对话中 ask_user 的最大连续调用次数,防止 AI 反复追问形成死循环。
        // 每次用户实际回复(发送消息 / 点击选项)后重置为 0。
        private const val MAX_ASK_USER_CALLS = 3
        // 2026.x 优化 D2:tool result 压缩阈值。
        // 超过 N 个 assistant 回合前的 tool 消息(其内容对当前轮次决策无影响)会被压缩为占位符。
        // 选 5 保留最近 5 轮的工具结果(约 1-2 个完整流程),超出部分用占位符替代。
        // 阈值太小会丢失近期上下文,太大则省不下来 token。
        private const val TOOL_RESULT_COMPACTION_THRESHOLD = 5
        // 压缩后的占位文本 — 协议层发回 AI(OpenAI/Anthropic 对 tool_result 长度不敏感),
        // UI 端在 chat tab 也展示这个占位(用户不会频繁翻 5 轮前的工具结果,
        // 真要时再调一次工具即可,接受这个折衷)。
        private const val TOOL_RESULT_COMPACTED_PLACEHOLDER = "[结果已压缩(节省 token);需要时重新调用工具]"
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
     * 发送"系统自动触发"消息给 AI 并异步等待回复(2026.x 新增)。
     *
     * 用法:由 [cn.jarryleo.insert_strings.ai.TodoAiResponder] 调用 —— 用户代办提醒触发时,
     * scheduler 把"提醒 X 已触发"作为 system message 发到这里,让 AI 生成简短友好的回复文本,
     * 回复会通过 [onResponse] 回调出去,scheduler 用 IDE 通知气泡展示。
     *
     * **不污染 chat tab**(2026.x 修复):系统消息 + AI 回复 + 中间所有 tool 消息都会标记
     * [ChatMessage.hidden] = true,UI 渲染时直接跳过它们。
     * 但它们的 [ChatMessage.protocolVisible] 仍是 true,所以这些消息仍然在 AI 的协议
     * 历史里 —— 后续对话中 AI 能看到「这次提醒触发了什么」,上下文不丢。
     *
     * **不修改 UI 状态**:不会清空 chat input、不会触发 toast 之类的副作用,
     * 也不打断用户当前的对话(如果用户同时在 chat tab 打字,AI 会串行处理)。
     *
     * **超时**:60 秒无回复则自动放弃(防止 AI 卡死时永远等),空回复时也会回传
     * "「AI 未回复」"占位文本,scheduler 不会因此静默失败。
     *
     * @param systemMessage 完整的系统消息文本(会作为 user 消息加入 chat,带 [自动触发] 前缀便于用户在 chat tab 识别)
     * @param onResponse AI 回复到后被调用,参数是 assistant 消息拼接后的纯文本;在 EDT 上调用。
     */
    fun sendSystemMessageAndAwait(systemMessage: String, onResponse: (String) -> Unit) {
        // 防止重入:如果 chat 正在处理,就排队等待直到当前轮结束(避免把 system 消息和用户消息搅在一起)
        if (state.chatSending) {
            // 用 invokeLater 简单重试,直到 chatSending=false
            SwingUtilities.invokeLater {
                if (state.chatSending) {
                    // 还在忙,递归再排队
                    sendSystemMessageAndAwait(systemMessage, onResponse)
                } else {
                    sendSystemMessageAndAwaitInternal(systemMessage, onResponse)
                }
            }
            return
        }
        sendSystemMessageAndAwaitInternal(systemMessage, onResponse)
    }

    private fun sendSystemMessageAndAwaitInternal(systemMessage: String, onResponse: (String) -> Unit) {
        val messageCountBefore = state.chatMessages.size
        // 用 [自动触发] 前缀让用户在 chat tab 能一眼看出这是系统消息(非用户输入)
        val tagged = "[自动触发 - 代办提醒] $systemMessage"
        // 关键:hidden = true —— 整条消息从 chat tab UI 里隐藏,但 protocolVisible = true
        // 保留让它进 AI 协议历史,AI 仍能在后续回合中看到这个上下文。
        state.chatMessages.add(
            ChatMessage(role = "user", content = tagged, hidden = true)
        )
        state.toolDocLoadCount = 0
        state.askUserCallCount = 0
        state.stopRequested = false
        bindRetryHooks()
        continueChatWithAi()

        // 后台线程等待 AI 完成。AI 可能调 tool,所以"完成"的标志是 chatSending=false
        // (tool loop 自然结束)或超时(60s)。
        val timeoutMs = 60_000L
        val pollMs = 100L
        Thread({
            try {
                val start = System.currentTimeMillis()
                while (state.chatSending && System.currentTimeMillis() - start < timeoutMs) {
                    try {
                        Thread.sleep(pollMs)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@Thread
                    }
                }
                // 收集"自系统消息之后"的新消息(包括 user 触发、assistant 回复、tool 消息等)
                val newIndices = (messageCountBefore until state.chatMessages.size).toList()
                val newMessages = newIndices.mapNotNull { idx ->
                    state.chatMessages.getOrNull(idx)
                }
                // 提取最后一条非空 assistant 消息作为 AI 回复(覆盖中间流式占位空消息)
                val responseText = newMessages
                    .filter { it.role == "assistant" }
                    .lastOrNull { it.content.isNotBlank() }
                    ?.content
                    ?.trim()
                    .orEmpty()
                // 关键:把这次"自动触发"产生的中间过程(user 触发、tool 消息、assistant 中间占位)
                // 标记为 hidden,从 chat tab UI 里隐藏,只留下**最后一条** assistant 消息
                // (AI 的最终回复)可见 —— 用户在 chat tab 能看到 AI 的人性化提醒文案(同时 IDE 气泡也展示)。
                // 协议历史(protocolVisible)不受影响,AI 后续回合仍能看到完整上下文。
                SwingUtilities.invokeLater {
                    newIndices.forEach { idx ->
                        if (idx < state.chatMessages.size) {
                            val cur = state.chatMessages[idx]
                            if (!cur.hidden) {
                                state.chatMessages[idx] = cur.copy(hidden = true)
                            }
                        }
                    }
                    // 找最后一条 assistant 消息(AI 最终回复),把它"显式还原"为可见。
                    // 上面已经把所有消息都设 hidden=true 了,这里只把最后一条 assistant 改回 false。
                    val lastAssistantIdx = newIndices.reversed().firstOrNull { idx ->
                        idx < state.chatMessages.size && state.chatMessages[idx].role == "assistant" &&
                            state.chatMessages[idx].content.isNotBlank()
                    }
                    if (lastAssistantIdx != null) {
                        val cur = state.chatMessages[lastAssistantIdx]
                        state.chatMessages[lastAssistantIdx] = cur.copy(hidden = false)
                    }
                    // 总是回调一次(即使是空文本),scheduler 可以显示"AI 未回复"占位气泡
                    onResponse(responseText)
                }
            } catch (e: Throwable) {
                // 兜底:任何异常都不影响主流程,scheduler 仍能继续工作
                SwingUtilities.invokeLater {
                    onResponse("")
                }
            }
        }, "TodoAiResponder-awaits").apply { isDaemon = true }.start()
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
        // 清掉 AITranslator 上挂的旧回调,避免下次新会话的 RetrySupport 还把提示塞到这条旧 chat
        AITranslator.onRetryListener = null
        // 引用内容是一次性的入口上下文,新会话不应继续展示旧引用 —— 避免「New Topic」之后
        // 旧编辑器选区还挂在聊天列表顶部,造成新对话中用户/AI 误把旧内容当作上下文。
        state.quoteContent = null
    }

    /**
     * 对话气泡选项按钮点击回调。
     * 优先级:待响应的 ask_user 工具调用 > 系统发起的重复 key 询问(Sheets) > 兜底普通消息。
     *
     * 翻译查重流程:AI 通过 ask_user 列出 `使用现有 key:<existing_key>` / `插入新 key` /
     * `取消操作` 三个选项;用户点选后,系统把选项文本作为 tool_result 透明回传给 AI,
     * **不再**自动触发任何替换/插入操作 — AI 自己根据选项内容决定下一步:
     *  - 「使用现有 key:<existing_key>」:AI 调用 [AiAction.ReplaceSelection] 工具
     *    触发硬编码文本替换,再调用 read_string 检查现有翻译是否需要修正;
     *  - 「插入新 key」:AI 检查自己生成的 key 是否已存在,若存在则重新生成,然后调用 insert_strings;
     *  - 「取消操作」:AI 可直接 task_complete 结束。
     * 这种 AI 驱动的设计让 AI 能根据上下文(布局/代码 vs 直接给出译文)决定是否触发替换,
     * 也方便后续复用 replace_selection 做更多场景。
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

        // Priority 2:系统发起的重复 key 询问(Sheets)
        val pending = state.pendingSheetsInsert
        if (pending != null) {
            state.pendingSheetsInsert = null
            resolveDuplicateInsert(option, pending)
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

        // 2026.x 优化 D2:压缩过期 tool result。
        // 超过 [TOOL_RESULT_COMPACTION_THRESHOLD] 个 assistant 回合之前的 tool 消息,
        // 其原始内容(可能几百~几千字符)对当前 AI 决策帮助有限,但会每轮都参与序列化。
        // 压缩为占位符,长对话每轮可省 0.5-2KB。
        // 在 EDT 上做(chatMessages 是 SnapshotStateList,后台线程访问不安全)。
        try {
            SwingUtilities.invokeAndWait {
                compactOldToolResults()
            }
        } catch (e: Exception) {
            // 异常不应阻塞 AI 调用,继续走原流程
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
                    onPartialText = { contentCumulative, reasoningCumulative ->
                        // 跑在后台线程,先做轻量 stop 检查
                        if (state.stopRequested) return@chatStream
                        // content(最终回答)与 reasoning(思考过程)分别落到 message 的 content / thinking 字段
                        // ——SSE parser 已经把 delta.content 与 delta.reasoning_content 拆开,这里直接接住。
                        // 非推理模型 reasoningCumulative 始终为 "",UI 上 Thinking 折叠区自然不出现。
                        SwingUtilities.invokeLater {
                            if (placeholderIdx < state.chatMessages.size) {
                                val current = state.chatMessages[placeholderIdx]
                                if (current.content != contentCumulative ||
                                    current.thinking != reasoningCumulative
                                ) {
                                    state.chatMessages[placeholderIdx] = current.copy(
                                        content = contentCumulative,
                                        thinking = reasoningCumulative,
                                    )
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
            //      - thinking: 模型的「思考/推理」文本,流式时实时显示,流结束后折叠为可展开区;
            //        非推理模型 reasoning 为空时,UI 上 Thinking 区直接不出现。
            //      - content: 给用户看的「最终回复」——纯文本对话回合就是模型全文,
            //        function-calling 回合则是「执行操作:xxx」或 task_complete summary;
            //        引用面板的「翻译/解释/总结」动作里,这里就是真正的翻译结果。
            //    关键:之前两个分支都把 reply.reply 塞进 thinking、把 content 留给占位文案,
            //    导致「翻译」动作的最终结果被折叠在 Thought 内。修后:
            //      - 情况 A(无 tool_calls):content=reply.reply, thinking=reply.reasoning
            //      - 情况 B(只 task_complete):content=reply.reply(由 handleTaskComplete 追加 summary),
            //        thinking=reply.reasoning
            val realToolCalls = reply.toolCalls.filter { it.name != ToolDefinitions.TOOL_TASK_COMPLETE }
            val hasTaskComplete = reply.toolCalls.any { it.name == ToolDefinitions.TOOL_TASK_COMPLETE }
            // 关键修复:必须把「解析失败」的 tool_call 一起写入 assistant 消息的 toolCalls 字段,
            // 否则下一轮 OpenAI/DeepSeek 协议校验会失败:
            //   "Messages with role 'tool' must be a response to a preceding message with 'tool_calls'"
            // —— 之前 [finalToolCalls] 只用 realToolCalls(排除 failed),导致下面追加的
            // "解析失败" tool_result 的 tool_call_id 在上一条 assistant 消息里找不到对应 tool_use,
            // DeepSeek / OpenAI 立即返回 HTTP 400。
            // UI 层只对 realToolCalls 做"summarizeToolCalls"展示用,真正驱动 processAiReply 的是
            // reply.actions(只含解析成功的),所以混入 failed 不会重复执行。
            val finalToolCalls = realToolCalls + reply.failedToolCalls
            // 2026.x 优化 D1:把"执行操作:xxx"占位文案从 content 拆到独立的 protocolSummary 字段。
            // 协议层(toOpenAiMessage/toAnthropicMessage)看到这个字段非空时,会把 content 设为 null,
            // 避免冗余的占位文本进入 AI 上下文(每轮节省几十~几百字符);UI 端读 protocolSummary 渲染占位行。
            data class AssistantDisplay(val content: String, val summary: String?, val thinking: String)
            val display: AssistantDisplay = when {
                // 情况 A:模型纯文本回复(无工具调用)——
                // content 放最终回答(用户实际要看的),thinking 放思考过程(非推理模型时为空)。
                finalToolCalls.isEmpty() -> {
                    AssistantDisplay(reply.reply, null, reply.reasoning)
                }
                // 情况 B:只有 task_complete 终止信号
                // content 放模型的真实文本回答(例如引用面板「翻译」动作的翻译结果),
                //   handleTaskComplete 会在末尾追加 summary + notes 作为脚注,而不再覆盖整个 content;
                // thinking 放思考过程。
                realToolCalls.isEmpty() && hasTaskComplete -> {
                    AssistantDisplay(reply.reply, null, reply.reasoning)
                }
                // 情况 C:有真实工具调用(可叠加 task_complete,但 task_complete 之后会被前面分支截走,
                //    所以这里只可能是纯真实工具调用)
                else -> {
                    val hasAskUser = realToolCalls.any { it.name == ToolDefinitions.TOOL_ASK_USER }
                    // 关键:当本轮含 ask_user 时,**不要**用「执行操作: ask_user」这种占位覆盖
                    // content。ask_user 的「询问文字」会由 processAiReply 的 AskUser 分支单独存到
                    // ChatMessage.askQuestion 字段,UI 端会把它渲染为独立的"❓ Question"区块;
                    // content 字段则保留 reply.reply(AI 同时返回的「正文/前言」),这样最终气泡里
                    // 「思考 / 正文 / 询问文字」三段式能清晰拆分,不会互相覆盖。
                    val summary = when {
                        realToolCalls.isNotEmpty() && !hasAskUser -> {
                            "执行操作: ${summarizeToolCalls(realToolCalls)}"
                        }
                        reply.failedToolCalls.isNotEmpty() -> {
                            // 全部解析失败:在 protocolSummary 上提示一下,用户能直接看到失败信息
                            // (否则 UI 上只看到一行「执行操作:」)
                            "执行操作失败: ${summarizeToolCalls(reply.failedToolCalls)} 参数无法解析"
                        }
                        else -> {
                            // 含 ask_user(或纯 ask_user)时,正文由 reply.reply 承载,无 protocolSummary。
                            null
                        }
                    }
                    // 工具调用回合:thinking 写思考过程;
                    // content 在 protocolSummary 非空时(有真实非 ask_user 工具)留空,避免冗余文本进入 AI 上下文。
                    // 含 ask_user 时 content = reply.reply(有前言正文);只有 failed 时 content 空 + summary 提示。
                    /*val displayContent = when {
                        summary != null && !hasAskUser && reply.failedToolCalls.isEmpty() -> ""
                        summary != null && reply.failedToolCalls.isNotEmpty() -> ""
                        else -> reply.reply
                    }*/
                    val displayContent = reply.reply
                    AssistantDisplay(displayContent, summary, reply.reasoning)
                }
            }
            val finalContent = display.content
            val finalThinking = display.thinking
            val finalProtocolSummary = display.summary

            if (placeholderIdx in 0 until state.chatMessages.size) {
                val current = state.chatMessages[placeholderIdx]
                if (current.content != finalContent ||
                    current.toolCalls != finalToolCalls ||
                    current.thinking != finalThinking ||
                    current.protocolSummary != finalProtocolSummary ||
                    current.streaming
                ) {
                    state.chatMessages[placeholderIdx] = current.copy(
                        content = finalContent,
                        toolCalls = finalToolCalls,
                        thinking = finalThinking,
                        protocolSummary = finalProtocolSummary,
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
                        protocolSummary = finalProtocolSummary,
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
                // 带 options:把 question 写入 assistant 消息的 [ChatMessage.askQuestion](独立字段),
                // options 挂到 options 字段(让 UI 渲染按钮)。记录 toolCallId,等待用户点击。
                // 关键 — 拆分「询问文字」与「正文」:
                //   * 旧实现把 question 直接写进 content 字段,会覆盖掉 finishWithReply
                //     阶段保留的 AI 正文(reply.reply,例如「我需要问你一个问题」之类的引语);
                //   * 新增 [ChatMessage.askQuestion] 字段后,question 单独存,UI 端
                //     「思考 / 正文 / 询问文字 / 选项按钮」四段式能清晰展示,互不挤压。
                // 修复:之前只挂 options,question 文本被吞掉,用户只看到「执行操作: ask_user」+ 按钮,
                //      完全不知道 AI 在问什么(流式累积的 thinking 在回合结束后被折叠,看不到)。
                // 保留 thinking:用户希望每个 AI 回复气泡都能展开查看思考文字。
                val lastIdx = state.chatMessages.lastIndex
                if (lastIdx >= 0) {
                    state.chatMessages[lastIdx] = state.chatMessages[lastIdx].copy(
                        askQuestion = askAction.question,
                        options = askAction.options,
                    )
                }
            } else {
                // 无 options:同样把 question 写入 [ChatMessage.askQuestion] 而非 content,
                // 保持拆分语义;UI 会渲染为独立的"❓ Question"区块,提示用户到输入框中回复。
                // toast 作为辅助提示。thinking 保留为可展开详情。
                val lastIdx = state.chatMessages.lastIndex
                if (lastIdx >= 0) {
                    state.chatMessages[lastIdx] = state.chatMessages[lastIdx].copy(
                        askQuestion = askAction.question,
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

        // Priority 6.5: 代办操作域(todo_list / todo_add / todo_update / todo_delete / current_time)
        // 放在 sheets 之后、文件操作之前 —— 代办操作极轻量(纯本地 service CRUD),
        // 让 AI 在「读完 Sheets → 提醒用户」一类连续动作里能紧接着把代办加好,
        // 不必跨过几个耗时工具(读大文件 / edit_file 等)才能落库。
        // current_time 也放这里:它与代办联动(「5 分钟后提醒我」需要先拿 now 算时间戳),
        // 让 AI 在同一次 round-trip 内串行做完。
        val todoEntries = unprocessed.filter {
            it.action is AiAction.TodoList ||
                it.action is AiAction.TodoAdd ||
                it.action is AiAction.TodoUpdate ||
                it.action is AiAction.TodoDelete ||
                it.action is AiAction.CurrentTime
        }
        if (todoEntries.isNotEmpty()) {
            unprocessed.removeAll(todoEntries)
            executeTodoActions(todoEntries, context, iteration)
            addSkippedToolResults(unprocessed, "因已执行代办操作而跳过")
            return
        }

        // Priority 6.6: run_shell —— 在项目根执行 shell 命令并流式回灌输出。
        // 放在文件操作之前,语义上更独立(可单独一组,不像 file ops 那样会成批),
        // 也不太可能与文件操作同时出现(AI 要么调 read_file 看文件,要么调 run_shell 跑命令)。
        val shellEntries = unprocessed.filter { it.action is AiAction.RunShell }
        if (shellEntries.isNotEmpty()) {
            unprocessed.removeAll(shellEntries)
            executeShellActions(shellEntries, context, iteration)
            addSkippedToolResults(unprocessed, "因已执行 run_shell 而跳过")
            return
        }

        // Priority 6.7: read_diagnostics —— 读编辑器级 LSP/静态分析诊断。
        // 紧跟 run_shell 之后(常配合:跑 build 失败后,用 read_diagnostics 精准定位编辑器里打开的文件的错);
        // 纯本地 daemon 缓存读取,毫秒级返回,不会拖累并发。
        val diagEntries = unprocessed.filter { it.action is AiAction.ReadDiagnostics }
        if (diagEntries.isNotEmpty()) {
            unprocessed.removeAll(diagEntries)
            executeReadDiagnostics(diagEntries, context, iteration)
            addSkippedToolResults(unprocessed, "因已执行 read_diagnostics 而跳过")
            return
        }

        // Priority 6.8: fetch_url —— 拉取远程 URL 内容(只读 GET)。
        // 紧跟 read_diagnostics 之后(常见组合:看 build 失败原因 → fetch_url 读官方文档/issue 找方案);
        // 同步阻塞(几百 ms 到几秒),由 controller 内置超时(默认 10s)兜底,不卡死 tool loop。
        val fetchUrlEntries = unprocessed.filter { it.action is AiAction.FetchUrl }
        if (fetchUrlEntries.isNotEmpty()) {
            unprocessed.removeAll(fetchUrlEntries)
            executeFetchUrl(fetchUrlEntries, context, iteration)
            addSkippedToolResults(unprocessed, "因已执行 fetch_url 而跳过")
            return
        }

        // Priority 7: 文件操作域(get_editor_file / read_file / read_files / edit_file /
        //   create_file / delete_file / move_file / search_in_files / find_references /
        //   list_files / file_info)—— 2026.x 新增 4 个写代码工具(file_info / read_files /
        //   delete_file / move_file)
        val fileEntries = unprocessed.filter {
            it.action is AiAction.GetEditorFile ||
                it.action is AiAction.ReadFile ||
                it.action is AiAction.ReadFiles ||
                it.action is AiAction.EditFile ||
                it.action is AiAction.CreateFile ||
                it.action is AiAction.DeleteFile ||
                it.action is AiAction.MoveFile ||
                it.action is AiAction.SearchInFiles ||
                it.action is AiAction.FindReferences ||
                it.action is AiAction.ListFiles ||
                it.action is AiAction.FileInfo
        }
        if (fileEntries.isNotEmpty()) {
            unprocessed.removeAll(fileEntries)
            executeFileOps(fileEntries, context, iteration)
            addSkippedToolResults(unprocessed, "因已执行文件操作而跳过")
            return
        }

        // Priority 8: replace_selection — 把 chat 入口捕获的编辑器选区替换为对 key 的引用
        // 典型场景:翻译查重时用户点选「使用现有 key:<existing_key>」后,AI 调用本工具触发替换
        val replaceSelectionEntries = unprocessed.filter { it.action is AiAction.ReplaceSelection }
        if (replaceSelectionEntries.isNotEmpty()) {
            unprocessed.removeAll(replaceSelectionEntries)
            executeReplaceSelection(replaceSelectionEntries, context, iteration)
            addSkippedToolResults(unprocessed, "因已执行 replace_selection 而跳过")
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
                is AiAction.ReplaceSelection -> "replace_selection"
                is AiAction.TaskComplete -> "task_complete"
                is AiAction.TodoList -> "todo_list"
                is AiAction.TodoAdd -> "todo_add"
                is AiAction.TodoUpdate -> "todo_update"
                is AiAction.TodoDelete -> "todo_delete"
                is AiAction.CurrentTime -> "current_time"
                // 新增 4 个写代码工具
                is AiAction.FileInfo -> "file_info"
                is AiAction.ReadFiles -> "read_files"
                is AiAction.DeleteFile -> "delete_file"
                is AiAction.MoveFile -> "move_file"
                // 新增 run_shell(命令流式执行)
                is AiAction.RunShell -> "run_shell"
                // 新增 read_diagnostics(编辑器级 LSP 诊断)
                is AiAction.ReadDiagnostics -> "read_diagnostics"
                // 新增 fetch_url(URL 拉取)
                is AiAction.FetchUrl -> "fetch_url"
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
        // 把 task_complete 收尾到当前流式 assistant 气泡(同一条消息),而不是再追加一条新消息——
        // 这样思考(流式累积的 thinking)和回复(content)会落在同一个气泡内,符合
        // "思考和回复在同一个气泡内"的设计。
        //
        // 关键修改:如果 content 已经有内容(例如引用面板「翻译/解释/总结」动作,模型先回了
        // 真实回答再调 task_complete),把 summary 作为脚注追加到尾部,而不是直接覆盖整个 content。
        // 否则 content 为空时仍按 summary 单独填入(覆盖旧路径的兼容行为)。
        // 若对话历史里没有 assistant 消息(理论上不会发生,只是兜底),才追加新消息。
        val lastIdx = state.chatMessages.indexOfLast { it.role == "assistant" }
        if (lastIdx >= 0) {
            val current = state.chatMessages[lastIdx]
            val newContent = if (current.content.isNotBlank()) {
                current.content.trimEnd() + "\n\n" + text
            } else {
                text
            }
            state.chatMessages[lastIdx] = current.copy(
                content = newContent,
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
     * 2026.x 优化 D2:压缩过期 tool result(必须在 EDT 上调用)。
     *
     * 策略:从 chatMessages 末尾向前扫描,数出 [TOOL_RESULT_COMPACTION_THRESHOLD]
     * 个最近的 assistant 消息(带 toolCalls 的也算)。在它们之前的 tool 消息视为"过期",
     * 把 content 替换为 [TOOL_RESULT_COMPACTED_PLACEHOLDER]。
     *
     * 安全性:
     * 1. tool_use → tool_result 的配对 ID 不变,只换 content,Anthropic/OpenAI 协议都允许。
     * 2. 压缩后 AI 看到的是占位符(几十字符)而非原内容(可能几千字符),
     *    真要细节时再调一次对应工具。
     * 3. "最近 N 轮"内最近调用的结果完整保留,AI 仍能基于最近动作做决策。
     * 4. UI 端 chat tab 也会展示占位符(用户通常不会翻 5 轮前的工具气泡做核对,
     *    真要时回看完整 result 的诉求不强,接受这个折衷)。
     *
     * 副作用:压缩过的 tool 消息如果用户后续"翻历史查看"也是占位符;
     * 故本方法的覆盖范围是"超过 N 个 assistant 回合之前的所有 tool 消息",
     * 不动 ask_user / load_tool_doc / task_complete 等非 tool 消息,
     * 也不动 retry 提示气泡(protocolVisible = false,本来就不发协议层)。
     */
    private fun compactOldToolResults() {
        val messages = state.chatMessages
        if (messages.size < 4) return // 太少,不值得压缩

        // 1) 从末尾向前数 N 个 assistant 消息的下标,作为"保留边界"。
        //    边界之后(包括)的 tool 消息保留原文;边界之前的才压缩。
        val boundary = mutableListOf<Int>()
        for (i in messages.indices.reversed()) {
            val m = messages[i]
            if (m.protocolVisible && m.role == "assistant") {
                boundary.add(i)
                if (boundary.size >= TOOL_RESULT_COMPACTION_THRESHOLD) break
            }
        }
        if (boundary.isEmpty()) return
        val keepFromIdx = boundary.last() // 最早的"保留区"起点

        // 2) 收集边界之前的所有 tool 消息(及 user 带 toolCallId 的)
        //    —— 但**仅压缩其 content 比占位符长**的(避免无意义重复设置)。
        val toUpdate = mutableListOf<Pair<Int, ChatMessage>>()
        for (i in 0 until keepFromIdx) {
            val m = messages[i]
            if (!m.protocolVisible) continue
            val isToolResult = m.role == "tool" || (m.role == "user" && m.toolCallId != null)
            if (!isToolResult) continue
            if (m.content == TOOL_RESULT_COMPACTED_PLACEHOLDER) continue
            if (m.content.length <= TOOL_RESULT_COMPACTED_PLACEHOLDER.length) continue
            toUpdate.add(i to m.copy(content = TOOL_RESULT_COMPACTED_PLACEHOLDER))
        }

        // 3) 倒序更新,避免下标位移
        toUpdate.sortedByDescending { it.first }.forEach { (idx, newMsg) ->
            state.chatMessages[idx] = newMsg
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
                        is AiAction.ReadFiles -> fileOps.runReadFiles(action)
                        is AiAction.EditFile -> fileOps.runEditFile(action)
                        is AiAction.CreateFile -> fileOps.runCreateFile(action)
                        is AiAction.DeleteFile -> fileOps.runDeleteFile(action)
                        is AiAction.MoveFile -> fileOps.runMoveFile(action)
                        is AiAction.SearchInFiles -> fileOps.runSearchInFiles(action)
                        is AiAction.FindReferences -> fileOps.runFindReferences(action)
                        is AiAction.ListFiles -> fileOps.runListFiles(action)
                        is AiAction.FileInfo -> fileOps.runFileInfo(action)
                        else -> return@forEach
                    }
                } catch (e: Exception) {
                    val typeLabel = when (action) {
                        is AiAction.GetEditorFile -> "get_editor_file"
                        is AiAction.ReadFile -> "read_file"
                        is AiAction.ReadFiles -> "read_files"
                        is AiAction.EditFile -> "edit_file"
                        is AiAction.CreateFile -> "create_file"
                        is AiAction.DeleteFile -> "delete_file"
                        is AiAction.MoveFile -> "move_file"
                        is AiAction.SearchInFiles -> "search_in_files"
                        is AiAction.FindReferences -> "find_references"
                        is AiAction.ListFiles -> "list_files"
                        is AiAction.FileInfo -> "file_info"
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
     * 执行 [AiAction.RunShell] —— 在项目根目录跑一条 shell 命令,流式追加到 tool 消息。
     *
     * 流程:
     * 1. 跑在 pooled thread,串行处理同一轮内的多个 run_shell(同一回合极少见,但语义上正确);
     * 2. controller 内部预占一条 `streaming = true` 的 tool 消息(在 EDT 上 add),
     *    ProcessHandler 的 `onTextAvailable` 把每段 stdout/stderr 增量追加到该消息 ——
     *    用户能实时看到 gradle / git 之类的运行进度;
     * 3. 进程结束 / 超时后,controller 关掉 streaming 标志 + 返回最终 tool_result 字符串;
     * 4. driver 把这条 final result 作为另一条 `role = "tool"` 消息推回去 —
     *    UI 端的 `ToolGroupBubble` 会按 toolCallId 把这两条 tool 消息折成一张可折叠卡片,
     *    头部显示命令 + 状态,详情显示流式输出 + 最终汇总。
     *
     * 停止语义:每个 run_shell 之间检查 [state.stopRequested];IDE 顶栏「停止」按下时
     * 已经 in-flight 的进程由 controller 的 `destroyProcess()` 在超时路径里处理,
     * 后续 run_shell 不会再启动。
     */
    private fun executeShellActions(
        entries: List<ActionWithToolCall>,
        context: String,
        iteration: Int
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val pendingResults = mutableListOf<Pair<String, String>>()
            entries.forEach { (action, toolCallId) ->
                if (toolCallId.isEmpty()) return@forEach
                if (state.stopRequested) {
                    pendingResults.add(
                        toolCallId to "[工具执行结果] 类型:run_shell 状态:已取消 信息:用户停止"
                    )
                    return@forEach
                }
                val resultText = try {
                    (action as? AiAction.RunShell)?.let { shellOps.runShell(toolCallId, it) }
                        ?: "[工具执行异常] run_shell 失败:action 类型不匹配"
                } catch (e: Exception) {
                    "[工具执行异常] run_shell 失败:${e.message ?: e.javaClass.simpleName}"
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
     * 执行 [AiAction.FetchUrl] —— HTTP GET 远程 URL 并返回响应体。
     *
     * 流程:
     * 1. 跑在 pooled thread,同步阻塞到 controller 返回(超时由 controller 内部兜底,
     *    默认 10s);driver 不再做二次 wait。
     * 2. controller 把响应转成 `[工具执行结果] 类型:fetch_url ...` 字符串(含状态码、
     *    Content-Type、耗时、原始大小、`---BEGIN BODY---` 包住的可读响应体)。
     * 3. driver 直接把字符串作为 `role=tool` 消息推回 — 不会在 tool loop 中制造
     *    长时间悬挂的占位。
     *
     * 停止语义:每个 fetch_url 之间检查 [state.stopRequested];但 HTTP 请求已经在路上
     * 了,本方法不主动中断(JDK HttpClient 不支持中途 cancel),仅在下一次 loop 时不再
     * 发起新请求。
     */
    private fun executeFetchUrl(
        entries: List<ActionWithToolCall>,
        context: String,
        iteration: Int
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val pendingResults = mutableListOf<Pair<String, String>>()
            entries.forEach { (action, toolCallId) ->
                if (toolCallId.isEmpty()) return@forEach
                if (state.stopRequested) {
                    pendingResults.add(
                        toolCallId to "[工具执行结果] 类型:fetch_url 状态:已取消 信息:用户停止"
                    )
                    return@forEach
                }
                val resultText = try {
                    (action as? AiAction.FetchUrl)?.let { fetchUrlOps.fetchUrl(it) }
                        ?: "[工具执行异常] fetch_url 失败:action 类型不匹配"
                } catch (e: Exception) {
                    "[工具执行异常] fetch_url 失败:${e.message ?: e.javaClass.simpleName}"
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
     * 执行 [AiAction.ReadDiagnostics] — 读当前打开文件的编辑器级诊断。
     *
     * 流程:
     * 1. 跑在 pooled thread;controller 内部枚举打开文件(EDT 上)+ 读 highlights(ReadAction),
     *    同步完成,毫秒级返回。
     * 2. controller 把结果序列化为 `JsonObject` 字符串(`[工具执行结果] 类型:read_diagnostics ...` 起头),
     *    driver 直接作为 tool_result 消息推回去。
     * 3. 一次调用只读一次 daemon 缓存;如果 AI 想"读完错 → 改 → 再读"循环,会触发
     *    多轮 tool loop,每轮重新读缓存(daemon 异步跑通常 100-500ms 追上)。
     *
     * 停止语义:如果 `state.stopRequested` 为 true,直接返回"已取消"信息,不调 daemon。
     */
    private fun executeReadDiagnostics(
        entries: List<ActionWithToolCall>,
        context: String,
        iteration: Int
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val pendingResults = mutableListOf<Pair<String, String>>()
            entries.forEach { (action, toolCallId) ->
                if (toolCallId.isEmpty()) return@forEach
                if (state.stopRequested) {
                    pendingResults.add(
                        toolCallId to "[工具执行结果] 类型:read_diagnostics 状态:已取消 信息:用户停止"
                    )
                    return@forEach
                }
                val resultText = try {
                    val a = action as? AiAction.ReadDiagnostics
                    if (a == null) {
                        "[工具执行异常] read_diagnostics 失败:action 类型不匹配"
                    } else {
                        val severity = diagnosticsOps.parseSeverity(a.minSeverity)
                        val result = diagnosticsOps.collectOpenFileDiagnostics(severity)
                        diagnosticsOps.formatToolResult(result, state.project.basePath)
                    }
                } catch (e: Exception) {
                    "[工具执行异常] read_diagnostics 失败:${e.message ?: e.javaClass.simpleName}"
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
     * 执行 [AiAction.ReplaceSelection] — 把 chat 入口捕获的编辑器选区替换为对 key 的引用。
     *
     * 同一回合内若有多个 replace_selection,串行执行(每个都触发 WriteCommandAction,
     * 顺序上后写覆盖前写,通常不会有这种调用场景)。
     *
     * 与 [executeFileOps] 不同:本方法在 EDT 上同步执行(controller 已 invokeAndWait),
     * 不需要后台线程 + 跨线程回传;但为了和其它 tool_executor 保持接口一致,这里也走
     * 后台线程 + SwingUtilities.invokeLater 的模式。
     */
    private fun executeReplaceSelection(
        entries: List<ActionWithToolCall>,
        context: String,
        iteration: Int
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val pendingResults = mutableListOf<Pair<String, String>>()
            entries.forEach { (action, toolCallId) ->
                if (toolCallId.isEmpty()) return@forEach
                val resultText = when (action) {
                    is AiAction.ReplaceSelection -> editorOps.runReplaceSelection(action)
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
     * 执行代办操作域(本地 service CRUD,无需后台线程)。
     *
     * 与 strings / sheets 域不同,代办操作是**纯本地**:
     * - 写操作:todo_add / todo_update / todo_delete 直接调 [cn.jarryleo.insert_strings.ai.TodoService];
     * - 读操作:todo_list 走 [cn.jarryleo.insert_strings.ai.TodoService.list] /
     *   [cn.jarryleo.insert_strings.ai.TodoService.listActive] / listCompleted 三种;
     *
     * 每个动作都返回 [cn.jarryleo.insert_strings.ai.TodoItem](读) / 操作结果(写)作为
     * tool_result,让 AI 在下一轮看到完整对象,可以做"读完改"、"改完再读"等连续动作。
     *
     * UI 同步:写操作完成后调 [cn.jarryleo.insert_strings.ui.InsertStringsTodosController]
     * 的等效方法(controller 内部会重排 todos 列表),让用户的 Todo tab 立即反映 AI 的改动。
     *
     * 错误处理:
     * - todo_add 时 title trim 后为空 → AITranslator 解析阶段就 return null,
     *   不会走到这里;真走到这里时 title 必非空。
     * - todo_update / todo_delete 的 id 不存在 → 给该 tool_call 发 [工具执行异常] tool_result,
     *   不抛异常中断流程(让 AI 能看到错误并自行修正)。
     */
    private fun executeTodoActions(
        entries: List<ActionWithToolCall>,
        context: String,
        iteration: Int
    ) {
        val ui = state as? cn.jarryleo.insert_strings.ui.InsertStringsUI
        val service = cn.jarryleo.insert_strings.ai.TodoService.getInstance()
        entries.forEach { (action, toolCallId) ->
            when (action) {
                is AiAction.TodoList -> {
                    val items: List<cn.jarryleo.insert_strings.ai.TodoItem> = when (action.filter.lowercase()) {
                        "completed" -> service.listCompleted()
                        "all" -> service.list()
                        else -> service.listActive()
                    }
                    val limit = action.limit ?: 50
                    val limited = items.take(limit.coerceAtLeast(1))
                    val payload = limited.joinToString("\n") { item ->
                        // 输出 id + title + priority + isCompleted + content(若有) + reminder(若有),
                        // 格式:JSON-like 一行一条,AI 解析后能直接拿去 todo_update / todo_delete。
                        buildString {
                            append("{\"id\":\"").append(item.id).append("\",")
                            append("\"title\":\"").append(escapeJson(item.title)).append("\",")
                            append("\"priority\":\"").append(item.priority.name).append("\",")
                            append("\"isCompleted\":").append(item.isCompleted)
                            if (item.content.isNotBlank()) {
                                append(",\"content\":\"").append(escapeJson(item.content)).append("\"")
                            }
                            val r = item.reminder
                            if (r != null) {
                                append(",\"reminder\":").append(reminderToJsonString(r))
                            }
                            append("}")
                        }
                    }
                    val totalCount = items.size
                    val shown = limited.size
                    val header = when (action.filter.lowercase()) {
                        "completed" -> "[代办列表] 过滤:completed,共 $totalCount 条,展示前 $shown 条。"
                        "all" -> "[代办列表] 过滤:all,共 $totalCount 条,展示前 $shown 条。"
                        else -> "[代办列表] 过滤:active,共 $totalCount 条,展示前 $shown 条。"
                    }
                    val content = if (limited.isEmpty()) {
                        "$header\n(无符合条件的代办)"
                    } else {
                        "$header\n$payload"
                    }
                    if (toolCallId.isNotEmpty()) {
                        state.chatMessages.add(
                            ChatMessage(
                                role = "tool",
                                content = "[工具执行结果] 类型:todo_list 状态:成功\n$content",
                                toolCallId = toolCallId
                            )
                        )
                    }
                }
                is AiAction.TodoAdd -> {
                    val title = action.title.trim()
                    if (title.isEmpty()) {
                        if (toolCallId.isNotEmpty()) {
                            state.chatMessages.add(
                                ChatMessage(
                                    role = "tool",
                                    content = "[工具执行异常] 类型:todo_add 状态:失败 信息:title 不能为空(trim 后为空)。",
                                    toolCallId = toolCallId
                                )
                            )
                        }
                    } else {
                        val priority = cn.jarryleo.insert_strings.ai.TodoPriority.fromName(action.priority)
                        val reminder = buildReminderFromAiArgs(
                            reminderTime = action.reminderTime,
                            reminderDate = action.reminderDate,
                            reminderTimeOfDay = action.reminderTimeOfDay,
                            recurrence = action.recurrence,
                            recurrenceDays = action.recurrenceDays,
                        )
                        val newItem = cn.jarryleo.insert_strings.ai.TodoItem(
                            title = title,
                            content = action.content,
                            priority = priority,
                            isCompleted = false,
                            createdAt = System.currentTimeMillis(),
                            completedAt = null,
                            reminder = reminder,
                        )
                        service.upsert(newItem)
                        // 同步 UI 列表:走 controller 的 reloadTodos()(内部按 active/completed 排序后
                        // 整体替换 ui.todos),让用户的 Todo tab 立即看到 AI 新增的条目。
                        ui?.todosController?.reloadTodos()
                        // 通知调度器:AI 设置了 reminder,需要加入 Timer 队列
                        if (reminder != null) {
                            cn.jarryleo.insert_strings.ai.TodoReminderScheduler.getInstance()
                                .notifyReminderChanged(newItem.id)
                        }
                        if (toolCallId.isNotEmpty()) {
                            val reminderJson = reminder?.let { reminderToJsonString(it) }
                            val reminderField = if (reminderJson != null) ",\"reminder\":$reminderJson" else ""
                            state.chatMessages.add(
                                ChatMessage(
                                    role = "tool",
                                    content = "[工具执行结果] 类型:todo_add 状态:成功\n" +
                                        "{\"id\":\"${newItem.id}\",\"title\":\"${escapeJson(newItem.title)}\"," +
                                        "\"priority\":\"${newItem.priority.name}\",\"isCompleted\":false$reminderField}",
                                    toolCallId = toolCallId
                                )
                            )
                        }
                    }
                }
                is AiAction.TodoUpdate -> {
                    val current = service.get(action.id)
                    if (current == null) {
                        if (toolCallId.isNotEmpty()) {
                            state.chatMessages.add(
                                ChatMessage(
                                    role = "tool",
                                    content = "[工具执行异常] 类型:todo_update 状态:失败 信息:未找到 id=${action.id} 的代办。请先 todo_list 拿到正确 id 再更新。",
                                    toolCallId = toolCallId
                                )
                            )
                        }
                    } else {
                        val newTitle = action.title?.trim()?.takeIf { it.isNotEmpty() } ?: current.title
                        val newContent = action.content ?: current.content
                        val newPriority = if (action.priority != null) {
                            cn.jarryleo.insert_strings.ai.TodoPriority.fromName(action.priority)
                        } else current.priority
                        val newCompleted = action.isCompleted ?: current.isCompleted
                        // 校验:title 不能 trim 后为空(AITranslator 阶段会校验,但本轮也兜底)
                        if (action.title != null && action.title.trim().isEmpty()) {
                            if (toolCallId.isNotEmpty()) {
                                state.chatMessages.add(
                                    ChatMessage(
                                        role = "tool",
                                        content = "[工具执行异常] 类型:todo_update 状态:失败 信息:title 不能为空(trim 后为空)。",
                                        toolCallId = toolCallId
                                    )
                                )
                            }
                            return@forEach
                        }
                        // reminder 计算:clearReminder=true → null;否则按 reminderTime/reminderDate/recurrence/recurrenceDays 合成
                        val newReminder: cn.jarryleo.insert_strings.ai.TodoReminder? = when {
                            action.clearReminder -> null
                            action.reminderTime != null || action.reminderDate != null ||
                                action.reminderTimeOfDay != null || action.recurrence != null ||
                                action.recurrenceDays != null -> {
                                buildReminderFromAiArgs(
                                    reminderTime = action.reminderTime ?: current.reminder?.nextTriggerAt,
                                    reminderDate = action.reminderDate,
                                    reminderTimeOfDay = action.reminderTimeOfDay
                                        ?: current.reminder?.timeOfDay?.format(),
                                    recurrence = action.recurrence,
                                    recurrenceDays = action.recurrenceDays,
                                )
                            }
                            else -> current.reminder
                        }
                        val updated = current.copy(
                            title = newTitle,
                            content = newContent,
                            priority = newPriority,
                            isCompleted = newCompleted,
                            reminder = newReminder,
                        )
                        service.upsert(updated)
                        // 完成态切换时同时维护 completedAt 时间戳(同 [TodoService.setCompleted] 语义)。
                        if (action.isCompleted != null && action.isCompleted != current.isCompleted) {
                            service.setCompleted(action.id, newCompleted)
                        }
                        ui?.todosController?.reloadTodos()
                        // 通知调度器:reminder 字段可能变了
                        val scheduler = cn.jarryleo.insert_strings.ai.TodoReminderScheduler.getInstance()
                        when {
                            newReminder == null && current.reminder != null -> scheduler.notifyReminderRemoved(updated.id)
                            newReminder != null -> scheduler.notifyReminderChanged(updated.id)
                            else -> { /* 都没变,no-op */ }
                        }
                        if (toolCallId.isNotEmpty()) {
                            val reminderJson = newReminder?.let { reminderToJsonString(it) }
                            val reminderField = if (reminderJson != null) ",\"reminder\":$reminderJson" else ""
                            state.chatMessages.add(
                                ChatMessage(
                                    role = "tool",
                                    content = "[工具执行结果] 类型:todo_update 状态:成功\n" +
                                        "{\"id\":\"${updated.id}\",\"title\":\"${escapeJson(updated.title)}\"," +
                                        "\"priority\":\"${updated.priority.name}\",\"isCompleted\":${updated.isCompleted}$reminderField}",
                                    toolCallId = toolCallId
                                )
                            )
                        }
                    }
                }
                is AiAction.TodoDelete -> {
                    val current = service.get(action.id)
                    if (current == null) {
                        if (toolCallId.isNotEmpty()) {
                            state.chatMessages.add(
                                ChatMessage(
                                    role = "tool",
                                    content = "[工具执行异常] 类型:todo_delete 状态:失败 信息:未找到 id=${action.id} 的代办。",
                                    toolCallId = toolCallId
                                )
                            )
                        }
                    } else {
                        val hadReminder = current.reminder != null
                        service.delete(action.id)
                        ui?.todosController?.reloadTodos()
                        if (hadReminder) {
                            cn.jarryleo.insert_strings.ai.TodoReminderScheduler.getInstance()
                                .notifyReminderRemoved(current.id)
                        }
                        if (toolCallId.isNotEmpty()) {
                            state.chatMessages.add(
                                ChatMessage(
                                    role = "tool",
                                    content = "[工具执行结果] 类型:todo_delete 状态:成功\n" +
                                        "已删除 id=${action.id}, title=\"${escapeJson(current.title)}\"",
                                    toolCallId = toolCallId
                                )
                            )
                        }
                    }
                }
                is AiAction.CurrentTime -> {
                    // 无副作用:直接返回 now 的多种表达(让 AI 选最合适的格式用)
                    val nowMs = System.currentTimeMillis()
                    val tz = java.util.TimeZone.getDefault()
                    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).apply {
                        timeZone = tz
                    }
                    val human = fmt.format(java.util.Date(nowMs))
                    val payload = "{\"timestamp\":$nowMs,\"formatted\":\"$human\"," +
                        "\"timezone\":\"${tz.id}\",\"offsetMinutes\":${tz.getOffset(nowMs) / 60_000}}"
                    if (toolCallId.isNotEmpty()) {
                        state.chatMessages.add(
                            ChatMessage(
                                role = "tool",
                                content = "[工具执行结果] 类型:current_time 状态:成功\n" +
                                    "{\"now\":$payload}",
                                toolCallId = toolCallId
                            )
                        )
                    }
                }
                else -> {
                    // 不应到达这里(只在 4 个 todo 动作上调用)
                }
            }
        }
        // 走下一轮 tool loop
        continueToolLoopInBackground(context, iteration + 1)
    }

    /**
     * 把 [String] 转义为 JSON 字符串字面量(转义双引号与反斜杠),用于构造 tool_result 里的
     * 简易 JSON 行(AI 自己反序列化用)。
     * 不做完整 JSON 转义(换行 / 制表符等),对代办 title / content 这种短文本足够。
     */
    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    /**
     * 把 AI 传入的 reminder 参数合成为 [cn.jarryleo.insert_strings.ai.TodoReminder]。
     *
     * 优先级(从高到低):
     * 1. [reminderDate] 出现 → 「指定日期 + 时分」语义:系统按本地时区组装 timestamp,
     *    recurrence 强制 NONE(忽略 AI 传的 recurrence/recurrenceDays,避免语义冲突)。
     * 2. [reminderTime] 出现 → 旧路径(绝对 timestamp),[reminderTimeOfDay] 被忽略。
     * 3. [reminderTimeOfDay] + [recurrence] 出现(无 date / 无 time)→ 循环 + 时分语义:
     *    DAILY → 下一个匹配 HH:MM 的时间戳;CUSTOM → 根据 [recurrenceDays] 找下一个匹配 day-of-week;
     *    NONE → 拒绝(语义不清,需要 AI 改用 reminderDate 或 reminderTime)。
     * 4. 都没有 → 返回 null(无提醒)。
     *
     * @param reminderTime       绝对时间戳(毫秒),旧 API
     * @param reminderDate       YYYY-MM-DD 本地日期,与 [reminderTimeOfDay] 配套
     * @param reminderTimeOfDay  HH:MM 24h 字符串,既能与 date 配套,也能与 recurrence 配套
     * @param recurrence         循环类型
     * @param recurrenceDays     CUSTOM 循环的星期几(1=周一...7=周日)
     */
    private fun buildReminderFromAiArgs(
        reminderTime: Long?,
        reminderDate: String?,
        reminderTimeOfDay: String?,
        recurrence: String?,
        recurrenceDays: List<Int>?,
    ): cn.jarryleo.insert_strings.ai.TodoReminder? {
        val parsedDate = parseReminderDate(reminderDate)
        if (parsedDate != null) {
            // 路径 A:用户用「指定日期」语义。reminderDate 出现即视为一次性,
            // 即便 AI 也传了 recurrence/recurrenceDays 也忽略(避免互相冲突)。
            val (hour, minute) = parseReminderTimeOfDay(reminderTimeOfDay) ?: (9 to 0)
            val cal = java.util.Calendar.getInstance().apply {
                clear()
                set(parsedDate.first, parsedDate.second, parsedDate.third, hour, minute, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            val ts = cal.timeInMillis
            val tod = cn.jarryleo.insert_strings.ai.TodoTimeOfDay(hour, minute)
            return cn.jarryleo.insert_strings.ai.TodoReminder(
                enabled = true,
                nextTriggerAt = ts,
                recurrence = cn.jarryleo.insert_strings.ai.TodoRecurrence.NONE,
                timeOfDay = tod,
                recurrenceDays = mutableSetOf(),
            )
        }
        // 路径 B:旧路径,reminderTime 是绝对 timestamp
        if (reminderTime != null) {
            val rec = cn.jarryleo.insert_strings.ai.TodoRecurrence.fromName(recurrence)
            val tod = cn.jarryleo.insert_strings.ai.TodoReminder.deriveDefaultsFrom(reminderTime).first
            val days = if (rec == cn.jarryleo.insert_strings.ai.TodoRecurrence.CUSTOM) {
                (recurrenceDays ?: emptyList()).filter { it in 1..7 }.toMutableSet()
            } else mutableSetOf()
            return cn.jarryleo.insert_strings.ai.TodoReminder(
                enabled = true,
                nextTriggerAt = reminderTime,
                recurrence = rec,
                timeOfDay = tod,
                recurrenceDays = days,
            )
        }
        // 路径 C:reminderTimeOfDay + recurrence 组合 —— 让 AI 不用算 timestamp 也能写循环提醒。
        // 典型场景:用户说「每周一 13:00 提醒我开会」→ recurrence=CUSTOM, recurrenceDays=[1], reminderTimeOfDay="13:00"。
        val (hour, minute) = parseReminderTimeOfDay(reminderTimeOfDay) ?: return null
        val rec = cn.jarryleo.insert_strings.ai.TodoRecurrence.fromName(recurrence)
        val tod = cn.jarryleo.insert_strings.ai.TodoTimeOfDay(hour, minute)
        val days = (recurrenceDays ?: emptyList()).filter { it in 1..7 }.toSet()
        // 校验:CUSTOM 必填 recurrenceDays(否则 scheduler 的 computeNextTriggerAfter 会因 days 为空返回 null,
        // 循环无法滚动)。NONE 不接受单独 reminderTimeOfDay(没 date 没 time 语义不清,直接拒绝)。
        if (rec == cn.jarryleo.insert_strings.ai.TodoRecurrence.CUSTOM && days.isEmpty()) {
            return null
        }
        if (rec == cn.jarryleo.insert_strings.ai.TodoRecurrence.NONE) {
            // 没有 reminderDate 又没 reminderTime,只剩一个 reminderTimeOfDay 语义不清(今天 13:00?明天 13:00?)。
            // 直接拒绝,提示 AI 改用 reminderDate 或 reminderTime。
            return null
        }
        // 计算首次触发:用 recurrence + days 找下一个匹配 day-of-week + 时分。
        val now = System.currentTimeMillis()
        val allowedDays: Set<Int> = when (rec) {
            cn.jarryleo.insert_strings.ai.TodoRecurrence.DAILY -> emptySet() // 空集 = 任意 day 都行
            cn.jarryleo.insert_strings.ai.TodoRecurrence.CUSTOM -> days
            else -> emptySet()
        }
        val firstTs = tod.nextOccurrence(now, allowedDays)
        return cn.jarryleo.insert_strings.ai.TodoReminder(
            enabled = true,
            nextTriggerAt = firstTs,
            recurrence = rec,
            timeOfDay = tod,
            recurrenceDays = days.toMutableSet(),
        )
    }

    /**
     * 解析 [reminderDate] (YYYY-MM-DD) → (year, month0based, dayOfMonth);解析失败返回 null。
     * 月份从 0 开始(与 [java.util.Calendar] 口径一致),内部转换时已经 +0 处理,调用方直接传给 Calendar.set 即可。
     */
    private fun parseReminderDate(raw: String?): Triple<Int, Int, Int>? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim()
        if (s.length != 10 || s[4] != '-' || s[7] != '-') return null
        val y = s.substring(0, 4).toIntOrNull() ?: return null
        val m = s.substring(5, 7).toIntOrNull() ?: return null
        val d = s.substring(8, 10).toIntOrNull() ?: return null
        if (m !in 1..12 || d !in 1..31) return null
        return Triple(y, m - 1, d)
    }

    /**
     * 解析 [reminderTimeOfDay] (HH:MM) → (hour, minute);解析失败返回 null(由调用方决定默认值)。
     */
    private fun parseReminderTimeOfDay(raw: String?): Pair<Int, Int>? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim()
        if (s.length != 5 || s[2] != ':') return null
        val h = s.substring(0, 2).toIntOrNull() ?: return null
        val m = s.substring(3, 5).toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h to m
    }

    /**
     * 把 [cn.jarryleo.insert_strings.ai.TodoReminder] 序列化为简短 JSON,供 AI 工具结果回传。
     *
     * 字段(2026.x 增强):
     * - `nextTriggerAt` Unix 毫秒时间戳 — 机器友好,用于相对时间运算;
     * - `nextTriggerAtFormatted` 人类可读时间 `yyyy-MM-dd HH:mm` — 不用让 AI 自己转时区;
     * - `triggerInMinutes` 距离触发的剩余分钟数 — 让 AI 一眼看出"还有多久";
     * - `recurrence` / `timeOfDay` / `days` 循环配置(同 context 里的 todos.active[].reminder 字段)。
     */
    private fun reminderToJsonString(r: cn.jarryleo.insert_strings.ai.TodoReminder): String {
        val tod = r.timeOfDay?.format() ?: "-"
        val days = if (r.recurrence == cn.jarryleo.insert_strings.ai.TodoRecurrence.CUSTOM) {
            r.recurrenceDays.sorted().joinToString(",")
        } else "-"
        val at = r.nextTriggerAt
        val nextTs = at ?: -1L
        val nextFormatted = if (at != null) {
            val timeFmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            timeFmt.format(java.util.Date(at))
        } else "-"
        // 距离触发的剩余分钟数;已过期显示负数(让 AI 知道提醒被拖延了)
        val triggerInMinutes = if (at != null) {
            (at - System.currentTimeMillis()) / 60_000L
        } else -1L
        // 2026.x 增强:暴露 expired 字段,让 AI 在用户说「提醒我 X」「我有什么提醒」时
        // 能立刻判断「这条已经过期」并主动告知(否则 AI 看到 nextTriggerAt 在过去只能瞎猜)。
        // 仅当 reminder 启用 + 有 nextTriggerAt + 已过 now 时为 true。
        val expired = r.enabled && at != null && at < System.currentTimeMillis()
        return "{\"nextTriggerAt\":$nextTs,\"nextTriggerAtFormatted\":\"$nextFormatted\"," +
            "\"triggerInMinutes\":$triggerInMinutes,\"expired\":$expired," +
            "\"recurrence\":\"${r.recurrence.name}\",\"timeOfDay\":\"$tod\",\"days\":\"$days\"}"
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

        // 翻译查重已交给 AI 自行处理(用 find_keys_by_text + ask_user):
        // - AI 在调用 insert_strings 之前应已用 find_keys_by_text 扫过现有 key,若发现重复会主动用
        //   ask_user 询问用户「使用现有 key / 插入新 key / 取消」;
        // - 用户在 ask_user 中选择「使用现有 key:<name>」时,系统会拦截该选项并触发
        //   ChatStateHolder.onInsertStringsInserted(让 ExtractStrings 把硬编码文本替换为
        //   @string/<key> 或 R.string.<key>),然后再把选项作为 tool_result 回传给 AI;
        // - 此处不再做系统侧查重,避免与 AI 的查重逻辑重复执行。

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
            // 逐项合并:AI 漏写 <![CDATA[]]> / <Data></Data> 包裹时,沿用原有包裹 —
            // 避免 insert_strings 覆写带 HTML 的翻译时把包裹一起吞掉(丢了之后
            // Android 端会按字面量渲染 <,lint 也会报 unescaped <)。详见
            // [cn.jarryleo.insert_strings.xml.AndroidStringEscaper.preserveWrapping]。
            action.translations.forEach { (lang, aiText) ->
                val existing = merged[lang]
                merged[lang] = if (existing != null) {
                    cn.jarryleo.insert_strings.xml.AndroidStringEscaper
                        .preserveWrapping(existing, aiText)
                } else {
                    aiText
                }
            }
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

        //state.closeChatView()

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
}

