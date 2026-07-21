package net.ib.ixpert.ops.wuwagent.agent.completeness

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.GraphExpander
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DiscoveryConfig
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DomainExtractor
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.SeedSelectionResult
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.ChangeIntent
import org.junit.Test
import org.junit.Ignore
import java.io.File

class GraphExpanderTest {
    @Test
    @Ignore("Manual: characterization of GraphExpander pruning")
    fun checkEntityExpansion() {
        val graphFile = File("C:/Workspace/member-market/.meta/project-graph.json")
        if (!graphFile.exists()) return
        val gson = Gson()
        val graph = gson.fromJson(graphFile.readText(), ProjectGraph::class.java)

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
        for ((path, step) in expanded) {
            val node = graph.files[path]
            val type = node?.fileType?.name ?: "UNKNOWN"
            expandedTypes[type] = expandedTypes.getOrDefault(type, 0) + 1
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
}
