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
        @Deprecated("타입별 필드(ollamaApiKey/openaiApiKey/aiproApiKey)로 대체됨. 레거시 XML 마이그레이션 전용, loadState() 이관 후에는 참조하지 말 것.")
        var apiKey: String = "ollama",         // 레거시 필드 (구 Ollama/aipro 공용 API Key)
        var ollamaApiKey: String = "ollama",   // Ollama 전용 API Key
        var openaiApiKey: String = "",         // OpenAI Compatible 전용 API Key
        var aiproApiKey: String = "",          // AIPro 전용 API Key
        var model: String = "qwen3-coder:30b",
        var temperature: Float = 0.1f,
        var timeoutSeconds: Int = 300,
        var contextWindow: Int = 32768,
        var enableLlmDebug: Boolean = false,
        var frameworkType: net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType = net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType.SPRING_BOOT_JPA,
        var isFirstRun: Boolean = true   // 최초 실행 여부 — 웰컴 다이얼로그 표시 제어
    ) {
        /** 현재 apiType에 대응하는 API Key를 반환합니다. 타입별 apiKey 참조는 항상 이 함수를 통할 것. */
        fun effectiveApiKey(): String = when (apiType) {
            ApiType.OLLAMA            -> ollamaApiKey
            ApiType.OPENAI_COMPATIBLE -> openaiApiKey
            ApiType.AIPRO             -> aiproApiKey
        }
    }

    private var myState = State()

    override fun getState(): State {
        return myState
    }

    override fun loadState(state: State) {
        // 마이그레이션: 레거시 공용 apiKey(v1) → 타입별 필드(v2)로 1회 이관
        // 대상: 저장된 apiType이 AIPRO였고 레거시 apiKey에 값이 있었는데 아직 aiproApiKey가 비어있는 경우
        @Suppress("DEPRECATION")
        if (state.apiType == ApiType.AIPRO && state.apiKey.isNotBlank() && state.aiproApiKey.isBlank()) {
            state.aiproApiKey = state.apiKey
        }
        myState = state
    }

    companion object {
        fun getInstance(): SettingsState {
            return ApplicationManager.getApplication().getService(SettingsState::class.java)
        }
    }
}
