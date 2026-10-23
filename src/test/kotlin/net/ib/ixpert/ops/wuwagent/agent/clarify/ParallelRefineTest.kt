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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * B-63 후보 A: buildClarifyIntent가 최종 제약 추출과 정제문 호출을 동시에 시작한다(정제문은 제약 추출 결과를 쓰지 않는다).
 * 모의 LLM에 지연을 넣어 임계 경로가 줄었는지, 한쪽만 실패할 때 기존 동작이 유지되는지, 결과 ClarifyIntent가 지연과 무관하게 같은지 확인한다.
 */
class ParallelRefineTest {

    private val note = "요청하신 조건을 해석하지 못했습니다. 제외하거나 한정할 파일이 있다면 파일명으로 다시 말씀해 주세요."
    private val sr = "설문 발송 채널 확장"
    private val okConstraints = """[{"kind": "SCOPE_LIMIT", "value": "수정 범위 한정", "rawStatement": "SurveyServiceImpl도 같이 봐 주세요", "evidence": "SurveyServiceImpl"}]"""

    private class Fake : LLMClient {
        @Volatile var constraints = "[]"
        @Volatile var constraintDelayMs = 0L
        @Volatile var refine = "정제된 요구사항"
        @Volatile var refineDelayMs = 0L
        val constraintCalls = AtomicInteger()
        val refineCalls = AtomicInteger()
        override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): OllamaChatResponse {
            val content = when {
                systemPrompt.contains("발화 번역 전문가") ->
                    """{"action": "ADD_CONSTRAINT", "constraintKind": "SCOPE_LIMIT", "constraintValue": "수정 범위 한정", "message": "m"}"""
                systemPrompt.contains("ConstraintKind") -> { constraintCalls.incrementAndGet(); Thread.sleep(constraintDelayMs); constraints }
                systemPrompt.contains("소프트웨어 요구사항 정제 전문가") -> { refineCalls.incrementAndGet(); Thread.sleep(refineDelayMs); refine }
                else -> "추가로 수정할 대상이 있으신가요?"
            }
            return OllamaChatResponse(model = "fake", createdAt = "", message = OllamaMessage("assistant", content), done = true)
        }
        override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
    }

    private fun node(path: String, className: String) = FileNode(
        path = path, packageName = "p", className = className, fileType = SpringFileType.SERVICE,
        layer = ArchitectureLayer.BUSINESS, koreanComments = listOf("테스트 $className")
    )

    private fun engine(llm: Fake): Stage0ClarificationEngine {
        val list = listOf(node("src/survey/SurveyServiceImpl.java", "SurveyServiceImpl"), node("src/batch/Job.java", "Job"))
        val g = ProjectGraph(
            generatedAt = "t", projectRoot = "/r", frameworkType = FrameworkType.SPRING_MVC_MYBATIS,
            files = list.associateBy { it.path }, relationships = emptyList(), resourceNodes = emptyList(), statistics = GraphStatistics()
        )
        return Stage0ClarificationEngine(Stage0GraphScanner(g, minSpecificityScore = 1.0, proposalBudget = 10), g, llmClient = llm)
    }

    // 발화 한 건이 쌓인 세션 상태(제외 표현이 없는 발화라 이 단계에서는 제약 추출을 하지 않는다)
    private fun prepared(llm: Fake, e: Stage0ClarificationEngine) =
        e.processUtterance(e.initSession(sr).state, "SurveyServiceImpl도 같이 봐 주세요").state

    // P1 추출 3초 + 정제문 1초: 전체가 약 3초(순차였다면 4초 이상)
    @Test
    fun p1_theCriticalPathIsTheLongerCallNotTheSum() {
        val llm = Fake(); llm.constraints = okConstraints
        val e = engine(llm); val state = prepared(llm, e)
        llm.constraintDelayMs = 3000; llm.refineDelayMs = 1000
        val t0 = System.nanoTime()
        val built = e.buildClarifyIntentChecked(state)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("추출 3초는 기다려야 한다: ${ms}ms", ms >= 2900)
        assertTrue("순차(4초 이상)가 아니라 약 3초여야 한다: ${ms}ms", ms < 3800)
        assertEquals("정제된 요구사항", built.intent.retentionAudit!!.rawRefinedRequirement)
        assertNull(built.constraintNotice)
        println("[B-63] 추출 3000ms + 정제문 1000ms -> buildClarifyIntent ${ms}ms")
    }

    // P2 추출만 실패: 실패 안내가 나오고 정제문은 반영된다
    @Test
    fun p2_extractionFailureShowsTheNoticeAndKeepsTheRefinedRequirement() {
        val llm = Fake(); val e = engine(llm); val state = prepared(llm, e)
        llm.constraints = "[Error] OpenAI 서버 통신 실패: HTTP 524"
        val built = e.buildClarifyIntentChecked(state)
        assertEquals(note, built.constraintNotice)
        assertEquals("정제된 요구사항", built.intent.retentionAudit!!.rawRefinedRequirement)
    }

    // P3 정제문만 실패: 원문으로 대체되는 기존 동작이 유지되고 제약 추출 안내는 없다. 둘 다 실패하면 안내가 나오고 원문이다.
    @Test
    fun p3_refineFailureFallsBackToTheOriginalAndBothFailuresKeepTheNotice() {
        val llm = Fake(); llm.constraints = okConstraints
        val e = engine(llm); val state = prepared(llm, e)
        llm.refine = "[Error] OpenAI 서버 통신 실패: HTTP 524"
        val onlyRefine = e.buildClarifyIntentChecked(state)
        assertNull(onlyRefine.constraintNotice)
        assertEquals(sr, onlyRefine.intent.retentionAudit!!.rawRefinedRequirement)

        val llm2 = Fake(); val e2 = engine(llm2); val state2 = prepared(llm2, e2)
        llm2.constraints = "[Error] x"; llm2.refine = "[Error] y"
        val both = e2.buildClarifyIntentChecked(state2)
        assertEquals(note, both.constraintNotice)
        assertEquals(sr, both.intent.retentionAudit!!.rawRefinedRequirement)
    }

    // P4 결과 ClarifyIntent는 지연과 무관하게 같고(경합이 결과를 바꾸지 않는다), 두 호출은 한 번씩만 불린다
    @Test
    fun p4_theResultIsTheSameWithAndWithoutDelays() {
        val fast = Fake(); fast.constraints = okConstraints
        val e1 = engine(fast); val s1 = prepared(fast, e1)
        val c0 = fast.constraintCalls.get(); val r0 = fast.refineCalls.get()
        val a = e1.buildClarifyIntentChecked(s1)

        val slow = Fake(); slow.constraints = okConstraints
        val e2 = engine(slow); val s2 = prepared(slow, e2)
        slow.constraintDelayMs = 700; slow.refineDelayMs = 300
        val sc0 = slow.constraintCalls.get(); val sr0 = slow.refineCalls.get()
        val b = e2.buildClarifyIntentChecked(s2)

        assertEquals(a.intent, b.intent)
        assertEquals(a.constraintNotice, b.constraintNotice)
        assertEquals(1, fast.constraintCalls.get() - c0); assertEquals(1, fast.refineCalls.get() - r0)
        assertEquals(1, slow.constraintCalls.get() - sc0); assertEquals(1, slow.refineCalls.get() - sr0)
        assertTrue(a.intent.constraints.any { it.evidence == "SurveyServiceImpl" })
    }
}
