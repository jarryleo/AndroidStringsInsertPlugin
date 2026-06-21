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
import cn.jarryleo.insert_strings.ai.AiReply
import cn.jarryleo.insert_strings.ai.AiSettingsService
import cn.jarryleo.insert_strings.ai.ChatMessage
import cn.jarryleo.insert_strings.ai.ReviewResult
import cn.jarryleo.insert_strings.sheets.SheetsManager
import cn.jarryleo.insert_strings.sheets.SheetsSettingsService
import cn.jarryleo.insert_strings.xml.ContextManager
import cn.jarryleo.insert_strings.xml.KeyedStringsInfo
import cn.jarryleo.insert_strings.xml.KeyReadResult
import cn.jarryleo.insert_strings.xml.KeySearchResult
import cn.jarryleo.insert_strings.xml.KeyTextSearchResult
import cn.jarryleo.insert_strings.xml.ModuleInfo
import cn.jarryleo.insert_strings.xml.StringsInfo
import cn.jarryleo.insert_strings.xml.StringsService
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.Timer

class InsertStringsUI(
    private val toolWindow: ToolWindow
) : UiCallback {
    private lateinit var project: Project
    private lateinit var insertStringsManager: InsertStringsManager

    companion object {
        // 单次对话中 AI 调用工具的最大轮数。超过则强制结束,防止死循环。
        // 设 30 足以覆盖现实中的多步操作(检查+修正等),又能及时止损。
        private const val MAX_ITERATIONS = 30
        // 批量翻译审查时每批的行数。控制单次 AI 调用 token,避免溢出。
        private const val REVIEW_BATCH_SIZE = 80
        // load_tool_doc 按需加载工具文档的最大连续次数,防止 AI 反复加载文档而不执行操作。
        private const val MAX_TOOL_DOC_LOADS = 4
        // 单轮对话中 ask_user 的最大连续调用次数,防止 AI 反复追问形成死循环。
        // 每次用户实际回复(发送消息 / 点击选项)后重置为 0。
        private const val MAX_ASK_USER_CALLS = 3
        // 默认语言目录名(对应 values/ 目录,Android 默认英语资源)。
        // 作为兜底确保插入翻译时一定包含 values 键,避免英语写空。
        private const val DEFAULT_LANGUAGE = "values"
    }

    private data class SheetsToolResult(
        val operation: String,
        val success: Boolean,
        val message: String,
        val data: List<List<String>>? = null,
        val sheetNames: List<String>? = null,
        val rowNumber: Int? = null
    )

    private var stringName by mutableStateOf("")
    private val rows = mutableStateListOf<StringRow>()
    private val keyEntries = mutableStateListOf<KeyedStringsInfo>()
    private var selectedKeyIndex by mutableStateOf(0)
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
    // 当前对话轮中已连续加载工具文档的次数,防止 AI 反复加载文档而不执行操作。
    // 每次用户发新消息时重置为 0。
    private var toolDocLoadCount: Int = 0
    // 当前轮待响应的 ask_user 工具调用 ID。用户点击选项或发送消息后,作为 tool result 回传给 AI。
    private var pendingAskUserToolCallId: String? = null
    // 当前对话轮中已连续调用 ask_user 的次数,防止 AI 反复追问导致死循环。
    // 每次用户实际回复(发送新消息、点击选项)后重置为 0;超过 MAX_ASK_USER_CALLS 强制终止。
    private var askUserCallCount: Int = 0
    // 用户点击「停止」时置为 true。runToolLoop 在每轮开头与 AI 调用返回后检查该标志,
    // 为 true 时立即终止 tool loop,不再继续驱动 AI。
    // 下一轮用户发送新消息时(sendChatMessage / onChatOptionClick)会重置为 false。
    @Volatile
    private var stopRequested: Boolean = false
    // AI 上下文气泡弹窗:用户点击右上角「Context」按钮时弹窗,展示当前 AI 所知的项目上下文。
    // 文案在打开弹窗时按需构造(调用 buildChatContext),保证看到的是「最新」状态。
    private var showContextPopup by mutableStateOf(false)
    private var chatContextText by mutableStateOf("")

    // Google Sheets state
    private var sheetsDefaultSpreadsheetId by mutableStateOf("")
    private var sheetsDefaultSheetName by mutableStateOf("Sheet1")
    private var sheetsConnectionStatus by mutableStateOf("")
    private val sheetsAvailableSheets = mutableStateListOf<SheetsManager.SheetInfo>()
    private var sheetsListStatus by mutableStateOf("")

    private var settingsTab by mutableStateOf(SettingsTab.AI)

    private data class PendingSheetsInsert(
        val actions: List<AiAction.SheetsOperation>,
        /**
         * 与 [actions] 平行对齐的 tool_call_id 列表。用户做出选择、执行后,
         * 用这些 id 把 tool result 回传给 AI。
         */
        val actionToolCallIds: List<String>,
        val duplicateKeys: Set<String>,
        val spreadsheetId: String,
        val sheetName: String,
        val context: String,
        val iteration: Int
    )
    private var pendingSheetsInsert: PendingSheetsInsert? = null

    /**
     * action 与其对应的 tool_call_id 配对,用于 execute 方法的精确回传。
     * 取代之前 `actions: List<T> + actionToolCallIds: List<String>` 的下标对齐模式,
     * 避免过滤后下标错位的 bug。
     */
    private data class ActionWithToolCall(
        val action: AiAction,
        val toolCallId: String
    )

    private val rootPanel = ComposePanel().apply {
        setContent {
            MaterialTheme {
                InsertStringsContent(
                    stringName = stringName,
                    rows = rows,
                    keys = keyEntries.map { it.key },
                    selectedKeyIndex = selectedKeyIndex,
                    onStringNameChange = { stringName = it },
                    onTextChange = ::updateRowText,
                    onClear = { row -> updateRowText(row, "") },
                    onAi = ::translateRow,
                    onCopy = ::copy,
                    onPaste = ::paste,
                    onInsert = ::insert,
                    onSelectKey = ::selectKey,
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
                    sheetsAvailableSheetNames = sheetsAvailableSheets.map { it.title },
                    sheetsListStatus = sheetsListStatus,
                    onSheetsDefaultSpreadsheetIdChange = { sheetsDefaultSpreadsheetId = it },
                    onSheetsDefaultSheetNameChange = { sheetsDefaultSheetName = it },
                    onTestSheetsConnection = ::testSheetsConnection,
                    onSaveSheetsSettings = ::saveSheetsSettings,
                    onRefreshSheetsList = ::refreshSheetsList,
                    onChatInputChange = { chatInput = it },
                    onSendChat = ::sendChat,
                    onStopChat = ::stopChat,
                    onQuickSend = ::quickSend,
                    onNewChat = ::newChat,
                    onOptionClick = ::onChatOptionClick,
                    onOpenContext = ::openContextPopup,
                    onCloseContext = ::closeContextPopup,
                    showContextPopup = showContextPopup,
                    chatContextText = chatContextText,
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
        refreshSheetsList()
    }

    fun getRootPanel(): JComponent = rootPanel

    private fun copy() {
        saveCurrentEdits()
        insertStringsManager.copy()
        showToast("Copied ${keyEntries.size} key(s)")
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

        saveCurrentEdits()
        if (keyEntries.isEmpty()) {
            Messages.showMessageDialog(
                "No key to insert!",
                "Error",
                Messages.getInformationIcon()
            )
            return
        }

        val translationsPerKey = keyEntries.associate { entry ->
            entry.key to entry.stringsInfoList.associate { it.language to it.text }
        }
        insertStringsManager.insert(
            project = project,
            translationsPerKey = translationsPerKey
        )
        showToast("Inserted ${keyEntries.size} key(s)")
    }

    private fun selectKey(index: Int) {
        if (index == selectedKeyIndex) return
        if (index !in keyEntries.indices) return
        saveCurrentEdits()
        selectedKeyIndex = index
        updateRowsForSelectedKey()
    }

    private fun saveCurrentEdits() {
        if (selectedKeyIndex !in keyEntries.indices) return
        val entry = keyEntries[selectedKeyIndex]
        val updatedTexts = rows.associate { it.language to it.text }
        val updatedInfoList = entry.stringsInfoList.map { info ->
            if (updatedTexts.containsKey(info.language)) {
                StringsInfo(info.stringsFile, info.language, info.key, updatedTexts[info.language] ?: "")
            } else {
                info
            }
        }
        keyEntries[selectedKeyIndex] = KeyedStringsInfo(
            key = stringName,
            anchorNodeName = entry.anchorNodeName,
            stringsInfoList = updatedInfoList
        )
    }

    private fun updateRowsForSelectedKey() {
        val entry = keyEntries.getOrNull(selectedKeyIndex) ?: return
        stringName = entry.key
        val seen = mutableSetOf<String>()
        val newRows = entry.stringsInfoList
            .filter { it.language.isNotEmpty() && seen.add(it.language) }
            .map { StringRow(language = it.language, text = it.text) }
        rows.clear()
        rows.addAll(newRows)
    }

    private fun updateRowText(rowIndex: Int, text: String) {
        if (rowIndex !in rows.indices) return
        rows[rowIndex] = rows[rowIndex].copy(text = text)
    }

    private fun translateRow(rowIndex: Int) {
        if (rowIndex !in rows.indices) return
        saveCurrentEdits()
        val targetLangRaw = rows[rowIndex].language
        val targetLanguage = targetLangRaw.let {
            if (it.equals("values", ignoreCase = true)) "values-en" else it
        }
        val currentKey = keyEntries.getOrNull(selectedKeyIndex)?.key ?: return
        val items = keyEntries.mapNotNull { entry ->
            val sourceText = entry.stringsInfoList.firstOrNull { it.text.isNotEmpty() }?.text
                ?: return@mapNotNull null
            entry.key to sourceText
        }
        if (items.isEmpty()) return

        updateRowText(rowIndex, "Translating...")
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = AITranslator.translateBatch(targetLanguage, items)
            SwingUtilities.invokeLater {
                val nowKey = keyEntries.getOrNull(selectedKeyIndex)?.key
                if (nowKey == currentKey && rowIndex in rows.indices) {
                    updateRowText(rowIndex, result[currentKey] ?: "")
                }
                keyEntries.forEachIndexed { index, entry ->
                    val translated = result[entry.key] ?: return@forEachIndexed
                    val newInfoList = entry.stringsInfoList.map { info ->
                        if (info.language == targetLangRaw) {
                            StringsInfo(info.stringsFile, info.language, info.key, translated)
                        } else {
                            info
                        }
                    }
                    keyEntries[index] = KeyedStringsInfo(
                        entry.key, entry.anchorNodeName, newInfoList
                    )
                }
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
        refreshSheetsList()
    }

    /**
     * 异步从默认表格拉取所有工作表名称，供 AI 上下文和设置页下拉使用。
     * 这同时验证了默认表格 ID 与授权是否可用。
     */
    private fun refreshSheetsList() {
        val spreadsheetId = sheetsDefaultSpreadsheetId.trim()
        if (spreadsheetId.isBlank()) {
            sheetsAvailableSheets.clear()
            sheetsListStatus = ""
            return
        }
        sheetsListStatus = "Loading sheets..."
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = SheetsManager.listSheetNames(project, spreadsheetId)
            SwingUtilities.invokeLater {
                result.fold(
                    onSuccess = { sheets ->
                        sheetsAvailableSheets.clear()
                        sheetsAvailableSheets.addAll(sheets)
                        sheetsListStatus = if (sheets.isEmpty()) {
                            "No sheets found."
                        } else {
                            "Loaded ${sheets.size} sheet(s): ${sheets.joinToString(", ") { it.title }}"
                        }
                        // 若当前默认 sheet 不在列表中，自动切换到第一个
                        if (sheets.isNotEmpty() &&
                            sheets.none { it.title.equals(sheetsDefaultSheetName, ignoreCase = true) }
                        ) {
                            sheetsDefaultSheetName = sheets.first().title
                        }
                    },
                    onFailure = {
                        sheetsAvailableSheets.clear()
                        sheetsListStatus = it.message ?: "Failed to load sheets."
                    }
                )
            }
        }
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
        saveCurrentEdits()
        val languages = keyEntries.firstOrNull()?.stringsInfoList?.map { it.language }
            ?: rows.map { it.language }
        if (languages.isEmpty()) return emptyList()
        val header = listOf("key") + languages
        val dataRows = keyEntries.map { entry ->
            val translationsMap = entry.stringsInfoList.associate { it.language to it.text }
            listOf(entry.key) + languages.map { translationsMap[it] ?: "" }
        }
        return listOf(header) + dataRows
    }

    private fun applySheetRowsToUi(sheetRows: List<List<String>>) {
        if (sheetRows.isEmpty()) return
        val header = sheetRows.firstOrNull() ?: return
        val keyIndex = header.indexOfFirst { it.equals("key", ignoreCase = true) }
        val dataRows = if (keyIndex != -1) sheetRows.drop(1) else sheetRows
        if (dataRows.isEmpty()) return
        val languages = if (keyIndex != -1) {
            header.filterIndexed { index, _ -> index != keyIndex }
        } else {
            header.drop(1)
        }
        val keyColumn = if (keyIndex != -1) keyIndex else 0

        val newEntries = dataRows.map { row ->
            val key = row.getOrNull(keyColumn) ?: ""
            val infoList = languages.mapIndexed { langIndex, language ->
                val originalIndex = if (keyIndex == -1) {
                    langIndex + 1
                } else {
                    if (langIndex < keyIndex) langIndex else langIndex + 1
                }
                val value = row.getOrNull(originalIndex) ?: ""
                StringRow(language = language, text = value)
            }
            KeyedStringsInfo(key, "", emptyList())
        }

        val existingLanguages = keyEntries.firstOrNull()?.stringsInfoList?.map { it.language }
        if (existingLanguages != null && existingLanguages.isNotEmpty()) {
            val mergedEntries = newEntries.mapIndexed { idx, entry ->
                val dataRow = dataRows[idx]
                val translationsMap = existingLanguages.associateWith { lang ->
                    val langIdx = languages.indexOf(lang)
                    if (langIdx >= 0) {
                        val originalIndex = if (keyIndex == -1) langIdx + 1
                        else if (langIdx < keyIndex) langIdx else langIdx + 1
                        dataRow.getOrNull(originalIndex) ?: ""
                    } else ""
                }
                val infoList = keyEntries.firstOrNull()?.stringsInfoList?.map { info ->
                    StringsInfo(info.stringsFile, info.language, entry.key, translationsMap[info.language] ?: "")
                } ?: emptyList()
                KeyedStringsInfo(entry.key, "", infoList)
            }
            keyEntries.clear()
            keyEntries.addAll(mergedEntries)
        } else {
            keyEntries.clear()
            keyEntries.addAll(newEntries)
        }
        selectedKeyIndex = 0
        updateRowsForSelectedKey()
    }

    private fun applySheetRowToUi(row: List<String>) {
        applySheetRowsToUi(listOf(row))
    }

    private fun executeSheetsOperationSync(action: AiAction.SheetsOperation): SheetsToolResult {
        val spreadsheetId = SheetsManager.resolveSpreadsheetId(project, action.spreadsheetId)
        if (spreadsheetId.isBlank()) {
            return SheetsToolResult(actionLabel(action), false, "Spreadsheet ID 为空，请先在设置中配置。")
        }
        val sheetName = action.sheetName ?: SheetsManager.defaultSheetName(project)

        return when (action.operation) {
            AiAction.SheetsOperation.Operation.LIST_SHEETS -> {
                val result = SheetsManager.listSheetNames(project, spreadsheetId)
                result.fold(
                    onSuccess = { sheets ->
                        SwingUtilities.invokeLater {
                            sheetsAvailableSheets.clear()
                            sheetsAvailableSheets.addAll(sheets)
                        }
                        val names = sheets.map { it.title }
                        val summary = if (names.isEmpty()) "表格中没有工作表" else "共 ${names.size} 个工作表: ${names.joinToString(", ")}"
                        SheetsToolResult(
                            operation = "列出工作表",
                            success = true,
                            message = summary,
                            sheetNames = names
                        )
                    },
                    onFailure = {
                        SheetsToolResult("列出工作表", false, it.message ?: "Failed to list sheets.")
                    }
                )
            }

            AiAction.SheetsOperation.Operation.READ -> {
                val result = if (!action.range.isNullOrBlank()) {
                    SheetsManager.readRange(project, spreadsheetId, action.range)
                } else if (!action.key.isNullOrBlank()) {
                    SheetsManager.searchRowInSheet(project, spreadsheetId, sheetName, action.key).map { listOf(it.second) }
                } else {
                    SheetsManager.readSheet(project, spreadsheetId, sheetName)
                }
                result.fold(
                    onSuccess = { sheetRows ->
                        SwingUtilities.invokeLater { showToast("Read from Google Sheets.") }
                        val dataSummary = if (sheetRows.isEmpty()) "工作表 '$sheetName' 为空" else "读取到 ${sheetRows.size} 行数据"
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
                val range = action.range ?: "$sheetName!A1:Z100000"
                val result = SheetsManager.searchRowByKey(project, spreadsheetId, range, key)
                result.fold(
                    onSuccess = { (rowNum, row) ->
                        SwingUtilities.invokeLater { showToast("Found key '$key' at row $rowNum.") }
                        SheetsToolResult(
                            operation = "搜索表格",
                            success = true,
                            message = "找到 key '$key' 在第 $rowNum 行",
                            data = listOf(row),
                            rowNumber = rowNum
                        )
                    },
                    onFailure = {
                        SheetsToolResult("搜索表格", false, it.message ?: "Sheets search failed.")
                    }
                )
            }

            AiAction.SheetsOperation.Operation.WRITE -> {
                val rowsToWrite = action.rows ?: buildSheetRows()
                val range = action.range ?: "$sheetName!A1:Z1000"
                val result = SheetsManager.writeRange(project, spreadsheetId, range, rowsToWrite, action.rowTextColors)
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

            AiAction.SheetsOperation.Operation.APPEND_ROW -> {
                val rowsToAppend = action.rows
                if (rowsToAppend.isNullOrEmpty()) {
                    return SheetsToolResult("追加行", false, "rows 为空。")
                }
                val row = rowsToAppend.first()
                val rowColors = action.rowTextColors?.firstOrNull()
                val result = SheetsManager.appendRow(project, spreadsheetId, sheetName, row, rowColors)
                result.fold(
                    onSuccess = {
                        SwingUtilities.invokeLater { showToast("Appended row to $sheetName.") }
                        SheetsToolResult("追加行", true, "成功在工作表 '$sheetName' 末尾追加 1 行: ${row.joinToString(" | ")}")
                    },
                    onFailure = {
                        SheetsToolResult("追加行", false, it.message ?: "Sheets append failed.")
                    }
                )
            }

            AiAction.SheetsOperation.Operation.INSERT_ROW -> {
                val rowNum = action.rowNumber
                if (rowNum == null || rowNum < 1) {
                    return SheetsToolResult("插入行", false, "rowNumber 无效，必须为 >= 1 的整数。")
                }
                val rowsToInsert = action.rows
                if (rowsToInsert.isNullOrEmpty()) {
                    return SheetsToolResult("插入行", false, "rows 为空。")
                }
                val row = rowsToInsert.first()
                val rowColors = action.rowTextColors?.firstOrNull()
                val result = SheetsManager.insertRow(project, spreadsheetId, sheetName, rowNum, row, rowColors)
                result.fold(
                    onSuccess = {
                        SwingUtilities.invokeLater { showToast("Inserted row at $rowNum in $sheetName.") }
                        SheetsToolResult("插入行", true, "成功在工作表 '$sheetName' 第 $rowNum 行插入新行: ${row.joinToString(" | ")}")
                    },
                    onFailure = {
                        SheetsToolResult("插入行", false, it.message ?: "Sheets insert failed.")
                    }
                )
            }

            AiAction.SheetsOperation.Operation.UPDATE_ROW -> {
                val rowNum = action.rowNumber
                if (rowNum == null || rowNum < 1) {
                    return SheetsToolResult("更新行", false, "rowNumber 无效，必须为 >= 1 的整数。")
                }
                val rowsToUpdate = action.rows
                if (rowsToUpdate.isNullOrEmpty()) {
                    return SheetsToolResult("更新行", false, "rows 为空。")
                }
                val row = rowsToUpdate.first()
                val rowColors = action.rowTextColors?.firstOrNull()
                val result = SheetsManager.updateRow(project, spreadsheetId, sheetName, rowNum, row, rowColors)
                result.fold(
                    onSuccess = {
                        SwingUtilities.invokeLater { showToast("Updated row $rowNum in $sheetName.") }
                        SheetsToolResult("更新行", true, "成功更新工作表 '$sheetName' 第 $rowNum 行为: ${row.joinToString(" | ")}")
                    },
                    onFailure = {
                        SheetsToolResult("更新行", false, it.message ?: "Sheets update failed.")
                    }
                )
            }

            AiAction.SheetsOperation.Operation.DELETE_ROW -> {
                val rowNum = action.rowNumber
                if (rowNum == null || rowNum < 1) {
                    return SheetsToolResult("删除行", false, "rowNumber 无效，必须为 >= 1 的整数。")
                }
                val result = SheetsManager.deleteRow(project, spreadsheetId, sheetName, rowNum)
                result.fold(
                    onSuccess = {
                        SwingUtilities.invokeLater { showToast("Deleted row $rowNum in $sheetName.") }
                        SheetsToolResult("删除行", true, "成功删除工作表 '$sheetName' 第 $rowNum 行")
                    },
                    onFailure = {
                        SheetsToolResult("删除行", false, it.message ?: "Sheets delete failed.")
                    }
                )
            }

            AiAction.SheetsOperation.Operation.CLEAR_ROW -> {
                val rowNum = action.rowNumber
                if (rowNum == null || rowNum < 1) {
                    return SheetsToolResult("清空行", false, "rowNumber 无效，必须为 >= 1 的整数。")
                }
                val result = SheetsManager.clearRow(project, spreadsheetId, sheetName, rowNum)
                result.fold(
                    onSuccess = {
                        SwingUtilities.invokeLater { showToast("Cleared row $rowNum in $sheetName.") }
                        SheetsToolResult("清空行", true, "成功清空工作表 '$sheetName' 第 $rowNum 行内容")
                    },
                    onFailure = {
                        SheetsToolResult("清空行", false, it.message ?: "Sheets clear failed.")
                    }
                )
            }

            // ==================== 列操作（需要用户确认） ====================

            AiAction.SheetsOperation.Operation.INSERT_COLUMN -> {
                val colIdx = action.columnIndex
                if (colIdx == null || colIdx < 1) {
                    return SheetsToolResult("插入列", false, "columnIndex 无效，必须为 >= 1 的整数。")
                }
                val values = action.columnValues
                if (values.isNullOrEmpty()) {
                    return SheetsToolResult("插入列", false, "columnValues 为空。")
                }
                if (!confirmColumnOperation("插入列", "将在工作表 '$sheetName' 第 $colIdx 列插入新列，原该列及之后整体右移。\n列头：${values.first()}\n是否允许？")) {
                    return SheetsToolResult("插入列", false, "用户已取消插入列操作。")
                }
                val result = SheetsManager.insertColumn(project, spreadsheetId, sheetName, colIdx, values, action.columnTextColors)
                result.fold(
                    onSuccess = {
                        SwingUtilities.invokeLater { showToast("Inserted column at $colIdx in $sheetName.") }
                        SheetsToolResult("插入列", true, "成功在工作表 '$sheetName' 第 $colIdx 列插入新列，共 ${values.size} 个单元格")
                    },
                    onFailure = { SheetsToolResult("插入列", false, it.message ?: "Sheets insert column failed.") }
                )
            }

            AiAction.SheetsOperation.Operation.APPEND_COLUMN -> {
                val values = action.columnValues
                if (values.isNullOrEmpty()) {
                    return SheetsToolResult("追加列", false, "columnValues 为空。")
                }
                if (!confirmColumnOperation("追加列", "将在工作表 '$sheetName' 末尾追加新列。\n列头：${values.first()}\n是否允许？")) {
                    return SheetsToolResult("追加列", false, "用户已取消追加列操作。")
                }
                val result = SheetsManager.appendColumn(project, spreadsheetId, sheetName, values, action.columnTextColors)
                result.fold(
                    onSuccess = {
                        SwingUtilities.invokeLater { showToast("Appended column to $sheetName.") }
                        SheetsToolResult("追加列", true, "成功在工作表 '$sheetName' 末尾追加新列，共 ${values.size} 个单元格")
                    },
                    onFailure = { SheetsToolResult("追加列", false, it.message ?: "Sheets append column failed.") }
                )
            }

            AiAction.SheetsOperation.Operation.UPDATE_COLUMN -> {
                val colIdx = action.columnIndex
                if (colIdx == null || colIdx < 1) {
                    return SheetsToolResult("更新列", false, "columnIndex 无效，必须为 >= 1 的整数。")
                }
                val values = action.columnValues
                if (values.isNullOrEmpty()) {
                    return SheetsToolResult("更新列", false, "columnValues 为空。")
                }
                if (!confirmColumnOperation("更新列", "将更新工作表 '$sheetName' 第 $colIdx 列内容，共 ${values.size} 个单元格。\n是否允许？")) {
                    return SheetsToolResult("更新列", false, "用户已取消更新列操作。")
                }
                val result = SheetsManager.updateColumn(project, spreadsheetId, sheetName, colIdx, values, action.columnTextColors)
                result.fold(
                    onSuccess = {
                        SwingUtilities.invokeLater { showToast("Updated column $colIdx in $sheetName.") }
                        SheetsToolResult("更新列", true, "成功更新工作表 '$sheetName' 第 $colIdx 列，共 ${values.size} 个单元格")
                    },
                    onFailure = { SheetsToolResult("更新列", false, it.message ?: "Sheets update column failed.") }
                )
            }

            AiAction.SheetsOperation.Operation.DELETE_COLUMN -> {
                val colIdx = action.columnIndex
                if (colIdx == null || colIdx < 1) {
                    return SheetsToolResult("删除列", false, "columnIndex 无效，必须为 >= 1 的整数。")
                }
                if (!confirmColumnOperation("删除列", "将删除工作表 '$sheetName' 第 $colIdx 列，后续列整体左移，数据不可恢复。\n是否允许？")) {
                    return SheetsToolResult("删除列", false, "用户已取消删除列操作。")
                }
                val result = SheetsManager.deleteColumn(project, spreadsheetId, sheetName, colIdx)
                result.fold(
                    onSuccess = {
                        SwingUtilities.invokeLater { showToast("Deleted column $colIdx in $sheetName.") }
                        SheetsToolResult("删除列", true, "成功删除工作表 '$sheetName' 第 $colIdx 列")
                    },
                    onFailure = { SheetsToolResult("删除列", false, it.message ?: "Sheets delete column failed.") }
                )
            }

            AiAction.SheetsOperation.Operation.CLEAR_COLUMN -> {
                val colIdx = action.columnIndex
                if (colIdx == null || colIdx < 1) {
                    return SheetsToolResult("清空列", false, "columnIndex 无效，必须为 >= 1 的整数。")
                }
                if (!confirmColumnOperation("清空列", "将清空工作表 '$sheetName' 第 $colIdx 列内容（保留空列）。\n是否允许？")) {
                    return SheetsToolResult("清空列", false, "用户已取消清空列操作。")
                }
                val result = SheetsManager.clearColumn(project, spreadsheetId, sheetName, colIdx)
                result.fold(
                    onSuccess = {
                        SwingUtilities.invokeLater { showToast("Cleared column $colIdx in $sheetName.") }
                        SheetsToolResult("清空列", true, "成功清空工作表 '$sheetName' 第 $colIdx 列内容")
                    },
                    onFailure = { SheetsToolResult("清空列", false, it.message ?: "Sheets clear column failed.") }
                )
            }

            // ==================== 批量翻译审查/修正（最小 token） ====================

            AiAction.SheetsOperation.Operation.CHECK_TRANSLATIONS -> {
                val report = runTranslationReview(action, spreadsheetId, sheetName, mode = "check")
                SwingUtilities.invokeLater { showToast(report.summary.ifBlank { "检查完成" }) }
                SheetsToolResult("检查全部翻译", true, report.toReportText())
            }

            AiAction.SheetsOperation.Operation.FIX_TRANSLATIONS -> {
                val report = runTranslationReview(action, spreadsheetId, sheetName, mode = "fix")
                SwingUtilities.invokeLater { showToast(report.summary.ifBlank { "修正完成" }) }
                SheetsToolResult("修正全部翻译", report.success, report.toReportText())
            }

            AiAction.SheetsOperation.Operation.FREEZE_ROWS -> {
                val rowCount = action.freezeRowCount
                if (rowCount == null || rowCount < 0) {
                    return SheetsToolResult("冻结行", false, "freezeRowCount 无效，必须为 >= 0 的整数。")
                }
                val result = SheetsManager.freezeRows(project, spreadsheetId, sheetName, rowCount)
                result.fold(
                    onSuccess = {
                        SwingUtilities.invokeLater {
                            showToast(if (rowCount == 0) "已取消冻结行" else "已冻结前 $rowCount 行")
                        }
                        SheetsToolResult(
                            "冻结行",
                            true,
                            if (rowCount == 0) "已取消工作表 '$sheetName' 的冻结行"
                            else "已冻结工作表 '$sheetName' 前 $rowCount 行"
                        )
                    },
                    onFailure = {
                        SheetsToolResult("冻结行", false, it.message ?: "Sheets freeze rows failed.")
                    }
                )
            }

            AiAction.SheetsOperation.Operation.FREEZE_COLUMNS -> {
                val colCount = action.freezeColumnCount
                if (colCount == null || colCount < 0) {
                    return SheetsToolResult("冻结列", false, "freezeColumnCount 无效，必须为 >= 0 的整数。")
                }
                val result = SheetsManager.freezeColumns(project, spreadsheetId, sheetName, colCount)
                result.fold(
                    onSuccess = {
                        SwingUtilities.invokeLater {
                            showToast(if (colCount == 0) "已取消冻结列" else "已冻结前 $colCount 列")
                        }
                        SheetsToolResult(
                            "冻结列",
                            true,
                            if (colCount == 0) "已取消工作表 '$sheetName' 的冻结列"
                            else "已冻结工作表 '$sheetName' 前 $colCount 列"
                        )
                    },
                    onFailure = {
                        SheetsToolResult("冻结列", false, it.message ?: "Sheets freeze columns failed.")
                    }
                )
            }

            // ==================== 填充/清除背景色 ====================

            AiAction.SheetsOperation.Operation.FILL_COLOR -> {
                val range = action.range
                if (range.isNullOrBlank()) {
                    return SheetsToolResult("填充颜色", false, "range 为空。")
                }
                val color = action.color
                if (color.isNullOrBlank()) {
                    return SheetsToolResult("填充颜色", false, "color 为空。")
                }
                val result = SheetsManager.fillColor(project, spreadsheetId, range, color, sheetName)
                result.fold(
                    onSuccess = {
                        SwingUtilities.invokeLater { showToast("Filled $range with $color.") }
                        SheetsToolResult(
                            "填充颜色",
                            true,
                            "已在工作表 '$sheetName' 的范围 $range 填充背景色 $color"
                        )
                    },
                    onFailure = {
                        SheetsToolResult("填充颜色", false, it.message ?: "Sheets fill color failed.")
                    }
                )
            }

            AiAction.SheetsOperation.Operation.CLEAR_COLOR -> {
                val range = action.range
                if (range.isNullOrBlank()) {
                    return SheetsToolResult("清除颜色", false, "range 为空。")
                }
                val result = SheetsManager.clearColor(project, spreadsheetId, range, sheetName)
                result.fold(
                    onSuccess = {
                        SwingUtilities.invokeLater { showToast("Cleared color on $range.") }
                        SheetsToolResult(
                            "清除颜色",
                            true,
                            "已清除工作表 '$sheetName' 范围 $range 的背景色"
                        )
                    },
                    onFailure = {
                        SheetsToolResult("清除颜色", false, it.message ?: "Sheets clear color failed.")
                    }
                )
            }

            AiAction.SheetsOperation.Operation.SET_TEXT_COLOR -> {
                val range = action.range
                if (range.isNullOrBlank()) {
                    return SheetsToolResult("设置文字色", false, "range 为空。")
                }
                val textColor = action.textColor
                if (textColor.isNullOrBlank()) {
                    return SheetsToolResult("设置文字色", false, "textColor 为空。")
                }
                val result = SheetsManager.setTextColor(project, spreadsheetId, range, textColor, sheetName)
                result.fold(
                    onSuccess = {
                        SwingUtilities.invokeLater { showToast("Set text color $textColor on $range.") }
                        SheetsToolResult(
                            "设置文字色",
                            true,
                            "已将工作表 '$sheetName' 范围 $range 的文字设为 $textColor"
                        )
                    },
                    onFailure = {
                        SheetsToolResult("设置文字色", false, it.message ?: "Sheets set text color failed.")
                    }
                )
            }

            AiAction.SheetsOperation.Operation.CLEAR_TEXT_COLOR -> {
                val range = action.range
                if (range.isNullOrBlank()) {
                    return SheetsToolResult("清除文字色", false, "range 为空。")
                }
                val result = SheetsManager.clearTextColor(project, spreadsheetId, range, sheetName)
                result.fold(
                    onSuccess = {
                        SwingUtilities.invokeLater { showToast("Cleared text color on $range.") }
                        SheetsToolResult(
                            "清除文字色",
                            true,
                            "已清除工作表 '$sheetName' 范围 $range 的文字色"
                        )
                    },
                    onFailure = {
                        SheetsToolResult("清除文字色", false, it.message ?: "Sheets clear text color failed.")
                    }
                )
            }
        }
    }

    private fun actionLabel(action: AiAction.SheetsOperation): String {
        return when (action.operation) {
            AiAction.SheetsOperation.Operation.LIST_SHEETS -> "列出工作表"
            AiAction.SheetsOperation.Operation.READ -> "读取表格"
            AiAction.SheetsOperation.Operation.SEARCH -> "搜索表格"
            AiAction.SheetsOperation.Operation.WRITE -> "写入表格"
            AiAction.SheetsOperation.Operation.APPEND_ROW -> "追加行"
            AiAction.SheetsOperation.Operation.INSERT_ROW -> "插入行"
            AiAction.SheetsOperation.Operation.UPDATE_ROW -> "更新行"
            AiAction.SheetsOperation.Operation.DELETE_ROW -> "删除行"
            AiAction.SheetsOperation.Operation.CLEAR_ROW -> "清空行"
            AiAction.SheetsOperation.Operation.INSERT_COLUMN -> "插入列"
            AiAction.SheetsOperation.Operation.APPEND_COLUMN -> "追加列"
            AiAction.SheetsOperation.Operation.UPDATE_COLUMN -> "更新列"
            AiAction.SheetsOperation.Operation.DELETE_COLUMN -> "删除列"
            AiAction.SheetsOperation.Operation.CLEAR_COLUMN -> "清空列"
            AiAction.SheetsOperation.Operation.CHECK_TRANSLATIONS -> "检查全部翻译"
            AiAction.SheetsOperation.Operation.FIX_TRANSLATIONS -> "修正全部翻译"
            AiAction.SheetsOperation.Operation.FREEZE_ROWS -> "冻结行"
            AiAction.SheetsOperation.Operation.FREEZE_COLUMNS -> "冻结列"
            AiAction.SheetsOperation.Operation.FILL_COLOR -> "填充颜色"
            AiAction.SheetsOperation.Operation.CLEAR_COLOR -> "清除颜色"
            AiAction.SheetsOperation.Operation.SET_TEXT_COLOR -> "设置文字色"
            AiAction.SheetsOperation.Operation.CLEAR_TEXT_COLOR -> "清除文字色"
        }
    }

    private fun runFindRowsByText(action: AiAction.FindRowsByText): String {
        val spreadsheetId = SheetsManager.resolveSpreadsheetId(project, action.spreadsheetId)
        if (spreadsheetId.isBlank()) {
            return "[工具执行结果] 类型:find_rows_by_text 状态:失败 信息:Spreadsheet ID 为空,请先在设置中配置"
        }
        val sheetName = action.sheetName ?: SheetsManager.defaultSheetName(project)
        val matchType = mapToSheetsMatchType(action.matchType)
        val result = SheetsManager.findRowsByText(
            project = project,
            spreadsheetId = spreadsheetId,
            sheetName = sheetName,
            text = action.text,
            column = action.column,
            matchType = matchType,
            caseSensitive = action.caseSensitive,
            limit = action.limit
        )
        return result.fold(
            onSuccess = { rows ->
                if (rows.isEmpty()) {
                    val scope = buildString {
                        append("text:\"").append(action.text).append("\"")
                        append(" sheet:").append(sheetName)
                        if (action.column != null) append(" column:").append(action.column)
                        append(" match:").append(action.matchType.name.lowercase())
                    }
                    "[工具执行结果] 类型:find_rows_by_text 状态:成功 命中:0 范围:$scope 未找到匹配行"
                } else {
                    buildString {
                        append("[工具执行结果] 类型:find_rows_by_text 状态:成功 命中:").append(rows.size)
                        append(" sheet:").append(sheetName)
                        append(" match:").append(action.matchType.name.lowercase())
                        appendLine()
                        rows.forEachIndexed { idx, r ->
                            append(idx + 1).append(". 行").append(r.rowNumber)
                            if (r.columnName.isNotEmpty()) {
                                append(" 列=").append(r.columnName)
                            }
                            append(" 命中=\"").append(truncateForLog(r.matchedText, 50)).append("\"")
                            append(" | ").append(r.row.joinToString(" | "))
                            appendLine()
                        }
                        if (rows.size >= action.limit) {
                            appendLine("… 已达返回上限,可能还有更多匹配。")
                        }
                    }
                }
            },
            onFailure = { e ->
                "[工具执行结果] 类型:find_rows_by_text 状态:失败 信息:${e.message ?: "unknown"}"
            }
        )
    }

    private fun mapToSheetsMatchType(type: AiAction.TextMatchType): SheetsManager.TextMatchType {
        return when (type) {
            AiAction.TextMatchType.EXACT -> SheetsManager.TextMatchType.EXACT
            AiAction.TextMatchType.CONTAINS -> SheetsManager.TextMatchType.CONTAINS
            AiAction.TextMatchType.REGEX -> SheetsManager.TextMatchType.REGEX
        }
    }

    /**
     * 面向用户的工具执行结果摘要（兜底用）。
     * 当前架构下,每个 tool result 都独立发给 AI,不再需要聚合的纯文本摘要;
     * 保留此函数供未来需要时使用。
     */
    @Suppress("unused")
    private fun buildToolResultSummary(results: List<SheetsToolResult>): String {
        if (results.isEmpty()) return ""
        return buildString {
            results.forEachIndexed { index, r ->
                if (index > 0) appendLine()
                append("${r.operation}：${if (r.success) "成功" else "失败"} - ${r.message}")
            }
        }
    }

    /**
     * 列结构变更操作的用户确认对话框（EDT 同步）。
     * executeSheetsOperationSync 运行在 pooled thread，故用 invokeAndWait 弹窗。
     */
    private fun confirmColumnOperation(title: String, message: String): Boolean {
        if (SwingUtilities.isEventDispatchThread()) {
            return Messages.showOkCancelDialog(message, title, Messages.getWarningIcon()) == Messages.OK
        }
        val approved = booleanArrayOf(false)
        try {
            SwingUtilities.invokeAndWait {
                approved[0] =
                    Messages.showOkCancelDialog(message, title, Messages.getWarningIcon()) == Messages.OK
            }
        } catch (e: Exception) {
            approved[0] = false
        }
        return approved[0]
    }

    /**
     * 批量翻译审查/修正结果（聚合后）。
     */
    private data class TranslationReviewReport(
        val success: Boolean,
        val summary: String,
        val issueLines: List<String>,
        val fixAppliedCount: Int,
        val fixFailedCount: Int
    ) {
        fun toReportText(): String {
            return buildString {
                appendLine(summary)
                if (fixAppliedCount > 0 || fixFailedCount > 0) {
                    appendLine("已写入修正行数：$fixAppliedCount；失败：$fixFailedCount。")
                }
                if (issueLines.isNotEmpty()) {
                    appendLine("问题清单：")
                    issueLines.take(200).forEach { appendLine("- $it") }
                    if (issueLines.size > 200) {
                        appendLine("...（共 ${issueLines.size} 条，已省略后续）")
                    }
                }
            }
        }
    }

    /**
     * 执行全表翻译审查/修正：读取整表 → 分批调用 AI（独立最小上下文）→ 汇总报告。
     * mode = "check" 仅报告问题；mode = "fix" 自动写入修正行。
     * 分批处理保证单次 AI 调用 token 不会溢出；主聊天只收到最终报告。
     */
    private fun runTranslationReview(
        action: AiAction.SheetsOperation,
        spreadsheetId: String,
        sheetName: String,
        mode: String
    ): TranslationReviewReport {
        val readResult = if (!action.range.isNullOrBlank()) {
            SheetsManager.readRange(project, spreadsheetId, action.range)
        } else {
            SheetsManager.readSheet(project, spreadsheetId, sheetName)
        }
        val sheetRows = readResult.getOrElse {
            return TranslationReviewReport(false, "读取表格失败：${it.message ?: "unknown"}", emptyList(), 0, 0)
        }
        if (sheetRows.isEmpty()) {
            return TranslationReviewReport(true, "工作表 '$sheetName' 为空，无需${
                if (mode == "fix") "修正" else "检查"
            }。", emptyList(), 0, 0)
        }

        val header = sheetRows.first()
        val dataRows = sheetRows.drop(1).filter { row -> row.any { it.isNotBlank() } }
        if (dataRows.isEmpty()) {
            return TranslationReviewReport(true, "工作表 '$sheetName' 没有数据行。", emptyList(), 0, 0)
        }

        val batchSize = REVIEW_BATCH_SIZE
        val issues = mutableListOf<String>()
        val summaries = mutableListOf<String>()
        var fixApplied = 0
        var fixFailed = 0

        dataRows.chunked(batchSize).forEachIndexed { batchIndex, batch ->
            // 该批第一行在原表格中的 1-based 行号（+1 跳过表头）
            val startRowNumber = batchIndex * batchSize + 2
            val review: ReviewResult = AITranslator.reviewTranslations(header, batch, startRowNumber, mode)

            summaries.add(review.summary)

            if (mode == "check") {
                review.issues.forEach { issue ->
                    val colName = header.getOrNull(issue.col) ?: "col${issue.col}"
                    issues.add(
                        "行${issue.row} [$colName] 「${issue.current}」→「${issue.suggested}」：${issue.reason}"
                    )
                }
            } else {
                review.fixes.forEach { fix ->
                    if (fix.values.isEmpty()) return@forEach
                    val expectedCols = header.size
                    val adjustedValues: MutableList<String> = when {
                        fix.values.size == expectedCols -> fix.values.toMutableList()
                        fix.values.size == expectedCols + 1 -> {
                            issues.add("行${fix.row}：AI 返回列数比表头多1，已自动去除首元素（疑似行号误入 values）")
                            fix.values.drop(1).toMutableList()
                        }
                        fix.values.size > expectedCols + 1 -> {
                            issues.add("行${fix.row}：AI 返回列数过多(${fix.values.size})，已截断为表头列数($expectedCols)")
                            fix.values.take(expectedCols).toMutableList()
                        }
                        else -> {
                            issues.add("行${fix.row}：AI 返回列数不足(${fix.values.size})，已补空至表头列数($expectedCols)")
                            (fix.values + List(expectedCols - fix.values.size) { "" }).toMutableList()
                        }
                    }
                    // 确保 key 列(第一列)与原值一致，AI 不应修改 key
                    val batchIdx = fix.row - startRowNumber
                    if (batchIdx in batch.indices) {
                        val originalKey = batch[batchIdx].firstOrNull().orEmpty()
                        if (originalKey.isNotBlank() && adjustedValues.isNotEmpty() && adjustedValues[0] != originalKey) {
                            issues.add("行${fix.row}：AI 修改了 key 列，已恢复原值「$originalKey」")
                            adjustedValues[0] = originalKey
                        }
                    }
                    val updateResult = SheetsManager.updateRow(
                        project, spreadsheetId, sheetName, fix.row, adjustedValues
                    )
                    if (updateResult.isSuccess) fixApplied++ else fixFailed++
                }
                // fix 模式下也把问题原因记入清单（若有 issues 字段）
                review.issues.forEach { issue ->
                    val colName = header.getOrNull(issue.col) ?: "col${issue.col}"
                    issues.add(
                        "行${issue.row} [$colName] 「${issue.current}」→「${issue.suggested}」：${issue.reason}"
                    )
                }
            }
        }

        val totalChecked = dataRows.size
        val summary = buildString {
            append(if (mode == "fix") "修正完成。" else "检查完成。")
            append("共处理 $totalChecked 行翻译。")
            if (issues.isNotEmpty()) {
                append("发现 ${issues.size} 处问题。")
            } else {
                append(if (mode == "fix") "未发现需要修正的问题。" else "未发现翻译问题。")
            }
            // 附上各批 AI 返回的 summary 片段（去重去空）
            val mergedSummaries = summaries.filter { it.isNotBlank() }.distinct()
            if (mergedSummaries.isNotEmpty()) {
                append("\n批次汇总：")
                mergedSummaries.forEach { append("\n- $it") }
            }
        }

        return TranslationReviewReport(true, summary, issues, fixApplied, fixFailed)
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
        chatInput = ""
        sendChatMessage(text)
    }

    private fun quickSend(text: String) {
        if (text.isBlank() || chatSending) return
        sendChatMessage(text.trim())
    }

    private fun sendChatMessage(text: String) {
        val askToolCallId = pendingAskUserToolCallId
        if (askToolCallId != null) {
            // 这是对 ask_user(无 options 场景)的回复,作为 tool_result 回传给 AI,
            // 而非新增 user 消息,否则会破坏 tool_use/tool_result 的配对语义。
            pendingAskUserToolCallId = null
            chatMessages.add(ChatMessage(role = "tool", content = text, toolCallId = askToolCallId))
        } else {
            chatMessages.add(ChatMessage(role = "user", content = text))
        }
        toolDocLoadCount = 0
        askUserCallCount = 0
        stopRequested = false
        continueChatWithAi()
    }

    /**
     * 用户点击「停止」按钮:标记停止请求,让当前/下一轮 tool loop 检测到后立即终止。
     * 由于 AI HTTP 请求是阻塞的,正在进行的网络请求会等其完成,但其返回的 tool_call 不会再驱动新轮次。
     * 不可重复点击(无副作用但也无意义)。
     *
     * 同时也覆盖 ask_user 等待用户响应的场景:此时 chatSending=false 但 pendingAskUserToolCallId
     * 非空,需要补全 tool_result 以满足 Anthropic 协议要求,否则下次发送新消息时会 HTTP 400。
     */
    private fun stopChat() {
        val hasPendingAsk = pendingAskUserToolCallId != null
        if (!chatSending && !hasPendingAsk) return
        stopRequested = true
        chatSending = false
        if (hasPendingAsk) {
            fillMissingToolResults("[已取消] 用户点击了停止按钮")
            pendingAskUserToolCallId = null
            chatMessages.add(ChatMessage(role = "assistant", content = "⏹ 已停止生成。"))
        }
        showToast("已停止生成")
    }

    /**
     * 打开「AI 上下文」弹窗:按需构造当前上下文(调用 buildChatContext),
     * 并尝试 pretty-print 成多行 JSON,方便用户直接查看 AI 真实收到的字段。
     */
    private fun openContextPopup() {
        val raw = buildChatContext()
        chatContextText = runCatching {
            val element = com.google.gson.JsonParser.parseString(raw)
            com.google.gson.GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(element)
        }.getOrElse { raw }
        showContextPopup = true
    }

    private fun closeContextPopup() {
        showContextPopup = false
    }

    /**
     * 公共 AI 调用入口。供 sendChatMessage 和 onChatOptionClick 共用。
     * 调用前应已把用户消息(或 tool result)加入 chatMessages。
     *
     * 关键设计:采用原生 function calling 协议后,AI 必须调用 task_complete 才能终止。
     * runToolLoop 在后台持续驱动 AI 调用工具,直到达成 task_complete 或达到 MAX_ITERATIONS。
     */
    private fun continueChatWithAi() {
        chatSending = true
        val context = buildChatContext()
        continueToolLoopInBackground(context, iteration = 0)
    }

    private fun continueToolLoopInBackground(context: String, iteration: Int) {
        ApplicationManager.getApplication().executeOnPooledThread {
            runToolLoop(context, iteration)
        }
    }

    /**
     * 工具调用主循环。后台线程上执行:
     * 1. 调一次 AI
     * 2. 切回 EDT,记入 assistant 消息(含 tool_calls)
     * 3. processAiReply 处理 actions
     * 4. 若仍有未完成动作,继续循环
     *
     * 终止条件:AI 调用 task_complete / 用户主动停止 / 达到 MAX_ITERATIONS。
     * 停止请求检查:每轮开头与 AI 调用返回后各检查一次 stopRequested,
     * 命中时立即结束(正在进行的网络请求会等其完成,但其返回结果会被丢弃)。
     */
    private fun runToolLoop(context: String, iteration: Int) {
        if (stopRequested) {
            // 在 AI 调用之前就已停止(用户在迭代间隙点击了 Stop),不发起任何请求
            handleStoppedByUser()
            return
        }

        if (iteration >= MAX_ITERATIONS) {
            SwingUtilities.invokeLater {
                chatMessages.add(
                    ChatMessage(
                        role = "assistant",
                        content = "已达到最大工具调用轮数($MAX_ITERATIONS),强制结束。请检查任务后重试。"
                    )
                )
                chatSending = false
            }
            return
        }

        // 兜底安全网:在 AI 调用前扫描 chatMessages,补齐所有尚未配对的 tool_result。
        // 这一步能挡住所有前序流程(用户主动操作 / 后台线程竞态 / Stop 按钮等)
        // 引入的悬挂 tool_use,确保每次 API 请求都满足 Anthropic 协议要求
        // (tool_use 必须紧跟 tool_result,否则 HTTP 400)。
        try {
            SwingUtilities.invokeAndWait {
                fillMissingToolResults("[自动补全] 上一轮未配对的工具调用")
            }
        } catch (e: Exception) {
            // invokeAndWait 异常不应阻塞 AI 调用,继续走原流程
        }

        val reply: AiReply
        try {
            reply = AITranslator.chat(chatMessages.toList(), context)
        } catch (e: Exception) {
            SwingUtilities.invokeLater {
                chatMessages.add(
                    ChatMessage(
                        role = "assistant",
                        content = "AI 请求失败：${e.message ?: "unknown"}"
                    )
                )
                chatSending = false
            }
            return
        }

        // 用户在 AI 网络请求期间点击了 Stop:丢弃本次响应,不再处理 tool_calls,
        // 避免已请求到的 tool_use 在下次发送时因缺少 tool_result 而触发 Anthropic 报错。
        if (stopRequested) {
            handleStoppedByUser()
            return
        }

        SwingUtilities.invokeLater {
            // 1. 记入 assistant 消息,带 tool_calls(同时包含已解析与未解析的全部 tool_use)
            //    若 AI 只返回 tool_calls 没文字,填充占位文案,避免空气泡
            val assistantContent = when {
                reply.reply.isNotBlank() -> reply.reply
                reply.toolCalls.isNotEmpty() -> "执行操作: ${summarizeToolCalls(reply.toolCalls)}"
                else -> ""
            }
            chatMessages.add(
                ChatMessage(
                    role = "assistant",
                    content = assistantContent,
                    toolCalls = reply.toolCalls
                )
            )

            // 2. 为「解析失败」的 tool_use 补 tool_result(防止 Anthropic 报
            //    "tool_use.id 'xxx' was found without a corresponding tool_result
            //    block immediately after")。这些 tool_use 占着 assistant 的位置,
            //    但 actions 中没有对应项,会被 processAiReply 漏掉,必须在此处补齐。
            reply.failedToolCalls.forEach { failed ->
                chatMessages.add(
                    ChatMessage(
                        role = "tool",
                        content = "[工具执行结果] 类型:unknown(${failed.name}) 状态:解析失败 " +
                            "信息:该工具调用的参数无法解析或工具名未注册。请检查调用格式后重试。",
                        toolCallId = failed.id
                    )
                )
            }

            // 3. 建立 action → toolCallId 的下标映射。
            //    parseAiReply 已保证 reply.actions 与 reply.toolCalls 严格 1:1 对齐,
            //    这里的下标取 id 才是正确的(之前 mapNotNull 会丢项导致错位)。
            val actionToolCallIds = reply.actions.indices.map { i ->
                reply.toolCalls.getOrNull(i)?.id.orEmpty()
            }

            // 4. 分发处理
            processAiReply(reply, actionToolCallIds, context, iteration)
        }
    }

    /**
     * 处理一次 AI 回复:按优先级分发到 task_complete / ask_user / load_tool_doc / insert / sheets 各分支。
     * 各分支处理完后再决定是否继续 tool loop。
     *
     * 关键设计:统一用 [ActionWithToolCall] 配对列表传递 action 与其 tool_call_id,
     * 杜绝「过滤后下标错位」的历史 bug。写操作(insert/update)还要先做模块一致性校验,
     * 防止 AI 一次回合内把不同 strings 插入到不同模块。
     */
    private fun processAiReply(
        reply: AiReply,
        actionToolCallIds: List<String>,
        context: String,
        iteration: Int
    ) {
        // 构造精确配对:每个 action 携带自己的 tool_call_id,不再依赖下标
        val pairs: List<ActionWithToolCall> = reply.actions.mapIndexedNotNull { i, action ->
            val toolCallId = actionToolCallIds.getOrNull(i).orEmpty()
            if (toolCallId.isEmpty()) null else ActionWithToolCall(action, toolCallId)
        }
        // 关键:跟踪本回合尚未被处理的 pairs。每个优先级处理完自己的子集后,
        // 必须为剩余 pairs 补上「已跳过」tool_result。否则 Anthropic 协议下,
        // assistant 消息中的 tool_use 在下一轮会因「缺少 tool_result」而 HTTP 400:
        //   tool_use blocks must be immediately followed by tool_result blocks
        //   in the next user message
        // OpenAI 协议虽然容错,但同样会污染上下文;统一补 skipped 最稳妥。
        val unprocessed = pairs.toMutableList()

        // Priority 1: task_complete 终止对话
        val taskEntry = unprocessed.firstOrNull { it.action is AiAction.TaskComplete }
        if (taskEntry != null) {
            unprocessed.remove(taskEntry)
            handleTaskComplete(taskEntry.action as AiAction.TaskComplete)
            // task_complete 与其它 tool_use 同时返回时,其它 tool_use 视为被取消
            addSkippedToolResults(unprocessed, "因 task_complete 终止对话而跳过")
            return
        }

        // Priority 2: ask_user
        val askUserEntry = unprocessed.firstOrNull { it.action is AiAction.AskUser }
        if (askUserEntry != null) {
            unprocessed.remove(askUserEntry)
            val askAction = askUserEntry.action as AiAction.AskUser
            val askToolCallId = askUserEntry.toolCallId.takeIf { it.isNotEmpty() }

            // 安全网:限制单轮内 ask_user 连续调用次数,防止 AI 反复追问形成死循环。
            askUserCallCount++
            if (askUserCallCount > MAX_ASK_USER_CALLS) {
                if (askToolCallId != null) {
                    chatMessages.add(
                        ChatMessage(
                            role = "tool",
                            content = "[已取消] ask_user 调用次数已达上限($MAX_ASK_USER_CALLS 次),系统为防止死循环自动终止。" +
                                "请基于已有信息完成任务(task_complete),或直接采取合理操作。",
                            toolCallId = askToolCallId
                        )
                    )
                }
                addSkippedToolResults(
                    unprocessed,
                    "因 ask_user 调用次数超限被强制终止"
                )
                chatMessages.add(
                    ChatMessage(
                        role = "assistant",
                        content = "⏹ ask_user 调用次数已达上限($MAX_ASK_USER_CALLS 次),已自动终止。请基于已有信息继续。"
                    )
                )
                chatSending = false
                pendingAskUserToolCallId = null
                return
            }

            if (askAction.options.isNotEmpty()) {
                // 带 options:挂到 assistant 消息渲染按钮,记录 toolCallId,等待用户点击
                chatMessages[chatMessages.lastIndex] =
                    chatMessages.last().copy(options = askAction.options)
            } else {
                // 无 options:用 toast 通知用户问题内容,等待用户在输入框中回复
                showToast(askAction.question)
            }
            // 无论是否带 options,都暂停 loop 等待用户响应。
            // 修复:之前无 options 时自动回传「已向用户显示问题,无需回复」并继续 loop,
            //      导致 AI 反复调用 ask_user 而用户根本没机会回复,形成死循环。
            pendingAskUserToolCallId = askToolCallId
            addSkippedToolResults(
                unprocessed,
                "因 ask_user 等待用户响应而跳过(用户点击选项或发送消息后 AI 可继续)"
            )
            chatSending = false
            return
        }

        // Priority 3: load_tool_doc
        val loadDocEntries = unprocessed.filter { it.action is AiAction.LoadToolDoc }
        if (loadDocEntries.isNotEmpty()) {
            unprocessed.removeAll(loadDocEntries)
            handleLoadToolDoc(loadDocEntries, context, iteration)
            addSkippedToolResults(unprocessed, "因 load_tool_doc 加载文档而跳过")
            return
        }

        // Priority 4-5: strings.xml 写操作(insert_strings / update_string / delete_string)统一做模块一致性校验
        val writeEntries = unprocessed.filter {
            it.action is AiAction.InsertStrings ||
                it.action is AiAction.UpdateString ||
                it.action is AiAction.DeleteString
        }
        if (writeEntries.isNotEmpty()) {
            val conflict = detectWriteModuleConflict(writeEntries)
            if (conflict != null) {
                unprocessed.removeAll(writeEntries)
                // 跨模块冲突:整批拒绝,把错误回传给 AI 让其修正
                rejectWriteModuleConflict(writeEntries, conflict, context, iteration)
                addSkippedToolResults(
                    unprocessed,
                    "因 strings 写动作跨模块冲突被拒,本批其它 action 一起跳过"
                )
                return
            }
        }

        // Priority 4: query_keys / read_string / update_string / delete_string / find_keys_by_text(strings.xml 主动操作能力)
        // 注意:update_string / delete_string 已被上面的模块一致性校验拦截(若有问题就 reject),此处仅含合法动作
        val stringReadEntries = unprocessed.filter {
            it.action is AiAction.QueryKeys ||
                it.action is AiAction.ReadString ||
                it.action is AiAction.FindKeysByText
        }
        val stringWriteEntries = unprocessed.filter {
            it.action is AiAction.UpdateString || it.action is AiAction.DeleteString
        }
        val stringsEntries = stringReadEntries + stringWriteEntries
        if (stringsEntries.isNotEmpty()) {
            unprocessed.removeAll(stringsEntries)
            executeStringsOps(stringsEntries, context, iteration)
            addSkippedToolResults(unprocessed, "因已执行 strings.xml 操作而跳过")
            return
        }

        // Priority 5: insert_strings
        val insertEntries = unprocessed.filter { it.action is AiAction.InsertStrings }
        if (insertEntries.isNotEmpty()) {
            unprocessed.removeAll(insertEntries)
            executeInsertActions(insertEntries, context, iteration)
            addSkippedToolResults(unprocessed, "因已执行 insert_strings 而跳过")
            return
        }

        // Priority 6: sheets_operation / find_rows_by_text
        val sheetsEntries = unprocessed.filter {
            it.action is AiAction.SheetsOperation || it.action is AiAction.FindRowsByText
        }
        if (sheetsEntries.isNotEmpty()) {
            unprocessed.removeAll(sheetsEntries)
            executeSheetsActions(sheetsEntries, context, iteration)
            addSkippedToolResults(unprocessed, "因已执行 sheets_operation 而跳过")
            return
        }

        // 没有任何可执行 tool call:AI 只是在说,等用户输入
        addSkippedToolResults(unprocessed, "本轮 AI 未调用可识别的工具")
        chatSending = false
    }

    /**
     * 为指定 [unprocessedPairs] 中的每个 action 添加一条「已跳过」的 tool_result,
     * 用于在 Anthropic / OpenAI 协议下满足「每个 tool_use 必须紧跟一个 tool_result」的要求。
     * 没有 tool_result 的 tool_use 会让下一轮 API 调用直接返回 HTTP 400。
     *
     * 调用方应仅在确认这些 action 不会在本轮被执行时调用;已经发了「成功/失败」tool_result 的不必重复。
     */
    private fun addSkippedToolResults(
        unprocessedPairs: List<ActionWithToolCall>,
        reason: String
    ) {
        unprocessedPairs.forEach { (action, toolCallId) ->
            if (toolCallId.isEmpty()) return@forEach
            val actionLabel = when (action) {
                is AiAction.InsertStrings -> "insert_strings"
                is AiAction.UpdateString -> "update_string"
                is AiAction.DeleteString -> "delete_string"
                is AiAction.QueryKeys -> "query_keys"
                is AiAction.ReadString -> "read_string"
                is AiAction.FindKeysByText -> "find_keys_by_text"
                is AiAction.FindRowsByText -> "find_rows_by_text"
                is AiAction.AskUser -> "ask_user"
                is AiAction.LoadToolDoc -> "load_tool_doc"
                is AiAction.SheetsOperation -> "sheets_operation(${action.operation.name.lowercase()})"
                is AiAction.TaskComplete -> "task_complete"
            }
            chatMessages.add(
                ChatMessage(
                    role = "tool",
                    content = "[工具执行结果] 类型:$actionLabel 状态:已跳过 信息:$reason。如需执行,请在下一轮重新调用。",
                    toolCallId = toolCallId
                )
            )
        }
    }

    /**
     * 检测 write actions(insert_strings + update_string + delete_string)是否指定了不同模块。
     * @return 冲突描述(列出所有显式指定的 module);若全部一致或仅有 0~1 个显式 module,返回 null
     */
    private fun detectWriteModuleConflict(
        writeEntries: List<ActionWithToolCall>
    ): String? {
        val explicitModules = writeEntries
            .mapNotNull { entry ->
                val m = when (val action = entry.action) {
                    is AiAction.InsertStrings -> action.module
                    is AiAction.UpdateString -> action.module
                    is AiAction.DeleteString -> action.module
                    else -> null
                }?.trim()?.takeIf { it.isNotEmpty() }
                normalizeExplicitWriteModule(m)
            }
            .distinct()
        return if (explicitModules.size > 1) explicitModules.joinToString(", ") else null
    }

    private fun normalizeExplicitWriteModule(moduleName: String?): String? {
        val candidate = moduleName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (candidate == ContextManager.contextInfo?.projectName) return null
        return ContextManager.resolveDisplayModuleName(project, candidate) ?: candidate
    }

    /**
     * 整批拒绝跨模块的 write actions,给每个 action 发错误 tool_result。
     * AI 会在下一轮看到错误并修正(统一 module 或全部省略 module 让系统用 currentModule)。
     */
    private fun rejectWriteModuleConflict(
        writeEntries: List<ActionWithToolCall>,
        explicitModulesCsv: String,
        context: String,
        iteration: Int
    ) {
        val errorMsg = buildString {
            append("[工具执行异常] 跨模块冲突:本轮内 insert_strings/update_string/delete_string 动作指定了多个不同 module(")
            append(explicitModulesCsv)
            append(")。同一 AI 回合内的所有字符串写入必须在同一模块。")
            append("请重新组织 actions:全部省略 module 参数(系统使用 currentModule),")
            append("或全部显式指定同一 module。若确实需要写入多个模块,请拆成多个 AI 回合。")
        }
        chatMessages.add(
            ChatMessage(role = "tool", content = errorMsg, toolCallId = writeEntries.first().toolCallId)
        )
        // 其余 action 同样发错误,避免协议错位
        writeEntries.drop(1).forEach { entry ->
            chatMessages.add(
                ChatMessage(role = "tool", content = errorMsg, toolCallId = entry.toolCallId)
            )
        }
        continueToolLoopInBackground(context, iteration + 1)
    }

    private fun handleTaskComplete(complete: AiAction.TaskComplete) {
        val icon = when (complete.status.lowercase()) {
            "success" -> "✅"
            "partial" -> "⚠️"
            "failed" -> "❌"
            else -> "ℹ️"
        }
        val text = buildString {
            append(icon).append(' ').append(complete.summary)
            if (!complete.notes.isNullOrBlank()) {
                append("\n\n").append(complete.notes)
            }
        }
        chatMessages.add(ChatMessage(role = "assistant", content = text))
        chatSending = false
    }

    /**
     * 用户在 tool loop 中点击「停止」后的统一收尾:
     * - 追加一条 assistant 消息提示已停止
     * - 重置 stopRequested,以便下一轮用户发送新消息时重新进入循环
     * - chatSending 已由 stopChat() 设为 false,这里不再重复设置
     */
    private fun handleStoppedByUser() {
        SwingUtilities.invokeLater {
            // 防御性:在添加「已停止」消息前,先把对话历史中所有尚未配对的 tool_use
            // 补上「已取消」tool_result,避免下一轮 AI 请求时 Anthropic 报:
            //   tool_use.id 'xxx' was found without a corresponding tool_result
            //   block immediately after
            // 这覆盖了 ask_user 等待用户点选项、用户却点 Stop 等导致 tool_use 悬挂的场景。
            fillMissingToolResults("[已取消] 用户点击了停止按钮,该工具调用未执行。如需执行,请在下一轮重新发起。")
            chatMessages.add(ChatMessage(role = "assistant", content = "⏹ 已停止生成。"))
            stopRequested = false
        }
    }

    /**
     * 扫描 chatMessages,找出所有尚未配对的 tool_use 块(assistant.toolCalls 中存在、
     * 但 chatMessages 中没有对应 toolCallId 的 tool 消息),为它们补一条 tool_result
     * 以满足 Anthropic 协议要求(每个 tool_use 必须紧跟 tool_result,否则 HTTP 400)。
     *
     * 关键:合成 tool_result 必须**插入到产生 tool_use 的 assistant 消息所在 block 内**,
     * 而且要排在 block 内所有非 tool_result 消息之前,否则会被 user 文本消息"截胡"
     * —— 那时 tool_result 落到 user 消息之后,Anthropic 仍会拒绝
     * (tool_use blocks must be immediately followed by tool_result blocks in the next user message)。
     *
     * 插入位置算法:对每个含 tool_uses 的 assistant 消息,确定其 block(到下一个 assistant
     * 或 chatMessages 末尾),在 block 内**第一个非 tool_result 消息之前**(若 block 全部是
     * tool_result 则在 block 末尾)插入合成 tool_result;按 insertAt 降序插入以避免下标错位。
     *
     * @param reason 写入 tool_result content 的说明,告诉 AI 为什么这个 action 没被执行
     */
    private fun fillMissingToolResults(reason: String) {
        if (chatMessages.isEmpty()) return

        // 1) 收集所有 (insertAt, toolResultMessage),稍后按 insertAt 降序插入
        val toInsert = mutableListOf<Pair<Int, ChatMessage>>()

        // 2) 助手查名字表(toolCallId -> 工具名),用于合成 tool_result 的内容描述
        val nameById = mutableMapOf<String, String>()
        chatMessages.forEach { msg ->
            if (msg.role == "assistant") {
                msg.toolCalls.forEach { tc -> nameById.putIfAbsent(tc.id, tc.name) }
            }
        }

        // 3) 遍历每个含 tool_uses 的 assistant,计算其 block 与插入点
        chatMessages.forEachIndexed { idx, msg ->
            if (msg.role != "assistant" || msg.toolCalls.isEmpty()) return@forEachIndexed

            // block = [idx+1, endOfBlock),endOfBlock 是下一个 assistant 或 chatMessages 末尾
            var endOfBlock = idx + 1
            while (endOfBlock < chatMessages.size && chatMessages[endOfBlock].role != "assistant") {
                endOfBlock++
            }

            // 在 block 内找到所有已有的 tool_result(tool 消息 + user 带 toolCallId)
            val pairedIds = mutableSetOf<String>()
            for (i in (idx + 1) until endOfBlock) {
                val m = chatMessages[i]
                if (m.role == "tool" || (m.role == "user" && m.toolCallId != null)) {
                    m.toolCallId?.let { pairedIds.add(it) }
                }
            }

            // 找出本 assistant 的未配对 tool_use
            val unpaired = msg.toolCalls.filter { it.id !in pairedIds }
            if (unpaired.isEmpty()) return@forEachIndexed

            // 在 block 内找第一个非 tool_result 的位置(没有则用 block 末尾)
            var insertAt = endOfBlock
            for (i in (idx + 1) until endOfBlock) {
                val m = chatMessages[i]
                val isToolResult = m.role == "tool" || (m.role == "user" && m.toolCallId != null)
                if (!isToolResult) {
                    insertAt = i
                    break
                }
            }

            unpaired.forEach { tc ->
                toInsert.add(
                    insertAt to ChatMessage(
                        role = "tool",
                        content = "[工具执行结果] 类型:${tc.name} 状态:已取消 信息:$reason",
                        toolCallId = tc.id
                    )
                )
            }
        }

        // 4) 按 insertAt 降序插入,避免下标位移影响后续插入位置
        toInsert.sortedByDescending { it.first }.forEach { (insertAt, message) ->
            chatMessages.add(insertAt, message)
        }
    }

    /**
     * 处理 load_tool_doc:为每个 load_tool_doc 调用添加对应的 tool result 注入文档,
     * 然后继续 tool loop 让 AI 返回实际执行动作。
     */
    private fun handleLoadToolDoc(
        entries: List<ActionWithToolCall>,
        context: String,
        iteration: Int
    ) {
        if (toolDocLoadCount >= MAX_TOOL_DOC_LOADS) {
            chatMessages.add(
                ChatMessage(
                    role = "assistant",
                    content = "工具文档加载次数已达上限($MAX_TOOL_DOC_LOADS 次),请直接执行操作或向用户说明情况。"
                )
            )
            chatSending = false
            return
        }
        toolDocLoadCount++

        val requestedTools = entries.mapNotNull { (action, _) ->
            (action as? AiAction.LoadToolDoc)?.tool
        }.distinct()
        val docsToInject = requestedTools.mapNotNull { tool ->
            AITranslator.getToolDoc(tool)?.let { doc -> tool to doc }
        }

        val summary = if (docsToInject.isEmpty()) {
            val available = AITranslator.availableToolDocs().joinToString(", ")
            "[工具文档加载失败] 请求的工具名不存在。可用工具:$available。" +
                "请用正确的工具名重新请求,或直接返回可执行的工具调用。"
        } else {
            buildString {
                docsToInject.forEach { (_, doc) ->
                    appendLine(doc)
                    appendLine()
                }
                appendLine("以上是你请求加载的工具文档。请据此直接返回正确的工具调用执行操作,不要再请求加载文档。")
            }
        }

        // 为每个 load_tool_doc 调用添加对应的 tool result(一对一关联 toolCallId)
        entries.forEach { (_, toolCallId) ->
            if (toolCallId.isNotEmpty()) {
                chatMessages.add(
                    ChatMessage(role = "tool", content = summary, toolCallId = toolCallId)
                )
            }
        }

        // 继续 tool loop:让 AI 拿到文档后返回实际工具调用
        continueToolLoopInBackground(context, iteration + 1)
    }

    /**
     * 统一执行 query_keys / read_string / update_string / find_keys_by_text 四类 strings.xml 操作。
     * 每个 entry 自带 toolCallId,内部按 entry 解构后独立生成 tool result。
     * 模块一致性已在 processAiReply 校验,此处直接执行。
     */
    private fun executeStringsOps(
        entries: List<ActionWithToolCall>,
        context: String,
        iteration: Int
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val pendingResults = mutableListOf<Pair<String, String>>()
                entries.forEach { (action, toolCallId) ->
                    val resultText = when (action) {
                        is AiAction.QueryKeys -> runQueryKeys(action)
                        is AiAction.ReadString -> runReadString(action)
                        is AiAction.UpdateString -> runUpdateString(action)
                        is AiAction.DeleteString -> runDeleteString(action)
                        is AiAction.FindKeysByText -> runFindKeysByText(action)
                        else -> return@forEach
                    }
                    pendingResults.add(toolCallId to resultText)
                }
                SwingUtilities.invokeLater {
                    pendingResults.forEach { (toolCallId, content) ->
                        chatMessages.add(
                            ChatMessage(role = "tool", content = content, toolCallId = toolCallId)
                        )
                    }
                    continueToolLoopInBackground(context, iteration + 1)
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    chatMessages.add(
                        ChatMessage(
                            role = "assistant",
                            content = "strings.xml 操作执行异常:${e.message ?: "unknown"}"
                        )
                    )
                    chatSending = false
                }
            }
        }
    }

    private fun runQueryKeys(action: AiAction.QueryKeys): String {
        val moduleName = resolveTargetModule(
            action.module,
            ContextManager.contextInfo?.currentModule?.moduleName,
            ContextManager.contextInfo?.moduleWithMostLines?.moduleName
        ) ?: return "[工具执行结果] 类型:query_keys 状态:失败 信息:未指定目标模块且无 currentModule"

        val results: List<KeySearchResult>
        val totalMatched: Int
        if (action.pattern.isNullOrBlank()) {
            val keys = StringsService.listKeys(project, moduleName, action.limit ?: 50, action.offset ?: 0)
            results = keys.map { KeySearchResult(it, emptyMap(), "") }
            totalMatched = keys.size
        } else {
            results = StringsService.searchKeys(
                project = project,
                moduleName = moduleName,
                pattern = action.pattern,
                limit = action.limit ?: 50,
                offset = action.offset ?: 0,
                includeTranslations = action.includeTranslations
            )
            totalMatched = results.size
        }
        return buildString {
            append("[工具执行结果] 类型:query_keys 模块:").append(moduleName)
            append(" pattern:").append(action.pattern ?: "(无)")
            append(" 状态:成功 命中:").append(totalMatched)
            if (results.isEmpty()) {
                appendLine("\n未找到匹配的 key。")
            } else {
                appendLine()
                results.forEachIndexed { idx, r ->
                    append(idx + 1).append(". ").append(r.key)
                    if (action.includeTranslations && r.translations.isNotEmpty()) {
                        appendLine()
                        r.translations.forEach { (lang, text) ->
                            append("    ").append(lang).append(": ")
                            appendLine(if (text.length > 80) text.take(80) + "…" else text)
                        }
                    } else {
                        appendLine()
                    }
                }
                if (action.pattern.isNullOrBlank() && totalMatched >= (action.limit ?: 50)) {
                    appendLine("… 可能还有更多 key,可用 offset 分页。")
                }
            }
        }
    }

    private fun runReadString(action: AiAction.ReadString): String {
        val moduleName = resolveTargetModule(
            action.module,
            ContextManager.contextInfo?.currentModule?.moduleName,
            ContextManager.contextInfo?.moduleWithMostLines?.moduleName
        ) ?: return "[工具执行结果] 类型:read_string 状态:失败 信息:未指定目标模块"
        val result = StringsService.readKey(project, moduleName, action.name)
            ?: return "[工具执行结果] 类型:read_string key:${action.name} 模块:$moduleName 状态:失败 信息:模块不存在或没有 strings.xml"
        if (result.translations.isEmpty()) {
            return "[工具执行结果] 类型:read_string key:${action.name} 模块:$moduleName 状态:成功 信息:该 key 不存在"
        }
        return buildString {
            append("[工具执行结果] 类型:read_string key:").append(result.key)
            append(" 模块:").append(moduleName)
            append(" 状态:成功")
            appendLine()
            result.translations.forEach { (lang, text) ->
                append("  ").append(lang).append(": ")
                appendLine(if (text.isEmpty()) "(空)" else text)
            }
        }
    }

    private fun runUpdateString(action: AiAction.UpdateString): String {
        val moduleName = resolveTargetModule(
            action.module,
            ContextManager.contextInfo?.currentModule?.moduleName,
            ContextManager.contextInfo?.moduleWithMostLines?.moduleName
        ) ?: return "[工具执行结果] 类型:update_string 状态:失败 信息:未指定目标模块"
        val results = StringsService.updateKey(project, moduleName, action.name, action.translations)
        val successCount = results.values.count { it == "成功" }
        val totalTarget = action.translations.size
        val updated = results.size
        val skipped = totalTarget - updated
        return buildString {
            append("[工具执行结果] 类型:update_string key:").append(action.name)
            append(" 模块:").append(moduleName)
            append(" 状态:").append(if (successCount == updated && updated == totalTarget) "成功" else "部分失败")
            append(" 成功:").append(successCount).append('/').append(totalTarget)
            if (skipped > 0) append(" 跳过(无该语言文件):").append(skipped)
            appendLine()
            results.forEach { (lang, msg) ->
                append("  ").append(lang).append(": ").appendLine(msg)
            }
        }
    }

    private fun runDeleteString(action: AiAction.DeleteString): String {
        val moduleName = resolveTargetModule(
            action.module,
            ContextManager.contextInfo?.currentModule?.moduleName,
            ContextManager.contextInfo?.moduleWithMostLines?.moduleName
        ) ?: return "[工具执行结果] 类型:delete_string 状态:失败 信息:未指定目标模块"
        val results = StringsService.deleteKey(project, moduleName, action.name, action.languages)
        val successCount = results.values.count { it == "成功" }
        val notFoundCount = results.values.count { it == "未找到该 key" }
        val totalTarget = results.size
        val scope = if (action.languages.isEmpty()) "all-languages" else action.languages.joinToString(",")
        // 同步刷新 UI:若该 key 正在 keyEntries 中,以最新翻译内容覆盖
        SwingUtilities.invokeLater { refreshKeyEntryAfterDelete(moduleName, action.name) }
        return buildString {
            append("[工具执行结果] 类型:delete_string key:").append(action.name)
            append(" 模块:").append(moduleName)
            append(" 范围:").append(scope)
            append(" 状态:").append(if (successCount == totalTarget) "成功" else "部分失败")
            append(" 成功:").append(successCount).append('/').append(totalTarget)
            if (notFoundCount > 0) append(" 未找到:").append(notFoundCount)
            appendLine()
            results.forEach { (lang, msg) ->
                append("  ").append(lang).append(": ").appendLine(msg)
            }
        }
    }

    /**
     * 删除后刷新 UI:重新扫描目标 key 在模块中的最新翻译,
     * 若 keyEntries 中包含该 key 则就地替换(并按需刷新当前选中行的 rows)。
     */
    private fun refreshKeyEntryAfterDelete(moduleName: String, key: String) {
        val idx = keyEntries.indexOfFirst { it.key == key }
        if (idx < 0) return
        val updated = ContextManager.scanModuleForKey(project, moduleName, key)
        keyEntries[idx] = KeyedStringsInfo(key, "", updated)
        if (selectedKeyIndex == idx) {
            updateRowsForSelectedKey()
        }
    }

    private fun runFindKeysByText(action: AiAction.FindKeysByText): String {
        val matchType = mapToServiceMatchType(action.matchType)
        val results: List<KeyTextSearchResult> = try {
            StringsService.findKeysByText(
                project = project,
                text = action.text,
                moduleName = action.module,
                language = action.language,
                matchType = matchType,
                caseSensitive = action.caseSensitive,
                limit = action.limit
            )
        } catch (e: Exception) {
            return "[工具执行结果] 类型:find_keys_by_text 状态:失败 信息:${e.message ?: "unknown"}"
        }
        if (results.isEmpty()) {
            val scope = buildString {
                append("text:\"").append(action.text).append("\"")
                if (action.module != null) append(" module:").append(action.module)
                if (action.language != null) append(" language:").append(action.language)
                append(" match:").append(action.matchType.name.lowercase())
            }
            return "[工具执行结果] 类型:find_keys_by_text 状态:成功 命中:0 范围:$scope 未找到匹配 key"
        }
        return buildString {
            append("[工具执行结果] 类型:find_keys_by_text 状态:成功 命中:").append(results.size)
            append(" match:").append(action.matchType.name.lowercase())
            appendLine()
            results.forEachIndexed { idx, r ->
                append(idx + 1).append(". ")
                append("module=").append(r.module)
                append(" lang=").append(r.language)
                append(" key=").append(r.key)
                append(" text=\"").append(truncateForLog(r.text, 60)).append("\"")
                appendLine()
            }
            if (results.size >= action.limit) {
                appendLine("… 已达返回上限,可能还有更多匹配。")
            }
        }
    }

    private fun mapToServiceMatchType(type: AiAction.TextMatchType): StringsService.TextMatchType {
        return when (type) {
            AiAction.TextMatchType.EXACT -> StringsService.TextMatchType.EXACT
            AiAction.TextMatchType.CONTAINS -> StringsService.TextMatchType.CONTAINS
            AiAction.TextMatchType.REGEX -> StringsService.TextMatchType.REGEX
        }
    }

    private fun truncateForLog(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max) + "…"

    /**
     * 执行 sheets 域动作(SheetsOperation + FindRowsByText)并把每个结果作为 tool result 回传。
     * 在执行前自动检测 append_row 动作是否有重复 key,若有则暂停并询问用户。
     * 每个 entry 自带 toolCallId,内部按 entry 解构后独立生成 tool result。
     */
    private fun executeSheetsActions(
        entries: List<ActionWithToolCall>,
        context: String,
        iteration: Int
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val actions = entries.map { it.action }
                // 仅 SheetsOperation 需要重复 key 检测;FindRowsByText 是只读操作
                val sheetsOps = actions.filterIsInstance<AiAction.SheetsOperation>()
                val appendActions = sheetsOps.filter {
                    it.operation == AiAction.SheetsOperation.Operation.APPEND_ROW
                }
                if (appendActions.isNotEmpty()) {
                    val spreadsheetId = SheetsManager.resolveSpreadsheetId(
                        project, appendActions.first().spreadsheetId
                    )
                    val sheetName = appendActions.first().sheetName
                        ?: SheetsManager.defaultSheetName(project)
                    val newKeys = appendActions.mapNotNull {
                        it.rows?.firstOrNull()?.firstOrNull()?.takeIf { k -> k.isNotBlank() }
                    }
                    if (spreadsheetId.isNotBlank() && newKeys.isNotEmpty()) {
                        val existingKeys = SheetsManager.readSheet(project, spreadsheetId, sheetName)
                            .getOrNull()
                            ?.mapNotNull {
                                it.firstOrNull()?.trim()?.takeIf { k -> k.isNotBlank() }
                            }
                            ?.toSet() ?: emptySet()
                        val duplicates = newKeys.filter { it in existingKeys }.toSet()
                        if (duplicates.isNotEmpty()) {
                            // 重复 key 场景:把 entry 列表投影回 SheetsOperation + 对应 toolCallId
                            val sheetsOpsWithIds = entries
                                .mapNotNull { (action, toolCallId) ->
                                    if (action is AiAction.SheetsOperation) action to toolCallId else null
                                }
                            val pending = PendingSheetsInsert(
                                actions = sheetsOps,
                                actionToolCallIds = sheetsOpsWithIds.map { it.second },
                                duplicateKeys = duplicates,
                                spreadsheetId = spreadsheetId,
                                sheetName = sheetName,
                                context = context,
                                iteration = iteration
                            )
                            SwingUtilities.invokeLater {
                                pendingSheetsInsert = pending
                                chatMessages.add(
                                    ChatMessage(
                                        role = "assistant",
                                        content = "检测到以下 key 已存在于表格中:" +
                                            "${duplicates.joinToString(", ")}。请选择如何处理:",
                                        options = listOf(
                                            "覆盖相同key的内容",
                                            "在列表末尾插入相同的key",
                                            "取消操作"
                                        )
                                    )
                                )
                                chatSending = false
                            }
                            return@executeOnPooledThread
                        }
                    }
                }

                // 准备所有 toolCallId→结果 映射,统一回 EDT
                val pendingResults = mutableListOf<Pair<String, String>>()
                entries.forEach { (action, toolCallId) ->
                    val content = when (action) {
                        is AiAction.SheetsOperation -> {
                            val result = executeSheetsOperationSync(action)
                            buildString {
                                append("[工具执行结果] 操作:${result.operation} 状态:${if (result.success) "成功" else "失败"} 信息:${result.message}")
                                if (result.rowNumber != null) append(" 行号:${result.rowNumber}")
                                if (!result.sheetNames.isNullOrEmpty()) {
                                    append(" 工作表列表:${result.sheetNames.joinToString(", ")}")
                                }
                                if (!result.data.isNullOrEmpty()) {
                                    append("\n数据:")
                                    result.data.forEachIndexed { idx, row ->
                                        append("\n  行${idx + 1}: ").append(row.joinToString(" | "))
                                    }
                                }
                            }
                        }
                        is AiAction.FindRowsByText -> runFindRowsByText(action)
                        else -> return@forEach
                    }
                    pendingResults.add(toolCallId to content)
                }

                SwingUtilities.invokeLater {
                    pendingResults.forEach { (toolCallId, content) ->
                        chatMessages.add(
                            ChatMessage(role = "tool", content = content, toolCallId = toolCallId)
                        )
                    }
                    continueToolLoopInBackground(context, iteration + 1)
                }
            } catch (e: Exception) {
                // 异常兜底:为所有未响应的 tool_call 添加错误 tool result,避免协议错位
                SwingUtilities.invokeLater {
                    entries.forEach { (action, toolCallId) ->
                        if (toolCallId.isNotEmpty()) {
                            val typeLabel = when (action) {
                                is AiAction.SheetsOperation -> action.operation.name
                                is AiAction.FindRowsByText -> "find_rows_by_text"
                                else -> "unknown"
                            }
                            chatMessages.add(
                                ChatMessage(
                                    role = "tool",
                                    content = "[工具执行异常] $typeLabel 失败:${e.message ?: "unknown"}",
                                    toolCallId = toolCallId
                                )
                            )
                        }
                    }
                    chatMessages.add(
                        ChatMessage(
                            role = "assistant",
                            content = "操作执行过程中发生异常,未完成:${e.message ?: "unknown"}"
                        )
                    )
                    chatSending = false
                }
            }
        }
    }

    /**
     * 重复 key 插入的用户选择处理(系统发起的询问,不是 AI 调用的 ask_user 工具)。
     * - 覆盖:将重复 key 的 append_row 转为 update_row(用 search 定位行号),非重复的保持 append_row
     * - 末尾插入:原样执行所有 append_row
     * - 取消:不执行,为每个 pending tool_call 添加「用户已取消」的 tool result
     */
    /**
     * 把 SheetsOperation 列表 + 平行的 toolCallId 列表打包成 [ActionWithToolCall] 列表,
     * 供 executeSheetsActions 使用。
     */
    private fun buildSheetsActionEntries(
        actions: List<AiAction.SheetsOperation>,
        toolCallIds: List<String>
    ): List<ActionWithToolCall> {
        return actions.mapIndexed { i, action ->
            ActionWithToolCall(action, toolCallIds.getOrNull(i).orEmpty())
        }.filter { it.toolCallId.isNotEmpty() }
    }

    private fun resolveDuplicateInsert(option: String, pending: PendingSheetsInsert) {
        when {
            option.contains("覆盖") -> {
                chatSending = true
                ApplicationManager.getApplication().executeOnPooledThread {
                    try {
                        val resolvedActions = pending.actions.map { action ->
                            if (action.operation == AiAction.SheetsOperation.Operation.APPEND_ROW) {
                                val key = action.rows?.firstOrNull()?.firstOrNull()
                                if (key != null && key in pending.duplicateKeys) {
                                    val searchResult = SheetsManager.searchRowInSheet(
                                        project, pending.spreadsheetId, pending.sheetName, key
                                    )
                                    searchResult.fold(
                                        onSuccess = { (rowNum, _) ->
                                            action.copy(
                                                operation = AiAction.SheetsOperation.Operation.UPDATE_ROW,
                                                rowNumber = rowNum
                                            )
                                        },
                                        onFailure = { action }
                                    )
                                } else {
                                    action
                                }
                            } else {
                                action
                            }
                        }
                        executeSheetsActions(
                            buildSheetsActionEntries(resolvedActions, pending.actionToolCallIds),
                            pending.context,
                            pending.iteration
                        )
                    } catch (e: Exception) {
                        SwingUtilities.invokeLater {
                            chatMessages.add(
                                ChatMessage(
                                    role = "assistant",
                                    content = "覆盖操作执行异常:${e.message ?: "unknown"}"
                                )
                            )
                            chatSending = false
                        }
                    }
                }
            }
            option.contains("末尾") -> {
                chatSending = true
                ApplicationManager.getApplication().executeOnPooledThread {
                    try {
                        executeSheetsActions(
                            buildSheetsActionEntries(pending.actions, pending.actionToolCallIds),
                            pending.context,
                            pending.iteration
                        )
                    } catch (e: Exception) {
                        SwingUtilities.invokeLater {
                            chatMessages.add(
                                ChatMessage(
                                    role = "assistant",
                                    content = "追加操作执行异常:${e.message ?: "unknown"}"
                                )
                            )
                            chatSending = false
                        }
                    }
                }
            }
            else -> {
                // 取消:为每个 pending tool_call 添加取消 tool result,继续 tool loop 让 AI 知道结果
                SwingUtilities.invokeLater {
                    pending.actions.forEachIndexed { i, action ->
                        val toolCallId = pending.actionToolCallIds.getOrNull(i).orEmpty()
                        if (toolCallId.isNotEmpty()) {
                            chatMessages.add(
                                ChatMessage(
                                    role = "tool",
                                    content = "[用户取消] ${action.operation} 未执行。",
                                    toolCallId = toolCallId
                                )
                            )
                        }
                    }
                    chatMessages.add(ChatMessage(role = "assistant", content = "已取消插入操作。"))
                    continueToolLoopInBackground(pending.context, pending.iteration + 1)
                }
            }
        }
    }

    /**
     * 对话气泡选项按钮点击回调。
     * 优先级:待响应的 ask_user 工具调用 > 系统发起的重复 key 询问 > 兜底普通消息。
     */
    private fun onChatOptionClick(messageIndex: Int, option: String) {
        // 清除该消息的 options 使按钮消失
        if (messageIndex in chatMessages.indices) {
            val msg = chatMessages[messageIndex]
            chatMessages[messageIndex] = msg.copy(options = emptyList())
        }

        // 重置停止标志:用户主动操作意味着继续对话
        stopRequested = false

        // Priority 1:ask_user 工具调用 → 作为 tool result 回传给 AI
        val askToolCallId = pendingAskUserToolCallId
        if (askToolCallId != null) {
            pendingAskUserToolCallId = null
            askUserCallCount = 0
            chatMessages.add(
                ChatMessage(role = "tool", content = option, toolCallId = askToolCallId)
            )
            val context = buildChatContext()
            continueToolLoopInBackground(context, iteration = 0)
            return
        }

        // Priority 2:系统发起的重复 key 询问
        val pending = pendingSheetsInsert
        if (pending != null) {
            pendingSheetsInsert = null
            resolveDuplicateInsert(option, pending)
            return
        }

        // 兜底:作为普通用户消息发回(保持旧逻辑兼容)
        chatMessages.add(ChatMessage(role = "user", content = option))
        toolDocLoadCount = 0
        continueChatWithAi()
    }

    private fun newChat() {
        // 标记停止,防止正在运行的 tool loop 在清空后继续向 chatMessages 追加新消息
        stopRequested = true
        chatMessages.clear()
        chatInput = ""
        chatSending = false
        pendingAskUserToolCallId = null
        askUserCallCount = 0
    }

    private fun buildChatContext(): String {
        val contextInfo = ContextManager.contextInfo ?: return ""
        // 基础集合:优先用用户在表格里选中的语言,否则用 currentModule 的 xmlFiles。
        // 始终把默认英语 "values" 包含进去,避免用户没选英语行时 AI 漏写 English,
        // 进而让 StringsWriter 把 values/strings.xml 写成空文本。
        val baseLanguages = insertStringsManager.languages?.takeIf { it.isNotEmpty() }
            ?: contextInfo.currentModule?.xmlFiles?.map { it.language }
            ?: emptyList()
        val availableLanguages = if (DEFAULT_LANGUAGE in baseLanguages) baseLanguages
        else baseLanguages + DEFAULT_LANGUAGE
        saveCurrentEdits()
        val sheetsSettings = SheetsSettingsService.getInstance(project).state
        val sheetsConfigured = SheetsManager.isConfigured(project)
        val availableSheetNames = sheetsAvailableSheets.map { it.title }.takeIf { it.isNotEmpty() }

        val root = JsonObject().apply {
            add("androidProject", JsonObject().apply {
                addProperty("name", contextInfo.projectName)
                addProperty("note", "Android 工程名,仅用于展示。module 参数必须用 modules[].moduleName,不要传这个 name。")
            })
            add("currentModule", contextInfo.currentModule?.let { moduleToJson(it) })
            add("modules", JsonArray().apply {
                contextInfo.modules.forEach { add(moduleToJson(it)) }
            })
            add("moduleWithMostLines", contextInfo.moduleWithMostLines?.let { moduleToJson(it) })
            add("availableLanguages", JsonArray().apply {
                availableLanguages.forEach { add(it) }
            })
            add("currentKeys", JsonArray().apply {
                keyEntries.forEach { entry ->
                    add(JsonObject().apply {
                        addProperty("key", entry.key)
                        add("translations", JsonObject().apply {
                            entry.stringsInfoList.filter { it.text.isNotEmpty() }.forEach {
                                addProperty(it.language, it.text)
                            }
                        })
                    })
                }
            })
            add("googleSheets", JsonObject().apply {
                addProperty("configured", sheetsConfigured)
                addProperty("defaultSpreadsheetId", sheetsSettings.defaultSpreadsheetId)
                addProperty("defaultSheetName", sheetsSettings.defaultSheetName)
                if (availableSheetNames != null) {
                    add("availableSheets", JsonArray().apply {
                        availableSheetNames.forEach { add(it) }
                    })
                }
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

    /**
     * 把一批 tool_call 折叠成一行简短描述,用于 assistant 消息占位文案。
     * 重复同名工具会合并计数,避免长列表刷屏。
     */
    private fun summarizeToolCalls(toolCalls: List<cn.jarryleo.insert_strings.ai.ToolCall>): String {
        if (toolCalls.isEmpty()) return ""
        val grouped = toolCalls.groupingBy { it.name }.eachCount()
        return grouped.entries.joinToString("、") { (name, count) ->
            if (count > 1) "$name×$count" else name
        }
    }

    private fun executeInsertActions(
        entries: List<ActionWithToolCall>,
        context: String,
        iteration: Int
    ) {
        val actions = entries.mapNotNull { it.action as? AiAction.InsertStrings }
        if (actions.isEmpty()) {
            continueToolLoopInBackground(context, iteration + 1)
            return
        }
        val contextInfo = ContextManager.contextInfo
        val currentModuleName = contextInfo?.currentModule?.moduleName
        val moduleWithMostLines = contextInfo?.moduleWithMostLines
        val moduleList = contextInfo?.modules?.map { it.moduleName }

        // 决定本批次的统一目标模块(模块一致性已由 processAiReply 校验过,此处安全取第一个非空 module)
        // 关键:排除项目名(与 normalizeExplicitWriteModule 同步),避免项目名被当作模块名
        val projectName = contextInfo?.projectName
        val batchModule = resolveTargetModule(
            actions.firstNotNullOfOrNull { it.module?.takeIf { m -> m.isNotBlank() && m != projectName } },
            currentModuleName,
            moduleWithMostLines?.moduleName
        )
        if (batchModule == null) {
            // 理论上不会走到这里(校验时已拦截),兜底
            entries.forEach { (_, toolCallId) ->
                if (toolCallId.isNotEmpty()) {
                    chatMessages.add(
                        ChatMessage(
                            role = "tool",
                            content = "[工具执行异常] insert_strings 未指定目标模块且无 currentModule , moduleList = $moduleList",
                            toolCallId = toolCallId
                        )
                    )
                }
            }
            continueToolLoopInBackground(context, iteration + 1)
            return
        }

        if (batchModule == projectName){
            // 理论上不会走到这里(校验时已拦截),兜底
            entries.forEach { (_, toolCallId) ->
                if (toolCallId.isNotEmpty()) {
                    chatMessages.add(
                        ChatMessage(
                            role = "tool",
                            content = "[工具执行异常] insert_strings 模块名称 = 项目名称,请勿使用项目名插入翻译 , moduleList = $moduleList",
                            toolCallId = toolCallId
                        )
                    )
                }
            }
            continueToolLoopInBackground(context, iteration + 1)
            return
        }

        // 逐个执行到 batchModule
        // 关键改动:不再用 keyEntries 的 stringsInfoList(它的 filePath 是用户原来选中的模块,
        // 跟 batchModule 可能是两个模块),改为:
        //   1) 提前在 batchModule 下补齐所有需要的语言文件(缺哪个建哪个)
        //   2) 总是走 insertIntoModule,以 batchModule 的 stringsInfoList 为准
        //   3) merged 基础值用 batchModule 现有翻译(用 scanModuleForKey 读真实文件),
        //      保留用户已存在的翻译不被 AI 漏写的语言「清空」

        // 预聚合:本批所有 action 中要写入的语言并集,提前在 batchModule 下补齐缺失文件。
        // 这是修复「values 写到别的模块」的关键 — targetModule 没有 values/ 时,直接建一个,
        // 所有语言落在同一模块,不会再让用户看到 values 在 module A、其它语言在 module B。
        val allLanguagesNeeded = (actions.flatMap { it.translations.keys } + DEFAULT_LANGUAGE).toSet()
        allLanguagesNeeded.forEach { lang ->
            if (!lang.startsWith("values")) return@forEach
            ContextManager.ensureLanguageFile(project, batchModule, lang)
        }

        val results = actions.map { action ->
            val targetModule = batchModule
            val moduleStringsInfo = ContextManager.getModuleStringsInfo(project, targetModule)
            if (moduleStringsInfo.isEmpty()) {
                showToast("Module $targetModule has no strings.xml")
                return@map "模块 $targetModule 没有 strings.xml 或缺少 res/ 目录" to false
            }

            // 以 batchModule 现有翻译为底,AI 没写的语言不覆盖(避免漏写时把已有翻译清空)。
            // 注意:此处只对「已存在的 key」保留翻译;对新增 key 来说,scanModuleForKey 全部返回空串,
            // 等价于空白底,行为与之前一致。
            val existingInfo = ContextManager.scanModuleForKey(project, targetModule, action.name)
            val existingTranslations = existingInfo.associate { it.language to it.text }
            val merged = existingTranslations.toMutableMap()
            merged.putAll(action.translations)
            // 兜底:确保 values(默认英语)一定存在,避免漏写导致 values/strings.xml 被清空
            if (DEFAULT_LANGUAGE !in merged) merged[DEFAULT_LANGUAGE] = ""

            try {
                insertStringsManager.insertIntoModule(
                    project, targetModule, mapOf(action.name to merged)
                )
                "成功" to true
            } catch (e: Exception) {
                "失败:${e.message ?: "unknown"}" to false
            }
        }

        // 刷新 UI(全部用 batchModule,保证一致性)
        val allEntries = actions.map { action ->
            KeyedStringsInfo(
                action.name,
                "",
                ContextManager.scanModuleForKey(project, batchModule, action.name)
            )
        }
        insertStringsManager.updateUI(allEntries)
        val names = actions.joinToString(", ") { it.name }
        showToast("Inserted: $names")
        showChat = false

        // 为每个 insert_strings 调用添加对应的 tool result(使用 entry 自带的 toolCallId,避免下标错位)
        entries.forEachIndexed { i, (_, toolCallId) ->
            val action = actions.getOrNull(i)
            val (msg, _) = results.getOrNull(i) ?: ("" to false)
            if (action != null && toolCallId.isNotEmpty()) {
                chatMessages.add(
                    ChatMessage(
                        role = "tool",
                        content = "[工具执行结果] insert_strings module=$batchModule name=${action.name} 状态:$msg",
                        toolCallId = toolCallId
                    )
                )
            }
        }

        // 继续 tool loop:让 AI 拿到结果后调用 task_complete 或继续下一步
        continueToolLoopInBackground(context, iteration + 1)
    }

    private fun resolveTargetModule(
        actionModule: String?,
        currentModuleName: String?,
        moduleWithMostLinesName: String?
    ): String? {
        // 1. AI 显式指定的 module 优先;但若它等于 androidProject.name(常见误传),
        //    视为未指定,继续走 currentModule/moduleWithMostLines。
        val contextInfo = ContextManager.contextInfo
        val projectName = contextInfo?.projectName
        val realModuleNames = contextInfo?.modules?.map { it.moduleName }?.toSet().orEmpty()
        val actionModuleSanitized = actionModule?.trim()?.takeIf { it.isNotBlank() }?.let { candidate ->
            if (candidate == projectName) {
                // AI 把工程名当 module 传了,无论其他条件如何,一律降级到 currentModule
                null
            } else if (candidate in realModuleNames) {
                candidate
            } else {
                // 不在已知模块里(可能 AI 编了一个不存在的名字),不直接信任,降级
                ContextManager.resolveDisplayModuleName(project, candidate)
            }
        }
        return actionModuleSanitized
            ?: currentModuleName?.takeIf { it.isNotBlank() }
            ?: moduleWithMostLinesName?.takeIf { it.isNotBlank() }
    }

    override fun updateUI(
        entries: List<KeyedStringsInfo>
    ) {
        if (entries.isEmpty()) return

        SwingUtilities.invokeLater {
            keyEntries.clear()
            keyEntries.addAll(entries)
            if (selectedKeyIndex >= keyEntries.size) selectedKeyIndex = 0
            updateRowsForSelectedKey()
            toolWindow.show()
        }
    }
}

@Composable
private fun InsertStringsContent(
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
    sheetsAvailableSheetNames: List<String>,
    sheetsListStatus: String,
    onSheetsDefaultSpreadsheetIdChange: (String) -> Unit,
    onSheetsDefaultSheetNameChange: (String) -> Unit,
    onTestSheetsConnection: () -> Unit,
    onSaveSheetsSettings: () -> Unit,
    onRefreshSheetsList: () -> Unit,
    onChatInputChange: (String) -> Unit,
    onSendChat: () -> Unit,
    onStopChat: () -> Unit,
    onQuickSend: (String) -> Unit,
    onNewChat: () -> Unit,
    onOptionClick: (Int, String) -> Unit,
    onOpenContext: () -> Unit,
    onCloseContext: () -> Unit,
    showContextPopup: Boolean,
    chatContextText: String,
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
                        sheetsAvailableSheetNames = sheetsAvailableSheetNames,
                        sheetsListStatus = sheetsListStatus,
                        onSheetsDefaultSpreadsheetIdChange = onSheetsDefaultSpreadsheetIdChange,
                        onSheetsDefaultSheetNameChange = onSheetsDefaultSheetNameChange,
                        onTestSheetsConnection = onTestSheetsConnection,
                        onSaveSheetsSettings = onSaveSheetsSettings,
                        onRefreshSheetsList = onRefreshSheetsList,
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
