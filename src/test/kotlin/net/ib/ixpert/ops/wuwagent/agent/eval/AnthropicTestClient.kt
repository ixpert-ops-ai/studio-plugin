package net.ib.ixpert.ops.wuwagent.agent.eval

import com.google.gson.Gson
import com.google.gson.JsonArray
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
 * 진단 실험 전용(src/test) Anthropic Messages API 어댑터. 운영 코드는 건드리지 않는다.
 * - 요청 형식: POST {baseUrl}/v1/messages, 헤더 x-api-key / anthropic-version: 2023-06-01 / content-type: application/json,
 *   본문 model·max_tokens·temperature·system(최상위)·messages·tools[{name,description,input_schema}]·tool_choice
 *   (https://platform.claude.com/docs/en/api/messages, https://platform.claude.com/docs/en/agents-and-tools/tool-use/define-tools).
 * - 응답: content 블록 text / tool_use(id,name,input)를 운영 모양(ChatCompletionResponse.toolCalls)으로 바꾼다. stop_reason tool_use→tool_calls, end_turn→stop, max_tokens→length.
 * - Qwen 전용 파라미터(chat_template_kwargs 등)와 stream은 보내지 않는다(비스트리밍).
 * - 키는 [keyProvider](기본: 환경변수 ANTHROPIC_API_KEY)에서만 읽고, 요청 헤더·키는 기록하지 않는다. 오류 메시지에는 상태 코드와 응답 본문 앞부분만 담는다.
 * - tool_choice: 문서상 Sonnet 5.5 계열 등은 강제 지정({"type":"tool"})을 400으로 거부하므로 기본은 auto로 보낸다(Stage 3 시스템 프롬프트가 도구 호출을 지시). [forceToolChoice]=true면 운영처럼 지정한다.
 * - 전송: 이 환경의 Gradle 테스트 JVM은 https(TLS) 초기화가 IllegalAccessError로 실패하므로 https는 curl로 보낸다([useCurl]). 키는 curl 인자·파일에 두지 않고 stdin 설정으로만 넘긴다.
 */
class AnthropicTestClient(
    private val model: String = "claude-sonnet-5",
    private val baseUrl: String = "https://api.anthropic.com",
    private val keyProvider: () -> String? = { System.getenv("ANTHROPIC_API_KEY") },
    private val forceToolChoice: Boolean = false,
    private val sendTemperature: Boolean = true,
    private val timeoutSeconds: Long = 300,
    private val useCurl: Boolean = baseUrl.startsWith("https")
) : LLMClient {

    /** 호출 기록. 헤더·키는 담지 않는다. */
    data class Usage(
        val model: String?, val stopReason: String?, val inputTokens: Int?, val outputTokens: Int?, val ms: Long,
        val kind: String, val toolArgsText: String? = null
    )

    val usages = CopyOnWriteArrayList<Usage>()
    private val gson = Gson()

    fun hasKey(): Boolean = !keyProvider().isNullOrBlank()

    private fun postViaJvm(url: String, bodyJson: String, key: String): Pair<Int, String> {
        val conn = URI.create(url).toURL().openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 30_000
            conn.readTimeout = (timeoutSeconds * 1000).toInt()
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-api-key", key)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.outputStream.use { it.write(bodyJson.toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
            return status to text
        } finally {
            conn.disconnect()
        }
    }

    private fun postViaCurl(url: String, bodyJson: String, key: String): Pair<Int, String> {
        val tmp = File.createTempFile("anthropic_req", ".json")
        try {
            tmp.writeText(bodyJson, Charsets.UTF_8)
            val proc = ProcessBuilder("curl", "-sS", "--max-time", timeoutSeconds.toString(), "--config", "-", "-w", "\n%{http_code}").start()
            val path = tmp.absolutePath.replace('\\', '/')
            val cfg = "url = \"$url\"\nrequest = \"POST\"\nheader = \"content-type: application/json\"\nheader = \"x-api-key: $key\"\n" +
                "header = \"anthropic-version: 2023-06-01\"\ndata-binary = \"@$path\"\n"
            proc.outputStream.use { it.write(cfg.toByteArray(Charsets.UTF_8)) }
            val errText = StringBuilder()
            val errReader = Thread { errText.append(String(proc.errorStream.readBytes(), Charsets.UTF_8)) }.also { it.isDaemon = true; it.start() }
            val out = String(proc.inputStream.readBytes(), Charsets.UTF_8)
            val code = proc.waitFor()
            errReader.join(2000)
            if (code != 0) throw IOException("curl 실패 exit=$code: ${errText.toString().take(300)}")
            val nl = out.lastIndexOf('\n')
            return out.substring(nl + 1).trim().toInt() to out.substring(0, maxOf(nl, 0))
        } finally {
            tmp.delete()
        }
    }

    private fun post(body: JsonObject, kind: String): JsonObject {
        val key = keyProvider()?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("ANTHROPIC_API_KEY 없음")
        val url = baseUrl.trimEnd('/') + "/v1/messages"
        val t0 = System.currentTimeMillis()
        val (status, respBody) = if (useCurl) postViaCurl(url, gson.toJson(body), key) else postViaJvm(url, gson.toJson(body), key)
        val ms = System.currentTimeMillis() - t0
        if (status !in 200..299) {
            throw IOException("Anthropic API HTTP $status: ${respBody.take(400)}")
        }
        val json = JsonParser.parseString(respBody).asJsonObject
        val usage = json.getAsJsonObject("usage")
        val toolArgs = json.getAsJsonArray("content")?.mapNotNull { it.asJsonObject.takeIf { b -> b.get("type")?.asString == "tool_use" } }
            ?.joinToString("\n") { gson.toJson(it.get("input")) }
        usages.add(
            Usage(
                json.get("model")?.takeIf { it.isJsonPrimitive }?.asString,
                json.get("stop_reason")?.takeIf { it.isJsonPrimitive }?.asString,
                usage?.get("input_tokens")?.asInt, usage?.get("output_tokens")?.asInt, ms, kind, toolArgs?.ifBlank { null }
            )
        )
        return json
    }


    private fun baseBody(systemPrompt: String, maxTokens: Int?, temperature: Double?, messages: JsonArray): JsonObject {
        val body = JsonObject()
        body.addProperty("model", model)
        body.addProperty("max_tokens", maxTokens ?: 4000)
        if (sendTemperature) body.addProperty("temperature", temperature ?: 0.1)
        if (systemPrompt.isNotBlank()) body.addProperty("system", systemPrompt)
        body.add("messages", messages)
        return body
    }

    private fun textMessage(role: String, text: String) = JsonObject().apply {
        addProperty("role", role); addProperty("content", text)
    }

    override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): OllamaChatResponse? {
        val msgs = JsonArray().apply { add(textMessage("user", userCode)) }
        val json = post(baseBody(systemPrompt, maxTokens, null, msgs), "chat")
        val text = json.getAsJsonArray("content").filter { it.asJsonObject.get("type")?.asString == "text" }
            .joinToString("") { it.asJsonObject.get("text").asString }
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
                        add("input_schema", JsonParser.parseString(gson.toJson(t.function.parameters)))
                    })
                }
            })
            val forced = (toolChoice as? Map<*, *>)?.let { (it["function"] as? Map<*, *>)?.get("name") as? String }
            body.add("tool_choice", JsonObject().apply {
                if (forceToolChoice && forced != null) { addProperty("type", "tool"); addProperty("name", forced) } else addProperty("type", "auto")
            })
        }
        val json = post(body, "chatWithTools")
        val blocks = json.getAsJsonArray("content")
        val text = blocks.filter { it.asJsonObject.get("type")?.asString == "text" }.joinToString("") { it.asJsonObject.get("text").asString }
        val calls = blocks.map { it.asJsonObject }.filter { it.get("type")?.asString == "tool_use" }.map {
            ToolCall(it.get("id").asString, "function", ToolCallFunction(it.get("name").asString, gson.toJson(it.get("input"))))
        }
        val finish = when (json.get("stop_reason")?.takeIf { it.isJsonPrimitive }?.asString) {
            "tool_use" -> "tool_calls"; "max_tokens" -> "length"; "end_turn", "stop_sequence" -> "stop"; null -> "stop"; else -> "stop"
        }
        return ChatCompletionResponse(
            json.get("id")?.takeIf { it.isJsonPrimitive }?.asString,
            listOf(ChatChoice(0, ChatMessage(role = "assistant", content = text.ifBlank { null }, toolCalls = calls.ifEmpty { null }), finish))
        )
    }

    override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = listOf(model)
}
