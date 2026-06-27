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
 *                    其它循环类型(DAILY)忽略本字段。
 *                    业务上"工作日"= [1,2,3,4,5]、"周末"= [6,7],由 AI/UI 在写入前转换。
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
    recurrence: TodoRecurrence = TodoRecurrence.NONE,
    var timeOfDay: TodoTimeOfDay? = null,
    var recurrenceDays: MutableSet<Int> = mutableSetOf(),
) {
    /**
     * 循环类型。
     *
     * setter 同时承担「老数据迁移」职责:磁盘上的 `insertStringsTodos.xml` 可能存有
     * 已合并的 [TodoRecurrence.WEEKDAYS] / [TodoRecurrence.WEEKLY](2026.x 之前的产品逻辑),
     * XmlSerializer 反序列化时 valueOf 会直接还原成这两个值;setter 立即把它们升级到
     * [TodoRecurrence.CUSTOM] + 合理 [recurrenceDays],并通过 [migrateLegacyRecurrence]
     * 在 init 阶段兜底。后续 [TodoService.loadState] 会再次校验 + 持久化回写。
     */
    var recurrence: TodoRecurrence = recurrence
        set(value) {
            field = when (value) {
                TodoRecurrence.WEEKDAYS -> {
                    if (recurrenceDays.isEmpty()) recurrenceDays = (1..5).toMutableSet()
                    TodoRecurrence.CUSTOM
                }
                TodoRecurrence.WEEKLY -> {
                    if (recurrenceDays.isEmpty()) {
                        val at = nextTriggerAt ?: System.currentTimeMillis()
                        val dow = TodoTimeOfDay.toCalendarDayOfWeek(
                            java.util.Calendar.getInstance().apply { timeInMillis = at }
                        )
                        recurrenceDays = mutableSetOf(dow)
                    }
                    TodoRecurrence.CUSTOM
                }
                else -> value
            }
        }

    init {
        // 兜底:XmlSerializer 在反序列化过程中可能先 set recurrence(走迁移逻辑),
        // 也可能先 set recurrenceDays 再 set recurrence,导致 setter 里的 days 推断
        // 拿不到 nextTriggerAt;这里在 init 再跑一次迁移确保万无一失。
        migrateLegacyRecurrence()
    }

    /**
     * 把 [recurrence] 上的历史值(已合并但磁盘上仍存在的 WEEKDAYS / WEEKLY)
     * 升级到 [TodoRecurrence.CUSTOM] + 合理的 [recurrenceDays]。
     */
    @Suppress("DEPRECATION")
    private fun migrateLegacyRecurrence() {
        when (recurrence) {
            TodoRecurrence.WEEKDAYS -> {
                recurrence = TodoRecurrence.CUSTOM
                if (recurrenceDays.isEmpty()) {
                    recurrenceDays = (1..5).toMutableSet()
                }
            }
            TodoRecurrence.WEEKLY -> {
                recurrence = TodoRecurrence.CUSTOM
                if (recurrenceDays.isEmpty()) {
                    val at = nextTriggerAt ?: System.currentTimeMillis()
                    val dow = TodoTimeOfDay.toCalendarDayOfWeek(
                        java.util.Calendar.getInstance().apply { timeInMillis = at }
                    )
                    recurrenceDays = mutableSetOf(dow)
                }
            }
            else -> Unit
        }
    }

    /**
     * 工具方法:从当前 [nextTriggerAt] 推算下一次触发时间(用于触发后自动滚动)。
     *
     * - [recurrence] = [TodoRecurrence.NONE]: 返回 null(表示不再触发,UI 应清除提醒)。
     * - [recurrence] = [TodoRecurrence.DAILY]: 下一天的 [timeOfDay]。
     * - [recurrence] = [TodoRecurrence.CUSTOM]: [recurrenceDays] 中下一个匹配 day-of-week 的 [timeOfDay];
     *   「工作日」用 [1,2,3,4,5],「周末」用 [6,7]。
     *
     * @param lastTriggerAt 上次触发的时间戳(用于 CUSTOM 的 day-of-week 推断);
     *                      缺省用 [nextTriggerAt] 或 now。
     * @return 下一次触发时间戳(毫秒);返回 null 表示「不再触发」,调用方应清掉 enabled/nextTriggerAt。
     */
    @Suppress("DEPRECATION")
    fun computeNextTriggerAfter(lastTriggerAt: Long = this.nextTriggerAt ?: System.currentTimeMillis()): Long? {
        val tod = this.timeOfDay
        if (tod == null) {
            // 没有 timeOfDay 没法算下一次(防御性兜底)
            return null
        }
        return when (recurrence) {
            TodoRecurrence.NONE -> null
            TodoRecurrence.DAILY -> tod.nextOccurrence(lastTriggerAt, emptySet())
            TodoRecurrence.CUSTOM -> {
                if (recurrenceDays.isEmpty()) null else tod.nextOccurrence(lastTriggerAt, recurrenceDays)
            }
            // 兼容老数据:setter 已迁移到 CUSTOM,理论到这里时 recurrence 必为新枚举。
            // 兜底走 CUSTOM 分支,days 仍可能为空(刚反序列化未跑 init 迁移)。
            TodoRecurrence.WEEKDAYS -> tod.nextOccurrence(lastTriggerAt, (1..5).toSet())
            TodoRecurrence.WEEKLY -> {
                if (recurrenceDays.isEmpty()) {
                    val dow = TodoTimeOfDay.toCalendarDayOfWeek(
                        java.util.Calendar.getInstance().apply { timeInMillis = lastTriggerAt }
                    )
                    tod.nextOccurrence(lastTriggerAt, setOf(dow))
                } else {
                    tod.nextOccurrence(lastTriggerAt, recurrenceDays)
                }
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
