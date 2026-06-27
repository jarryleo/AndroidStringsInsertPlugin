package cn.jarryleo.insert_strings.ai

import java.util.Calendar

/**
 * 单条代办上的提醒配置。
 *
 * 字段语义:
 * - [enabled] 提醒是否启用。false 时即便 [nextTriggerAt] 仍在未来,调度器也不会触发它;
 *             用于「先关闭、再恢复」的场景(用户暂不希望被打扰)。
 * - [nextTriggerAt] 下一次触发的绝对时间戳(毫秒);null 表示「待用户设置时间」(新建草稿态)。
 * - [recurrence] 循环类型(默认 [TodoRecurrence.NONE] = 一次性触发)。
 * - [timeOfDay] 一天中的固定时间(小时 + 分钟);循环提醒按此时间计算下一次触发。
 *               一次性提醒时也保留这个字段,方便用户把一条一次性提醒"改成"循环,
 *               直接复用原时间;不强制要求。
 * - [recurrenceDays] 自定义循环的星期几(1=周一,...,7=周日),仅 [recurrence]=[TodoRecurrence.CUSTOM] 时使用。
 *                    其它循环类型由各自规则决定(WEEKDAYS = 1-5, WEEKLY = 从 nextTriggerAt 推算 day-of-week),
 *                    不依赖本字段。
 *
 * 持久化:作为 [TodoItem.reminder] 字段整体写盘,IntelliJ XmlSerializer 支持 var 字段嵌套。
 * 重启恢复:IDE 重启时 [TodoService] 加载所有 [TodoItem] 进 [cn.jarryleo.insert_strings.ai.TodoReminderScheduler],
 *          调度器根据 [nextTriggerAt] 重新安排 Timer。
 *
 * 计算下一次触发时间:[computeNextTriggerAfter]
 */
