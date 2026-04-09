package net.ib.ixpert.ops.wuwagent.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.Messages
import com.intellij.util.io.HttpRequests
import com.google.gson.JsonParser
import java.io.IOException
import java.awt.Component
import javax.swing.SwingUtilities

/**
 * Ollama 서버 통신 및 결과 알림을 총괄하는 서비스입니다.
 * 모든 서버 호출은 비동기로 수행되며, 결과는 IntelliJ 네이티브 메시지 박스로 표시됩니다.
 */
object WuwLlmService {
    private val logger = Logger.getInstance(WuwLlmService::class.java)

    /**
     * 서버 연결 상태를 테스트하고 결과를 메시지로 표시합니다.
     */
    fun testConnection(parent: Component?, baseUrl: String, apiKey: String, onComplete: ((Boolean) -> Unit)? = null) {
        val cleanUrl = baseUrl.trimEnd('/')
        if (cleanUrl.isBlank()) {
            Messages.showErrorDialog(parent, "Base URL이 입력되지 않았습니다.", "Test Connection")
            onComplete?.invoke(false)
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val url = "$cleanUrl/api/tags"
                HttpRequests.request(url).tuner {
                    if (apiKey.isNotBlank()) it.setRequestProperty("Authorization", "Bearer $apiKey")
                    it.connectTimeout = 5000
                    it.readTimeout = 5000
                }.readString()

                ApplicationManager.getApplication().invokeLater({
                    if (parent != null) {
                        Messages.showInfoMessage(parent, "Ollama 서버 연결에 성공했습니다!", "Test Connection")
                    } else {
                        Messages.showInfoMessage("Ollama 서버 연결에 성공했습니다!", "Test Connection")
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
     * 사용 가능한 모델 목록을 조회하고 콤보박스를 갱신하거나 성공 메시지를 표시합니다.
     */
    fun fetchModels(
        parent: Component?,
        baseUrl: String,
        apiKey: String,
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
            try {
                val url = "$cleanUrl/api/tags"
                val response = HttpRequests.request(url).tuner {
                    if (apiKey.isNotBlank()) it.setRequestProperty("Authorization", "Bearer $apiKey")
                    it.connectTimeout = 5000
                    it.readTimeout = 5000
                }.readString()

                val jsonObject = JsonParser.parseString(response).asJsonObject
                val modelsArray = jsonObject.getAsJsonArray("models")
                val models = modelsArray?.mapNotNull { it.asJsonObject.get("name")?.asString } ?: emptyList()

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
