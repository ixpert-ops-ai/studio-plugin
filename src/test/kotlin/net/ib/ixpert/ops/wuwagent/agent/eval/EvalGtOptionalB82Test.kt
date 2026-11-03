package net.ib.ixpert.ops.wuwagent.agent.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-82 4: 정답을 필수와 선택으로 나눈다. 기존 시나리오(role 없음)의 Recall 분모는 그대로이고, 선택(optional)은 분모에서 빠진다.
 * 응답자 이메일 SR(설문 상세 조회, 컬럼 추가)의 정답: 필수 sql_survey.xml, SurveyDto, SurveyServiceImpl / 선택 SurveyDao, SurveyDaoImpl
 * (근거: SurveyDaoImpl.java:48-50, 143-145는 DTO를 sqlSession에 그대로 넘긴다).
 */
class EvalGtOptionalB82Test {
    private val emailSr = listOf(
        EvalGt(path = "src/main/java/net/infobank/iss/sql/mysql/sql_survey.xml"),
        EvalGt(path = "src/main/java/net/infobank/iss/survey/dto/SurveyDto.java"),
        EvalGt(path = "src/main/java/net/infobank/iss/survey/service/SurveyServiceImpl.java"),
        EvalGt(path = "src/main/java/net/infobank/iss/survey/dao/SurveyDao.java", role = "optional"),
        EvalGt(path = "src/main/java/net/infobank/iss/survey/dao/SurveyDaoImpl.java", role = "optional")
    )

    @Test
    fun o1_선택_정답은_Recall_분모에서_빠진다() {
        assertEquals(3, emailSr.count { it.countsForRecall() })
        assertEquals(2, emailSr.count { it.isOptional() })
        assertTrue(emailSr.filter { it.isOptional() }.none { it.countsForRecall() })
    }

    @Test
    fun o2_role이_없는_기존_정답과_reference는_종전과_같다() {
        assertTrue(EvalGt(path = "a").countsForRecall())
        assertFalse(EvalGt(path = "a").isOptional())
        assertFalse(EvalGt(path = "a", role = "reference").countsForRecall())
        assertFalse(EvalGt(path = "a", inGraph = false).countsForRecall())
    }

    @Test
    fun o3_이메일_SR_최종_목록_판정_IDE_기록() {
        // IDE 최종 목록(11-03): SurveyDaoImpl, sql_survey.xml, SurveyDto, SurveyServiceImpl → 필수 3/3, 선택 1/2
        val final = listOf("survey/dao/SurveyDaoImpl.java", "sql/mysql/sql_survey.xml", "survey/dto/SurveyDto.java", "survey/service/SurveyServiceImpl.java")
        val required = emailSr.filter { it.countsForRecall() }.count { gt -> final.any { gt.path!!.endsWith(it) } }
        val optional = emailSr.filter { it.isOptional() }.count { gt -> final.any { gt.path!!.endsWith(it) } }
        assertEquals(3, required)
        assertEquals(1, optional)
    }
}