class TodoReminder(
    var enabled: Boolean = true,
    var nextTriggerAt: Long? = null,
    var recurrence: TodoRecurrence = TodoRecurrence.NONE,
    var timeOfDay: TodoTimeOfDay? = null,
    var recurrenceDays: MutableSet<Int> = mutableSetOf(),
) {

    /**
     * 工具方法:从当前 [nextTriggerAt] 推算下一次触发时间(用于触发后自动滚动)。
     *
     * - [recurrence] = [TodoRecurrence.NONE]: 返回 null(表示不再触发,UI 应清除提醒)。
     * - [recurrence] = [TodoRecurrence.DAILY]: 下一天的 [timeOfDay]。
     * - [recurrence] = [TodoRecurrence.WEEKDAYS]: 下一个工作日的 [timeOfDay]。
     * - [recurrence] = [TodoRecurrence.WEEKLY]: 与 [lastTriggerAt] 同 day-of-week 的下一周同一时间。
     * - [recurrence] = [TodoRecurrence.CUSTOM]: [recurrenceDays] 中下一个匹配 day-of-week 的 [timeOfDay]。
     *
     * @param lastTriggerAt 上次触发的时间戳(用于 WEEKLY / CUSTOM 的 day-of-week 推断);
     *                      缺省用 [nextTriggerAt] 或 now。
     * @return 下一次触发时间戳(毫秒);返回 null 表示「不再触发」,调用方应清掉 enabled/nextTriggerAt。
     */
    fun computeNextTriggerAfter(lastTriggerAt: Long = this.nextTriggerAt ?: System.currentTimeMillis()): Long? {
        val tod = this.timeOfDay
        if (tod == null) {
            // 没有 timeOfDay 没法算下一次(防御性兜底)
            return null
        }
        return when (recurrence) {
            TodoRecurrence.NONE -> null
            TodoRecurrence.DAILY -> tod.nextOccurrence(lastTriggerAt, emptySet())
            TodoRecurrence.WEEKDAYS -> tod.nextOccurrence(lastTriggerAt, (1..5).toSet())
            TodoRecurrence.WEEKLY -> {
                val dow = TodoTimeOfDay.toCalendarDayOfWeek(
                    Calendar.getInstance().apply { timeInMillis = lastTriggerAt }
                )
                tod.nextOccurrence(lastTriggerAt, setOf(dow))
            }
            TodoRecurrence.CUSTOM -> {
                if (recurrenceDays.isEmpty()) null else tod.nextOccurrence(lastTriggerAt, recurrenceDays)
            }
        }
    }

    /**
     * 校验 [recurrence] 与 [recurrenceDays] / [timeOfDay] 的一致性,用于 UI 提交前的最后兜底。
     * 返回 null 表示合法;返回错误信息表示校验失败(UI 应当 toast 提示用户)。
     *
     * 规则:
     * - [recurrence] = [TodoRecurrence.CUSTOM] 时,[recurrenceDays] 必须非空,否则报错。
     * - 任何非 NONE 循环都要求 [timeOfDay] 非 null(否则没法"每天 X 点"计算)。
     */
    fun validate(): String? {
        if (recurrence == TodoRecurrence.CUSTOM && recurrenceDays.isEmpty()) {
            return "自定义循环必须至少选择一天。"
        }
        if (recurrence != TodoRecurrence.NONE && timeOfDay == null) {
            return "循环提醒需要设置时间(几点几分)。"
        }
        return null
    }

    /**
     * 复制一份新的 [TodoReminder],字段可独立覆盖;未覆盖的字段沿用当前值。
     * 用于「更新某一项时不动其它项」的场景(如只改 enabled)。
     */
    fun copy(
        enabled: Boolean = this.enabled,
        nextTriggerAt: Long? = this.nextTriggerAt,
        recurrence: TodoRecurrence = this.recurrence,
        timeOfDay: TodoTimeOfDay? = this.timeOfDay,
        recurrenceDays: MutableSet<Int> = this.recurrenceDays,
    ): TodoReminder = TodoReminder(
        enabled = enabled,
        nextTriggerAt = nextTriggerAt,
        recurrence = recurrence,
        timeOfDay = timeOfDay,
        recurrenceDays = recurrenceDays.toMutableSet(),
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TodoReminder) return false
        return enabled == other.enabled &&
            nextTriggerAt == other.nextTriggerAt &&
            recurrence == other.recurrence &&
            timeOfDay == other.timeOfDay &&
            recurrenceDays == other.recurrenceDays
    }

    override fun hashCode(): Int {
        var result = enabled.hashCode()
        result = 31 * result + (nextTriggerAt?.hashCode() ?: 0)
        result = 31 * result + recurrence.hashCode()
        result = 31 * result + (timeOfDay?.hashCode() ?: 0)
        result = 31 * result + recurrenceDays.hashCode()
        return result
    }

    override fun toString(): String =
        "TodoReminder(enabled=$enabled, nextTriggerAt=$nextTriggerAt, " +
            "recurrence=$recurrence, timeOfDay=${timeOfDay?.format()}, days=$recurrenceDays)"

    companion object {
        /**
         * 从给定的 [triggerAt] 时间戳里抽取出 [TodoTimeOfDay] + day-of-week,作为新建草稿的默认值。
         * 让 UI 一打开编辑表单就有合理初值(用户改时间时不需要从 0 点开始选)。
         */
        fun deriveDefaultsFrom(triggerAt: Long): Pair<TodoTimeOfDay, Int> {
            val cal = Calendar.getInstance().apply { timeInMillis = triggerAt }
            val tod = TodoTimeOfDay(
                hour = cal.get(Calendar.HOUR_OF_DAY),
                minute = cal.get(Calendar.MINUTE),
            )
            val dow = TodoTimeOfDay.toCalendarDayOfWeek(cal)
            return tod to dow
        }

        /**
         * 空草稿,所有字段留空(null/空集合),供新建流程使用。
         */
        fun blank(): TodoReminder = TodoReminder(
            enabled = true,
            nextTriggerAt = null,
            recurrence = TodoRecurrence.NONE,
            timeOfDay = null,
            recurrenceDays = mutableSetOf(),
        )
    }
}
