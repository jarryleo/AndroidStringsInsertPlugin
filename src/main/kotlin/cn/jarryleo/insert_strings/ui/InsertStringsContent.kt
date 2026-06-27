package cn.jarryleo.insert_strings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.jarryleo.insert_strings.ai.AiProvider
import cn.jarryleo.insert_strings.ai.AiProtocol
import cn.jarryleo.insert_strings.ai.AiRole
import cn.jarryleo.insert_strings.ai.ChatMessage
import cn.jarryleo.insert_strings.phrases.QuickPhrase

@Composable
internal fun InsertStringsContent(
    stringName: String,
    rows: List<StringRow>,
    keys: List<String>,
    selectedKeyIndex: Int,
    onStringNameChange: (String) -> Unit,
    onTextChange: (Int, String) -> Unit,
    onClear: (Int) -> Unit,
    onAi: (Int) -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onInsert: () -> Unit,
    onSelectKey: (Int) -> Unit,
    toastMessage: String,
    showSettings: Boolean,
    showChat: Boolean,
    settingsTab: SettingsTab,
    aiProviders: List<AiProvider>,
    currentAiProviderId: String?,
    editingAiProvider: AiProvider?,
    editingIsNew: Boolean,
    modelOptions: List<String>,
    modelFetchStatus: String,
    chatMessages: List<ChatMessage>,
    chatInput: String,
    chatSending: Boolean,
    onSettingsTabChange: (SettingsTab) -> Unit,
    onOpenSettings: () -> Unit,
    onCloseSettings: () -> Unit,
    onOpenChat: () -> Unit,
    onCloseChat: () -> Unit,
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
    phrases: List<QuickPhrase>,
    editingPhrase: QuickPhrase?,
    onAddPhrase: () -> Unit,
    onEditPhrase: (QuickPhrase) -> Unit,
    onDeletePhrase: (QuickPhrase) -> Boolean,
    onMovePhrase: (fromIndex: Int, toIndex: Int) -> Unit,
    onSavePhraseEdit: (title: String, text: String, color: String?) -> Boolean,
    onCancelPhraseEdit: () -> Unit,
    onResetDefaultPhrases: () -> Unit,
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
    onChatInputChange: (String) -> Unit,
    onSendChat: () -> Unit,
    onStopChat: () -> Unit,
    onQuickSend: (String) -> Unit,
    onNewChat: () -> Unit,
    onOptionClick: (Int, Int, String) -> Unit,
    onOpenContext: () -> Unit,
    onCloseContext: () -> Unit,
    showContextPopup: Boolean,
    chatContextText: String,
    /**
     * 当前用户在 strings.xml 中选中的 key 列表,用于聊天面板顶部展示「已选择翻译(N)」。
     * 由父组件从 keyEntries 派生(随用户重新选 key 同步更新)。
     */
    selectedKeys: List<String> = emptyList(),
    /**
     * 入口携带的「引用内容」,聊天面板顶部以独立气泡展示。
     * 主面板聊天视图始终传 null(无编辑器上下文);AskAi/ExtractStrings 弹框会传入选区文本。
     */
    quoteContent: String? = null,
    onQuoteDismiss: (() -> Unit)? = null,
    /**
     * 引用面板「复制」按钮回调:由调用方写剪贴板 + 弹 toast。
     * 主面板 / 弹框都需要,统一透传。
     */
    onCopyQuote: ((String) -> Unit)? = null,
) {
    val colors = rememberIdeColors()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = colors.panel,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (showSettings) {
                    SettingsContent(
                        selectedTab = settingsTab,
                        onTabChange = onSettingsTabChange,
                        onClose = onCloseSettings,
                        aiProviders = aiProviders,
                        currentAiProviderId = currentAiProviderId,
                        editingAiProvider = editingAiProvider,
                        editingIsNew = editingIsNew,
                        modelOptions = modelOptions,
                        modelFetchStatus = modelFetchStatus,
                        onAddAiProvider = onAddAiProvider,
                        onEditAiProvider = onEditAiProvider,
                        onDeleteAiProvider = onDeleteAiProvider,
                        onUseAiProvider = onUseAiProvider,
                        onAiProviderNameChange = onAiProviderNameChange,
                        onAiProviderUrlChange = onAiProviderUrlChange,
                        onAiProviderApiKeyChange = onAiProviderApiKeyChange,
                        onAiProviderProtocolChange = onAiProviderProtocolChange,
                        onAiProviderModelChange = onAiProviderModelChange,
                        onFetchModels = onFetchModels,
                        onSaveAiProvider = onSaveAiProvider,
                        onCancelAiProviderEdit = onCancelAiProviderEdit,
                        sheetsDefaultSpreadsheetId = sheetsDefaultSpreadsheetId,
                        sheetsDefaultSheetName = sheetsDefaultSheetName,
                        sheetsConnectionStatus = sheetsConnectionStatus,
                        sheetsAvailableSheetNames = sheetsAvailableSheetNames,
                        sheetsListStatus = sheetsListStatus,
                        onSheetsDefaultSpreadsheetIdChange = onSheetsDefaultSpreadsheetIdChange,
                        onSheetsDefaultSheetNameChange = onSheetsDefaultSheetNameChange,
                        onTestSheetsConnection = onTestSheetsConnection,
                        onSaveSheetsSettings = onSaveSheetsSettings,
                        onRefreshSheetsList = onRefreshSheetsList,
                        phrases = phrases,
                        editingPhrase = editingPhrase,
                        onAddPhrase = onAddPhrase,
                        onEditPhrase = onEditPhrase,
                        onDeletePhrase = onDeletePhrase,
                        onMovePhrase = onMovePhrase,
                        onSavePhraseEdit = onSavePhraseEdit,
                        onCancelPhraseEdit = onCancelPhraseEdit,
                        onResetDefaultPhrases = onResetDefaultPhrases,
                        roles = roles,
                        editingRole = editingRole,
                        onAddRole = onAddRole,
                        onEditRole = onEditRole,
                        onDeleteRole = onDeleteRole,
                        onSetRoleEnabled = onSetRoleEnabled,
                        onDraftRoleTitleChange = onDraftRoleTitleChange,
                        onDraftRolePromptChange = onDraftRolePromptChange,
                        onSaveRoleEdit = onSaveRoleEdit,
                        onCancelRoleEdit = onCancelRoleEdit,
                        modifier = Modifier.fillMaxSize(),
                        colors = colors,
                    )
                } else if (showChat) {
                    AiChatContent(
                        chatMessages = chatMessages,
                        chatInput = chatInput,
                        chatSending = chatSending,
                        onClose = onCloseChat,
                        onNewChat = onNewChat,
                        onChatInputChange = onChatInputChange,
                        onSendChat = onSendChat,
                        onStopChat = onStopChat,
                        onQuickSend = onQuickSend,
                        onOptionClick = onOptionClick,
                        onOpenContext = onOpenContext,
                        onCloseContext = onCloseContext,
                        showContextPopup = showContextPopup,
                        chatContextText = chatContextText,
                        quickPhrases = phrases,
                        selectedKeys = selectedKeys,
                        quoteContent = quoteContent,
                        onQuoteDismiss = onQuoteDismiss,
                        onCopyQuote = onCopyQuote,
                        modifier = Modifier.fillMaxSize(),
                        colors = colors,
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        KeySelectorDropdown(
                            keys = keys,
                            selectedIndex = selectedKeyIndex,
                            onSelect = onSelectKey,
                            modifier = Modifier.weight(1f),
                            colors = colors,
                        )
                        CompactButton(
                            text = "Chat",
                            onClick = onOpenChat,
                            modifier = Modifier.width(52.dp),
                            colors = colors,
                        )
                        CompactButton(
                            text = "Settings",
                            onClick = onOpenSettings,
                            modifier = Modifier.width(72.dp),
                            colors = colors,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "<string name=",
                            color = colors.text,
                            style = compactTextStyle(colors.text),
                        )
                        CompactTextField(
                            value = stringName,
                            onValueChange = onStringNameChange,
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            colors = colors,
                        )
                    }

                    StringsTable(
                        rows = rows,
                        onTextChange = onTextChange,
                        onClear = onClear,
                        onAi = onAi,
                        modifier = Modifier.weight(1f),
                        colors = colors,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CompactButton(
                            text = "Copy",
                            onClick = onCopy,
                            modifier = Modifier.weight(1f),
                            colors = colors,
                        )
                        CompactButton(
                            text = "Paste",
                            onClick = onPaste,
                            modifier = Modifier.weight(1f),
                            colors = colors,
                        )
                        CompactButton(
                            text = "Insert",
                            onClick = onInsert,
                            modifier = Modifier.weight(1f),
                            colors = colors,
                            primary = true,
                        )
                    }
                }
            }

            if (toastMessage.isNotEmpty()) {
                ToastMessage(
                    text = toastMessage,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 42.dp),
                    colors = colors,
                )
            }
        }
    }
}
