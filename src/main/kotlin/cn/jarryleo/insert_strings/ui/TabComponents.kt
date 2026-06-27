package cn.jarryleo.insert_strings.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 通用 tab 按钮(主页三 tab + 设置页子 tab 共用)。
 *
 * 视觉:26dp 高,圆角 3dp,选中时使用 accent 底色 + accentText 文字 + accent 描边;
 * 未选中时使用 buttonBackground 底色 + text 文字 + buttonBorder 描边。
 * 点击无波纹(indication = null),与现有按钮风格保持一致。
 *
 * 设计为宽度自适应(wrapContentWidth),方便一行排列多个 tab;
 * 调用方在 `Row` 里组合即可,无需固定宽度。
 */
@Composable
fun TabButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colors: IdeColors,
) {
    val background = if (selected) colors.accent else colors.buttonBackground
    val foreground = if (selected) colors.accentText else colors.text
    val border = if (selected) colors.accent else colors.buttonBorder
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .height(26.dp)
            .background(background, RoundedCornerShape(3.dp))
            .border(BorderStroke(1.dp, border), RoundedCornerShape(3.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 12.dp),
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
