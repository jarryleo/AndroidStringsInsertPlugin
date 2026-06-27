package cn.jarryleo.insert_strings.ai

import cn.jarryleo.insert_strings.ui.TodoReminderPopup
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/**
 * 全局(应用级)代办提醒调度器。
 *
 * **职责**:
 * 1. 启动时从 [TodoService] 加载所有有提醒的 todo,把「最近一次触发」的 Timer 安排上;
 * 2. 提供 [notifyReminderChanged] / [notifyReminderRemoved] 两个写操作入口,
 *    任何 todo_add / todo_update / 删除 / 完成 / 暂停提醒 的路径都走这里;
 * 3. 触发时弹出 [TodoReminderPopup](非模态 + always-on-top),用户选择"完成 / 1m / 5m / 10m"
 *    后由本调度器负责把 [TodoItem.reminder] 写回(完成 / 滚动到下一次 / 暂停 N 分钟)。
 *
 * **持久化 vs 内存**:
 * - 真实数据(enabled / nextTriggerAt / recurrence)存在 [TodoService] 里,IDE 重启自动从
 *   `insertStringsTodos.xml` 加载。
 * - 内存里只维护一个 [ScheduledFuture] 引用;重启后通过 [onProjectReady] (在
 *   [com.intellij.openapi.startup.StartupActivity] 触发)重新调度。
 *
 * **过期处理**(用户已确认):
 * - 重启 IDE 时,过期 24h+ 的一次性提醒会被静默清除(避免堆积陈年旧账);
 * - 过期 < 24h 的一次性提醒立即触发弹框,让用户还有机会看到;
 * - 循环提醒无论过期多久都滚动到下一次(循环语义就是"过去的事不再追究")。
 *
 * **线程模型**:
 * - 内部用 [ScheduledExecutorService](单线程 daemon),Timer 任务在后台线程跑;
 * - 写盘 + 弹框统一在 EDT 上执行(避免 Swing 线程违例 + XmlSerializer 偶发的并发问题)。
 *
 * **生命周期**:
 * - [Service] 注解让 IntelliJ 自动管理单例(applicationService);
 * - [dispose] 在 IDE 关闭时被调用,关闭 executor,防止线程泄漏。
 */
@Service(Service.Level.APP)
class TodoReminderScheduler : Disposable {

    private val log = thisLogger()

