package net.ib.ixpert.ops.wuwagent.agent

import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.io.HttpRequests

/**
 * 설정 화면 및 관리 작업을 전담하는 에이전트입니다.
 * LLM 추론이 아닌 서버 관리용 API 호출(예: 모델 목록 조회)을 담당합니다.
 */
object SettingsAgent {
    private val logger = Logger.getInstance(SettingsAgent::class.java)

    /**
     * Ollama 서버에서 사용 가능한 모델 목록을 동기적으로 가져옵니다.
     * @return 모델명 리스트, 실패 시 null 반환
     */
    fun fetchModelsSilent(baseUrl: String, apiKey: String): List<String>? {
        val cleanUrl = baseUrl.trimEnd('/')
        if (cleanUrl.isBlank()) return null

        return try {
            val url = "$cleanUrl/api/tags"
            val response = HttpRequests.request(url).tuner {
                if (apiKey.isNotBlank()) it.setRequestProperty("Authorization", "Bearer $apiKey")
                it.connectTimeout = 5000
                it.readTimeout = 5000
            }.readString()

            val jsonObject = JsonParser.parseString(response).asJsonObject
            val modelsArray = jsonObject.getAsJsonArray("models")
            modelsArray?.mapNotNull { it.asJsonObject.get("name")?.asString }
        } catch (e: Exception) {
            logger.warn("SettingsAgent: Silent fetch models failed", e)
            null
        }
    }
}
