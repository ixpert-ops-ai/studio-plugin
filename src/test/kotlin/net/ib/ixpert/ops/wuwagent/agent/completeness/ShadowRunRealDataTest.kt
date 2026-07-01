package net.ib.ixpert.ops.wuwagent.agent.completeness

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*
import net.ib.ixpert.ops.wuwagent.agent.completeness.model.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import java.io.File

class ShadowRunRealDataTest {

    private lateinit var ctx: GraphMatchContext
    private val graphJsonPath = "C:/Workspace/HC_card_survey_admin/.meta/project-graph.json"

    @Before
    fun setUp() {
        val graphFile = File(graphJsonPath)
        assumeTrue("Skipping real graph tests because the graph file does not exist", graphFile.exists())
        val graph = com.google.gson.Gson().fromJson(graphFile.readText(), ProjectGraph::class.java)
        ctx = ProjectGraphAdapter(graph)
    }

    @Test
    fun `test PASS case with all files including XML`() {
        val selectedFiles = setOf(
            "survey_admin/src/main/java/net/infobank/iss/controller/IpsController.java",
            "survey_admin/src/main/java/net/infobank/iss/survey/dao/SurveyDao.java",
            "survey_admin/src/main/java/net/infobank/iss/survey/dao/SurveyDaoImpl.java",
            "survey_admin/src/main/java/net/infobank/iss/sql/mysql/sql_survey.xml"
        )
        
        // We set touchesUi=false and hasBusinessLogic=false so it only requires CONTROLLER and DAO, which we know exist and are classified.
        val report = CompletenessEngine(ctx).evaluate(FrameworkType.SPRING_MVC_MYBATIS, selectedFiles, SrFacts(
            hasUserAction = true, readsOrWritesData = true, hasBusinessLogic = false, touchesUi = false, addsNewMethod = true
        ))
        
        val outcome = CompletenessGuard.check(report)
        assertTrue("Outcome should be Pass because all required roles and companions are present", outcome is GuardOutcome.Pass)
        assertEquals("There should be no violations", 0, report.companionViolations.size + report.roleViolations.size)
    }

    @Test
    fun `test addsNewMethod=false case omits XML requirement`() {
        // Same as PASS case, but we intentionally omit XML
        val selectedFiles = setOf(
            "survey_admin/src/main/java/net/infobank/iss/controller/IpsController.java",
            "survey_admin/src/main/java/net/infobank/iss/survey/dao/SurveyDao.java",
            "survey_admin/src/main/java/net/infobank/iss/survey/dao/SurveyDaoImpl.java"
            // NO XML!
        )
        
        // addsNewMethod = false
        val report = CompletenessEngine(ctx).evaluate(FrameworkType.SPRING_MVC_MYBATIS, selectedFiles, SrFacts(
            hasUserAction = true, readsOrWritesData = true, hasBusinessLogic = false, touchesUi = false, addsNewMethod = false
        ))
        
        val outcome = CompletenessGuard.check(report)
        // [DOCUMENTING CURRENT BEHAVIOR]
        // Currently, MYBATIS_XML requires CompanionTrigger.ON_ANY_CHANGE.
        // Even if addsNewMethod=false, it demands the XML file. 
        // This causes an outcome of Block, which we believe may be a "File-level Over-Enforcement FP"
        // for cases where only DAO comments or simple logic was changed. We are putting this in SHADOW mode
        // specifically to measure how often this FP occurs before deciding whether to switch to ON_NEW_METHOD.
        assertTrue("Outcome should be Block because ON_ANY_CHANGE strictly demands XML even if addsNewMethod=false", outcome is GuardOutcome.Block)
        val hasXmlViolation = report.companionViolations.any { it.companionKind == FileKind.MYBATIS_XML }
        assertTrue("Should detect missing MYBATIS_XML companion", hasXmlViolation)
    }

    @Test
    fun `test RoleMissing standalone ghost block case`() {
        // We include DAO and XML (companions are perfect), but intentionally OMIT Controller
        val selectedFiles = setOf(
            "survey_admin/src/main/java/net/infobank/iss/survey/dao/SurveyDao.java",
            "survey_admin/src/main/java/net/infobank/iss/survey/dao/SurveyDaoImpl.java",
            "survey_admin/src/main/java/net/infobank/iss/sql/mysql/sql_survey.xml"
            // NO Controller!
        )
        
        val report = CompletenessEngine(ctx).evaluate(FrameworkType.SPRING_MVC_MYBATIS, selectedFiles, SrFacts(
            hasUserAction = true, readsOrWritesData = true, hasBusinessLogic = false, touchesUi = false, addsNewMethod = true
        ))
        
        val outcome = CompletenessGuard.check(report)
        assertTrue("Outcome should be Block because Controller (ENTRYPOINT) is missing", outcome is GuardOutcome.Block)
        
        // This is the standalone RoleMissing test! Companion violations are 0, but Role violations > 0.
        assertEquals("Companion violations should be 0", 0, report.companionViolations.size)
        val hasEntrypointViolation = report.roleViolations.any { it.role == ArchRole.ENTRYPOINT }
        assertTrue("Should detect missing ENTRYPOINT role", hasEntrypointViolation)
        
        val totalViolationsCount = report.companionViolations.size + report.roleViolations.size
        assertTrue("Ghost Block check: violations must not be empty if blocked", totalViolationsCount > 0)
    }
}
