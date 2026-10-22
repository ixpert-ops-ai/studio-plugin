package net.ib.ixpert.ops.wuwagent.client

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import net.ib.ixpert.ops.wuwagent.setting.SettingsState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * B-57 비스트림 chat 1회 재시도: 가짜 서버로 "524 다음 200", "524 두 번", "타임아웃 다음 200", "400은 재시도 없음", "스트림은 재시도 없음"을 확인한다.
 * 운영 클라이언트의 logger.error는 테스트 환경에서 AssertionError로 던져지므로(B-56), error 로그 여부는 AssertionError 발생으로 본다.
 */
class OpenAIClientNonStreamRetryTest {

    private lateinit var server: HttpServer
    private val hits = AtomicInteger()
    // 요청 순번(0부터)에 대한 응답 동작
    @Volatile private var behavior: (Int, HttpExchange) -> Unit = { _, _ -> }

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()   // 첫 요청이 지연되는 동안 재시도 요청도 받아야 한다
        server.createContext("/") { ex ->
            val n = hits.getAndIncrement()
            try {
                ex.requestBody.readBytes()
                behavior(n, ex)
            } catch (_: Exception) {
            } finally {
                try { ex.close() } catch (_: Exception) {}
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    private fun ok(ex: HttpExchange) {
        val body = """{"choices":[{"message":{"role":"assistant","content":"정상 응답"}}]}""".toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(200, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }

    private fun status(ex: HttpExchange, code: Int) {
        val body = "error code: $code".toByteArray()
        ex.sendResponseHeaders(code, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }

    private fun sse(ex: HttpExchange) {
        ex.responseHeaders.add("Content-Type", "text/event-stream")
        ex.sendResponseHeaders(200, 0)
        ex.responseBody.use { it.write("""data: {"choices":[{"delta":{"content":"스트림"}}]}""".toByteArray() + "\n\ndata: [DONE]\n\n".toByteArray()) }
    }

    private fun client(timeoutSeconds: Int = 20, shortReadTimeoutSec: Int = 1) = OpenAIClient(shortReadTimeoutSec = shortReadTimeoutSec, retryWaitMs = 10) {
        SettingsState.State(
            apiType = SettingsState.ApiType.OPENAI_COMPATIBLE,
            openaiServerUrl = "http://127.0.0.1:${server.address.port}",
            model = "test-model",
            timeoutSeconds = timeoutSeconds
        )
    }

    /** 호출 결과: 내용 또는 실패 방식. error 로그(테스트에서는 AssertionError)가 났으면 "ERROR_LOG", "[Error]" 응답이면 그 앞부분. */
    private fun call(c: OpenAIClient, maxTokens: Int? = 100, onChunk: ((String) -> Unit)? = null): String {
        return try {
            c.chat("sys", "q", maxTokens, onChunk)?.message?.content ?: "(null)"
        } catch (e: AssertionError) {
            "ERROR_LOG"
        }
    }

    // N1 524 다음 200: 성공, 서버 2회 호출, error 로그 없음
    @Test
    fun n1_status524ThenOkSucceedsWithoutAnErrorLog() {
        behavior = { n, ex -> if (n == 0) status(ex, 524) else ok(ex) }
        assertEquals("정상 응답", call(client()))
        assertEquals(2, hits.get())
    }

    // N2 524 두 번: 지금과 같은 실패 처리(error 로그), 서버 2회 호출
    @Test
    fun n2_status524TwiceFailsLikeBeforeAfterOneRetry() {
        behavior = { _, ex -> status(ex, 524) }
        val r = call(client())
        assertTrue(r, r == "ERROR_LOG" || r.startsWith("[Error]"))
        assertEquals(2, hits.get())
    }

    // N3 첫 응답이 읽기 타임아웃(짧은 호출 1초)보다 늦음 다음 200: 성공
    @Test
    fun n3_firstResponseSlowerThanTheTimeoutThenOkSucceeds() {
        behavior = { n, ex -> if (n == 0) { Thread.sleep(2500); status(ex, 200) } else ok(ex) }
        assertEquals("정상 응답", call(client()))
        assertEquals(2, hits.get())
    }

    // N4 400: 재시도 없음, 서버 1회 호출
    @Test
    fun n4_status400IsNotRetried() {
        behavior = { _, ex -> status(ex, 400) }
        val r = call(client())
        assertTrue(r, r == "ERROR_LOG" || r.startsWith("[Error]"))
        assertEquals(1, hits.get())
    }

    // N5 스트림 호출: 524여도 재시도 로직을 타지 않음(서버 1회 호출). 스트림 정상 응답은 그대로 읽는다.
    @Test
    fun n5_streamCallsDoNotUseTheRetry() {
        behavior = { _, ex -> status(ex, 524) }
        val chunks = StringBuilder()
        val r = call(client(), onChunk = { chunks.append(it) })
        assertTrue(r, r == "ERROR_LOG" || r.startsWith("[Error]"))
        assertEquals(1, hits.get())
        hits.set(0)
        behavior = { _, ex -> sse(ex) }
        assertEquals("스트림", call(client(), onChunk = { chunks.append(it) }))
        assertEquals(1, hits.get())
    }

    // N6 max_tokens가 큰 호출(문서 생성 등)은 짧은 타임아웃을 쓰지 않고 타임아웃을 재시도하지 않지만, 524는 1회 재시도한다.
    @Test
    fun n6_longCallsKeepTheConfiguredTimeoutAndRetryOnlyStatusErrors() {
        behavior = { n, ex -> if (n == 0) { Thread.sleep(1500); ok(ex) } else ok(ex) }
        // 설정 타임아웃 1초, 짧은 호출 타임아웃은 해당 없음 -> 1.5초 응답은 타임아웃, 타임아웃은 재시도하지 않음
        val r = call(client(timeoutSeconds = 1, shortReadTimeoutSec = 90), maxTokens = 4000)
        assertTrue(r, r == "ERROR_LOG" || r.startsWith("[Error]"))
        assertEquals(1, hits.get())
        hits.set(0)
        behavior = { n, ex -> if (n == 0) status(ex, 524) else ok(ex) }
        assertEquals("정상 응답", call(client(), maxTokens = 4000))
        assertEquals(2, hits.get())
        assertFalse(hits.get() > 2)
    }
}
