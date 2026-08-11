package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Test
import java.io.File
import java.io.FileReader

class ApcExpanderTest {
    @Test
    fun testApcExpanderUpward() {
        val gson = Gson()
        val graphFile = File("C:\\Users\\dffrp\\Downloads\\project-graph_a\\project-graph.json")
        println("Loading graph from " + graphFile.absolutePath + "...")
        
        FileReader(graphFile).use { reader ->
            val projectGraph = gson.fromJson(reader, ProjectGraph::class.java)
            
            println("Graph loaded! Files: " + projectGraph.files.size)
            
            // Seed a DATA_ACCESS file to test UPWARD traversal
            val seedPath = "src/main/java/sc/chn/aps/apc/zz/dqm/APCSpacdPrvMngtDQM.java"
            println("Seed file: " + seedPath)
            val seedNode = projectGraph.files[seedPath]
            if (seedNode == null) {
                println("Seed node not found!")
                return
            }
            println("Seed fileType: " + seedNode.fileType.name)
            
            val domainExtractor = DomainExtractor(projectGraph.files)
            val expander = GraphExpander(projectGraph, domainExtractor, DiscoveryConfig(maxHop = 5))
            
            val seedResult = SeedSelectionResult(
                seedClasses = listOf("APCSpacdPrvMngtDQM"), // Seed with DATA_ACCESS
                changeIntent = ChangeIntent.MODIFY,
                layerHint = emptyList(),
                frontendRelevant = false,
                reasoning = "Test Upward"
            )
            
            val expandedFilesMap = expander.expand(seedResult)
            
            println("\n--- Expanded Files Distribution ---")
            val typeCount = mutableMapOf<String, Int>()
            for ((path, step) in expandedFilesMap) {
                val node = projectGraph.files[path]
                val type = node?.fileType?.name ?: "UNKNOWN"
                typeCount[type] = typeCount.getOrDefault(type, 0) + 1
            }
            for ((type, count) in typeCount) {
                println(type + ": " + count)
            }
            
            println("\n--- Expanded Files Detailed ---")
            for ((path, step) in expandedFilesMap) {
                val node = projectGraph.files[path]
                val type = node?.fileType?.name ?: "UNKNOWN"
                println("- " + path + " (" + type + ") [via: " + step.via + ", hop: " + step.hop + "]")
            }
        }
    }
}
