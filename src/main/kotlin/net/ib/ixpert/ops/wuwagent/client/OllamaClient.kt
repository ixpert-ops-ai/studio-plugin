package net.ib.ixpert.ops.wuwagent.client

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.io.HttpRequests
import net.ib.ixpert.ops.wuwagent.agent.TaskCancellationToken
import net.ib.ixpert.ops.wuwagent.model.OllamaChatRequest
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.OllamaMessage
import net.ib.ixpert.ops.wuwagent.service.DebugManager
import java.io.IOException

@Suppress("UnstableApiUsage")
class OllamaClient : LLMClient {
    private val logger = Logger.getInstance(OllamaClient::class.java)
    private val gson = Gson()

    override fun chat(
        systemPrompt: String,
        userCode: String,
        maxTokens: Int?,
        onChunk: ((String) -> Unit)?
    ): OllamaChatResponse? {
        val settings = net.ib.ixpert.ops.wuwagent.setting.SettingsState.getInstance().state
        val serverUrl = "${settings.ollamaServerUrl.trimEnd('/')}/api/chat"

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
                "num_ctx" to settings.contextWindow,
                "num_predict" to (maxTokens ?: 4096),
                "repeat_penalty" to 1.1,
                "repeat_last_n" to 256
            )
        )
        val jsonPayload = gson.toJson(requestBody)
        val isStreaming = onChunk != null

        logger.info("Ollama API Call (Stream=$isStreaming): url=$serverUrl, model=${settings.model}")

        val startMs = System.currentTimeMillis()
        val debugEntry = if (settings.enableLlmDebug) {
            try {
                DebugManager.getInstance().newEntry(settings.model, serverUrl, jsonPayload, messages.size, isStreaming)
            } catch (_: Exception) { null }
        } else null

        val maxAttempts = 3
        var attempts = 0
        var hasStreamedData = false
        var lastException: Exception? = null

        while (attempts < maxAttempts) {
            attempts++
            try {
                val result = HttpRequests.post(serverUrl, "application/json")
                    .tuner { connection ->
                        if (settings.apiKey.isNotBlank()) {
                            connection.setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
                        }
                        // 서버 측 Keep-Alive 커넥션 강제 종료 문제로 인한 간헐적 빈 응답 방지
                        connection.setRequestProperty("Connection", "close")
                        val timeoutMs = settings.timeoutSeconds * 1000
                        connection.connectTimeout = 30_000
                        connection.readTimeout = timeoutMs
                    }
                    .connect { request ->
                    request.write(jsonPayload)

                    if (onChunk != null) {
                        val inputStream = request.connection.inputStream
                        TaskCancellationToken.activeInputStream = inputStream
                        val reader = inputStream.bufferedReader()
                        var fullContent = ""
                        var lastResponse: OllamaChatResponse? = null

                        try {
                            var line = reader.readLine()
                            while (line != null) {
                                if (TaskCancellationToken.isCancelled.get()) {
                                    logger.info("OllamaClient: 취소 감지 → 스트림 중단")
                                    break
                                }
                                if (line.isNotBlank()) {
                                    try {
                                        val chunkResponse = gson.fromJson(line, OllamaChatResponse::class.java)
                                        if (!chunkResponse.error.isNullOrBlank()) {
                                            throw Exception("Server Error: ${chunkResponse.error}")
                                        }
                                        val content = chunkResponse.message?.content ?: ""
                                        if (content.isNotEmpty()) {
                                            fullContent += content
                                            hasStreamedData = true
                                            onChunk(content)
                                        }
                                        if (chunkResponse.done == true) {
                                            lastResponse = chunkResponse
                                        }
                                    } catch (e: Exception) {
                                        logger.warn("Failed to parse chunk: $line", e)
                                        if (e.message?.startsWith("Server Error:") == true) {
                                            throw e // Rethrow to be caught by the outer block and shown to the user
                                        }
                                    }
                                }
                                line = reader.readLine()
                            }
                        } catch (e: IOException) {
                            if (TaskCancellationToken.isCancelled.get()) {
                                logger.info("OllamaClient: 스트림 취소로 인한 IOException (정상) — ${e.message}")
                            } else {
                                throw e
                            }
                        } finally {
                            TaskCancellationToken.activeInputStream = null
                        }

                        if (fullContent.isBlank()) {
                            throw Exception("서버에서 빈 응답(0 byte)을 반환했습니다. (Ollama 엔진 OOM 또는 프록시 강제 종료 의심)")
                        }

                        try {
                            debugEntry?.responseText = fullContent
                            debugEntry?.responseLength = fullContent.length
                        } catch (_: Exception) {}

                        OllamaChatResponse(
                            model = lastResponse?.model,
                            createdAt = lastResponse?.createdAt,
                            message = OllamaMessage("assistant", fullContent),
                            done = true
                        )
                    } else {
                        val httpConn = request.connection as? java.net.HttpURLConnection
                        TaskCancellationToken.activeHttpConnection = httpConn
                        try {
                            val responseString = request.readString()
                            logger.info("Ollama API Raw Response (len=${responseString.length})")
                            try {
                                debugEntry?.responseText = responseString
                                debugEntry?.responseLength = responseString.length
                            } catch (_: Exception) {}
                            val parsedResponse = gson.fromJson(responseString, OllamaChatResponse::class.java)
                            if (!parsedResponse.error.isNullOrBlank()) {
                                throw Exception("Server Error: ${parsedResponse.error}")
                            }
                            parsedResponse
                        } catch (e: IOException) {
                            if (TaskCancellationToken.isCancelled.get()) {
                                logger.info("OllamaClient: 비스트리밍 취소로 인한 IOException (정상) — ${e.message}")
                                OllamaChatResponse(null, null, OllamaMessage("assistant", "__cancelled__"), true)
                            } else {
                                throw e
                            }
                        } finally {
                            TaskCancellationToken.activeHttpConnection = null
                        }
                    }
                }
            try {
                if (debugEntry != null) {
                    debugEntry.durationMs = System.currentTimeMillis() - startMs
                    debugEntry.isSuccess = true
                    DebugManager.getInstance().addLog(debugEntry)
                }
            } catch (_: Exception) {}
                return result
            } catch (e: IOException) {
                lastException = e
                if (TaskCancellationToken.isCancelled.get()) {
                    val errorMsg = "[Error] Ollama 서버 연동이 취소되었습니다."
                    return OllamaChatResponse(null, null, OllamaMessage("assistant", errorMsg), true)
                }
                
                if (hasStreamedData) {
                    logger.warn("Ollama API: 이미 스트리밍이 진행된 상태에서 에러 발생 → 재시도 불가", e)
                    break
                }
                
                if (attempts < maxAttempts) {
                    logger.warn("Ollama API 통신 실패 (Attempt $attempts/$maxAttempts). 3초 후 재시도합니다: ${e.message}")
                    Thread.sleep(3000)
                }
            } catch (e: Exception) {
                lastException = e
                if (TaskCancellationToken.isCancelled.get()) {
                    val errorMsg = "[Error] Ollama 서버 연동이 취소되었습니다."
                    return OllamaChatResponse(null, null, OllamaMessage("assistant", errorMsg), true)
                }

                if (hasStreamedData) {
                    logger.warn("Ollama API: 이미 스트리밍이 진행된 상태에서 에러 발생 → 재시도 불가", e)
                    break
                }

                if (attempts < maxAttempts) {
                    logger.warn("Ollama API 예외 발생 (Attempt $attempts/$maxAttempts). 3초 후 재시도합니다: ${e.message}")
                    Thread.sleep(3000)
                }
            }
        }

        // 모든 재시도 실패 시
        val finalException = lastException ?: Exception("Unknown error")
        val errorMsg = when {
            finalException.message?.contains("timeout", ignoreCase = true) == true ->
                "[Error] Ollama 서버 응답 타임아웃 ($serverUrl). 설정에서 Timeout 시간을 늘려보세요."
            finalException.message?.contains("refused", ignoreCase = true) == true ->
                "[Error] Ollama 서버 연결 거부 ($serverUrl). 서버가 실행 중인지 확인하세요."
            else ->
                "[Error] Ollama 서버 통신 실패 (재시도 $maxAttempts 회 초과): ${finalException.message}"
        }
        logger.error(errorMsg, finalException)
        try {
            if (debugEntry != null) {
                debugEntry.durationMs = System.currentTimeMillis() - startMs
                debugEntry.isSuccess = false
                debugEntry.errorMessage = errorMsg
                DebugManager.getInstance().addLog(debugEntry)
            }
        } catch (_: Exception) {}
        
        return OllamaChatResponse(null, null, OllamaMessage("assistant", errorMsg), true)
    }

    override fun chatWithTools(
        systemPrompt: String,
        messages: List<net.ib.ixpert.ops.wuwagent.model.ChatMessage>,
        maxTokens: Int?,
        tools: List<net.ib.ixpert.ops.wuwagent.model.ToolDefinition>?,
        toolChoice: Any?
    ): net.ib.ixpert.ops.wuwagent.model.ChatCompletionResponse? {
        val settings = net.ib.ixpert.ops.wuwagent.setting.SettingsState.getInstance().state
        val serverUrl = "${settings.ollamaServerUrl.trimEnd('/')}/api/chat"

        val requestMessages = mutableListOf<Map<String, Any?>>()
        requestMessages.add(mapOf("role" to "system", "content" to systemPrompt))
        
        for (msg in messages) {
            val map = mutableMapOf<String, Any?>("role" to msg.role)
            if (msg.content != null) map["content"] = msg.content
            if (msg.name != null) map["name"] = msg.name
            if (msg.toolCallId != null) map["tool_call_id"] = msg.toolCallId
            if (msg.toolCalls != null) map["tool_calls"] = msg.toolCalls
            requestMessages.add(map)
        }

        val requestBody = mutableMapOf<String, Any?>(
            "model" to settings.model,
            "messages" to requestMessages,
            "stream" to false,
            "options" to mapOf(
                "temperature" to settings.temperature,
                "num_ctx" to settings.contextWindow,
                "num_predict" to (maxTokens ?: 4096),
                "repeat_penalty" to 1.1,
                "repeat_last_n" to 256
            )
        )
        if (!tools.isNullOrEmpty()) {
            requestBody["tools"] = tools
        }

        val jsonPayload = gson.toJson(requestBody)

        logger.info("Ollama API Call (ToolCalling): url=$serverUrl, model=${settings.model}")
        
        val startMs = System.currentTimeMillis()
        val debugEntry = if (settings.enableLlmDebug) {
            try {
                DebugManager.getInstance().newEntry(settings.model, serverUrl, jsonPayload, messages.size + 1, false)
            } catch (_: Exception) { null }
        } else null

        return try {
            val responseString = HttpRequests.post(serverUrl, "application/json")
                .tuner { connection ->
                    if (settings.apiKey.isNotBlank()) {
                        connection.setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
                    }
                    connection.setRequestProperty("Connection", "close")
                    val timeoutMs = settings.timeoutSeconds * 1000
                    connection.connectTimeout = 30_000
                    connection.readTimeout = timeoutMs
                }
                .connect { request ->
                    request.write(jsonPayload)
                    request.readString()
                }

            logger.info("Ollama API ToolCalling Response (len=${responseString.length})")
            
            try {
                if (debugEntry != null) {
                    debugEntry.durationMs = System.currentTimeMillis() - startMs
                    debugEntry.isSuccess = true
                    debugEntry.responseText = responseString
                    debugEntry.responseLength = responseString.length
                    DebugManager.getInstance().addLog(debugEntry)
                }
            } catch (_: Exception) {}
            
            // Ollama's response format is similar but not exactly OpenAI's ChatCompletionResponse
            // Let's parse as generic JSON and map to ChatCompletionResponse
            val json = JsonParser.parseString(responseString).asJsonObject
            if (json.has("error")) {
                throw Exception("Ollama Server Error: ${json.get("error").asString}")
            }
            
            val messageObj = json.getAsJsonObject("message")
            val content = if (messageObj.has("content") && !messageObj.get("content").isJsonNull) messageObj.get("content").asString else null
            val toolCallsArray = messageObj.getAsJsonArray("tool_calls")
            
            val toolCalls = if (toolCallsArray != null && toolCallsArray.size() > 0) {
                gson.fromJson(toolCallsArray, Array<net.ib.ixpert.ops.wuwagent.model.ToolCall>::class.java).toList()
            } else null
            
            val chatMessage = net.ib.ixpert.ops.wuwagent.model.ChatMessage(
                role = "assistant",
                content = content,
                toolCalls = toolCalls
            )
            
            net.ib.ixpert.ops.wuwagent.model.ChatCompletionResponse(
                id = null,
                choices = listOf(net.ib.ixpert.ops.wuwagent.model.ChatChoice(index = 0, message = chatMessage, finishReason = "stop"))
            )
        } catch (e: Exception) {
            val errorMsg = e.message ?: "Unknown error"
            logger.error("Ollama API ToolCalling Failed: $errorMsg", e)
            
            try {
                if (debugEntry != null) {
                    debugEntry.durationMs = System.currentTimeMillis() - startMs
                    debugEntry.isSuccess = false
                    debugEntry.errorMessage = errorMsg
                    DebugManager.getInstance().addLog(debugEntry)
                }
            } catch (_: Exception) {}
            
            throw e
        }
    }

    override fun fetchModels(baseUrl: String, apiKey: String): List<String>? {
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
            logger.warn("OllamaClient: fetchModels failed", e)
            null
        }
    }
}
