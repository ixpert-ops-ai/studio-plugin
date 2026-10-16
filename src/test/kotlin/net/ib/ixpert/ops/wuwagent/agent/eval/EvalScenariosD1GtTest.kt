package net.ib.ixpert.ops.wuwagent.agent.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-48 D1 정답 확정(사용자, 2026-10-16): SurveyServiceImpl.java와 survey.write.js는 required, 나머지 6개는 role을 유지하되 출처에 "사용자 미확인"을 표시한다.
 */
class EvalScenariosD1GtTest {

    private val d1 = com.google.gson.Gson().fromJson(
        javaClass.getResource("/eval/scenarios.json")!!.readText(Charsets.UTF_8), EvalScenarioFile::class.java
    ).scenarios!!.first { it.id == "D1" }

    @Test
    fun d1ConfirmedFilesAreRequiredWithSource() {
        val gt = d1.gtOrEmpty()
        assertEquals(8, gt.size)
        assertTrue("D1 GT는 모두 required", gt.all { it.countsForRecall() })
        val confirmed = gt.filter { it.reason?.contains("사용자 확정(2026-10-16)") == true }.map { it.label() }
        assertEquals(listOf("survey.write.js", "SurveyServiceImpl.java"), confirmed)
        val unchecked = gt.filter { it.reason?.contains("사용자 미확인") == true }.map { it.label() }
        assertEquals(listOf("survey_list.jsp", "survey_write.jsp", "survey.list.js", "sql_survey.xml", "AlimtalkChnlDto.java", "AlimtalkTmplDto.java"), unchecked)
        assertTrue(d1.mustNotAppearOrEmpty().isEmpty())
    }
}
