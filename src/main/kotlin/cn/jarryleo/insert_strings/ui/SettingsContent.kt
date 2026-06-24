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
import cn.jarryleo.insert_strings.ai.AiProvider
import cn.jarryleo.insert_strings.ai.AiProtocol
import cn.jarryleo.insert_strings.phrases.QuickPhrase

enum class SettingsTab {
    AI, SHEETS, QUICK_PHRASES, DEBUG
}

@Composable
fun SettingsContent(
    selectedTab: SettingsTab,
    onTabChange: (SettingsTab) -> Unit,
    onClose: () -> Unit,
    // ===== AI 多 provider 模型 =====
    aiProviders: List<AiProvider>,
    currentAiProviderId: String?,
    editingAiProvider: AiProvider?,
    editingIsNew: Boolean,
    modelOptions: List<String>,
    modelFetchStatus: String,
    onAddAiProvider: () -> Unit,
    onEditAiProvider: (AiProvider) -> Unit,
    onDeleteAiProvider: (AiProvider) -> Unit,
    onUseAiProvider: (AiProvider) -> Unit,
    onAiProviderNameChange: (String) -> Unit,
    onAiProviderUrlChange: (String) -> Unit,
    onAiProviderApiKeyChange: (String) -> Unit,
    onAiProviderProtocolChange: (AiProtocol) -> Unit,
    onAiProviderModelChange: (String) -> Unit,
    onFetchModels: () -> Unit,
    onSaveAiProvider: () -> Boolean,
    onCancelAiProviderEdit: () -> Unit,
    // ===== Sheets =====
    sheetsDefaultSpreadsheetId: String,
    sheetsDefaultSheetName: String,
    sheetsConnectionStatus: String,
    sheetsAvailableSheetNames: List<String>,
    sheetsListStatus: String,
    onSheetsDefaultSpreadsheetIdChange: (String) -> Unit,
    onSheetsDefaultSheetNameChange: (String) -> Unit,
    onTestSheetsConnection: () -> Unit,
    onSaveSheetsSettings: () -> Unit,
    onRefreshSheetsList: () -> Unit,
    // ===== Quick Phrases =====
    phrases: List<QuickPhrase>,
    editingPhrase: QuickPhrase?,
    onAddPhrase: () -> Unit,
    onEditPhrase: (QuickPhrase) -> Unit,
    onDeletePhrase: (QuickPhrase) -> Boolean,
    onMovePhrase: (fromIndex: Int, toIndex: Int) -> Unit,
    onSavePhraseEdit: (title: String, text: String, color: String?) -> Boolean,
    onCancelPhraseEdit: () -> Unit,
    onResetDefaultPhrases: () -> Unit,
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
            SettingsTabButton(
                text = "Quick Phrases",
                selected = selectedTab == SettingsTab.QUICK_PHRASES,
                onClick = { onTabChange(SettingsTab.QUICK_PHRASES) },
                modifier = Modifier.weight(1f),
                colors = colors,
            )
            SettingsTabButton(
                text = "Debug",
                selected = selectedTab == SettingsTab.DEBUG,
                onClick = { onTabChange(SettingsTab.DEBUG) },
                modifier = Modifier.weight(1f),
                colors = colors,
            )
        }

        when (selectedTab) {
            SettingsTab.AI -> AiProvidersContent(
                aiProviders = aiProviders,
                currentAiProviderId = currentAiProviderId,
                editingAiProvider = editingAiProvider,
                editingIsNew = editingIsNew,
                modelOptions = modelOptions,
                modelFetchStatus = modelFetchStatus,
                onAddProvider = onAddAiProvider,
                onEditProvider = onEditAiProvider,
                onDeleteProvider = onDeleteAiProvider,
                onUseProvider = onUseAiProvider,
                onProviderNameChange = onAiProviderNameChange,
                onProviderUrlChange = onAiProviderUrlChange,
                onProviderApiKeyChange = onAiProviderApiKeyChange,
                onProviderProtocolChange = onAiProviderProtocolChange,
                onProviderModelChange = onAiProviderModelChange,
                onFetchModels = onFetchModels,
                onSaveEditing = { onSaveAiProvider() },
                onCancelEditing = onCancelAiProviderEdit,
                modifier = Modifier.fillMaxSize(),
                colors = colors,
            )
            SettingsTab.SHEETS -> SheetsSettingsContent(
                defaultSpreadsheetId = sheetsDefaultSpreadsheetId,
                defaultSheetName = sheetsDefaultSheetName,
                connectionStatus = sheetsConnectionStatus,
                availableSheetNames = sheetsAvailableSheetNames,
                listStatus = sheetsListStatus,
                onDefaultSpreadsheetIdChange = onSheetsDefaultSpreadsheetIdChange,
                onDefaultSheetNameChange = onSheetsDefaultSheetNameChange,
                onTestConnection = onTestSheetsConnection,
                onSave = onSaveSheetsSettings,
                onRefreshSheets = onRefreshSheetsList,
                modifier = Modifier.fillMaxSize(),
                colors = colors,
            )
            SettingsTab.QUICK_PHRASES -> QuickPhrasesContent(
                phrases = phrases,
                editing = editingPhrase,
                onAdd = onAddPhrase,
                onEdit = onEditPhrase,
                onDelete = onDeletePhrase,
                onMove = onMovePhrase,
                onSaveEdit = onSavePhraseEdit,
                onCancelEdit = onCancelPhraseEdit,
                onResetDefaults = onResetDefaultPhrases,
                modifier = Modifier.fillMaxSize(),
                colors = colors,
            )
            SettingsTab.DEBUG -> DebugContent(
                logs = DebugLog.entries.toList(),
                onClear = { DebugLog.clear() },
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
