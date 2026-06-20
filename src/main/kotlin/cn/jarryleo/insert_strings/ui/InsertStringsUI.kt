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
        // 设 15 足以覆盖现实中的多步操作(检查+修正等),又能及时止损。
        private const val MAX_ITERATIONS = 15
        // 批量翻译审查时每批的行数。控制单次 AI 调用 token,避免溢出。
        private const val REVIEW_BATCH_SIZE = 80
        // load_tool_doc 按需加载工具文档的最大连续次数,防止 AI 反复加载文档而不执行操作。
        private const val MAX_TOOL_DOC_LOADS = 4
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
    // 当前轮待响应的 ask_user 工具调用 ID。用户点击选项后,作为 tool result 回传给 AI。
    private var pendingAskUserToolCallId: String? = null

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
                    onQuickSend = ::quickSend,
                    onNewChat = ::newChat,
                    onOptionClick = ::onChatOptionClick,
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

            AiAction.SheetsOperation.Operation.APPEND_ROW -> {
                val rowsToAppend = action.rows
                if (rowsToAppend.isNullOrEmpty()) {
                    return SheetsToolResult("追加行", false, "rows 为空。")
                }
                val row = rowsToAppend.first()
                val result = SheetsManager.appendRow(project, spreadsheetId, sheetName, row)
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
                val result = SheetsManager.insertRow(project, spreadsheetId, sheetName, rowNum, row)
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
                val result = SheetsManager.updateRow(project, spreadsheetId, sheetName, rowNum, row)
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
                val result = SheetsManager.insertColumn(project, spreadsheetId, sheetName, colIdx, values)
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
                val result = SheetsManager.appendColumn(project, spreadsheetId, sheetName, values)
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
                val result = SheetsManager.updateColumn(project, spreadsheetId, sheetName, colIdx, values)
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
        chatMessages.add(ChatMessage(role = "user", content = text))
        toolDocLoadCount = 0
        continueChatWithAi()
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
     * 终止条件:AI 调用 task_complete / 用户主动取消 / 达到 MAX_ITERATIONS。
     */
    private fun runToolLoop(context: String, iteration: Int) {
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

        SwingUtilities.invokeLater {
            // 1. 记入 assistant 消息,带 tool_calls
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

            // 2. 建立 action → toolCallId 的下标映射
            val actionToolCallIds = reply.actions.indices.map { i ->
                reply.toolCalls.getOrNull(i)?.id.orEmpty()
            }

            // 3. 分发处理
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

        // Priority 1: task_complete 终止对话
        pairs.firstOrNull { it.action is AiAction.TaskComplete }?.let { (complete, _) ->
            handleTaskComplete(complete as AiAction.TaskComplete)
            return
        }

        // Priority 2: ask_user
        val askUserEntry = pairs.firstOrNull { it.action is AiAction.AskUser }
        if (askUserEntry != null) {
            val askAction = askUserEntry.action as AiAction.AskUser
            if (askAction.options.isNotEmpty()) {
                // 带 options:挂到 assistant 消息渲染按钮,记录 toolCallId,等待用户点击
                chatMessages[chatMessages.lastIndex] =
                    chatMessages.last().copy(options = askAction.options)
                pendingAskUserToolCallId = askUserEntry.toolCallId.takeIf { it.isNotEmpty() }
                chatSending = false
                return
            }
            // 无 options:仅提示用户,自动添加 tool result 继续
            showToast(askAction.question)
            if (askUserEntry.toolCallId.isNotEmpty()) {
                chatMessages.add(
                    ChatMessage(
                        role = "tool",
                        content = "[已向用户显示问题,无需回复] ${askAction.question}",
                        toolCallId = askUserEntry.toolCallId
                    )
                )
                continueToolLoopInBackground(context, iteration + 1)
                return
            }
            // toolCallId 缺失(异常):不回传 tool result,让 AI 看到「无 options + 无响应」自己决定下一步
        }

        // Priority 3: load_tool_doc
        val loadDocEntries = pairs.filter { it.action is AiAction.LoadToolDoc }
        if (loadDocEntries.isNotEmpty()) {
            handleLoadToolDoc(loadDocEntries, context, iteration)
            return
        }

        // Priority 4-5: strings.xml 写操作(insert_strings / update_string)统一做模块一致性校验
        val writeEntries = pairs.filter {
            it.action is AiAction.InsertStrings || it.action is AiAction.UpdateString
        }
        if (writeEntries.isNotEmpty()) {
            val conflict = detectWriteModuleConflict(writeEntries)
            if (conflict != null) {
                // 跨模块冲突:整批拒绝,把错误回传给 AI 让其修正
                rejectWriteModuleConflict(writeEntries, conflict, context, iteration)
                return
            }
        }

        // Priority 4: query_keys / read_string / update_string / find_keys_by_text(strings.xml 主动操作能力)
        // 注意:update_string 已被上面的模块一致性校验拦截(若有问题就 reject),此处仅含只读动作
        val stringReadEntries = pairs.filter {
            it.action is AiAction.QueryKeys ||
                it.action is AiAction.ReadString ||
                it.action is AiAction.FindKeysByText
        }
        val stringWriteEntries = pairs.filter { it.action is AiAction.UpdateString }
        val stringsEntries = stringReadEntries + stringWriteEntries
        if (stringsEntries.isNotEmpty()) {
            executeStringsOps(stringsEntries, context, iteration)
            return
        }

        // Priority 5: insert_strings
        val insertEntries = pairs.filter { it.action is AiAction.InsertStrings }
        if (insertEntries.isNotEmpty()) {
            executeInsertActions(insertEntries, context, iteration)
            return
        }

        // Priority 6: sheets_operation / find_rows_by_text
        val sheetsEntries = pairs.filter {
            it.action is AiAction.SheetsOperation || it.action is AiAction.FindRowsByText
        }
        if (sheetsEntries.isNotEmpty()) {
            executeSheetsActions(sheetsEntries, context, iteration)
            return
        }

        // 没有任何可执行 tool call:AI 只是在说,等用户输入
        chatSending = false
    }

    /**
     * 检测 write actions(insert_strings + update_string)是否指定了不同模块。
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
                    else -> null
                }?.trim()?.takeIf { it.isNotEmpty() }
                m
            }
            .distinct()
        return if (explicitModules.size > 1) explicitModules.joinToString(", ") else null
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
            append("[工具执行异常] 跨模块冲突:本轮内 insert_strings/update_string 动作指定了多个不同 module(")
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

        // Priority 1:ask_user 工具调用 → 作为 tool result 回传给 AI
        val askToolCallId = pendingAskUserToolCallId
        if (askToolCallId != null) {
            pendingAskUserToolCallId = null
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
        chatMessages.clear()
        chatInput = ""
        chatSending = false
    }

    private fun buildChatContext(): String {
        val contextInfo = ContextManager.contextInfo ?: return ""
        val availableLanguages = insertStringsManager.languages?.takeIf { it.isNotEmpty() }
            ?: contextInfo.currentModule?.xmlFiles?.map { it.language }
            ?: emptyList()
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

        // 决定本批次的统一目标模块(模块一致性已由 processAiReply 校验过,此处安全取第一个非空 module)
        val batchModule = resolveTargetModule(
            actions.firstNotNullOfOrNull { it.module?.takeIf { m -> m.isNotBlank() } },
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
                            content = "[工具执行异常] insert_strings 未指定目标模块且无 currentModule",
                            toolCallId = toolCallId
                        )
                    )
                }
            }
            continueToolLoopInBackground(context, iteration + 1)
            return
        }

        // 逐个执行到 batchModule
        val results = actions.map { action ->
            val targetModule = batchModule
            val stringsInfoList = if (targetModule == currentModuleName) {
                val existingTranslations = keyEntries.firstOrNull()?.stringsInfoList
                    ?.associate { it.language to it.text } ?: emptyMap()
                val merged = existingTranslations.toMutableMap()
                merged.putAll(action.translations)
                merged
            } else {
                val moduleStringsInfo = ContextManager.getModuleStringsInfo(project, targetModule)
                if (moduleStringsInfo.isEmpty()) {
                    showToast("Module $targetModule has no strings.xml")
                    return@map "模块 $targetModule 没有 strings.xml" to false
                }
                val merged = moduleStringsInfo.associate { it.language to "" }.toMutableMap()
                merged.putAll(action.translations)
                merged
            }

            val useCurrentStringsList = targetModule == currentModuleName
                    && !insertStringsManager.languages.isNullOrEmpty()
            try {
                if (useCurrentStringsList) {
                    insertStringsManager.insert(project, mapOf(action.name to stringsInfoList))
                } else {
                    insertStringsManager.insertIntoModule(
                        project, targetModule, mapOf(action.name to stringsInfoList)
                    )
                }
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
        return actionModule?.takeIf { it.isNotBlank() }
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
    onQuickSend: (String) -> Unit,
    onNewChat: () -> Unit,
    onOptionClick: (Int, String) -> Unit,
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
                        onQuickSend = onQuickSend,
                        onOptionClick = onOptionClick,
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
