package cn.jarryleo.android.buddy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DebugContent(
    logs: List<DebugLog.Entry>,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    colors: IdeColors,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Debug Log (${logs.size})",
                modifier = Modifier.weight(1f),
                color = colors.text,
                style = compactTextStyle(colors.text),
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            )
            CompactButton(
                text = "Clear",
                onClick = onClear,
                modifier = Modifier.width(60.dp),
                colors = colors,
                tone = ButtonTone.WARNING,
            )
        }

        val scrollState = rememberScrollState()
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(colors.fieldBackground, RoundedCornerShape(3.dp))
                .border(
                    androidx.compose.foundation.BorderStroke(1.dp, colors.fieldBorder),
                    RoundedCornerShape(3.dp)
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (logs.isEmpty()) {
                    Text(
                        text = "No debug logs yet.",
                        color = colors.secondaryText,
                        style = compactTextStyle(colors.secondaryText),
                    )
                } else {
                    logs.forEach { entry ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = entry.timestamp,
                                color = colors.secondaryText,
                                style = compactTextStyle(colors.secondaryText).copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                ),
                            )
                            Text(
                                text = "[${entry.tag}]",
                                color = colors.accent,
                                style = compactTextStyle(colors.accent).copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                ),
                            )
                            Text(
                                text = entry.message,
                                modifier = Modifier.weight(1f),
                                color = colors.text,
                                style = compactTextStyle(colors.text).copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}
