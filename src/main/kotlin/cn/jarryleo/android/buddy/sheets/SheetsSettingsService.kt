package cn.jarryleo.android.buddy.sheets

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(
    name = "InsertStringsSheetsSettings",
    storages = [Storage("insertStringsSheetsSettings.xml")]
)
class SheetsSettingsService : PersistentStateComponent<SheetsSettingsState> {
    private var state = SheetsSettingsState()

    override fun getState(): SheetsSettingsState = state

    override fun loadState(state: SheetsSettingsState) {
        this.state = state
    }

    fun update(
        defaultSpreadsheetId: String,
        defaultSheetName: String
    ) {
        state.defaultSpreadsheetId = defaultSpreadsheetId.trim()
        state.defaultSheetName = defaultSheetName.trim().ifBlank { "Sheet1" }
    }

    companion object {
        fun getInstance(project: Project): SheetsSettingsService {
            return project.getService(SheetsSettingsService::class.java)
        }
    }
}
