package net.ib.ixpert.ops.wuwagent.client

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import net.ib.ixpert.ops.wuwagent.model.ChatMessage
import net.ib.ixpert.ops.wuwagent.setting.SettingsState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * B-41 스트림 절단 재시도: 가짜 SSE 서버로 "절단 → 재시도 성공", "절단 → 재시도도 절단 → 실패 기록", "타임아웃 → 재시도 없음"을 확인한다.
 */
class OpenAIClientStreamRetryTest {

    private val okArgs = """{"requiredFiles":[{"n":1,"reason":"정상 응답"}],"removedFiles":[],"reasoning":"1) 가."}"""
    private val cutArgs = """{"requiredFiles":[{"n":1,"reas"""

    private lateinit var server: HttpServer
    private val hits = AtomicInteger()
    // 요청 순번(0부터)에 대한 응답 동작
    @Volatile private var behavior: (Int, HttpExchange) -> Unit = { _, _ -> }

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
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

    private fun chunk(args: String, finish: String? = null): String {
        val argsJson = com.google.gson.Gson().toJson(args)
        val fin = if (finish == null) "null" else "\"$finish\""
        return """data: {"id":"t","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"c1","type":"function","function":{"name":"submit_verification","arguments":$argsJson}}]},"finish_reason":$fin}]}""" + "\n\n"
    }

    private fun sendSse(ex: HttpExchange, body: String) {
        ex.responseHeaders.add("Content-Type", "text/event-stream")
        ex.sendResponseHeaders(200, 0)
        ex.responseBody.use { it.write(body.toByteArray(Charsets.UTF_8)); it.flush() }
    }

    // 정상 종료 응답
    private fun complete(ex: HttpExchange) = sendSse(ex, chunk(okArgs) + """data: {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""" + "\n\n" + "data: [DONE]\n\n")

    // 인자 JSON 중간에서 [DONE]·finish_reason 없이 연결이 닫히는 절단 응답
    private fun truncated(ex: HttpExchange) = sendSse(ex, chunk(cutArgs))

    private fun client(timeoutSeconds: Int = 20) = OpenAIClient {
        SettingsState.State(
            apiType = SettingsState.ApiType.OPENAI_COMPATIBLE,
            openaiServerUrl = "http://127.0.0.1:${server.address.port}",
            model = "test-model",
            timeoutSeconds = timeoutSeconds
        )
    }

    private fun call(c: OpenAIClient) = c.chatWithTools("sys", listOf(ChatMessage(role = "user", content = "q")), 100, null, null, null)

    // R1 절단 → 재시도 성공: 서버 2회 호출, 두 번째 응답을 돌려주고 재시도 1회·실패 0회 기록
    @Test
    fun r1_truncatedThenRetrySucceeds() {
        behavior = { n, ex -> if (n == 0) truncated(ex) else complete(ex) }
        val retry0 = OpenAIClient.streamRetryCount.get(); val failed0 = OpenAIClient.streamRetryFailedCount.get()
        val r = call(client())
        assertEquals(2, hits.get())
        assertEquals(okArgs, r!!.toolCalls!!.single().function.arguments)
        assertEquals(1, OpenAIClient.streamRetryCount.get() - retry0)
        assertEquals(0, OpenAIClient.streamRetryFailedCount.get() - failed0)
    }

    // R2 절단 → 재시도도 절단: 재시도는 1회뿐(서버 2회), 끊긴 응답을 그대로 돌려주고 실패 1회 기록
    @Test
    fun r2_truncatedTwiceIsRecordedAsFailure() {
        behavior = { _, ex -> truncated(ex) }
        val retry0 = OpenAIClient.streamRetryCount.get(); val failed0 = OpenAIClient.streamRetryFailedCount.get()
        val r = call(client())
        assertEquals(2, hits.get())
        assertEquals(cutArgs, r!!.toolCalls!!.single().function.arguments)
        assertTrue(OpenAIClient.isTruncatedToolCall(r))
        assertEquals(1, OpenAIClient.streamRetryCount.get() - retry0)
        assertEquals(1, OpenAIClient.streamRetryFailedCount.get() - failed0)
    }

    // R3 타임아웃: 응답이 오지 않아 읽기 제한(1초)을 넘기면 예외를 그대로 던지고 다시 호출하지 않는다.
    @Test
    fun r3_timeoutIsNotRetried() {
        behavior = { _, _ -> Thread.sleep(3_500) }
        val retry0 = OpenAIClient.streamRetryCount.get()
        try {
            call(client(timeoutSeconds = 1))
            fail("타임아웃 예외가 던져져야 한다")
        } catch (e: Throwable) {
            // 기존 오류 로그(logger.error)가 테스트 환경에서는 AssertionError로 바뀌어 던져지므로 원인 체인에서 타임아웃을 확인한다.
            val chain = generateSequence(e) { it.cause }.toList()
            assertTrue("읽기 제한 초과가 원인이어야 한다: $chain", chain.any { it is java.net.SocketTimeoutException })
        }
        assertEquals(1, hits.get())
        assertEquals(0, OpenAIClient.streamRetryCount.get() - retry0)
    }

    // R4 정상 응답은 다시 호출하지 않는다.
    @Test
    fun r4_normalResponseIsNotRetried() {
        behavior = { _, ex -> complete(ex) }
        val retry0 = OpenAIClient.streamRetryCount.get()
        val r = call(client())
        assertEquals(1, hits.get())
        assertEquals(okArgs, r!!.toolCalls!!.single().function.arguments)
        assertEquals(0, OpenAIClient.streamRetryCount.get() - retry0)
    }
}
