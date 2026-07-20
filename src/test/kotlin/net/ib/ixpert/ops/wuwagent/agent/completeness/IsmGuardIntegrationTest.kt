package net.ib.ixpert.ops.wuwagent.agent.completeness

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.agent.completeness.model.*
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class IsmGuardIntegrationTest {

    private lateinit var ismGraph: ProjectGraph
    private lateinit var matchContext: GraphMatchContext
    private lateinit var engine: CompletenessEngine

    @Before
    fun setUp() {
        val file = File("C:\\Workspace\\project-graph_b\\project-graph_b.json")
        if (!file.exists()) {
            throw IllegalStateException("ISM Graph fixture not found")
        }
        val json = Files.readString(file.toPath())
        ismGraph = Gson().fromJson(json, ProjectGraph::class.java)
        matchContext = ProjectGraphAdapter(ismGraph)
        val debtRegistry = JsonKnownDebtRegistry.loadFromClasspath("ixpert/known_debts.json", "ixpert/heuristic_suppressions.json")
        engine = CompletenessEngine(matchContext, debtRegistry)
    }

    @Test
    fun `dump and verify node kinds distribution`() {
        val kindDistribution = mutableMapOf<FileKind?, Int>()
        ismGraph.files.values.forEach { node ->
            val kind = FileKindClassifier.classify(node.path, matchContext)
            kindDistribution[kind] = kindDistribution.getOrDefault(kind, 0) + 1
        }
        
        println("=== ISM Node Kind Distribution ===")
        kindDistribution.entries.sortedByDescending { it.value }.forEach { (kind, count) ->
            println("${kind ?: "UNCLASSIFIED"} : $count")
        }
        
        assertTrue("Should have DAO Interfaces", kindDistribution.getOrDefault(FileKind.DAO_INTERFACE, 0) > 0)
        assertTrue("Should have Service Interfaces", kindDistribution.getOrDefault(FileKind.SERVICE_INTERFACE, 0) > 0)
    }

    @Test
    fun `verify zero violations for a standard Controller-Service-Dao-XML flow`() {
        val targetDaoPath = ismGraph.files.values.firstOrNull { it.className.endsWith("Dao") }?.path 
            ?: ismGraph.files.values.firstOrNull { it.className.endsWith("Repository") }?.path
            
        assertNotNull("Target Dao should exist", targetDaoPath)
        
        val requiredFiles = mutableSetOf(targetDaoPath!!)
        
        val xmlCompanions = ismGraph.resourceNodes.filter { 
            it.linkedTo.contains(targetDaoPath)
        }.map { it.path }
        
        requiredFiles.addAll(xmlCompanions)
        
        val srFacts = SrFacts(
            hasUserAction = false,
            readsOrWritesData = true,
            hasBusinessLogic = false,
            touchesUi = false,
            addsNewMethod = false
        )
        
        val evaluation = engine.evaluate(ismGraph.resolvedFrameworkType, requiredFiles, srFacts)
        
        val violations = evaluation.companionViolations
        assertTrue("Standard structure should have 0 violations", violations.isEmpty())
    }

    @Test
    fun `verify NOT_IN_GRAPH when fictional node is requested`() {
        val fakeDaoPath = "src/main/java/com/samsungcardmall/api/bo/app/dao/FakeDao.java"
        val requiredFiles = setOf(fakeDaoPath)
        
        val srFacts = SrFacts(
            hasUserAction = false,
            readsOrWritesData = true,
            hasBusinessLogic = false,
            touchesUi = false,
            addsNewMethod = false
        )
        
        val evaluation = engine.evaluate(ismGraph.resolvedFrameworkType, requiredFiles, srFacts)
        
        val violations = evaluation.companionViolations
        assertTrue(violations.any { it.companionKind == FileKind.MYBATIS_XML })
        assertTrue("Fictional DAO should NOT be suppressed as known debt", evaluation.acceptedDebts.isEmpty())
        
        val violation = violations.first { it.companionKind == FileKind.MYBATIS_XML }
        
        val cause = RootCauseAnalyzer.analyze(violation, matchContext)
        assertEquals(RootCause.NOT_IN_GRAPH, cause)
    }

    @Test
    fun `verify NO_EDGE when companion exists in graph but edge is missing`() {
        val targetDaoPath = ismGraph.files.values.firstOrNull { it.className.endsWith("Dao") }?.path ?: return 
        val xmlCompanion = ismGraph.resourceNodes.firstOrNull { 
            it.linkedTo.contains(targetDaoPath)
        } ?: return
        
        val requiredFiles = setOf(targetDaoPath)
        
        // Remove the edge (linkedTo) from the resource node
        val modifiedResourceNodes = ismGraph.resourceNodes.map { res ->
            if (res.path == xmlCompanion.path) {
                res.copy(linkedTo = res.linkedTo.filter { it != targetDaoPath })
            } else res
        }
        
        val manipulatedGraph = ismGraph.copy(resourceNodes = modifiedResourceNodes)
        val manipulatedContext = ProjectGraphAdapter(manipulatedGraph)
        val manipulatedEngine = CompletenessEngine(manipulatedContext)
        
        val srFacts = SrFacts(
            hasUserAction = false,
            readsOrWritesData = true,
            hasBusinessLogic = false,
            touchesUi = false,
            addsNewMethod = false
        )
        
        val evaluation = manipulatedEngine.evaluate(manipulatedGraph.resolvedFrameworkType, requiredFiles, srFacts)
        val violations = evaluation.companionViolations
        val violation = violations.firstOrNull { it.companionKind == FileKind.MYBATIS_XML }
        
        assertNotNull("Should have violation for missing XML", violation)
        
        val cause = RootCauseAnalyzer.analyze(violation!!, manipulatedContext)
        assertEquals(RootCause.NO_EDGE, cause)
    }

    @Test
    fun `evaluate MYBATIS_XML across entire graph`() {
        val daoNodes = ismGraph.files.values.filter { 
            FileKindClassifier.classify(it.path, matchContext) == FileKind.DAO_INTERFACE 
        }
        println("Found ${daoNodes.size} DAO nodes to evaluate.")

        val requiredFiles = daoNodes.map { it.path }.toSet() + ismGraph.resourceNodes.map { it.path }.toSet()
        
        val srFacts = SrFacts(
            hasUserAction = false,
            readsOrWritesData = true,
            hasBusinessLogic = false,
            touchesUi = false,
            addsNewMethod = false
        )
        
        val evaluation = engine.evaluate(ismGraph.resolvedFrameworkType, requiredFiles, srFacts)
        
        val xmlViolations = evaluation.companionViolations.filter { it.companionKind == FileKind.MYBATIS_XML }
        
        println("=== Evaluation Results ===")
        println("DAO nodes: ${daoNodes.size}")
        println("MYBATIS_XML missing (violations): ${xmlViolations.size}")
        println("Accepted debts: ${evaluation.acceptedDebts.size}")
        
        // Output accepted debts for debugging
        if (evaluation.acceptedDebts.isNotEmpty()) {
            println("Suppressed violations:")
            evaluation.acceptedDebts.forEach { println(" - ${it.anchorPath}") }
        }
        
        // The 16 DAOs that lack XML should now be completely suppressed.
        // Therefore, there should be exactly 0 violations for MYBATIS_XML.
        assertEquals("There should be 0 violations after suppression", 0, xmlViolations.size)
        // And there should be exactly 16 accepted debts recorded in the engine report.
        assertEquals("There should be 16 accepted debts", 16, evaluation.acceptedDebts.size)
    }

    @Test
    fun `diagnose isolated mappers against the known 16 cases`() {
        val expectedIsolatedMappers = setOf(
            "WFCCTBCCY004Mapper",
            "StStdCdMapper_b",
            "PADMSU23P014TrxMapper",
            "WFCCTBCCY004TrxMapper",
            "ECCSTBCST010TrxMapper",
            "BatchJobTrxMapper",
            "StStdCdMlTrxMapper",
            "CcSiteBaseTrxMapper",
            "StUsrWkLogTrxMapper",
            "OrderRestoreBatchMapper",
            "BatchJobMapper",
            "StBatchLogMapper",
            "StUsrWkLogMapper",
            "BatchJobExecutionMapper",
            "BatchStepExecutionMapper",
            "BatchJobExecutionParamsMapper"
        )

        // Find all DAO_INTERFACE (Mappers) in the graph
        val allMappers = ismGraph.files.values.filter { 
            FileKindClassifier.classify(it.path, matchContext) == FileKind.DAO_INTERFACE 
        }.map { it.path }.toSet()

        // To simulate a static scan that triggers ON_ANY_CHANGE, we pass all mapper paths as requiredFiles
        val srFacts = SrFacts(
            hasUserAction = false,
            readsOrWritesData = true,
            hasBusinessLogic = false,
            touchesUi = false,
            addsNewMethod = true
        )

        // Evaluate all mappers using the empty debt registry so we see all raw violations
        val pureEngine = CompletenessEngine(matchContext, JsonKnownDebtRegistry.empty())
        val evaluation = pureEngine.evaluate(ismGraph.frameworkType, allMappers, srFacts)

        // Filter violations specifically for missing MYBATIS_XML where the anchor was a DAO_INTERFACE
        val xmlViolations = evaluation.companionViolations.filter { 
            it.companionKind == FileKind.MYBATIS_XML && 
            !it.result.existsInGraph 
        }

        // Extract class names from the violating anchor paths
        val actualIsolatedMappers = xmlViolations.map { violation ->
            matchContext.baseName(violation.anchorPath).removeSuffix(".java")
        }.toSet()

        println("=== Static Isolation Diagnosis ===")
        println("Expected Count: ${expectedIsolatedMappers.size}")
        println("Actual Count  : ${actualIsolatedMappers.size}")
        
        val falseNegatives = expectedIsolatedMappers - actualIsolatedMappers
        val falsePositives = actualIsolatedMappers - expectedIsolatedMappers
        val truePositives = expectedIsolatedMappers.intersect(actualIsolatedMappers)

        println("\n[1. 양쪽 다 있는 것 (정확히 재현 - True Positive)] - ${truePositives.size}건")
        truePositives.sorted().forEach { println("  - $it") }

        println("\n[2. 정답에 있는데 엔진이 놓친 것 (False Negative)] - ${falseNegatives.size}건")
        falseNegatives.sorted().forEach { println("  - $it") }

        println("\n[3. 엔진이 잡았는데 정답에 없는 것 (신규 또는 False Positive)] - ${falsePositives.size}건")
        falsePositives.sorted().forEach { println("  - $it") }
        
        // Assertions locked in after successful diagnostic run
        assertEquals("Should not miss any known isolated mappers", 0, falseNegatives.size)
        assertEquals("Should not falsely report any new isolated mappers", 0, falsePositives.size)
        assertEquals("Should exactly reproduce all 16 isolated mappers", 16, truePositives.size)
    }
}
