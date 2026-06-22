package cn.jarryleo.insert_strings.ui

import androidx.compose.ui.graphics.Color
import cn.jarryleo.insert_strings.phrases.QuickPhrase

/**
 * 解析 [QuickPhrase.color] 字符串为 Compose [Color]。
 * 与 [QuickPhrasesContent] 中的解析规则保持一致(hex 7 位 / 命名色),
 * 解析失败或为空返回 null,UI 层据此决定是否染色。
 */
internal fun QuickPhrase.toColorOrNull(): Color? {
    val raw = color?.trim().orEmpty()
    if (raw.isEmpty()) return null
    return runCatching {
        if (raw.startsWith("#")) {
            val hex = raw.substring(1)
            if (hex.length != 3 && hex.length != 6) return@runCatching null
            if (!hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return@runCatching null
            val full = if (hex.length == 3) hex.map { "$it$it" }.joinToString("") else hex
            val r = full.substring(0, 2).toInt(16)
            val g = full.substring(2, 4).toInt(16)
            val b = full.substring(4, 6).toInt(16)
            Color(r, g, b)
        } else {
            NAMED_COLORS[raw.lowercase()]?.let { Color((it shr 16) and 0xFF, (it shr 8) and 0xFF, it and 0xFF) }
        }
    }.getOrNull()
}

// 与 QuickPhrasesContent 内联版本保持同步(共享解析规则)。
private val NAMED_COLORS: Map<String, Int> = mapOf(
    "red" to 0xFF0000,
    "green" to 0x00FF00,
    "blue" to 0x0000FF,
    "yellow" to 0xFFFF00,
    "orange" to 0xFFA500.toInt(),
    "purple" to 0x800080,
    "pink" to 0xFFC0CB.toInt(),
    "gray" to 0x808080,
    "grey" to 0x808080,
)
