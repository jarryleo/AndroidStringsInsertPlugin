package cn.jarryleo.android.buddy.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun CompactTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean,
    maxLines: Int = 1,
    colors: IdeColors,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    /**
     * 可选:按下 Enter(无 Alt/Shift)时触发的回调。
     * - 设置后:Enter 调用此回调(用于"回车发送"),Alt+Enter / Shift+Enter 走原行为(插入换行)。
     * - 不设置:保持原行为(Enter 由 singleLine / maxLines 决定是否换行)。
     *
     * 适用于聊天输入框等"回车即发"场景;其它纯文本输入框保持默认行为,不需要传此参数。
     */
    onSend: (() -> Unit)? = null,
    /**
     * 可选:按下 Ctrl+V / Cmd+V 时触发的回调(2026.x 多模态新增)。
     * - 设置后:粘贴组合键被本回调**截断**;由 caller 自己决定如何处理(读剪贴板 / 弹 toast / 还原默认粘贴)。
     *   这是「Ctrl+V 粘贴图片」的核心入口 —— 当剪贴板里是图片时由 caller 把图片加入 pendingImages;
     *   不是图片时 caller 应自行还原默认粘贴行为(或 toast 提示)。
     * - 不设置:保持原行为(由 BasicTextField 自己处理粘贴,贴入文字)。
     *
     * 用 isCtrlPressed / isMetaPressed 兼容 Windows / Linux(Ctrl)与 macOS(Cmd)双平台。
     */
    onPaste: (() -> Unit)? = null,
) {
    val keyModifier = Modifier.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        // Enter 发送
        if (onSend != null &&
            event.key == Key.Enter &&
            !event.isAltPressed &&
            !event.isShiftPressed
        ) {
            onSend.invoke()
            return@onPreviewKeyEvent true
        }
        // Ctrl+V / Cmd+V 拦截(粘贴图片用)
        if (onPaste != null &&
            event.key == Key.V &&
            (event.isCtrlPressed || event.isMetaPressed) &&
            !event.isAltPressed &&
            !event.isShiftPressed
        ) {
            onPaste.invoke()
            return@onPreviewKeyEvent true
        }
        false
    }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .then(keyModifier)
            .heightIn(min = 26.dp)
            .background(colors.fieldBackground, RoundedCornerShape(3.dp))
            .border(BorderStroke(1.dp, colors.fieldBorder), RoundedCornerShape(3.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        singleLine = singleLine,
        maxLines = maxLines,
        textStyle = compactTextStyle(colors.text),
        cursorBrush = SolidColor(colors.accent),
        visualTransformation = visualTransformation,
        // 软键盘场景:把 IME action 设为 Send,使虚拟键盘的回车键显示"发送"图标。
        // 桌面端由 [sendModifier] 处理硬件 Enter。
        keyboardOptions = if (onSend != null) KeyboardOptions(imeAction = ImeAction.Send) else KeyboardOptions.Default,
    )
}

enum class ButtonTone {
    NEUTRAL,
    POSITIVE,
    WARNING,
    NEGATIVE,
    INFO,
}

private fun toneColors(tone: ButtonTone): Triple<Color, Color, Color> {
    return when (tone) {
        ButtonTone.POSITIVE -> Triple(Color(0xFF16A34A), Color(0xFFDCFCE7), Color(0xFF15803D))
        ButtonTone.WARNING -> Triple(Color(0xFFEA580C), Color(0xFFFFEDD5), Color(0xFFC2410C))
        ButtonTone.NEGATIVE -> Triple(Color(0xFFDC2626), Color(0xFFFEE2E2), Color(0xFFB91C1C))
        ButtonTone.INFO -> Triple(Color(0xFF2563EB), Color(0xFFDBEAFE), Color(0xFF1D4ED8))
        ButtonTone.NEUTRAL -> Triple(Color.Unspecified, Color.Unspecified, Color.Unspecified)
    }
}

@Composable
fun CompactButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colors: IdeColors,
    primary: Boolean = false,
    enabled: Boolean = true,
    tone: ButtonTone = ButtonTone.NEUTRAL,
) {
    val (accent, tintBg, accentDark) = toneColors(tone)
    val background = when {
        !enabled -> colors.fieldBackground
        primary -> colors.accent
        tone != ButtonTone.NEUTRAL -> tintBg
        else -> colors.buttonBackground
    }
    val foreground = when {
        !enabled -> colors.secondaryText
        primary -> colors.accentText
        tone != ButtonTone.NEUTRAL -> accentDark
        else -> colors.text
    }
    val border = when {
        !enabled -> colors.fieldBorder
        primary -> colors.accent
        tone != ButtonTone.NEUTRAL -> accent
        else -> colors.buttonBorder
    }
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .height(26.dp)
            .background(background, RoundedCornerShape(3.dp))
            .border(
                BorderStroke(1.dp, border),
                RoundedCornerShape(3.dp)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = foreground,
            style = compactTextStyle(foreground),
            maxLines = 1,
        )
    }
}

@Composable
fun HeaderText(
    text: String,
    modifier: Modifier = Modifier,
    colors: IdeColors,
) {
    Text(
        text = text,
        modifier = modifier,
        color = colors.secondaryText,
        style = compactTextStyle(colors.secondaryText),
        fontWeight = FontWeight.Bold,
    )
}

@Composable
fun SettingsLabel(text: String, colors: IdeColors) {
    Text(
        text = text,
        color = colors.secondaryText,
        style = compactTextStyle(colors.secondaryText),
        fontWeight = FontWeight.Bold,
    )
}

@Composable
fun ToastMessage(
    text: String,
    modifier: Modifier = Modifier,
    colors: IdeColors,
) {
    Box(
        modifier = modifier
            .background(colors.toastBackground, RoundedCornerShape(4.dp))
            .border(BorderStroke(1.dp, colors.border), RoundedCornerShape(4.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = colors.toastText,
            style = compactTextStyle(colors.toastText),
            maxLines = 1,
        )
    }
}
