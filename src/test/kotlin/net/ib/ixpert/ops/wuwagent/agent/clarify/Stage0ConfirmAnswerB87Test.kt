package net.ib.ixpert.ops.wuwagent.agent.clarify

import net.ib.ixpert.ops.wuwagent.agent.clarify.ConfirmB87Fixtures.ALIM_PATH
import net.ib.ixpert.ops.wuwagent.agent.clarify.ConfirmB87Fixtures.LABEL1
import net.ib.ixpert.ops.wuwagent.agent.clarify.ConfirmB87Fixtures.LABEL2
import net.ib.ixpert.ops.wuwagent.agent.clarify.ConfirmB87Fixtures.RecordingLlm
import net.ib.ixpert.ops.wuwagent.agent.clarify.Stage0ClarificationEngine.AnswerStepKind
import net.ib.ixpert.ops.wuwagent.agent.clarify.Stage0ClarificationEngine.UserInput
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.HintSource
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.LinkHint
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.Stage0State
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B-87 (가): 확정 때 입력창에 남은 글을 대화 경로(processUtterance)와 같은 규칙으로 처리한다.
 *  등가: A(processUtterance 후 글 없이 확정) = B(processTurn에 글을 실어 확정)의 상태, 정제문·제약 추출 입력, decisionNote
 *  e1 출처 답 / e2 질문 2개 열림(첫 항목만) / e3 lenient / e4 제외 제안 열림·닫힘 / e5 체크박스 우선
 *  e6 완료 표현만 있는 글 무시와 섞인 글의 기존 경로 / e7 deferTaskSummary=false
 *  r1~r5 되돌림(r5는 위임 실패 바깥 catch,ConfirmCheckpoint + ClarifyConfirmHandoff) / a1 별칭 / c1 isCompletionOnly
 */
class Stage0ConfirmAnswerB87Test {

    private val newAnswer = "응답자 이메일은 아직 수집하지 않아. 설문 응답 테이블에 이메일 컬럼을 새로 추가해서 저장하는 방향으로 해줘."
    private fun complete(text: String?, verdicts: Map<String, Verdict> = emptyMap()) =
        UserInput(verdictUpdates = verdicts, userStatement = text, isCompletionDeclared = true)

    private class Outcome(
        val state: Stage0State, val preKinds: List<String>, val refined: String, val note: String?,
        val refineInputs: List<String>, val extractInputs: List<String>
    )

    private fun finish(e: Stage0ClarificationEngine, llm: RecordingLlm, st: Stage0State, pre: List<String>): Outcome {
        llm.calls.clear()
        val deferred = e.buildClarifyIntentDeferred(st)
        deferred.complete(deferred.early.intent)
        return Outcome(st, pre, deferred.early.intent.refinedRequirement, deferred.decisionNote, llm.inputs("refine"), llm.inputs("extract"))
    }

    private fun runA(state: Stage0State, text: String, defer: Boolean = true): Outcome {
        val llm = RecordingLlm(); val e = ConfirmB87Fixtures.engine(llm, defer)
        val u = e.processUtterance(state, text)
        val t = e.processTurn(u.state, complete(null))
        return finish(e, llm, t.state, llm.calls.map { it.first })
    }

    private fun runB(state: Stage0State, text: String, defer: Boolean = true, verdicts: Map<String, Verdict> = emptyMap()): Outcome {
        val llm = RecordingLlm(); val e = ConfirmB87Fixtures.engine(llm, defer)
        val t = e.processTurn(state, complete(text, verdicts))
        return finish(e, llm, t.state, llm.calls.map { it.first })
    }

    private fun assertEquivalent(a: Outcome, b: Outcome) {
        assertEquals(a.state, b.state)
        assertEquals(a.state.fieldAnswers, b.state.fieldAnswers)
        assertEquals(a.state.userStatements, b.state.userStatements)
        assertEquals(a.state.dialogueHistory, b.state.dialogueHistory)
        assertEquals(a.refineInputs, b.refineInputs)
        assertEquals(a.refined, b.refined)
        assertEquals(a.extractInputs, b.extractInputs)
        assertEquals(a.note, b.note)
    }

    private fun setup(fields: Int = 1, proposal: Boolean = false): Stage0State {
        val llm = RecordingLlm(); val e = ConfirmB87Fixtures.engine(llm)
        var s = ConfirmB87Fixtures.withOpenFields(e.initSession("설문 발송 처리").state, fields)
        if (proposal) s = ConfirmB87Fixtures.withOpenProposal(e, llm, s)
        return s
    }

