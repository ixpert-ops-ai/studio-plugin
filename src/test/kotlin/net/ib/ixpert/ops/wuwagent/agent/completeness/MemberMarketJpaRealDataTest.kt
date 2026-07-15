package net.ib.ixpert.ops.wuwagent.agent.completeness

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*
import net.ib.ixpert.ops.wuwagent.agent.completeness.model.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
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

    @Test
    fun `test MemberRepository recommends multiple injected companions without duplicates`() {
        val selectedFiles = setOf(
            "member-market-api/src/main/java/com/membermarket/domain/member/MemberRepository.java"
        )
        
        val report = CompletenessEngine(ctx).evaluate(FrameworkType.SPRING_BOOT_JPA, selectedFiles, SrFacts(
            hasUserAction = false, readsOrWritesData = true, hasBusinessLogic = false, touchesUi = false, addsNewMethod = true
        ))

        val outcome = CompletenessGuard.check(report)
        assertTrue("Repository alone should Pass", outcome is GuardOutcome.Pass)
        
        val serviceCompanions = report.companionFindings.filter { it.companionKind == FileKind.SERVICE_IMPL }
        val controllerCompanions = report.companionFindings.filter { it.companionKind == FileKind.CONTROLLER }
        
        // Verify multiple returns (1:N)
        // User stated: 5 Services (Auth, Chat, Admin, Product, CustomUserDetails) and 1 Controller (MemberController)
        assertEquals("Should recommend 5 Services that inject MemberRepository", 5, serviceCompanions.size)
        assertEquals("Should recommend 1 Controller that injects MemberRepository", 1, controllerCompanions.size)
        
        // Verify Dedup: No duplicate files within findings
        val distinctFindings = report.companionFindings.distinctBy { it.companionKind to it.result.matchedPath }
        assertEquals("There should be no duplicate companion findings", distinctFindings.size, report.companionFindings.size)
    }

    @Test
    fun `test isolated Entity modifications trigger no False Positives`() {
        // Shop, ProductImage, Transaction have no Repository and no dependedBy
        val selectedFiles = setOf(
            "member-market-api/src/main/java/com/membermarket/domain/shop/Shop.java",
            "member-market-api/src/main/java/com/membermarket/domain/product/ProductImage.java",
            "member-market-api/src/main/java/com/membermarket/domain/transaction/Transaction.java"
        )
        
        val report = CompletenessEngine(ctx).evaluate(FrameworkType.SPRING_BOOT_JPA, selectedFiles, SrFacts(
            hasUserAction = false, readsOrWritesData = false, hasBusinessLogic = false, touchesUi = false, addsNewMethod = true
        ))

        println("=== Violations in Isolated Entity Test ===")
        report.roleViolations.forEach { println("RoleViolation: $it") }
        report.companionViolations.forEach { println("CompanionViolation: $it") }

        val outcome = CompletenessGuard.check(report)
        
        // Verify Negative Precision: SHOULD PASS gracefully without WOULD_BLOCK
        assertTrue("Isolated entities should Pass without WOULD_BLOCK", outcome is GuardOutcome.Pass)
        assertEquals("Should not have any MANDATORY companion violations (FP)", 0, report.companionViolations.size)
    }

    @Test
    fun `test ProductResponse exact path match for satisfied boolean`() {
        // SR-01 scenario: ProductResponse is included, but ProductListResponse is missing.
        // We simulate the evaluation where CompletenessEngine generates RECOMMENDATION for both,
        // and we verify that existsInRequiredSet strictly distinguishes them by exact path match.
        val selectedFiles = setOf(
            "member-market-api/src/main/java/com/membermarket/domain/product/Product.java",
            "member-market-api/src/main/java/com/membermarket/api/product/dto/ProductResponse.java"
            // Note: ProductListResponse is intentionally NOT in this set
        )
        
        val report = CompletenessEngine(ctx).evaluate(FrameworkType.SPRING_BOOT_JPA, selectedFiles, SrFacts(
            hasUserAction = true, readsOrWritesData = true, hasBusinessLogic = true, touchesUi = false, addsNewMethod = true
        ))

        // Find the companion findings for Product entity that point to RESPONSE_DTO
        val productFindings = report.companionFindings.filter { 
            it.anchorPath == "member-market-api/src/main/java/com/membermarket/domain/product/Product.java" &&
            it.companionKind == FileKind.RESPONSE_DTO
        }
        
        // We expect CompletenessEngine to recommend both DTOs, but only ProductResponse should be satisfied
        val productResponseRec = productFindings.find { it.result.matchedPath?.contains("ProductResponse") == true || it.result.note.contains("ProductResponse") }
        val productListResponseRec = productFindings.find { it.result.matchedPath?.contains("ProductListResponse") == true || it.result.note.contains("ProductListResponse") }
        
        println("=== Product Findings ===")
        productFindings.forEach { println(it) }
        
        assertTrue("ProductResponse recommendation should exist", productResponseRec != null)
        assertTrue("ProductListResponse recommendation should exist", productListResponseRec != null)
        
        assertTrue("ProductResponse must be satisfied=true because it is exactly in the required files", productResponseRec!!.existsInRequiredSet)
        assertFalse("ProductListResponse must be satisfied=false because it is NOT in the required files", productListResponseRec!!.existsInRequiredSet)
    }
    
    @Test
    fun `test SR01 shadow log simulation`() {
        val requiredFiles = setOf(
            "member-market-api/src/main/java/com/membermarket/domain/product/Product.java",
            "member-market-api/src/main/java/com/membermarket/api/product/dto/ProductResponse.java",
            "member-market-api/src/main/java/com/membermarket/api/product/dto/ProductCreateRequest.java",
            "member-market-api/src/main/java/com/membermarket/api/product/dto/ProductUpdateRequest.java",
            "member-market-api/src/main/java/com/membermarket/api/product/ProductService.java",
            "member-market-api/src/main/java/com/membermarket/api/product/ProductController.java",
            "member-market-web/src/views/product/ProductCreateView.vue",
            "member-market-web/src/views/product/ProductDetailView.vue",
            "member-market-web/src/views/product/ProductUpdateView.vue"
        )
        val srFacts = net.ib.ixpert.ops.wuwagent.agent.completeness.model.SrFacts(
            hasUserAction = true, touchesUi = true, hasBusinessLogic = true, readsOrWritesData = true, addsNewMethod = true
        )
        val engine = CompletenessEngine(ctx)
        val report = engine.evaluate(FrameworkType.SPRING_BOOT_JPA, requiredFiles, srFacts)
        
        // [Regression Test] Ensure anchors are strictly limited to requiredFiles.
        // ProductRepository is NOT in requiredFiles, so it should NOT be evaluated as an anchor.
        val anchorsInFindings = report.companionFindings.map { it.anchorPath }.toSet()
        assertTrue("Anchors must be restricted to requiredFiles only. Found extra anchors: ${anchorsInFindings - requiredFiles}", 
            requiredFiles.containsAll(anchorsInFindings))
        assertFalse("Repository was not in requiredFiles, so it must not be an anchor", 
            anchorsInFindings.any { it.contains("Repository") })
            
        val companionDetails = report.companionViolations.map { finding ->
            val rootCause = net.ib.ixpert.ops.wuwagent.agent.completeness.RootCauseAnalyzer.analyze(finding, ctx)
            val debtReason = net.ib.ixpert.ops.wuwagent.agent.completeness.KnownDebtClassifier.classify(finding, rootCause)
            net.ib.ixpert.ops.wuwagent.agent.completeness.model.ViolationDetail(
                type = "CompanionMissing", anchorFile = finding.anchorPath, missingTargetKind = finding.companionKind.name,
                rootCause = rootCause, isKnownDebt = debtReason != null, debtReason = debtReason,
                resolvedCategory = net.ib.ixpert.ops.wuwagent.agent.completeness.model.ResolvedCategory.UNRESOLVED
            )
        }
        val recommendations = report.companionFindings.filter { it.pairing == net.ib.ixpert.ops.wuwagent.agent.completeness.model.PairingStrength.RECOMMENDED }.map {
            net.ib.ixpert.ops.wuwagent.agent.completeness.model.CompanionRecommendation(
                anchorFile = it.anchorPath, recommendedTargetKind = it.companionKind.name,
                pairingStrength = it.pairing.name, satisfied = it.existsInRequiredSet, note = it.result.note
            )
        }
        val log = net.ib.ixpert.ops.wuwagent.agent.completeness.model.ShadowLog(
            timestamp = "2026-07-10T00:00:00Z", srKey = "SR-f853f6ad", runId = "348c63e0-b515-4e8d-88da-57631d2cdd88",
            rulesetVersion = "1.0.0-shadow", guardMode = "SHADOW", frameworkType = "SPRING_BOOT_JPA",
            verdict = "WARN", requiredFiles = requiredFiles.toList(), violations = companionDetails,
            acceptedDebts = emptyList(), recommendations = recommendations,
            unclassifiedFiles = report.unclassifiedFiles, srFactsSource = "heuristic-from-pipeline-output"
        )
        println("=== SR-01 SHADOW LOG ===")
        println(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(log))
        println("========================")
    }
}
