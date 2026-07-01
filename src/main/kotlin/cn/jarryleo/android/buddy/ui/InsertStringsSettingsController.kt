package cn.jarryleo.android.buddy.ui

import cn.jarryleo.android.buddy.ai.AITranslator
import cn.jarryleo.android.buddy.ai.AiProvider
import cn.jarryleo.android.buddy.ai.AiProtocol
import cn.jarryleo.android.buddy.ai.AiSettingsService
import cn.jarryleo.android.buddy.sheets.SheetsManager
import cn.jarryleo.android.buddy.sheets.SheetsSettingsService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import javax.swing.SwingUtilities

/**
 * AI + Sheets 设置的加载/保存/刷新,以及模型列表拉取。
 *
 * 拆分理由:这些方法只读 / 写少量 UI 状态,与 UI 主流程无强耦合,独立成类方便阅读和测试。
 *
 * **AI 部分职责(多 provider 模型)**:
 * - [loadAiSettings]  从 [AiSettingsService] 加载全部 provider 与 currentId;
 * - [beginAddProvider] / [beginEditProvider]  进入「编辑草稿」态(不写库);
 * - [saveEditingProvider]  校验草稿,落库并同步 UI 列表;
 * - [cancelEditingProvider]  丢弃草稿;
 * - [deleteProvider]  二次确认后删除;
 * - [useProvider]  切换为当前 provider(AI 调用从此处取配置);
 * - [fetchModels]  按草稿的 url/apiKey/protocol 拉取模型列表。
 */
