package cn.jarryleo.insert_strings.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SheetsSettingsContent(
    credentialsPath: String,
    tokensPath: String,
    defaultSpreadsheetId: String,
    defaultSheetName: String,
    connectionStatus: String,
    onClose: () -> Unit,
    onCredentialsPathChange: (String) -> Unit,
    onTokensPathChange: (String) -> Unit,
    onDefaultSpreadsheetIdChange: (String) -> Unit,
    onDefaultSheetNameChange: (String) -> Unit,
    onBrowseCredentials: () -> Unit,
    onBrowseTokensDir: () -> Unit,
    onTestConnection: () -> Unit,
    onSave: () -> Unit,
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
                text = "Google Sheets Settings",
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

        SettingsLabel("Credentials JSON Path", colors)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CompactTextField(
                value = credentialsPath,
                onValueChange = onCredentialsPathChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                colors = colors,
            )
            CompactButton(
                text = "Browse",
                onClick = onBrowseCredentials,
                modifier = Modifier.width(64.dp),
                colors = colors,
            )
        }
        Text(
            text = "OAuth 2.0 credentials JSON from Google Cloud Console",
            color = colors.secondaryText,
            style = compactTextStyle(colors.secondaryText),
        )

        SettingsLabel("Tokens Directory", colors)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CompactTextField(
                value = tokensPath,
                onValueChange = onTokensPathChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                colors = colors,
            )
            CompactButton(
                text = "Browse",
                onClick = onBrowseTokensDir,
                modifier = Modifier.width(64.dp),
                colors = colors,
            )
        }
        Text(
            text = "Directory to store authorized user tokens",
            color = colors.secondaryText,
            style = compactTextStyle(colors.secondaryText),
        )

        SettingsLabel("Default Spreadsheet ID", colors)
        CompactTextField(
            value = defaultSpreadsheetId,
            onValueChange = onDefaultSpreadsheetIdChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = colors,
        )

        SettingsLabel("Default Sheet Name", colors)
        CompactTextField(
            value = defaultSheetName,
            onValueChange = onDefaultSheetNameChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = colors,
        )

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
        }

        if (connectionStatus.isNotEmpty()) {
            Text(
                text = connectionStatus,
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
