package net.ib.ixpert.ops.wuwagent.agent.clarify

import net.ib.ixpert.ops.wuwagent.agent.clarify.ConfirmB87Fixtures.ALIM_PATH
import net.ib.ixpert.ops.wuwagent.agent.clarify.ConfirmB87Fixtures.LABEL1
import net.ib.ixpert.ops.wuwagent.agent.clarify.Stage0ClarificationEngine.UserInput
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.HintSource
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.LinkHint
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-87 (가) 고정 시험(구현 전 커밋): 구현 뒤에도 바뀌면 안 되는 `processTurn` 동작을 현재 코드로 고정한다.
 *  f1 빈 입력(null, "", 공백)은 서로 같은 결과이고 LLM 호출이 없다(출처 질문·제외 제안이 열려 있어도)
 *  f2 일반 글(열린 질문 없음)은 원문 그대로 userStatements에 쌓이고 제약 추출 1회
 *  f3 열린 출처 질문이 있어도 제외 표현이 있는 글은 기존 경로(원문 그대로, fieldAnswers 불변)
 *  f4 완결 선언이 아닌 processTurn은 열린 출처 질문이 있어도 글을 출처 답으로 보지 않는다
 *  f5 완결 선언이 아닌 processTurn의 제외 제안 답("응")은 기존대로 제외 확정
 */
class Stage0ConfirmFixedB87Test {

    private fun complete(text: String?) = UserInput(userStatement = text, isCompletionDeclared = true)

    // f1
    @Test
    fun f1_emptyInputsGiveTheSameResultAndNoLlmCall() {
        val llm = ConfirmB87Fixtures.RecordingLlm()
        val engine = ConfirmB87Fixtures.engine(llm)
        val base = engine.initSession("설문 발송 처리").state
        val open = ConfirmB87Fixtures.withOpenFields(base, 2)
        val proposal = ConfirmB87Fixtures.withOpenProposal(engine, llm, open)
        for (state in listOf(base, open, proposal)) {
            llm.calls.clear()
            val results = listOf(null, "", "   ", "\n\t").map { engine.processTurn(state, complete(it)) }
            for (r in results) {
                assertEquals(results[0], r)
                assertEquals(state.userStatements, r.state.userStatements)
                assertEquals(state.fieldAnswers, r.state.fieldAnswers)
                assertEquals(state.dialogueHistory, r.state.dialogueHistory)
                assertTrue(r.isReadyForStage1)
            }
            assertEquals("빈 입력은 LLM을 부르지 않는다: ${llm.calls}", 0, llm.calls.size)
        }
    }

    // f2
    @Test
    fun f2_generalTextIsAppendedAsIsAndExtractedOnce() {
        val llm = ConfirmB87Fixtures.RecordingLlm()
        val engine = ConfirmB87Fixtures.engine(llm)
        val base = engine.initSession("설문 발송 처리").state
        llm.calls.clear()
        val text = "AlimtalkBatchJob 로직만 수정해줘"
        val r = engine.processTurn(base, complete("  $text  "))
        assertEquals(base.userStatements + text, r.state.userStatements)
        assertEquals(base.fieldAnswers, r.state.fieldAnswers)
        assertEquals(base.dialogueHistory, r.state.dialogueHistory)
        assertEquals(1, llm.count("extract"))
        assertEquals(0, llm.count("translate"))
    }

    // f3
    @Test
    fun f3_exclusionTextKeepsTheLegacyPathEvenWithAnOpenFieldQuestion() {
        val llm = ConfirmB87Fixtures.RecordingLlm(extractJson = ConfirmB87Fixtures.EXCLUDE_JSON)
        val engine = ConfirmB87Fixtures.engine(llm)
        val open = ConfirmB87Fixtures.withOpenFields(engine.initSession("설문 발송 처리").state, 2)
        llm.calls.clear()
        val text = "알림톡 배치는 건드리지 마"
        val r = engine.processTurn(open, complete(text))
        assertEquals(open.userStatements + text, r.state.userStatements)
        assertEquals(emptyMap<String, String>(), r.state.fieldAnswers)
        assertEquals(1, llm.count("extract"))
        assertEquals(open.dialogueHistory, r.state.dialogueHistory)
    }

    // f4
    @Test
    fun f4_nonCompletionTurnDoesNotTreatTheTextAsAFieldAnswer() {
        val llm = ConfirmB87Fixtures.RecordingLlm()
        val engine = ConfirmB87Fixtures.engine(llm)
        val open = ConfirmB87Fixtures.withOpenFields(engine.initSession("설문 발송 처리").state, 2)
        llm.calls.clear()
        val text = "$LABEL1 은(는) 설문 응답 테이블에서 가져와"
        val r = engine.processTurn(open, UserInput(userStatement = text, isCompletionDeclared = false))
        assertFalse(r.isReadyForStage1)
        assertEquals(open.userStatements + text, r.state.userStatements)
        assertEquals(emptyMap<String, String>(), r.state.fieldAnswers)
        assertEquals(open.dialogueHistory, r.state.dialogueHistory)
        assertEquals(1, llm.count("extract"))
    }

    // f5
    @Test
    fun f5_nonCompletionTurnConfirmsAnOpenExclusionProposalWithExactAnswer() {
        val llm = ConfirmB87Fixtures.RecordingLlm()
        val engine = ConfirmB87Fixtures.engine(llm)
        val proposal = ConfirmB87Fixtures.withOpenProposal(engine, llm, engine.initSession("설문 발송 처리").state)
        val proposed = proposal.items.filter { it.source == HintSource.PROPOSED_EXCLUSION }
        assertEquals(1, proposed.size)
        assertEquals(ALIM_PATH, (proposed.single().hint as LinkHint.ExistingRef).filePath)
        val r = engine.processTurn(proposal, UserInput(userStatement = "응", isCompletionDeclared = false))
        val rejected = r.state.items.filter { it.verdict == Verdict.REJECTED }.mapNotNull { (it.hint as? LinkHint.ExistingRef)?.filePath }
        assertEquals(listOf(ALIM_PATH), rejected)
        assertEquals(proposal.userStatements + "응", r.state.userStatements)
    }
}
