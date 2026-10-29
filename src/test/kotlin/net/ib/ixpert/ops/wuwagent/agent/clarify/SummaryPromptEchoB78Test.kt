package net.ib.ixpert.ops.wuwagent.agent.clarify

import net.ib.ixpert.ops.wuwagent.agent.ClaimedFieldCheck
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.FieldFact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** B-78 5: 요약이 프롬프트 지시문을 옮겨 쓰지 않게 한다(사실 줄은 평서문, 에코 문장은 걸러낸다). */
class SummaryPromptEchoB78Test {

    @Test
    fun e1_사실_줄은_평서문이고_지시문_표지가_없다() {
        val notFound = FieldFact("응답자 이메일", found = false, otherCites = listOf("sql_survey.xml:670"), filesRead = 30, question = "q")
        val found = FieldFact("관리자 계정 이메일", found = true, cite = "UserDto.java:12", filesRead = 4)
        val l1 = ClaimedFieldCheck.promptLine(notFound)
        val l2 = ClaimedFieldCheck.promptLine(found)
        assertEquals("- 응답자 이메일: 없음(후보 폴더의 소스·매퍼 30개를 읽음), 다른 업무의 쿼리에만 있음(sql_survey.xml:670)", l1)
        assertEquals("- 관리자 계정 이메일: UserDto.java:12에 있음", l2)
        assertFalse(l1, SummaryPromptEcho.leaks(l1))
        assertFalse(l2, SummaryPromptEcho.leaks(l2))
    }

    @Test
    fun e2_지시문을_옮겨_쓴_문장은_빠지고_나머지는_남는다() {
        val text = "기존 설문 상세 조회에 응답자 이메일을 포함하도록 수정하는 것이 핵심 목표입니다.\n" +
            "다만 사실과 어긋나지 않도록 응답자 이메일은 UserDto에 있다고 쓰지 말아야 하며 이름 유사성으로만 취급해야 합니다.\n" +
            "UserDto.java는 이름이 비슷해 포함되었으므로 확인이 필요합니다."
        assertTrue(SummaryPromptEcho.leaks(text))
        val out = SummaryPromptEcho.strip(text)!!
        assertFalse(out, SummaryPromptEcho.leaks(out))
        assertTrue(out, out.contains("핵심 목표입니다.") && out.contains("확인이 필요합니다."))
        assertFalse(out, out.contains("어긋나지 않도록"))
    }

    @Test
    fun e3_지시문이_없으면_그대로이고_전부_지시문이면_null() {
        val clean = "설문 상세 조회에 이메일을 포함하도록 수정합니다.\n후보 중 UserDto는 이름이 비슷해 포함되었습니다."
        assertEquals(clean, SummaryPromptEcho.strip(clean))
        assertNull(SummaryPromptEcho.strip("목록의 문구를 옮기지 말 것. 어긋나지 않도록 취급해야 한다."))
        assertNotNull(SummaryPromptEcho.strip("소스 확인 결과에 따르면 이메일이 없다. 대신 설명 문장 하나가 남는다."))
    }

    @Test
    fun e4_B77_실험_요약_5회의_표지_수를_센다() {
        // B-77 실험에서 사실 줄을 넣은 요약 5회의 원문(사람이 읽은 보고서에서 옮김). 전: 표지가 몇 회에서 나오는가
        val before = listOf(
            "다만, 소스 확인 결과에 따르면 현재 유효한 후보 파일 목록 내에서는 응답자 이메일 관련 로직이 발견되지 않았으며, 이메일 정보는 후보 범위 밖의 sql_survey.xml에서만 확인되었습니다.",
            "다만, 소스 확인 결과에 따르면 현재 유효한 후보 파일 목록 내에서는 응답자 이메일 관련 로직이 발견되지 않았으며, 이메일 정보는 후보 범위 밖의 sql_survey.xml에서만 확인되었습니다.",
            "다만, 소스 확인 결과에 따르면 현재 유효한 후보 파일 목록 내에서는 응답자 이메일 관련 로직이 발견되지 않았으며, 이메일 정보는 후보 범위 밖의 sql_survey.xml에서만 확인되었습니다.",
            "다만, 소스 확인 결과에 따르면 현재 유효한 후보 파일 목록 내의 업무 코드나 SQL에서 응답자 이메일 정보가 발견되지 않았으며, 이는 다른 업무 코드에서만 존재하는 것으로 확인되었습니다.",
            "다만, 소스 확인 결과에 따르면 현재 유효한 후보 파일 목록 내에서는 응답자 이메일 관련 로직이 발견되지 않았으며, 이메일 정보는 후보 범위 밖의 sql_survey.xml에서만 확인되었습니다."
        )
        assertEquals("전: 5회 모두 표지가 있다(\"소스 확인 결과\")", 5, before.count { SummaryPromptEcho.leaks(it) })
    }
}
