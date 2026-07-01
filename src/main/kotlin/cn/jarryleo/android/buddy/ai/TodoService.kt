package cn.jarryleo.android.buddy.ai

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.util.UUID

/**
 * 持久化层 bean。IntelliJ 的 [PersistentStateComponent] 通过反射读写 bean 属性,
 * 所以这里每个字段必须是 public `var`,`MutableList<TodoItem>` 会被自动 XML 序列化。
 */
class TodoState {
    var items: MutableList<TodoItem> = mutableListOf()
}

/**
 * 全局(应用级)代办列表的持久化服务。
 *
 * 用 `applicationService` 而非 `projectService`:
 * - 「代办」是用户的个人事项,不与具体项目绑定;
 * - 换项目也能直接用同一份列表,无需重复配置。
 *
 * 存储位置:`<config>/options/insertStringsTodos.xml`(应用级配置目录)。
 *
 * 列表**不强制**按任何顺序写入;UI / driver 层各自按需要排序(默认按 [TodoItem.createdAt]
 * 倒序 / 按优先级 + createdAt 倒序)。service 只保证 CRUD 的原子性 + 写盘。
 */
@State(
    name = "InsertStringsTodos",
    storages = [Storage("insertStringsTodos.xml")]
)
class TodoService : PersistentStateComponent<TodoState> {
    private var state = TodoState()

    override fun getState(): TodoState = state

    @Suppress("DEPRECATION")
    override fun loadState(state: TodoState) {
        this.state = state
        // 防御性:历史数据可能缺 id(早期版本或外部导入);为缺 id 的条目补一个 UUID,
        // 避免后续 update/delete 时无法定位。
        state.items.forEach { item ->
            if (item.id.isBlank()) item.id = UUID.randomUUID().toString()
        }
        // 防御性:磁盘上的老 XML 仍可能含 WEEKDAYS / WEEKLY 这两种已合并的循环类型。
        // XmlSerializer 在反序列化时调 setter 走 [TodoReminder] 上的迁移逻辑即可,
        // 这里再补一次保证:任何从 state.items 直接读出来的 reminder 都已是新枚举。
        // (setter 已处理了反序列化路径,这里只兜底「未来若有人跳过 setter 直接赋值」的情况。)
        state.items.forEach { item ->
            val r = item.reminder ?: return@forEach
            when (r.recurrence) {
                TodoRecurrence.WEEKDAYS -> {
                    r.recurrence = TodoRecurrence.CUSTOM
                    if (r.recurrenceDays.isEmpty()) r.recurrenceDays = (1..5).toMutableSet()
                }
                TodoRecurrence.WEEKLY -> {
                    r.recurrence = TodoRecurrence.CUSTOM
                    if (r.recurrenceDays.isEmpty()) {
                        val at = r.nextTriggerAt ?: System.currentTimeMillis()
                        val dow = TodoTimeOfDay.toCalendarDayOfWeek(
                            java.util.Calendar.getInstance().apply { timeInMillis = at }
                        )
                        r.recurrenceDays = mutableSetOf(dow)
                    }
                }
                else -> Unit
            }
        }
    }

    // ============== 读 ==============

    /**
     * 全部代办(返回不可变副本,避免外部直接修改内部 state)。
     * UI 一般不会直接用这个,会用 [listActive] / [listCompleted] 过滤后展示。
     */
    fun list(): List<TodoItem> = state.items.toList()

    /**
     * 未完成的代办(供主列表默认视图 + AI 上下文注入用)。
     */
    fun listActive(): List<TodoItem> = state.items.filter { !it.isCompleted }

    /**
     * 已完成的代办(供「Completed」过滤视图用)。
     */
    fun listCompleted(): List<TodoItem> = state.items.filter { it.isCompleted }

    /**
     * 按 id 查一条;不存在返回 null。
     * AI 的 todo_update / todo_delete 工具走这条路。
     */
    fun get(id: String): TodoItem? = state.items.firstOrNull { it.id == id }

    // ============== 写 ==============

    /**
     * 新增或更新一条代办(按 [TodoItem.id] 匹配)。
     * - 新增:id 由 caller 提供或保持 blank(由 service 补 UUID);
     * - 更新:按 id 替换原条目;
     * - 完成态切换由 [setCompleted] 单独处理(自动写 [TodoItem.completedAt] 时间戳)。
     */
    fun upsert(item: TodoItem) {
        if (item.id.isBlank()) item.id = UUID.randomUUID().toString()
        val list = state.items
        val idx = list.indexOfFirst { it.id == item.id }
        if (idx >= 0) {
            list[idx] = item
        } else {
            list.add(item)
        }
    }

    /**
     * 按 id 删除一条;id 不存在时 no-op。
     */
    fun delete(id: String) {
        state.items.removeAll { it.id == id }
    }

    /**
     * 把 [id] 对应条目的 [TodoItem.isCompleted] 设为 [completed],并自动维护
     * [TodoItem.completedAt] 时间戳:
     * - true → completedAt = System.currentTimeMillis()
     * - false → completedAt = null
     *
     * AI 标记完成 / 取消完成都走这个入口,保证时间戳一致;UI 端"勾选 checkbox"
     * 也可以直接调它。
     */
    fun setCompleted(id: String, completed: Boolean) {
        val list = state.items
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return
        val current = list[idx]
        // 没变化就 no-op(避免无谓的写盘 + 时间戳被刷新)
        if (current.isCompleted == completed) return
        current.isCompleted = completed
        current.completedAt = if (completed) System.currentTimeMillis() else null
    }

    // ============== 提醒(2026.x 新增) ==============

    /**
     * 列出所有「尚未完成 + 有提醒 + 已启用 + 触发时间在未来或最近 N 分钟」的活动提醒。
     *
     * 用于:
     * - [cn.jarryleo.android.buddy.ai.TodoReminderScheduler] 启动时把这些提醒加入 Timer;
     * - UI 「下一条提醒」角标(将来扩展)。
     *
     * 不传 [withinMillis] 时只返回 [nextTriggerAt] > now 的提醒;
     * 传了非 0 值时把 [withinMillis] 毫秒内过期的也算上(让启动时能「立即补触发」错过的小窗口)。
     */
    fun listActiveReminders(withinMillis: Long = 0L): List<TodoItem> {
        val now = System.currentTimeMillis()
        val threshold = now - withinMillis
        return state.items.filter { item ->
            !item.isCompleted &&
                item.reminder?.enabled == true &&
                item.reminder?.nextTriggerAt != null &&
                item.reminder?.nextTriggerAt!! >= threshold
        }
    }

    /**
     * 按 id 更新 [TodoItem.reminder] 字段;新值为 null 时等价于"清除提醒"。
     * id 不存在时 no-op。
     */
    fun setReminder(id: String, reminder: TodoReminder?) {
        val list = state.items
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return
        list[idx].reminder = reminder
    }

    companion object {
        @JvmStatic
        fun getInstance(): TodoService {
            return ApplicationManager.getApplication().getService(TodoService::class.java)
        }
    }
}
