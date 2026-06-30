package cn.jarryleo.insert_strings.ai

import java.util.Calendar

/**
 * 代办提醒的循环类型。
 *
 * 用途:决定 [TodoReminder.nextTriggerAt] 触发后如何计算下一次触发时间。
 *
 * 设计决定(2026.x):用户每次手动选时间(hour + minute + day-of-week),
 * 不从「首次设置时的时间」隐式推断。原因是隐式推断会让用户疑惑
 * 「我设的是 14:35,为什么是 14:30 触发?」;显式让 UI 强制选时间,
 * 触发时间点对用户完全透明。
 *
 * 字符串名 = enum 名,持久化与 AI 通信都直接用 name;反序列化用 [fromName] 容错。
 */
enum class TodoRecurrence {
    /**
     * 仅触发一次;触发后 [TodoReminder] 整体被清除(或 enabled=false + nextTriggerAt=null),
     * UI 上的闹钟标识随之消失。
     */
    NONE,

    /**
     * 每天固定时间触发(由 [TodoReminder.timeOfDayHour] / [timeOfDayMinute] 决定)。
     * 触发后滚动到「明天的同一时间」。
     */
    DAILY,

    /**
     * 自定义每周哪几天(由 [TodoReminder.recurrenceDays] 决定,1-7 表示周一到周日)
     * 固定时间触发。触发后滚动到「下一个匹配 day-of-week 的同一时间」。
     *
     * 包含 AI 端口语化的「工作日」(一/二/三/四/五)与「周末」(六/日),都通过
     * CUSTOM + 对应 [recurrenceDays] 表示,不再单设枚举值。
     */
    CUSTOM,

    // ===== 以下两个枚举值为历史遗留,仅用于反序列化老 XML 数据 =====
    // 触发后由 [TodoReminder.recurrence] setter 立即迁移到 [CUSTOM] + 对应 days,
    // UI、AI 提示词、JSON schema 都不再暴露它们。新代码请勿直接引用。

    /**
     * @deprecated 已合并到 [CUSTOM] + recurrenceDays=[1,2,3,4,5];保留仅为
     * 兼容 `insertStringsTodos.xml` 里的老数据。setter 会在反序列化时自动迁移。
     */
    @Deprecated("合并到 CUSTOM + recurrenceDays=[1,2,3,4,5],保留仅为反序列化兼容")
    WEEKDAYS,

    /**
     * @deprecated 已合并到 [CUSTOM] + recurrenceDays=[原 nextTriggerAt 对应 day-of-week];
     * 保留仅为兼容 `insertStringsTodos.xml` 里的老数据。setter 会在反序列化时自动迁移。
     */
    @Deprecated("合并到 CUSTOM + recurrenceDays=[原 nextTriggerAt day-of-week],保留仅为反序列化兼容")
    WEEKLY,
    ;

    /**
     * UI / 文档用的中文显示名。AI 通信仍用 enum name(英文),避免多语言歧义。
     *
     * WEEKDAYS / WEEKLY 不返回显示名(若被读到说明数据迁移未完成,UI 应走 CUSTOM 分支)。
     */
    val displayName: String
        @Suppress("DEPRECATION")
        get() = when (this) {
            NONE -> "一次性"
            DAILY -> "每日"
            CUSTOM -> "自定义"
            WEEKDAYS, WEEKLY -> "自定义"
        }

    companion object {
        /**
         * 从字符串解析(大小写不敏感),失败时回退到 [NONE]。
         * 用于反序列化 / AI 传入未知值时的兜底。
         */
        fun fromName(name: String?): TodoRecurrence {
            if (name.isNullOrBlank()) return NONE
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: NONE
        }
    }
}

/**
 * 一天的某个时间点(小时 + 分钟),用于循环提醒的「每天几点几分」语义。
 *
 * 抽成值对象,避免在 TodoReminder 上同时暴露两个 Int 字段导致 UI 拼装易错。
 *
 * - [hour] 0-23
 * - [minute] 0-59
 *
 * **XmlSerializer 兼容**:IntelliJ XmlSerializer 反射创建时按构造参数顺序匹配,
 * 必须给参数默认值,否则历史 XML 数据反序列化会抛
 * "No argument provided for a required parameter"(见 2026.x 修复)。
 * `init` 块的 require 校验在反序列化路径下也会跑,所以「0 ≤ hour ≤ 23」「0 ≤ minute ≤ 59」
 * 这两条仍然由类自身保证;非法值会让 XmlSerializer 失败回退到默认值,不会污染磁盘。
 */
data class TodoTimeOfDay(
    var hour: Int = 0,
    var minute: Int = 0,
) {
    init {
        require(hour in 0..23) { "hour must be in 0..23, got $hour" }
        require(minute in 0..59) { "minute must be in 0..59, got $minute" }
    }

    /**
     * 格式化为 `HH:mm` 字符串(24 小时制,不足两位补 0),用于 UI / 日志展示。
     */
    fun format(): String = "%02d:%02d".format(hour, minute)

    /**
     * 把当前时间点滚动到「下一个匹配 day-of-week 的同一时间」。
     * - [from]: 从哪个时间开始往后算(通常 = now 或上次触发时间)
     * - [allowedDaysOfWeek]: 1-7 (周一到周日) 的可触发日集合;
     *                        为空时退回到 from 后一天的同日(用于 DAILY 等任意 day 场景)。
     * - 返回:严格 > from 的最近一次触发时间戳。
     *
     * 规则:
     * - 「同日同时间」严格大于 from 才算(避免重复触发);
     * - 否则滚动到「下一个允许日 + 该时间」。
     * - 跨日期滚动时,小时/分钟保持 [hour]:[minute] 不变。
     */
    fun nextOccurrence(
        from: Long,
        allowedDaysOfWeek: Set<Int> = emptySet(),
    ): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = from
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // 最多向前滚 8 天(7 天 + 1 天兜底),一定可以命中。
        repeat(8) {
            if (cal.timeInMillis > from &&
                (allowedDaysOfWeek.isEmpty() || allowedDaysOfWeek.contains(toCalendarDayOfWeek(cal)))
            ) {
                return cal.timeInMillis
            }
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        // 兜底:按「明天这个时间」返回,虽然正常情况不会到这里。
        cal.timeInMillis = from
        cal.add(Calendar.DAY_OF_MONTH, 1)
        return cal.timeInMillis
    }

    companion object {
        /**
         * 从 Calendar 的星期几(1=周日,2=周一,...,7=周六)转换成业务口径
         * 「1=周一,...,7=周日」,与 [TodoReminder.recurrenceDays] 保持一致。
         */
        fun toCalendarDayOfWeek(cal: Calendar): Int {
            // Calendar.SUNDAY = 1, MONDAY = 2, ..., SATURDAY = 7
            return when (cal.get(Calendar.DAY_OF_WEEK)) {
                Calendar.SUNDAY -> 7
                else -> cal.get(Calendar.DAY_OF_WEEK) - 1
            }
        }
    }
}
