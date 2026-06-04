package cn.jarryleo.insert_strings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

class AiSettingsState {
    var url: String = ""
    var apiKey: String = ""
    var protocol: String = AiProtocol.OPENAI.name
    var model: String = "qwen-plus"
}

@State(
    name = "InsertStringsAiSettings",
    storages = [Storage("insertStringsAiSettings.xml")]
)
class AiSettingsService : PersistentStateComponent<AiSettingsState> {
    private var state = AiSettingsState()

    override fun getState(): AiSettingsState = state

    override fun loadState(state: AiSettingsState) {
        this.state = state
    }

    fun update(url: String, apiKey: String, protocol: AiProtocol, model: String) {
        state.url = url.trim()
        state.apiKey = apiKey.trim()
        state.protocol = protocol.name
        state.model = model.trim()
    }

    companion object {
        fun getInstance(): AiSettingsService {
            return ApplicationManager.getApplication().getService(AiSettingsService::class.java)
        }
    }
}
