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

@Composable
fun CompactButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colors: IdeColors,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    val background = if (primary) colors.accent else colors.buttonBackground
    val foreground = if (primary) colors.accentText else colors.text
    val border = if (primary) colors.accent else colors.buttonBorder
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .height(26.dp)
            .background(
                if (enabled) background else colors.fieldBackground,
                RoundedCornerShape(3.dp)
            )
            .border(
                BorderStroke(1.dp, if (enabled) border else colors.fieldBorder),
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
            color = if (enabled) foreground else colors.secondaryText,
            style = compactTextStyle(if (enabled) foreground else colors.secondaryText),
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
