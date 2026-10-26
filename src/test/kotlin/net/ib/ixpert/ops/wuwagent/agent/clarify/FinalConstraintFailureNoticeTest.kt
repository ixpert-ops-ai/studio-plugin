package net.ib.ixpert.ops.wuwagent.agent.clarify

import net.ib.ixpert.ops.wuwagent.agent.clarify.model.exclusionSummaryLines
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B-59: buildClarifyIntent의 최종 제약 추출이 실패해도 화면에 표시가 없던 문제. 실패하면 B-57과 같은 안내 한 줄을 분석 결과 앞에 보이고,
 * 앞 단계(initSession, processUtterance)에서 이미 안내했으면 되풀이하지 않는다. ClarifyIntent와 저장 형식은 바꾸지 않는다.
 */
class FinalConstraintFailureNoticeTest {

    // B-72: 첫 화면(initSession)의 초기 실패 안내는 발화 실패 안내와 문구를 나눴다
    private val initNote = "요구사항에서 조건을 자동으로 정리하지 못했습니다. 분석에는 영향이 없으며, 범위나 조건이 있으면 말씀해 주세요."
    private val note = "요청하신 조건을 해석하지 못했습니다. 제외하거나 한정할 파일이 있다면 파일명으로 다시 말씀해 주세요."

    /** 제약 추출 응답을 도중에 바꿀 수 있는 가짜 LLM: init과 발화 단계는 성공시키고 최종 추출만 실패시키는 상황을 만든다. */
    private class Fake : LLMClient {
        @Volatile var constraints: String = "[]"
        var constraintCalls = 0
        override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): OllamaChatResponse {
            val content = when {
                systemPrompt.contains("발화 번역 전문가") ->
                    """{"action": "ADD_CONSTRAINT", "constraintKind": "SCOPE_LIMIT", "constraintValue": "수정 범위 한정", "message": "제약 조건으로 기록했습니다."}"""
                systemPrompt.contains("ConstraintKind") -> { constraintCalls++; constraints }
                systemPrompt.contains("소프트웨어 요구사항 정제 전문가") -> "정제된 요구사항"
                else -> "추가로 수정할 대상이 있으신가요?"
            }
            return OllamaChatResponse(model = "fake", createdAt = "", message = OllamaMessage("assistant", content), done = true)
        }

