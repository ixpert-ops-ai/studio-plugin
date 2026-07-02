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

    enum class ApiType { OPENAI_COMPATIBLE, AIPRO, OLLAMA }

    data class State(
        var apiType: ApiType = ApiType.OPENAI_COMPATIBLE,
        var ollamaServerUrl: String = "http://ollama.ixpertops.cloud",
        var openaiServerUrl: String = "http://vllm.ixpertops.cloud",
        var aiproServerUrl: String = "https://aipro.samsungcard.biz:20443/open/api",
        var apiKey: String = "ollama",         // Ollama / aipro 공용 API Key
        var openaiApiKey: String = "",         // OpenAI Compatible 전용 API Key
        var model: String = "qwen3-coder:30b",
        var temperature: Float = 0.1f,
        var timeoutSeconds: Int = 300,
        var contextWindow: Int = 32768,
        var enableLlmDebug: Boolean = false,
        var frameworkType: net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType = net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType.SPRING_BOOT_JPA,
        var isFirstRun: Boolean = true   // 최초 실행 여부 — 웰컴 다이얼로그 표시 제어
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
