package net.ib.ixpert.ops.wuwagent.client

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import net.ib.ixpert.ops.wuwagent.agent.FileRelevanceVerifier
import net.ib.ixpert.ops.wuwagent.agent.TargetFileSpec
import net.ib.ixpert.ops.wuwagent.model.ChatChoice
import net.ib.ixpert.ops.wuwagent.model.ChatCompletionResponse
import net.ib.ixpert.ops.wuwagent.model.ChatMessage
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.OllamaMessage
import net.ib.ixpert.ops.wuwagent.model.ToolDefinition
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.AgenticSeedSelector
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ArchitectureLayer
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FileNode
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.GraphStatistics
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.SpringFileType
import net.ib.ixpert.ops.wuwagent.setting.SettingsState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.nio.file.Paths
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * B-74 보충: content와 tool_calls가 모두 빈 도구 호출 응답은 오류로 분류해 1회 다시 호출하고(그래도 비면 호출부가 실패로 처리),
 * "도구 없이 끝남"과 로그에서 구분한다. 스트림 조립은 "data:"(공백 없음), 비스트림 JSON 본문, 서버 오류 본문을 더 이상 버리지 않는다.
 */
class EmptyToolResponseB74Test {

    private lateinit var server: HttpServer
    private val hits = AtomicInteger()
    @Volatile private var behavior: (Int, HttpExchange) -> Unit = { _, _ -> }

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
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
    fun tearDown() = server.stop(0)

    private val toolChunk = "{\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"t1\",\"type\":\"function\",\"function\":{\"name\":\"search_graph_nodes\",\"arguments\":\"{\\\"query\\\":\\\"survey email\\\"}\"}}]},\"finish_reason\":null}]}"
    private val doneChunk = "{\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}"

    private fun send(ex: HttpExchange, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", contentType)
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun sse(ex: HttpExchange, space: Boolean = true) =
        send(ex, "text/event-stream", "data:${if (space) " " else ""}$toolChunk\n\ndata:${if (space) " " else ""}$doneChunk\n\ndata: [DONE]\n\n")

    private fun client() = OpenAIClient(retryWaitMs = 10) {
        SettingsState.State(apiType = SettingsState.ApiType.OPENAI_COMPATIBLE, openaiServerUrl = "http://127.0.0.1:${server.address.port}", model = "test-model", timeoutSeconds = 20)
    }

    private fun call(): ChatCompletionResponse? =
        client().chatWithTools("sys", listOf(ChatMessage(role = "user", content = "요구사항")), 2048, emptyList<ToolDefinition>(), "auto", 0.0)

    private fun names(r: ChatCompletionResponse?) = r?.choices?.firstOrNull()?.message?.toolCalls?.map { it.function.name }

    @Test
    fun m1_빈_응답은_오류로_분류해_한_번_다시_호출하고_두_번째가_정상이면_쓴다() {
        behavior = { n, ex -> if (n == 0) send(ex, "text/event-stream", "") else sse(ex) }
        val r = call()
        assertEquals(listOf("search_graph_nodes"), names(r))
        assertEquals(2, hits.get())
    }

    @Test
    fun m2_다시_호출해도_비면_빈_응답을_그대로_돌려준다_호출부가_실패로_처리한다() {
        behavior = { _, ex -> send(ex, "text/event-stream", "") }
        val r = call()
        assertTrue(OpenAIClient.isEmptyToolResponse(r))
        assertEquals("재시도는 1회뿐", 2, hits.get())
    }

    @Test
    fun m3_정상_응답과_도구_없이_내용만_있는_응답은_재시도하지_않는다() {
        behavior = { _, ex -> sse(ex) }
        assertEquals(listOf("search_graph_nodes"), names(call()))
        assertEquals(1, hits.get())
        hits.set(0)
        behavior = { _, ex -> send(ex, "text/event-stream", "data: {\"choices\":[{\"delta\":{\"content\":\"도구 없이 답합니다\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n") }
        val r = call()
        assertFalse(OpenAIClient.isEmptyToolResponse(r))
        assertEquals(1, hits.get())
    }

    @Test
    fun m4_data_콜론_뒤_공백이_없는_SSE도_읽는다() {
        behavior = { _, ex -> sse(ex, space = false) }
        assertEquals(listOf("search_graph_nodes"), names(call()))
        assertEquals(1, hits.get())
    }

    @Test
    fun m5_서버가_스트림_대신_일반_JSON_응답을_보내도_도구_호출을_읽는다() {
        val body = "{\"id\":\"x\",\"choices\":[{\"index\":0,\"finish_reason\":\"tool_calls\",\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"t1\",\"type\":\"function\",\"function\":{\"name\":\"confirm_final_seeds\",\"arguments\":\"{\\\"seed_classes\\\":[\\\"A\\\"]}\"}}]}}]}"
        behavior = { _, ex -> send(ex, "application/json", body) }
        val r = call()
        assertEquals(listOf("confirm_final_seeds"), names(r))
        assertEquals(1, hits.get())
    }

    @Test
    fun m6_서버_오류_본문은_빈_응답으로_삼키지_않고_오류로_알린다() {
        behavior = { _, ex -> send(ex, "application/json", "{\"error\":{\"message\":\"overloaded\",\"type\":\"server_error\"}}") }
        // 운영 클라이언트의 logger.error는 테스트 환경에서 AssertionError로 던져진다(B-56). 둘 중 하나로 오류가 알려져야 한다.
        val thrown = try { call(); null } catch (e: Throwable) { e }
        assertNotNull("오류로 알려야 한다", thrown)
        assertTrue(thrown.toString(), thrown is AssertionError || thrown?.message?.contains("overloaded") == true)
        assertEquals("오류 본문은 재시도하지 않는다", 1, hits.get())
    }

    // ── 호출부: 빈 응답과 "도구 없이 끝남"을 구분해 로그를 남긴다 ──
    private class Stub(val message: ChatMessage?) : LLMClient {
        override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): OllamaChatResponse =
            OllamaChatResponse(model = "stub", createdAt = "", message = OllamaMessage("assistant", "{}"), done = true)
        override fun chatWithTools(systemPrompt: String, messages: List<ChatMessage>, maxTokens: Int?, tools: List<ToolDefinition>?, toolChoice: Any?, temperature: Double?): ChatCompletionResponse? =
            message?.let { ChatCompletionResponse("x", listOf(ChatChoice(0, it, "stop"))) }
        override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
    }

