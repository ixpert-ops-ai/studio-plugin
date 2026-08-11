package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Test
import java.io.File
import java.io.FileReader

class IsmTrack2Test {

    @Test
    fun testIsmNoiseWithHop3Adjustment() {
        val gson = Gson()
        val graphFile = File("C:\\Users\\dffrp\\Downloads\\project-graph (1).json")
        println("Loading graph from " + graphFile.absolutePath + "...")
        
        FileReader(graphFile).use { reader ->
            val projectGraph = gson.fromJson(reader, ProjectGraph::class.java)
            println("Graph loaded! Files: " + projectGraph.files.size)
            
            // Pick a known ISM service file or any SERVICE
            val seedPath = "src/main/java/com/samsungcardmall/api/bo/app/service/cc/point/WfrPointDlngServiceImpl.java"
                
            println("Seed file: " + seedPath)
            
            val srText = "dummy_unmatched_text" // Ensure lexical scores are 0
            val layerHint = listOf("SERVICE", "REPOSITORY", "CONTROLLER", "DTO")
            
            val domainExtractor = DomainExtractor(projectGraph.files)
            val expander = GraphExpander(projectGraph, domainExtractor, DiscoveryConfig(maxHop = 5))
            
            val seedResult = SeedSelectionResult(
                seedClasses = listOf(seedPath.substringAfterLast("/").substringBefore(".")),
                changeIntent = ChangeIntent.MODIFY,
                layerHint = layerHint,
                frontendRelevant = false,
                reasoning = "Test ISM Noise"
            )
            
            // Phase 1: Expansion
            val expandedFilesMap = expander.expand(seedResult, srText)
            
            // Phase 2: Scoring with minScore = 0 to capture all scores
            val scorer = RelevanceScorer(projectGraph, fileLimit = 10000, minScore = 0)
            val allScoredFiles = scorer.scoreAndFilter(srText, expandedFilesMap, seedResult)
            
            var hop3Total = 0
            var hop3SurviveCurrent = 0
            var hop3SurviveAdjusted = 0
            
            for (scored in allScoredFiles) {
                if (scored.hopDistance >= 3) {
                    hop3Total++
                    if (scored.score >= 55) {
                        hop3SurviveCurrent++
                    }
                    // Simulate Hop 3 base score = 40 (meaning adding 40 to current score which is 0 for hop)
                    val simulatedScore = scored.score + 40
                    if (simulatedScore >= 55) {
                        hop3SurviveAdjusted++
                    }
                }
            }
            
            println("=========================================================")
            println("ISM Hop 3+ Noise Analysis")
            println("=========================================================")
            println("Seed: $seedPath")
            println("Total Hop 3+ Nodes Discovered: $hop3Total")
            println("Currently Surviving (minScore=55): $hop3SurviveCurrent")
            println("Would Survive if Hop 3 Base=40: $hop3SurviveAdjusted")
            println("-> Additional Noise Introduced: ${hop3SurviveAdjusted - hop3SurviveCurrent} nodes")
        }
    }

    @Test
    fun testIsmInfrastructureExemptionNoise() {
        val gson = Gson()
        val graphFile = File("C:\\Users\\dffrp\\Downloads\\project-graph (1).json")
        println("Loading graph from " + graphFile.absolutePath + "...")
        
        FileReader(graphFile).use { reader ->
            val projectGraph = gson.fromJson(reader, ProjectGraph::class.java)
            println("Graph loaded! Files: " + projectGraph.files.size)
            
            val seedPath = "src/main/java/com/samsungcardmall/api/bo/app/service/cc/point/WfrPointDlngServiceImpl.java"
            
            val seedResult = net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.SeedSelectionResult(
                seedClasses = listOf("WfrPointDlngServiceImpl"),
                changeIntent = net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.ChangeIntent.MODIFY,
                layerHint = listOf("SERVICE", "REPOSITORY", "CONTROLLER", "DTO"),
                frontendRelevant = false,
                reasoning = "Test ISM Exemption"
            )
            
            val domainExtractor = DomainExtractor(projectGraph.files)
            val config = DiscoveryConfig(maxHop = 5)
            val expander = GraphExpander(projectGraph, domainExtractor, config)
            
            val expandedNodes = expander.expand(seedResult, "")
            
            println("=========================================================")
            println("ISM Infrastructure Exemption Noise Analysis")
            println("=========================================================")
            println("Total Expanded Nodes: ${expandedNodes.size}")
            
            val hopCounts = expandedNodes.values.groupingBy { it.hop }.eachCount()
            println("Hop Counts:")
            hopCounts.entries.sortedBy { it.key }.forEach { (hop, count) ->
                println("  Hop $hop: $count nodes")
            }
        }
    }
}
