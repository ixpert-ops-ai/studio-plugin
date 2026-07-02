package net.ib.ixpert.ops.wuwagent.agent.stage0

import org.junit.Assert.*
import org.junit.Test

class Stage0PipelineIntegrationTest {

    private val provider: () -> String = { "메타 그래프 요약" }

    @Test
    fun testClearSrPassesImmediately() {
        val pipeline = Stage0Pipeline(provider)
        val input = SrInput(
            srId = "1",
            title = "필드 추가",
            description = "src/main/java/User.java 파일에 address 필드를 추가해야 합니다. 기대 동작: 주소가 표시되어야 한다. 길이는 충분히 길게 작성합니다. 50자가 넘도록 계속 작성합니다."
        )

        val output = pipeline.startAnalysis(input)
        
        // Blocking 결함이 없으므로 바로 진행 가능해야 함
        assertTrue(output.gateEvaluation.canProceed)
        assertNotNull(output.gateEvaluation.enrichedSr)
    }

    @Test
    fun testAmbiguousSrRequiresAnswers() {
        val pipeline = Stage0Pipeline(provider)
        // 페이징 방식 트리거
        val input = SrInput(
            srId = "2",
            title = "목록 페이징 적용",
            description = "사용자 목록에 페이징을 적용해야 합니다. 기대 동작: 10건씩 표시되어야 한다. 설명 길이 50자 넘게 채웁니다. 충분히 길게 씁니다."
        )

        val initialOutput = pipeline.startAnalysis(input)
        
        // 아키텍처 결정 미해소로 진행 불가
        assertFalse(initialOutput.gateEvaluation.canProceed)
        val archQuestion = initialOutput.gateEvaluation.unresolvedQuestions.find { it.relatedDeficiency?.type == DeficiencyType.AMBIGUITY }
        assertNotNull(archQuestion)

        // 답변 제출
        val answers = mapOf(archQuestion!! to "1번 Offset 기반 유지로 합니다.")
        val secondEval = pipeline.submitAnswers(answers)

        assertTrue(secondEval.canProceed)
    }

    @Test
    fun testEvasiveAnswerKeepsBlocked() {
        val pipeline = Stage0Pipeline(provider)
        val input = SrInput(
            srId = "3",
            title = "신규 기능",
            description = "신규 화면 개발." // 50자 미만, 기대동작 없음 -> OMISSION
        )

        val initialOutput = pipeline.startAnalysis(input)
        assertFalse(initialOutput.gateEvaluation.canProceed)

        val questions = initialOutput.gateEvaluation.unresolvedQuestions
        val answers = questions.associateWith { "잘 모르겠습니다" }
        
        val secondEval = pipeline.submitAnswers(answers)
        assertFalse(secondEval.canProceed) // 회피성 답변으로 여전히 진행 불가
    }
}
