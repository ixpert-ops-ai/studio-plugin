package net.ib.ixpert.ops.wuwagent.agent.clarify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** B-79 4: 사용자의 답을 출처 / 대상 화면 / 마스킹 규칙으로 나눠 보이고, 결과 경고는 그중 출처 부분만 인용한다. */
class AnswerInterpreterB79Test {

    // 2026-10-29 IDE에서 사용자가 실제로 쓴 답
    private val answer = "응답자 이메일은 아직 수집하지 않아. 설문 응답 테이블에 이메일 컬럼을 새로 추가해서 저장하는 방향으로 해줘. 관리자 설문 상세 화면용이고, 이메일은 앞 2자리와 도메인만 보이게 마스킹해줘."

    @Test
    fun i1_오늘_답을_세_항목으로_나눈다() {
        val r = AnswerInterpreter.interpret(answer)
        assertEquals(listOf("응답자 이메일은 아직 수집하지 않아", "설문 응답 테이블에 이메일 컬럼을 새로 추가해서 저장하는 방향으로"), r.source)
        assertEquals(listOf("관리자 설문 상세 화면용"), r.screen)
        assertEquals(listOf("이메일은 앞 2자리와 도메인만 보이게 마스킹"), r.mask)
        assertTrue(r.other.isEmpty())
    }

    @Test
    fun i2_기록함_메시지는_발화_통째_인용_대신_항목별_줄이다() {
        val echo = AnswerInterpreter.echo("응답자 이메일", answer)
        val lines = echo.lines()
        assertEquals("기록함: 응답자 이메일의 출처 (제약으로 정제문에 전달됩니다)", lines[0])
        assertEquals(listOf("- 출처: ", "- 대상 화면: ", "- 마스킹 규칙: "), lines.drop(1).map { it.substringBefore(":") + ": " })
        assertFalse("발화 전체를 그대로 싣지 않는다: $echo", echo.contains(answer))
        assertTrue(echo, echo.contains("- 대상 화면: 관리자 설문 상세 화면용") && echo.contains("- 마스킹 규칙: 이메일은 앞 2자리와 도메인만 보이게 마스킹"))
    }

    @Test
    fun i3_결과_경고는_출처_절만_인용한다() {
        val q = AnswerInterpreter.sourceQuote(answer)
        assertEquals("응답자 이메일은 아직 수집하지 않아 / 설문 응답 테이블에 이메일 컬럼을 새로 추가해서 저장하는 방향으로", q)
        assertFalse(q, q.contains("마스킹") || q.contains("화면"))
    }

    @Test
    fun i4_나눌_수_없으면_원문_앞부분을_인용한다() {
        val e = AnswerInterpreter.echo("응답자 이메일", "잘 모르겠어요")
        assertTrue(e, e.startsWith("기록함: 응답자 이메일의 출처 — \"잘 모르겠어요\""))
        assertEquals("잘 모르겠어요", AnswerInterpreter.sourceQuote("잘 모르겠어요"))
    }
}
