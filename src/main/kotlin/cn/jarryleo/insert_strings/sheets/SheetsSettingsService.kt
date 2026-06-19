package cn.jarryleo.insert_strings.sheets

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

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
        credentialsJsonPath: String,
        tokensDirectoryPath: String,
        defaultSpreadsheetId: String,
        defaultSheetName: String
    ) {
        state.credentialsJsonPath = credentialsJsonPath.trim()
        state.tokensDirectoryPath = tokensDirectoryPath.trim()
        state.defaultSpreadsheetId = defaultSpreadsheetId.trim()
        state.defaultSheetName = defaultSheetName.trim().ifBlank { "Sheet1" }
    }

    companion object {
        fun getInstance(): SheetsSettingsService {
            return ApplicationManager.getApplication().getService(SheetsSettingsService::class.java)
        }
    }
}
