package net.ib.ixpert.ops.wuwagent.agent.completeness

import net.ib.ixpert.ops.wuwagent.agent.completeness.model.*
import net.ib.ixpert.ops.wuwagent.agent.completeness.rulesets.AnyframeApRuleset
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*
import org.junit.Assert.*
import org.junit.Test

class AnyframeApRulesetSpecTest {

    private fun createNode(name: String, depends: List<String> = emptyList()): FileNode {
        return FileNode(
            path = "src/main/java/$name.java",
            packageName = "net.infobank.iss",
            className = name,
            fileType = SpringFileType.SERVICE, // generic
            layer = ArchitectureLayer.UNKNOWN,
            isInterface = name.endsWith("SVC"),
            dependsOn = depends.toMutableList()
        )
    }

    private fun buildCtx(files: List<FileNode>): ProjectGraphAdapter {
        return ProjectGraphAdapter(
            ProjectGraph(
                frameworkType = FrameworkType.ANYFRAME_AP,
                generatedAt = "2026-06-29T00:00:00Z",
                projectRoot = "C:/fake/path",
                files = files.associateBy { it.path },
                resourceNodes = emptyList(),
                relationships = emptyList(),
                statistics = GraphStatistics()
            )
        )
    }

    @Test
    fun `test SVCImpl missing SVO is blocked`() {
        val svc = createNode("APCMMPsnzInfSVC")
        val svcImpl = createNode("APCMMPsnzInfSVCImpl")
        val ctx = buildCtx(listOf(svc, svcImpl))
        val engine = CompletenessEngine(ctx)
        
        val evaluation = engine.evaluate(
            frameworkType = FrameworkType.ANYFRAME_AP,
            requiredFiles = setOf(svcImpl.path, svc.path),
            sr = SrFacts(hasUserAction=true, readsOrWritesData=false, hasBusinessLogic=false, touchesUi=false, addsNewMethod=true)
        )

        assertTrue(evaluation.companionViolations.any { it.companionKind == FileKind.SVO })
    }

    @Test
    fun `test BIZ to DEM chain missing DVO dependency is blocked`() {
        val dem = createNode("ACAMTBAPC001DEM")
        val dvo = createNode("ACAMTBAPC001DVO") // DVO exists in the graph
        val biz = createNode("APCSPSspySpacdRgUswyCardCtfBIZ", depends = listOf(dem.path))
        val bvo = createNode("APCSPSspySpacdRgUswyCardCtfBVO")
        val svc = createNode("APCMMPsnzInfSVC")
        val svcImpl = createNode("APCMMPsnzInfSVCImpl", depends = listOf(biz.path))
        val svo = createNode("APCMMPsnzInfSVO")

        val ctx = buildCtx(listOf(dem, dvo, biz, bvo, svc, svcImpl, svo))
        val engine = CompletenessEngine(ctx)
        
        val evaluation = engine.evaluate(
            frameworkType = FrameworkType.ANYFRAME_AP,
            requiredFiles = setOf(biz.path, svcImpl.path, svc.path), 
            sr = SrFacts(hasUserAction=true, readsOrWritesData=true, hasBusinessLogic=true, touchesUi=false, addsNewMethod=true)
        )

        val violations = evaluation.companionViolations.map { it.companionKind }
        assertTrue("Expected DVO violation but got $violations", violations.contains(FileKind.DVO))
    }

    @Test
    fun `test BIZ-only chain missing DVO is proceeded`() {
        val biz = createNode("APCSPSspySpacdRgUswyCardCtfBIZ", depends = emptyList())
        val bvo = createNode("APCSPSspySpacdRgUswyCardCtfBVO")
        val svc = createNode("APCMMPsnzInfSVC")
        val svcImpl = createNode("APCMMPsnzInfSVCImpl", depends = listOf(biz.path))
        val svo = createNode("APCMMPsnzInfSVO")

        val ctx = buildCtx(listOf(biz, bvo, svc, svcImpl, svo))
        val engine = CompletenessEngine(ctx)
        
        val evaluation = engine.evaluate(
            frameworkType = FrameworkType.ANYFRAME_AP,
            requiredFiles = setOf(biz.path, svcImpl.path, svc.path, bvo.path, svo.path),
            sr = SrFacts(hasUserAction=true, readsOrWritesData=false, hasBusinessLogic=true, touchesUi=false, addsNewMethod=true)
        )

        val isBlocked = evaluation.isBlocked
        assertFalse("Should not be blocked. RoleViolations: ${evaluation.roleViolations}, CompanionViolations: ${evaluation.companionViolations}", isBlocked)
        assertFalse(evaluation.companionViolations.any { it.companionKind == FileKind.DVO })
    }

