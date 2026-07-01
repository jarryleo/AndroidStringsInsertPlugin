package cn.jarryleo.android.buddy.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.jarryleo.android.buddy.ai.AiProvider
import cn.jarryleo.android.buddy.ai.AiProtocol
import cn.jarryleo.android.buddy.ai.AiRole
import cn.jarryleo.android.buddy.phrases.QuickPhrase

enum class SettingsTab {
    AI, ROLES, SHEETS, QUICK_PHRASES, DEBUG
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
    // ===== AI Roles =====
    roles: List<AiRole>,
    editingRole: AiRole?,
    onAddRole: () -> Unit,
    onEditRole: (AiRole) -> Unit,
    onDeleteRole: (AiRole) -> Unit,
    onSetRoleEnabled: (AiRole, Boolean) -> Unit,
    onDraftRoleTitleChange: (String) -> Unit,
    onDraftRolePromptChange: (String) -> Unit,
    onSaveRoleEdit: (title: String, prompt: String) -> Boolean,
    onCancelRoleEdit: () -> Unit,
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
            /*CompactButton(
                text = "Back",
                onClick = onClose,
                modifier = Modifier.width(56.dp),
                colors = colors,
            )*/
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(state = rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TabButton(
                text = "AI Settings",
                selected = selectedTab == SettingsTab.AI,
                onClick = { onTabChange(SettingsTab.AI) },
                colors = colors,
            )
            TabButton(
                text = "Role",
                selected = selectedTab == SettingsTab.ROLES,
                onClick = { onTabChange(SettingsTab.ROLES) },
                colors = colors,
            )
            TabButton(
                text = "Google Sheets",
                selected = selectedTab == SettingsTab.SHEETS,
                onClick = { onTabChange(SettingsTab.SHEETS) },
                colors = colors,
            )
            TabButton(
                text = "Quick Phrases",
                selected = selectedTab == SettingsTab.QUICK_PHRASES,
                onClick = { onTabChange(SettingsTab.QUICK_PHRASES) },
                colors = colors,
            )
            TabButton(
                text = "Debug",
                selected = selectedTab == SettingsTab.DEBUG,
                onClick = { onTabChange(SettingsTab.DEBUG) },
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
            SettingsTab.ROLES -> AiRolesContent(
                roles = roles,
                editingRole = editingRole,
                onAdd = onAddRole,
                onEdit = onEditRole,
                onDelete = onDeleteRole,
                onSetEnabled = onSetRoleEnabled,
                onDraftTitleChange = onDraftRoleTitleChange,
                onDraftPromptChange = onDraftRolePromptChange,
                onSaveEdit = onSaveRoleEdit,
                onCancelEdit = onCancelRoleEdit,
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
