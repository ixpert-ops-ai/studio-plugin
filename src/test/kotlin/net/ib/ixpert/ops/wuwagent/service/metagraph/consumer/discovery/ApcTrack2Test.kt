package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Test
import java.io.File
import java.io.FileReader

class ApcTrack2Test {

    @Test
    fun testTrack2ScoringLogic() {
        val gson = Gson()
        val graphFile = File("C:\\Users\\dffrp\\Downloads\\project-graph_a\\project-graph.json")
        println("Loading graph from " + graphFile.absolutePath + "...")
        
        FileReader(graphFile).use { reader ->
            val projectGraph = gson.fromJson(reader, ProjectGraph::class.java)
            println("Graph loaded! Files: " + projectGraph.files.size)
            
            val seedPath = "src/main/java/sc/chn/aps/apc/ad/ad03/svc/APCADPrvMngtSVC.java"
            val srText = "dummy_unmatched_text" // Ensure lexical scores (name/method/comment) are 0
            
            val domainExtractor = DomainExtractor(projectGraph.files)
            val expander = GraphExpander(projectGraph, domainExtractor, DiscoveryConfig(maxHop = 5))
            
            println("=========================================================")
            println("Scenario 1: Narrow layerHint = [\"SERVICE\"]")
            println("=========================================================")
            runScenario(projectGraph, expander, seedPath, srText, listOf("SERVICE"), "Scenario 1")
            
            println("=========================================================")
            println("Scenario 2: Wide layerHint = [\"SERVICE\", \"BIZ\", \"DATA_ACCESS\"]")
            println("=========================================================")
            runScenario(projectGraph, expander, seedPath, srText, listOf("SERVICE", "BIZ", "DATA_ACCESS"), "Scenario 2")
        }
    }
    
    private fun runScenario(
        projectGraph: ProjectGraph,
        expander: GraphExpander,
        seedPath: String,
        srText: String,
        layerHint: List<String>,
        scenarioName: String
    ) {
        val seedResult = SeedSelectionResult(
            seedClasses = listOf("APCADPrvMngtSVC"),
            changeIntent = ChangeIntent.MODIFY,
            layerHint = layerHint,
            frontendRelevant = false,
            reasoning = "Test Track 2"
        )
        
        // Phase 1: Expansion
        val expandedFilesMap = expander.expand(seedResult, srText)
        
        // Phase 2: Scoring with minScore = 0 to capture all scores
        val scorer = RelevanceScorer(projectGraph, fileLimit = 1000, minScore = 0)
        val allScoredFiles = scorer.scoreAndFilter(srText, expandedFilesMap, seedResult)
        
        println("--- $scenarioName Results ---")
        println(String.format("%-20s | %-6s | %-11s | %-5s | %-6s", "ClassName", "Type", "Hop(Via)", "Score", "Result"))
        println("-------------------------------------------------------------------------")
        
        // Only print interesting nodes: BIZ and DATA_ACCESS related to the seed
        for (scored in allScoredFiles) {
            val isInteresting = scored.className.contains("APCADPrvMngt") || 
                                scored.className.contains("APCSpacdPrvMngtDQM") || 
                                scored.className.contains("ACMBTBAPC004DEM")
            
            if (isInteresting) {
                val survival = if (scored.score >= 55) "SURVIVE" else "DIE"
                val hopInfo = "${scored.hopDistance} (${scored.discoveryReason})"
                println(String.format("%-20s | %-6s | %-11s | %-5d | %-6s", 
                    scored.className.take(20), 
                    scored.fileType.take(6), 
                    hopInfo.take(11), 
                    scored.score, 
                    survival))
            }
        }
    }
}
