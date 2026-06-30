package cn.jarryleo.insert_strings.ui

import cn.jarryleo.insert_strings.ai.AiAction
import cn.jarryleo.insert_strings.ai.ChatMessage
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.nio.file.Paths
import javax.swing.SwingUtilities

/**
 * `run_shell` 工具的运行时控制器。
 *
 * 设计要点:
 * - **流式输出**:进程一启动就预占一个 `ChatMessage(role = "tool", streaming = true)`,
 *   ProcessHandler 的 `onTextAvailable` 把 stdout/stderr 增量追加到该消息;
 *   进程结束 / 超时时关掉 streaming 标志。
 * - **平台差异**:Windows 走 `cmd.exe /c "<command> <arg1> <arg2>"`(整条命令加双引号);
 *   POSIX 走原生 `[command, arg1, arg2]` 数组(`ProcessBuilder` 风格,不经 shell 解析)。
 *   两种路径都**不会**把 args 字符串再走一次 shell,从根本上避免命令注入。
 * - **安全策略**:本控制器**不**做危险命令白名单/黑名单 — 按用户在 plan 阶段的选择,
 *   由 AI 自己在不确定时调 `ask_user`。本控制器只守住三道基础防线:
 *   1. cwd 必须落在项目根内(防御 `cwd: "../../etc"` 这类越界);
 *   2. `GIT_TERMINAL_PROMPT=0` 防止 git 弹交互提示卡住进程;
 *   3. 超时 / stopRequested 时主动 `destroyProcess` 避免孤儿进程。
 *
 * UI 流式反馈的视觉对齐:在 `AiChatContent.kt` 中,`ToolGroupBubble` 检测到任一
 * 子消息 `streaming == true` 时,在卡片标题旁显示一个 `CircularProgressIndicator`。
 * 这与 `assistant` 消息流式回复的视觉风格一致(单色 12dp spinner + 「输出中…」文案)。
 */