    // e1
    @Test
    fun e1_fieldAnswerIsEquivalentToSendingItFirst() {
        val s = setup(1)
        val a = runA(s, newAnswer); val b = runB(s, newAnswer)
        assertEquivalent(a, b)
        assertEquals(mapOf(LABEL1 to newAnswer), b.state.fieldAnswers)
        assertEquals(s.userStatements + "${LABEL1}의 출처: $newAnswer", b.state.userStatements)
        assertNotNull("새 데이터 저장 답과 쓰기 쿼리 없음 판정이면 decisionNote가 있다", b.note)
        // 질문과 사용자 글, 안내문이 대화 기록에 남는다
        assertTrue(b.state.dialogueHistory.any { it.role == "user" && it.content == newAnswer })
        assertEquals(0, b.preKinds.size)
        assertEquals(1, b.refineInputs.size)
        assertEquals(1, b.extractInputs.size)
    }

    // e2
    @Test
    fun e2_twoOpenQuestionsOnlyTheFirstIsAnswered() {
        val s = setup(2)
        val a = runA(s, "설문 응답 테이블에서 가져와"); val b = runB(s, "설문 응답 테이블에서 가져와")
        assertEquivalent(a, b)
        assertEquals(listOf(LABEL1), b.state.fieldAnswers.keys.toList())
        assertEquals(LABEL2, b.state.fieldFacts.first { it.question != null && it.label !in b.state.fieldAnswers }.label)
    }

    // e3
    @Test
    fun e3_lenientExclusionAnswerIsHandledLikeTheDialoguePath() {
        val s = setup(0, proposal = true)
        val text = "네 제외해주세요"
        val a = runA(s, text); val b = runB(s, text)
        assertEquivalent(a, b)
        val rejected = b.state.items.filter { it.verdict == Verdict.REJECTED }.mapNotNull { (it.hint as? LinkHint.ExistingRef)?.filePath }
        assertEquals(listOf(ALIM_PATH), rejected)
        assertEquals(s.userStatements + text, b.state.userStatements)
        // 대조: 완결 선언이 아닌 턴(기존 정확 일치 판정)은 같은 글로 제외를 확정하지 않는다
        val llm = RecordingLlm(); val e = ConfirmB87Fixtures.engine(llm)
        val strict = e.processTurn(s, UserInput(userStatement = text, isCompletionDeclared = false))
        assertTrue(strict.state.items.none { it.verdict == Verdict.REJECTED })
    }

    // e4: 제외 제안이 열려 있을 때(제안 답, 출처 질문도 함께 열림)와 닫혀 있을 때
    @Test
    fun e4_proposalOpenAndClosed() {
        val open = setup(1, proposal = true)
        val answerOpen = runA(open, "응"); val answerOpenB = runB(open, "응")
        assertEquivalent(answerOpen, answerOpenB)
        assertEquals("제외 제안 답이 먼저이고 출처 질문은 열린 채", emptyMap<String, String>(), answerOpenB.state.fieldAnswers)
        assertEquals(listOf(ALIM_PATH), answerOpenB.state.items.filter { it.verdict == Verdict.REJECTED }.mapNotNull { (it.hint as? LinkHint.ExistingRef)?.filePath })

        val closed = setup(1)
        val a = runA(closed, "응"); val b = runB(closed, "응")
        assertEquivalent(a, b)
        assertEquals("제안이 닫혀 있으면 같은 글은 출처 답이다", mapOf(LABEL1 to "응"), b.state.fieldAnswers)
        assertTrue(b.state.items.none { it.verdict == Verdict.REJECTED })
    }

    // e5
    @Test
    fun e5_checkboxWinsOverTheExclusionAnswerText() {
        val s = setup(0, proposal = true)
        val proposed = s.items.single { it.source == HintSource.PROPOSED_EXCLUSION }
        val b = runB(s, "응", verdicts = mapOf(proposed.id to Verdict.CONFIRMED))
        val item = b.state.items.single { (it.hint as? LinkHint.ExistingRef)?.filePath == ALIM_PATH }
        assertEquals("글은 제외 확정이지만 체크박스 선택이 우선한다", Verdict.CONFIRMED, item.verdict)
        assertEquals(s.userStatements + "응", b.state.userStatements)
    }

