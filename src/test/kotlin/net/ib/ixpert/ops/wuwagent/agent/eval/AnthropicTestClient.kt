package net.ib.ixpert.ops.wuwagent.agent.eval

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.model.ChatChoice
import net.ib.ixpert.ops.wuwagent.model.ChatCompletionResponse
import net.ib.ixpert.ops.wuwagent.model.ChatMessage
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.OllamaMessage
import net.ib.ixpert.ops.wuwagent.model.ToolCall
import net.ib.ixpert.ops.wuwagent.model.ToolCallFunction
import net.ib.ixpert.ops.wuwagent.model.ToolDefinition
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 진단 실험 전용(src/test) Anthropic Messages API 어댑터(Claude Sonnet 5.5 기준). 운영 코드는 건드리지 않는다.
 * 문서: https://platform.claude.com/docs/en/api/messages,
 *       https://platform.claude.com/docs/en/agents-and-tools/tool-use/define-tools,
 *       https://platform.claude.com/docs/en/agents-and-tools/tool-use/strict-tool-use,
 *       https://platform.claude.com/docs/en/models/sonnet-5-5/overview, .../whats-new-sonnet-5-5
 * - 요청: POST {baseUrl}/v1/messages, 헤더 x-api-key / anthropic-version: 2023-06-01 / content-type, 본문 model·max_tokens·system(최상위)·messages·tools·tool_choice·thinking.
 * - Sonnet 5.5 제약 반영: temperature/top_p/top_k를 기본값이 아닌 값으로 보내면 400이므로 보내지 않는다([sendTemperature]=false가 기본).
 *   tool_choice any/tool은 400이므로 auto만 보낸다. 도구 정의에는 최상위 strict:true를 붙이고, strict가 요구하는 additionalProperties:false를 모든 object 스키마에 더한다(그 밖의 스키마 내용은 그대로).
 *   thinking은 {"type":"between_tools"}(최저 설정, 다른 필드 금지)로 보낸다. effort는 보내지 않아 기본값(high)이다.
 * - Qwen 전용 파라미터(chat_template_kwargs 등)와 stream은 보내지 않는다(비스트리밍).
 * - 응답: text / tool_use / thinking 블록을 구분한다. thinking 블록 수·글자 수와 stop_reason, refusal의 stop_details.category를 기록한다. stop_reason tool_use→tool_calls, end_turn→stop, max_tokens→length.
 * - 키는 [keyProvider](기본: 환경변수 ANTHROPIC_API_KEY)에서만 읽고, 요청 헤더·키는 기록하지 않는다. 오류 메시지에는 상태 코드와 응답 본문 앞부분만 담는다.
 * - 전송: 이 환경의 Gradle 테스트 JVM은 https(TLS) 초기화가 IllegalAccessError로 실패하므로 https는 curl로 보낸다([useCurl]). 키는 curl 인자·파일에 두지 않고 stdin 설정으로만 넘긴다.
 */
