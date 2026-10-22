package net.ib.ixpert.ops.wuwagent.agent.clarify

import net.ib.ixpert.ops.wuwagent.agent.clarify.model.HintSource
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.LinkHint
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.Verdict
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

/**
 * B-57: 제약 추출이 최종 실패해 기본 제약으로 바뀌면 화면 안내에 한 줄을 붙이고, 제외가 처리된 것처럼 보이는 안내는 내지 않는다.
 * 모델이 빈 목록을 준 경우와 파일명 직접 제외(8627fe5 규칙)는 실패 안내와 무관하다.
 */
class ConstraintExtractionFailureNoticeTest {

    private val note = "요청하신 조건을 해석하지 못했습니다. 제외하거나 한정할 파일이 있다면 파일명으로 다시 말씀해 주세요."

    /** 제약 추출 응답: 문자열이면 그 내용, null이면 예외를 던진다. */
    private class Fake(private val constraints: String?, private val translatorJson: String) : LLMClient {
        override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): OllamaChatResponse {
            val content = when {
                systemPrompt.contains("발화 번역 전문가") -> translatorJson
                systemPrompt.contains("ConstraintKind") -> constraints ?: throw RuntimeException("제약 추출 호출 예외")
                systemPrompt.contains("소프트웨어 요구사항 정제 전문가") -> "정제된 요구사항"
                else -> "추가로 수정할 대상이 있으신가요?"
            }
            return OllamaChatResponse(model = "fake", createdAt = "", message = OllamaMessage("assistant", content), done = true)
        }

