package net.ib.ixpert.ops.wuwagent.agent.clarify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** B-79 5: 모델이 만든 내부 용어("토큰 일치" 등)를 사용자 문구로 바꾸고, 지시문 필터(65d27ad)가 정상 문장을 지우지 않는지 확인한다. */
class SummaryWordingB79Test {

    // 2026-10-29 10:41 IDE의 Stage 0 요약(실제 출력): "토큰 일치 수준"이 나왔다
    private val todayIdeSummary = "설문 응답 테이블에 이메일 컬럼을 추가하고, 관리자 설문 상세 조회 시 해당 이메일을 앞 2자리와 도메인만 노출되도록 마스킹하여 반환하는 백엔드 로직 수정이 핵심 목표입니다.\n\n" +
        "현재 후보 목록 중 `IpsController.java`, `survey_result.jsp`, `survey.result.js`는 설문 결과 상세 화면의 URL과 변수를 공유하여 해당 화면의 데이터 수신 및 표시에 관여할 가능성이 있습니다. " +
        "`UserDto.java`, `sql_user.xml`, `UserServiceImpl.java`는 이름이나 호출 관계가 유사하여 포함되었으나, 근거가 토큰 일치 수준에 불과하므로 실제 설문 응답 이메일 처리와 직접적인 연관성은 확인이 필요합니다. " +
        "`survey_list.jsp`와 `survey.list.js`는 목록 화면 관련 파일로, 상세 조회 기능의 직접적인 변경 대상과는 거리가 있어 보입니다."

    @Test
    fun w1_토큰_일치는_이름_유사로_바뀐다() {
        val out = RationaleWording.cleanSummary(todayIdeSummary)
        assertTrue(out, out.contains("근거가 이름 유사 수준에 불과하므로"))
        assertFalse(out, out.contains("토큰"))
        // 나머지 문장은 그대로다
        assertTrue(out.contains("survey_result.jsp") && out.contains("거리가 있어 보입니다."))
    }

    @Test
    fun w2_근거_문구의_내부_표현과_남은_토큰도_사용자_문구로_바뀐다() {
        assertEquals("이름이 요구사항 단어(edit, modify)와 비슷함", RationaleWording.cleanSummary("클래스명/엔드포인트가 요구사항 토큰(edit, modify)과 일치"))
        assertEquals("같은 화면 변수(a, b) 사용", RationaleWording.cleanSummary("구조 식별자(a, b) 공유"))
        assertEquals("검색 단어와 이름이 비슷한 파일", RationaleWording.cleanSummary("검색 토큰과 이름이 비슷한 파일"))
        assertEquals("내부 용어가 없는 문장은 그대로입니다.", RationaleWording.cleanSummary("내부 용어가 없는 문장은 그대로입니다."))
    }

    // 65d27ad의 지시문 필터가 정상 요약을 지우지 않는다: 실제 요약 출력들과 "어긋나/확인" 같은 일반 표현이 든 정상 문장
    private val normalSummaries = listOf(
        todayIdeSummary,
        "설문조사 상세 조회 시 응답자 이메일 정보를 백엔드에서 함께 반환하도록 수정하는 것이 핵심 목표입니다.\n현재 후보 파일 목록 내 소스 및 매퍼 파일에서 응답자 이메일 관련 로직이 확인되지 않았으므로, 해당 데이터 매핑 및 조회 로직을 추가해야 합니다.\nUserDto.java와 sql_user.xml은 이름 유사성으로 포함되었으나, 실제 설문 응답자 이메일 처리와 직접적인 관련성이 확인되지 않아 확인이 필요합니다.",
        "기존 설문조사 상세 조회 기능에 응답자 이메일 정보를 포함하도록 백엔드 로직을 수정하는 것이 핵심 목표입니다.\n\n다만, 코드 확인 결과 후보 폴더 내 소스 및 매퍼 파일에는 응답자 이메일 정보가 존재하지 않으며, 다른 업무 쿼리에만 해당 데이터가 확인되었습니다.",
        "사용자 제약과 어긋나는 파일은 핵심 변경 대상이 아닙니다. 확인된 후보는 3개이며 이름이 비슷해 포함되었으므로 확인이 필요합니다.",
        "화면 쪽 파일은 요구사항의 범위와 어긋나 보이지만 같은 URL을 공유합니다. 참고용 후보이니 쓰지 않아도 됩니다."
    )

    @Test
    fun w3_정상_요약은_지시문_필터가_한_글자도_지우지_않는다() {
        for (s in normalSummaries) {
            assertFalse("지시문으로 오인: $s", SummaryPromptEcho.leaks(s))
            assertEquals(s, SummaryPromptEcho.strip(s))
        }
    }

    @Test
    fun w4_지시문_문장만_지워지고_바로_옆_정상_문장은_남는다() {
        val mixed = "핵심 목표는 이메일 반환입니다.\n사실과 어긋나지 않도록 목록의 문구를 쓰지 말아야 합니다.\n이름 유사 후보는 확인이 필요합니다."
        assertEquals("핵심 목표는 이메일 반환입니다.\n이름 유사 후보는 확인이 필요합니다.", SummaryPromptEcho.strip(mixed))
    }
}
