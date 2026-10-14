package net.ib.ixpert.ops.wuwagent.agent.eval

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
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
}

/**
 * 평가용 투명 프록시(측정만, 요청·응답 내용은 바꾸지 않는다). 운영 `OpenAIClient`는 usage를 읽지 않으므로,
 * JVM 프록시 설정(http.proxyHost/proxyPort)으로 이 서버를 거치게 해 호출마다 prompt/completion 토큰, finish_reason,
 * 첫 바이트까지 시간(TTFB), 총 시간을 서버 응답에서 직접 기록한다. 응답에 usage가 없으면 "미제공"으로 둔다.
 */
class UsageProxy(private val target: String) {
    @Volatile var scenarioId: String = ""
    @Volatile var run: Int = 0
    @Volatile var phase: String = ""

    val calls = CopyOnWriteArrayList<ProxyCall>()
    private val seq = AtomicInteger()
    private var server: HttpServer? = null
    var port: Int = 0
        private set

    fun start() {
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
            parseResponse(call, String(buf.toByteArray(), Charsets.UTF_8))
            call.completed = true
            try { ex.close() } catch (_: Exception) { }
        }
    }

    private fun parseResponse(call: ProxyCall, text: String) {
        if (text.isBlank()) return
        call.responseChars = text.length
        try {
            if (call.stream) {
                var chunks = 0
                var usage: JsonObject? = null
                var finish: String? = null
                for (line in text.lineSequence()) {
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isEmpty() || data == "[DONE]") continue
                    val o = JsonParser.parseString(data).asJsonObject
                    chunks++
                    o.get("usage")?.takeIf { it.isJsonObject }?.let { usage = it.asJsonObject }
                    o.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject?.get("finish_reason")?.takeIf { it.isJsonPrimitive }?.asString?.let { finish = it }
                }
                call.sseChunks = chunks
                call.finishReason = finish
                applyUsage(call, usage, " (스트림 응답에 usage 없음, 청크 수 ${chunks}는 토큰 수의 근사)")
            } else {
                val o = JsonParser.parseString(text).asJsonObject
                call.finishReason = o.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject?.get("finish_reason")?.takeIf { it.isJsonPrimitive }?.asString
                applyUsage(call, o.get("usage")?.takeIf { it.isJsonObject }?.asJsonObject, "")
            }
        } catch (e: Exception) {
            call.usageNote = "응답 해석 실패: ${e.javaClass.simpleName}"
        }
    }

    private fun applyUsage(call: ProxyCall, usage: JsonObject?, missingSuffix: String) {
        if (usage == null) { call.usageNote = "미제공$missingSuffix"; return }
        call.promptTokens = usage.get("prompt_tokens")?.takeIf { it.isJsonPrimitive }?.asInt
        call.completionTokens = usage.get("completion_tokens")?.takeIf { it.isJsonPrimitive }?.asInt
        call.usageNote = if (call.promptTokens == null && call.completionTokens == null) "미제공$missingSuffix" else "서버 usage"
    }
}