    private fun graph() = ProjectGraph(
        generatedAt = "now", projectRoot = "/", files = mapOf("a/A.java" to FileNode(path = "a/A.java", packageName = "a", className = "A", fileType = SpringFileType.SERVICE, layer = ArchitectureLayer.SERVICE)),
        relationships = emptyList(), statistics = GraphStatistics()
    )

    private fun <T> captureOut(block: () -> T): Pair<T, String> {
        val original = System.out
        val buf = ByteArrayOutputStream()
        System.setOut(PrintStream(buf, true, "UTF-8"))
        try { return block() to buf.toString("UTF-8") } finally { System.setOut(original) }
    }

    @Test
    fun m7_탐색기는_빈_응답과_도구_없이_끝남을_다르게_기록하고_둘_다_대체_경로다() {
        val (empty, outEmpty) = captureOut { AgenticSeedSelector(Stub(ChatMessage(role = "assistant", content = null))).selectSeeds("A 수정", graph()) }
        assertTrue(outEmpty, outEmpty.contains("[EmptyResponse]"))
        assertFalse(outEmpty, outEmpty.contains("finished without tool calls"))
        assertTrue(empty.usedFallback)
        val (noTool, outNoTool) = captureOut { AgenticSeedSelector(Stub(ChatMessage(role = "assistant", content = "도구 없이 끝냅니다"))).selectSeeds("A 수정", graph()) }
        assertTrue(outNoTool, outNoTool.contains("finished without tool calls"))
        assertFalse(outNoTool, outNoTool.contains("[EmptyResponse]"))
        assertTrue(noTool.usedFallback)
    }

    @Test
    fun m8_검증기는_빈_응답이어도_후보를_유지하고_대체_경로로_표시한다() {
        val specs = listOf(TargetFileSpec(1, "a/A.java", "MODIFY", ""))
        val out = FileRelevanceVerifier(Stub(ChatMessage(role = "assistant", content = null)), graph(), Paths.get("nonexistent-docs")).verify("요구사항", specs)
        assertTrue(out.usedFallback)
        assertEquals(listOf("a/A.java"), out.files.map { it.path })
    }
}
