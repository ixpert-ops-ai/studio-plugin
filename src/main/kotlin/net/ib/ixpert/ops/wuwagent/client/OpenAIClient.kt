package net.ib.ixpert.ops.wuwagent.client

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.io.HttpRequests
import net.ib.ixpert.ops.wuwagent.agent.TaskCancellationToken
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.OllamaMessage
import net.ib.ixpert.ops.wuwagent.service.DebugManager
import java.io.IOException

@Suppress("UnstableApiUsage")
class OpenAIClient : LLMClient {
    private val logger = Logger.getInstance(OpenAIClient::class.java)
    private val gson = Gson()

    override fun chat(
        systemPrompt: String,
        userCode: String,
        maxTokens: Int?,
        onChunk: ((String) -> Unit)?
    ): OllamaChatResponse? {
        val settings = net.ib.ixpert.ops.wuwagent.setting.SettingsState.getInstance().state
        val baseUrl = when (settings.apiType) {
            net.ib.ixpert.ops.wuwagent.setting.SettingsState.ApiType.AIPRO -> settings.aiproServerUrl
            else -> settings.openaiServerUrl
        }
        val serverUrl = if (baseUrl.endsWith("/chat/completions")) {
            baseUrl
        } else if (baseUrl.contains("/openai")) {
            "${baseUrl.trimEnd('/')}/chat/completions"
        } else {
            "${baseUrl.trimEnd('/')}/v1/chat/completions"
        }

        val messagesList = listOf(
            mapOf("role" to "system", "content" to systemPrompt),
            mapOf("role" to "user", "content" to userCode)
        )
        val requestBody = mapOf(
            "model" to settings.model,
            "messages" to messagesList,
            "stream" to (onChunk != null),
            "temperature" to settings.temperature,
            "max_tokens" to (maxTokens ?: 4096)
        )
        val jsonPayload = gson.toJson(requestBody)
        val isStreaming = onChunk != null

        logger.info("OpenAI API Call (Stream=$isStreaming): url=$serverUrl, model=${settings.model}")

        val startMs = System.currentTimeMillis()
        val debugEntry = if (settings.enableLlmDebug) {
            try {
                DebugManager.getInstance().newEntry(settings.model, serverUrl, jsonPayload, messagesList.size, isStreaming)
            } catch (_: Exception) { null }
        } else null

        return try {
            val result = HttpRequests.post(serverUrl, "application/json")
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
                        val inputStream = request.connection.inputStream
                        TaskCancellationToken.activeInputStream = inputStream
                        val reader = inputStream.bufferedReader()
                        var fullContent = ""

                        try {
                            var line = reader.readLine()
                            while (line != null) {
                                if (TaskCancellationToken.isCancelled.get()) {
                                    logger.info("OpenAIClient: 취소 감지 → 스트림 중단")
                                    break
                                }
                                if (line.startsWith("data: ")) {
                                    val data = line.removePrefix("data: ").trim()
                                    if (data == "[DONE]") break
                                    try {
                                        val json = JsonParser.parseString(data).asJsonObject
                                        val delta = json.getAsJsonArray("choices")
                                            ?.get(0)?.asJsonObject
                                            ?.getAsJsonObject("delta")
                                        val content = delta?.get("content")?.asString ?: ""
                                        if (content.isNotEmpty()) {
                                            fullContent += content
                                            onChunk(content)
                                        }
                                    } catch (e: Exception) {
                                        logger.warn("Failed to parse SSE chunk: $line", e)
                                    }
                                }
                                line = reader.readLine()
                            }
                        } catch (e: IOException) {
                            if (TaskCancellationToken.isCancelled.get()) {
                                logger.info("OpenAIClient: 스트림 취소로 인한 IOException (정상) — ${e.message}")
                            } else {
                                throw e
                            }
                        } finally {
                            TaskCancellationToken.activeInputStream = null
                        }

                        try {
                            debugEntry?.responseText = fullContent
                            debugEntry?.responseLength = fullContent.length
                        } catch (_: Exception) {}

                        OllamaChatResponse(
                            model = settings.model,
                            createdAt = null,
                            message = OllamaMessage("assistant", fullContent),
                            done = true
                        )
                    } else {
                        val httpConn = request.connection as? java.net.HttpURLConnection
                        TaskCancellationToken.activeHttpConnection = httpConn
                        try {
                            val responseString = request.readString()
                            logger.info("OpenAI API Raw Response (len=${responseString.length})")
                            try {
                                debugEntry?.responseText = responseString
                                debugEntry?.responseLength = responseString.length
                            } catch (_: Exception) {}
                            val json = JsonParser.parseString(responseString).asJsonObject
                            val content = json.getAsJsonArray("choices")
                                ?.get(0)?.asJsonObject
                                ?.getAsJsonObject("message")
                                ?.get("content")?.asString ?: ""
                            OllamaChatResponse(
                                model = settings.model,
                                createdAt = null,
                                message = OllamaMessage("assistant", content),
                                done = true
                            )
                        } catch (e: IOException) {
                            if (TaskCancellationToken.isCancelled.get()) {
                                logger.info("OpenAIClient: 비스트리밍 취소로 인한 IOException (정상) — ${e.message}")
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
            result
        } catch (e: IOException) {
            val errorMsg = when {
                e.message?.contains("timeout", ignoreCase = true) == true ->
                    "[Error] OpenAI 서버 응답 타임아웃 ($serverUrl). 설정에서 Timeout 시간을 늘려보세요."
                e.message?.contains("refused", ignoreCase = true) == true ->
                    "[Error] OpenAI 서버 연결 거부 ($serverUrl). 서버가 실행 중인지 확인하세요."
                else ->
                    "[Error] OpenAI 서버 통신 실패: ${e.message} ($serverUrl)"
            }
            logger.error(errorMsg, e)
            try {
                if (debugEntry != null) {
                    debugEntry.durationMs = System.currentTimeMillis() - startMs
                    debugEntry.isSuccess = false
                    debugEntry.errorMessage = errorMsg
                    DebugManager.getInstance().addLog(debugEntry)
                }
            } catch (_: Exception) {}
            OllamaChatResponse(null, null, OllamaMessage("assistant", errorMsg), true)
        } catch (e: Exception) {
            val errorMsg = "[Error] 예상치 못한 전송/파싱 오류: ${e.message} ($serverUrl)"
            logger.error(errorMsg, e)
            try {
                if (debugEntry != null) {
                    debugEntry.durationMs = System.currentTimeMillis() - startMs
                    debugEntry.isSuccess = false
                    debugEntry.errorMessage = errorMsg
                    DebugManager.getInstance().addLog(debugEntry)
                }
            } catch (_: Exception) {}
            OllamaChatResponse(null, null, OllamaMessage("assistant", errorMsg), true)
        }
    }

    override fun chatWithTools(
        systemPrompt: String,
        messages: List<net.ib.ixpert.ops.wuwagent.model.ChatMessage>,
        maxTokens: Int?,
        tools: List<net.ib.ixpert.ops.wuwagent.model.ToolDefinition>?,
        toolChoice: Any?
    ): net.ib.ixpert.ops.wuwagent.model.ChatCompletionResponse? {
        val settings = net.ib.ixpert.ops.wuwagent.setting.SettingsState.getInstance().state
        val baseUrl = when (settings.apiType) {
            net.ib.ixpert.ops.wuwagent.setting.SettingsState.ApiType.AIPRO -> settings.aiproServerUrl
            else -> settings.openaiServerUrl
        }
        val serverUrl = if (baseUrl.endsWith("/chat/completions")) {
            baseUrl
        } else if (baseUrl.contains("/openai")) {
            "${baseUrl.trimEnd('/')}/chat/completions"
        } else {
            "${baseUrl.trimEnd('/')}/v1/chat/completions"
        }

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
            "temperature" to settings.temperature,
            "max_tokens" to (maxTokens ?: 4096)
        )
        if (!tools.isNullOrEmpty()) {
            requestBody["tools"] = tools
            if (toolChoice != null) {
                requestBody["tool_choice"] = toolChoice
            }
        }
        
        val jsonPayload = gson.toJson(requestBody)

        logger.info("OpenAI API Call (ToolCalling): url=$serverUrl, model=${settings.model}")
        
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
                    val timeoutMs = settings.timeoutSeconds * 1000
                    connection.connectTimeout = 30_000
                    connection.readTimeout = timeoutMs
                }
                .connect { request ->
                    request.write(jsonPayload)
                    request.readString()
                }

            logger.info("OpenAI API ToolCalling Response (len=${responseString.length})")
            
            try {
                if (debugEntry != null) {
                    debugEntry.durationMs = System.currentTimeMillis() - startMs
                    debugEntry.isSuccess = true
                    debugEntry.responseText = responseString
                    debugEntry.responseLength = responseString.length
                    DebugManager.getInstance().addLog(debugEntry)
                }
            } catch (_: Exception) {}
            
            gson.fromJson(responseString, net.ib.ixpert.ops.wuwagent.model.ChatCompletionResponse::class.java)
        } catch (e: Exception) {
            val errorMsg = e.message ?: "Unknown error"
            logger.error("OpenAI API ToolCalling Failed: $errorMsg", e)
            
            try {
                if (debugEntry != null) {
                    debugEntry.durationMs = System.currentTimeMillis() - startMs
                    debugEntry.isSuccess = false
                    debugEntry.errorMessage = errorMsg
                    DebugManager.getInstance().addLog(debugEntry)
                }
            } catch (_: Exception) {}
            
            throw e // Throw so LlmCandidateSelector can catch and fallback
        }
    }

    override fun fetchModels(baseUrl: String, apiKey: String): List<String>? {
        val cleanUrl = baseUrl.trimEnd('/')
        if (cleanUrl.isBlank()) return null

        return try {
            val url = "$cleanUrl/v1/models"
            val response = HttpRequests.request(url).tuner {
                if (apiKey.isNotBlank()) it.setRequestProperty("Authorization", "Bearer $apiKey")
                it.connectTimeout = 5000
                it.readTimeout = 5000
            }.readString()

            val json = JsonParser.parseString(response).asJsonObject
            val data = json.getAsJsonArray("data")
            data?.mapNotNull { it.asJsonObject.get("id")?.asString }
        } catch (e: Exception) {
            logger.warn("OpenAIClient: fetchModels failed", e)
            null
        }
    }
}
