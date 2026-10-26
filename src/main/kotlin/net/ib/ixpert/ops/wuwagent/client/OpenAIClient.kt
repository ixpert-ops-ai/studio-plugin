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
class OpenAIClient(
    // 짧은 비스트림 호출(max_tokens <= SHORT_CALL_MAX_TOKENS)의 읽기 타임아웃(초)과 재시도 전 대기(ms). 테스트에서 줄여 쓴다.
    private val shortReadTimeoutSec: Int = NON_STREAM_READ_TIMEOUT_SEC,
    private val retryWaitMs: Long = NON_STREAM_RETRY_WAIT_MS,
    // 테스트에서 가짜 서버로 보내기 위한 설정 주입 지점. null이면 저장된 설정(SettingsState)을 쓴다. 후행 람다로 넘길 수 있게 마지막에 둔다.
    private val settingsOverride: (() -> net.ib.ixpert.ops.wuwagent.setting.SettingsState.State)? = null
) : LLMClient {
    private val logger = Logger.getInstance(OpenAIClient::class.java)
    private val gson = Gson()

    companion object {
        /**
         * 비스트림 호출은 첫 바이트 없이 약 125초가 지나면 중간 계층이 524로 끊는다(B-56). 정상 비스트림 929건 중 90초를 넘은 것은 정체로 보이는 1건뿐이라
         * 짧은 호출(max_tokens <= SHORT_CALL_MAX_TOKENS)의 읽기 타임아웃을 90초로 두고, 524/502/503/504 또는 타임아웃이면 1회만 다시 호출한다.
         * max_tokens가 큰 호출(문서 생성 등)은 오래 걸릴 수 있어 설정의 타임아웃을 그대로 쓰고 상태 코드 오류에만 재시도한다.
         */
        const val NON_STREAM_READ_TIMEOUT_SEC = 90
        const val NON_STREAM_RETRY_WAIT_MS = 1000L
        const val SHORT_CALL_MAX_TOKENS = 1000
        val RETRYABLE_STATUS = setOf(502, 503, 504, 524)

        /** 도구 호출 스트림이 중간에 끊겨(인자 JSON 해석 불가) 다시 호출한 횟수와, 다시 호출해도 끊겨 실패로 남은 횟수. 평가 보고서가 읽는다. */
        val streamRetryCount = java.util.concurrent.atomic.AtomicInteger()
        val streamRetryFailedCount = java.util.concurrent.atomic.AtomicInteger()

        /** 도구 호출 인자가 하나라도 JSON으로 해석되지 않으면 절단으로 본다. 토큰 상한(length)으로 끝난 응답은 같은 요청이 다시 같은 곳에서 끊기므로 제외한다. */
        internal fun isTruncatedToolCall(response: net.ib.ixpert.ops.wuwagent.model.ChatCompletionResponse?): Boolean {
            val choice = response?.choices?.firstOrNull() ?: return false
            if (choice.finishReason == "length") return false
            return choice.message?.toolCalls.orEmpty().any { call ->
                val args = call.function.arguments
                args.isNotBlank() && try { JsonParser.parseString(args); false } catch (_: Exception) { true }
            }
        }

        /**
         * apiType 기준으로 chat completions 엔드포인트 URL을 명시적으로 조합합니다.
         * - 이미 "/chat/completions"로 끝나면 그대로 사용
         * - AIPRO / OPENAI_COMPATIBLE 모두 "/v1/chat/completions"를 덧붙임
         */
        fun buildChatCompletionsUrl(
            baseUrl: String,
            apiType: net.ib.ixpert.ops.wuwagent.setting.SettingsState.ApiType
        ): String {
            val trimmed = baseUrl.trimEnd('/')
            if (trimmed.endsWith("/chat/completions")) return trimmed
            return when (apiType) {
                // baseUrl(예: .../open/api) + /v1/chat/completions
                net.ib.ixpert.ops.wuwagent.setting.SettingsState.ApiType.AIPRO -> "$trimmed/v1/chat/completions"
                net.ib.ixpert.ops.wuwagent.setting.SettingsState.ApiType.OPENAI_COMPATIBLE -> "$trimmed/v1/chat/completions"
                // OpenAIClient는 AIPRO/OPENAI_COMPATIBLE 전용이며 OLLAMA는 별도 클라이언트(OllamaClient) 사용
                net.ib.ixpert.ops.wuwagent.setting.SettingsState.ApiType.OLLAMA -> "$trimmed/v1/chat/completions"
            }
        }
    }

    private fun getEffectiveSettings(): net.ib.ixpert.ops.wuwagent.setting.SettingsState.State {
        settingsOverride?.let { return it() }
        return try {
            net.ib.ixpert.ops.wuwagent.setting.SettingsState.getInstance().state
        } catch (_: Throwable) {
            net.ib.ixpert.ops.wuwagent.setting.SettingsState.State().apply {
                apiType = net.ib.ixpert.ops.wuwagent.setting.SettingsState.ApiType.OPENAI_COMPATIBLE
                openaiServerUrl = "http://vllm.ixpertops.cloud"
                model = "Qwen/Qwen3.8-27B-FP8"
                temperature = 0.1f
            }
        }
    }

    override fun chat(
        systemPrompt: String,
        userCode: String,
        maxTokens: Int?,
        onChunk: ((String) -> Unit)?
    ): OllamaChatResponse? = chatInternal(systemPrompt, userCode, maxTokens, onChunk, null)

    /** response_format json_schema(vLLM 구조화 출력)를 건 비스트림 호출. 오류·빈 응답·서버 거부는 null(호출부가 기존 chat으로 대체). */
    override fun chatJsonSchema(
        systemPrompt: String,
        userCode: String,
        maxTokens: Int?,
        schemaName: String,
        schema: Map<String, Any>
    ): OllamaChatResponse? {
        val responseFormat = mapOf(
            "type" to "json_schema",
            "json_schema" to mapOf("name" to schemaName, "strict" to true, "schema" to schema)
        )
        val r = chatInternal(systemPrompt, userCode, maxTokens, null, responseFormat)
        val content = r?.message?.content
        return if (content.isNullOrBlank() || content.startsWith("[Error]")) null else r
    }

    private fun chatInternal(
        systemPrompt: String,
        userCode: String,
        maxTokens: Int?,
        onChunk: ((String) -> Unit)?,
        responseFormat: Map<String, Any>?
    ): OllamaChatResponse? {
        val settings = getEffectiveSettings()
        val baseUrl = when (settings.apiType) {
            net.ib.ixpert.ops.wuwagent.setting.SettingsState.ApiType.AIPRO -> settings.aiproServerUrl
            else -> settings.openaiServerUrl
        }
        val serverUrl = buildChatCompletionsUrl(baseUrl, settings.apiType)

        val messagesList = listOf(
            mapOf("role" to "system", "content" to systemPrompt),
            mapOf("role" to "user", "content" to userCode)
        )
        val requestBody = mutableMapOf<String, Any>(
            "model" to settings.model,
            "messages" to messagesList,
            "stream" to (onChunk != null),
            "temperature" to settings.temperature,
            "max_tokens" to (maxTokens ?: 4096)
        )
        if (responseFormat != null) requestBody["response_format"] = responseFormat
        val jsonPayload = gson.toJson(requestBody)
        val isStreaming = onChunk != null
        val shortCall = !isStreaming && (maxTokens ?: 4096) <= SHORT_CALL_MAX_TOKENS
        val readTimeoutMs = if (shortCall) minOf(settings.timeoutSeconds * 1000, shortReadTimeoutSec * 1000) else settings.timeoutSeconds * 1000

        logger.info("OpenAI API Call (Stream=$isStreaming): url=$serverUrl, model=${settings.model}")

        val startMs = System.currentTimeMillis()
        val debugEntry = if (settings.enableLlmDebug) {
            try {
                DebugManager.getInstance().newEntry(settings.model, serverUrl, jsonPayload, messagesList.size, isStreaming)
            } catch (_: Exception) { null }
        } else null

        return try {
            val result = postWithRetry(isStreaming, retryOnTimeout = shortCall) {
            HttpRequests.post(serverUrl, "application/json")
                .tuner { connection ->
                    val effectiveKey = settings.effectiveApiKey()
                    if (effectiveKey.isNotBlank()) {
                        connection.setRequestProperty("Authorization", "Bearer $effectiveKey")
                    }
                    connection.connectTimeout = 30_000
                    connection.readTimeout = readTimeoutMs
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
            // 구조화 출력 시도의 거부·오류는 호출부가 기존 호출로 대체하므로 경고로만 남긴다
            if (responseFormat != null) logger.warn(errorMsg) else logger.error(errorMsg, e)
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
            // 구조화 출력 시도의 거부·오류는 호출부가 기존 호출로 대체하므로 경고로만 남긴다
            if (responseFormat != null) logger.warn(errorMsg) else logger.error(errorMsg, e)
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

    /**
     * 비스트림 호출만 1회 다시 호출한다(스트림은 건드리지 않는다). 상태 코드 502/503/504/524이거나, 짧은 호출의 읽기 타임아웃일 때다.
     * 첫 실패는 warn으로 남기고, 다시 호출해서도 실패하면 예외를 그대로 던져 호출부가 지금처럼 error로 처리한다. 취소 중이면 다시 호출하지 않는다.
     */
    private fun <T> postWithRetry(isStreaming: Boolean, retryOnTimeout: Boolean, block: () -> T): T {
        try {
            return block()
        } catch (e: IOException) {
            if (isStreaming || TaskCancellationToken.isCancelled.get() || !isRetryable(e, retryOnTimeout)) throw e
            logger.warn("OpenAI 비스트림 호출 실패(${e.message}), ${retryWaitMs}ms 뒤 1회 다시 호출합니다.")
            try {
                Thread.sleep(retryWaitMs)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            }
            return block()
        }
    }

    /** HttpURLConnection은 4xx/5xx를 "Server returned HTTP response code: 524 for URL ..." IOException으로 던지므로 메시지에서 상태 코드를 읽는다(실측 524 메시지와 같은 형식). */
    private fun statusOf(e: IOException): Int? =
        (e as? HttpRequests.HttpStatusException)?.statusCode
            ?: Regex("""HTTP response code: (\d{3})""").find(e.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()

    private fun isRetryable(e: IOException, retryOnTimeout: Boolean): Boolean =
        statusOf(e) in RETRYABLE_STATUS || (retryOnTimeout && e is java.net.SocketTimeoutException)

    /**
     * 스트림 절단(도구 인자 JSON 해석 불가)일 때만 1회 다시 호출한다. 타임아웃·연결 오류 등 예외는 그대로 던지고 다시 호출하지 않는다.
     * 다시 호출해도 끊기면 그 응답을 그대로 돌려주어 호출한 쪽이 지금처럼 실패로 처리하게 한다(실패 횟수만 기록).
     */
    override fun chatWithTools(
        systemPrompt: String,
        messages: List<net.ib.ixpert.ops.wuwagent.model.ChatMessage>,
        maxTokens: Int?,
        tools: List<net.ib.ixpert.ops.wuwagent.model.ToolDefinition>?,
        toolChoice: Any?,
        temperature: Double?
    ): net.ib.ixpert.ops.wuwagent.model.ChatCompletionResponse? {
        val first = chatWithToolsOnce(systemPrompt, messages, maxTokens, tools, toolChoice, temperature)
        if (!isTruncatedToolCall(first) || TaskCancellationToken.isCancelled.get()) return first
        streamRetryCount.incrementAndGet()
        logger.warn("OpenAIClient (ToolCalling): 스트림 절단 감지(도구 인자 JSON 해석 불가) → 1회 재시도")
        val second = chatWithToolsOnce(systemPrompt, messages, maxTokens, tools, toolChoice, temperature)
        if (isTruncatedToolCall(second)) {
            streamRetryFailedCount.incrementAndGet()
            logger.warn("OpenAIClient (ToolCalling): 재시도 후에도 스트림 절단 — 실패로 처리")
        }
        return second
    }

    private fun chatWithToolsOnce(
        systemPrompt: String,
        messages: List<net.ib.ixpert.ops.wuwagent.model.ChatMessage>,
        maxTokens: Int?,
        tools: List<net.ib.ixpert.ops.wuwagent.model.ToolDefinition>?,
        toolChoice: Any?,
        temperature: Double?
    ): net.ib.ixpert.ops.wuwagent.model.ChatCompletionResponse? {
        val settings = getEffectiveSettings()
        val baseUrl = when (settings.apiType) {
            net.ib.ixpert.ops.wuwagent.setting.SettingsState.ApiType.AIPRO -> settings.aiproServerUrl
            else -> settings.openaiServerUrl
        }
        val serverUrl = buildChatCompletionsUrl(baseUrl, settings.apiType)

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
            "stream" to true,
            "temperature" to (temperature ?: settings.temperature),
            "max_tokens" to (maxTokens ?: 4096)
        )
        if (!tools.isNullOrEmpty()) {
            requestBody["tools"] = tools
            if (toolChoice != null) {
                requestBody["tool_choice"] = toolChoice
            }
        }
        
        val jsonPayload = gson.toJson(requestBody)

        logger.info("OpenAI API Call (ToolCalling Stream=true): url=$serverUrl, model=${settings.model}")
        
        val startMs = System.currentTimeMillis()
        val debugEntry = if (settings.enableLlmDebug) {
            try {
                DebugManager.getInstance().newEntry(settings.model, serverUrl, jsonPayload, messages.size + 1, false)
            } catch (_: Exception) { null }
        } else null

        return try {
            HttpRequests.post(serverUrl, "application/json")
                .tuner { connection ->
                    val effectiveKey = settings.effectiveApiKey()
                    if (effectiveKey.isNotBlank()) {
                        connection.setRequestProperty("Authorization", "Bearer $effectiveKey")
                    }
                    val timeoutMs = settings.timeoutSeconds * 1000
                    connection.connectTimeout = 30_000
                    connection.readTimeout = timeoutMs
                }
                .connect { request ->
                    request.write(jsonPayload)
                    val inputStream = request.connection.inputStream
                    TaskCancellationToken.activeInputStream = inputStream
                    val reader = inputStream.bufferedReader()
                    
                    class ToolCallAccumulator(
                        var id: String = "",
                        var type: String = "function",
                        var name: String = "",
                        val arguments: StringBuilder = StringBuilder()
                    )
                    
                    val toolCallsMap = mutableMapOf<Int, ToolCallAccumulator>()
                    val contentBuilder = StringBuilder()
                    val reasoningBuilder = StringBuilder()
                    var responseId: String? = null
                    var finishReason: String? = null
                    var totalChunks = 0

                    try {
                        var line: String? = reader.readLine()
                        while (line != null) {
                            val currentLine = line
                            if (TaskCancellationToken.isCancelled.get()) {
                                logger.info("OpenAIClient (ToolCalling): 취소 감지 → 스트림 중단")
                                break
                            }
                            if (currentLine.startsWith("data: ")) {
                                val data = currentLine.removePrefix("data: ").trim()
                                if (data == "[DONE]") break
                                totalChunks++
                                try {
                                    val json = JsonParser.parseString(data).asJsonObject
                                    if (responseId == null) {
                                        responseId = json.get("id")?.takeIf { it.isJsonPrimitive }?.asString
                                    }
                                    val choice = json.getAsJsonArray("choices")?.get(0)?.asJsonObject
                                    choice?.get("finish_reason")?.takeIf { it.isJsonPrimitive }?.asString?.let {
                                        if (it.isNotBlank()) finishReason = it
                                    }
                                    val delta = choice?.getAsJsonObject("delta")
                                    delta?.get("content")?.takeIf { it.isJsonPrimitive }?.asString?.let { contentBuilder.append(it) }
                                    delta?.get("reasoning_content")?.takeIf { it.isJsonPrimitive }?.asString?.let { reasoningBuilder.append(it) }
                                    
                                    val toolCallsArray = delta?.getAsJsonArray("tool_calls")
                                    if (toolCallsArray != null) {
                                        for (tcElem in toolCallsArray) {
                                            val tcObj = tcElem.asJsonObject
                                            val idx = tcObj.get("index")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
                                            val accumulator = toolCallsMap.getOrPut(idx) { ToolCallAccumulator() }
                                            
                                            tcObj.get("id")?.takeIf { it.isJsonPrimitive }?.asString?.let { if (it.isNotBlank()) accumulator.id = it }
                                            tcObj.get("type")?.takeIf { it.isJsonPrimitive }?.asString?.let { if (it.isNotBlank()) accumulator.type = it }
                                            
                                            val fnObj = tcObj.getAsJsonObject("function")
                                            fnObj?.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.let { if (it.isNotBlank()) accumulator.name = it }
                                            fnObj?.get("arguments")?.takeIf { it.isJsonPrimitive }?.asString?.let { accumulator.arguments.append(it) }
                                        }
                                    }
                                } catch (e: Exception) {
                                    logger.warn("Failed to parse tool calling SSE chunk: $currentLine", e)
                                }
                            }
                            line = reader.readLine()
                        }
                    } catch (e: IOException) {
                        if (TaskCancellationToken.isCancelled.get()) {
                            logger.info("OpenAIClient (ToolCalling): 스트림 취소로 인한 IOException (정상) — ${e.message}")
                        } else {
                            throw e
                        }
                    } finally {
                        TaskCancellationToken.activeInputStream = null
                    }

                    val toolCallsList = if (toolCallsMap.isNotEmpty()) {
                        toolCallsMap.entries.sortedBy { it.key }.map { (_, acc) ->
                            net.ib.ixpert.ops.wuwagent.model.ToolCall(
                                id = if (acc.id.isNotBlank()) acc.id else "call_${System.currentTimeMillis()}",
                                type = acc.type,
                                function = net.ib.ixpert.ops.wuwagent.model.ToolCallFunction(
                                    name = acc.name,
                                    arguments = acc.arguments.toString()
                                )
                            )
                        }
                    } else null

                    val assembledContent = if (contentBuilder.isNotEmpty()) contentBuilder.toString() else null
                    val assembledReasoning = if (reasoningBuilder.isNotEmpty()) reasoningBuilder.toString() else null

                    logger.info("OpenAI API ToolCalling Stream Complete: chunks=$totalChunks, toolCalls=${toolCallsList?.size ?: 0}, finishReason=$finishReason")

                    val chatMessage = net.ib.ixpert.ops.wuwagent.model.ChatMessage(
                        role = "assistant",
                        content = assembledContent,
                        reasoningContent = assembledReasoning,
                        toolCalls = toolCallsList
                    )
                    val chatChoice = net.ib.ixpert.ops.wuwagent.model.ChatChoice(
                        index = 0,
                        message = chatMessage,
                        finishReason = finishReason ?: "stop"
                    )

                    val response = net.ib.ixpert.ops.wuwagent.model.ChatCompletionResponse(
                        id = responseId,
                        choices = listOf(chatChoice)
                    )

                    try {
                        if (debugEntry != null) {
                            debugEntry.durationMs = System.currentTimeMillis() - startMs
                            debugEntry.isSuccess = true
                            debugEntry.responseText = gson.toJson(response)
                            debugEntry.responseLength = (toolCallsList?.sumOf { it.function.arguments.length } ?: 0) + (assembledContent?.length ?: 0)
                            DebugManager.getInstance().addLog(debugEntry)
                        }
                    } catch (_: Exception) {}

                    response
                }
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

            // 1순위: 표준 OpenAI 형식 (data[].id)
            val data = json.getAsJsonArray("data")
            val standardModels = data?.mapNotNull { it.asJsonObject.get("id")?.asString }
            if (!standardModels.isNullOrEmpty()) return standardModels

            // 2순위: AIPro 형식 (modelList[].name)
            val modelList = json.getAsJsonArray("modelList")
            modelList?.mapNotNull { it.asJsonObject.get("name")?.asString }
        } catch (e: Exception) {
            logger.warn("OpenAIClient: fetchModels failed", e)
            null
        }
    }
}
