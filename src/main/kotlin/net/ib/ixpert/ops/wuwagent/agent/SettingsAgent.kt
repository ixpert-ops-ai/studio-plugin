package net.ib.ixpert.ops.wuwagent.agent

import com.intellij.openapi.diagnostic.Logger
import net.ib.ixpert.ops.wuwagent.client.OllamaClient
import net.ib.ixpert.ops.wuwagent.client.OpenAIClient
import net.ib.ixpert.ops.wuwagent.setting.SettingsState

/**
 * 설정 화면 및 관리 작업을 전담하는 에이전트입니다.
 * LLM 추론이 아닌 서버 관리용 API 호출(예: 모델 목록 조회)을 담당합니다.
 */
object SettingsAgent {
    private val logger = Logger.getInstance(SettingsAgent::class.java)

    /**
     * apiType에 따라 적절한 엔드포인트에서 모델 목록을 동기적으로 가져옵니다.
     * @return 모델명 리스트, 실패 시 null 반환
     */
    fun fetchModelsSilent(
        baseUrl: String,
        apiKey: String,
        apiType: SettingsState.ApiType = SettingsState.getInstance().state.apiType
    ): List<String>? {
        val cleanUrl = baseUrl.trimEnd('/')
        if (cleanUrl.isBlank()) return null

        return try {
            val client = when (apiType) {
                SettingsState.ApiType.OPENAI_COMPATIBLE -> OpenAIClient()
                SettingsState.ApiType.OLLAMA -> OllamaClient()
            }
            client.fetchModels(cleanUrl, apiKey)
        } catch (e: Exception) {
            logger.warn("SettingsAgent: Silent fetch models failed", e)
            null
        }
    }
}
