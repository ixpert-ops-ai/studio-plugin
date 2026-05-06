package net.ib.ixpert.ops.wuwagent.client

import com.google.gson.Gson
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.io.HttpRequests
import net.ib.ixpert.ops.wuwagent.agent.TaskCancellationToken
import net.ib.ixpert.ops.wuwagent.model.OllamaChatRequest
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.OllamaMessage
import java.io.IOException

@Suppress("UnstableApiUsage")
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
                "num_ctx" to settings.contextWindow,
                "num_predict" to 4096,
                "repeat_penalty" to 1.5,
                "repeat_last_n" to 256
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
                        val inputStream = request.connection.inputStream
                        // 취소 시 강제 close()로 스트림 즉시 종료할 수 있도록 등록
                        TaskCancellationToken.activeInputStream = inputStream
                        val reader = inputStream.bufferedReader()
                        var fullContent = ""
                        var lastResponse: OllamaChatResponse? = null

                        try {
                            var line = reader.readLine()
                            while (line != null) {
                                // 매 청크마다 취소 여부 확인 → 취소 시 즉시 루프 탈출
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
                            // activeInputStream.close()로 인한 IOException은 취소의 정상적인 결과
                            if (TaskCancellationToken.isCancelled.get()) {
                                logger.info("OllamaClient: 스트림 취소로 인한 IOException (정상) — ${e.message}")
                            } else {
                                throw e
                            }
                        } finally {
                            TaskCancellationToken.activeInputStream = null
                        }

                        // Ollama done 청크는 content가 비어있으므로 누적된 fullContent로 교체하여 반환
                        // (취소 시 lastResponse가 null이면 fullContent만으로 안전하게 반환)
                        OllamaChatResponse(
                            model = lastResponse?.model,
                            createdAt = lastResponse?.createdAt,
                            message = OllamaMessage("assistant", fullContent),
                            done = true
                        )
                    } else {
                        // ── 비스트리밍 취소 처리 ────────────────────────────────────────
                        // getInputStream() 자체가 LLM 추론 완료 때까지 블로킹되므로
                        // inputStream 등록은 불가. 그 전에 HttpURLConnection 레퍼런스를 저장하고
                        // cancel() 시 disconnect()로 소켓을 강제 닫아 블로킹을 IOException으로 깨운다.
                        val httpConn = request.connection as? java.net.HttpURLConnection
                        TaskCancellationToken.activeHttpConnection = httpConn
                        try {
                            val responseString = request.readString()
                            logger.info("Ollama API Raw Response (len=${responseString.length})")
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
                        // ─────────────────────────────────────────────────────────────────
                    }
                }
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
            OllamaChatResponse(null, null, OllamaMessage("assistant", errorMsg), true)
        } catch (e: Exception) {
            val errorMsg = "[Error] 예상치 못한 전송/파싱 오류: ${e.message} ($serverUrl)"
            logger.error(errorMsg, e)
            OllamaChatResponse(null, null, OllamaMessage("assistant", errorMsg), true)
        }
    }
}
