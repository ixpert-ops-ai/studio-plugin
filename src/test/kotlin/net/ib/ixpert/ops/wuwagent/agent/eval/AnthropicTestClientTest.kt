package net.ib.ixpert.ops.wuwagent.agent.eval

import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import net.ib.ixpert.ops.wuwagent.agent.FileRelevanceVerifier
import net.ib.ixpert.ops.wuwagent.agent.TargetFileSpec
import net.ib.ixpert.ops.wuwagent.model.ChatMessage
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.GraphStatistics
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicReference

/**
 * 진단용 Anthropic 어댑터 검증. 가짜 서버로 요청·응답 변환을 확인한다(외부 전송 없음). 스모크 호출은 ANTHROPIC_API_KEY가 있을 때만 실행.
 */
class AnthropicTestClientTest {

    private lateinit var server: HttpServer
    private val lastBody = AtomicReference<String>()
    private val lastHeaders = AtomicReference<Map<String, String>>()
    @Volatile private var status = 200
    @Volatile private var reply = ""

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            lastBody.set(String(ex.requestBody.readBytes(), Charsets.UTF_8))
            lastHeaders.set(ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") })
            val bytes = reply.toByteArray(Charsets.UTF_8)
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun tearDown() = server.stop(0)

    private fun client(key: String? = "test-key-not-real") =
        AnthropicTestClient(model = "claude-sonnet-5", baseUrl = "http://127.0.0.1:${server.address.port}", keyProvider = { key })

    private val graph = ProjectGraph(generatedAt = "now", projectRoot = "/", files = emptyMap(), relationships = emptyList(), statistics = GraphStatistics())

    // A1 Stage 3 호출을 실제 검증기 경로로 보내면 Messages API 형식(최상위 system, input_schema, auto, temperature 0.1, max_tokens 4000)이고 Qwen 전용·스트림 항목이 없다.
    @Test
    fun a1_requestUsesMessagesApiShapeWithoutQwenParameters() {
        reply = """{"id":"msg_1","model":"claude-sonnet-5","stop_reason":"tool_use","usage":{"input_tokens":11,"output_tokens":7},"content":[{"type":"tool_use","id":"toolu_1","name":"submit_verification","input":{"requiredFiles":[{"n":1,"reason":"필드 추가가 필요함"}],"removedFiles":[2],"reasoning":"1) 가."}}]}"""
        val c = client()
        val specs = listOf(TargetFileSpec(1, "/src/A.java", "MODIFY", ""), TargetFileSpec(2, "/src/B.java", "MODIFY", ""))
        val out = FileRelevanceVerifier(c, graph, Paths.get("nonexistent-docs")).verify("합성 요구사항", specs)
        assertEquals(listOf("/src/A.java"), out.files.map { it.path })
        assertEquals(listOf("/src/B.java"), out.removed)
        val body = JsonParser.parseString(lastBody.get()).asJsonObject
        assertEquals("claude-sonnet-5", body.get("model").asString)
        assertEquals(4000, body.get("max_tokens").asInt)
        assertEquals(0.1, body.get("temperature").asDouble, 1e-9)
        assertTrue(body.get("system").asString.contains("코드 변경 범위 검증자"))
        assertTrue(body.getAsJsonArray("messages").all { it.asJsonObject.get("role").asString != "system" })
        val tool = body.getAsJsonArray("tools").single().asJsonObject
        assertEquals("submit_verification", tool.get("name").asString)
        assertEquals("object", tool.getAsJsonObject("input_schema").get("type").asString)
        assertEquals("auto", body.getAsJsonObject("tool_choice").get("type").asString)
        for (k in listOf("stream", "chat_template_kwargs", "enable_thinking", "extra_body")) assertFalse("$k 를 보내면 안 됨", body.has(k))
        assertEquals("2023-06-01", lastHeaders.get()["anthropic-version"])
        assertEquals("test-key-not-real", lastHeaders.get()["x-api-key"])
        // 기록에는 키·헤더가 없다.
        val u = c.usages.single()
        assertEquals(11, u.inputTokens); assertEquals(7, u.outputTokens); assertEquals("tool_use", u.stopReason)
        assertFalse(u.toString().contains("test-key-not-real"))
    }

    // A2 chat은 텍스트 블록을 이어 붙여 돌려주고 system을 최상위로 보낸다.
    @Test
    fun a2_chatReturnsTextBlocks() {
        reply = """{"id":"m","model":"claude-sonnet-5","stop_reason":"end_turn","usage":{"input_tokens":5,"output_tokens":2},"content":[{"type":"text","text":"안"},{"type":"text","text":"녕"}]}"""
        val r = client().chat("시스템", "질문", 50, null)
        assertEquals("안녕", r!!.message!!.content)
        val body = JsonParser.parseString(lastBody.get()).asJsonObject
        assertEquals("시스템", body.get("system").asString)
        assertEquals(50, body.get("max_tokens").asInt)
        assertFalse(body.has("tools"))
    }

    // A3 tool_use 응답은 운영 모양으로 바뀌고 stop_reason이 매핑된다. max_tokens는 length.
    @Test
    fun a3_toolUseIsConvertedAndStopReasonMapped() {
        reply = """{"id":"m","model":"claude-sonnet-5","stop_reason":"max_tokens","usage":{"input_tokens":1,"output_tokens":4000},"content":[{"type":"tool_use","id":"toolu_9","name":"t","input":{"a":[1,2]}}]}"""
        val r = client().chatWithTools("s", listOf(ChatMessage("user", "q")), 4000, null, null, null)
        assertEquals("""{"a":[1,2]}""", r!!.toolCalls!!.single().function.arguments)
        assertEquals("t", r.toolCalls!!.single().function.name)
        assertEquals("length", r.choices!!.single().finishReason)
    }

    // A4 키가 없으면 호출하지 않고, HTTP 오류 메시지에는 상태와 본문만 담긴다(키 미포함).
    @Test
    fun a4_noKeyAndHttpErrorHandling() {
        assertFalse(client(null).hasKey())
        try { client(null).chat("s", "u", 10, null); fail() } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("ANTHROPIC_API_KEY")) }
        assertNull(lastBody.get())
        status = 400; reply = """{"type":"error","error":{"type":"invalid_request_error","message":"bad"}}"""
        try { client().chat("s", "u", 10, null); fail() } catch (e: java.io.IOException) {
            assertTrue(e.message!!.startsWith("Anthropic API HTTP 400"))
            assertFalse(e.message!!.contains("test-key-not-real"))
        }
    }

    // A5 curl 전송(https용)도 같은 요청을 보낸다: 본문·헤더 전달, 키는 응답·기록에 남지 않음, 임시 요청 파일은 삭제됨(가짜 http 서버로 확인).
    @Test
    fun a5_curlTransportSendsSameRequest() {
        reply = """{"id":"m","model":"claude-sonnet-5","stop_reason":"end_turn","usage":{"input_tokens":3,"output_tokens":1},"content":[{"type":"text","text":"한글 OK"}]}"""
        val before = java.io.File(System.getProperty("java.io.tmpdir")).listFiles { f -> f.name.startsWith("anthropic_req") }!!.size
        val c = AnthropicTestClient(model = "claude-sonnet-5", baseUrl = "http://127.0.0.1:${server.address.port}", keyProvider = { "test-key-not-real" }, useCurl = true)
        val r = c.chat("시스템", "질문 한글", 20, null)
        assertEquals("한글 OK", r!!.message!!.content)
        val body = JsonParser.parseString(lastBody.get()).asJsonObject
        assertEquals("질문 한글", body.getAsJsonArray("messages").single().asJsonObject.get("content").asString)
        assertEquals("test-key-not-real", lastHeaders.get()["x-api-key"])
        assertEquals("2023-06-01", lastHeaders.get()["anthropic-version"])
        assertEquals(3, c.usages.single().inputTokens)
        assertFalse(c.usages.single().toString().contains("test-key-not-real"))
        assertEquals(before, java.io.File(System.getProperty("java.io.tmpdir")).listFiles { f -> f.name.startsWith("anthropic_req") }!!.size)
    }

    // 스모크: 짧은 chat 1회 + Stage 3 스키마(실제 검증기 경로) tool 호출 1회. 합성 내용만 보낸다. 키가 없으면 skip.
    @Test
    fun smoke_realApiWithSyntheticContent() {
        val c = AnthropicTestClient()
        if (!c.hasKey()) println("[ANTHROPIC] ANTHROPIC_API_KEY 없음 — 스모크 skip")
        Assume.assumeTrue("ANTHROPIC_API_KEY 없음", c.hasKey())
        val chat = c.chat("", "OK 라고만 답하세요.", 20, null)
        println("[ANTHROPIC] chat 응답: ${chat?.message?.content}")
        val specs = listOf(
            TargetFileSpec(1, "/demo/OrderService.java", "MODIFY", ""),
            TargetFileSpec(2, "/demo/OrderDao.java", "MODIFY", ""),
            TargetFileSpec(3, "/demo/UnrelatedBanner.java", "MODIFY", "")
        )
        val out = FileRelevanceVerifier(c, graph, Paths.get("nonexistent-docs")).verify("주문 서비스에서 주문 취소 시 사유를 저장한다(합성 예시).", specs)
        println("[ANTHROPIC] Stage3 남음=${out.files.map { it.path }} 제거=${out.removed} 판정누락=${out.unjudged}")
        c.usages.forEach { println("[ANTHROPIC] ${it.kind}: model=${it.model} stop_reason=${it.stopReason} in=${it.inputTokens} out=${it.outputTokens} ${it.ms}ms") }
    }
}
