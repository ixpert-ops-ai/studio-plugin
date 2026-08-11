package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Test
import java.io.File
import java.io.FileReader
import org.junit.Assert.assertTrue

class IsmRule2Test {

    @Test
    fun testIsmRule2Impact() {
        val gson = Gson()
        val graphFile = File("C:\\Workspace\\project-graph_b\\project-graph_b.json")
        println("Loading ISM graph from " + graphFile.absolutePath + "...")
        
        FileReader(graphFile).use { reader ->
            val projectGraph = gson.fromJson(reader, ProjectGraph::class.java)
            println("Graph loaded! Files: " + projectGraph.files.size)
            
            // Seed = WfrPointDlngServiceImpl
            val seedResult = SeedSelectionResult(
                seedClasses = listOf("WfrPointDlngServiceImpl"),
                changeIntent = ChangeIntent.MODIFY,
                layerHint = listOf("SERVICE", "DATA_ACCESS"),
                frontendRelevant = false,
                reasoning = "Check ISM recall impact"
            )
            
            val domainExtractor = DomainExtractor(projectGraph.files)
            val expander = GraphExpander(projectGraph, domainExtractor, DiscoveryConfig(maxHop = 5, domainFilterEnabled = false, minInfraThreshold = 999))
            
            println("=========================================================")
            println("ISM Expansion")
            println("=========================================================")
            
            val expandedFilesMap = expander.expand(seedResult, "")
            
            println("Expanded Files count: " + expandedFilesMap.size)
            
            println(String.format("%-40s | %-12s | %-20s", "ClassName", "Type", "Hop(Via)"))
            println("-----------------------------------------------------------------------------------------")
            
            expandedFilesMap.forEach { (path, step) ->
                val hopInfo = "${step.hop} (${step.via})"
                println(String.format("%-40s | %-12s | %-20s", 
                    path.take(40), 
                    step.via.take(12), 
                    hopInfo))
            }
        }
    }
}
