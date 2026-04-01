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
    private val serverUrl = "http://ollama.jodongik.cloud:11434/api/chat"
    private val modelName = "qwen3-coder:30b"

    fun callChatApi(systemPrompt: String, userCode: String): OllamaChatResponse? {
        val messages = listOf(
            OllamaMessage(role = "system", content = systemPrompt),
            OllamaMessage(role = "user", content = userCode)
        )
        val requestBody = OllamaChatRequest(
            model = modelName,
            messages = messages,
            stream = false
        )
        val jsonPayload = gson.toJson(requestBody)

        return try {
            val responseString = HttpRequests.post(serverUrl, "application/json")
                .tuner { connection -> 
                    connection.setRequestProperty("Authorization", "Bearer ollama")
                    connection.connectTimeout = 30_000 // 30초
                    connection.readTimeout = 180_000 // 3분
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