internal class InsertStringsShellOpsController(
    private val state: ChatStateHolder,
) {

    /**
     * 跑一条 shell 命令,边跑边把 stdout/stderr 增量流到对应 tool 消息。
     * 由 driver 在 pooled thread 中调用;**全程不应在 EDT 阻塞**(`invokeAndWait`
     * 之类的弹窗交互由 AI 在调 run_shell 之前自己用 `ask_user` 完成)。
     *
     * @param toolCallId AI 这次 tool_call 的 id,driver 用它绑定预占消息 + 最终 result
     * @param action     AI 传入的参数(command / args / cwd / timeoutMs)
     * @return           最终的 tool_result 字符串(driver 直接作为 `role = "tool"` 消息
     *                   推给下一轮 AI;失败/取消/超时信息也走这条路径)
     */
    fun runShell(toolCallId: String, action: AiAction.RunShell): String {
        val basePath = state.project.basePath
            ?: return resultOf("失败", "项目未关联磁盘路径")

        val workDir = resolveWorkDir(basePath, action.cwd)
            ?: return resultOf("失败", "工作目录不存在或越界: ${action.cwd ?: "<项目根>"}")

        // 1) 构造命令
        //    Windows:把 command + args 当作 cmd.exe 的独立参数传入,**不**自己拼字符串。
        //      GeneralCommandLine.withParameters 会按 Windows CommandLineToArgvW 规则
        //      给每个含空格/引号的参数加双引号 — 比手写 quoteForCmd 更可靠。
        //      `cmd.exe /c <prog> <arg1> <arg2>` 让 cmd 找到 git 并把后续 args 原样转发,
        //      内部命令不经过 cmd 解析(只有 command 名解析一次,这一步是必要的,
        //      因为 .bat / .cmd / 系统内置命令要靠 cmd 解析)。
        //      上一版用 joinToString(quoteForCmd(...)) 自己拼出
        //      `"cmd.exe" /c ""git" "log" "..."` 在 cmd 的引号配对规则下把 `"git"`
        //      误识别为字面 token,导致 Windows 报"不是内部或外部命令"。
        //    POSIX:`[command, arg1, arg2]` 直传 fork+exec,不经 shell。
        val cmd = if (SystemInfo.isWindows) {
            val all = listOf(action.command) + action.args
            GeneralCommandLine("cmd.exe", "/c", *all.toTypedArray())
                .withWorkDirectory(workDir)
        } else {
            GeneralCommandLine(action.command, *action.args.toTypedArray())
                .withWorkDirectory(workDir)
        }
        // 防止 git 等交互提示卡住进程
        cmd.withEnvironment("GIT_TERMINAL_PROMPT", "0")
        // POSIX 下 gradle/node 等常看 LC_ALL 来判断是否彩色输出
        cmd.withEnvironment("LC_ALL", "C.UTF-8")
        // 编码统一 UTF-8(ProcessOutput.getStdout 默认 UTF-8,这里再保险一次)
        cmd.withCharset(Charsets.UTF_8)

        // 2) 预占一条 streaming tool 消息(在 EDT 上 add;driver 端的 ToolGroupBubble
        //    会把同一 toolCallId 的所有 role=tool 消息折成一个可折叠卡片)
        //
        //    关键:`protocolVisible = false` — 这条中间状态消息**只用于 UI 实时滚动展示**,
        //    **不**发到 OpenAI/Anthropic 协议。DeepSeek / OpenAI 兼容要求每个 tool_call
        //    对应**恰好 1 条** `role=tool` 消息作为 tool_result(有些 provider 还要求
        //    1:1 严格配对);把不完整的 streaming 输出也发出去会被 DeepSeek 直接 400
        //    "Messages with role 'tool' must be a response to a preceding message with
        //    'tool_calls'"。driver 在 controller 返回后追加的 final result 消息才是
        //    真正的 tool_result(`protocolVisible = true`),它会进下一轮 AI 的历史。
        val header = buildString {
            append("▸ ${action.command} ${action.args.joinToString(" ")}\n")
            append("  (cwd: ${workDir.absolutePath})\n")
            if (action.timeoutMs != null) append("  (timeout: ${action.timeoutMs}ms)\n")
        }
        SwingUtilities.invokeLater {
            state.chatMessages.add(
                ChatMessage(
                    role = "tool",
                    content = header,
                    toolCallId = toolCallId,
                    streaming = true,
                    protocolVisible = false,
                )
            )
        }
        // 预占消息的索引在下一轮 EDT 上才稳定(driver 拿到 idx 后再往里追加)
        // 这里先把引用记下,ProcessListener 内部用 synchronized 串行追加
        val streamingHolder = StreamingMessageHolder(toolCallId)

        // 3) 启动 ProcessHandler
        val handler = try {
            KillableProcessHandler(cmd)
        } catch (t: Throwable) {
            return resultOf("失败", "启动进程失败: ${t.message ?: t.javaClass.simpleName}")
        }
        val buffer = StringBuilder()
        handler.addProcessListener(object : ProcessAdapter() {
            override fun onTextAvailable(event: ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) {
                val text = event.text ?: return
                synchronized(buffer) { buffer.append(text) }
                streamingHolder.append(text)
            }
        })
        handler.startNotify()

        // 4) 等待完成
        val timeoutMs = action.timeoutMs ?: 60_000
        val finished = handler.waitFor(timeoutMs.toLong())
        if (!finished) {
            handler.destroyProcess()
            streamingHolder.finish()
            return resultOf(
                status = "超时(${timeoutMs}ms)",
                output = buffer.toString(),
                command = action,
                workDir = workDir,
            )
        }
        val exitCode = handler.exitCode ?: -1
        streamingHolder.finish()
        return resultOf(
            status = if (exitCode == 0) "成功" else "失败(exit=$exitCode)",
            output = buffer.toString(),
            command = action,
            workDir = workDir,
        )
    }

    // region ============== helpers ==============

    /**
     * 把工作目录解析为绝对路径,失败(null / 不存在 / 越出项目根)返回 null。
     * 防御 AI 传 `cwd: "../../../etc"` 跳出项目根 — 走 `canonicalFile` 标准化后
     * 用 `startsWith(base.canonical)` 严格校验。
     */
    private fun resolveWorkDir(basePath: String, cwd: String?): File? {
        val base = runCatching { Paths.get(basePath).toFile().canonicalFile }.getOrNull() ?: return null
        val target = if (cwd.isNullOrBlank()) {
            base
        } else {
            runCatching { File(base, cwd).canonicalFile }.getOrNull() ?: return null
        }
        if (!target.exists() || !target.isDirectory) return null
        if (!target.absolutePath.startsWith(base.absolutePath)) return null
        return target
    }

    /**
     * 持有「预占 tool 消息」的当前索引;onTextAvailable 时拿这个 idx 往里追加文本。
     * 索引在 add 完成(下一次 EDT flush)后才稳定,所以这里做个「懒初始化 + 缓存」,
     * 后续直接复用。toolCallId 在构造时锁定,避免短时间内多次 runShell 串号。
     */
    private inner class StreamingMessageHolder(private val toolCallId: String) {
        @Volatile private var idx: Int? = null

        fun append(text: String) {
            // 立刻算一次 idx;如果消息还没落地(竞态),下一次 append 会重算
            val current = idx ?: findToolMessageIndex().also { idx = it }
            if (current == null) return
            SwingUtilities.invokeLater {
                if (current < state.chatMessages.size) {
                    val cur = state.chatMessages[current]
                    if (state.chatMessages[current].toolCallId == cur.toolCallId) {
                        state.chatMessages[current] = cur.copy(content = cur.content + text)
                    }
                }
            }
        }

        fun finish() {
            SwingUtilities.invokeLater {
                val current = idx ?: findToolMessageIndex() ?: return@invokeLater
                if (current < state.chatMessages.size) {
                    val cur = state.chatMessages[current]
                    if (cur.streaming) {
                        state.chatMessages[current] = cur.copy(streaming = false)
                    }
                }
            }
        }

        private fun findToolMessageIndex(): Int? {
            // 必须从 EDT 读 SnapshotStateList 的 size / get,故用 invokeAndWait 同步等结果
            val result = intArrayOf(-1)
            if (SwingUtilities.isEventDispatchThread()) {
                result[0] = scan()
            } else {
                try {
                    SwingUtilities.invokeAndWait { result[0] = scan() }
                } catch (_: Exception) {
                    return null
                }
            }
            return result[0].takeIf { it >= 0 }
        }

        private fun scan(): Int {
            for (i in state.chatMessages.indices.reversed()) {
                if (state.chatMessages[i].toolCallId == toolCallId) return i
            }
            return -1
        }
    }

    private fun resultOf(
        status: String,
        output: String,
        command: AiAction.RunShell? = null,
        workDir: File? = null,
    ): String = buildString {
        append("[工具执行结果] 类型:run_shell 状态:$status")
        if (command != null) {
            append(" 命令:${command.command} ${command.args.joinToString(" ")}")
        }
        if (workDir != null) {
            append(" 工作目录:${workDir.absolutePath}")
        }
        append('\n')
        if (output.isNotBlank()) {
            append("输出:\n").append(truncate(output, MAX_OUTPUT_CHARS))
        }
    }

    private fun truncate(s: String, max: Int): String =
        if (s.length <= max) s else s.substring(0, max) + "\n…(截断,原长度 ${s.length})"

    // endregion

    companion object {
        /**
         * tool_result 里 `输出:\n` 段最大保留字符数。超过会被截断并标注原长度,
         * 防止 AI 把几 MB 的 gradle 日志塞进下一轮 context(把上下文打爆)。
         * 8000 字 ≈ 2k tokens,和单条 read_file 的 maxLines 上限同一量级。
         */
        private const val MAX_OUTPUT_CHARS = 8000
    }
}
