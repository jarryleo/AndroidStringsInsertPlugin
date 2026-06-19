package cn.jarryleo.insert_strings.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SheetsOperationsContent(
    spreadsheetId: String,
    sheetRange: String,
    searchKey: String,
    operationStatus: String,
    onClose: () -> Unit,
    onSpreadsheetIdChange: (String) -> Unit,
    onSheetRangeChange: (String) -> Unit,
    onSearchKeyChange: (String) -> Unit,
    onRead: () -> Unit,
    onWrite: () -> Unit,
    onAppend: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    colors: IdeColors,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Google Sheets",
                modifier = Modifier.weight(1f),
                color = colors.text,
                style = compactTextStyle(colors.text),
                fontWeight = FontWeight.Bold,
            )
            CompactButton(
                text = "Back",
                onClick = onClose,
                modifier = Modifier.width(56.dp),
                colors = colors,
            )
        }

        SettingsLabel("Spreadsheet ID", colors)
        CompactTextField(
            value = spreadsheetId,
            onValueChange = onSpreadsheetIdChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = colors,
        )

        SettingsLabel("Range (e.g. Sheet1!A1:Z100)", colors)
        CompactTextField(
            value = sheetRange,
            onValueChange = onSheetRangeChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = colors,
        )

        SettingsLabel("Search Key", colors)
        CompactTextField(
            value = searchKey,
            onValueChange = onSearchKeyChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = colors,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CompactButton(
                text = "Read",
                onClick = onRead,
                modifier = Modifier.weight(1f),
                colors = colors,
            )
            CompactButton(
                text = "Write",
                onClick = onWrite,
                modifier = Modifier.weight(1f),
                colors = colors,
            )
            CompactButton(
                text = "Append",
                onClick = onAppend,
                modifier = Modifier.weight(1f),
                colors = colors,
            )
            CompactButton(
                text = "Search",
                onClick = onSearch,
                modifier = Modifier.weight(1f),
                colors = colors,
            )
        }

        if (operationStatus.isNotEmpty()) {
            Text(
                text = operationStatus,
                color = colors.secondaryText,
                style = compactTextStyle(colors.secondaryText),
            )
        }

        Spacer(Modifier.weight(1f))
    }
}