        override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
    }

    private val jobPath = "src/batch/AlimtalkTemplateBatchJob.java"
    private val runnerPath = "src/batch/AlimtalkTemplateBatchRunner.java"
    private val surveyPath = "src/survey/SurveyServiceImpl.java"

    private fun node(path: String, className: String, comments: List<String>) = FileNode(
        path = path, packageName = "p", className = className, fileType = SpringFileType.SERVICE,
        layer = ArchitectureLayer.BUSINESS, koreanComments = comments
    )

    private fun graph(): ProjectGraph {
        val list = listOf(
            node(jobPath, "AlimtalkTemplateBatchJob", listOf("알림톡 템플릿 배치 작업")),
            node(runnerPath, "AlimtalkTemplateBatchRunner", listOf("알림톡 템플릿 배치 실행")),
            node(surveyPath, "SurveyServiceImpl", listOf("설문 서비스"))
        )
        return ProjectGraph(
            generatedAt = java.time.Instant.now().toString(), projectRoot = "/test/root", frameworkType = FrameworkType.SPRING_MVC_MYBATIS,
            files = list.associateBy { it.path }, relationships = emptyList(), resourceNodes = emptyList(), statistics = GraphStatistics()
        )
    }

    private val addConstraint = """{"action": "ADD_CONSTRAINT", "constraintKind": "SCOPE_LIMIT", "constraintValue": "수정 범위 한정", "message": "제외하도록 제약 조건을 등록했습니다."}"""
    private val exclusionEvidence = "알림톡 배치는 건드리지 말고"
    private val okConstraints = """[{"kind": "EXCLUDE_COMPONENT", "value": "제외 요청", "rawStatement": "$exclusionEvidence", "evidence": "$exclusionEvidence"}]"""
    private val exclusionStatement = "알림톡 배치는 건드리지 말고 SurveyServiceImpl만 수정할 거야"

    private val failures = listOf(
        "[Error] OpenAI 서버 통신 실패: Server returned HTTP response code: 524 for URL: http://x/v1/chat/completions" to "[Error] 응답",
        "" to "빈 응답",
        "이건 JSON이 아닙니다" to "해석 불가 응답",
        null to "호출 예외"
    )

    private fun engine(llm: Fake): Stage0ClarificationEngine {
        val g = graph()
        return Stage0ClarificationEngine(Stage0GraphScanner(g, minSpecificityScore = 1.0, proposalBudget = 10), g, llmClient = llm)
    }

    private fun proposals(state: net.ib.ixpert.ops.wuwagent.agent.clarify.model.Stage0State) =
        state.items.filter { it.source == HintSource.PROPOSED_EXCLUSION && it.verdict == Verdict.PENDING }
            .mapNotNull { (it.hint as? LinkHint.ExistingRef)?.filePath }.sorted()

    // F1 제약 추출 실패 + 제외 표현 있음(SR 원문과 발화 모두): 안내 줄이 있고, 제외된 파일이 없으며, 제외했다는 문구가 없다.
    @Test
    fun f1_failureWithExclusionWordsShowsTheNoticeAndExcludesNothing() {
        for ((response, label) in failures) {
            val srEngine = engine(Fake(response, addConstraint))
            val init = srEngine.initSession("설문 발송 채널 확장. 알림톡 배치는 건드리지 마세요.")
            val echo = init.echoBackMessage
            assertNotNull(label, echo)
            assertTrue("$label: $echo", echo!!.contains(note))
            assertFalse("$label: $echo", echo.contains("제외했습니다") || echo.contains("제외 목록에 넣었습니다"))
            assertEquals(label, emptyList<String>(), srEngine.exclusionView(init.state).excludedFiles)

            val e = engine(Fake(response, addConstraint))
            val t0 = e.initSession("설문 발송 채널 확장")
            val t1 = e.processUtterance(t0.state, exclusionStatement)
            val echo1 = t1.echoBackMessage
            assertNotNull(label, echo1)
            assertTrue("$label: $echo1", echo1!!.contains(note))
            assertFalse("$label: 번역기의 등록 안내가 남으면 안 된다: $echo1", echo1.contains("제외하도록") || echo1.contains("제외했습니다"))
            assertEquals(label, emptyList<String>(), e.exclusionView(t1.state).excludedFiles)
            assertEquals(label, emptyList<String>(), proposals(t1.state))
            assertFalse(label, (t1.openQuestion ?: "").contains("제외할까요?"))
        }
    }

    // F2 제약 추출 실패 + 제외 표현 없음(SR 원문): 안내 줄이 있다.
    @Test
    fun f2_failureWithoutExclusionWordsStillShowsTheNotice() {
        for ((response, label) in failures) {
            val init = engine(Fake(response, addConstraint)).initSession("설문 발송 채널 확장")
            assertTrue("$label: ${init.echoBackMessage}", init.echoBackMessage?.contains(note) == true)
        }
    }

    // F3 제약 추출 성공(모델이 빈 목록을 준 경우 포함): 안내 줄이 없고 기존 결과와 같다(제안 항목과 확인 질문).
    @Test
    fun f3_successShowsNoNoticeAndKeepsTheExistingResult() {
        val e = engine(Fake(okConstraints, addConstraint))
        val t0 = e.initSession("설문 발송 채널 확장")
        assertNull(t0.echoBackMessage)
        val t1 = e.processUtterance(t0.state, exclusionStatement)
        assertEquals(listOf(jobPath, runnerPath).sorted(), proposals(t1.state))
        assertFalse(t1.echoBackMessage ?: "", (t1.echoBackMessage ?: "").contains(note))
        assertTrue((t1.openQuestion ?: t1.echoBackMessage ?: "").contains("제외할까요?"))
        assertEquals(emptyList<String>(), e.exclusionView(t1.state).excludedFiles)
        // 모델이 "제약 없음"(빈 목록)을 준 것은 실패가 아니다
        val empty = engine(Fake("[]", addConstraint)).initSession("설문 발송 채널 확장. 알림톡 배치는 건드리지 마세요.")
        assertFalse(empty.echoBackMessage ?: "", (empty.echoBackMessage ?: "").contains(note))
    }

    // F4 파일명 직접 제외(8627fe5 규칙)는 제약 추출 결과와 상관없이 그대로 동작하고 실패 안내도 붙지 않는다.
    @Test
    fun f4_explicitFileNameExclusionIsIndependentOfTheConstraintExtraction() {
        val statement = "AlimtalkTemplateBatchJob.java, AlimtalkTemplateBatchRunner.java는 제외해 주세요"
        for (response in listOf("[Error] 응답 실패", okConstraints)) {
            val e = engine(Fake(response, addConstraint))
            val t1 = e.processUtterance(e.initSession("설문 발송 채널 확장").state, statement)
            assertEquals(response, listOf(jobPath, runnerPath).sorted(), e.exclusionView(t1.state).excludedFiles.sorted())
            val echo = t1.echoBackMessage ?: ""
            assertTrue(echo, echo.contains("제외했습니다") || echo.contains("제외 목록에 넣었습니다"))
            assertFalse(echo, echo.contains(note))
        }
    }
}
