package net.ib.ixpert.ops.wuwagent.service

import com.intellij.util.io.HttpRequests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ServerStatusTrackerTest {
    private var clock = 1_000L
    private val tracker = ServerStatusTracker { clock }

    @Test
    fun `초기 상태는 CHECKING`() {
        assertEquals(ServerState.CHECKING, tracker.snapshot().state)
    }

    @Test
    fun `확인 성공은 OK와 응답시간 확시각을 기록`() {
        tracker.begin()
        clock = 2_000
        val s = tracker.finish(ServerState.OK, 120, null)
        assertEquals(ServerState.OK, s.state)
        assertEquals(120L, s.latencyMs)
        assertEquals(2_000L, s.lastCheckedAt)
        assertNull(s.failingSince)
    }

    @Test
    fun `실패 시작 시각은 복구 전까지 고정`() {
        clock = 1_000; tracker.finish(ServerState.UNREACHABLE, null, "연결 거부")
        clock = 5_000; val again = tracker.finish(ServerState.UNREACHABLE, null, "연결 거부")
        assertEquals(1_000L, again.failingSince)
        assertEquals(5_000L, again.lastCheckedAt)

        // 재확인 중에도 확정 상태(실패 시작 시각)는 유지되고 표시만 CHECKING
        val checking = tracker.begin()
        assertEquals(ServerState.CHECKING, checking.state)
        assertEquals(1_000L, checking.failingSince)

        // 복구 시 초기화
        clock = 9_000
        val ok = tracker.finish(ServerState.OK, 80, null)
        assertNull(ok.failingSince)
        assertNull(ok.errorSummary)

        // 다시 실패하면 새 시각부터
        clock = 12_000
        assertEquals(12_000L, tracker.requestFailed("응답 시간 초과").failingSince)
    }

    @Test
    fun `LLM 요청 성공은 응답시간을 유지하고 확인 시각만 갱신하며 UNREACHABLE에서 복구`() {
        clock = 1_000; tracker.finish(ServerState.OK, 150, null)
        clock = 3_000; val s = tracker.requestSucceeded()
        assertEquals(150L, s.latencyMs)
        assertEquals(3_000L, s.lastCheckedAt)

        clock = 4_000; tracker.requestFailed("연결 거부")
        clock = 6_000; val recovered = tracker.requestSucceeded()
        assertEquals(ServerState.OK, recovered.state)
        assertNull(recovered.failingSince)
    }

    @Test
    fun `연결 에러 분류 - 접속불가 타임아웃 5xx만 true`() {
        assertTrue(ServerErrors.isConnectionFailure(ConnectException("Connection refused")))
        assertTrue(ServerErrors.isConnectionFailure(SocketTimeoutException("Read timed out")))
        assertTrue(ServerErrors.isConnectionFailure(UnknownHostException("host")))
        assertTrue(ServerErrors.isConnectionFailure(java.io.IOException("Server returned HTTP response code: 502 for URL: http://x")))
        assertTrue(ServerErrors.isConnectionFailure(RuntimeException("wrap", ConnectException("refused"))))

        assertFalse(ServerErrors.isConnectionFailure(java.io.IOException("Server returned HTTP response code: 400 for URL: http://x")))
        assertFalse(ServerErrors.isConnectionFailure(java.io.IOException("Server returned HTTP response code: 401 for URL: http://x")))
        assertFalse(ServerErrors.isConnectionFailure(IllegalStateException("parse error")))
    }

    @Test
    fun `요약 문구에는 서버 주소가 들어가지 않는다`() {
        val summary = ServerErrors.summarize(ConnectException("Connection refused: http://secret.internal:8000/v1/models"))
        assertEquals("연결 거부", summary)
        assertFalse(summary.contains("secret"))
        assertEquals("응답 시간 초과", ServerErrors.summarize(SocketTimeoutException("x http://a")))
        assertEquals("서버 오류(503)", ServerErrors.summarize(java.io.IOException("Server returned HTTP response code: 503 for URL: http://a")))
    }

    @Test
    fun `HttpStatusException 상태코드 추출`() {
        val e = HttpRequests.HttpStatusException("fail", 404, "http://a")
        assertEquals(404, ServerErrors.statusCodeOf(e))
    }
}

/** 목 서버로 /v1/models 조회 예외가 상태 분류(ServerErrors)까지 올바르게 이어지는지 확인 */
class ServerStatusHttpTest {
    private fun server(handler: (com.sun.net.httpserver.HttpExchange) -> Unit): com.sun.net.httpserver.HttpServer =
        com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/models") { handler(it) }
            start()
        }

    private fun url(s: com.sun.net.httpserver.HttpServer) = "http://127.0.0.1:${s.address.port}"

    private fun respond(ex: com.sun.net.httpserver.HttpExchange, code: Int, body: String = "{}") {
        val bytes = body.toByteArray()
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    @Test
    fun `정상 응답은 모델 목록을 반환`() {
        val s = server { respond(it, 200, """{"data":[{"id":"m-a"},{"id":"m-b"}]}""") }
        try {
            assertEquals(listOf("m-a", "m-b"), net.ib.ixpert.ops.wuwagent.client.OpenAIClient().fetchModelsOrThrow(url(s), ""))
        } finally { s.stop(0) }
    }

    @Test
    fun `서버가 꺼져 있으면 예외가 전파되고 연결 거부로 분류`() {
        val s = server { respond(it, 200) }
        val u = url(s)
        s.stop(0) // 포트를 닫아 접속 불가 상태를 만든다
        val e = runCatching { net.ib.ixpert.ops.wuwagent.client.OpenAIClient().fetchModelsOrThrow(u, "") }.exceptionOrNull()
        assertTrue(e != null)
        assertTrue(ServerErrors.isConnectionFailure(e!!))
        assertEquals("연결 거부", ServerErrors.summarize(e))
    }

    @Test
    fun `HTTP 502는 UNREACHABLE 404는 서버 응답 있음으로 구분`() {
        val bad = server { respond(it, 502) }
        val nf = server { respond(it, 404) }
        try {
            val e502 = runCatching { net.ib.ixpert.ops.wuwagent.client.OpenAIClient().fetchModelsOrThrow(url(bad), "") }.exceptionOrNull()!!
            assertEquals(502, ServerErrors.statusCodeOf(e502))
            assertTrue(ServerErrors.isConnectionFailure(e502))

            val e404 = runCatching { net.ib.ixpert.ops.wuwagent.client.OpenAIClient().fetchModelsOrThrow(url(nf), "") }.exceptionOrNull()!!
            assertEquals(404, ServerErrors.statusCodeOf(e404))
            assertFalse(ServerErrors.isConnectionFailure(e404))
        } finally { bad.stop(0); nf.stop(0) }
    }
}
