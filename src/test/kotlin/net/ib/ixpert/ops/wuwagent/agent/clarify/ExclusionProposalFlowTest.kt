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
 * B-51 안 A: UI 경로(processUtterance, initSession)의 "X는 건드리지 마" 처리.
 * 제외 단어가 있을 때만 제약 추출을 하고, 대상이 1~10개면 확인 질문(1회), 10개 초과나 0개면 안내, 파일명을 직접 적으면 바로 제외한다.
 * 확정된 파일만 excludedFiles에 들어가고, 답 없이 확정하면 미확정 제외로 표시된다.
 */
class ExclusionProposalFlowTest {

    private class Fake(
        private val translatorJson: String,
        private val constraintsJson: String
    ) : LLMClient {
        var translatorCalls = 0
        var constraintCalls = 0

        override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): OllamaChatResponse {
            val content = when {
                systemPrompt.contains("발화 번역 전문가") -> { translatorCalls++; translatorJson }
                systemPrompt.contains("ConstraintKind") -> { constraintCalls++; constraintsJson }
                systemPrompt.contains("소프트웨어 요구사항 정제 전문가") -> "정제된 요구사항"
                else -> "추가로 수정할 대상이 있으신가요?"
            }
            return OllamaChatResponse(model = "fake", createdAt = "", message = OllamaMessage("assistant", content), done = true)
        }

