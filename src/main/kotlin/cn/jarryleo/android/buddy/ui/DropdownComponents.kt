package cn.jarryleo.android.buddy.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.DropdownMenu
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import cn.jarryleo.android.buddy.ai.AiProtocol

@Composable
fun ProtocolDropdown(
    protocol: AiProtocol,
    onProtocolChange: (AiProtocol) -> Unit,
    modifier: Modifier = Modifier,
    colors: IdeColors,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        DropdownFieldShell(
            text = protocol.displayName,
            expanded = expanded,
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            colors = colors,
        )
        StyledDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            colors = colors,
            modifier = modifier,
        ) {
            AiProtocol.entries.forEach { item ->
                DropdownOption(
                    text = item.displayName,
                    onClick = {
                        expanded = false
                        onProtocolChange(item)
                    },
                    colors = colors,
                )
            }
        }
    }
}

@Composable
fun ModelField(
    value: String,
    options: List<String>,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    colors: IdeColors,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 26.dp)
                .background(colors.fieldBackground, RoundedCornerShape(3.dp))
                .border(BorderStroke(1.dp, colors.fieldBorder), RoundedCornerShape(3.dp))
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    singleLine = true,
                    maxLines = 1,
                    textStyle = compactTextStyle(colors.text),
                    cursorBrush = SolidColor(colors.accent),
                )
                Box(
                    modifier = Modifier
                        .width(28.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { expanded = options.isNotEmpty() }
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "▾",
                        color = colors.secondaryText,
                        style = compactTextStyle(colors.secondaryText),
                        maxLines = 1,
                    )
                }
            }
            StyledDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                colors = colors,
                modifier = Modifier.fillMaxWidth(),
                maxHeight = 220,
            ) {
                options.forEach { model ->
                    DropdownOption(
                        text = model,
                        onClick = {
                            expanded = false
                            onValueChange(model)
                        },
                        colors = colors,
                    )
                }
            }
        }
    }
}

@Composable
fun DropdownFieldShell(
    text: String,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colors: IdeColors,
) {
    Row(
        modifier = modifier
            .height(26.dp)
            .background(colors.fieldBackground, RoundedCornerShape(3.dp))
            .border(BorderStroke(1.dp, colors.fieldBorder), RoundedCornerShape(3.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(start = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            color = colors.text,
            style = compactTextStyle(colors.text),
            maxLines = 1,
        )
        Text(
            text = if (expanded) "▴" else "▾",
            color = colors.secondaryText,
            style = compactTextStyle(colors.secondaryText),
            maxLines = 1,
        )
    }
}

@Composable
fun StyledDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    colors: IdeColors,
    modifier: Modifier = Modifier,
    maxHeight: Int = 180,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colors = MaterialTheme.colors.copy(
            surface = colors.tableBackground,
            onSurface = colors.text,
            background = colors.tableBackground,
        )
    ) {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            modifier = modifier
                .heightIn(max = maxHeight.dp)
                .background(colors.tableBackground)
                .border(BorderStroke(1.dp, colors.border), RoundedCornerShape(3.dp)),
        ) {
            content()
        }
    }
}

@Composable
fun DropdownOption(
    text: String,
    onClick: () -> Unit,
    colors: IdeColors,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 28.dp)
            .background(colors.tableBackground)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = text,
            color = colors.text,
            style = compactTextStyle(colors.text),
            maxLines = 1,
        )
    }
}

/**
 * 多 key 选择下拉框。展示当前选中的 key 及其在列表中的序号，
 * 展开后列出全部选中的 key 供切换。仅当 keys 数量 > 1 时可展开。
 */
@Composable
fun KeySelectorDropdown(
    keys: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    colors: IdeColors,
) {
    var expanded by remember { mutableStateOf(false) }
    val currentKey = keys.getOrNull(selectedIndex) ?: ""
    val label = if (keys.size > 1) {
        "$currentKey  (${selectedIndex + 1}/${keys.size})"
    } else {
        currentKey.ifEmpty { "No key selected" }
    }
    val canExpand = keys.size > 1
    Box(modifier = modifier) {
        DropdownFieldShell(
            text = label,
            expanded = expanded,
            onClick = { if (canExpand) expanded = true },
            modifier = Modifier.fillMaxWidth(),
            colors = colors,
        )
        if (canExpand) {
            StyledDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                colors = colors,
                modifier = Modifier.fillMaxWidth(),
                maxHeight = 260,
            ) {
                keys.forEachIndexed { index, key ->
                    DropdownOption(
                        text = if (index == selectedIndex) "▶  $key" else "     $key",
                        onClick = {
                            expanded = false
                            onSelect(index)
                        },
                        colors = colors,
                    )
                }
            }
        }
    }
}