internal class InsertStringsSettingsController(
    private val ui: InsertStringsUI,
) {

    private val project: Project get() = ui.project

    // ============== AI 多 provider 模型 ==============

    /**
     * 加载全部 provider + currentProviderId 到 UI。
     * 在 tool window 打开时调用一次。
     * 老格式 → 新格式的迁移由 [AiSettingsService.loadState] 自动完成,
     * 这里只负责把 service 状态原样搬到 UI state。
     */
    fun loadAiSettings() {
        val service = AiSettingsService.getInstance()
        ui.aiProviders.clear()
        ui.aiProviders.addAll(service.listProviders())
        ui.currentAiProviderId = service.state.currentProviderId
    }

    /**
     * 进入「新增 provider」态:
     * 分配新 id 的空 provider 作为草稿,设置 [InsertStringsUI.editingIsNew] = true。
     * 若草稿里已有内容,直接替换(用户切换意图)。
     * 不写库,等 [saveEditingProvider] 时才落库。
     */
    fun beginAddProvider() {
        ui.editingAiProvider = AiProvider.blank()
        ui.editingIsNew = true
        ui.modelOptions.clear()
        ui.modelFetchStatus = ""
    }

    /**
     * 进入「编辑已有 provider」态:把目标 provider 复制到草稿,
     * 保留 id(用 [AiProvider.copy] 实现),设置 [InsertStringsUI.editingIsNew] = false。
     * 不写库,Save 时按 id upsert。
     */
    fun beginEditProvider(provider: AiProvider) {
        ui.editingAiProvider = provider.copy()
        ui.editingIsNew = false
        ui.modelOptions.clear()
        ui.modelFetchStatus = ""
    }

    /**
     * 把当前草稿保存为新增 / 更新。校验:
     * - URL / API Key / Model 三项至少 URL 和 Model 必填(没有 URL/Model 无法发起 AI 调用,
     *   API Key 缺失的话由具体服务在调用时报错,这里只做软提示);
     * - name 留空允许(UI 显示回退为 "(unnamed)")。
     *
     * 落库后:
     * - 新增:在 UI 列表尾部追加;
     * - 更新:按 id 替换原条目;
     * - 如果新增后当前无 provider,自动把新增项置为 current;
     * - 清空草稿,退出编辑态。
     *
     * @return true 表示已落库;false 表示校验失败(UI 用 toast 提示)。
     */
    fun saveEditingProvider(): Boolean {
        val draft = ui.editingAiProvider ?: return false
        val name = draft.name.trim()
        val url = draft.url.trim()
        val apiKey = draft.apiKey.trim()
        val model = draft.model.trim()
        if (url.isBlank()) {
            showToast("URL is required.")
            return false
        }
        if (model.isBlank()) {
            showToast("Model is required.")
            return false
        }
        val service = AiSettingsService.getInstance()
        val saved = draft.copy(
            name = name,
            url = url,
            apiKey = apiKey,
            protocol = draft.protocol,
            model = model,
        )
        if (ui.editingIsNew) {
            service.addProvider(saved)
            // 同步 UI
            ui.aiProviders.add(saved)
            // 如果 service 把它自动设为 current(此前无 current),也同步
            if (ui.currentAiProviderId == null) {
                ui.currentAiProviderId = service.state.currentProviderId
            }
        } else {
            service.updateProvider(saved)
            val idx = ui.aiProviders.indexOfFirst { it.id == saved.id }
            if (idx >= 0) {
                ui.aiProviders[idx] = saved
            }
        }
        ui.editingAiProvider = null
        ui.editingIsNew = false
        ui.modelOptions.clear()
        ui.modelFetchStatus = ""
        showToast("Saved.")
        return true
    }

    /**
     * 取消编辑草稿,丢弃所有未保存修改。
     */
    fun cancelEditingProvider() {
        ui.editingAiProvider = null
        ui.editingIsNew = false
        ui.modelOptions.clear()
        ui.modelFetchStatus = ""
    }

    /**
     * 删除一个 provider。先弹二次确认(IDE 标准 YesNo 对话框),用户取消则不做任何操作。
     * 删除时若是当前 provider,service 会自动切到剩余列表第一个;UI 同步。
     */
    fun deleteProvider(provider: AiProvider) {
        val displayName = provider.name.ifBlank { "(unnamed)" }
        val confirm = Messages.showYesNoDialog(
            project,
            "Delete provider \"$displayName\"? This cannot be undone.",
            "Delete AI Provider",
            Messages.getQuestionIcon(),
        )
        if (confirm != Messages.YES) return
        AiSettingsService.getInstance().deleteProvider(provider.id)
        ui.aiProviders.removeAll { it.id == provider.id }
        // 同步 current(若 service 自动切到了别的 provider)
        ui.currentAiProviderId = AiSettingsService.getInstance().state.currentProviderId
        // 若删的是正在编辑的,清空草稿
        if (ui.editingAiProvider?.id == provider.id) {
            cancelEditingProvider()
        }
        showToast("Deleted.")
    }

    /**
     * 把 [provider] 设为当前激活的 provider。
     * service 内部会校验 id 存在,UI 同步 currentProviderId。
     */
    fun useProvider(provider: AiProvider) {
        AiSettingsService.getInstance().setCurrentProvider(provider.id)
        ui.currentAiProviderId = provider.id
        showToast("Switched to ${provider.name.ifBlank { "(unnamed)" }}.")
    }

    /**
     * 按当前草稿(新增/编辑中的 provider)的 url/apiKey/protocol 拉取模型列表。
     * 与旧版区别:不再读全局 state,直接读草稿字段。
     * 拉取成功时,如果草稿 model 留空,自动填第一个模型。
     */
    fun fetchModels() {
        val draft = ui.editingAiProvider ?: return
        if (draft.url.isBlank()) {
            ui.modelFetchStatus = "Please enter the URL first."
            return
        }
        val protocol = AiProtocol.fromName(draft.protocol)
        ui.modelFetchStatus = "Loading models..."
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = AITranslator.fetchModels(draft.url, protocol, draft.apiKey)
            SwingUtilities.invokeLater {
                // 草稿可能已被用户切换 / 取消,这里保守判断一次 id 一致
                if (ui.editingAiProvider?.id != draft.id) return@invokeLater
                result.fold(
                    onSuccess = { models ->
                        ui.modelOptions.clear()
                        ui.modelOptions.addAll(models)
                        val currentDraft = ui.editingAiProvider
                        if (currentDraft != null && currentDraft.model.isBlank()) {
                            ui.editingAiProvider = currentDraft.copy(model = models.firstOrNull().orEmpty())
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

    private fun showToast(message: String) {
        // 复用 actionsController 的 showToast,避免重复实现
        ui.showToast(message)
    }

    // ============== Sheets 设置(保持原样) ==============

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
}
