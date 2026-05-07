package net.ib.ixpert.ops.wuwagent.client

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.io.HttpRequests
import net.ib.ixpert.ops.wuwagent.agent.TaskCancellationToken
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.OllamaMessage
import java.io.IOException

@Suppress("UnstableApiUsage")
class OpenAIClient : LLMClient {
    private val logger = Logger.getInstance(OpenAIClient::class.java)
    private val gson = Gson()

    override fun chat(
        systemPrompt: String,
        userCode: String,
        onChunk: ((String) -> Unit)?
    ): OllamaChatResponse? {
        val settings = net.ib.ixpert.ops.wuwagent.setting.SettingsState.getInstance().state
        val serverUrl = "${settings.baseUrl.trimEnd('/')}/v1/chat/completions"

        val requestBody = mapOf(
            "model" to settings.model,
            "messages" to listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to userCode)
            ),
            "stream" to (onChunk != null),
            "temperature" to settings.temperature,
            "max_tokens" to 4096
        )
        val jsonPayload = gson.toJson(requestBody)

        logger.info("OpenAI API Call (Stream=${onChunk != null}): url=$serverUrl, model=${settings.model}")

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
            OllamaChatResponse(null, null, OllamaMessage("assistant", errorMsg), true)
        } catch (e: Exception) {
            val errorMsg = "[Error] 예상치 못한 전송/파싱 오류: ${e.message} ($serverUrl)"
            logger.error(errorMsg, e)
            OllamaChatResponse(null, null, OllamaMessage("assistant", errorMsg), true)
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
