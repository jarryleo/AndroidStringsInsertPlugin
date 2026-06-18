package cn.jarryleo.insert_strings.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.jarryleo.insert_strings.InsertStringsManager
import cn.jarryleo.insert_strings.UiCallback
import cn.jarryleo.insert_strings.ai.AITranslator
import cn.jarryleo.insert_strings.ai.AiAction
import cn.jarryleo.insert_strings.ai.AiProtocol
import cn.jarryleo.insert_strings.ai.AiReply
import cn.jarryleo.insert_strings.ai.AiSettingsService
import cn.jarryleo.insert_strings.ai.ChatMessage
import cn.jarryleo.insert_strings.ai.mcp.McpClient
import cn.jarryleo.insert_strings.ai.mcp.McpSettingsService
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

private enum class SettingsTab {
    AI,
    MCP
}

class InsertStringsUI(
    private val toolWindow: ToolWindow
) : UiCallback {
    private lateinit var project: Project
    private lateinit var insertStringsManager: InsertStringsManager

    private var stringName by mutableStateOf("")
    private val rows = mutableStateListOf<StringRow>()
    private var showSettings by mutableStateOf(false)
    private var settingsTab by mutableStateOf(SettingsTab.AI)
    private var aiUrl by mutableStateOf("")
    private var aiApiKey by mutableStateOf("")
    private var aiProtocol by mutableStateOf(AiProtocol.OPENAI)
    private var aiModel by mutableStateOf("qwen-plus")
    private val modelOptions = mutableStateListOf<String>()
    private var modelFetchStatus by mutableStateOf("")
    private var mcpEnabled by mutableStateOf(false)
    private var mcpCommand by mutableStateOf("")
    private var mcpArguments by mutableStateOf("")
    private var mcpWorkingDir by mutableStateOf("")
    private var mcpSpreadsheetId by mutableStateOf("")
    private var mcpSheetName by mutableStateOf("Sheet1")
    private var mcpTimeoutSeconds by mutableStateOf("30")
    private var mcpConnectionStatus by mutableStateOf("")
    private var toastMessage by mutableStateOf("")
    private var toastTimer: Timer? = null
    private var showChat by mutableStateOf(false)
    private val chatMessages = mutableStateListOf<ChatMessage>()
    private var chatInput by mutableStateOf("")
    private var chatSending by mutableStateOf(false)

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
                    onSaveSettings = ::saveSettings,
                    mcpEnabled = mcpEnabled,
                    mcpCommand = mcpCommand,
                    mcpArguments = mcpArguments,
                    mcpWorkingDir = mcpWorkingDir,
                    mcpSpreadsheetId = mcpSpreadsheetId,
                    mcpSheetName = mcpSheetName,
                    mcpTimeoutSeconds = mcpTimeoutSeconds,
                    mcpConnectionStatus = mcpConnectionStatus,
                    onMcpEnabledChange = { mcpEnabled = it },
                    onMcpCommandChange = { mcpCommand = it },
                    onMcpArgumentsChange = { mcpArguments = it },
                    onMcpWorkingDirChange = { mcpWorkingDir = it },
                    onMcpSpreadsheetIdChange = { mcpSpreadsheetId = it },
                    onMcpSheetNameChange = { mcpSheetName = it },
                    onMcpTimeoutSecondsChange = { mcpTimeoutSeconds = it },
                    onMcpTestConnection = ::testMcpConnection,
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

        val mcpSettings = McpSettingsService.getInstance().state
        mcpEnabled = mcpSettings.enabled
        mcpCommand = mcpSettings.command
        mcpArguments = mcpSettings.arguments
        mcpWorkingDir = mcpSettings.workingDir
        mcpSpreadsheetId = mcpSettings.spreadsheetId
        mcpSheetName = mcpSettings.sheetName
        mcpTimeoutSeconds = mcpSettings.timeoutSeconds.toString()
    }

    private fun saveSettings() {
        AiSettingsService.getInstance().update(
            url = aiUrl,
            apiKey = aiApiKey,
            protocol = aiProtocol,
            model = aiModel,
        )
        McpSettingsService.getInstance().update(
            enabled = mcpEnabled,
            command = mcpCommand,
            arguments = mcpArguments,
            workingDir = mcpWorkingDir,
            spreadsheetId = mcpSpreadsheetId,
            sheetName = mcpSheetName,
            timeoutSeconds = mcpTimeoutSeconds.toIntOrNull() ?: 30,
        )
        modelFetchStatus = "Saved."
        mcpConnectionStatus = "Saved."
        showSettings = false
    }

    private fun testMcpConnection() {
        if (mcpCommand.isBlank()) {
            mcpConnectionStatus = "Please configure the MCP server command first."
            return
        }
        mcpConnectionStatus = "Connecting to MCP server..."
        ApplicationManager.getApplication().executeOnPooledThread {
            val client = createMcpClient()
            val result = client.use {
                it.connect().map { _ ->
                    val tools = it.listTools().getOrElse { emptyList() }
                    "Connected. Found ${tools.size} tool(s)."
                }
            }
            SwingUtilities.invokeLater {
                mcpConnectionStatus = result.getOrElse { it.message ?: "Connection failed." }
            }
        }
    }

    private fun createMcpClient(): McpClient {
        return McpClient(
            command = mcpCommand,
            arguments = mcpArguments.split(Regex("\\s+")).filter { it.isNotBlank() },
            workingDir = mcpWorkingDir.takeIf { it.isNotBlank() },
            timeoutSeconds = mcpTimeoutSeconds.toLongOrNull()?.coerceAtLeast(5) ?: 30
        )
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
        val internalMessages = chatMessages.toList().toMutableList()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val reply = runChatWithToolLoop(internalMessages, context)
                SwingUtilities.invokeLater {
                    chatMessages.add(ChatMessage(role = "assistant", content = reply.reply))
                    chatSending = false
                    handleAiActions(reply.actions)
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    chatMessages.add(ChatMessage(role = "assistant", content = "Error: ${e.message}"))
                    chatSending = false
                }
            }
        }
    }

    private fun runChatWithToolLoop(messages: MutableList<ChatMessage>, context: String): AiReply {
        if (!mcpEnabled || mcpCommand.isBlank()) {
            return AITranslator.chat(messages.toList(), context)
        }
        var client: McpClient? = null
        try {
            val maxIterations = 10
            repeat(maxIterations) {
                val reply = AITranslator.chat(messages.toList(), context)
                val toolCalls = reply.actions.filterIsInstance<AiAction.McpToolCall>()
                if (toolCalls.isEmpty()) {
                    return reply
                }
                if (client == null) {
                    client = createMcpClient()
                    client.connect().getOrThrow()
                }
                messages.add(ChatMessage(role = "assistant", content = reply.reply))
                toolCalls.forEach { toolCall ->
                    val resultText = try {
                        val currentClient = client ?: throw IllegalStateException("MCP client not initialized")
                        val arguments = buildMcpToolArguments(toolCall)
                        currentClient.callTool(toolCall.tool, arguments).fold(
                            onSuccess = { if (it.success) it.content else "Tool error: ${it.content}" },
                            onFailure = { "Tool call failed: ${it.message}" }
                        )
                    } catch (e: Exception) {
                        "Tool call failed: ${e.message}"
                    }
                    messages.add(ChatMessage(role = "user", content = "MCP tool `${toolCall.tool}` result:\n$resultText"))
                }
            }
            return AiReply("Reached maximum tool call iterations.", emptyList())
        } finally {
            client?.close()
        }
    }

    private fun buildMcpToolArguments(toolCall: AiAction.McpToolCall): JsonObject {
        return JsonObject().apply {
            addProperty("spreadsheetId", mcpSpreadsheetId)
            addProperty("sheet", mcpSheetName)
            toolCall.arguments.forEach { (k, v) -> addProperty(k, v) }
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
            addProperty("mcpEnabled", mcpEnabled)
            addProperty("mcpSpreadsheetId", mcpSpreadsheetId)
            addProperty("mcpSheetName", mcpSheetName)
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

    private fun handleAiActions(actions: List<AiAction>) {
        val insertActions = actions.filterIsInstance<AiAction.InsertStrings>()
        if (insertActions.isNotEmpty()) {
            executeInsertActions(insertActions)
            return
        }
        val askActions = actions.filterIsInstance<AiAction.AskUser>()
        if (askActions.isNotEmpty()) {
            // 问题内容已经包含在 reply 中，用户继续对话即可
            showToast(askActions.first().question)
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
    aiUrl: String,
    aiApiKey: String,
    aiProtocol: AiProtocol,
    aiModel: String,
    modelOptions: List<String>,
    modelFetchStatus: String,
    chatMessages: List<ChatMessage>,
    chatInput: String,
    chatSending: Boolean,
    settingsTab: SettingsTab,
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
    onSaveSettings: () -> Unit,
    mcpEnabled: Boolean,
    mcpCommand: String,
    mcpArguments: String,
    mcpWorkingDir: String,
    mcpSpreadsheetId: String,
    mcpSheetName: String,
    mcpTimeoutSeconds: String,
    mcpConnectionStatus: String,
    onMcpEnabledChange: (Boolean) -> Unit,
    onMcpCommandChange: (String) -> Unit,
    onMcpArgumentsChange: (String) -> Unit,
    onMcpWorkingDirChange: (String) -> Unit,
    onMcpSpreadsheetIdChange: (String) -> Unit,
    onMcpSheetNameChange: (String) -> Unit,
    onMcpTimeoutSecondsChange: (String) -> Unit,
    onMcpTestConnection: () -> Unit,
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
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            CompactButton(
                                text = "AI",
                                onClick = { onSettingsTabChange(SettingsTab.AI) },
                                modifier = Modifier.weight(1f),
                                colors = colors,
                                primary = settingsTab == SettingsTab.AI,
                            )
                            CompactButton(
                                text = "Sheets MCP",
                                onClick = { onSettingsTabChange(SettingsTab.MCP) },
                                modifier = Modifier.weight(1f),
                                colors = colors,
                                primary = settingsTab == SettingsTab.MCP,
                            )
                        }
                        when (settingsTab) {
                            SettingsTab.AI -> AiSettingsContent(
                                aiUrl = aiUrl,
                                aiApiKey = aiApiKey,
                                aiProtocol = aiProtocol,
                                aiModel = aiModel,
                                modelOptions = modelOptions,
                                modelFetchStatus = modelFetchStatus,
                                onClose = onCloseSettings,
                                onAiUrlChange = onAiUrlChange,
                                onAiApiKeyChange = onAiApiKeyChange,
                                onAiProtocolChange = onAiProtocolChange,
                                onAiModelChange = onAiModelChange,
                                onFetchModels = onFetchModels,
                                onSave = onSaveSettings,
                                modifier = Modifier.fillMaxSize(),
                                colors = colors,
                            )
                            SettingsTab.MCP -> McpSettingsContent(
                                enabled = mcpEnabled,
                                command = mcpCommand,
                                arguments = mcpArguments,
                                workingDir = mcpWorkingDir,
                                spreadsheetId = mcpSpreadsheetId,
                                sheetName = mcpSheetName,
                                timeoutSeconds = mcpTimeoutSeconds,
                                connectionStatus = mcpConnectionStatus,
                                onClose = onCloseSettings,
                                onEnabledChange = onMcpEnabledChange,
                                onCommandChange = onMcpCommandChange,
                                onArgumentsChange = onMcpArgumentsChange,
                                onWorkingDirChange = onMcpWorkingDirChange,
                                onSpreadsheetIdChange = onMcpSpreadsheetIdChange,
                                onSheetNameChange = onMcpSheetNameChange,
                                onTimeoutSecondsChange = onMcpTimeoutSecondsChange,
                                onTestConnection = onMcpTestConnection,
                                onSave = onSaveSettings,
                                modifier = Modifier.fillMaxSize(),
                                colors = colors,
                            )
                        }
                    }
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
