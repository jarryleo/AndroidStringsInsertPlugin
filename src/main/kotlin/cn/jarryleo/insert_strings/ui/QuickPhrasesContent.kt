package cn.jarryleo.insert_strings.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.jarryleo.insert_strings.phrases.QuickPhrase

/**
 * 快捷短语管理面板。
 *
 * 两种模式:
 * - **列表模式**(editing == null):每行展示一条短语(标题着色 + 文本预览),
 *   右侧 Edit / Delete;顶部「Add」按钮进入编辑模式新建。
 * - **行内编辑模式**(editing != null):目标行就地展开为编辑器,
 *   含 Title / Text / Color 三个字段 + Save / Cancel 按钮。
 *   Save 时校验通过才写库;Cancel 直接丢弃草稿。
 *
 * 配色(Color):支持 `#RRGGBB` / `#RGB` 形式 hex,也支持 8 个常用命名色(red/green/blue/orange/yellow/purple/pink/gray)。
 * 留空表示无染色,沿用 IDE 主题色。
 */
@Composable
fun QuickPhrasesContent(
    phrases: List<QuickPhrase>,
    editing: QuickPhrase?,
    onAdd: () -> Unit,
    onEdit: (QuickPhrase) -> Unit,
    onDelete: (QuickPhrase) -> Unit,
    onSaveEdit: (title: String, text: String, color: String?) -> Boolean,
    onCancelEdit: () -> Unit,
    modifier: Modifier = Modifier,
    colors: IdeColors,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 标题行
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Quick Phrases",
                modifier = Modifier.weight(1f),
                color = colors.secondaryText,
                style = compactTextStyle(colors.secondaryText),
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "共 ${phrases.size} 条",
                color = colors.secondaryText,
                style = compactTextStyle(colors.secondaryText),
            )
            CompactButton(
                text = "+ Add",
                onClick = onAdd,
                colors = colors,
                primary = true,
            )
        }

        // 说明
        Text(
            text = "在 AI 聊天面板以按钮形式显示,点击即把文本作为用户消息发送。" +
                "可给文字指定颜色便于快速分辨。",
            color = colors.secondaryText,
            style = compactTextStyle(colors.secondaryText),
        )

        if (phrases.isEmpty() && editing == null) {
            // 空态
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.tableBackground, RoundedCornerShape(3.dp))
                    .border(BorderStroke(1.dp, colors.fieldBorder), RoundedCornerShape(3.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "还没有快捷短语,点上方「+ Add」新建一条。",
                    color = colors.secondaryText,
                    style = compactTextStyle(colors.secondaryText),
                )
            }
        } else {
            // 列表(可滚动)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                phrases.forEach { phrase ->
                    if (editing?.id == phrase.id) {
                        PhraseEditRow(
                            initial = phrase,
                            onSave = onSaveEdit,
                            onCancel = onCancelEdit,
                            colors = colors,
                        )
                    } else {
                        PhraseListRow(
                            phrase = phrase,
                            onEdit = { onEdit(phrase) },
                            onDelete = { onDelete(phrase) },
                            colors = colors,
                        )
                    }
                }
                // 新增场景:editing.id 在 phrases 里不存在,渲染在列表最上方
                if (editing != null && phrases.none { it.id == editing.id }) {
                    PhraseEditRow(
                        initial = editing,
                        onSave = onSaveEdit,
                        onCancel = onCancelEdit,
                        colors = colors,
                    )
                }
            }
        }
    }
}

/**
 * 列表模式下的单行:左侧色块 + 标题(着色) + 文本预览,右侧 Edit / Delete。
 */
@Composable
private fun PhraseListRow(
    phrase: QuickPhrase,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    colors: IdeColors,
) {
    val titleColor = parseColorOrNull(phrase.color) ?: colors.text
    val textPreview = phrase.text.lineSequence().first().let {
        if (it.length > 60) it.take(60) + "…" else it
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.tableBackground, RoundedCornerShape(3.dp))
            .border(BorderStroke(1.dp, colors.fieldBorder), RoundedCornerShape(3.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 色块(无配色时画一个细描边的空圆,让对齐统一)
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(parseColorOrNull(phrase.color) ?: Color.Transparent, CircleShape)
                .border(
                    BorderStroke(
                        1.dp,
                        if (parseColorOrNull(phrase.color) != null) parseColorOrNull(phrase.color)!!
                        else colors.fieldBorder
                    ),
                    CircleShape,
                ),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = phrase.title,
                color = titleColor,
                style = compactTextStyle(titleColor),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Text(
                text = textPreview,
                color = colors.secondaryText,
                style = compactTextStyle(colors.secondaryText),
                maxLines = 1,
            )
        }
        CompactButton(
            text = "Edit",
            onClick = onEdit,
            colors = colors,
        )
        CompactButton(
            text = "Delete",
            onClick = onDelete,
            colors = colors,
            tone = ButtonTone.NEGATIVE,
        )
    }
}

