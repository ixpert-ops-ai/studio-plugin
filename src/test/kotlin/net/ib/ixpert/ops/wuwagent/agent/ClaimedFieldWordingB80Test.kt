package net.ib.ixpert.ops.wuwagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** B-80 6, 7, 8, 10: 결과 표 사유와 ℹ️ 줄의 일치, 다음 행동 문구, 범위·테이블 기준 문구, 단계별 메타데이터. */
class ClaimedFieldWordingB80Test {

    private fun finding(answer: String?, domainScope: Boolean = true, tables: List<String> = listOf("ISSW_ADMIN_USER", "ISSW_COMPANY")) = ClaimedFieldCheck.Finding(
        noun = "이메일", subject = "응답자", filesRead = 24, otherCites = listOf("sql_survey.xml:670", "sql_survey.xml:841"),
        otherTables = tables, seedNames = listOf("SurveyServiceImpl", "SurveyDao"), answer = answer, domainScope = domainScope
    )

    private val saved = "응답 테이블에 이메일 컬럼을 새로 추가해서 저장"

    @Test
    fun w1_수집_저장이_범위에_들어가면_첫_Service_사유에_저장_경로가_붙는다() {
        val paths = listOf("a/survey/web/IpsController.java", "a/survey/service/SurveyServiceImpl.java", "a/survey/service/SurveyService.java")
        val r = ClaimedFieldCheck.savePathReason(listOf(finding(saved)), paths[1], paths, "설문 상세 응답에 필드를 추가합니다")
        assertNotNull(r)
        assertTrue(r!!, r.contains("응답자 이메일 수집·저장 경로 추가") && r.contains("프로시저"))
        // 첫 Service가 아닌 파일, Service가 아닌 파일, 이미 저장을 말하는 사유에는 붙이지 않는다
        assertNull(ClaimedFieldCheck.savePathReason(listOf(finding(saved)), paths[2], paths, "인터페이스"))
        assertNull(ClaimedFieldCheck.savePathReason(listOf(finding(saved)), paths[0], paths, "컨트롤러"))
        assertNull(ClaimedFieldCheck.savePathReason(listOf(finding(saved)), paths[1], paths, "이메일을 저장하는 로직을 추가"))
        // 새로 저장이 아닌 답(조인)이면 붙이지 않는다
        assertNull(ClaimedFieldCheck.savePathReason(listOf(finding("다른 테이블에서 조인해서 가져와")), paths[1], paths, "설문 상세"))
    }

    @Test
    fun w2_새_컬럼이면_DDL_한_줄_안내가_나온다() {
        val n = ClaimedFieldCheck.ddlNote(finding(saved))
        assertNotNull(n)
        assertTrue(n!!, n.contains("ALTER TABLE") && n.contains("DDL 파일을 만들지 않았습니다") && !n.contains("\n"))
        assertNull(ClaimedFieldCheck.ddlNote(finding("설문 응답 테이블에 새로 저장")))
        assertNull(ClaimedFieldCheck.ddlNote(finding("다른 테이블에서 조인")))
        assertNull(ClaimedFieldCheck.ddlNote(finding(null)))
    }

    @Test
    fun w3_수집_경로_안내_줄에_다음_행동이_붙는다() {
        val withName = ClaimedFieldCheck.entryPointNote(finding(saved), "IpsController")
        assertTrue(withName, withName.contains("필요하면 @IpsController 를 추가해 다시 분석하세요"))
        val generic = ClaimedFieldCheck.entryPointNote(finding(saved), null)
        assertTrue(generic, generic.contains("필요하면") && generic.contains("@로 추가해 다시 분석하세요"))
        assertEquals("IpsController", ClaimedFieldCheck.suggestedEntryController(listOf("a/SurveyDao.java", "b/IpsController.java", "c/UserController.java"), listOf("a/SurveyDao.java")))
        assertNull(ClaimedFieldCheck.suggestedEntryController(listOf("b/IpsController.java"), listOf("b/IpsController.java")))
    }

    @Test
    fun w4_읽은_폴더가_도메인_패키지가_아니면_도메인_이름을_쓰지_않는다() {
        assertEquals("설문 관련 코드를 읽어 확인", ClaimedFieldCheck.scopeNote(listOf("SurveyServiceImpl"), true))
        assertEquals("관련 코드를 읽어 확인", ClaimedFieldCheck.scopeNote(listOf("SurveyServiceImpl"), false))
        val mixed = ClaimedFieldCheck.render(finding(null, domainScope = false))
        assertTrue(mixed, mixed.contains("(관련 코드를 읽어 확인)") && !mixed.contains("설문 관련 코드"))
        val domain = ClaimedFieldCheck.render(finding(null, domainScope = true))
        assertTrue(domain, domain.contains("설문 관련 코드를 읽어 확인"))
        val resolvedMixed = ClaimedFieldCheck.renderResolved(finding(saved, domainScope = false))
        assertTrue(resolvedMixed, resolvedMixed.contains(": 관련 코드를 읽어 확인했으나") && !resolvedMixed.contains("설문 관련 코드"))
    }

    @Test
    fun w5_다른_업무_코드에서만은_테이블_기준_표현이다() {
        val domain = ClaimedFieldCheck.question(finding(null, domainScope = true))
        assertTrue(domain, domain.contains("설문 매퍼 안의 ISSW_ADMIN_USER/ISSW_COMPANY 테이블 쿼리에서만 발견됩니다: sql_survey.xml:670, sql_survey.xml:841"))
        assertFalse(domain, domain.contains("다른 업무 코드에서만"))
        val mixed = ClaimedFieldCheck.question(finding(null, domainScope = false))
        assertTrue(mixed, mixed.contains("읽은 범위 안의 ISSW_ADMIN_USER/ISSW_COMPANY 테이블 쿼리에서만 발견됩니다") && !mixed.contains("설문 매퍼"))
        // 결과 경고 줄도 같은 표현
        val warn = ClaimedFieldCheck.render(finding(null, domainScope = true))
        assertTrue(warn, warn.contains("설문 매퍼 안의 ISSW_ADMIN_USER/ISSW_COMPANY 테이블 쿼리에서만 발견됩니다"))
        // 테이블이 없는 발견(자바 코드)은 종전 말투를 유지한다
        val noTable = ClaimedFieldCheck.question(finding(null, tables = emptyList()))
        assertTrue(noTable, noTable.contains("다른 업무 코드에서만 발견됩니다: sql_survey.xml:670"))
    }

    @Test
    fun w6_메타데이터는_단계별_개수로_보인다() {
        assertEquals("평가 30개 → 검증 후보 13개 → 최종 4개", AnalysisResultWording.stageCounts(30, 13, 4, 0))
        assertEquals("평가 30개 → 검증 후보 13개 → 최종 4개 (신규 제안 2개는 별도)", AnalysisResultWording.stageCounts(30, 13, 4, 2))
    }
}
