package net.ib.ixpert.ops.wuwagent.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.Messages
import com.intellij.util.io.HttpRequests
import java.io.IOException
import java.awt.Component
import javax.swing.SwingUtilities
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.client.OllamaClient
import net.ib.ixpert.ops.wuwagent.client.OpenAIClient
import net.ib.ixpert.ops.wuwagent.setting.SettingsState

@Suppress("UnstableApiUsage")
object WuwLlmService {
    private val logger = Logger.getInstance(WuwLlmService::class.java)

    fun getClient(): LLMClient {
        val apiType = SettingsState.getInstance().state.apiType
        return when (apiType) {
            SettingsState.ApiType.OLLAMA -> OllamaClient()
            SettingsState.ApiType.OPENAI_COMPATIBLE -> OpenAIClient()
        }
    }

    fun testConnection(
        parent: Component?,
        baseUrl: String,
        apiKey: String,
        apiType: SettingsState.ApiType = SettingsState.getInstance().state.apiType,
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
                val url = when (apiType) {
                    SettingsState.ApiType.OPENAI_COMPATIBLE -> "$cleanUrl/v1/models"
                    SettingsState.ApiType.OLLAMA -> "$cleanUrl/api/tags"
                }
                HttpRequests.request(url).tuner {
                    if (apiKey.isNotBlank()) it.setRequestProperty("Authorization", "Bearer $apiKey")
                    it.connectTimeout = 5000
                    it.readTimeout = 5000
                }.readString()

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
                SettingsState.ApiType.OPENAI_COMPATIBLE -> OpenAIClient()
                SettingsState.ApiType.OLLAMA -> OllamaClient()
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