        override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
    }

    private fun node(path: String, className: String, comments: List<String> = emptyList()) = FileNode(
        path = path, packageName = "p", className = className,
        fileType = SpringFileType.SERVICE, layer = ArchitectureLayer.BUSINESS, koreanComments = comments
    )

    private val jobPath = "src/batch/AlimtalkTemplateBatchJob.java"
    private val runnerPath = "src/batch/AlimtalkTemplateBatchRunner.java"
    private val repoPath = "src/batch/AlimtalkTemplateBatchRepository.java"
    private val surveyPath = "src/survey/SurveyServiceImpl.java"

    private fun graph(): ProjectGraph {
        val list = mutableListOf(
            node(jobPath, "AlimtalkTemplateBatchJob", listOf("알림톡 템플릿 배치 작업")),
            node(runnerPath, "AlimtalkTemplateBatchRunner", listOf("알림톡 템플릿 배치 실행")),
            node(repoPath, "AlimtalkTemplateBatchRepository", listOf("템플릿 저장소")),   // 알림톡 주석이 없어 제외 후보에서 빠진다(실측 D5와 같다)
            node(surveyPath, "SurveyServiceImpl", listOf("설문 서비스"))
        )
        (1..12).forEach { list.add(node("src/admin/AdminMgmt${it}Biz.java", "AdminMgmt${it}Biz", listOf("관리 화면 $it"))) }
        return ProjectGraph(
            generatedAt = java.time.Instant.now().toString(), projectRoot = "/test/root", frameworkType = FrameworkType.SPRING_MVC_MYBATIS,
            files = list.associateBy { it.path }, relationships = emptyList(), resourceNodes = emptyList(), statistics = GraphStatistics()
        )
    }

    private val addConstraint = """{"action": "ADD_CONSTRAINT", "constraintKind": "SCOPE_LIMIT", "constraintValue": "수정 범위 한정", "message": "제외하도록 제약 조건을 등록했습니다."}"""

    private fun constraints(evidence: String, kind: String = "EXCLUDE_COMPONENT") =
        """[{"kind": "$kind", "value": "제외 요청", "rawStatement": "$evidence", "evidence": "$evidence"}]"""

    private fun engine(llm: Fake): Stage0ClarificationEngine {
        val g = graph()
        return Stage0ClarificationEngine(Stage0GraphScanner(g, minSpecificityScore = 1.0, proposalBudget = 10), g, llmClient = llm)
    }

    private val alimtalkEvidence = "알림톡 배치는 건드리지 말고"
    private val alimtalkStatement = "알림톡 배치는 건드리지 말고 SurveyServiceImpl만 수정할 거야"

    private fun proposals(state: net.ib.ixpert.ops.wuwagent.agent.clarify.model.Stage0State) =
        state.items.filter { it.source == HintSource.PROPOSED_EXCLUSION && it.verdict == Verdict.PENDING }
            .mapNotNull { (it.hint as? LinkHint.ExistingRef)?.filePath }.sorted()

    // F1 제외 제안: 대상이 1~10개면 항목과 확인 질문을 만들고, 확정 전에는 excludedFiles가 비어 있다. 안내문은 제외했다고 말하지 않는다.
    @Test
    fun f1_proposalIsCreatedForAResolvableTarget() {
        val llm = Fake(addConstraint, constraints(alimtalkEvidence))
        val e = engine(llm)
        val t0 = e.initSession("설문 발송 채널 확장")
        val base = llm.constraintCalls   // init의 제약 추출 1회는 기존 호출(resolveUserUtteredIdentifiers)이다
        val t1 = e.processUtterance(t0.state, alimtalkStatement)
        assertEquals(listOf(jobPath, runnerPath).sorted(), proposals(t1.state))
        assertEquals(emptyList<String>(), e.exclusionView(t1.state).excludedFiles)
        assertEquals(listOf(jobPath, runnerPath).sorted(), e.exclusionView(t1.state).pendingExclusions.sorted())
        val q = t1.openQuestion!!
        assertTrue(q, q.contains("'$alimtalkEvidence' 관련 다음 파일을 분석 대상에서 제외할까요?"))
        assertTrue(q, q.contains("AlimtalkTemplateBatchJob.java") && q.contains("AlimtalkTemplateBatchRunner.java"))
        assertFalse("Repository는 후보 탐색에 없다", q.contains("AlimtalkTemplateBatchRepository"))
        val echo = t1.echoBackMessage!!
        assertTrue(echo, echo.startsWith("제약 조건으로 기록했습니다"))
        assertFalse("모델이 만든 '제외하도록' 문구는 쓰지 않는다", echo.contains("제외하도록"))
        assertEquals("제외 단어가 있는 발화는 제약 추출을 1회 더 한다", base + 1, llm.constraintCalls)
    }

    // F2 긍정 답: "네, 제외해 주세요"(정확 일치가 아님)도 확정이 되고 번역기를 거치지 않는다. 확정된 파일만 excludedFiles와 intent에 들어간다.
    @Test
    fun f2_affirmativeAnswerConfirmsTheExclusion() {
        val llm = Fake(addConstraint, constraints(alimtalkEvidence))
        val e = engine(llm)
        val t1 = e.processUtterance(e.initSession("설문 발송 채널 확장").state, alimtalkStatement)
        val translatorBefore = llm.translatorCalls
        val constraintsBefore = llm.constraintCalls
        val t2 = e.processUtterance(t1.state, "네, 제외해 주세요")
        assertEquals(listOf(jobPath, runnerPath).sorted(), e.exclusionView(t2.state).excludedFiles.sorted())
        assertEquals(emptyList<String>(), e.exclusionView(t2.state).pendingExclusions)
        assertTrue(t2.echoBackMessage!!.contains("제외(REJECTED) 확정"))
        assertEquals("답은 번역기를 거치지 않는다", translatorBefore, llm.translatorCalls)
        assertEquals("답에서는 제약 추출을 하지 않는다", constraintsBefore, llm.constraintCalls)
        assertNull("확정 뒤에는 같은 요청을 다시 묻지 않는다", t2.openQuestion?.takeIf { it.contains("제외할까요?") })
        val intent = e.buildClarifyIntent(t2.state)
        assertEquals(listOf(jobPath, runnerPath).sorted(), intent.excludedFiles.sorted())
        assertEquals(emptyList<Any>(), intent.unconfirmedExclusions)
        assertEquals("제외하면 안 되는 SurveyServiceImpl은 그대로", false, intent.excludedFiles.contains(surveyPath))
    }

    // F3 부정 답: 제안을 취소하고 아무 파일도 제외하지 않는다.
    @Test
    fun f3_negativeAnswerCancelsTheProposal() {
        val e = engine(Fake(addConstraint, constraints(alimtalkEvidence)))
        val t1 = e.processUtterance(e.initSession("설문 발송 채널 확장").state, alimtalkStatement)
        for (no in listOf("아니요", "취소")) {
            val t2 = e.processUtterance(t1.state, no)
            assertEquals(no, emptyList<String>(), proposals(t2.state))
            assertEquals(no, emptyList<String>(), e.exclusionView(t2.state).excludedFiles)
            assertTrue(no, t2.echoBackMessage!!.contains("취소"))
        }
    }

    // F4 일부만 지키는 답: 확정하지 않고, 다시 묻지도 않고, 미확정으로 남긴다.
    @Test
    fun f4_mixedAnswerStaysUnconfirmedWithoutAskingAgain() {
        val llm = Fake(addConstraint, constraints(alimtalkEvidence))
        val e = engine(llm)
        val t1 = e.processUtterance(e.initSession("설문 발송 채널 확장").state, alimtalkStatement)
        val t2 = e.processUtterance(t1.state, "네 근데 Runner는 빼지 마세요")
        assertEquals(emptyList<String>(), e.exclusionView(t2.state).excludedFiles)
        assertEquals("제안은 그대로 미확정", listOf(jobPath, runnerPath).sorted(), proposals(t2.state))
        assertTrue(t2.echoBackMessage!!.contains("확정하지 않았습니다"))
        assertFalse("다시 묻지 않는다", (t2.openQuestion ?: "").contains("제외할까요?"))
        // 같은 제외 요청을 한 번 더 해도 다시 묻지 않는다(대화 기록에 같은 증거 구문으로 안내한 적이 있다)
        val t3 = e.processUtterance(t2.state, alimtalkStatement)
        assertFalse("같은 요청으로 다시 묻지 않는다", (t3.openQuestion ?: "").contains("제외할까요?"))
        // 확정하면 미확정 제외로 표시된다(제외하지 않는다)
        val intent = e.buildClarifyIntent(t3.state)
        assertEquals(emptyList<String>(), intent.excludedFiles)
        val u = intent.unconfirmedExclusions.single()
        assertEquals(alimtalkEvidence, u.evidence)
        assertEquals(listOf(jobPath, runnerPath).sorted(), u.candidateFiles.sorted())
    }

    // F5 답 없이 확정: 제외하지 않고 미확정 제외로 표시한다. 제안 항목은 Stage 3에 미확정 식별자로 넘어가지 않는다.
    @Test
    fun f5_unansweredProposalBecomesUnconfirmedExclusion() {
        val e = engine(Fake(addConstraint, constraints(alimtalkEvidence)))
        val t1 = e.processUtterance(e.initSession("설문 발송 채널 확장").state, alimtalkStatement)
        val intent = e.buildClarifyIntent(t1.state)
        assertEquals(emptyList<String>(), intent.excludedFiles)
        assertEquals(1, intent.unconfirmedExclusions.size)
        assertEquals(alimtalkStatement, intent.unconfirmedExclusions.single().statement)
        assertEquals(emptyList<Any>(), intent.unresolvedItems)
    }

    // F6 10개 초과: 항목을 만들지 않고 "N개라 특정하지 못했습니다. 파일명을 알려 주시면 제외합니다" 안내만 한다.
    @Test
    fun f6_tooManyCandidatesAsksForAFileNameAndCreatesNoItems() {
        val e = engine(Fake(addConstraint, constraints("관리 화면은 그대로 두세요")))
        val t1 = e.processUtterance(e.initSession("설문 발송 채널 확장").state, "관리 화면은 그대로 두세요")
        assertEquals(emptyList<String>(), proposals(t1.state))
        assertEquals(
            "'관리 화면은 그대로 두세요' 관련 파일이 12개라 특정하지 못했습니다. 파일명을 알려 주시면 제외합니다.",
            t1.openQuestion
        )
        assertEquals(emptyList<String>(), e.exclusionView(t1.state).excludedFiles)
    }

    // F7 0개: "대상을 찾지 못했습니다"를 알린다(조용히 버리지 않는다). 증거 구문에 명사가 없으면 발화 전체로 한 번 더 찾는다.
    @Test
    fun f7_noTargetIsReportedNotDropped() {
        val e = engine(Fake(addConstraint, constraints("건드리지 마세요")))
        val t1 = e.processUtterance(e.initSession("설문 발송 채널 확장").state, "저쪽은 건드리지 마세요")
        assertEquals("'건드리지 마세요'에 해당하는 대상을 찾지 못했습니다. 제외할 파일명을 알려 주시면 제외합니다.", t1.openQuestion)
        assertEquals(emptyList<String>(), proposals(t1.state))
    }

    @Test
    fun f7b_evidenceWithoutANounFallsBackToTheWholeUtterance() {
        // 증거 구문 "건드리지 마세요"만으로는 0개지만 발화 전체("알림톡 배치는 건드리지 마세요")로 찾는다.
        // "마세요"가 불용어가 아니라 미매칭 단어로 남아(저신뢰, 부분 매칭) 알림톡·배치 후보의 합집합 3개가 나온다.
        val e = engine(Fake(addConstraint, constraints("건드리지 마세요")))
        val t1 = e.processUtterance(e.initSession("설문 발송 채널 확장").state, "알림톡 배치는 건드리지 마세요")
        assertEquals(listOf(jobPath, runnerPath, repoPath).sorted(), proposals(t1.state))
        val q = t1.openQuestion!!
        assertTrue(q, q.startsWith("'건드리지 마세요'와 정확히 일치하지는 않지만 관련될 수 있는 파일입니다."))
        assertTrue(q, q.contains("(AlimtalkTemplateBatchJob.java, AlimtalkTemplateBatchRunner.java, AlimtalkTemplateBatchRepository.java)"))
        // 저신뢰 제안은 "네"로 한꺼번에 제외되지 않는다.
        val t2 = e.processUtterance(t1.state, "네")
        assertEquals(emptyList<String>(), e.exclusionView(t2.state).excludedFiles)
        assertTrue(t2.echoBackMessage!!.contains("일괄 제외되지 않았습니다"))
    }

    // F8 파일명을 직접 적으면(안내에 대한 답) 바로 제외한다. 확정된 파일만 excludedFiles에 들어간다.
    @Test
    fun f8_explicitFileNameIsExcludedImmediately() {
        val evidence = "AlimtalkTemplateBatchRepository.java는 제외해 주세요"
        val e = engine(Fake(addConstraint, constraints(evidence)))
        val t1 = e.processUtterance(e.initSession("설문 발송 채널 확장").state, evidence)
        assertEquals(listOf(repoPath), e.exclusionView(t1.state).excludedFiles)
        assertTrue(t1.echoBackMessage!!.contains("다음 파일을 분석 대상에서 제외했습니다: AlimtalkTemplateBatchRepository.java"))
        assertFalse("직접 적었으므로 다시 묻지 않는다", (t1.openQuestion ?: "").contains("제외할까요?"))
        assertEquals(listOf(repoPath), e.buildClarifyIntent(t1.state).excludedFiles)
    }

    // F9 제외 단어가 없는 발화에서는 제약 추출(LLM)을 부르지 않는다.
    @Test
    fun f9_noExclusionWordNoConstraintExtraction() {
        val llm = Fake(addConstraint, constraints(alimtalkEvidence))
        val e = engine(llm)
        val t0 = e.initSession("설문 발송 채널 확장")
        val base = llm.constraintCalls
        e.processUtterance(t0.state, "SMS 발송도 같이 고려해 줘")
        e.processUtterance(t0.state, "목록 조회하는 쪽이에요.")
        assertEquals("제외 단어가 없는 발화는 clarify 호출 수가 늘지 않는다", base, llm.constraintCalls)
    }

    // F10 SR 원문에 제외 문장이 있으면 init에서 같은 흐름을 탄다(제약 추출 1회). 답 없이 확정하면 미확정 제외로 표시된다.
    @Test
    fun f10_exclusionSentenceInTheOriginalRequirement() {
        val llm = Fake(addConstraint, constraints(alimtalkEvidence))
        val e = engine(llm)
        val sr = "알림톡 배치는 건드리지 말고 설문 발송 채널을 확장해 주세요"
        val t0 = e.initSession(sr)
        assertTrue(t0.openQuestion!!.contains("'$alimtalkEvidence' 관련 다음 파일을 분석 대상에서 제외할까요?"))
        assertEquals(listOf(jobPath, runnerPath).sorted(), proposals(t0.state))
        assertTrue("안내는 대화 기록에 남는다", t0.state.dialogueHistory.single().content.contains("'$alimtalkEvidence'"))
        val callsAfterInit = llm.constraintCalls
        val t1 = e.processUtterance(t0.state, "네")
        assertEquals(listOf(jobPath, runnerPath).sorted(), e.exclusionView(t1.state).excludedFiles.sorted())
        assertEquals(callsAfterInit, llm.constraintCalls)

        // 답 없이 확정: 제외하지 않고, SR 원문 안의 요청이라는 것이 미확정 제외 표시에 인용된다.
        val e2 = engine(Fake(addConstraint, constraints(alimtalkEvidence)))
        val intent = e2.buildClarifyIntent(e2.initSession(sr).state)
        assertEquals(emptyList<String>(), intent.excludedFiles)
        assertEquals(sr, intent.unconfirmedExclusions.single().statement)
    }

    // F11 번역기가 EXCLUDE로 낸 기존 단일 제외는 그대로 동작하고, 제약 추출을 더 부르지 않는다.
    @Test
    fun f11_singleExcludeByTheTranslatorIsUnchanged() {
        val llm0 = Fake(addConstraint, constraints(alimtalkEvidence))
        val e0 = engine(llm0)
        val target = e0.initSession("SurveyServiceImpl 수정").state.items
            .first { (it.hint as? LinkHint.ExistingRef)?.filePath == surveyPath }
        val excludeJson = """{"action": "EXCLUDE", "targetId": "${target.id}", "message": "'$surveyPath' 파일을 분석 대상에서 제외합니다."}"""
        val llm = Fake(excludeJson, constraints(alimtalkEvidence))
        val e = engine(llm)
        val t0 = e.initSession("SurveyServiceImpl 수정")
        val callsAfterInit = llm.constraintCalls
        val t1 = e.processUtterance(t0.state, "SurveyServiceImpl은 빼줘")
        assertEquals(listOf(surveyPath), e.exclusionView(t1.state).excludedFiles)
        assertEquals("EXCLUDE에서는 제외 제안용 제약 추출을 더 하지 않는다", callsAfterInit, llm.constraintCalls)
        assertNotNull(t1.echoBackMessage)
    }

    // F12 답 판정: "긍정어 포함 + 부정어 없음"이 확정, 부정어만 있으면 취소, 섞이면 미확정. 짧은 긍정어는 단어 전체 일치만 인정한다.
    @Test
    fun f12_answerJudgement() {
        listOf("네, 제외해 주세요", "제외해줘요", "응", "네", "확인", "제외", "yes").forEach { assertEquals(it, ExclusionAnswer.AFFIRM, ExclusionWords.judgeAnswer(it)) }
        listOf("아니요", "취소", "아니", "no", "유지").forEach { assertEquals(it, ExclusionAnswer.NEGATE, ExclusionWords.judgeAnswer(it)) }
        listOf("네 근데 Runner는 빼지 마세요", "네, 그런데 제외하지 마세요").forEach { assertEquals(it, ExclusionAnswer.MIXED, ExclusionWords.judgeAnswer(it)) }
        listOf("네트워크 쪽은 어때요", "목록 조회하는 쪽이에요.", "SMS 발송도 고려해 줘", "").forEach { assertEquals(it, ExclusionAnswer.OTHER, ExclusionWords.judgeAnswer(it)) }
        // 긴 문장(새 제외 요청)은 답으로 읽지 않는다. 읽으면 "건드리지 말고"가 부정 답으로 오인된다.
        assertEquals(ExclusionAnswer.OTHER, ExclusionWords.judgeAnswer(alimtalkStatement))
    }
}
