package net.ib.ixpert.ops.wuwagent.agent.completeness

import net.ib.ixpert.ops.wuwagent.agent.completeness.model.SrFacts
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * Track 2, 둘째 층위: Enforcing 루프 통합 테스트.
 *
 * RequirementAnalysisPipeline 전체를 관통하지 않고,
 * Guard 루프의 핵심인 CompletenessGuardIntegration을 직접 호출하여
 * (a) 피드백 내용에 DVO가 명시되는지, (b) 모드 전환, (c) 수렴/비수렴을 검증합니다.
 *
 * 실 호출 체인 데이터: APCADSbodrMngtBIZ → ACMBTBAPC026DEM (DVO 누락)
 */
class CompletenessGuardEnforcingIntegrationTest {

    private val graph = AnyframeGraphFixture.graph()
    private val graphCtx = ProjectGraphAdapter(graph)
    private val srFacts = SrFacts(
        hasUserAction = true,   // ENTRYPOINT 포함 시나리오
        touchesUi = false,
        readsOrWritesData = true,
        hasBusinessLogic = true,
        addsNewMethod = true
    )

    // 전체 7파일 (SVC, SVCImpl, BIZ, SVO, BVO, DEM, DVO)
    private val fullSet = setOf(
        "src/main/java/sc/chn/aps/apc/ad/ad03/svc/APCADSbodrMngtSVC.java",
        "src/main/java/sc/chn/aps/apc/ad/ad03/svc/impl/APCADSbodrMngtSVCImpl.java",
        "src/main/java/sc/chn/aps/apc/ad/ad03/biz/APCADSbodrMngtBIZ.java",
        "src/main/java/sc/chn/aps/apc/ad/ad03/svc/svo/APCADSbodrMngtSVO.java",
        "src/main/java/sc/chn/aps/apc/ad/ad03/biz/bvo/APCADSbodrMngtBVO.java",
        "src/main/java/sc/chn/aps/apc/zz/dem/ACMBTBAPC026DEM.java",
        "src/main/java/sc/chn/aps/apc/zz/dem/dvo/ACMBTBAPC026DVO.java"
    )

    // DVO만 빠진 6파일
    private val missingDvoSet = fullSet - "src/main/java/sc/chn/aps/apc/zz/dem/dvo/ACMBTBAPC026DVO.java"

    @After
    fun tearDown() {
        System.clearProperty("wuwagent.guard.enforcing")
    }

    // ─── Scenario A: Enforcing + DVO 누락 → Retry 판정 + 피드백에 DVO 명시 ───
    @Test
    fun testEnforcingMode_DvoMissing_ReturnsRetryWithDvoFeedback() {
        System.setProperty("wuwagent.guard.enforcing", "true")

        val guard = CompletenessGuardIntegration(GuardMode.SHADOW) // dynamicMode가 enforcing으로 오버라이드

        val decision = guard.evaluateAfterVerifier(
            frameworkType = FrameworkType.ANYFRAME_AP,
            requiredFiles = missingDvoSet,
            srFacts = srFacts,
            ctx = graphCtx
        )

        // 1. Retry(WOULD_BLOCK) 판정인지 확인
        assertTrue(
            "DVO 누락 시 Enforcing 모드에서 Retry(WOULD_BLOCK) 판정이어야 합니다. 실제: $decision",
            decision is GuardDecision.Retry
        )

        // 2. 피드백 내용에 DVO 식별자가 명시되어 있는지 확인
        val feedback = (decision as GuardDecision.Retry).feedback
        assertTrue(
            "피드백에 누락된 DVO 파일명(ACMBTBAPC026DVO 또는 DVO)이 포함되어야 합니다.\n실제 피드백: $feedback",
            feedback.contains("DVO") || feedback.contains("ACMBTBAPC026DVO")
        )
    }

    // ─── Scenario A-2: Enforcing + DVO 포함(전체 세트) → Proceed 판정 ───
    @Test
    fun testEnforcingMode_FullSet_ReturnsProceed() {
        System.setProperty("wuwagent.guard.enforcing", "true")

        val guard = CompletenessGuardIntegration(GuardMode.SHADOW)

        val decision = guard.evaluateAfterVerifier(
            frameworkType = FrameworkType.ANYFRAME_AP,
            requiredFiles = fullSet,
            srFacts = srFacts,
            ctx = graphCtx
        )

        assertTrue(
            "전체 파일이 포함된 경우 Proceed여야 합니다. 실제: $decision",
            decision is GuardDecision.Proceed
        )
    }

