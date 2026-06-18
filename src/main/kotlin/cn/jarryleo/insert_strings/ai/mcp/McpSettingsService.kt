package cn.jarryleo.insert_strings.ai.mcp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(
    name = "InsertStringsMcpSettings",
    storages = [Storage("insertStringsMcpSettings.xml")]
)
class McpSettingsService : PersistentStateComponent<McpSettingsState> {
    private var state = McpSettingsState()

    override fun getState(): McpSettingsState = state

    override fun loadState(state: McpSettingsState) {
        this.state = state
    }

    fun update(
        enabled: Boolean,
        command: String,
        arguments: String,
        workingDir: String,
        spreadsheetId: String,
        sheetName: String,
        timeoutSeconds: Int
    ) {
        state.enabled = enabled
        state.command = command.trim()
        state.arguments = arguments.trim()
        state.workingDir = workingDir.trim()
        state.spreadsheetId = spreadsheetId.trim()
        state.sheetName = sheetName.trim().ifEmpty { "Sheet1" }
        state.timeoutSeconds = timeoutSeconds.coerceAtLeast(5)
    }

    companion object {
        fun getInstance(): McpSettingsService {
            return ApplicationManager.getApplication().getService(McpSettingsService::class.java)
        }
    }
}
