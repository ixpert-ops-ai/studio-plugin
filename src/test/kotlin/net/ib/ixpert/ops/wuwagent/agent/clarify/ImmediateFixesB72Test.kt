package net.ib.ixpert.ops.wuwagent.agent.clarify

import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.OllamaMessage
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ArchitectureLayer
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FileNode
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.GraphStatistics
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.SpringFileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * B-72 즉시 수정(모의 LLM, IDE·서버 없음).
 * x1 초기 추출 실패 → 새 문구 1줄, 사용자 발화 없음, "파일명" 단어 없음
 * x2 발화 추출 실패 → 기존 문구 그대로
 * x3 [ExtractFail] 줄에 종류와 응답 본문 앞 500자(본문이 짧으면 전체)
 */
class ImmediateFixesB72Test {

    private val initNote = "요구사항에서 조건을 자동으로 정리하지 못했습니다. 분석에는 영향이 없으며, 범위나 조건이 있으면 말씀해 주세요."
    private val utteranceNote = "요청하신 조건을 해석하지 못했습니다. 제외하거나 한정할 파일이 있다면 파일명으로 다시 말씀해 주세요."

    private class Fake(var constraints: String?) : LLMClient {
        override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): OllamaChatResponse {
            val content = when {
                systemPrompt.contains("ConstraintKind") -> constraints ?: throw RuntimeException("제약 추출 호출 예외")
                systemPrompt.contains("소프트웨어 요구사항 정제 전문가") -> "정제된 요구사항"
                else -> "추가로 수정할 대상이 있으신가요?"
            }
            return OllamaChatResponse(model = "fake", createdAt = "", message = OllamaMessage("assistant", content), done = true)
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
        try {
            val r = block()
            return r to buf.toString("UTF-8")
        } finally {
            System.setOut(original)
        }
    }

    private val failing = "이건 JSON이 아닙니다"

    @Test
    fun x1_초기_추출_실패는_새_문구_한_줄이고_발화_없이_파일명_단어가_없다() {
        for (resp in listOf("[Error] 통신 실패", "", failing, null)) {
            val init = engine(Fake(resp)).initSession("설문 발송 채널 확장")
            val echo = init.echoBackMessage
            assertNotNull(echo)
            assertEquals("새 문구가 정확히 1번: $echo", 1, Regex(Regex.escape(initNote)).findAll(echo!!).count())
            assertFalse("\"파일명\" 단어가 없어야 한다: $echo", echo.contains("파일명"))
            assertFalse("발화 실패 문구가 섞이면 안 된다: $echo", echo.contains(utteranceNote))
            // 사용자 발화 없이 나온 첫 화면이므로 대화 이력에 사용자 턴이 없다
            assertTrue(init.state.dialogueHistory.none { it.role == "user" })
            assertTrue(init.state.userStatements.isEmpty())
        }
    }

    @Test
    fun x2_발화_추출_실패는_기존_문구_그대로다() {
        val llm = Fake("""[{"kind":"OTHER","value":"v","rawStatement":"설문 발송 채널 확장","evidence":"설문 발송 채널 확장"}]""")
        val e = engine(llm)
        val t0 = e.initSession("설문 발송 채널 확장")
        assertFalse(t0.echoBackMessage ?: "", (t0.echoBackMessage ?: "").contains(initNote))
        llm.constraints = failing
        val u1 = e.processUtterance(t0.state, "알림톡 배치는 건드리지 말고 SurveyServiceImpl만 수정할 거야")
        val echo = u1.echoBackMessage ?: ""
        assertTrue(echo, echo.contains(utteranceNote))
        assertFalse("발화 단계에서 초기 문구가 나오면 안 된다: $echo", echo.contains(initNote))
    }

    @Test
    fun x3_ExtractFail_줄은_종류와_본문_앞_500자다() {
        val long = "가".repeat(700)
        val cases = listOf(
            Triple("[Error] 통신 실패", "[Error]", "[Error] 통신 실패"),
            Triple("", "응답 없음", ""),
            Triple(long, "파싱 실패", "가".repeat(500)),
            Triple(failing, "파싱 실패", failing),
            Triple(null, "예외", "")
        )
        for ((resp, kind, expectedBody) in cases) {
            val (_, out) = captureOut { engine(Fake(resp)).initSession("설문 발송 채널 확장") }
            val line = out.lineSequence().firstOrNull { it.startsWith("[ExtractFail]") }
            assertNotNull("[ExtractFail] 줄이 있어야 한다($kind): $out", line)
            assertTrue("종류 $kind: $line", line!!.contains("종류=$kind "))
            assertTrue("본문 앞 500자: $line", line.contains("=$expectedBody") || expectedBody.isEmpty())
            if (resp != null && resp.length > 500) {
                assertTrue("501번째 글자부터는 없다: $line", !line.contains("가".repeat(501)))
                assertTrue("본문 길이 표시(700자 중 앞 500자): $line", line.contains("(700자 중 앞 500자)"))
            }
            if (kind == "예외") assertTrue(line, line.contains("RuntimeException"))
        }
        // 정상 응답과 빈 목록 응답은 실패 줄을 남기지 않는다
        val (_, ok) = captureOut { engine(Fake("[]")).initSession("설문 발송 채널 확장") }
        assertFalse(ok, ok.contains("[ExtractFail]"))
    }
}
