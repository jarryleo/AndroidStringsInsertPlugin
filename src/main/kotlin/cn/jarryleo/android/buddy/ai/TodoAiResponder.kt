package cn.jarryleo.android.buddy.ai

import com.intellij.openapi.diagnostic.thisLogger

/**
 * Scheduler ↔ Chat Driver 解耦的「AI 回复触发钩子」(2026.x 新增)。
 *
 * **为什么需要**:Scheduler 在 `ai` 包,不能反向依赖 `ui` 包里的 chat driver
 * (避免循环依赖);但提醒触发后,需要让 AI 看到提醒内容并生成回复,
 * 再把回复用 IDE 气泡展示。
 *
 * **解决**:`[cn.jarryleo.android.buddy.ui.InsertStringsUI]` 在装配时通过 [setResponder]
 * 注册一个回调,该回调内部调用 chat driver 的 [sendSystemMessageAndAwait] 方法,
 * 把系统消息发给 AI 并等待回复。
 *
 * **触发时机**:[TodoReminderScheduler.fireById] 弹框展示时,同步触发
 * [respondToReminder] 让 AI 并行生成回复文本(不影响用户点 snooze/done 的操作)。
 * AI 回复到后,scheduler 用 IDE 通知气泡展示在右下角。
 *
 * **线程模型**:回调在 EDT 上调用,内部用 `invokeLater` 保证 UI 写操作在 EDT;
 * 实际 AI 请求是异步的(chat driver 自己管协程)。
 */
object TodoAiResponder {
    private val log = thisLogger()

    /**
     * 注册的 responder 回调。签名:
     * - 第一参数:系统提示文本(由 scheduler 构造,描述"哪个 todo 触发了提醒")。
     * - 第二参数:onResponse 回调,AI 回复到后被调用,参数是回复的纯文本。
     */
    @Volatile
    private var responder: ((systemMessage: String, onResponse: (String) -> Unit) -> Unit)? = null

    /**
     * 由 [cn.jarryleo.android.buddy.ui.InsertStringsUI.createToolWindowContent]
     * 在 tool window 打开时注册一次。
     */
    fun setResponder(r: (systemMessage: String, onResponse: (String) -> Unit) -> Unit) {
        responder = r
    }

    /**
     * Scheduler 触发的入口。系统消息会作为「user」消息加入 chat,AI 看到后会生成回复。
     * AI 回复到后,onResponse 会被调用(可能在后台线程,实现方自行 EDT 化)。
     */
    fun respondToReminder(systemMessage: String, onResponse: (String) -> Unit) {
        val r = responder
        if (r == null) {
            log.warn("TodoAiResponder: no responder registered (tool window 还没打开?)")
            return
        }
        try {
            r.invoke(systemMessage, onResponse)
        } catch (e: Throwable) {
            log.warn("TodoAiResponder: responder failed", e)
        }
    }
}