/**
 * 行内编辑态:在同一行下面展开 Title / Text / Color 三个字段 + Save / Cancel。
 * 校验:title 和 text 都不能为空;color 留空或合法格式都接受。
 */
@Composable
private fun PhraseEditRow(
    initial: QuickPhrase,
    onSave: (title: String, text: String, color: String?) -> Boolean,
    onCancel: () -> Unit,
    colors: IdeColors,
) {
    var title by remember(initial.id) { mutableStateOf(initial.title) }
    var text by remember(initial.id) { mutableStateOf(initial.text) }
    var color by remember(initial.id) { mutableStateOf(initial.color.orEmpty()) }
    var error by remember(initial.id) { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.tableBackground, RoundedCornerShape(3.dp))
            .border(BorderStroke(1.dp, colors.accent), RoundedCornerShape(3.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SettingsLabel("Title", colors)
        CompactTextField(
            value = title,
            onValueChange = {
                title = it
                error = ""
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = colors,
        )

        SettingsLabel("Text", colors)
        CompactTextField(
            value = text,
            onValueChange = {
                text = it
                error = ""
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
            maxLines = 5,
            colors = colors,
        )

        SettingsLabel("Color (optional)", colors)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CompactTextField(
                value = color,
                onValueChange = {
                    color = it
                    error = ""
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                colors = colors,
            )
            // 色块预览
            val parsed = parseColorOrNull(color)
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(parsed ?: Color.Transparent, RoundedCornerShape(3.dp))
                    .border(
                        BorderStroke(1.dp, if (parsed != null) parsed else colors.fieldBorder),
                        RoundedCornerShape(3.dp),
                    ),
            )
        }
        Text(
            text = "支持 #RRGGBB / #RGB,或命名色 red/green/blue/orange/yellow/purple/pink/gray。留空表示无染色。",
            color = colors.secondaryText,
            style = compactTextStyle(colors.secondaryText),
        )

        if (error.isNotEmpty()) {
            Text(
                text = error,
                color = Color(0xFFB91C1C),
                style = compactTextStyle(Color(0xFFB91C1C)),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Spacer(Modifier.weight(1f))
            CompactButton(
                text = "Cancel",
                onClick = onCancel,
                colors = colors,
            )
            CompactButton(
                text = "Save",
                onClick = {
                    val colorToSave = color.trim().takeIf { it.isNotEmpty() }
                    if (colorToSave != null && parseColorOrNull(colorToSave) == null) {
                        error = "颜色格式不合法,请使用 #RRGGBB 或命名色。"
                        return@CompactButton
                    }
                    val ok = onSave(title, text, colorToSave)
                    if (!ok) {
                        error = "标题和文本都不能为空。"
                    }
                },
                colors = colors,
                primary = true,
            )
        }
    }
}

/**
 * 解析 hex / 命名色,失败返回 null。
 * 与 SheetsManager 的解析规则保持一致(简化版)。
 */
private fun parseColorOrNull(raw: String?): Color? {
    if (raw.isNullOrBlank()) return null
    val s = raw.trim()
    return runCatching {
        if (s.startsWith("#")) {
            val hex = s.substring(1)
            if (hex.length != 3 && hex.length != 6) return@runCatching null
            if (!hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return@runCatching null
            val full = if (hex.length == 3) hex.map { "$it$it" }.joinToString("") else hex
            val r = full.substring(0, 2).toInt(16)
            val g = full.substring(2, 4).toInt(16)
            val b = full.substring(4, 6).toInt(16)
            Color(r, g, b)
        } else {
            NAMED_COLORS[s.lowercase()]?.let { Color((it shr 16) and 0xFF, (it shr 8) and 0xFF, it and 0xFF) }
        }
    }.getOrNull()
}

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