    // e6
    @Test
    fun e6_completionOnlyTextIsIgnoredAndMixedTextKeepsTheLegacyPath() {
        val s = setup(1)
        for (text in listOf("분석 시작해줘", "이제 분석해줘!", "완료")) {
            val llm = RecordingLlm(); val e = ConfirmB87Fixtures.engine(llm)
            val withText = e.processTurn(s, complete(text))
            val without = e.processTurn(s, complete(null))
            assertEquals(without, withText)
            assertEquals(s.userStatements, withText.state.userStatements)
            assertEquals("LLM 호출 0: ${llm.calls}", 0, llm.calls.size)
        }
        val llm = RecordingLlm(extractJson = ConfirmB87Fixtures.EXCLUDE_JSON); val e = ConfirmB87Fixtures.engine(llm)
        val mixed = "분석 시작해줘, 단 알림톡 배치는 빼줘"
        val r = e.processTurn(s, complete(mixed))
        assertEquals(s.userStatements + mixed, r.state.userStatements)
        assertEquals(emptyMap<String, String>(), r.state.fieldAnswers)
        assertEquals(1, llm.count("extract"))
        assertEquals(AnswerStepKind.NONE, e.applyAnswerStep(s, mixed).kind)
    }

    // e7
    @Test
    fun e7_withDeferTaskSummaryOffTheConfirmPathMakesNoSummaryCall() {
        val s = setup(1)
        val a = runA(s, newAnswer, defer = false); val b = runB(s, newAnswer, defer = false)
        assertEquals(0, b.preKinds.count { it == "summary" })
        assertTrue("대화 경로(A)는 요약을 부른다: ${a.preKinds}", a.preKinds.count { it == "summary" } >= 1)
        assertEquals(a.refined, b.refined)
        assertEquals(a.note, b.note)
        assertEquals(a.state, b.state)
    }

    // ── 되돌림 ──

    private fun newSession(state: Stage0State): Stage0Session {
        val e = ConfirmB87Fixtures.engine(RecordingLlm())
        return Stage0Session("설문 발송 처리", e, state, Stage0ClarificationEngine.Stage0TurnResult(state))
    }

    /** 라우터의 확정 순서를 IDE 없이 되풀이한다: 체크포인트 → processTurn → 세션 대입 → applied → Handoff. */
    private fun confirmFlow(
        session: Stage0Session, text: String, build: () -> Unit = {}, cont: () -> Unit = {},
        background: ((() -> Unit) -> Unit) = { it() }
    ): Exception? {
        val cp = ConfirmCheckpoint(session)
        val turn = session.engine.processTurn(session.currentState, complete(text))
        session.currentState = turn.state
        session.lastTurnResult = turn
        cp.applied()
        var failure: Exception? = null
        ClarifyConfirmHandoff.start(
            ClarifyConfirmHandoff.Steps<Unit>(
                invalidateSummary = {}, beginHandoff = {}, buildIntent = { build() }, removeSession = {},
                continueAfterClarify = { cont() }, onFailure = { failure = it }, endHandoff = {},
                restoreSession = {}, rollbackState = { cp.rollback() }
            ),
            runInBackground = background
        )
        return failure
    }

    private fun assertRollbackThenRetryEqualsOneSuccess(failAt: String) {
        val before = setup(1)
        val session = newSession(before)
        val once = session.engine.processTurn(before, complete(newAnswer)).state
        val failure = confirmFlow(
            session, newAnswer,
            build = { if (failAt == "build") throw RuntimeException("buildIntent 실패") },
            cont = { if (failAt == "continue") throw RuntimeException("continueAfterClarify 실패") }
        )
        assertNotNull(failure)
        assertSame("실패 뒤 상태가 호출 전과 같다($failAt)", before, session.currentState)
        assertEquals(before.userStatements, session.currentState.userStatements)
        // 같은 글로 다시 시도: 1회 성공한 결과와 같고 글이 중복되지 않는다
        assertNull(confirmFlow(session, newAnswer))
        assertEquals(once, session.currentState)
        assertEquals(1, session.currentState.userStatements.count { it.endsWith(newAnswer) })
    }

    @Test
    fun r1_buildIntentFailureRollsBackAndRetryEqualsOneSuccess() = assertRollbackThenRetryEqualsOneSuccess("build")

    @Test
    fun r2_continueAfterClarifyFailureRollsBackAndRetryEqualsOneSuccess() = assertRollbackThenRetryEqualsOneSuccess("continue")

