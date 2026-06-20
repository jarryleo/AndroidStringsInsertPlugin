package cn.jarryleo.insert_strings.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
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
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .heightIn(min = 26.dp)
            .background(colors.fieldBackground, RoundedCornerShape(3.dp))
            .border(BorderStroke(1.dp, colors.fieldBorder), RoundedCornerShape(3.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        singleLine = singleLine,
        maxLines = maxLines,
        textStyle = compactTextStyle(colors.text),
        cursorBrush = SolidColor(colors.accent),
        visualTransformation = visualTransformation,
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
