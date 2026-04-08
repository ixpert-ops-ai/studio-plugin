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
        return callChatApiStream(systemPrompt, userCode, null)
    }

    /**
     * 스트리밍 방식으로 Ollama API를 호출합니다.
     * @param onChunk 각 데이터 청크 수신 시 호출될 콜백
     */
    fun callChatApiStream(
        systemPrompt: String, 
        userCode: String, 
        onChunk: ((String) -> Unit)? = null
    ): OllamaChatResponse? {
        val settings = net.ib.ixpert.ops.wuwagent.setting.SettingsState.getInstance().state
        val serverUrl = "${settings.baseUrl.trimEnd('/')}/api/chat"

        val messages = listOf(
            OllamaMessage(role = "system", content = systemPrompt),
            OllamaMessage(role = "user", content = userCode)
        )
        val requestBody = OllamaChatRequest(
            model = settings.model,
            messages = messages,
            stream = (onChunk != null),
            options = mapOf(
                "temperature" to settings.temperature,
                "num_ctx" to settings.contextWindow
            )
        )
        val jsonPayload = gson.toJson(requestBody)
        
        logger.info("Ollama API Call (Stream=${onChunk != null}): url=$serverUrl, model=${settings.model}")

        return try {
            HttpRequests.post(serverUrl, "application/json")
                .tuner { connection -> 
                    if (settings.apiKey.isNotBlank()) {
                        connection.setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
                    }
                    val timeoutMs = settings.timeoutSeconds * 1000
                    connection.connectTimeout = 30_000
                    connection.readTimeout = timeoutMs
                }
                .connect { request ->
                    request.write(jsonPayload)
                    
                    if (onChunk != null) {
                        val reader = request.connection.inputStream.bufferedReader()
                        var fullContent = ""
                        var lastResponse: OllamaChatResponse? = null
                        
                        reader.forEachLine { line ->
                            if (line.isNotBlank()) {
                                try {
                                    val chunkResponse = gson.fromJson(line, OllamaChatResponse::class.java)
                                    val content = chunkResponse.message?.content ?: ""
                                    if (content.isNotEmpty()) {
                                        fullContent += content
                                        onChunk(content)
                                    }
                                    if (chunkResponse.done == true) {
                                        lastResponse = chunkResponse
                                    }
                                } catch (e: Exception) {
                                    logger.warn("Failed to parse chunk: $line", e)
                                }
                            }
                        }
                        // 모든 청크 합친 결과 반환
                        lastResponse ?: OllamaChatResponse(
                            null, null, OllamaMessage("assistant", fullContent), true
                        )
                    } else {
                        val responseString = request.readString()
                        logger.info("Ollama API Raw Response: $responseString")
                        gson.fromJson(responseString, OllamaChatResponse::class.java)
                    }
                }
        } catch (e: IOException) {
            logger.error("Failed to communicate with Ollama API: ${e.message}", e)
            OllamaChatResponse(null, null, OllamaMessage("assistant", "[Error] Ollama 서버 연결/통신 실패 (Timeout 등): ${e.message}"), true)
        } catch (e: Exception) {
            logger.error("Unexpected error occurred while calling Ollama API: ${e.message}", e)
            OllamaChatResponse(null, null, OllamaMessage("assistant", "[Error] 응답 파싱 실패 (서버가 응답 포맷을 다르게 내려줬을 확률이 높음): ${e.message}"), true)
        }
    }
}
