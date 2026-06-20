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
import cn.jarryleo.insert_strings.xml.ModuleInfo
import cn.jarryleo.insert_strings.xml.StringsInfo
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
        private const val MAX_TOOL_ROUNDS = 3
        // 批量翻译审查时每批的行数。控制单次 AI 调用 token，避免溢出。
        private const val REVIEW_BATCH_SIZE = 80
        // AI 声称要执行操作却未返回 actions 时，强制其重新给出动作的最大重试次数。
        private const val MAX_REMEDIATION_ROUNDS = 2
        // 监督提示：当 AI 只回复文字却未给出可执行 actions 时注入，强制其返回结构化动作。
        private const val SUPERVISOR_PROMPT =
            "[系统监督] 你上次的回复不包含可解析的 JSON actions，系统未执行任何操作。" +
                "你必须只返回一个纯 JSON 对象（以 { 开头、以 } 结尾），绝对禁止使用 <search_and_update> 或任何自定义标签，" +
                "禁止使用 markdown 代码块，禁止在 JSON 之外添加任何文字。" +
                "正确格式示例：\n" +
                """{"reply":"已帮你补全繁体中文翻译","actions":[{"type":"sheets_operation","operation":"update_row","sheetName":"1.0.3.0","rowNumber":7,"rows":[["mall_tab_room_card","房间名片卡","房間名片卡","Room name card"]]}]}""" + "\n" +
                "请直接返回符合上述格式的 JSON，把要执行的操作放入 actions 数组。" +
                "若要修改某行，operation 用 update_row 并传入 rowNumber 和 rows。" +
                "若要在末尾添加，operation 用 append_row。" +
                "若要搜索定位行号，operation 用 search 并传入 key。"
    }

    private data class SheetsToolResult(
        val operation: String,
        val success: Boolean,
        val message: String,
        val data: List<List<String>>? = null,
        val sheetNames: List<String>? = null,
        val rowNumber: Int? = null,
        // 若为 true，表示该结果本身是面向用户的最终报告，
        // handleAiActions 应直接展示给用户，不再发起后续 AI 调用（省 token）。
        val terminal: Boolean = false
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

    // Google Sheets state
    private var sheetsDefaultSpreadsheetId by mutableStateOf("")
    private var sheetsDefaultSheetName by mutableStateOf("Sheet1")
    private var sheetsConnectionStatus by mutableStateOf("")
    private val sheetsAvailableSheets = mutableStateListOf<SheetsManager.SheetInfo>()
    private var sheetsListStatus by mutableStateOf("")

    private var settingsTab by mutableStateOf(SettingsTab.AI)

    private data class PendingSheetsInsert(
        val actions: List<AiAction.SheetsOperation>,
        val duplicateKeys: Set<String>,
        val spreadsheetId: String,
        val sheetName: String,
        val depth: Int
    )
    private var pendingSheetsInsert: PendingSheetsInsert? = null

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
                SheetsToolResult("检查全部翻译", true, report.toReportText(), terminal = true)
            }

            AiAction.SheetsOperation.Operation.FIX_TRANSLATIONS -> {
                val report = runTranslationReview(action, spreadsheetId, sheetName, mode = "fix")
                SwingUtilities.invokeLater { showToast(report.summary.ifBlank { "修正完成" }) }
                SheetsToolResult("修正全部翻译", report.success, report.toReportText(), terminal = true)
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
                if (result.sheetNames != null && result.sheetNames.isNotEmpty()) {
                    appendLine("  工作表列表:")
                    result.sheetNames.forEach { name ->
                        appendLine("    - $name")
                    }
                }
                if (result.rowNumber != null) {
                    appendLine("  行号: ${result.rowNumber}")
                }
                if (result.data != null && result.data.isNotEmpty()) {
                    appendLine("  数据:")
                    result.data.forEachIndexed { rowIndex, row ->
                        appendLine("    行${rowIndex + 1}: ${row.joinToString(" | ")}")
                    }
                }
            }
            appendLine("请根据以上结果回复用户。")
        }
    }

    /**
     * 监督：判断 AI 回复文本是否暗示要执行工具操作（删除/插入/修改等）。
     * 用于检测「AI 声称要做但未返回 actions」的情况，触发自动纠偏。
     * 排除明显的拒绝/需配置话术，避免对「无法执行」的回复做无意义重试。
     */
    private fun replyImpliesToolIntent(reply: String): Boolean {
        if (reply.isBlank()) return false
        val text = reply.lowercase()
        val refusalMarkers = listOf(
            "无法", "不能", "请先配置", "请配置", "未配置", "没有配置",
            "请先在设置", "失败", "error", "出错", "暂不支持", "无法执行",
            "请先选择", "请提供", "需要你提供"
        )
        if (refusalMarkers.any { text.contains(it.lowercase()) }) return false
        val intentKeywords = listOf(
            "删除", "删掉", "去掉", "delete",
            "插入", "新增", "添加", "insert", "add",
            "修改", "更新", "改成", "改为", "替换", "update",
            "追加", "append",
            "清空", "清除", "clear",
            "读取", "读一下", "read",
            "搜索", "查找", "search",
            "检查", "审查", "check",
            "修正", "修复", "补全", "补充", "fix",
            "写入", "write",
            "列出", "list_sheets", "工作表列表",
            "search_and_update", "update_row", "append_row", "insert_row", "delete_row"
        )
        return intentKeywords.any { text.contains(it) }
    }

    /**
     * 面向用户的工具执行结果摘要（非给 AI 的工具消息格式）。
     * 当后续 AI 回复为空或异常时，用它兜底展示，保证用户始终知道实际发生了什么。
     */
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
        continueChatWithAi()
    }

    /**
     * 公共 AI 调用 + 监督纠偏 + 动作处理。
     * 调用前应已将用户消息加入 chatMessages。供 sendChatMessage 和 onChatOptionClick 共用。
     */
    private fun continueChatWithAi() {
        chatSending = true
        val context = buildChatContext()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                var reply = AITranslator.chat(chatMessages.toList(), context)
                // 监督纠偏：AI 声称要执行操作却未返回任何 actions 时，注入监督提示强制其重新给出动作。
                var remediation = 0
                while (reply.actions.isEmpty() &&
                    remediation < MAX_REMEDIATION_ROUNDS &&
                    replyImpliesToolIntent(reply.reply)
                ) {
                    // 构造带监督提示的消息序列传给 AI（不依赖 EDT 时序，避免竞态）
                    val supervised = chatMessages.toList() + listOf(
                        ChatMessage(role = "assistant", content = reply.reply),
                        ChatMessage(role = "tool", content = SUPERVISOR_PROMPT)
                    )
                    // 同步在 UI 展示 AI 的「空动作」回复与监督提示，保持对话可读
                    SwingUtilities.invokeLater {
                        chatMessages.add(ChatMessage(role = "assistant", content = reply.reply))
                        chatMessages.add(ChatMessage(role = "tool", content = SUPERVISOR_PROMPT))
                    }
                    reply = AITranslator.chat(supervised, context)
                    remediation++
                }
                val finalReply = reply
                SwingUtilities.invokeLater {
                    val display = if (finalReply.actions.isEmpty() && remediation > 0) {
                        finalReply.reply +
                            "\n\n（系统已提醒 AI 返回操作动作，但 AI 仍未给出可执行的动作，因此未执行任何操作。）"
                    } else {
                        finalReply.reply
                    }
                    chatMessages.add(ChatMessage(role = "assistant", content = display))
                    handleAiActions(finalReply.actions, 0)
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    chatMessages.add(
                        ChatMessage(
                            role = "assistant",
                            content = "发送请求时发生异常，操作未完成：${e.message ?: "unknown"}"
                        )
                    )
                    chatSending = false
                }
            }
        }
    }

    /**
     * 对话气泡选项按钮点击回调。
     * 清除按钮后判断是待处理的重复 key 插入还是普通 AskUser，分别处理。
     */
    private fun onChatOptionClick(messageIndex: Int, option: String) {
        // 清除该消息的 options 使按钮消失
        if (messageIndex in chatMessages.indices) {
            val msg = chatMessages[messageIndex]
            chatMessages[messageIndex] = msg.copy(options = emptyList())
        }
        // 重复 key 插入等待用户选择
        val pending = pendingSheetsInsert
        if (pending != null) {
            pendingSheetsInsert = null
            resolveDuplicateInsert(option, pending)
            return
        }
        // 普通 AskUser：将用户选择作为消息发回 AI 继续对话
        chatMessages.add(ChatMessage(role = "user", content = option))
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
            addProperty("projectName", contextInfo.projectName)
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

    private fun handleAiActions(actions: List<AiAction>, depth: Int) {
        val insertActions = actions.filterIsInstance<AiAction.InsertStrings>()
        if (insertActions.isNotEmpty()) {
            executeInsertActions(insertActions)
        }
        val askActions = actions.filterIsInstance<AiAction.AskUser>()
        if (askActions.isNotEmpty()) {
            val askAction = askActions.first()
            if (askAction.options.isNotEmpty()) {
                // 带 options 的 AskUser：在气泡底部展示按钮，等待用户点击，暂不继续执行
                chatMessages.add(
                    ChatMessage(
                        role = "assistant",
                        content = askAction.question,
                        options = askAction.options
                    )
                )
                chatSending = false
                return
            } else {
                showToast(askAction.question)
            }
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

        executeSheetsActions(sheetsActions, depth)
    }

    /**
     * 执行 sheets 操作并处理后续 AI 回复。
     * 在执行前自动检测 append_row 动作是否有重复 key，若有则暂停并询问用户。
     */
    private fun executeSheetsActions(sheetsActions: List<AiAction.SheetsOperation>, depth: Int) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                // 重复 key 检测：append_row 动作执行前读取表格现有 key，发现重复则暂停并询问用户
                val appendActions = sheetsActions.filter {
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
                            ?.mapNotNull { it.firstOrNull()?.trim()?.takeIf { k -> k.isNotBlank() } }
                            ?.toSet() ?: emptySet()
                        val duplicates = newKeys.filter { it in existingKeys }.toSet()
                        if (duplicates.isNotEmpty()) {
                            val pending = PendingSheetsInsert(
                                sheetsActions, duplicates, spreadsheetId, sheetName, depth
                            )
                            SwingUtilities.invokeLater {
                                pendingSheetsInsert = pending
                                chatMessages.add(
                                    ChatMessage(
                                        role = "assistant",
                                        content = "检测到以下 key 已存在于表格中：" +
                                            "${duplicates.joinToString(", ")}。请选择如何处理：",
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

                val toolResults = sheetsActions.map { executeSheetsOperationSync(it) }

                // 若全部为终态报告（批量审查/修正），直接展示报告，不再发起后续 AI 调用（省 token）。
                if (toolResults.isNotEmpty() && toolResults.all { it.terminal }) {
                    val reportText = toolResults.joinToString("\n\n") { it.message }
                    SwingUtilities.invokeLater {
                        chatMessages.add(ChatMessage(role = "assistant", content = reportText))
                        chatSending = false
                    }
                    return@executeOnPooledThread
                }

                val resultMessage = buildToolResultMessage(toolResults)

                var context = ""
                try {
                    SwingUtilities.invokeAndWait {
                        chatMessages.add(ChatMessage(role = "tool", content = resultMessage))
                        context = buildChatContext()
                    }
                } catch (e: Exception) {
                    context = buildChatContext()
                }

                val followUp = AITranslator.chat(chatMessages.toList(), context)

                SwingUtilities.invokeLater {
                    // 结果回报兜底：后续 AI 回复为空时，直接把工具执行结果摘要展示给用户，
                    // 保证用户始终知道实际执行了什么、成功与否。
                    val displayReply = followUp.reply.ifBlank { buildToolResultSummary(toolResults) }
                    chatMessages.add(ChatMessage(role = "assistant", content = displayReply))
                    handleAiActions(followUp.actions, depth + 1)
                }
            } catch (e: Exception) {
                // 异常兜底：pooled 线程任意异常都向用户报错并强制复位 chatSending，避免 UI 永久卡在「...」。
                SwingUtilities.invokeLater {
                    chatMessages.add(
                        ChatMessage(
                            role = "assistant",
                            content = "操作执行过程中发生异常，未完成：${e.message ?: "unknown"}"
                        )
                    )
                    chatSending = false
                }
            }
        }
    }

    /**
     * 重复 key 插入的用户选择处理。
     * - 覆盖：将重复 key 的 append_row 转为 search + update_row，非重复的保持 append_row。
     * - 末尾插入：原样执行所有 append_row。
     * - 取消：不执行，告知用户。
     */
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
                        executeSheetsActions(resolvedActions, pending.depth)
                    } catch (e: Exception) {
                        SwingUtilities.invokeLater {
                            chatMessages.add(
                                ChatMessage(
                                    role = "assistant",
                                    content = "覆盖操作执行异常：${e.message ?: "unknown"}"
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
                        executeSheetsActions(pending.actions, pending.depth)
                    } catch (e: Exception) {
                        SwingUtilities.invokeLater {
                            chatMessages.add(
                                ChatMessage(
                                    role = "assistant",
                                    content = "追加操作执行异常：${e.message ?: "unknown"}"
                                )
                            )
                            chatSending = false
                        }
                    }
                }
            }
            else -> {
                chatMessages.add(ChatMessage(role = "assistant", content = "已取消插入操作。"))
                chatSending = false
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
                val existingTranslations = keyEntries.firstOrNull()?.stringsInfoList
                    ?.associate { it.language to it.text } ?: emptyMap()
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
                insertStringsManager.insert(project, mapOf(action.name to stringsInfoList))
            } else {
                insertStringsManager.insertIntoModule(project, targetModule, mapOf(action.name to stringsInfoList))
            }
        }

        val lastAction = actions.last()
        val targetModule = resolveTargetModule(
            lastAction.module,
            currentModuleName,
            moduleWithMostLines?.moduleName
        )
        if (targetModule != null) {
            val allEntries = actions.map { action ->
                KeyedStringsInfo(
                    action.name,
                    "",
                    ContextManager.scanModuleForKey(project, targetModule, action.name)
                )
            }
            insertStringsManager.updateUI(allEntries)
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
