package net.ib.ixpert.ops.wuwagent.setting

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.Service
import com.intellij.openapi.application.ApplicationManager

@Service(Service.Level.APP)
@State(
    name = "net.ib.ixpert.ops.wuwagent.setting.SettingsState",
    storages = [Storage("WuwAgentSettings.xml")]
)
class SettingsState : PersistentStateComponent<SettingsState.State> {

    data class State(
        var baseUrl: String = "http://ollama.jodongik.cloud",
        var apiKey: String = "ollama",
        var model: String = "qwen3-coder:30b",
        var temperature: Float = 0.1f,
        var timeoutSeconds: Int = 60,
        var contextWindow: Int = 4096
    )

    private var myState = State()

    override fun getState(): State {
        return myState
    }

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        fun getInstance(): SettingsState {
            return ApplicationManager.getApplication().getService(SettingsState::class.java)
        }
    }
}
