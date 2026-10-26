package net.ib.ixpert.ops.wuwagent.agent.clarify

import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.ConstraintKind
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.client.OpenAIClient
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.OllamaMessage
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ArchitectureLayer
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FileNode
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.GraphStatistics
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.SpringFileType
import net.ib.ixpert.ops.wuwagent.setting.SettingsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

/**
 * B-73 제약 추출 구조화 출력(JSON 스키마). 모의 클라이언트와 가짜 서버만 쓴다(실서버 없음).
 * 사례 3종: 이스케이프 안 된 따옴표 응답(B-72 2-m의 D2 실패 사례), 정상 응답, 서버 거부 응답.
 */
class ConstraintStructuredOutputB73Test {

    private val initNote = "요구사항에서 조건을 자동으로 정리하지 못했습니다. 분석에는 영향이 없으며, 범위나 조건이 있으면 말씀해 주세요."

    /** B-72 2-m 실패 사례(D2_run1_c13.resp.json의 content): evidence 값에 따옴표가 이스케이프되지 않은 깨진 JSON. */
    private val brokenQuotes = "[\n  {\n    \"kind\": \"OTHER\",\n    \"value\": \"스펙 참조 조회 기능 구현\",\n    \"rawStatement\": \"참조하여 최근이력 1건 조회\",\n    \"evidence\": \"참조하여\", \"앱카드회원ID 및 모니모페이회원ID\", \"최근이력 1건 조회\"\n  }\n]"
    private val sr = "참조하여 앱카드회원ID 및 모니모페이회원ID 최근이력 1건 조회"
    private val validJson = "[{\"kind\":\"OTHER\",\"value\":\"조회 기능 구현\",\"rawStatement\":\"$sr\",\"evidence\":\"최근이력 1건 조회\"}]"

