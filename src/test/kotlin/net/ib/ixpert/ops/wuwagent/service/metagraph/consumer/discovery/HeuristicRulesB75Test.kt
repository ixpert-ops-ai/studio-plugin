package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** B-75 3: 화면 확장 판정, 범용 번역 토큰, 리소스 파일명 단어 경계 일치. 정상(화면 단어가 있는 요구사항)의 판정은 그대로다. */
class HeuristicRulesB75Test {

    @Test
    fun f1_백엔드_서버_API가_있으면_화면_확장을_하지_않는다() {
        assertFalse(FrontendKeywordRule.isExplicitFrontend("관리자 설문 상세 화면의 백엔드를 수정한다"))
        assertFalse(FrontendKeywordRule.isExplicitFrontend("admin 화면에서 서버 응답을 바꾼다"))
        assertFalse(FrontendKeywordRule.isExplicitFrontend("주문 API에 필드를 추가하고 화면도 본다"))
        assertTrue(FrontendKeywordRule.mentionsBackend("백엔드를 수정"))
        assertTrue(FrontendKeywordRule.mentionsBackend("REST API 추가"))
        assertFalse(FrontendKeywordRule.mentionsBackend("화면 문구를 바꾼다"))
    }

    @Test
    fun f2_화면용_화면에서_쓰는은_화면_키워드로_세지_않는다() {
        assertFalse(FrontendKeywordRule.isExplicitFrontend("관리자 설문 상세 화면용 조회 기능에서 이메일을 반환한다"))
        assertFalse(FrontendKeywordRule.isExplicitFrontend("상세 화면에서 쓰는 응답 필드를 추가한다"))
        assertFalse(FrontendKeywordRule.isExplicitFrontend("상세 화면에서 사용하는 값을 바꾼다"))
    }

    @Test
    fun f3_화면_수정이_목적인_요구사항은_그대로_화면_확장이다() {
        assertTrue(FrontendKeywordRule.isExplicitFrontend("설문 목록 화면에 검색 버튼을 추가한다"))
        assertTrue(FrontendKeywordRule.isExplicitFrontend("어드민 팝업 문구를 수정한다"))
        assertTrue(FrontendKeywordRule.isExplicitFrontend("survey_list.jsp 레이아웃 변경"))
        assertTrue(FrontendKeywordRule.isExplicitFrontend("Vue 컴포넌트 수정"))
        assertTrue(FrontendKeywordRule.isExplicitFrontend("프론트 화면 개선"))
    }

    @Test
    fun g1_범용_번역_토큰은_범용이고_도메인_토큰은_아니다() {
        for (t in listOf("info", "information", "data", "manage", "management", "mgr", "list", "edit", "modify", "Service")) assertTrue(t, GenericTranslationTokens.isGeneric(t))
        for (t in listOf("survey", "email", "member", "chat", "payment", "product", "cert", "auth")) assertFalse(t, GenericTranslationTokens.isGeneric(t))
    }

    @Test
    fun n1_리소스_파일명은_단어_경계로_일치한다() {
        // manage가 manager를 맞히지 않는다(오늘 후보 30개에 들어온 manager.list.js, approve_manager.jsp)
        assertFalse(NameWordMatch.containsWord("manager.list.js", "manage"))
        assertFalse(NameWordMatch.containsWord("approve_manager.jsp", "manage"))
        assertFalse(NameWordMatch.containsWord("manager_list_old.jsp", "manage"))
        // 같은 단어는 일치한다
        assertTrue(NameWordMatch.containsWord("authority_manage.jsp", "manage"))
        assertTrue(NameWordMatch.containsWord("survey.list.js", "survey"))
        assertTrue(NameWordMatch.containsWord("sql_survey.xml", "survey"))
        assertTrue(NameWordMatch.containsWord("surveyResult.jsp", "survey"))
        assertTrue(NameWordMatch.containsWord("survey_result.jsp", "RESULT"))
        // 부분 문자열은 일치하지 않는다
        assertFalse(NameWordMatch.containsWord("surveyor.js", "survey"))
        assertFalse(NameWordMatch.containsWord("anything.js", ""))
    }

    @Test
    fun n2_카멜_경계로도_쪼갠다() {
        assertEquals(true, NameWordMatch.containsWord("SurveyPartExport.js", "part"))
        assertEquals(false, NameWordMatch.containsWord("SurveyPartExport.js", "partex"))
    }
}
