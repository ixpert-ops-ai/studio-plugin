package net.ib.ixpert.ops.wuwagent.agent.eval

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** 호출 한 건의 서버 쪽 기록. 프록시가 요청·응답을 그대로 전달하면서 채운다. */
class ProxyCall(
    val seq: Int,
    val scenarioId: String,
    val run: Int,
    val phase: String,          // clarify | analyze
    val stage: String,          // 시스템 프롬프트 표지로 분류한 단계 이름
    val startMs: Long,
    val stream: Boolean,
    val hasTools: Boolean,
    val maxTokens: Int?,
    val requestChars: Int
) {
    @Volatile var ttfbMs: Long? = null
    @Volatile var totalMs: Long? = null
    @Volatile var httpStatus: Int? = null
    @Volatile var completed: Boolean = false
    @Volatile var clientGone: Boolean = false   // 호출자가 상한으로 연결을 끊은 뒤에도 서버 응답이 끝날 때까지 읽는다
    @Volatile var promptTokens: Int? = null
    @Volatile var completionTokens: Int? = null
    @Volatile var finishReason: String? = null
    @Volatile var sseChunks: Int? = null
    @Volatile var responseChars: Int? = null
    @Volatile var usageNote: String = "미제공"
    // 출력 구성(스트림): delta 종류별 청크 수. 청크는 토큰의 근사다.
    @Volatile var reasoningChunks: Int? = null
    @Volatile var contentChunks: Int? = null
    @Volatile var toolArgChunks: Int? = null
    @Volatile var reasoningChars: Int? = null
    @Volatile var contentChars: Int? = null
    @Volatile var toolArgChars: Int? = null
    @Volatile var deltaKeys: String? = null
}

/** 스트림·일반 응답에서 추출한 출력 구성. 프록시와 재생 실험이 같은 해석을 쓴다. */
class ParsedOutput(
    var chunks: Int = 0,
    var reasoningChunks: Int = 0,
    var contentChunks: Int = 0,
    var toolArgChunks: Int = 0,
    val reasoning: StringBuilder = StringBuilder(),
    val content: StringBuilder = StringBuilder(),
    val toolArgs: StringBuilder = StringBuilder(),
    var finishReason: String? = null,
    var promptTokens: Int? = null,
    var completionTokens: Int? = null,
    val deltaKeys: MutableSet<String> = linkedSetOf()
)

object ResponseParser {
    private fun str(e: JsonElement?): String? = if (e != null && e.isJsonPrimitive) e.asString else null

    /** SSE 본문(`data: {...}` 줄)을 해석한다. */
    fun parseStream(text: String): ParsedOutput {
        val out = ParsedOutput()
        for (line in text.lineSequence()) {
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data.isEmpty() || data == "[DONE]") continue
            val o = JsonParser.parseString(data).asJsonObject
            out.chunks++
            o.get("usage")?.takeIf { it.isJsonObject }?.asJsonObject?.let { u ->
                out.promptTokens = str(u.get("prompt_tokens"))?.toIntOrNull() ?: out.promptTokens
                out.completionTokens = str(u.get("completion_tokens"))?.toIntOrNull() ?: out.completionTokens
            }
            val choice = o.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject ?: continue
            str(choice.get("finish_reason"))?.let { out.finishReason = it }
            val delta = choice.getAsJsonObject("delta") ?: continue
            delta.keySet().forEach { out.deltaKeys.add(it) }
            (str(delta.get("reasoning_content")) ?: str(delta.get("reasoning")))?.takeIf { it.isNotEmpty() }?.let { out.reasoningChunks++; out.reasoning.append(it) }
            str(delta.get("content"))?.takeIf { it.isNotEmpty() }?.let { out.contentChunks++; out.content.append(it) }
            delta.getAsJsonArray("tool_calls")?.forEach { tc ->
                str(tc.asJsonObject.getAsJsonObject("function")?.get("arguments"))?.takeIf { it.isNotEmpty() }?.let { out.toolArgChunks++; out.toolArgs.append(it) }
            }
        }
        return out
    }

    /** 일반(non-stream) 응답 JSON을 해석한다. 청크 수는 없고 글자 수와 usage만 채운다. */
    fun parseJson(text: String): ParsedOutput {
        val out = ParsedOutput()
        val o = JsonParser.parseString(text).asJsonObject
        o.get("usage")?.takeIf { it.isJsonObject }?.asJsonObject?.let { u ->
            out.promptTokens = str(u.get("prompt_tokens"))?.toIntOrNull()
            out.completionTokens = str(u.get("completion_tokens"))?.toIntOrNull()
        }
        val choice = o.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
        out.finishReason = str(choice?.get("finish_reason"))
        val msg = choice?.getAsJsonObject("message")
        if (msg != null) {
            (str(msg.get("reasoning_content")) ?: str(msg.get("reasoning")))?.let { out.reasoning.append(it) }
            str(msg.get("content"))?.let { out.content.append(it) }
            msg.getAsJsonArray("tool_calls")?.forEach { tc -> str(tc.asJsonObject.getAsJsonObject("function")?.get("arguments"))?.let { out.toolArgs.append(it) } }
        }
        return out
    }
}

