package net.ib.ixpert.ops.wuwagent.agent.stage0

import org.junit.Assert.*
import org.junit.Test

class Stage0GateTest {

    private val dummyAnalysis = SrAnalysis(
        srInput = SrInput("1", "타이틀", "충분히 긴 설명. 기대 동작이 명시되어야 한다. 기술 용어 API 포함."),
        classification = SrClassification(SrType.NEW_FEATURE, emptyList(), 90, ""),
        qualityIndicators = QualityIndicators(100, true, 1, false, true, listOf("API"), true, false),
        deficiencies = emptyList(),
        architectureDecisions = emptyList()
    )

    @Test
    fun testGate_EvasiveAnswerBlocked() {
        val question = Stage0Question(QuestionCategory.DEFICIENCY_RESOLUTION, Priority.CRITICAL, "어떤 방식?", "목적",
            Deficiency(DeficiencyType.OMISSION, DeficiencySeverity.BLOCKING, "필드", "설명", "제안"))
        
        val answers = mapOf(question to "잘 모르겠습니다")
        val result = Stage0Gate.evaluate(dummyAnalysis, listOf(question), answers)
        
        assertFalse(result.canProceed)
        assertTrue(result.blockingReasons.isNotEmpty())
    }

    @Test
    fun testGate_ShortButValidAnswerBlockedForOmission() {
        val question = Stage0Question(QuestionCategory.DEFICIENCY_RESOLUTION, Priority.CRITICAL, "어떤 방식?", "목적",
            Deficiency(DeficiencyType.OMISSION, DeficiencySeverity.BLOCKING, "필드", "설명", "제안"))
        
        val answers = mapOf(question to "안녕") // 너무 짧음
        val result = Stage0Gate.evaluate(dummyAnalysis, listOf(question), answers)
        
        assertFalse(result.canProceed)
    }

    @Test
    fun testGate_ValidAnswerPasses() {
        val question = Stage0Question(QuestionCategory.DEFICIENCY_RESOLUTION, Priority.CRITICAL, "어떤 방식?", "목적",
            Deficiency(DeficiencyType.OMISSION, DeficiencySeverity.BLOCKING, "필드", "설명", "제안"))
        
        val answers = mapOf(question to "이 부분은 A 방식으로 처리해야 합니다.")
        val result = Stage0Gate.evaluate(dummyAnalysis, listOf(question), answers)
        
        assertTrue(result.canProceed)
        assertNotNull(result.enrichedSr)
    }

    @Test
    fun testGate_ArchDecisionValidAnswerPasses() {
        val archDecision = ArchDecision("통신", listOf("A", "B"), "통신")
        val question = Stage0Question(QuestionCategory.ARCHITECTURE_DECISION, Priority.HIGH, "방식은?", "목적", null, archDecision)
        
        val answers = mapOf(question to "1번 방식으로 진행해주세요.")
        val result = Stage0Gate.evaluate(dummyAnalysis.copy(architectureDecisions = listOf(archDecision)), listOf(question), answers)
        
        assertTrue(result.canProceed)
        val enriched = result.enrichedSr
        assertNotNull(enriched)
        assertEquals("1번 방식으로 진행해주세요.", enriched?.resolvedDecisions?.first()?.selectedOption)
    }
}