    @Test
    fun r3_aUtteranceBetweenTheFailureKeepsItsChange() {
        val before = setup(1)
        val session = newSession(before)
        lateinit var interleaved: Stage0State
        val failure = confirmFlow(session, newAnswer, cont = {
            interleaved = session.currentState.copy(userStatements = session.currentState.userStatements + "끼어든 발화")
            session.currentState = interleaved
            throw RuntimeException("continueAfterClarify 실패")
        })
        assertNotNull(failure)
        assertSame("끼어든 변경은 유지한다(=== 비교가 달라 되돌리지 않음)", interleaved, session.currentState)
        assertTrue(session.currentState.userStatements.contains("끼어든 발화"))
        assertTrue(session.currentState.userStatements.contains("${LABEL1}의 출처: $newAnswer"))
    }

    // r5: 백그라운드 위임 자체가 실패하는 경로(ClarifyConfirmHandoff 바깥 catch). 작업이 한 번도 실행되지 않는다.
    @Test
    fun r5_backgroundDelegationFailureRollsBackThroughTheOuterCatch() {
        val refuse: (() -> Unit) -> Unit = { throw RuntimeException("백그라운드 위임 실패") }

        // (1) 실패 뒤 상태가 호출 전과 같고(=== 조건 경유), 같은 글로 다시 시도한 결과는 1회 성공한 결과와 같으며 글이 중복되지 않는다
        val before = setup(1)
        val session = newSession(before)
        val once = session.engine.processTurn(before, complete(newAnswer)).state
        var ran = false
        val failure = confirmFlow(session, newAnswer, build = { ran = true }, background = refuse)
        assertNotNull(failure)
        assertFalse("위임이 실패해 작업은 실행되지 않았다", ran)
        assertSame("위임 실패 뒤 상태가 호출 전과 같다", before, session.currentState)
        assertEquals(before.userStatements, session.currentState.userStatements)
        assertNull(confirmFlow(session, newAnswer))
        assertEquals(once, session.currentState)
        assertEquals(1, session.currentState.userStatements.count { it.endsWith(newAnswer) })

        // (2) 실패 사이에 상태가 바뀌었으면(끼어든 발화) 되돌리지 않는다(r3와 같은 형태)
        val before2 = setup(1)
        val session2 = newSession(before2)
        lateinit var interleaved: Stage0State
        val failure2 = confirmFlow(session2, newAnswer, background = {
            interleaved = session2.currentState.copy(userStatements = session2.currentState.userStatements + "끼어든 발화")
            session2.currentState = interleaved
            throw RuntimeException("백그라운드 위임 실패")
        })
        assertNotNull(failure2)
        assertSame("끼어든 변경은 유지한다(=== 비교가 달라 되돌리지 않음)", interleaved, session2.currentState)
        assertTrue(session2.currentState.userStatements.contains("끼어든 발화"))
        assertTrue(session2.currentState.userStatements.contains("${LABEL1}의 출처: $newAnswer"))
    }

    @Test
    fun r4_checkpointDoesNothingBeforeAppliedAndSuccessKeepsTheState() {
        val before = setup(1)
        val session = newSession(before)
        val cp = ConfirmCheckpoint(session)
        assertFalse(cp.rollback())
        assertNull(confirmFlow(session, newAnswer))
        assertFalse("성공하면 되돌리지 않는다", session.currentState === before)
        assertTrue(session.currentState.fieldAnswers.containsKey(LABEL1))
    }

    // a1: /clarify-confirm 별칭은 /analyze-confirm과 같은 한 분기(같은 코드)다
    @Test
    fun a1_clarifyConfirmAliasSharesTheAnalyzeConfirmBranch() {
        val router = File("src/main/kotlin/net/ib/ixpert/ops/wuwagent/action/WebviewActionRouter.kt").readText(Charsets.UTF_8)
        assertEquals(1, Regex("\"/clarify-confirm\", \"/analyze-confirm\" ->").findAll(router).count())
        assertEquals("별칭이 따로 처리되는 분기가 없다", 0, Regex("\"/clarify-confirm\" ->").findAll(router).count())
        assertTrue(router.contains("rollbackState = { checkpoint.rollback() }"))
        assertTrue(router.contains("checkpoint.applied()"))
    }

    // c1
    @Test
    fun c1_isCompletionOnly() {
        for (t in listOf("분석 시작해줘", "분석시작", "이제 분석해줘!", "완료", "이대로 분석 시작해 주세요.", "다음으로 진행해줘~"))
            assertTrue(t, ClarifyUtteranceTranslator.isCompletionOnly(t))
        for (t in listOf("분석 시작해줘, 단 Address는 빼줘", "Address 분석해줘", "응답자 이메일은 설문 테이블에서 가져와", "", "   ", "알림톡도 봐줘"))
            assertFalse(t, ClarifyUtteranceTranslator.isCompletionOnly(t))
    }
}