/**
 * 평가용 투명 프록시(측정만, 요청·응답 내용은 바꾸지 않는다). 운영 `OpenAIClient`는 usage를 읽지 않으므로,
 * JVM 프록시 설정(http.proxyHost/proxyPort)으로 이 서버를 거치게 해 호출마다 prompt/completion 토큰, finish_reason,
 * 첫 바이트까지 시간(TTFB), 총 시간을 서버 응답에서 직접 기록한다. 응답에 usage가 없으면 "미제공"으로 둔다.
 * `dumpDir`을 주면 호출마다 요청 본문(.req.json)과 응답 구성(.resp.json: reasoning/content/toolArgs)을 파일로 남긴다(재생 실험·누락 위치 분석용).
 */
class UsageProxy(private val target: String, private val dumpDir: File? = null) {
    @Volatile var scenarioId: String = ""
    @Volatile var run: Int = 0
    @Volatile var phase: String = ""

    val calls = CopyOnWriteArrayList<ProxyCall>()
    private val seq = AtomicInteger()
    private var server: HttpServer? = null
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    var port: Int = 0
        private set

    fun start() {
        dumpDir?.mkdirs()
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { ex -> handle(ex) }
        s.executor = Executors.newCachedThreadPool { r -> Thread(r, "eval-proxy").apply { isDaemon = true } }
        s.start()
        server = s
        port = s.address.port
    }

    fun install() {
        System.setProperty("http.proxyHost", "127.0.0.1")
        System.setProperty("http.proxyPort", port.toString())
        System.setProperty("http.nonProxyHosts", "localhost|127.*|[::1]")
    }

    fun uninstall() {
        System.clearProperty("http.proxyHost")
        System.clearProperty("http.proxyPort")
        System.clearProperty("http.nonProxyHosts")
    }

    fun stop() { server?.stop(0) }

    /** 상한으로 포기한 호출 등 아직 끝나지 않은 서버 응답을 최대 waitMs까지 기다린다. */
    fun awaitIdle(waitMs: Long) {
        val deadline = System.currentTimeMillis() + waitMs
        while (calls.any { !it.completed } && System.currentTimeMillis() < deadline) Thread.sleep(500)
    }

    private fun classify(system: String): String = when {
        system.contains("소프트웨어 요구사항 정제 전문가") -> "clarify:정제문 생성"
        system.contains("ConstraintKind") -> "clarify:제약 추출"
        system.contains("개방형 질문") -> "clarify:개방형 질문"
        system.contains("발화 번역 전문가") -> "clarify:발화 번역(UI)"
        system.contains("당신은 프로젝트 아키텍트입니다.") -> "analyze:신규 파일 탐지"
        system.contains("당신은 코드 변경 범위 검증자입니다.") -> "analyze:Stage3 검증"
        system.contains("능동적으로 탐색하는 전문 AI 분석 에이전트") -> "분석:시드 선정(tool)"
        else -> "기타:" + system.replace("\n", " ").take(40)
    }

