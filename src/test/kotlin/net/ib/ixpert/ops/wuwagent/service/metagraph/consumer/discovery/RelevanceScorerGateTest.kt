package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Test
import java.io.File
import org.junit.Assert.assertTrue

class RelevanceScorerGateTest {

    @Test
    fun `test Gate 2 - RelevanceScorer ranks ECPDTBISM032TrxMapper in top 30`() {
        val file = File("C:/Workspace/graph/project-graph-i/project-graph.json")
        if (!file.exists()) {
            println("File not found")
            return
        }
        
        val gson = Gson()
        val jsonString = file.readText(Charsets.UTF_8)
        val normalizedGraph = gson.fromJson(jsonString, ProjectGraph::class.java).normalizeLegacyCollections()
        
        val domainExtractor = DomainExtractor(normalizedGraph.files)
        val expander = GraphExpander(normalizedGraph, domainExtractor, DiscoveryConfig(maxHop = 4))
        
        val seedResult = SeedSelectionResult(
            seedClasses = listOf("PdInfoController"),
            changeIntent = ChangeIntent.MODIFY,
            layerHint = listOf("CONTROLLER", "SERVICE", "MAPPER", "DATA_ACCESS", "DTO", "VO", "REPOSITORY"),
            frontendRelevant = false,
            reasoning = "Test"
        )
        val expanded = expander.expand(seedResult)
        
        val scorer = RelevanceScorer(normalizedGraph, fileLimit = 30, minScore = 50)
        // SR text: "주문제작여부 컬럼 추가 PdInfoRequest"
        // This triggers nameMatchScore >= 15 on PdInfoRequest specifically,
        // without flooding the top 30 with all "PdInfo" classes,
        // proving that the +20 inheritance bonus works via PdInfoRequest -> Mapper
        val srText = "주문제작여부 컬럼 추가 PdInfoRequest"
        
        val scoredFiles = scorer.scoreAndFilter(srText, expanded, seedResult)
        
        println("===============================================")
        println("Gate 2 Result (ISM RelevanceScorer): ${scoredFiles.size} nodes passed")
        scoredFiles.forEachIndexed { index, scoredFile ->
            println("${index + 1}위: ${scoredFile.className} (Score: ${scoredFile.score})")
        }
        println("===============================================")
        
        // Also find it in the initialScoredFiles (before limit) to print its absolute score
        val allScoredFiles = RelevanceScorer(normalizedGraph, fileLimit = 1000, minScore = 0).scoreAndFilter(srText, expanded, seedResult)
        
        val actualMapper = allScoredFiles.find { it.className == "ECPDTBISM032TrxMapper" }
        println("===============================================")
        println("ECPDTBISM032TrxMapper 실제 상세 점수: ${actualMapper?.score ?: -1}")
        println("===============================================")
        
        val gtMapperRank = scoredFiles.indexOfFirst { it.className == "ECPDTBISM032TrxMapper" }
        println("ECPDTBISM032TrxMapper 순위: ${if (gtMapperRank >= 0) gtMapperRank + 1 else -1}")
        
        assertTrue("ECPDTBISM032TrxMapper should be in the top 30", gtMapperRank >= 0 && gtMapperRank < 30)
    }
}
