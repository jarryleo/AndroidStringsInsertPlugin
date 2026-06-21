package cn.jarryleo.insert_strings.ui

import androidx.compose.runtime.mutableStateListOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局调试日志收集器。
 * 用 Compose mutableStateListOf 存储，UI 可实时响应变化。
 */
object DebugLog {
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    data class Entry(
        val timestamp: String,
        val tag: String,
        val message: String,
    )

    val entries = mutableStateListOf<Entry>()

    fun log(tag: String, message: String) {
        val ts = timeFormat.format(Date())
        entries.add(Entry(ts, tag, message))
        // 防止内存泄漏：超过上限时丢弃最早的记录
        while (entries.size > MAX_ENTRIES) {
            entries.removeAt(0)
        }
    }

    fun clear() {
        entries.clear()
    }

    private const val MAX_ENTRIES = 2000
}
