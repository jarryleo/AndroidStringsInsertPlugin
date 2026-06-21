package cn.jarryleo.insert_strings.ui

import cn.jarryleo.insert_strings.ai.AITranslator
import cn.jarryleo.insert_strings.ai.AiProtocol
import cn.jarryleo.insert_strings.ai.AiSettingsService
import cn.jarryleo.insert_strings.sheets.SheetsManager
import cn.jarryleo.insert_strings.sheets.SheetsSettingsService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import javax.swing.SwingUtilities

/**
 * AI + Sheets 设置的加载/保存/刷新,以及模型列表拉取。
 *
 * 拆分理由:这些方法只读 / 写少量 UI 状态,与 UI 主流程无强耦合,独立成类方便阅读和测试。
 */
internal class InsertStringsSettingsController(
    private val ui: InsertStringsUI,
) {

    private val project: Project get() = ui.project

    fun loadAiSettings() {
        val settings = AiSettingsService.getInstance().state
        ui.aiUrl = settings.url
        ui.aiApiKey = settings.apiKey
        ui.aiProtocol = AiProtocol.fromName(settings.protocol)
        ui.aiModel = settings.model
    }

    fun saveAiSettings() {
        AiSettingsService.getInstance().update(
            url = ui.aiUrl,
            apiKey = ui.aiApiKey,
            protocol = ui.aiProtocol,
            model = ui.aiModel,
        )
        ui.modelFetchStatus = "Saved."
        ui.showSettings = false
    }

    fun loadSheetsSettings() {
        val settings = SheetsSettingsService.getInstance(project).state
        ui.sheetsDefaultSpreadsheetId = settings.defaultSpreadsheetId
        ui.sheetsDefaultSheetName = settings.defaultSheetName
    }

    fun saveSheetsSettings() {
        SheetsSettingsService.getInstance(project).update(
            defaultSpreadsheetId = ui.sheetsDefaultSpreadsheetId,
            defaultSheetName = ui.sheetsDefaultSheetName,
        )
        ui.sheetsConnectionStatus = "Saved."
        refreshSheetsList()
    }

    /**
     * 异步从默认表格拉取所有工作表名称,供 AI 上下文和设置页下拉使用。
     * 这同时验证了默认表格 ID 与授权是否可用。
     */
    fun refreshSheetsList() {
        val spreadsheetId = ui.sheetsDefaultSpreadsheetId.trim()
        if (spreadsheetId.isBlank()) {
            ui.sheetsAvailableSheets.clear()
            ui.sheetsListStatus = ""
            return
        }
        ui.sheetsListStatus = "Loading sheets..."
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = SheetsManager.listSheetNames(project, spreadsheetId)
            SwingUtilities.invokeLater {
                result.fold(
                    onSuccess = { sheets ->
                        ui.sheetsAvailableSheets.clear()
                        ui.sheetsAvailableSheets.addAll(sheets)
                        ui.sheetsListStatus = if (sheets.isEmpty()) {
                            "No sheets found."
                        } else {
                            "Loaded ${sheets.size} sheet(s): ${sheets.joinToString(", ") { it.title }}"
                        }
                        // 若当前默认 sheet 不在列表中,自动切换到第一个
                        if (sheets.isNotEmpty() &&
                            sheets.none { it.title.equals(ui.sheetsDefaultSheetName, ignoreCase = true) }
                        ) {
                            ui.sheetsDefaultSheetName = sheets.first().title
                        }
                    },
                    onFailure = {
                        ui.sheetsAvailableSheets.clear()
                        ui.sheetsListStatus = it.message ?: "Failed to load sheets."
                    }
                )
            }
        }
    }

    fun testSheetsConnection() {
        ui.sheetsConnectionStatus = "Connecting..."
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = SheetsManager.testConnection(project, ui.sheetsDefaultSpreadsheetId)
            SwingUtilities.invokeLater {
                result.fold(
                    onSuccess = { ui.sheetsConnectionStatus = it },
                    onFailure = { ui.sheetsConnectionStatus = it.message ?: "Connection failed." }
                )
            }
        }
    }

    fun fetchModels() {
        ui.modelFetchStatus = "Loading models..."
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = AITranslator.fetchModels(ui.aiUrl, ui.aiProtocol, ui.aiApiKey)
            SwingUtilities.invokeLater {
                result.fold(
                    onSuccess = { models ->
                        ui.modelOptions.clear()
                        ui.modelOptions.addAll(models)
                        if (ui.aiModel.isBlank()) {
                            ui.aiModel = models.firstOrNull().orEmpty()
                        }
                        ui.modelFetchStatus = "Loaded ${models.size} models."
                    },
                    onFailure = {
                        ui.modelFetchStatus = it.message ?: "Failed to load models."
                    }
                )
            }
        }
    }
}
