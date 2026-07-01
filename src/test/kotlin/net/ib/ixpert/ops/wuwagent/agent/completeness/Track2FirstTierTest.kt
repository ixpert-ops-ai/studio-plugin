package net.ib.ixpert.ops.wuwagent.agent.completeness

import net.ib.ixpert.ops.wuwagent.agent.completeness.model.*
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Track2FirstTierTest {

    @Test
    fun runFirstTierCases() {
        val projectGraph = AnyframeGraphFixture.graph()
        val ctx = ProjectGraphAdapter(projectGraph)
        val engine = CompletenessEngine(ctx)

        // CASE 1: Normal Complete Case (PROCEED)
        val case1Files = setOf(
            AnyframeGraphFixture.svc.path,
            AnyframeGraphFixture.svcImpl.path,
            AnyframeGraphFixture.biz.path,
            AnyframeGraphFixture.svo.path,
            AnyframeGraphFixture.bvo.path,
            AnyframeGraphFixture.dem.path,
            AnyframeGraphFixture.dvo.path
        )
        val sr1 = SrFacts(hasUserAction = true, readsOrWritesData = true, hasBusinessLogic = true, touchesUi = true, addsNewMethod = true)
        val report1 = engine.evaluate(projectGraph.frameworkType, case1Files, sr1)
        val outcome1 = CompletenessGuard.check(report1)
        println("=== CASE 1 (Normal) ===")
        println("Outcome: $outcome1")
        println("Companion Violations: ${report1.companionViolations}")
        assertTrue("Expected pure Pass (no unclassified warnings since graph is complete)", outcome1 is GuardOutcome.Pass)
        assertTrue("Companion violations should be empty", report1.companionViolations.isEmpty())

        // CASE 2: Missing DVO Case (WOULD_BLOCK)
        val case2Files = case1Files - AnyframeGraphFixture.dvo.path
        val sr2 = sr1.copy()
        val report2 = engine.evaluate(projectGraph.frameworkType, case2Files, sr2)
        val outcome2 = CompletenessGuard.check(report2)
        println("\n=== CASE 2 (Missing DVO) ===")
        println("Outcome: $outcome2")
        println("Companion Violations: ${report2.companionViolations}")
        assertTrue("Expected Block", outcome2 is GuardOutcome.Block)
        assertTrue("Should have exactly 1 DVO violation", report2.companionViolations.count { it.companionKind.name == "DVO" } == 1)

        // CASE 3: BIZ-only Case (PROCEED)
        // BIZ-only 체인 노드들 사용 (DEM/DQM 의존성 없음)
        val case3Files = setOf(
            AnyframeGraphFixture.bizOnlySvc.path,
            AnyframeGraphFixture.bizOnlySvcImpl.path,
            AnyframeGraphFixture.bizOnlyBiz.path,
            AnyframeGraphFixture.bizOnlySvo.path,
            AnyframeGraphFixture.bizOnlyBvo.path
        )
        val sr3 = SrFacts(hasUserAction = true, readsOrWritesData = false, hasBusinessLogic = true, touchesUi = false, addsNewMethod = true)
        val report3 = engine.evaluate(projectGraph.frameworkType, case3Files, sr3)
        val outcome3 = CompletenessGuard.check(report3)
        println("\n=== CASE 3 (BIZ-only) ===")
        println("Outcome: $outcome3")
        println("Companion Violations: ${report3.companionViolations}")
        assertTrue("Expected pure Pass", outcome3 is GuardOutcome.Pass)
        assertTrue("Should not have DVO violation", report3.companionViolations.none { it.companionKind.name == "DVO" })
    }
}