class AnthropicTestClient(
    private val model: String = "claude-sonnet-5-5",
    private val baseUrl: String = "https://api.anthropic.com",
    private val keyProvider: () -> String? = { System.getenv("ANTHROPIC_API_KEY") },
    private val forceToolChoice: Boolean = false,
    private val sendTemperature: Boolean = false,
    private val strictTools: Boolean = true,
    private val thinkingBetweenTools: Boolean = true,
    private val timeoutSeconds: Long = 300,
    private val useCurl: Boolean = baseUrl.startsWith("https")
) : LLMClient {

    /** 호출 기록. 헤더·키는 담지 않는다. */
    data class Usage(
        val model: String?, val stopReason: String?, val inputTokens: Int?, val outputTokens: Int?, val ms: Long,
        val kind: String, val toolArgsText: String? = null,
        val thinkingBlocks: Int = 0, val thinkingChars: Int = 0, val toolCalled: Boolean = false, val refusalCategory: String? = null
    )

    val usages = CopyOnWriteArrayList<Usage>()
    private val gson = Gson()

    fun hasKey(): Boolean = !keyProvider().isNullOrBlank()

    /** 보고서에 적을 요청 설정 요약(키 제외). */
    fun configSummary(): String =
        "model=$model, temperature=${if (sendTemperature) "0.1" else "미전송"}, max_tokens=4000, tool_choice=${if (forceToolChoice) "tool(강제)" else "auto"}, " +
            "strict=$strictTools, thinking=${if (thinkingBetweenTools) "{\"type\":\"between_tools\"}" else "기본(adaptive)"}, effort=미지정(기본 high), stream=false"

    private fun postViaJvm(method: String, url: String, bodyJson: String?, key: String): Pair<Int, String> {
        val conn = URI.create(url).toURL().openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 30_000
            conn.readTimeout = (timeoutSeconds * 1000).toInt()
            conn.setRequestProperty("x-api-key", key)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            if (bodyJson != null) {
                conn.doOutput = true
                conn.setRequestProperty("content-type", "application/json")
                conn.outputStream.use { it.write(bodyJson.toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
            return status to text
        } finally {
            conn.disconnect()
        }
    }

    private fun postViaCurl(method: String, url: String, bodyJson: String?, key: String): Pair<Int, String> {
        val tmp = if (bodyJson != null) File.createTempFile("anthropic_req", ".json") else null
        try {
            tmp?.writeText(bodyJson!!, Charsets.UTF_8)
            val proc = ProcessBuilder("curl", "-sS", "--max-time", timeoutSeconds.toString(), "--config", "-", "-w", "\n%{http_code}").start()
            val cfg = StringBuilder("url = \"$url\"\nrequest = \"$method\"\nheader = \"x-api-key: $key\"\nheader = \"anthropic-version: 2023-06-01\"\n")
            if (tmp != null) cfg.append("header = \"content-type: application/json\"\ndata-binary = \"@${tmp.absolutePath.replace('\\', '/')}\"\n")
            proc.outputStream.use { it.write(cfg.toString().toByteArray(Charsets.UTF_8)) }
            val errText = StringBuilder()
            val errReader = Thread { errText.append(String(proc.errorStream.readBytes(), Charsets.UTF_8)) }.also { it.isDaemon = true; it.start() }
            val out = String(proc.inputStream.readBytes(), Charsets.UTF_8)
            val code = proc.waitFor()
            errReader.join(2000)
            if (code != 0) throw IOException("curl 실패 exit=$code: ${errText.toString().take(300)}")
            val nl = out.lastIndexOf('\n')
            return out.substring(nl + 1).trim().toInt() to out.substring(0, maxOf(nl, 0))
        } finally {
            tmp?.delete()
        }
    }

    private fun send(method: String, path: String, bodyJson: String?): Pair<Int, String> {
        val key = keyProvider()?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("ANTHROPIC_API_KEY 없음")
        val url = baseUrl.trimEnd('/') + path
        return if (useCurl) postViaCurl(method, url, bodyJson, key) else postViaJvm(method, url, bodyJson, key)
    }

    /** GET /v1/models 응답 원문(2xx가 아니면 예외). */
    fun modelsRaw(): String {
        val (status, text) = send("GET", "/v1/models?limit=1000", null)
        if (status !in 200..299) throw IOException("Anthropic API HTTP $status: ${text.take(400)}")
        return text
    }

    /** 모델 목록의 id들. */
    fun listModelIds(): List<String> =
        JsonParser.parseString(modelsRaw()).asJsonObject.getAsJsonArray("data")?.mapNotNull { it.asJsonObject.get("id")?.asString } ?: emptyList()

    private fun post(body: JsonObject, kind: String): JsonObject {
        val t0 = System.currentTimeMillis()
        val (status, respBody) = send("POST", "/v1/messages", gson.toJson(body))
        val ms = System.currentTimeMillis() - t0
        if (status !in 200..299) {
            throw IOException("Anthropic API HTTP $status: ${respBody.take(400)}")
        }
        val json = JsonParser.parseString(respBody).asJsonObject
        val usage = json.getAsJsonObject("usage")
        val blocks = json.getAsJsonArray("content")?.map { it.asJsonObject } ?: emptyList()
        val toolUses = blocks.filter { it.get("type")?.asString == "tool_use" }
        val thinking = blocks.filter { it.get("type")?.asString == "thinking" }
        usages.add(
            Usage(
                json.get("model")?.takeIf { it.isJsonPrimitive }?.asString,
                json.get("stop_reason")?.takeIf { it.isJsonPrimitive }?.asString,
                usage?.get("input_tokens")?.asInt, usage?.get("output_tokens")?.asInt, ms, kind,
                toolUses.joinToString("\n") { gson.toJson(it.get("input")) }.ifBlank { null },
                thinking.size, thinking.sumOf { (it.get("thinking")?.takeIf { e -> e.isJsonPrimitive }?.asString ?: "").length },
                toolUses.isNotEmpty(),
                json.getAsJsonObject("stop_details")?.get("category")?.takeIf { it.isJsonPrimitive }?.asString
            )
        )
        return json
    }

    private fun baseBody(systemPrompt: String, maxTokens: Int?, temperature: Double?, messages: JsonArray): JsonObject {
        val body = JsonObject()
        body.addProperty("model", model)
        body.addProperty("max_tokens", maxTokens ?: 4000)
        if (sendTemperature) body.addProperty("temperature", temperature ?: 0.1)
        if (thinkingBetweenTools) body.add("thinking", JsonObject().apply { addProperty("type", "between_tools") })
        if (systemPrompt.isNotBlank()) body.addProperty("system", systemPrompt)
        body.add("messages", messages)
        return body
    }

    /** strict가 요구하는 additionalProperties:false를 모든 object 스키마에 더한다. 그 밖의 내용은 바꾸지 않는다. */
    private fun withClosedObjects(schema: JsonElement): JsonElement {
        if (schema.isJsonObject) {
            val o = schema.asJsonObject
            if (o.get("type")?.takeIf { it.isJsonPrimitive }?.asString == "object" && !o.has("additionalProperties")) o.addProperty("additionalProperties", false)
            o.entrySet().toList().forEach { withClosedObjects(it.value) }
        } else if (schema.isJsonArray) {
            schema.asJsonArray.forEach { withClosedObjects(it) }
        }
        return schema
    }

    private fun textMessage(role: String, text: String) = JsonObject().apply {
        addProperty("role", role); addProperty("content", text)
    }

    private fun textOf(json: JsonObject): String =
        json.getAsJsonArray("content").map { it.asJsonObject }.filter { it.get("type")?.asString == "text" }.joinToString("") { it.get("text").asString }

    override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): OllamaChatResponse? {
        val msgs = JsonArray().apply { add(textMessage("user", userCode)) }
        val json = post(baseBody(systemPrompt, maxTokens, null, msgs), "chat")
        val text = textOf(json)
        onChunk?.invoke(text)
        return OllamaChatResponse(json.get("model")?.asString, null, OllamaMessage("assistant", text), true)
    }

    override fun chatWithTools(
        systemPrompt: String, messages: List<ChatMessage>, maxTokens: Int?, tools: List<ToolDefinition>?, toolChoice: Any?, temperature: Double?
    ): ChatCompletionResponse? {
        val msgs = JsonArray()
        for (m in messages) {
            when {
                m.role == "tool" -> msgs.add(JsonObject().apply {
                    addProperty("role", "user")
                    add("content", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("type", "tool_result"); addProperty("tool_use_id", m.toolCallId ?: ""); addProperty("content", m.content ?: "")
                        })
                    })
                })
                m.role == "assistant" && !m.toolCalls.isNullOrEmpty() -> msgs.add(JsonObject().apply {
                    addProperty("role", "assistant")
                    add("content", JsonArray().apply {
                        if (!m.content.isNullOrBlank()) add(JsonObject().apply { addProperty("type", "text"); addProperty("text", m.content) })
                        m.toolCalls!!.forEach { c ->
                            add(JsonObject().apply {
                                addProperty("type", "tool_use"); addProperty("id", c.id); addProperty("name", c.function.name)
                                add("input", JsonParser.parseString(c.function.arguments.ifBlank { "{}" }))
                            })
                        }
                    })
                })
                else -> msgs.add(textMessage(if (m.role == "assistant") "assistant" else "user", m.content ?: ""))
            }
        }
        val body = baseBody(systemPrompt, maxTokens, temperature, msgs)
        if (!tools.isNullOrEmpty()) {
            body.add("tools", JsonArray().apply {
                tools.forEach { t ->
                    add(JsonObject().apply {
                        addProperty("name", t.function.name)
                        addProperty("description", t.function.description)
                        if (strictTools) addProperty("strict", true)
                        val schema = JsonParser.parseString(gson.toJson(t.function.parameters))
                        add("input_schema", if (strictTools) withClosedObjects(schema) else schema)
                    })
                }
            })
            val forced = (toolChoice as? Map<*, *>)?.let { (it["function"] as? Map<*, *>)?.get("name") as? String }
            body.add("tool_choice", JsonObject().apply {
                if (forceToolChoice && forced != null) { addProperty("type", "tool"); addProperty("name", forced) } else addProperty("type", "auto")
            })
        }
        val json = post(body, "chatWithTools")
        val blocks = json.getAsJsonArray("content").map { it.asJsonObject }
        val calls = blocks.filter { it.get("type")?.asString == "tool_use" }.map {
            ToolCall(it.get("id").asString, "function", ToolCallFunction(it.get("name").asString, gson.toJson(it.get("input"))))
        }
        val finish = when (json.get("stop_reason")?.takeIf { it.isJsonPrimitive }?.asString) {
            "tool_use" -> "tool_calls"; "max_tokens" -> "length"; else -> "stop"
        }
        val text = textOf(json)
        return ChatCompletionResponse(
            json.get("id")?.takeIf { it.isJsonPrimitive }?.asString,
            listOf(ChatChoice(0, ChatMessage(role = "assistant", content = text.ifBlank { null }, toolCalls = calls.ifEmpty { null }), finish))
        )
    }

    override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = listModelIds()
}
