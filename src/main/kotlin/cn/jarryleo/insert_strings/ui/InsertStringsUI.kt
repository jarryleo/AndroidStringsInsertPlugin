package cn.jarryleo.insert_strings.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.unit.dp
import cn.jarryleo.insert_strings.InsertStringsManager
import cn.jarryleo.insert_strings.UiCallback
import cn.jarryleo.insert_strings.ai.AITranslator
import cn.jarryleo.insert_strings.ai.AiAction
import cn.jarryleo.insert_strings.ai.AiProtocol
import cn.jarryleo.insert_strings.ai.AiSettingsService
import cn.jarryleo.insert_strings.ai.ChatMessage
import cn.jarryleo.insert_strings.sheets.SheetsManager
import cn.jarryleo.insert_strings.sheets.SheetsSettingsService
import cn.jarryleo.insert_strings.xml.ContextManager
import cn.jarryleo.insert_strings.xml.ModuleInfo
import cn.jarryleo.insert_strings.xml.StringsInfo
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import org.jetbrains.annotations.NotNull
import org.jetbrains.annotations.Nullable
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.Timer

class InsertStringsUI(
    private val toolWindow: ToolWindow
) : UiCallback {
    private lateinit var project: Project
    private lateinit var insertStringsManager: InsertStringsManager

    companion object {
        private const val MAX_TOOL_ROUNDS = 3
    }

    private data class SheetsToolResult(
        val operation: String,
        val success: Boolean,
        val message: String,
        val data: List<List<String>>? = null
    )

    private var stringName by mutableStateOf("")
    private val rows = mutableStateListOf<StringRow>()
    private var showSettings by mutableStateOf(false)
    private var aiUrl by mutableStateOf("")
    private var aiApiKey by mutableStateOf("")
    private var aiProtocol by mutableStateOf(AiProtocol.OPENAI)
    private var aiModel by mutableStateOf("qwen-plus")
    private val modelOptions = mutableStateListOf<String>()
    private var modelFetchStatus by mutableStateOf("")
    private var toastMessage by mutableStateOf("")
    private var toastTimer: Timer? = null
    private var showChat by mutableStateOf(false)
    private val chatMessages = mutableStateListOf<ChatMessage>()
    private var chatInput by mutableStateOf("")
    private var chatSending by mutableStateOf(false)

    // Google Sheets state
    private var sheetsDefaultSpreadsheetId by mutableStateOf("")
    private var sheetsDefaultSheetName by mutableStateOf("Sheet1")
    private var sheetsConnectionStatus by mutableStateOf("")

    private var settingsTab by mutableStateOf(SettingsTab.AI)

    private val rootPanel = ComposePanel().apply {
        setContent {
            MaterialTheme {
                InsertStringsContent(
                    stringName = stringName,
                    rows = rows,
                    onStringNameChange = { stringName = it },
                    onTextChange = ::updateRowText,
                    onClear = { row -> updateRowText(row, "") },
                    onAi = ::translateRow,
                    onCopy = ::copy,
                    onPaste = ::paste,
                    onInsert = ::insert,
                    toastMessage = toastMessage,
                    showSettings = showSettings,
                    showChat = showChat,
                    aiUrl = aiUrl,
                    aiApiKey = aiApiKey,
                    aiProtocol = aiProtocol,
                    aiModel = aiModel,
                    modelOptions = modelOptions,
                    modelFetchStatus = modelFetchStatus,
                    chatMessages = chatMessages,
                    chatInput = chatInput,
                    chatSending = chatSending,
                    settingsTab = settingsTab,
                    onSettingsTabChange = { settingsTab = it },
                    onOpenSettings = { showSettings = true },
                    onCloseSettings = { showSettings = false },
                    onOpenChat = { showChat = true },
                    onCloseChat = { showChat = false },
                    onAiUrlChange = { aiUrl = it },
                    onAiApiKeyChange = { aiApiKey = it },
                    onAiProtocolChange = { aiProtocol = it },
                    onAiModelChange = { aiModel = it },
                    onFetchModels = ::fetchModels,
                    onSaveAiSettings = ::saveSettings,
                    sheetsDefaultSpreadsheetId = sheetsDefaultSpreadsheetId,
                    sheetsDefaultSheetName = sheetsDefaultSheetName,
                    sheetsConnectionStatus = sheetsConnectionStatus,
                    onSheetsDefaultSpreadsheetIdChange = { sheetsDefaultSpreadsheetId = it },
                    onSheetsDefaultSheetNameChange = { sheetsDefaultSheetName = it },
                    onTestSheetsConnection = ::testSheetsConnection,
                    onSaveSheetsSettings = ::saveSheetsSettings,
                    onChatInputChange = { chatInput = it },
                    onSendChat = ::sendChat,
                    onNewChat = ::newChat,
                )
            }
        }
    }

    fun createToolWindowContent(project: Project) {
        this.project = project
        insertStringsManager = InsertStringsManager.getInstance(project)
        val currentFile = FileEditorManager.getInstance(project).selectedEditor?.file
        ContextManager.updateCurrentModule(project, currentFile)
        loadSettings()
        loadSheetsSettings()
        insertStringsManager.setUiCallBack(this)
    }

    fun getRootPanel(): JComponent = rootPanel

    private fun copy() {
        insertStringsManager.copy()
        showToast("Copied")
    }

    private fun paste() {
        val selectedEditor = FileEditorManager.getInstance(project).selectedEditor
        if (selectedEditor == null) {
            Messages.showMessageDialog(
                "Please open a strings.xml first!",
                "Error",
                Messages.getInformationIcon()
            )
            return
        }

        if (insertStringsManager.paste(selectedEditor.file)) {
            showToast("Pasted")
        } else {
            showToast("Nothing to paste")
        }
    }

    private fun insert() {
        val languages = insertStringsManager.languages
        if (languages == null) {
            Messages.showMessageDialog(
                "Please open a strings.xml first!",
                "Error",
                Messages.getInformationIcon()
            )
            return
        }

        if (stringName.isEmpty()) {
            Messages.showMessageDialog(
                "Name can't be empty!",
                "Error",
                Messages.getInformationIcon()
            )
            return
        }

        insertStringsManager.insert(
            project = project,
            stringName = stringName,
            stringsInfoList = rows.associate { it.language to it.text }
        )
        showToast("Inserted")
    }

    private fun updateRowText(rowIndex: Int, text: String) {
        if (rowIndex !in rows.indices) return
        rows[rowIndex] = rows[rowIndex].copy(text = text)
    }

    private fun translateRow(rowIndex: Int) {
        if (rowIndex !in rows.indices) return
        val sourceText = rows.firstOrNull { it.text.isNotEmpty() }?.text.orEmpty()
        val targetLanguage = rows[rowIndex].language.let {
            if (it.equals("values", ignoreCase = true)) "values-en" else it
        }
        updateRowText(rowIndex, "Translating...")
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = AITranslator.translate(targetLanguage, sourceText)
            SwingUtilities.invokeLater {
                updateRowText(rowIndex, result)
            }
        }
    }

    private fun loadSettings() {
        val settings = AiSettingsService.getInstance().state
        aiUrl = settings.url
        aiApiKey = settings.apiKey
        aiProtocol = AiProtocol.fromName(settings.protocol)
        aiModel = settings.model
    }

    private fun saveSettings() {
        AiSettingsService.getInstance().update(
            url = aiUrl,
            apiKey = aiApiKey,
            protocol = aiProtocol,
            model = aiModel,
        )
        modelFetchStatus = "Saved."
        showSettings = false
    }

    private fun loadSheetsSettings() {
        val settings = SheetsSettingsService.getInstance(project).state
        sheetsDefaultSpreadsheetId = settings.defaultSpreadsheetId
        sheetsDefaultSheetName = settings.defaultSheetName
    }

    private fun saveSheetsSettings() {
        SheetsSettingsService.getInstance(project).update(
            defaultSpreadsheetId = sheetsDefaultSpreadsheetId,
            defaultSheetName = sheetsDefaultSheetName,
        )
        sheetsConnectionStatus = "Saved."
    }

    private fun testSheetsConnection() {
        sheetsConnectionStatus = "Connecting..."
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = SheetsManager.testConnection(project, sheetsDefaultSpreadsheetId)
            SwingUtilities.invokeLater {
                result.fold(
                    onSuccess = { sheetsConnectionStatus = it },
                    onFailure = { sheetsConnectionStatus = it.message ?: "Connection failed." }
                )
            }
        }
    }

    private fun buildSheetRows(): List<List<String>> {
        val languages = rows.map { it.language }
        val header = listOf("key") + languages
        val dataRow = listOf(stringName) + rows.map { it.text }
        return listOf(header, dataRow)
    }

    private fun applySheetRowsToUi(sheetRows: List<List<String>>) {
        if (sheetRows.isEmpty()) return
        val header = sheetRows.firstOrNull() ?: return
        val keyIndex = header.indexOfFirst { it.equals("key", ignoreCase = true) }
        val dataRows = if (keyIndex != -1) sheetRows.drop(1) else sheetRows
        val languages = if (keyIndex != -1) {
            header.filterIndexed { index, _ -> index != keyIndex }
        } else {
            header.drop(1)
        }
        val keyColumn = if (keyIndex != -1) keyIndex else 0

        val firstRow = dataRows.firstOrNull() ?: return
        val newKey = firstRow.getOrNull(keyColumn) ?: ""
        if (newKey.isNotEmpty()) {
            stringName = newKey
        }

        val newRows = languages.mapIndexed { langIndex, language ->
            val originalIndex = if (keyIndex == -1) {
                langIndex + 1
            } else {
                if (langIndex < keyIndex) langIndex else langIndex + 1
            }
            val value = firstRow.getOrNull(originalIndex) ?: ""
            StringRow(language = language, text = value)
        }
        rows.clear()
        rows.addAll(newRows)
    }

    private fun applySheetRowToUi(row: List<String>) {
        applySheetRowsToUi(listOf(row))
    }

    private fun executeSheetsOperationSync(action: AiAction.SheetsOperation): SheetsToolResult {
        return when (action.operation) {
            AiAction.SheetsOperation.Operation.WRITE -> {
                val rowsToWrite = action.rows ?: buildSheetRows()
                val spreadsheetId = SheetsManager.resolveSpreadsheetId(project, action.spreadsheetId)
                val range = action.range ?: "${SheetsManager.defaultSheetName(project)}!A1:Z1000"
                if (spreadsheetId.isBlank()) {
                    return SheetsToolResult("写入表格", false, "Spreadsheet ID 为空，请先在设置中配置。")
                }
                val result = SheetsManager.writeRange(project, spreadsheetId, range, rowsToWrite)
                result.fold(
                    onSuccess = {
                        SwingUtilities.invokeLater { showToast("Wrote to Google Sheets.") }
                        SheetsToolResult("写入表格", true, "成功写入 ${rowsToWrite.size} 行数据到范围 $range")
                    },
                    onFailure = {
                        SheetsToolResult("写入表格", false, it.message ?: "Sheets write failed.")
                    }
                )
            }
            AiAction.SheetsOperation.Operation.READ -> {
                val spreadsheetId = SheetsManager.resolveSpreadsheetId(project, action.spreadsheetId)
                val range = action.range ?: "${SheetsManager.defaultSheetName(project)}!A1:Z1000"
                if (spreadsheetId.isBlank()) {
                    return SheetsToolResult("读取表格", false, "Spreadsheet ID 为空，请先在设置中配置。")
                }
                val result = if (action.key.isNullOrBlank()) {
                    SheetsManager.readRange(project, spreadsheetId, range)
                } else {
                    SheetsManager.searchRowByKey(project, spreadsheetId, range, action.key).map { listOf(it.second) }
                }
                result.fold(
                    onSuccess = { sheetRows ->
                        SwingUtilities.invokeLater {
                            applySheetRowsToUi(sheetRows)
                            showToast("Read from Google Sheets.")
                        }
                        val dataSummary = if (sheetRows.isEmpty()) "表格为空" else "读取到 ${sheetRows.size} 行数据"
                        SheetsToolResult("读取表格", true, dataSummary, sheetRows)
                    },
                    onFailure = {
                        SheetsToolResult("读取表格", false, it.message ?: "Sheets read failed.")
                    }
                )
            }
            AiAction.SheetsOperation.Operation.SEARCH -> {
                val key = action.key
                if (key.isNullOrBlank()) {
                    return SheetsToolResult("搜索表格", false, "Search key 为空。")
                }
                val spreadsheetId = SheetsManager.resolveSpreadsheetId(project, action.spreadsheetId)
                val range = action.range ?: "${SheetsManager.defaultSheetName(project)}!A1:Z1000"
                if (spreadsheetId.isBlank()) {
                    return SheetsToolResult("搜索表格", false, "Spreadsheet ID 为空，请先在设置中配置。")
                }
                val result = SheetsManager.searchRowByKey(project, spreadsheetId, range, key)
                result.fold(
                    onSuccess = { (_, row) ->
                        SwingUtilities.invokeLater {
                            applySheetRowToUi(row)
                            showToast("Found key '$key' in Google Sheets.")
                        }
                        SheetsToolResult("搜索表格", true, "找到 key '$key'", listOf(row))
                    },
                    onFailure = {
                        SheetsToolResult("搜索表格", false, it.message ?: "Sheets search failed.")
                    }
                )
            }
        }
    }

    private fun buildToolResultMessage(results: List<SheetsToolResult>): String {
        return buildString {
            appendLine("[工具执行结果]")
            results.forEachIndexed { index, result ->
                if (index > 0) appendLine()
                appendLine("操作${index + 1}:")
                appendLine("  类型: ${result.operation}")
                appendLine("  状态: ${if (result.success) "成功" else "失败"}")
                appendLine("  信息: ${result.message}")
                if (result.data != null && result.data.isNotEmpty()) {
                    appendLine("  数据:")
                    result.data.forEach { row ->
                        appendLine("    ${row.joinToString(" | ")}")
                    }
                }
            }
            appendLine("请根据以上结果回复用户。")
        }
    }

    private fun fetchModels() {
        modelFetchStatus = "Loading models..."
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = AITranslator.fetchModels(aiUrl, aiProtocol, aiApiKey)
            SwingUtilities.invokeLater {
                result.fold(
                    onSuccess = { models ->
                        modelOptions.clear()
                        modelOptions.addAll(models)
                        if (aiModel.isBlank()) {
                            aiModel = models.firstOrNull().orEmpty()
                        }
                        modelFetchStatus = "Loaded ${models.size} models."
                    },
                    onFailure = {
                        modelFetchStatus = it.message ?: "Failed to load models."
                    }
                )
            }
        }
    }

    private fun showToast(message: String) {
        toastTimer?.stop()
        toastMessage = message
        toastTimer = Timer(1800) {
            toastMessage = ""
        }.apply {
            isRepeats = false
            start()
        }
    }

    private fun sendChat() {
        val text = chatInput.trim()
        if (text.isEmpty() || chatSending) return
        chatMessages.add(ChatMessage(role = "user", content = text))
        chatInput = ""
        chatSending = true
        val context = buildChatContext()
        ApplicationManager.getApplication().executeOnPooledThread {
            val reply = AITranslator.chat(chatMessages.toList(), context)
            SwingUtilities.invokeLater {
                chatMessages.add(ChatMessage(role = "assistant", content = reply.reply))
                handleAiActions(reply.actions, 0)
            }
        }
    }

    private fun newChat() {
        chatMessages.clear()
        chatInput = ""
        chatSending = false
    }

    private fun buildChatContext(): String {
        val contextInfo = ContextManager.contextInfo ?: return ""
        val availableLanguages = insertStringsManager.languages?.takeIf { it.isNotEmpty() }
            ?: contextInfo.currentModule?.xmlFiles?.map { it.language }
            ?: emptyList()
        val currentTranslations = rows.filter { it.text.isNotEmpty() }.associate { it.language to it.text }
        val sheetsSettings = SheetsSettingsService.getInstance(project).state
        val sheetsConfigured = SheetsManager.isConfigured(project)

        val root = JsonObject().apply {
            addProperty("projectName", contextInfo.projectName)
            add("currentModule", contextInfo.currentModule?.let { moduleToJson(it) })
            add("modules", JsonArray().apply {
                contextInfo.modules.forEach { add(moduleToJson(it)) }
            })
            add("moduleWithMostLines", contextInfo.moduleWithMostLines?.let { moduleToJson(it) })
            add("availableLanguages", JsonArray().apply {
                availableLanguages.forEach { add(it) }
            })
            addProperty("currentKey", stringName)
            add("currentTranslations", JsonObject().apply {
                currentTranslations.forEach { (k, v) -> addProperty(k, v) }
            })
            add("googleSheets", JsonObject().apply {
                addProperty("configured", sheetsConfigured)
                addProperty("defaultSpreadsheetId", sheetsSettings.defaultSpreadsheetId)
                addProperty("defaultSheetName", sheetsSettings.defaultSheetName)
            })
        }
        return root.toString()
    }

    private fun moduleToJson(module: ModuleInfo): JsonObject {
        return JsonObject().apply {
            addProperty("moduleName", module.moduleName)
            addProperty("originalModuleName", module.originalModuleName)
            addProperty("modulePath", module.modulePath)
            addProperty("totalLines", module.totalLines)
            add("xmlFiles", JsonArray().apply {
                module.xmlFiles.forEach { file ->
                    add(JsonObject().apply {
                        addProperty("filePath", file.filePath)
                        addProperty("language", file.language)
                        addProperty("fileLines", file.fileLines)
                    })
                }
            })
        }
    }

    private fun handleAiActions(actions: List<AiAction>, depth: Int) {
        val insertActions = actions.filterIsInstance<AiAction.InsertStrings>()
        if (insertActions.isNotEmpty()) {
            executeInsertActions(insertActions)
        }
        val askActions = actions.filterIsInstance<AiAction.AskUser>()
        if (askActions.isNotEmpty()) {
            showToast(askActions.first().question)
        }

        val sheetsActions = actions.filterIsInstance<AiAction.SheetsOperation>()
        if (sheetsActions.isEmpty()) {
            chatSending = false
            return
        }

        if (depth >= MAX_TOOL_ROUNDS) {
            chatMessages.add(
                ChatMessage(
                    role = "assistant",
                    content = "已达到工具调用最大轮数（$MAX_TOOL_ROUNDS 轮），操作已结束。"
                )
            )
            chatSending = false
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            val toolResults = sheetsActions.map { executeSheetsOperationSync(it) }
            val resultMessage = buildToolResultMessage(toolResults)

            var context = ""
            try {
                SwingUtilities.invokeAndWait {
                    chatMessages.add(ChatMessage(role = "user", content = resultMessage))
                    context = buildChatContext()
                }
            } catch (e: Exception) {
                context = buildChatContext()
            }

            val followUp = AITranslator.chat(chatMessages.toList(), context)

            SwingUtilities.invokeLater {
                chatMessages.add(ChatMessage(role = "assistant", content = followUp.reply))
                handleAiActions(followUp.actions, depth + 1)
            }
        }
    }

    private fun executeInsertActions(actions: List<AiAction.InsertStrings>) {
        if (actions.isEmpty()) return
        val contextInfo = ContextManager.contextInfo ?: return
        val currentModuleName = contextInfo.currentModule?.moduleName
        val moduleWithMostLines = contextInfo.moduleWithMostLines

        actions.forEach { action ->
            val targetModule = resolveTargetModule(
                action.module,
                currentModuleName,
                moduleWithMostLines?.moduleName
            )
            if (targetModule == null) {
                showToast("No target module for ${action.name}")
                return@forEach
            }
            val stringsInfoList = if (targetModule == currentModuleName) {
                val existingTranslations = rows.associate { it.language to it.text }
                val merged = existingTranslations.toMutableMap()
                merged.putAll(action.translations)
                merged
            } else {
                val moduleStringsInfo = ContextManager.getModuleStringsInfo(project, targetModule)
                if (moduleStringsInfo.isEmpty()) {
                    showToast("Module $targetModule has no strings.xml")
                    return@forEach
                }
                val merged = moduleStringsInfo.associate { it.language to "" }.toMutableMap()
                merged.putAll(action.translations)
                merged
            }

            val useCurrentStringsList = targetModule == currentModuleName
                    && !insertStringsManager.languages.isNullOrEmpty()
            if (useCurrentStringsList) {
                insertStringsManager.insert(project, action.name, stringsInfoList)
            } else {
                insertStringsManager.insertIntoModule(project, targetModule, action.name, stringsInfoList)
            }
        }

        val lastAction = actions.last()
        val targetModule = resolveTargetModule(
            lastAction.module,
            currentModuleName,
            moduleWithMostLines?.moduleName
        )
        if (targetModule != null) {
            val updatedStringsList = ContextManager.scanModuleForKey(project, targetModule, lastAction.name)
            insertStringsManager.updateUI(lastAction.name, "", updatedStringsList)
        }
        val names = actions.joinToString(", ") { it.name }
        showToast("Inserted: $names")
        showChat = false
    }

    private fun resolveTargetModule(
        actionModule: String?,
        currentModuleName: String?,
        moduleWithMostLinesName: String?
    ): String? {
        return actionModule?.takeIf { it.isNotBlank() }
            ?: currentModuleName?.takeIf { it.isNotBlank() }
            ?: moduleWithMostLinesName?.takeIf { it.isNotBlank() }
    }

    override fun updateUI(
        @NotNull nodeName: String,
        @Nullable stringsList: List<StringsInfo>?
    ) {
        if (stringsList == null) return

        val seen = mutableSetOf<String>()
        val newRows = stringsList
            .filter { it.language.isNotEmpty() && seen.add(it.language) }
            .map { StringRow(language = it.language, text = it.text) }

        SwingUtilities.invokeLater {
            stringName = nodeName
            rows.clear()
            rows.addAll(newRows)
            toolWindow.show()
        }
    }
}

