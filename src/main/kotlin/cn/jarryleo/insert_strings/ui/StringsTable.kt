package cn.jarryleo.insert_strings.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Divider
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class StringRow(
    val language: String,
    val text: String,
)

@Composable
fun StringsTable(
    rows: List<StringRow>,
    onTextChange: (Int, String) -> Unit,
    onClear: (Int) -> Unit,
    onAi: (Int) -> Unit,
    modifier: Modifier = Modifier,
    colors: IdeColors,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(BorderStroke(1.dp, colors.border), RoundedCornerShape(4.dp))
            .background(colors.tableBackground, RoundedCornerShape(4.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.headerBackground)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HeaderText("language", modifier = Modifier.width(96.dp), colors = colors)
            HeaderText("text", modifier = Modifier.weight(1f), colors = colors)
            Spacer(Modifier.width(104.dp))
        }

        Divider(color = colors.border)

        if (rows.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No strings selected",
                    color = colors.secondaryText,
                    style = compactTextStyle(colors.secondaryText),
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(rows, key = { index, _ -> index }) { index, row ->
                    StringTableRow(
                        row = row,
                        onTextChange = { onTextChange(index, it) },
                        onClear = { onClear(index) },
                        onAi = { onAi(index) },
                        colors = colors,
                    )
                    if (index != rows.lastIndex) {
                        Divider(color = colors.grid)
                    }
                }
            }
        }
    }
}

@Composable
private fun StringTableRow(
    row: StringRow,
    onTextChange: (String) -> Unit,
    onClear: () -> Unit,
    onAi: () -> Unit,
    colors: IdeColors,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = row.language,
            modifier = Modifier.width(90.dp),
            color = colors.text,
            style = compactTextStyle(colors.text),
        )
        CompactTextField(
            value = row.text,
            onValueChange = onTextChange,
            modifier = Modifier.weight(1f),
            singleLine = false,
            maxLines = 2,
            colors = colors,
        )
        CompactButton(
            text = "Clear",
            onClick = onClear,
            modifier = Modifier.width(54.dp),
            colors = colors,
        )
        CompactButton(
            text = "AI",
            onClick = onAi,
            modifier = Modifier.width(44.dp),
            colors = colors,
        )
    }
}
