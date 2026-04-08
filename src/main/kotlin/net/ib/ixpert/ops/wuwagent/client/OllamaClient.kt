package net.ib.ixpert.ops.wuwagent.client

import com.google.gson.Gson
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.io.HttpRequests
import net.ib.ixpert.ops.wuwagent.model.OllamaChatRequest
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.OllamaMessage
import java.io.IOException

class OllamaClient {
    private val logger = Logger.getInstance(OllamaClient::class.java)
    private val gson = Gson()
    fun callChatApi(systemPrompt: String, userCode: String): OllamaChatResponse? {
        val settings = net.ib.ixpert.ops.wuwagent.setting.SettingsState.getInstance().state
        val serverUrl = "${settings.baseUrl.trimEnd('/')}/api/chat"

        val messages = listOf(
            OllamaMessage(role = "system", content = systemPrompt),
            OllamaMessage(role = "user", content = userCode)
        )
        val requestBody = OllamaChatRequest(
            model = settings.model,
            messages = messages,
            stream = false,
            options = mapOf(
                "temperature" to settings.temperature,
                "num_ctx" to settings.contextWindow
            )
        )
        val jsonPayload = gson.toJson(requestBody)
        
        logger.info("Ollama API Call: url=$serverUrl, model=${settings.model}")

        return try {
            val responseString = HttpRequests.post(serverUrl, "application/json")
                .tuner { connection -> 
                    if (settings.apiKey.isNotBlank()) {
                        connection.setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
                    }
                    val timeoutMs = settings.timeoutSeconds * 1000
                    connection.connectTimeout = 30_000 // 연결은 30초 고정
                    connection.readTimeout = timeoutMs
                }
                .connect { request ->
                    request.write(jsonPayload)
                    request.readString()
                }
            
            logger.info("Ollama API Raw Response: $responseString")
            
            gson.fromJson(responseString, OllamaChatResponse::class.java)
        } catch (e: IOException) {
            logger.error("Failed to communicate with Ollama API: ${e.message}", e)
            OllamaChatResponse(null, null, OllamaMessage("assistant", "[Error] Ollama 서버 연결/통신 실패 (Timeout 등): ${e.message}"), true)
        } catch (e: Exception) {
            logger.error("Unexpected error occurred while calling Ollama API: ${e.message}", e)
            OllamaChatResponse(null, null, OllamaMessage("assistant", "[Error] 응답 파싱 실패 (서버가 응답 포맷을 다르게 내려줬을 확률이 높음): ${e.message}"), true)
        }
    }
}
