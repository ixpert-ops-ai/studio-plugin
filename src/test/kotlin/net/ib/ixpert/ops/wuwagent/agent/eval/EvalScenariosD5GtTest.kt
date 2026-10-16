package net.ib.ixpert.ops.wuwagent.agent.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-45 D5 계열 GT 안 A(사용자 확정): required는 SurveyServiceImpl 1개, sql_survey.xml과 알림톡 DTO 2개는 reference, 제외 대상은 AlimtalkTemplateBatch* 3개.
 * D1과 다른 시나리오의 GT는 바뀌지 않았다.
 */
class EvalScenariosD5GtTest {

    // loadAll()은 probe 그룹(D5E, D5N)을 eval.only 없이는 빼므로 픽스처를 직접 읽는다
    private val byId = com.google.gson.Gson().fromJson(
        javaClass.getResource("/eval/scenarios.json")!!.readText(Charsets.UTF_8), EvalScenarioFile::class.java
    ).scenarios!!.associateBy { it.id }

    @Test
    fun d5FamilyUsesPlanAGtAndMustNotAppear() {
        for (id in listOf("D5", "D5E", "D5N")) {
            val sc = byId.getValue(id)
            assertEquals("$id required", listOf("SurveyServiceImpl.java"), sc.gtOrEmpty().filter { it.countsForRecall() }.map { it.label() })
            assertEquals("$id reference", listOf("sql_survey.xml", "AlimtalkChnlDto.java", "AlimtalkTmplDto.java"), sc.gtOrEmpty().filter { it.isReference() }.map { it.label() })
            assertEquals("$id 제외 대상", listOf("AlimtalkTemplateBatchJob.java", "AlimtalkTemplateBatchRunner.java", "AlimtalkTemplateBatchRepository.java"), sc.mustNotAppearOrEmpty().map { it.label() })
        }
    }

    @Test
    fun otherScenariosKeepTheirGt() {
        val d1 = byId.getValue("D1")
        assertEquals(8, d1.gtOrEmpty().size)
        assertTrue("D1 GT는 모두 required", d1.gtOrEmpty().all { it.countsForRecall() })
        assertTrue("D1에는 제외 대상이 없다", d1.mustNotAppearOrEmpty().isEmpty())
        for (id in byId.keys - setOf("D5", "D5E", "D5N")) assertTrue("$id 에는 제외 대상이 없다", byId.getValue(id).mustNotAppearOrEmpty().isEmpty())
    }
}
