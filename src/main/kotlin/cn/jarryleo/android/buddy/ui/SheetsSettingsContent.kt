package cn.jarryleo.android.buddy.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SheetsSettingsContent(
    defaultSpreadsheetId: String,
    defaultSheetName: String,
    connectionStatus: String,
    availableSheetNames: List<String>,
    listStatus: String,
    onDefaultSpreadsheetIdChange: (String) -> Unit,
    onDefaultSheetNameChange: (String) -> Unit,
    onTestConnection: () -> Unit,
    onSave: () -> Unit,
    onRefreshSheets: () -> Unit,
    modifier: Modifier = Modifier,
    colors: IdeColors,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "OAuth 2.0 credentials are loaded automatically from the plugin resources.",
            color = colors.secondaryText,
            style = compactTextStyle(colors.secondaryText),
        )

        SettingsLabel("Default Spreadsheet URL / ID", colors)
        CompactTextField(
            value = defaultSpreadsheetId,
            onValueChange = { onDefaultSpreadsheetIdChange(extractSpreadsheetId(it)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = colors,
        )
        Text(
            text = "Paste a Google Sheets URL or enter the Spreadsheet ID directly",
            color = colors.secondaryText,
            style = compactTextStyle(colors.secondaryText),
        )

        SettingsLabel("Default Sheet Name", colors)
        if (availableSheetNames.isNotEmpty()) {
            ModelField(
                value = defaultSheetName,
                options = availableSheetNames,
                onValueChange = onDefaultSheetNameChange,
                modifier = Modifier.fillMaxWidth(),
                colors = colors,
            )
        } else {
            CompactTextField(
                value = defaultSheetName,
                onValueChange = onDefaultSheetNameChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = colors,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CompactButton(
                text = "Test Connection",
                onClick = onTestConnection,
                modifier = Modifier.weight(1f),
                colors = colors,
            )
            CompactButton(
                text = "Refresh Sheets",
                onClick = onRefreshSheets,
                modifier = Modifier.weight(1f),
                colors = colors,
            )
        }

        if (connectionStatus.isNotEmpty()) {
            Text(
                text = connectionStatus,
                color = colors.secondaryText,
                style = compactTextStyle(colors.secondaryText),
            )
        }

        if (listStatus.isNotEmpty()) {
            Text(
                text = listStatus,
                color = colors.secondaryText,
                style = compactTextStyle(colors.secondaryText),
            )
        }

        if (availableSheetNames.isNotEmpty()) {
            Text(
                text = "可用工作表: ${availableSheetNames.joinToString(", ")}",
                color = colors.secondaryText,
                style = compactTextStyle(colors.secondaryText),
            )
        }

        Spacer(Modifier.weight(1f))

        CompactButton(
            text = "Save",
            onClick = onSave,
            modifier = Modifier.fillMaxWidth(),
            colors = colors,
            primary = true,
        )
    }
}

private fun extractSpreadsheetId(input: String): String {
    val trimmed = input.trim()
    val matchResult = Regex("/spreadsheets/d/([a-zA-Z0-9_-]+)").find(trimmed)
    return matchResult?.groupValues?.get(1) ?: trimmed
}
