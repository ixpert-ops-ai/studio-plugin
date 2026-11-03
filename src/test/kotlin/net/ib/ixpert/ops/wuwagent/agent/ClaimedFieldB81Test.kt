package net.ib.ixpert.ops.wuwagent.agent

import net.ib.ixpert.ops.wuwagent.agent.clarify.AnswerInterpreter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** B-81 3, 8: "모름" 판정과 Stage 0·결과 화면이 같은 출처 문장 함수를 쓰는지. */
class ClaimedFieldB81Test {

    private fun finding(answer: String?) = ClaimedFieldCheck.Finding(
        noun = "이메일", subject = "응답자", filesRead = 24, otherCites = listOf("sql_survey.xml:670"),
        otherTables = listOf("ISSW_ADMIN_USER"), seedNames = listOf("SurveyServiceImpl"), answer = answer, readFolders = listOf("survey/service", "survey/dao")
    )

    @Test
    fun u1_모름_답변_판정() {
        for (a in listOf("잘 모르겠음", "잘 모르겠어요", "모르겠습니다", "몰라요", "모릅니다", "음 모르겠네요")) {
            assertTrue(a, ClaimedFieldCheck.isUnknownAnswer(a))
            assertTrue(a, finding(a).unresolved)
        }
        // 출처를 함께 말했으면 모름이 아니다
        for (a in listOf("잘 모르겠지만 새로 저장해줘", "모르니까 다른 테이블에서 조인해서 가져와", "설문 응답 테이블에 이메일 컬럼을 새로 추가해서 저장")) {
            assertFalse(a, ClaimedFieldCheck.isUnknownAnswer(a))
            assertFalse(a, finding(a).unresolved)
        }
        // 답이 없으면 미해결
        assertTrue(finding(null).unresolved)
    }

    @Test
    fun u2_모름_답변의_결과_줄은_경고_문구이고_정보_문구가_아니다() {
        val line = ClaimedFieldCheck.renderResolved(finding("잘 모르겠음"))
        assertTrue(line, line.startsWith("확인 안 됨:") && line.contains("사용자 답변: 출처 미정(사용자도 모름)"))
        assertFalse(line, line.contains("출처가 지정되었습니다") || line.contains("구현 범위에 포함"))
    }

    @Test
    fun u3_결과_화면의_출처_문장은_Stage0_기록함과_같은_함수의_결과다() {
        for (a in listOf(
            "설문 응답 테이블에 이메일 컬럼을 새로 추가해서 저장하는 방향으로 해줘",
            "응답자 이메일은 아직 수집하지 않아. 설문 응답 테이블에 이메일 컬럼을 새로 추가해서 저장하는 방향으로 해줘.",
            "다른 테이블에서 조인해서 가져오세요"
        )) {
            val sentence = ClaimedFieldCheck.sourceSentence(a)
            assertTrue("Stage 0 기록함의 출처 줄과 같다: $sentence", AnswerInterpreter.echo("응답자 이메일", a).lines().any { it == "- 출처: $sentence" })
            val line = ClaimedFieldCheck.renderResolved(finding(a))
            assertTrue(line, line.contains("(출처: $sentence)"))
            assertFalse("발화 통째 인용이 아니다: $line", line.contains("\""))
        }
    }

    @Test
    fun u4_범위_문구는_읽은_폴더로_만들고_고정_문구가_없다() {
        val line = ClaimedFieldCheck.render(finding(null))
        assertTrue(line, line.contains("(분석 단계에서 survey/service·survey/dao 폴더를 읽어 확인)"))
        assertFalse(line, line.contains("설문 관련 코드를 읽어"))
        val q = ClaimedFieldCheck.question(finding(null))
        assertTrue(q, q.contains("(분석 단계에서 survey/service·survey/dao 폴더를 읽어 확인)"))
        // 질문 카드와 대화 기록이 쓰는 question()도 같은 scopeNote 함수를 쓴다(단계만 다르다)
        val stage0 = ClaimedFieldCheck.question(ClaimedFieldCheck.Finding("이메일", "응답자", 24, emptyList(), readFolders = listOf("survey/service", "batch"), stage = "구체화"))
        assertTrue(stage0, stage0.contains("(구체화 단계에서 survey/service·batch 폴더를 읽어 확인)"))
    }
}
