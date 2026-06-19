package cn.jarryleo.insert_strings.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.jarryleo.insert_strings.ai.AiProtocol

enum class SettingsTab {
    AI, SHEETS
}

@Composable
fun SettingsContent(
    selectedTab: SettingsTab,
    onTabChange: (SettingsTab) -> Unit,
    onClose: () -> Unit,
    aiUrl: String,
    aiApiKey: String,
    aiProtocol: AiProtocol,
    aiModel: String,
    modelOptions: List<String>,
    modelFetchStatus: String,
    onAiUrlChange: (String) -> Unit,
    onAiApiKeyChange: (String) -> Unit,
    onAiProtocolChange: (AiProtocol) -> Unit,
    onAiModelChange: (String) -> Unit,
    onFetchModels: () -> Unit,
    onSaveAiSettings: () -> Unit,
    sheetsCredentialsPath: String,
    sheetsTokensPath: String,
    sheetsDefaultSpreadsheetId: String,
    sheetsDefaultSheetName: String,
    sheetsConnectionStatus: String,
    onSheetsCredentialsPathChange: (String) -> Unit,
    onSheetsTokensPathChange: (String) -> Unit,
    onSheetsDefaultSpreadsheetIdChange: (String) -> Unit,
    onSheetsDefaultSheetNameChange: (String) -> Unit,
    onBrowseSheetsCredentials: () -> Unit,
    onBrowseSheetsTokensDir: () -> Unit,
    onTestSheetsConnection: () -> Unit,
    onSaveSheetsSettings: () -> Unit,
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
                text = "Settings",
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
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SettingsTabButton(
                text = "AI",
                selected = selectedTab == SettingsTab.AI,
                onClick = { onTabChange(SettingsTab.AI) },
                modifier = Modifier.weight(1f),
                colors = colors,
            )
            SettingsTabButton(
                text = "Google Sheets",
                selected = selectedTab == SettingsTab.SHEETS,
                onClick = { onTabChange(SettingsTab.SHEETS) },
                modifier = Modifier.weight(1f),
                colors = colors,
            )
        }

        when (selectedTab) {
            SettingsTab.AI -> AiSettingsContent(
                aiUrl = aiUrl,
                aiApiKey = aiApiKey,
                aiProtocol = aiProtocol,
                aiModel = aiModel,
                modelOptions = modelOptions,
                modelFetchStatus = modelFetchStatus,
                onAiUrlChange = onAiUrlChange,
                onAiApiKeyChange = onAiApiKeyChange,
                onAiProtocolChange = onAiProtocolChange,
                onAiModelChange = onAiModelChange,
                onFetchModels = onFetchModels,
                onSave = onSaveAiSettings,
                modifier = Modifier.fillMaxSize(),
                colors = colors,
            )
            SettingsTab.SHEETS -> SheetsSettingsContent(
                credentialsPath = sheetsCredentialsPath,
                tokensPath = sheetsTokensPath,
                defaultSpreadsheetId = sheetsDefaultSpreadsheetId,
                defaultSheetName = sheetsDefaultSheetName,
                connectionStatus = sheetsConnectionStatus,
                onCredentialsPathChange = onSheetsCredentialsPathChange,
                onTokensPathChange = onSheetsTokensPathChange,
                onDefaultSpreadsheetIdChange = onSheetsDefaultSpreadsheetIdChange,
                onDefaultSheetNameChange = onSheetsDefaultSheetNameChange,
                onBrowseCredentials = onBrowseSheetsCredentials,
                onBrowseTokensDir = onBrowseSheetsTokensDir,
                onTestConnection = onTestSheetsConnection,
                onSave = onSaveSheetsSettings,
                modifier = Modifier.fillMaxSize(),
                colors = colors,
            )
        }
    }
}

@Composable
private fun SettingsTabButton(
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
