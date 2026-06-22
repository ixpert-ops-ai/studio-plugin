package net.ib.ixpert.ops.wuwagent.agent.stage0

import org.junit.Assert.*
import org.junit.Test

class DeficiencyDetectorTest {

    @Test
    fun testDetectOmission_ShortDescription() {
        val input = SrInput("1", "타이틀", "너무 짧은 설명")
        val quality = SrQualityAnalyzer.analyze(input)
        val classification = SrClassifier.preClassify(input) ?: SrClassification(SrType.NEW_FEATURE, emptyList(), 100, "")
        val decisions = ArchitectureDecisionExtractor.extract(input)
        
        val deficiencies = DeficiencyDetector.detect(input, quality, classification, decisions)
        
        val omission = deficiencies.find { it.field == "설명 길이" }
        assertNotNull(omission)
        assertEquals(DeficiencySeverity.BLOCKING, omission?.severity)
    }

    @Test
    fun testDetectOmission_NoExpectedBehavior() {
        val input = SrInput("2", "기능 수정", "버튼을 클릭하면 오류가 납니다. 이것을 수정해주세요. 50자가 넘도록 길게 작성합니다. 길게길게길게길게길게.")
        val quality = SrQualityAnalyzer.analyze(input)
        val classification = SrClassification(SrType.NEW_FEATURE, emptyList(), 100, "")
        
        val deficiencies = DeficiencyDetector.detect(input, quality, classification, emptyList())
        val omission = deficiencies.find { it.field == "기대 동작" }
        assertNotNull(omission)
        assertEquals(DeficiencySeverity.BLOCKING, omission?.severity)
    }

    @Test
    fun testDetectConflict_SimultaneousDeleteAndKeep() {
        val input = SrInput("3", "회원 탈퇴 처리", "회원을 삭제하되 데이터는 유지해주세요. 길이는 충분합니다 50자 넘게 채웁니다. 기대 동작: 팝업이 표시되어야 한다.")
        val quality = SrQualityAnalyzer.analyze(input)
        val classification = SrClassification(SrType.BATCH_MODIFY, emptyList(), 100, "")
        
        val deficiencies = DeficiencyDetector.detect(input, quality, classification, emptyList())
        val conflict = deficiencies.find { it.type == DeficiencyType.CONFLICT }
        assertNotNull(conflict)
        assertEquals("삭제/제거와 유지/보존이 동시에 요구됩니다.", conflict?.description)
    }

    @Test
    fun testDetectAmbiguity_UnresolvedArchitecture() {
        val input = SrInput("4", "페이징 적용", "게시판에 페이징을 적용해야 합니다. 50자 이상 채웁니다. 기대 동작: 페이지가 넘어가야 한다.")
        val quality = SrQualityAnalyzer.analyze(input)
        val classification = SrClassification(SrType.NEW_FEATURE, emptyList(), 100, "")
        val decisions = ArchitectureDecisionExtractor.extract(input)
        
        val deficiencies = DeficiencyDetector.detect(input, quality, classification, decisions)
        val ambiguity = deficiencies.find { it.type == DeficiencyType.AMBIGUITY && it.field == "페이징 방식" }
        assertNotNull(ambiguity)
        assertEquals(DeficiencySeverity.BLOCKING, ambiguity?.severity)
    }
}
