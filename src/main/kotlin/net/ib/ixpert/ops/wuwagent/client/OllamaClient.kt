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
                }
                .connect { request ->
                    request.write(jsonPayload)
                    request.readString()
                }
            
            gson.fromJson(responseString, OllamaChatResponse::class.java)
        } catch (e: IOException) {
            logger.error("Failed to communicate with Ollama API: ${e.message}", e)
            null
        } catch (e: Exception) {
            logger.error("Unexpected error occurred while calling Ollama API: ${e.message}", e)
            null
        }
    }
}
