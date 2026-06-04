package cn.jarryleo.insert_strings.ui

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import java.awt.Color as AwtColor
import javax.swing.UIManager

data class IdeColors(
    val panel: Color,
    val tableBackground: Color,
    val headerBackground: Color,
    val text: Color,
    val secondaryText: Color,
    val fieldBackground: Color,
    val fieldBorder: Color,
    val buttonBackground: Color,
    val buttonBorder: Color,
    val border: Color,
    val grid: Color,
    val accent: Color,
    val accentText: Color,
    val toastBackground: Color,
    val toastText: Color,
)

@Composable
fun rememberIdeColors(): IdeColors {
    return remember {
        IdeColors(
            panel = uiColor("Panel.background", fallback = AwtColor(0xF2F2F2)),
            tableBackground = uiColor("Table.background", "Panel.background", fallback = AwtColor.WHITE),
            headerBackground = uiColor("TableHeader.background", "Panel.background", fallback = AwtColor(0xF2F2F2)),
            text = uiColor("Label.foreground", fallback = AwtColor(0x1F1F1F)),
            secondaryText = uiColor(
                "Label.disabledForeground",
                "ContextHelp.foreground",
                fallback = AwtColor(0x6E6E6E)
            ),
            fieldBackground = uiColor("TextField.background", "EditorPane.background", fallback = AwtColor.WHITE),
            fieldBorder = uiColor("TextField.borderColor", "Component.borderColor", fallback = AwtColor(0xBDBDBD)),
            buttonBackground = uiColor("Button.background", fallback = AwtColor(0xF5F5F5)),
            buttonBorder = uiColor("Button.borderColor", "Component.borderColor", fallback = AwtColor(0xBDBDBD)),
            border = uiColor("Component.borderColor", "Table.gridColor", fallback = AwtColor(0xC8C8C8)),
            grid = uiColor("Table.gridColor", "Component.borderColor", fallback = AwtColor(0xE0E0E0)),
            accent = uiColor(
                "Component.focusColor",
                "Button.default.focusColor",
                "Actions.Blue",
                fallback = AwtColor(0x3574F0)
            ),
            accentText = uiColor("Button.default.foreground", fallback = AwtColor.WHITE),
            toastBackground = uiColor(
                "Notification.background",
                "GotItTooltip.background",
                fallback = AwtColor(0x323232)
            ),
            toastText = uiColor("Notification.foreground", "GotItTooltip.foreground", fallback = AwtColor.WHITE),
        )
    }
}

@Composable
fun compactTextStyle(color: Color): TextStyle {
    return MaterialTheme.typography.body2.merge(
        TextStyle(
            color = color,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
    )
}

private fun uiColor(vararg keys: String, fallback: AwtColor): Color {
    val color = keys.firstNotNullOfOrNull(UIManager::getColor) ?: fallback
    return Color(color.red, color.green, color.blue, color.alpha)
}