    private fun handle(ex: HttpExchange) {
        val started = System.currentTimeMillis()
        val reqBytes = ex.requestBody.readBytes()
        val uri = ex.requestURI
        val path = (uri.rawPath ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")

        var stream = false
        var hasTools = false
        var maxTokens: Int? = null
        var system = ""
        try {
            val body = JsonParser.parseString(String(reqBytes, Charsets.UTF_8))
            if (body.isJsonObject) {
                val o = body.asJsonObject
                stream = o.get("stream")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
                hasTools = o.has("tools") && !o.get("tools").isJsonNull
                maxTokens = o.get("max_tokens")?.takeIf { it.isJsonPrimitive }?.asInt
                val msgs = o.getAsJsonArray("messages")
                if (msgs != null && msgs.size() > 0) {
                    val first = msgs[0].asJsonObject
                    if (first.get("role")?.asString == "system") system = first.get("content")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
                }
            }
        } catch (_: Exception) { /* 본문이 JSON이 아니면 분류만 건너뜀 */ }

        val isChat = path.contains("/chat/completions")
        val call = ProxyCall(seq.incrementAndGet(), scenarioId, run, phase, if (isChat) classify(system) else "기타:$path", started, stream, hasTools, maxTokens, reqBytes.size)
        if (isChat) calls.add(call)

        val buf = ByteArrayOutputStream()
        try {
            val conn = URL(target.trimEnd('/') + path).openConnection(Proxy.NO_PROXY) as HttpURLConnection
            conn.requestMethod = ex.requestMethod
            conn.connectTimeout = 30_000
            conn.readTimeout = 900_000
            for ((k, v) in ex.requestHeaders) {
                val kl = k.lowercase()
                if (kl in setOf("host", "content-length", "connection", "transfer-encoding", "accept-encoding", "proxy-connection")) continue
                conn.setRequestProperty(k, v.joinToString(", "))
            }
            if (reqBytes.isNotEmpty()) { conn.doOutput = true; conn.outputStream.use { it.write(reqBytes) } }
            val status = conn.responseCode
            call.httpStatus = status
            val upstream = if (status >= 400) conn.errorStream else conn.inputStream
            conn.contentType?.let { ex.responseHeaders.add("Content-Type", it) }
            var headersSent = false
            val chunk = ByteArray(8192)
            while (upstream != null) {
                val n = upstream.read(chunk)
                if (n < 0) break
                if (call.ttfbMs == null) call.ttfbMs = System.currentTimeMillis() - started
                buf.write(chunk, 0, n)
                if (!call.clientGone) {
                    try {
                        if (!headersSent) { ex.sendResponseHeaders(status, 0); headersSent = true }
                        ex.responseBody.write(chunk, 0, n)
                        ex.responseBody.flush()
                    } catch (_: Exception) { call.clientGone = true }
                }
            }
            if (!call.clientGone) {
                try {
                    if (!headersSent) ex.sendResponseHeaders(status, -1)
                    ex.responseBody.close()
                } catch (_: Exception) { call.clientGone = true }
            }
        } catch (e: Exception) {
            call.usageNote = "프록시 오류: ${e.javaClass.simpleName}"
            try { ex.sendResponseHeaders(502, -1) } catch (_: Exception) { }
        } finally {
            call.totalMs = System.currentTimeMillis() - started
            val parsed = parseResponse(call, String(buf.toByteArray(), Charsets.UTF_8))
            call.completed = true
            if (isChat && dumpDir != null) dump(call, reqBytes, parsed)
            try { ex.close() } catch (_: Exception) { }
        }
    }

    private fun dump(call: ProxyCall, req: ByteArray, parsed: ParsedOutput?) {
        try {
            val base = "${call.scenarioId}_run${call.run}_c${call.seq}"
            File(dumpDir, "$base.req.json").writeBytes(req)
            val resp = mapOf(
                "stage" to call.stage, "phase" to call.phase, "stream" to call.stream, "httpStatus" to call.httpStatus,
                "totalMs" to call.totalMs, "ttfbMs" to call.ttfbMs, "finishReason" to call.finishReason,
                "promptTokens" to call.promptTokens, "completionTokens" to call.completionTokens,
                "chunks" to call.sseChunks, "reasoningChunks" to call.reasoningChunks, "contentChunks" to call.contentChunks, "toolArgChunks" to call.toolArgChunks,
                "deltaKeys" to call.deltaKeys,
                "reasoning" to (parsed?.reasoning?.toString() ?: ""), "content" to (parsed?.content?.toString() ?: ""), "toolArgs" to (parsed?.toolArgs?.toString() ?: "")
            )
            File(dumpDir, "$base.resp.json").writeText(gson.toJson(resp), Charsets.UTF_8)
        } catch (_: Exception) { /* 덤프 실패는 측정에 영향을 주지 않는다 */ }
    }

    private fun parseResponse(call: ProxyCall, text: String): ParsedOutput? {
        if (text.isBlank()) return null
        call.responseChars = text.length
        return try {
            val p = if (call.stream) ResponseParser.parseStream(text) else ResponseParser.parseJson(text)
            call.finishReason = p.finishReason
            call.promptTokens = p.promptTokens
            call.completionTokens = p.completionTokens
            if (call.stream) {
                call.sseChunks = p.chunks
                call.reasoningChunks = p.reasoningChunks
                call.contentChunks = p.contentChunks
                call.toolArgChunks = p.toolArgChunks
                call.deltaKeys = p.deltaKeys.joinToString(",")
            }
            call.reasoningChars = p.reasoning.length
            call.contentChars = p.content.length
            call.toolArgChars = p.toolArgs.length
            call.usageNote = if (p.promptTokens != null || p.completionTokens != null) "서버 usage"
                else "미제공" + if (call.stream) " (스트림 응답에 usage 없음, 청크 수 ${p.chunks}는 토큰 수의 근사)" else ""
            p
        } catch (e: Exception) {
            call.usageNote = "응답 해석 실패: ${e.javaClass.simpleName}"
            null
        }
    }
}
