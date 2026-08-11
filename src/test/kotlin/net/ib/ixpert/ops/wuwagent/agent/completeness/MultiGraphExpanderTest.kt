package net.ib.ixpert.ops.wuwagent.agent.completeness

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.GraphExpander
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DiscoveryConfig
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DomainExtractor
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.SeedSelectionResult
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.ChangeIntent
import org.junit.Test
import java.io.File
import java.util.LinkedList

class MultiGraphExpanderTest {
    
    @Test
    fun testIsmAndApc() {
        val paths = listOf(
            "ISM" to "C:/Workspace/project-graph_b/project-graph_b.json",
            "APC" to "C:/Users/dffrp/Downloads/project-graph_a/project-graph.json"
        )
        
        val gson = Gson()
        
        for ((name, path) in paths) {
            println("==================================================")
            println("=== Analyzing $name ===")
            println("==================================================")
            
            val file = File(path)
            if (!file.exists()) {
                println("File not found: $path")
                continue
            }
            val graph = gson.fromJson(file.readText(), ProjectGraph::class.java)
            println("Graph loaded. Total files: ${graph.files.size}")
            
            // 1. Find a seed
            val controllers = graph.files.values.filter { 
                it.fileType.name == "REST_CONTROLLER" || it.fileType.name == "CONTROLLER" 
            }
            
            if (controllers.isEmpty()) {
                println("No controllers found in $name. Using a BIZ or SERVICE as seed.")
            }
            
            val seedNode = controllers.maxByOrNull { it.dependsOn.size + it.dependedBy.size } 
                ?: graph.files.values.filter { it.fileType.name == "BIZ" || it.fileType.name == "SERVICE" }.maxByOrNull { it.dependsOn.size + it.dependedBy.size }
            
            if (seedNode == null) {
                println("No suitable seed found.")
                continue
            }
            
            println("Selected Seed: ${seedNode.className} (${seedNode.fileType.name}) with ${seedNode.dependsOn.size} outgoing and ${seedNode.dependedBy.size} incoming edges.")
            
            // 2. Pure BFS
            val visited = mutableMapOf<String, Int>()
            val queue = LinkedList<Pair<String, Int>>()
            queue.add(seedNode.path to 0)
            
            while (queue.isNotEmpty()) {
                val (curr, hop) = queue.poll()
                if (visited.containsKey(curr)) continue
                visited[curr] = hop
                if (hop >= 6) continue // max hop 6
                
                val node = graph.files[curr] ?: continue
                for (edge in node.dependsOn) queue.add(edge to hop + 1)
                for (edge in node.dependedBy) queue.add(edge to hop + 1)
            }
            
            val entitiesPure = visited.filter { 
                val t = graph.files[it.key]?.fileType?.name 
                t == "ENTITY" || t == "REPOSITORY" || t == "VO" || t == "DATA_ACCESS"
            }
            
            println("\n--- [1] Pure BFS (Unlimited Bidirectional) ---")
            println("Total reachable nodes within hop 6: ${visited.size}")
            println("Reachable ENTITY/REPOSITORY/VO/DATA_ACCESS: ${entitiesPure.size}")
            
            val hop3Plus = entitiesPure.filter { it.value >= 3 }
            println("Of those, Hop 3+ count: ${hop3Plus.size}")
            if (hop3Plus.isNotEmpty()) {
                println("Sample Hop 3+ entities:")
                hop3Plus.entries.take(5).forEach { 
                    val t = graph.files[it.key]?.fileType?.name
                    println("  - [$t] ${it.key} (Hop: ${it.value})") 
                }
            } else {
                println("  NONE!")
            }
            
            // 3. GraphExpander
            val domainExtractor = DomainExtractor(graph.files)
            val config = DiscoveryConfig(maxHop = 6)
            val expander = GraphExpander(graph, domainExtractor, config)
            
            val seedResult = SeedSelectionResult(
                seedClasses = listOf(seedNode.className),
                changeIntent = ChangeIntent.MODIFY,
                layerHint = listOf("API", "SERVICE", "DOMAIN"),
                reasoning = "Test auto seed",
                frontendRelevant = false
            )
            
            val expanded = expander.expand(seedResult, "Test requirement")
            
            val entitiesExp = expanded.filter { 
                val t = graph.files[it.key]?.fileType?.name
                t == "ENTITY" || t == "REPOSITORY" || t == "VO" || t == "DATA_ACCESS"
            }
            
            println("\n--- [2] GraphExpander (Directional Pruning) ---")
            println("Total expanded files: ${expanded.size}")
            println("Expanded ENTITY/REPOSITORY/VO/DATA_ACCESS: ${entitiesExp.size}")
            if (entitiesExp.isNotEmpty()) {
                println("Sample expanded entities:")
                entitiesExp.entries.take(5).forEach { 
                    val t = graph.files[it.key]?.fileType?.name
                    println("  - [$t] ${it.key} (Hop: ${it.value.hop}, Via: ${it.value.via})") 
                }
            } else {
                println("  NONE!")
            }
            println("\n")
        }
    }
}