    /**
     * 单线程 daemon executor,Timer 任务都跑在它上面。
     * 用 daemon 线程,IDE 关闭时 JVM 不必等它。
     */
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "TodoReminderScheduler").apply { isDaemon = true }
    }

    /**
     * 当前已安排的 Timer 句柄;只在重新安排 / 取消时被替换。
     * 永远引用「下一次要触发的 reminder」的 ScheduledFuture。
     */
    @Volatile
    private var pendingFire: ScheduledFuture<*>? = null

    /**
     * 重新加载 [TodoService] 里的全部活跃提醒并重新调度最近一次触发。
     *
     * 触发时机:
     * - IDE 启动完成([com.intellij.openapi.startup.StartupActivity] 调用);
     * - todo 列表发生变化(新增/编辑/删除)由 controller 主动调一次,确保 Timer 与磁盘一致。
     *
     * **不**重新加载「未来很久」才会触发的提醒 —— 反正它们还要很久,
     * 等待真正需要时再安排即可,避免后台线程长期持有 Future。
     */
    fun rescheduleAll() {
        cancelPending()
        val now = System.currentTimeMillis()
        val service = TodoService.getInstance()
        val items = service.listActiveReminders()
        val next = items
            .mapNotNull { item -> item.reminder?.nextTriggerAt?.let { it to item.id } }
            .filter { (at, _) -> at >= now }
            .minByOrNull { it.first }
        if (next == null) {
            log.info("TodoReminderScheduler: no upcoming reminder to schedule")
            return
        }
        val (at, itemId) = next
        val delayMs = at - now
        val service2 = service
        pendingFire = executor.schedule(
            { fireById(itemId) },
            delayMs,
            TimeUnit.MILLISECONDS,
        )
        log.info("TodoReminderScheduler: scheduled item=$itemId in ${delayMs}ms")
        // 静默清除过期 24h+ 的一次性提醒(用户已确认)
        sweepExpired(service2, now)
    }

    /**
     * 把指定 [itemId] 的 todo 标记为「立即触发」,重新安排到 [newTriggerAt]。
     * 写盘 + UI 列表刷新由 [TodoService] 完成;本方法不直接动 service,
     * 只负责"通知调度器这条 todo 的时间变了"。
     *
     * 触发场景:
     * - UI 设置/编辑了 todo 的提醒,新的 [TodoReminder.nextTriggerAt] 与旧的不一致;
     * - AI 调 todo_add / todo_update 写入新的 reminder 字段;
     * - 弹框「1/5/10 分钟后再提醒」按钮 → controller 改 nextTriggerAt → 通知本调度器。
     */
    fun notifyReminderChanged(itemId: String) {
        // 简单粗暴:整体重排。比维护精确的「取消哪条 / 加哪条」更可靠,
        // 列表量小(<200)重排代价可忽略。
        rescheduleAll()
        // 顺手刷一下 UI(让 list 视图里的 nextTriggerAt 立刻更新)
        refreshUiList()
    }

    /**
     * 取消 [itemId] 对应的提醒(整条清掉 reminder 字段);调度器只需 rescheduleAll
     * 即可(新列表里没有它了)。
     */
    fun notifyReminderRemoved(itemId: String) {
        rescheduleAll()
        refreshUiList()
    }

    /**
     * 关闭 Timer,释放资源。IDE 关闭时被 IntelliJ 自动调用。
     */
    override fun dispose() {
        cancelPending()
        executor.shutdownNow()
    }

    // ===== 内部实现 =====

    private fun cancelPending() {
        pendingFire?.cancel(false)
        pendingFire = null
    }

    /**
     * Timer 触发了:在 EDT 上找 todo 弹框。
     * 这里用 itemId 而不是 TodoItem 引用,避免后台线程持有 UI 状态(可能已被 UI 重排)。
     */
    private fun fireById(itemId: String) {
        SwingUtilities.invokeLater {
            val item = TodoService.getInstance().get(itemId) ?: return@invokeLater
            val reminder = item.reminder
            if (reminder == null || !reminder.enabled) return@invokeLater
            // 防御性兜底:触发时若 nextTriggerAt 为 null,直接清掉,不再 schedule
            if (reminder.nextTriggerAt == null) {
                TodoService.getInstance().setReminder(itemId, null)
                refreshUiList()
                return@invokeLater
            }
            showPopup(item, reminder)
        }
    }

    /**
     * 真正弹出 [TodoReminderPopup],并把用户选择通过 [handleChoice] 写回 service。
     */
    private fun showPopup(item: TodoItem, reminder: TodoReminder) {
        val popup = TodoReminderPopup(
            item = item,
            reminder = reminder,
            onChoice = { choice -> handleChoice(item.id, reminder, choice) },
        )
        popup.isVisible = true
    }

    /**
     * 把用户在弹框里做的选择翻译成 [TodoItem.reminder] 的新值,并写回 service。
     *
     * - [TodoReminderPopup.Choice.DONE] 一次性 → reminder = null;
     *   循环 → nextTriggerAt = computeNextTriggerAfter(now),其它字段保留。
     * - [SNOOZE_1M / SNOOZE_5M / SNOOZE_10M] → nextTriggerAt = now + N 分钟,recurrence 保留
     *   (用「N 分钟后再次提醒」语义,保持与用户预期一致 —— 不会因为是循环就跳过这次)。
     */
    private fun handleChoice(itemId: String, oldReminder: TodoReminder, choice: TodoReminderPopup.Choice) {
        val now = System.currentTimeMillis()
        val newReminder: TodoReminder? = when (choice) {
            TodoReminderPopup.Choice.DONE -> {
                if (oldReminder.recurrence == TodoRecurrence.NONE) {
                    null
                } else {
                    // 循环:滚动到下一次;若 compute 返回 null(防御性)就一并清掉
                    val next = oldReminder.copy(nextTriggerAt = now).computeNextTriggerAfter(now)
                    if (next == null) null
                    else oldReminder.copy(nextTriggerAt = next, enabled = true)
                }
            }
            TodoReminderPopup.Choice.SNOOZE_1M ->
                oldReminder.copy(nextTriggerAt = now + 1 * 60 * 1000L, enabled = true)
            TodoReminderPopup.Choice.SNOOZE_5M ->
                oldReminder.copy(nextTriggerAt = now + 5 * 60 * 1000L, enabled = true)
            TodoReminderPopup.Choice.SNOOZE_10M ->
                oldReminder.copy(nextTriggerAt = now + 10 * 60 * 1000L, enabled = true)
        }
        TodoService.getInstance().setReminder(itemId, newReminder)
        refreshUiList()
        if (newReminder != null) {
            // 重新排 Timer(可能下一次就是几秒后,也可能是一周后)
            notifyReminderChanged(itemId)
        } else {
            notifyReminderRemoved(itemId)
        }
    }

    /**
     * 把 [TodoService] 里过期 24h+ 的一次性提醒静默清除(用户已确认行为)。
     * 循环提醒不会被本方法影响,直接 rescheduleAll 即可滚动到下一次。
     */
    private fun sweepExpired(service: TodoService, now: Long) {
        val day = 24L * 60 * 60 * 1000
        val expired = service.list().filter { item ->
            val r = item.reminder ?: return@filter false
            if (r.recurrence != TodoRecurrence.NONE) return@filter false
            val at = r.nextTriggerAt ?: return@filter false
            // 过期 24h+ 视为"已经没意义了",静默清掉
            at < now - day
        }
        if (expired.isEmpty()) return
        log.info("TodoReminderScheduler: sweeping ${expired.size} expired one-time reminders")
        expired.forEach { service.setReminder(it.id, null) }
        refreshUiList()
    }

    /**
     * 通知 UI 刷新代办列表(让闹钟图标 / 下次时间立即反映新状态)。
     * 通过 InsertStringsTodosController.refreshUi() 静态入口转发 —— 该入口是
     * [cn.jarryleo.insert_strings.ui.InsertStringsTodosController] 暴露的 service-level 钩子,
     * 在 UI 装配时由 InsertStringsUI.registerTodosController() 注册。
     */
    private fun refreshUiList() {
        try {
            TodoUiRefresher.refresh()
        } catch (e: Throwable) {
            log.warn("TodoReminderScheduler: UI refresh failed", e)
        }
    }

    companion object {
        @JvmStatic
        fun getInstance(): TodoReminderScheduler {
            return ApplicationManager.getApplication().getService(TodoReminderScheduler::class.java)
        }
    }
}

/**
 * UI ↔ Scheduler 解耦的「UI 列表刷新钩子」。
 *
 * 为什么需要:Scheduler 写在 `ai` 包,不能反向依赖 `ui` 包里的 controller(避免循环依赖);
 * 但 scheduler 触发后需要让 Todo tab 立刻反映新的 reminder 状态(更新 list / 闹钟图标)。
 *
 * 解决:[InsertStringsUI] 在装配时通过 [setRefresher] 注册一个回调;Scheduler 触发时调
 * [refresh],回调在 EDT 上执行,内部通过 service 拿到 controller 引用并 reload。
 */
object TodoUiRefresher {
    @Volatile
    private var refresher: (() -> Unit)? = null

    fun setRefresher(r: () -> Unit) {
        refresher = r
    }

    fun refresh() {
        refresher?.invoke()
    }
}