        override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
    }

    private val failing = "[Error] OpenAI 서버 통신 실패: Server returned HTTP response code: 524 for URL: http://x/v1/chat/completions"
    private val surveyPath = "src/survey/SurveyServiceImpl.java"
    private val jobPath = "src/batch/AlimtalkTemplateBatchJob.java"

    private fun node(path: String, className: String, comments: List<String>) = FileNode(
        path = path, packageName = "p", className = className, fileType = SpringFileType.SERVICE,
        layer = ArchitectureLayer.BUSINESS, koreanComments = comments
    )

    private fun engine(llm: Fake): Stage0ClarificationEngine {
        val list = listOf(node(jobPath, "AlimtalkTemplateBatchJob", listOf("알림톡 템플릿 배치 작업")), node(surveyPath, "SurveyServiceImpl", listOf("설문 서비스")))
        val g = ProjectGraph(
            generatedAt = java.time.Instant.now().toString(), projectRoot = "/test/root", frameworkType = FrameworkType.SPRING_MVC_MYBATIS,
            files = list.associateBy { it.path }, relationships = emptyList(), resourceNodes = emptyList(), statistics = GraphStatistics()
        )
        return Stage0ClarificationEngine(Stage0GraphScanner(g, minSpecificityScore = 1.0, proposalBudget = 10), g, llmClient = llm)
    }

    // 발화 한 건을 처리한 세션(제외 표현 없는 발화라 이 단계에서는 제약 추출을 하지 않는다)
    private fun sessionWithStatement(llm: Fake, e: Stage0ClarificationEngine) =
        e.processUtterance(e.initSession("설문 발송 채널 확장").state, "SurveyServiceImpl도 같이 봐 주세요").state

    // G1 최종 추출 실패: 안내 한 줄, 제외된 파일 변화 없음, 제외·제약이 반영된 것처럼 보이는 문구 없음
    @Test
    fun g1_finalFailureProducesOneNoticeAndExcludesNothing() {
        val llm = Fake(); val e = engine(llm)
        val state = sessionWithStatement(llm, e)
        llm.constraints = failing
        val built = e.buildClarifyIntentChecked(state)
        assertEquals(note, built.constraintNotice)
        assertEquals(emptyList<String>(), built.intent.excludedFiles)
        assertFalse(built.constraintNotice!!.contains("제외했습니다"))
        assertTrue("제외 확정을 말하는 줄이 없어야 한다: ${built.intent.exclusionSummaryLines()}", built.intent.exclusionSummaryLines().none { it.contains("제외 확정") || it.contains("제외했습니다") })
        // 기존 시그니처는 같은 ClarifyIntent를 돌려준다
        assertEquals(built.intent.excludedFiles, e.buildClarifyIntent(state).excludedFiles)
    }

    // G2 앞 단계(initSession, processUtterance)에서 이미 안내했으면 최종 추출 안내는 되풀이하지 않는다
    @Test
    fun g2_noticeIsNotRepeatedWhenAnEarlierStepAlreadyShowedIt() {
        val llm = Fake(); llm.constraints = failing
        val e = engine(llm)
        val t0 = e.initSession("설문 발송 채널 확장")
        assertTrue(t0.echoBackMessage ?: "", t0.echoBackMessage?.contains(initNote) == true)
        val t1 = e.processUtterance(t0.state, "SurveyServiceImpl도 같이 봐 주세요")
        assertNull("최종 추출이 같은 말을 되풀이하지 않는다", e.buildClarifyIntentChecked(t1.state).constraintNotice)
        // processUtterance 단계에서 안내한 경우(제외 표현이 있는 발화)도 마찬가지
        val llm2 = Fake(); val e2 = engine(llm2)
        val u0 = e2.initSession("설문 발송 채널 확장")
        llm2.constraints = failing
        val u1 = e2.processUtterance(u0.state, "알림톡 배치는 건드리지 말고 SurveyServiceImpl만 수정할 거야")
        assertTrue(u1.echoBackMessage ?: "", u1.echoBackMessage?.contains(note) == true)
        assertNull(e2.buildClarifyIntentChecked(u1.state).constraintNotice)
    }

    // G3 추출 성공: 안내 없음, 결과는 기존과 같다(모델이 빈 목록을 준 경우도 실패가 아니다)
    @Test
    fun g3_successProducesNoNoticeAndTheSameIntent() {
        val llm = Fake(); val e = engine(llm)
        val state = sessionWithStatement(llm, e)
        val built = e.buildClarifyIntentChecked(state)
        assertNull(built.constraintNotice)
        assertEquals(e.buildClarifyIntent(state), built.intent)
        llm.constraints = "[]"
        assertNull(e.buildClarifyIntentChecked(state).constraintNotice)
    }

    // G4 라우터: 두 호출 지점(/clarify-confirm, /clarify-utterance 완료)이 안내를 분석 시작 직후 첫 청크로 보내고, ClarifyIntent는 바뀌지 않는다
    @Test
    fun g4_routerPassesTheNoticeIntoTheAnalysisStream() {
        val router = File("src/main/kotlin/net/ib/ixpert/ops/wuwagent/action/WebviewActionRouter.kt").readText()
        assertEquals(2, Regex("""buildClarifyIntentChecked\(turnResult\.state\)""").findAll(router).count())
        // 두 호출 지점은 같은 도우미(continueAfterClarify, 정제문 확인 → 저장 → 분석)로 이어지고, 안내는 그 안의 분석 호출에 한 번 넘어간다
        assertEquals(2, Regex("""continueAfterClarify\(project, bridge, intentBuild, messageId\)""").findAll(router).count())
        assertEquals(1, Regex("""clarifyIntent, intentBuild\.constraintNotice\)""").findAll(router).count())
        val analyze = router.substringAfter("private fun executeAnalyzePipeline(")
        val start = analyze.indexOf("""bridge.sendMessage("analyze_start"""")
        val chunk = analyze.indexOf("constraintNotice?.let { bridge.sendMessageChunk(messageId")
        val pipeline = analyze.indexOf("pipeline.analyze(")
        assertTrue(start in 0 until chunk && chunk < pipeline)
        assertNotNull(Regex("""data class ClarifyIntentBuild\(val intent: ClarifyIntent, val constraintNotice: String\?\)""").find(
            File("src/main/kotlin/net/ib/ixpert/ops/wuwagent/agent/clarify/Stage0ClarificationEngine.kt").readText()
        ))
    }
}