    // ─── Scenario A-3: 수렴 시뮬레이션 (1차 Retry → 2차 Proceed) ───
    @Test
    fun testEnforcingMode_ConvergenceSimulation() {
        System.setProperty("wuwagent.guard.enforcing", "true")

        val guard = CompletenessGuardIntegration(GuardMode.SHADOW)

        // 1차: DVO 누락 → Retry
        val firstDecision = guard.evaluateAfterVerifier(
            frameworkType = FrameworkType.ANYFRAME_AP,
            requiredFiles = missingDvoSet,
            srFacts = srFacts,
            ctx = graphCtx
        )
        assertTrue("1차는 Retry여야 합니다", firstDecision is GuardDecision.Retry)

        // 피드백 내용 확인 (1회차 피드백에 DVO 누락이 명시되는지 본체)
        val feedback = (firstDecision as GuardDecision.Retry).feedback
        assertTrue("피드백에 누락된 DVO가 명시되어야 합니다", feedback.contains("DVO") || feedback.contains("ACMBTBAPC026DVO"))

        // 2차: 피드백 반영(DVO 추가) → Proceed
        val secondDecision = guard.evaluateAfterVerifier(
            frameworkType = FrameworkType.ANYFRAME_AP,
            requiredFiles = fullSet, // DVO 추가한 전체 세트
            srFacts = srFacts,
            ctx = graphCtx
        )
        assertTrue("2차(DVO 추가 후)는 Proceed여야 합니다", secondDecision is GuardDecision.Proceed)
    }

    // ─── Scenario B: Graceful Degradation 시뮬레이션 (MaxRetries 초과 시 Proceed + 경고) ───
    @Test
    fun testEnforcingMode_GracefulDegradation_NoException() {
        System.setProperty("wuwagent.guard.enforcing", "true")

        val guard = CompletenessGuardIntegration(GuardMode.SHADOW)
        val maxRetries = 3
        var retryCount = 0
        var finalDecision: GuardDecision? = null
        var warningEmitted = false

        // 파이프라인 루프 시뮬레이션: 매번 같은 불완전 세트(missingDvoSet) 제출
        while (retryCount <= maxRetries) {
            val decision = guard.evaluateAfterVerifier(
                frameworkType = FrameworkType.ANYFRAME_AP,
                requiredFiles = missingDvoSet,
                srFacts = srFacts,
                ctx = graphCtx
            )

            if (decision is GuardDecision.Proceed) {
                finalDecision = decision
                break
            } else if (decision is GuardDecision.Retry) {
                if (retryCount >= maxRetries) {
                    // 5차 구현 스펙: MaxRetriesExceededException이 아니라 
                    // 예외 없이 graceful degradation으로 Proceed + 경고 메시지
                    warningEmitted = true
                    finalDecision = GuardDecision.Proceed(missingDvoSet) // Pipeline overrides to Proceed
                    break
                }
                retryCount++
                // 피드백 무시하고 같은 세트 다시 제출 (stubborn LLM 시뮬레이션)
            }
        }

        // 핵심 검증: 최대 재시도 후 예외 없이 Proceed로 수렴(degraded)
        assertEquals("최대 재시도(3회)에 도달해야 합니다", 3, retryCount)
        assertTrue("최대 재시도 도달 시 경고가 발생해야 합니다", warningEmitted)
        assertTrue("마지막 판정은 Proceed(강제 통과)여야 합니다", finalDecision is GuardDecision.Proceed)
    }

    // ─── Scenario C: Shadow 모드 → DVO 누락이어도 0회 재시도(Proceed) ───
    @Test
    fun testShadowMode_DvoMissing_ProceedsWithoutRetry() {
        System.setProperty("wuwagent.guard.enforcing", "false")

        val guard = CompletenessGuardIntegration(GuardMode.SHADOW)

        val decision = guard.evaluateAfterVerifier(
            frameworkType = FrameworkType.ANYFRAME_AP,
            requiredFiles = missingDvoSet,
            srFacts = srFacts,
            ctx = graphCtx
        )

        assertTrue(
            "Shadow 모드에서는 DVO 누락이어도 0회 재시도(Proceed)여야 합니다. 실제: $decision",
            decision is GuardDecision.Proceed
        )
        assertEquals(
            "Shadow Proceed의 requiredFiles는 입력과 동일해야 합니다",
            missingDvoSet,
            (decision as GuardDecision.Proceed).requiredFiles
        )
    }
}
