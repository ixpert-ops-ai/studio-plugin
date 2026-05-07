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
        onChunk: ((String) -> Unit)?
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
                "num_ctx" to settings.contextWindow,
                "num_predict" to 4096,
                "repeat_penalty" to 1.5,
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
                            gson.fromJson(responseString, OllamaChatResponse::class.java)
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
            result
        } catch (e: IOException) {
            val errorMsg = when {
                e.message?.contains("timeout", ignoreCase = true) == true ->
                    "[Error] Ollama 서버 응답 타임아웃 ($serverUrl). 설정에서 Timeout 시간을 늘려보세요."
                e.message?.contains("refused", ignoreCase = true) == true ->
                    "[Error] Ollama 서버 연결 거부 ($serverUrl). 서버가 실행 중인지 확인하세요."
                else ->
                    "[Error] Ollama 서버 통신 실패: ${e.message} ($serverUrl)"
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
