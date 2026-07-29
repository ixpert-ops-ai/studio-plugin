package net.ib.ixpert.ops.wuwagent.agent.stage0

import com.google.gson.GsonBuilder
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.*
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class Phase3ScoreMeasurementTest {

    companion object {
        var projectGraph: ProjectGraph? = null

        @JvmStatic
        @BeforeClass
        fun setup() {
            val graphPath = "C:\\Workspace\\member-market\\.meta\\project-graph.json"
            val graphFile = File(graphPath)

            if (graphFile.exists()) {
                val gson = GsonBuilder().disableHtmlEscaping().create()
                projectGraph = gson.fromJson(graphFile.readText(Charsets.UTF_8), ProjectGraph::class.java)
            }
        }
    }

    @Test
    fun testMeasurePhase3Scores() {
        val graph = projectGraph
        if (graph == null) {
            println("member-market project-graph.json not found, skipping test.")
            return
        }

        // Target SR-01 (from member_market_shadow_test_cases.md)
        val srText = "[SR-01] 상품 원산지 필드 추가\nProduct 엔티티에 원산지(originCountry) 필드를 추가하고, 상품 등록/수정/조회 API 및 화면에 반영해주세요."

        // Mock Seed Result (Assuming Product entity and related controllers are found)
        val seedResult = SeedSelectionResult(
            seedClasses = listOf("Product", "ProductController", "ProductCreateRequest", "ProductResponse"),
            changeIntent = ChangeIntent.MODIFY,
            layerHint = listOf("ENTITY", "PRESENTATION", "DTO", "SERVICE", "REPOSITORY"),
            frontendRelevant = true,
            reasoning = "Mock seed"
        )

        val domainExtractor = DomainExtractor(graph.files)
        val config = DiscoveryConfig(maxHop = 3)
        val expander = GraphExpander(graph, domainExtractor, config)
        
        // 1. Expand
        val expandedFiles = expander.expand(seedResult, srText)
        println("Expanded files count: \${expandedFiles.size}")
        println("Expanded files count: ${expandedFiles.size}")

        // 2. Score with minScore = 0 so we see everything
        val scorer = RelevanceScorer(graph, fileLimit = 1000, minScore = 0)
        val scoredFiles = scorer.scoreAndFilter(srText, expandedFiles, seedResult)

        // 3. Print
        println("    === Phase 3 Score Distribution for SR-01 ===")
        val format = "%4s pts | Hop %d | %-20s | %s"
        scoredFiles.sortedByDescending { it.score }.forEach {
            println(String.format(format, it.score.toString(), it.hopDistance, it.className, it.path))
        }

        // Ground truth verification
        println("\n    Ground Truth Check (Expected to be modified):")
        val groundTruth = listOf("Product", "ProductController", "ProductCreateRequest", "ProductResponse", "ProductService", "ProductRepository", "ProductCreateView", "ProductDetailView", "ProductListView")
        groundTruth.forEach { gt ->
            val found = scoredFiles.find { it.className == gt || it.className == "$gt.vue" }
            if (found != null) {
                println("    [FOUND] $gt -> ${found.score} pts")
            } else {
                println("    [MISSING] $gt")
            }
        }
    }

    @Test
    fun testMeasurePhase3Scores_SR03() {
        val graph = projectGraph
        if (graph == null) {
            println("member-market project-graph.json not found, skipping test.")
            return
        }

        // Target SR-03
        val srText = "[SR-03] 가격대별 상품 검색 기능\n상품 목록 조회 API에 최소 가격(minPrice)과 최대 가격(maxPrice) 조건을 파라미터로 추가해 검색이 가능하도록 수정해주세요."

        // Mock Seed Result for SR-03
        val seedResult = SeedSelectionResult(
            seedClasses = listOf("ProductController", "ProductService", "ProductRepository", "ProductListRequest"),
            changeIntent = ChangeIntent.MODIFY,
            layerHint = listOf("PRESENTATION", "SERVICE", "REPOSITORY", "DTO"),
            frontendRelevant = true,
            reasoning = "Mock seed SR-03"
        )

        val domainExtractor = DomainExtractor(graph.files)
        val config = DiscoveryConfig(maxHop = 3)
        val expander = GraphExpander(graph, domainExtractor, config)

        // 1. Expand
        val expandedFiles = expander.expand(seedResult, srText)
        println("\n    Expanded files count: ${expandedFiles.size}")

        // 2. Score with minScore = 0 so we see everything
        val scorer = RelevanceScorer(graph, fileLimit = 1000, minScore = 0)
        val scoredFiles = scorer.scoreAndFilter(srText, expandedFiles, seedResult)

        // 3. Print
        println("    === Phase 3 Score Distribution for SR-03 ===")
        val format = "%4s pts | Hop %d | %-20s | %s"
        scoredFiles.sortedByDescending { it.score }.forEach {
            println(String.format(format, it.score.toString(), it.hopDistance, it.className, it.path))
        }

        // Ground truth verification
        println("\n    Ground Truth Check (Expected to be modified):")
        val groundTruth = listOf("ProductController", "ProductService", "ProductRepository", "ProductListRequest", "ProductListView")
        groundTruth.forEach { gt ->
            val found = scoredFiles.find { it.className == gt || it.className == "$gt.vue" }
            if (found != null) {
                println("    [FOUND] $gt -> ${found.score} pts")
            } else {
                println("    [MISSING] $gt")
            }
        }
    }

    @Test
    fun testMeasurePhase3Scores_PureKoreanSR_GapCollapse() {
        val graph = projectGraph
        if (graph == null) {
            println("member-market project-graph.json not found, skipping test.")
            return
        }

        // Target: 순수 한국어판 SR-01 ("Product" 직접어 제거)
        val srText = "[SR-Synthetic] 상품 원산지 필드 추가\n상품 엔티티에 원산지 필드를 추가하고, 상품 등록/수정/조회 API 및 화면에 반영해주세요."

        // Mock Seed Result (Assuming Product related classes are found)
        val seedResult = SeedSelectionResult(
            seedClasses = listOf("Product", "ProductController", "ProductService", "ProductRepository"),
            changeIntent = ChangeIntent.MODIFY,
            layerHint = listOf("ENTITY", "PRESENTATION", "SERVICE", "REPOSITORY"),
            frontendRelevant = true,
            reasoning = "Mock seed Pure Korean SR-01"
        )

        val domainExtractor = DomainExtractor(graph.files)
        val config = DiscoveryConfig(maxHop = 3)
        val expander = GraphExpander(graph, domainExtractor, config)

        // Diagnostic: Print Dictionary translations
        println("\n    === Diagnostic: DomainDictionary Translations ===")
        val dictionary = DomainDictionary.load(graph)
        val testWords = listOf("상품", "엔티티", "원산지", "필드", "추가", "등록", "수정", "조회", "화면", "반영")
        testWords.forEach { word ->
            val translated = dictionary.translate(word)
            println("    Word '$word' -> ${translated.size} translations: $translated")
        }

        // 1. Expand
        val expandedFiles = expander.expand(seedResult, srText)
        println("\n    Expanded files count: ${expandedFiles.size}")

        // 2. Score with minScore = 0 so we see everything
        val scorer = RelevanceScorer(graph, fileLimit = 1000, minScore = 0)
        val scoredFiles = scorer.scoreAndFilter(srText, expandedFiles, seedResult)

        // 3. Print
        println("    === Phase 3 Score Distribution for Pure Korean SR-01 ===")
        val format = "%4s pts | Hop %d | %-20s | %s"
        scoredFiles.sortedByDescending { it.score }.forEach {
            println(String.format(format, it.score.toString(), it.hopDistance, it.className, it.path))
        }
    }
}
