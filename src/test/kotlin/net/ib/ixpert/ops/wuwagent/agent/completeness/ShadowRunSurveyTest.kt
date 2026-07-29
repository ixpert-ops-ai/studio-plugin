package net.ib.ixpert.ops.wuwagent.agent.completeness

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*
import net.ib.ixpert.ops.wuwagent.agent.completeness.model.*
import org.junit.Test
import org.junit.Assert.assertTrue

class ShadowRunSurveyTest {

    @Test
    fun `test live survey CSV export SR without mapper XML`() {
        val graph = SurveyGraphFixture.graph()
        val ctx = ProjectGraphAdapter(graph)
        val guardIntegration = CompletenessGuardIntegration(GuardMode.SHADOW)
        
        // The 6 files the planner selected (omitting SurveyDao.xml)
        val selectedFiles = setOf(
            "src/main/java/net/infobank/iss/controller/IpsController.java",
            "src/main/java/net/infobank/iss/survey/service/SurveyService.java",
            "src/main/java/net/infobank/iss/survey/service/SurveyServiceImpl.java",
            "src/main/java/net/infobank/iss/survey/dao/SurveyDao.java",
            "src/main/java/net/infobank/iss/survey/dao/SurveyDaoImpl.java",
            "src/main/webapp/resources/js/survey/survey.list.js"
        )
        
        // We run the engine and guard directly to test the invariant (ghost block fix)
        val report = CompletenessEngine(ctx).evaluate(FrameworkType.SPRING_MVC_MYBATIS, selectedFiles, SrFacts(
            hasUserAction = true,
            readsOrWritesData = true,
            hasBusinessLogic = true,
            touchesUi = true,
            addsNewMethod = true
        ))
        val outcome = CompletenessGuard.check(report)
        
        // Assert the ghost block fix: If it WOULD_BLOCK, violations MUST NOT be empty
        assertTrue("Outcome should be Block because XML companion is missing", outcome is GuardOutcome.Block)
        
        val totalViolationsCount = report.companionViolations.size + report.roleViolations.size
        assertTrue(
            "Ghost Block check: If outcome is Block, there must be at least one violation recorded. Count: $totalViolationsCount", 
            totalViolationsCount > 0
        )
        
        // Specifically, it should find the CompanionMissing for MYBATIS_XML
        val hasXmlViolation = report.companionViolations.any { it.companionKind == FileKind.MYBATIS_XML }
        assertTrue("Should detect missing MYBATIS_XML companion", hasXmlViolation)
        
        // Also run the integration layer to ensure it doesn't crash
        val decision = guardIntegration.evaluateAfterVerifier(
            frameworkType = FrameworkType.SPRING_MVC_MYBATIS,
            requiredFiles = selectedFiles,
            srFacts = SrFacts(
                hasUserAction = true,
                readsOrWritesData = true,
                hasBusinessLogic = true,
                touchesUi = true,
                addsNewMethod = true
            ),
            ctx = ctx,
            projectRoot = "C:/fake/path",
            srKey = "LIVE-SR-SURVEY"
        )
        
        assertTrue("In SHADOW mode, it should always Proceed", decision is GuardDecision.Proceed)
    }
}
