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
        // 批量翻译审查时每批的行数。控制单次 AI 调用 token，避免溢出。
        private const val REVIEW_BATCH_SIZE = 80
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
                    val updateResult = SheetsManager.updateRow(
                        project, spreadsheetId, sheetName, fix.row, fix.values
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
            addProperty("currentKey", stringName)
            add("currentTranslations", JsonObject().apply {
                currentTranslations.forEach { (k, v) -> addProperty(k, v) }
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
