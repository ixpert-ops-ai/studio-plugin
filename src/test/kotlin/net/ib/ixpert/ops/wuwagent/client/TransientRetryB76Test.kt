package net.ib.ixpert.ops.wuwagent.client

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import net.ib.ixpert.ops.wuwagent.model.ChatMessage
import net.ib.ixpert.ops.wuwagent.model.ToolDefinition
import net.ib.ixpert.ops.wuwagent.setting.SettingsState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** B-76 2: Connection reset 계열 IOException과 빈 스트림을 같은 일시 오류로 보고 1회 재시도한다(도구 호출 경로와 비스트림 chat). */
class TransientRetryB76Test {

    private lateinit var server: HttpServer
    private val hits = AtomicInteger()
    @Volatile private var behavior: (Int, HttpExchange) -> Unit = { _, _ -> }

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/") { ex ->
            val n = hits.getAndIncrement()
            try { ex.requestBody.readBytes(); behavior(n, ex) } catch (_: Exception) {} finally { try { ex.close() } catch (_: Exception) {} }
        }
        server.start()
    }

    @After
    fun tearDown() = server.stop(0)

    private val toolChunk = "{\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"t1\",\"type\":\"function\",\"function\":{\"name\":\"search_graph_nodes\",\"arguments\":\"{\\\"query\\\":\\\"x\\\"}\"}}]},\"finish_reason\":null}]}"

    private fun send(ex: HttpExchange, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", contentType)
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun sse(ex: HttpExchange) = send(ex, "text/event-stream", "data: $toolChunk\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n")

    /** 응답 없이 연결을 닫는다: 클라이언트에는 "Unexpected end of file from server" 같은 IOException으로 보인다. */
    private fun drop(ex: HttpExchange) { ex.close() }

    private fun client() = OpenAIClient(retryWaitMs = 10) {
        SettingsState.State(apiType = SettingsState.ApiType.OPENAI_COMPATIBLE, openaiServerUrl = "http://127.0.0.1:${server.address.port}", model = "test-model", timeoutSeconds = 20)
    }

    private fun call() = client().chatWithTools("sys", listOf(ChatMessage(role = "user", content = "요구사항")), 2048, emptyList<ToolDefinition>(), "auto", 0.0)

    @Test
    fun r1_연결이_끊긴_뒤_재시도에서_성공하면_그_응답을_쓴다() {
        behavior = { n, ex -> if (n == 0) drop(ex) else sse(ex) }
        val r = call()
        assertEquals(listOf("search_graph_nodes"), r?.choices?.firstOrNull()?.message?.toolCalls?.map { it.function.name })
        assertEquals(2, hits.get())
    }

    @Test
    fun r2_두_번_연속_실패하면_재시도는_1회뿐이고_예외로_알린다() {
        behavior = { _, ex -> drop(ex) }
        // 운영 클라이언트의 logger.error는 테스트 환경에서 AssertionError로 던져진다(B-56). 둘 중 하나로 실패가 알려져야 한다.
        val thrown = try { call(); null } catch (e: Throwable) { e }
        assertNotNull("실패로 알려야 한다", thrown)
        assertTrue(thrown.toString(), thrown is AssertionError || thrown is IOException)
        assertEquals("재시도는 1회뿐", 2, hits.get())
    }

    @Test
    fun r3_정상_응답은_재시도_없이_한_번만_부른다() {
        behavior = { _, ex -> sse(ex) }
        assertNotNull(call())
        assertEquals(1, hits.get())
    }

    @Test
    fun r4_빈_스트림과_연결_끊김은_합쳐_1회만_재시도한다() {
        // 첫 호출 연결 끊김 → 재시도 응답이 빈 스트림이어도 더 부르지 않는다(일시 오류 재시도는 합쳐서 1회)
        behavior = { n, ex -> if (n == 0) drop(ex) else send(ex, "text/event-stream", "") }
        val r = call()
        assertTrue(OpenAIClient.isEmptyToolResponse(r))
        assertEquals(2, hits.get())
    }

    @Test
    fun r5_일시_오류_판정은_타임아웃과_서버_오류_상태를_제외한다() {
        assertTrue(OpenAIClient.isTransientIo(IOException("Connection reset")))
        assertTrue(OpenAIClient.isTransientIo(java.net.SocketException("Unexpected end of file from server")))
        assertTrue(OpenAIClient.isTransientIo(IOException("wrapper", IOException("Premature EOF"))))
        assertFalse(OpenAIClient.isTransientIo(java.net.SocketTimeoutException("Read timed out")))
        assertFalse(OpenAIClient.isTransientIo(IOException("Server returned HTTP response code: 400 for URL")))
    }

    @Test
    fun r6_비스트림_chat도_연결이_끊기면_1회_다시_호출한다() {
        behavior = { n, ex -> if (n == 0) drop(ex) else send(ex, "application/json", "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"정상 응답\"}}]}") }
        val r = client().chat("sys", "q", 100, null)
        assertEquals("정상 응답", r?.message?.content)
        assertEquals(2, hits.get())
    }
}
