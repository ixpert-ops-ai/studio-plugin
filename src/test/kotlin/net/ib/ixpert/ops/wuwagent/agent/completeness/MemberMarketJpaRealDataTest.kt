package net.ib.ixpert.ops.wuwagent.agent.completeness

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*
import net.ib.ixpert.ops.wuwagent.agent.completeness.model.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import java.io.File

class MemberMarketJpaRealDataTest {

    private lateinit var ctx: GraphMatchContext
    private val graphJsonPath = "C:/Workspace/member-market/.meta/project-graph.json"

    @Before
    fun setup() {
        val graphFile = File(graphJsonPath)
        assumeTrue("Skipping real graph tests because the graph file does not exist", graphFile.exists())
        val graph = com.google.gson.Gson().fromJson(graphFile.readText(), ProjectGraph::class.java)
        ctx = ProjectGraphAdapter(graph)
    }

    @Test
    fun `test Product complete family validation`() {
        // Product domain has Controller, Service, Repository, Entity
        val selectedFiles = setOf(
            "member-market-api/src/main/java/com/membermarket/api/product/ProductController.java",
            "member-market-api/src/main/java/com/membermarket/api/product/ProductService.java",
            "member-market-api/src/main/java/com/membermarket/domain/product/ProductRepository.java",
            "member-market-api/src/main/java/com/membermarket/domain/product/Product.java"
        )
        
        val report = CompletenessEngine(ctx).evaluate(FrameworkType.SPRING_BOOT_JPA, selectedFiles, SrFacts(
            hasUserAction = true, readsOrWritesData = true, hasBusinessLogic = true, touchesUi = false, addsNewMethod = true
        ))

        println("=== Violations ===")
        report.roleViolations.forEach { println("RoleViolation: \$it") }
        report.companionViolations.forEach { println("CompanionViolation: \$it") }
        
        val outcome = CompletenessGuard.check(report)
        println("Outcome: \$outcome")

        assertTrue("Product family should be complete and pass", outcome is GuardOutcome.Pass)
    }
    
    @Test
    fun `test Product missing Service ghost block defense`() {
        // Missing ProductService.java
        val selectedFiles = setOf(
            "member-market-api/src/main/java/com/membermarket/api/product/ProductController.java",
            "member-market-api/src/main/java/com/membermarket/domain/product/ProductRepository.java",
            "member-market-api/src/main/java/com/membermarket/domain/product/Product.java"
        )
        
        val report = CompletenessEngine(ctx).evaluate(FrameworkType.SPRING_BOOT_JPA, selectedFiles, SrFacts(
            hasUserAction = true, readsOrWritesData = true, hasBusinessLogic = true, touchesUi = false, addsNewMethod = true
        ))

        println("=== Violations ===")
        report.roleViolations.forEach { println("RoleViolation: \$it") }
        report.companionViolations.forEach { println("CompanionViolation: \$it") }
        
        val outcome = CompletenessGuard.check(report)
        println("Outcome: \$outcome")

        assertTrue("Outcome should be Block due to missing BUSINESS role", outcome is GuardOutcome.Block)
        val hasBusinessViolation = report.roleViolations.any { it.role == ArchRole.BUSINESS }
        assertTrue("Should detect missing BUSINESS role", hasBusinessViolation)
    }
    @Test
    fun `test ProductRepository only passes and enforces no companions`() {
        // Only Repository is provided. No Entity, no Impl, no XML.
        val selectedFiles = setOf(
            "member-market-api/src/main/java/com/membermarket/domain/product/ProductRepository.java"
        )
        
        // SrFacts should only require PERSISTENCE
        val report = CompletenessEngine(ctx).evaluate(FrameworkType.SPRING_BOOT_JPA, selectedFiles, SrFacts(
            hasUserAction = false, readsOrWritesData = true, hasBusinessLogic = false, touchesUi = false, addsNewMethod = true
        ))

        val outcome = CompletenessGuard.check(report)
        // If it demanded an XML or Impl, it would Block.
        // We assert it Passes, proving companions are correctly emptyList().
        assertTrue("Repository alone should Pass with no forced companions", outcome is GuardOutcome.Pass)
        assertEquals("There should be no companion violations for JPA Repository", 0, report.companionViolations.size)
    }

    @Test
    fun `test ProductEntity missing RESPONSE_DTO passes because RECOMMENDED is non-blocking`() {
        // Only Entity is provided. The DTO is missing.
        val selectedFiles = setOf(
            "member-market-api/src/main/java/com/membermarket/domain/product/Product.java"
        )
        
        val report = CompletenessEngine(ctx).evaluate(FrameworkType.SPRING_BOOT_JPA, selectedFiles, SrFacts(
            hasUserAction = false, readsOrWritesData = false, hasBusinessLogic = false, touchesUi = false, addsNewMethod = true
        ))

        println("=== Violations ===")
        report.roleViolations.forEach { println("RoleViolation: \$it") }
        report.companionViolations.forEach { println("CompanionViolation: \$it") }

        val outcome = CompletenessGuard.check(report)
        // Ensure that a RECOMMENDED companion violation does NOT cause a Block
        assertTrue("Outcome should be Pass because RECOMMENDED missing companions are non-blocking", outcome is GuardOutcome.Pass)
        
        val hasDtoFinding = report.companionFindings.any { it.companionKind == FileKind.RESPONSE_DTO }
        assertTrue("Should detect missing RESPONSE_DTO companion finding (RECOMMENDED)", hasDtoFinding)
        assertEquals("There should be no companion VIOLATIONS because it's RECOMMENDED", 0, report.companionViolations.size)
    }
}