    /** structured가 null이면 서버 거부로 본다. legacy는 기존 chat 응답. */
    private class Fake(val structured: String?, val legacy: String) : LLMClient {
        var structuredCalls = 0
        var legacyExtractionCalls = 0
        var schemaSeen: Map<String, Any>? = null
        private fun resp(c: String) = OllamaChatResponse(model = "fake", createdAt = "", message = OllamaMessage("assistant", c), done = true)
        override fun chatJsonSchema(systemPrompt: String, userCode: String, maxTokens: Int?, schemaName: String, schema: Map<String, Any>): OllamaChatResponse? {
            structuredCalls++
            schemaSeen = schema
            return structured?.let { resp(it) }
        }
        override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): OllamaChatResponse {
            val c = when {
                systemPrompt.contains("ConstraintKind") -> { legacyExtractionCalls++; legacy }
                systemPrompt.contains("소프트웨어 요구사항 정제 전문가") -> "정제된 요구사항"
                else -> "추가로 수정할 대상이 있으신가요?"
            }
            return resp(c)
        }
        override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
    }

    private fun graph(): ProjectGraph {
        val n = FileNode(path = "src/survey/SurveyServiceImpl.java", packageName = "p", className = "SurveyServiceImpl", fileType = SpringFileType.SERVICE,
            layer = ArchitectureLayer.BUSINESS, koreanComments = listOf("설문 서비스"))
        return ProjectGraph(generatedAt = java.time.Instant.now().toString(), projectRoot = "/test/root", frameworkType = FrameworkType.SPRING_MVC_MYBATIS,
            files = mapOf(n.path to n), relationships = emptyList(), resourceNodes = emptyList(), statistics = GraphStatistics())
    }

    private fun engine(llm: Fake): Stage0ClarificationEngine {
        val g = graph()
        return Stage0ClarificationEngine(Stage0GraphScanner(g, minSpecificityScore = 1.0, proposalBudget = 10), g, llmClient = llm)
    }

    private fun <T> captureOut(block: () -> T): Pair<T, String> {
        val original = System.out
        val buf = ByteArrayOutputStream()
        System.setOut(PrintStream(buf, true, "UTF-8"))
        try { return block() to buf.toString("UTF-8") } finally { System.setOut(original) }
    }

    @Test
    fun s1_따옴표_깨진_응답은_구조화_응답이_먼저_쓰여_실패_안내가_없다() {
        // 기존 경로(chat)는 같은 깨진 JSON을 주지만 구조화 응답이 먼저 쓰여 파싱 실패가 나지 않는다
        val llm = Fake(structured = validJson, legacy = brokenQuotes)
        val (init, out) = captureOut { engine(llm).initSession(sr) }
        assertFalse(init.echoBackMessage ?: "", (init.echoBackMessage ?: "").contains(initNote))
        assertFalse(out, out.contains("[ExtractFail]"))
        assertEquals(1, llm.structuredCalls)
        assertEquals("구조화 응답이 성공하면 기존 추출 호출은 하지 않는다", 0, llm.legacyExtractionCalls)
    }

    @Test
    fun s2_서버가_스키마를_받지_않고_같은_깨진_응답이_오면_기존과_같이_파싱_실패다() {
        // 구조화 응답이 없으면(null) 기존 경로로 대체: D2 사례가 그대로 실패한다(기존 동작 불변)
        val llm = Fake(structured = null, legacy = brokenQuotes)
        val (init, out) = captureOut { engine(llm).initSession(sr) }
        assertTrue(init.echoBackMessage ?: "", (init.echoBackMessage ?: "").contains(initNote))
        val line = out.lineSequence().firstOrNull { it.startsWith("[ExtractFail]") }
        assertNotNull(out, line)
        assertTrue(line, line!!.contains("종류=파싱 실패"))
        assertTrue(out, out.contains("[ExtractSchema] 구조화 출력 응답 없음"))
        assertEquals(1, llm.legacyExtractionCalls)
    }

    @Test
    fun s3_정상_응답과_서버_거부_뒤_정상_대체_응답은_모두_성공한다() {
        val accepted = Fake(validJson, brokenQuotes)
        val rejected = Fake(null, validJson)
        for (llm in listOf(accepted, rejected)) {
            val (init, out) = captureOut { engine(llm).initSession(sr) }
            assertFalse(init.echoBackMessage ?: "", (init.echoBackMessage ?: "").contains(initNote))
            assertFalse(out, out.contains("[ExtractFail]"))
        }
        assertEquals(0, accepted.legacyExtractionCalls)
        assertEquals("거부되면 같은 추출을 기존 호출로 한 번 더 한다", 1, rejected.legacyExtractionCalls)
    }

    @Test
    fun s4_스키마는_배열이고_kind는_ConstraintKind_값이며_필수_필드를_모두_요구한다() {
        val llm = Fake(validJson, validJson)
        engine(llm).initSession(sr)
        val schema = llm.schemaSeen!!
        assertEquals("array", schema["type"])
        @Suppress("UNCHECKED_CAST") val items = schema["items"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST") val props = items["properties"] as Map<String, Map<String, Any>>
        assertEquals(ConstraintKind.values().map { it.name }, props["kind"]!!["enum"])
        assertEquals(listOf("kind", "value", "rawStatement", "evidence"), items["required"])
        assertEquals(false, items["additionalProperties"])
    }

    // OpenAIClient: 요청 본문에 response_format이 실리고, 서버가 거부하면 null(error 로그 없음)
    @Test
    fun s5_OpenAIClient는_json_schema를_보내고_거부되면_null을_돌려준다() {
        val body = AtomicReference<String>()
        var status = 200
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            body.set(String(ex.requestBody.readBytes(), Charsets.UTF_8))
            val text = if (status == 200) "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"[]\"}}]}" else "bad schema"
            val bytes = text.toByteArray(Charsets.UTF_8)
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val c = OpenAIClient(retryWaitMs = 10) {
                SettingsState.State(apiType = SettingsState.ApiType.OPENAI_COMPATIBLE, openaiServerUrl = "http://127.0.0.1:${server.address.port}", model = "test-model", timeoutSeconds = 20)
            }
            val schema = mapOf<String, Any>("type" to "array", "items" to mapOf("type" to "object"))
            val ok = c.chatJsonSchema("sys", "q", 500, "constraints", schema)
            assertEquals("[]", ok?.message?.content)
            val rf = JsonParser.parseString(body.get()).asJsonObject.getAsJsonObject("response_format")
            assertEquals("json_schema", rf.get("type").asString)
            assertEquals("constraints", rf.getAsJsonObject("json_schema").get("name").asString)
            assertTrue(rf.getAsJsonObject("json_schema").get("strict").asBoolean)
            assertEquals("array", rf.getAsJsonObject("json_schema").getAsJsonObject("schema").get("type").asString)
            // 기존 chat은 response_format을 보내지 않는다
            c.chat("sys", "q", 100, null)
            assertFalse(body.get(), body.get().contains("response_format"))
            // 서버가 400으로 거부하면 null(경고만, error 로그 없음)
            status = 400
            assertNull(c.chatJsonSchema("sys", "q", 500, "constraints", schema))
        } finally {
            server.stop(0)
        }
    }
}
