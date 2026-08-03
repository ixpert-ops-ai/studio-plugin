package net.ib.ixpert.ops.wuwagent.agent.completeness

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.GraphExpander
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DiscoveryConfig
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DomainExtractor
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.RelevanceScorer
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.SeedSelectionResult
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.ChangeIntent
import org.junit.Test
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.RelationshipType
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FileNode
import org.junit.Assume.assumeTrue
import java.io.File

class GraphExpanderTest {
    @Test
    fun checkEntityExpansion() {
        val graphFile = File("C:/Workspace/member-market/.meta/project-graph.json")
        assumeTrue("Skipping real graph tests because the graph file does not exist", graphFile.exists())
        val graph = Gson().fromJson(graphFile.readText(), ProjectGraph::class.java)

        val domainExtractor = DomainExtractor(graph.files)
        val config = DiscoveryConfig(maxHop = 6)
        val expander = GraphExpander(graph, domainExtractor, config)

        val seedResult = SeedSelectionResult(
            seedClasses = listOf("ProductController"),
            changeIntent = ChangeIntent.MODIFY,
            layerHint = listOf("API"),
            reasoning = "mock",
            frontendRelevant = false
        )

        val expanded = expander.expand(seedResult, "mock sr text")

        println("=== GraphExpander Result for ProductController Seed ===")
        println("Total expanded files: " + expanded.size)
        
        val expandedTypes = mutableMapOf<String, Int>()
        println("All expanded files:")
        for ((path, step) in expanded) {
            val node = graph.files[path]
            val type = node?.fileType?.name ?: "UNKNOWN"
            expandedTypes[type] = expandedTypes.getOrDefault(type, 0) + 1
            println("  $path (Hop: ${step.hop}, via: ${step.via}, type: $type)")
        }
        
        println("Expanded Type Distribution:")
        expandedTypes.forEach { (k, v) -> println("  " + k + ": " + v) }

        println("\nEntities/DTOs found in expandedFiles:")
        val entitiesOrDtos = expanded.filter { 
            val type = graph.files[it.key]?.fileType?.name
            type == "ENTITY" || type == "DTO"
        }
        if (entitiesOrDtos.isEmpty()) {
            println("  NONE!")
        } else {
            entitiesOrDtos.forEach { (path, step) ->
                val type = graph.files[path]?.fileType?.name
                println("  [" + type + "] " + path + " (Hop: " + step.hop + ", via: " + step.via + ")")
            }
        }
    }

    @Test
    fun checkRelevanceScorer() {
        val graphFile = File("C:/Workspace/member-market/.meta/project-graph.json")
        assumeTrue("Skipping real graph tests because the graph file does not exist", graphFile.exists())
        val graph = Gson().fromJson(graphFile.readText(), ProjectGraph::class.java)

        val domainExtractor = DomainExtractor(graph.files)
        val config = DiscoveryConfig(maxHop = 6)
        val expander = GraphExpander(graph, domainExtractor, config)

        val seedPath = "member-market-api/src/main/java/com/membermarket/api/product/ProductController.java"
        val seedNode = graph.files[seedPath]
        assumeTrue("Seed node must exist", seedNode != null)

        val seedResult = SeedSelectionResult(
            seedClasses = listOf("ProductController", "ProductService", "ProductRepository"),
            changeIntent = ChangeIntent.MODIFY,
            layerHint = listOf("API", "SERVICE", "REPOSITORY"),
            frontendRelevant = false,
            reasoning = "Test multi seeds"
        )

        val expandedFiles = expander.expand(seedResult)
        
        val outFile = File("C:/Workspace/member-market/relevance-scores.txt")
        outFile.writeText("=== Debug Info ===\n")
        outFile.appendText("expandedFiles.size: ${expandedFiles.size}\n")
        
        val scorer = RelevanceScorer(graph, fileLimit = 30, minScore = 72)
        val srText = "단순 변경"
        
        val scoredFiles = scorer.scoreAndFilter(srText, expandedFiles, seedResult)

        outFile.appendText("\n=== RelevanceScorer Measurement (Cutoff: 72) ===\n")
        outFile.appendText("Input SR: $srText\n")
        outFile.appendText("Total Scored Files Passed: ${scoredFiles.size}\n")
        for (f in scoredFiles) {
            outFile.appendText("  [${f.fileType}] ${f.path.substringAfterLast("/")} (Score: ${f.score}, via: ${f.discoveryReason})\n")
        }
        
        // Let's also print ALL expanded files' scores without cutoff
        val allScorer = RelevanceScorer(graph, fileLimit = 30, minScore = 0)
        val allScoredFiles = allScorer.scoreAndFilter(srText, expandedFiles, seedResult)
        outFile.appendText("\n=== All Scores (No Cutoff) ===\n")
        for (f in allScoredFiles) {
            outFile.appendText("  [${f.fileType}] ${f.path.substringAfterLast("/")} (Score: ${f.score}, via: ${f.discoveryReason})\n")
        }
    }
}
