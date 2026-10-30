package net.ib.ixpert.ops.wuwagent.agent.clarify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** B-80 4: 출처 답 3유형(새로 저장 / 조인 / 모름), 마스킹 규칙 변형, 정리한 출처 문장과 뜻이 바뀌는 경우의 원문 유지. */
class AnswerInterpreterB80Test {

    private fun sourceLineOf(answer: String): String = AnswerInterpreter.sourceLine(AnswerInterpreter.interpret(answer).source)

    @Test
    fun a1_새로_저장하는_답은_기존_데이터_없음_화살표_신설_저장_문장이_된다() {
        assertEquals(
            "기존 데이터 없음 → 응답 테이블에 이메일 컬럼 신설·저장",
            sourceLineOf("응답자 이메일은 아직 수집하지 않아. 응답 테이블에 이메일 컬럼을 새로 추가해서 저장해줘.")
        )
        assertEquals("설문 응답 테이블에 이메일 컬럼 신설·저장", sourceLineOf("설문 응답 테이블에 이메일 컬럼을 새로 추가해서 저장해 주세요"))
    }

    @Test
    fun a2_조인하는_답은_조인으로_조회_문장이_된다() {
        assertEquals("다른 테이블 조인으로 조회", sourceLineOf("다른 테이블에서 조인해서 가져오세요"))
        assertEquals("회사 테이블 조인으로 조회", sourceLineOf("회사 테이블에서 조인해서 가져와"))
        // 알려진 한계: "관리자"가 들어간 절은 대상 화면으로 먼저 나뉜다(우선순위 마스킹 > 대상 화면 > 출처). 테이블 이름이어도 같다.
        assertTrue(AnswerInterpreter.interpret("관리자 테이블에서 조인해서 가져와").source.isEmpty())
    }

    @Test
    fun a3_모른다는_답은_출처_미정으로_줄이_남는다() {
        assertEquals("출처 미정(사용자도 모름)", sourceLineOf("잘 모르겠어요"))
        assertEquals("출처 미정(사용자도 모름)", sourceLineOf("모르겠어"))
        val echo = AnswerInterpreter.echo("응답자 이메일", "잘 모르겠어요")
        assertTrue(echo, echo.contains("- 출처: 출처 미정(사용자도 모름)"))
    }

    @Test
    fun a4_뜻이_바뀔_수_있으면_원문_절을_그대로_둔다() {
        // 부정·제외 표현이 있는 절은 정리 규칙이 맞아 보여도 바꾸지 않는다
        assertNull(AnswerInterpreter.refineSourceClause("관리자 테이블에 이메일 컬럼을 새로 추가하지 말고 저장"))
        assertEquals("이메일 컬럼을 새로 추가하지 말고 기존 테이블에서 조인해서 가져와", sourceLineOf("이메일 컬럼을 새로 추가하지 말고 기존 테이블에서 조인해서 가져와"))
        // 규칙에 정확히 맞지 않는 절이 하나라도 섞이면 전부 원문 절로 둔다(" / "로 잇는다)
        assertEquals(
            "응답자 이메일은 아직 수집하지 않아 / 설문 응답 테이블에 이메일을 어딘가에 저장하는 방향으로",
            sourceLineOf("응답자 이메일은 아직 수집하지 않아. 설문 응답 테이블에 이메일을 어딘가에 저장하는 방향으로 해줘.")
        )
        // 수집 여부를 말하지 않는 "없어"는 기존 데이터 없음으로 바꾸지 않는다
        assertNull(AnswerInterpreter.refineSourceClause("연동 API가 없어"))
    }

    @Test
    fun a5_마스킹_규칙_변형_두_가지도_마스킹_규칙으로_나뉜다() {
        val a = AnswerInterpreter.interpret("앞 3자리만 보이고 나머지는 *로 표시")
        assertEquals(listOf("앞 3자리만 보이고 나머지는 *로 표시"), a.mask)
        val b = AnswerInterpreter.interpret("@ 앞부분은 가리고 도메인만 보이게 해줘")
        assertEquals(listOf("@ 앞부분은 가리고 도메인만 보이게"), b.mask)
        val c = AnswerInterpreter.interpret("이메일은 가운데를 숨겨서 보여줘")
        assertEquals(listOf("이메일은 가운데를 숨겨서 보여"), c.mask) // 끝의 "줘"는 정리 규칙(TRAILING)이 뗀다
        assertTrue(a.source.isEmpty() && b.source.isEmpty() && c.source.isEmpty())
    }

    @Test
    fun a6_세_항목이_섞인_답도_줄마다_정리된다() {
        val echo = AnswerInterpreter.echo(
            "응답자 이메일",
            "응답자 이메일은 아직 수집하지 않아. 응답 테이블에 이메일 컬럼을 새로 추가해서 저장해줘. 관리자 설문 상세 화면용이고, 이메일은 @ 앞부분은 가리고 도메인만 보이게 해줘."
        )
        val lines = echo.lines()
        assertEquals("- 출처: 기존 데이터 없음 → 응답 테이블에 이메일 컬럼 신설·저장", lines[1])
        assertEquals("- 대상 화면: 관리자 설문 상세 화면용", lines[2])
        assertTrue(lines[3], lines[3].startsWith("- 마스킹 규칙: "))
        assertFalse(echo, echo.contains("해줘"))
    }

    @Test
    fun a7_결과_경고의_인용은_정리하지_않고_사용자_원문_절을_쓴다() {
        assertEquals("다른 테이블에서 조인해서 가져오세요", AnswerInterpreter.sourceQuote("다른 테이블에서 조인해서 가져오세요"))
    }
}