@Composable
private fun InsertStringsContent(
    stringName: String,
    rows: List<StringRow>,
    onStringNameChange: (String) -> Unit,
    onTextChange: (Int, String) -> Unit,
    onClear: (Int) -> Unit,
    onAi: (Int) -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onInsert: () -> Unit,
    toastMessage: String,
    showSettings: Boolean,
    showChat: Boolean,
    settingsTab: SettingsTab,
    aiUrl: String,
    aiApiKey: String,
    aiProtocol: AiProtocol,
    aiModel: String,
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
    onAiUrlChange: (String) -> Unit,
    onAiApiKeyChange: (String) -> Unit,
    onAiProtocolChange: (AiProtocol) -> Unit,
    onAiModelChange: (String) -> Unit,
    onFetchModels: () -> Unit,
    onSaveAiSettings: () -> Unit,
    sheetsDefaultSpreadsheetId: String,
    sheetsDefaultSheetName: String,
    sheetsConnectionStatus: String,
    onSheetsDefaultSpreadsheetIdChange: (String) -> Unit,
    onSheetsDefaultSheetNameChange: (String) -> Unit,
    onTestSheetsConnection: () -> Unit,
    onSaveSheetsSettings: () -> Unit,
    onChatInputChange: (String) -> Unit,
    onSendChat: () -> Unit,
    onNewChat: () -> Unit,
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
                        onSaveAiSettings = onSaveAiSettings,
                        sheetsDefaultSpreadsheetId = sheetsDefaultSpreadsheetId,
                        sheetsDefaultSheetName = sheetsDefaultSheetName,
                        sheetsConnectionStatus = sheetsConnectionStatus,
                        onSheetsDefaultSpreadsheetIdChange = onSheetsDefaultSpreadsheetIdChange,
                        onSheetsDefaultSheetNameChange = onSheetsDefaultSheetNameChange,
                        onTestSheetsConnection = onTestSheetsConnection,
                        onSaveSheetsSettings = onSaveSheetsSettings,
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
                        modifier = Modifier.fillMaxSize(),
                        colors = colors,
                    )
                } else {
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