    @Test
    fun `test BIZ-to-UTIL chain missing DVO is proceeded`() {
        // BIZ depends on a UTIL class (UNKNOWN role). It should NOT require DVO.
        val util = createNode("APCCmnConstantUtil")
        val biz = createNode("APCMMOacdCardInfStsBIZ", depends = listOf(util.path))
        val bvo = createNode("APCMMOacdCardInfStsBVO")
        val svc = createNode("APCMMPsnzInfSVC")
        val svcImpl = createNode("APCMMPsnzInfSVCImpl", depends = listOf(biz.path))
        val svo = createNode("APCMMPsnzInfSVO")

        val ctx = buildCtx(listOf(util, biz, bvo, svc, svcImpl, svo))
        val engine = CompletenessEngine(ctx)
        
        val evaluation = engine.evaluate(
            frameworkType = FrameworkType.ANYFRAME_AP,
            requiredFiles = setOf(biz.path, svcImpl.path, svc.path, bvo.path, svo.path),
            sr = SrFacts(hasUserAction=true, readsOrWritesData=false, hasBusinessLogic=true, touchesUi=false, addsNewMethod=true)
        )

        val isBlocked = evaluation.isBlocked
        assertFalse("Should not be blocked for UTIL dependency. RoleViolations: ${evaluation.roleViolations}, CompanionViolations: ${evaluation.companionViolations}", isBlocked)
        assertFalse(evaluation.companionViolations.any { it.companionKind == FileKind.DVO })
    }

    @Test
    fun `test mm16 Type 1 Normal Complete Case`() {
        val dem = createNode("ACMBTBAPC033DEM")
        val dvo = createNode("ACMBTBAPC033DVO")
        val biz = createNode("APCMMOpnApiO011BIZ", depends = listOf(dem.path))
        val bvo = createNode("APCMMOpnApiO011BVO")
        val svo = createNode("APCMMOpnApiO011SVO")
        val svc = createNode("APCMMOpnApiO011SVC")
        val svcImpl = createNode("APCMMOpnApiO011SVCImpl", depends = listOf(biz.path))

        val ctx = buildCtx(listOf(dem, dvo, biz, bvo, svo, svc, svcImpl))
        val engine = CompletenessEngine(ctx)
        
        val evaluation = engine.evaluate(
            frameworkType = FrameworkType.ANYFRAME_AP,
            requiredFiles = setOf(dem.path, dvo.path, biz.path, bvo.path, svo.path, svc.path, svcImpl.path),
            sr = SrFacts(hasUserAction=true, readsOrWritesData=true, hasBusinessLogic=true, touchesUi=false, addsNewMethod=true)
        )
        assertFalse("Type 1 should PROCEED but got blocked: ${evaluation.companionViolations}", evaluation.isBlocked)
    }

    @Test
    fun `test mm16 Type 2 Intentional Missing Case`() {
        val dem = createNode("ACMBTBAPC030DEM")
        // MISSING DVO: val dvo = createNode("ACMBTBAPC030DVO")
        val biz = createNode("APCMMOpnApiO012BIZ", depends = listOf(dem.path))
        val bvo = createNode("APCMMOpnApiO012BVO")
        val svo = createNode("APCMMOpnApiO012SVO")
        val svc = createNode("APCMMOpnApiO012SVC")
        val svcImpl = createNode("APCMMOpnApiO012SVCImpl", depends = listOf(biz.path))

        val ctx = buildCtx(listOf(dem, biz, bvo, svo, svc, svcImpl)) // DVO is missing in graph or requiredFiles
        val engine = CompletenessEngine(ctx)
        
        val evaluation = engine.evaluate(
            frameworkType = FrameworkType.ANYFRAME_AP,
            requiredFiles = setOf(dem.path, biz.path, bvo.path, svo.path, svc.path, svcImpl.path), // User didn't include DVO
            sr = SrFacts(hasUserAction=true, readsOrWritesData=true, hasBusinessLogic=true, touchesUi=false, addsNewMethod=true)
        )
        assertTrue("Type 2 should WOULD_BLOCK", evaluation.isBlocked)
        assertTrue(evaluation.companionViolations.any { it.companionKind == FileKind.DVO })
    }

    @Test
    fun `test mm16 Type 3 BIZ-only Case`() {
        val util = createNode("APCCmnConstantUtil")
        val biz = createNode("APCMMOacdCardInfStsBIZ", depends = listOf(util.path))
        val bvo = createNode("APCMMOacdCardInfStsBVO")
        val svo = createNode("APCMMOacdCardInfStsSVO")
        val svc = createNode("APCMMOacdCardInfStsSVC")
        val svcImpl = createNode("APCMMOacdCardInfStsSVCImpl", depends = listOf(biz.path))

        val ctx = buildCtx(listOf(util, biz, bvo, svo, svc, svcImpl))
        val engine = CompletenessEngine(ctx)
        
        val evaluation = engine.evaluate(
            frameworkType = FrameworkType.ANYFRAME_AP,
            requiredFiles = setOf(biz.path, bvo.path, svo.path, svc.path, svcImpl.path),
            sr = SrFacts(hasUserAction=true, readsOrWritesData=false, hasBusinessLogic=true, touchesUi=false, addsNewMethod=true)
        )
        assertFalse("Type 3 should PROCEED but got blocked: ${evaluation.companionViolations}", evaluation.isBlocked)
    }
}
