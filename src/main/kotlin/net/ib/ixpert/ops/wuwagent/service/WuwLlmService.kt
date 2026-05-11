package net.ib.ixpert.ops.wuwagent.service

import com.google.gson.Gson
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.Messages
import com.intellij.util.io.HttpRequests
import java.awt.Component
import javax.swing.SwingUtilities
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.client.OllamaClient
import net.ib.ixpert.ops.wuwagent.client.OpenAIClient
import net.ib.ixpert.ops.wuwagent.setting.SettingsState

@Suppress("UnstableApiUsage")
object WuwLlmService {
    private val logger = Logger.getInstance(WuwLlmService::class.java)
    private val gson = Gson()

    fun getClient(): LLMClient {
        val apiType = SettingsState.getInstance().state.apiType
        return when (apiType) {
            SettingsState.ApiType.OLLAMA -> OllamaClient()
            SettingsState.ApiType.OPENAI_COMPATIBLE,
            SettingsState.ApiType.AIPRO -> OpenAIClient()
        }
    }

    /**
     * 서버 연결 상태를 테스트합니다.
     * - Ollama: GET /api/tags
     * - OpenAI Compatible: POST /v1/chat/completions 에 최소 요청 (max_tokens=1)
     *
     * @param model OpenAI 모드일 때 사용할 모델명 (설정 화면 현재 입력값)
     */
    fun testConnection(
        parent: Component?,
        baseUrl: String,
        apiKey: String,
        apiType: SettingsState.ApiType = SettingsState.getInstance().state.apiType,
        model: String = SettingsState.getInstance().state.model,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        val cleanUrl = baseUrl.trimEnd('/')
        if (cleanUrl.isBlank()) {
            Messages.showErrorDialog(parent, "Base URL이 입력되지 않았습니다.", "Test Connection")
            onComplete?.invoke(false)
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                when (apiType) {
                    SettingsState.ApiType.OLLAMA -> {
                        // Ollama: 모델 목록 조회로 연결 확인
                        HttpRequests.request("$cleanUrl/api/tags").tuner {
                            if (apiKey.isNotBlank()) it.setRequestProperty("Authorization", "Bearer $apiKey")
                            it.connectTimeout = 5000
                            it.readTimeout = 5000
                        }.readString()
                    }
                    SettingsState.ApiType.OPENAI_COMPATIBLE,
                    SettingsState.ApiType.AIPRO -> {
                        // OpenAI Compatible / aipro: 실제 chat completions 엔드포인트에 최소 요청
                        val requestModel = model.ifBlank { "model" }
                        val payload = gson.toJson(mapOf(
                            "model" to requestModel,
                            "messages" to listOf(mapOf("role" to "user", "content" to "hi")),
                            "max_tokens" to 1
                        ))
                        HttpRequests.post("$cleanUrl/v1/chat/completions", "application/json").tuner {
                            if (apiKey.isNotBlank()) it.setRequestProperty("Authorization", "Bearer $apiKey")
                            it.connectTimeout = 5000
                            it.readTimeout = 10000
                        }.connect { req ->
                            req.write(payload)
                            req.readString()
                        }
                    }
                }

                ApplicationManager.getApplication().invokeLater({
                    if (parent != null) {
                        Messages.showInfoMessage(parent, "LLM 서버 연결에 성공했습니다!", "Test Connection")
                    } else {
                        Messages.showInfoMessage("LLM 서버 연결에 성공했습니다!", "Test Connection")
                    }
                    onComplete?.invoke(true)
                }, ModalityState.any())

            } catch (e: Exception) {
                logger.warn("Connection test failed", e)
                ApplicationManager.getApplication().invokeLater({
                    if (parent != null) {
                        Messages.showErrorDialog(parent, "연결 실패: ${e.message}", "Test Connection")
                    } else {
                        Messages.showErrorDialog("연결 실패: ${e.message}", "Test Connection")
                    }
                    onComplete?.invoke(false)
                }, ModalityState.any())
            }
        }
    }

    /**
     * 사용 가능한 모델 목록을 조회합니다.
     * OpenAI Compatible 모드에서는 /v1/models 미지원 서버가 많으므로
     * 실패 시 오류 다이얼로그를 표시합니다.
     */
    fun fetchModels(
        parent: Component?,
        baseUrl: String,
        apiKey: String,
        apiType: SettingsState.ApiType = SettingsState.getInstance().state.apiType,
        onComplete: ((Boolean) -> Unit)? = null,
        onSuccess: (List<String>) -> Unit
    ) {
        val cleanUrl = baseUrl.trimEnd('/')
        if (cleanUrl.isBlank()) {
            Messages.showErrorDialog(parent, "Base URL이 입력되지 않았습니다.", "Fetch Models")
            onComplete?.invoke(false)
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            val client: LLMClient = when (apiType) {
                SettingsState.ApiType.OLLAMA -> OllamaClient()
                SettingsState.ApiType.OPENAI_COMPATIBLE,
                SettingsState.ApiType.AIPRO -> OpenAIClient()
            }

            try {
                val models = client.fetchModels(cleanUrl, apiKey) ?: emptyList()

                ApplicationManager.getApplication().invokeLater({
                    if (models.isEmpty()) {
                        if (parent != null) {
                            Messages.showWarningDialog(parent, "조회된 모델이 없습니다.", "Fetch Models")
                        } else {
                            Messages.showWarningDialog("조회된 모델이 없습니다.", "Fetch Models")
                        }
                    } else {
                        onSuccess(models)
                        if (parent != null) {
                            Messages.showInfoMessage(parent, "성공적으로 ${models.size}개의 모델을 가져왔습니다.", "Fetch Models")
                        } else {
                            Messages.showInfoMessage("성공적으로 ${models.size}개의 모델을 가져왔습니다.", "Fetch Models")
                        }
                    }
                    onComplete?.invoke(true)
                }, ModalityState.any())
            } catch (e: Exception) {
                logger.warn("Fetch models failed", e)
                ApplicationManager.getApplication().invokeLater({
                    if (parent != null) {
                        Messages.showErrorDialog(parent, "모델 조회 실패: ${e.message}", "Fetch Models")
                    } else {
                        Messages.showErrorDialog("모델 조회 실패: ${e.message}", "Fetch Models")
                    }
                    onComplete?.invoke(false)
                }, ModalityState.any())
            }
        }
    }
}
