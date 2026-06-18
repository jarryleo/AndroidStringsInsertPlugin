package cn.jarryleo.insert_strings.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun McpSettingsContent(
    enabled: Boolean,
    command: String,
    arguments: String,
    workingDir: String,
    spreadsheetId: String,
    sheetName: String,
    timeoutSeconds: String,
    connectionStatus: String,
    onClose: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onCommandChange: (String) -> Unit,
    onArgumentsChange: (String) -> Unit,
    onWorkingDirChange: (String) -> Unit,
    onSpreadsheetIdChange: (String) -> Unit,
    onSheetNameChange: (String) -> Unit,
    onTimeoutSecondsChange: (String) -> Unit,
    onTestConnection: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    colors: IdeColors,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Google Sheets MCP",
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Enable MCP",
                modifier = Modifier.weight(1f),
                color = colors.text,
                style = compactTextStyle(colors.text),
            )
            CompactButton(
                text = if (enabled) "ON" else "OFF",
                onClick = { onEnabledChange(!enabled) },
                modifier = Modifier.width(48.dp),
                colors = colors,
                primary = enabled,
            )
        }

        SettingsLabel("MCP Server Command", colors)
        CompactTextField(
            value = command,
            onValueChange = onCommandChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = colors,
        )
        Text(
            text = "Example: npx or full path to the MCP server executable",
            color = colors.secondaryText,
            style = compactTextStyle(colors.secondaryText),
        )

        SettingsLabel("Arguments", colors)
        CompactTextField(
            value = arguments,
            onValueChange = onArgumentsChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
            maxLines = 4,
            colors = colors,
        )
        Text(
            text = "Space-separated arguments (e.g. -y @modelcontextprotocol/server-google-sheets)",
            color = colors.secondaryText,
            style = compactTextStyle(colors.secondaryText),
        )

        SettingsLabel("Working Directory (optional)", colors)
        CompactTextField(
            value = workingDir,
            onValueChange = onWorkingDirChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = colors,
        )

        SettingsLabel("Spreadsheet ID", colors)
        CompactTextField(
            value = spreadsheetId,
            onValueChange = onSpreadsheetIdChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = colors,
        )

        SettingsLabel("Sheet Name", colors)
        CompactTextField(
            value = sheetName,
            onValueChange = onSheetNameChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = colors,
        )

        SettingsLabel("Timeout (seconds)", colors)
        CompactTextField(
            value = timeoutSeconds,
            onValueChange = onTimeoutSecondsChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = colors,
        )

        if (connectionStatus.isNotEmpty()) {
            Text(
                text = connectionStatus,
                color = colors.secondaryText,
                style = compactTextStyle(colors.secondaryText),
            )
        }

        Spacer(Modifier.height(8.dp))

        CompactButton(
            text = "Test Connection",
            onClick = onTestConnection,
            modifier = Modifier.fillMaxWidth(),
            colors = colors,
        )

        Spacer(Modifier.height(8.dp))

        CompactButton(
            text = "Save",
            onClick = onSave,
            modifier = Modifier.fillMaxWidth(),
            colors = colors,
            primary = true,
        )
    }
}
