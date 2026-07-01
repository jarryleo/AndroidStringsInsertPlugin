package cn.jarryleo.android.buddy.ai

import androidx.compose.runtime.mutableStateOf
import java.util.UUID

/**
 * 代办优先级。
 *
 * 用途:
 * - UI 列表里左侧用不同颜色的圆点 / 文字标识,用户扫一眼就能识别轻重缓急;
 * - AI 提醒时按优先级排序,「URGENT > HIGH > NORMAL > LOW」,提醒文案也按此取舍。
 *
 * 字符串名 = enum 名,持久化与 AI 通信都直接用 name;反序列化用 [fromName] 容错。
 */
enum class TodoPriority {
    LOW,
    NORMAL,
    HIGH,
    URGENT,
    ;

    companion object {
        /**
         * 从字符串解析(大小写不敏感),失败时回退到 [NORMAL]。
         * 用于反序列化 / AI 传入未知值时的兜底,避免 throw 中断流程。
         */
        fun fromName(name: String?): TodoPriority {
            if (name.isNullOrBlank()) return NORMAL
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: NORMAL
        }
    }
}

/**
 * 单条代办事项。
 *
 * 用途:用户在插件主页「Todo」tab 添加的待办;AI 也能通过 `todo_*` 工具读写这些条目。
 *
 * 字段约束:
 * - [id] 稳定 UUID,AI 工具 `todo_update` / `todo_delete` 必传,UI 编辑/删除也按 id 定位。
 *   新建时由 [cn.jarryleo.android.buddy.ui.InsertStringsTodosController] 分配,UI 不应自行设置。
 * - [title] 必填,人类可读的标题;为空时 UI 回退为 "(untitled)"。
 * - [content] 可选详细描述(多行,留空时 UI 不显示描述区)。
 * - [priority] 优先级,默认 [TodoPriority.NORMAL]。
 * - [isCompleted] 是否已完成。完成时把 [completedAt] 置为当前时间戳,UI 会用删除线 + 灰色呈现;
 *   取消完成时清空 [completedAt]。
 * - [createdAt] 创建时间戳,用于排序(默认按创建时间倒序展示,新加的在上)。
 * - [completedAt] 完成时间戳,仅在 [isCompleted] = true 时有值;用于将来扩展"今日完成"统计。
 * - [reminder] 可选提醒配置(null = 没有提醒);有值时 UI 显示闹钟图标,
 *             调度器按 [TodoReminder.nextTriggerAt] 触发右下角弹框。
 *             循环提醒触发后由调度器自动滚动 nextTriggerAt 到下一次;一次性触发后调度器把整个 reminder 置 null。
 *
 * 字段全 `var`:IntelliJ XmlSerializer 需要 setter;data class 的 val 属性会导致反序列化失败。
 * 手写 [equals] / [hashCode] / [toString] / [copy] 替代 data class 能力。
 */
class TodoItem(
    var id: String = "",
    var title: String = "",
    var content: String = "",
    var priority: TodoPriority = TodoPriority.NORMAL,
    isCompleted: Boolean = false,
    var createdAt: Long = System.currentTimeMillis(),
    var completedAt: Long? = null,
    var reminder: TodoReminder? = null,
) {

    /**
     * UI 用 Compose state(2026.x 修复)。
     *
     * 历史 bug:`val completeState = mutableStateOf(isCompleted)` 只在构造时读一次
     * [isCompleted](默认 false),所以 IntelliJ XmlSerializer 反序列化时——
     *   1) 构造 TodoItem() → isCompleted=false → completeState = mutableStateOf(false)
     *   2) 反射调用 setter 把 isCompleted 设为 true → completeState 仍是 false
     * ——导致插件重启后所有已完成代办 UI 上不显示勾选,直到用户手动点一次。
     *
     * 修复:让 [isCompleted] setter 同步写 [_isCompletedState],保证任意入口(反序列化、
     * 用户点击、TodoService.setCompleted、AI 工具)更新 isCompleted 后,UI 立即看到。
     * 公开的 [completeState] 仍是 [State] 引用,UI 与 controller 代码不动。
     */
    private val _isCompletedState = mutableStateOf(isCompleted)

    val completeState: androidx.compose.runtime.State<Boolean> get() = _isCompletedState

    var isCompleted: Boolean = isCompleted
        set(value) {
            field = value
            _isCompletedState.value = value
        }

    fun copy(
        id: String = this.id,
        title: String = this.title,
        content: String = this.content,
        priority: TodoPriority = this.priority,
        isCompleted: Boolean = this.isCompleted,
        createdAt: Long = this.createdAt,
        completedAt: Long? = this.completedAt,
        reminder: TodoReminder? = this.reminder,
    ): TodoItem = TodoItem(
        id = id,
        title = title,
        content = content,
        priority = priority,
        isCompleted = isCompleted,
        createdAt = createdAt,
        completedAt = completedAt,
        reminder = reminder?.copy(),
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TodoItem) return false
        return id == other.id &&
            title == other.title &&
            content == other.content &&
            priority == other.priority &&
            isCompleted == other.isCompleted &&
            createdAt == other.createdAt &&
            completedAt == other.completedAt &&
            reminder == other.reminder
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + priority.hashCode()
        result = 31 * result + isCompleted.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + (completedAt?.hashCode() ?: 0)
        result = 31 * result + (reminder?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "TodoItem(id=$id, title=$title, priority=$priority, completed=$isCompleted, hasReminder=${reminder != null})"

    companion object {
        /**
         * 新建一条空白代办(id 自动分配,createdAt = now),供「+ Add」按钮使用。
         */
        fun blank(): TodoItem = TodoItem(
            id = UUID.randomUUID().toString(),
            title = "",
            content = "",
            priority = TodoPriority.NORMAL,
            isCompleted = false,
            createdAt = System.currentTimeMillis(),
            completedAt = null,
            reminder = null,
        )
    }
}
