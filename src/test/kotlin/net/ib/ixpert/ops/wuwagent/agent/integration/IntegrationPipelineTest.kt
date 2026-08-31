package net.ib.ixpert.ops.wuwagent.agent.integration

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.client.OpenAIClient
import net.ib.ixpert.ops.wuwagent.agent.PureAnalyzeSurveyAdminTest.RealVllmClient
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.AdaptiveFileDiscovery
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.AgenticSeedSelector
import org.junit.Test
import java.io.File

class IntegrationPipelineTest {

    data class RunResult(
        val project: String,
        val runIndex: Int,
        val totalGt: Int,
        val survivedGt: Int,
        val survivedGtNames: List<String>,
        val stage2Count: Int,
        val stage3Count: Int,
        val stage3TimeMs: Long,
        val completed: Boolean,
        val fallback: Boolean,
        val notes: String
    )

    @Test
    fun runAll() {
        val client = RealVllmClient()
        val gson = Gson()
        val results = mutableListOf<RunResult>()

        val projects = listOf(
            mapOf(
                "name" to "member-market",
                "path" to "C:/Workspace/member-market/.meta/project-graph.json",
                "sr" to "상품 상세 내 단말기 스펙 조회",
                "gt" to listOf("ProductController", "ProductResponse", "Product") 
            ),
            mapOf(
                "name" to "ISM",
                "path" to "C:/Workspace/graph/project-graph-i/project-graph.json",
                "sr" to "케어회원 관리 화면 조회",
                "gt" to listOf("CareMemberMgmtController", "CareMemberMgmtServiceImpl", "ECMBTBISM006Mapper", "ECMBTBISM006Mapper.xml")
            )
        )

        for (proj in projects) {
            val name = proj["name"] as String
            val path = proj["path"] as String
            val projectBasePath = if (path.contains("/.meta/")) path.substringBefore("/.meta/") else null
            val sr = proj["sr"] as String
            @Suppress("UNCHECKED_CAST")
            val gt = proj["gt"] as List<String>
            
            val iterations = 1
            
            val actualPath = path
            
            if (!File(actualPath).exists()) {
                println("Skipping $name: file not found at $actualPath")
                continue
            }
            
            println("Loading graph for $name from $actualPath")
            val graphContent = File(actualPath).readText()
            val graph = gson.fromJson(graphContent, ProjectGraph::class.java)
            
            for (i in 1..iterations) {
                println("=== Running $name (Run $i) ===")
                try {
                    val start = System.currentTimeMillis()
                    var stage2Count = 0
                    
                    val discoveryResult = AdaptiveFileDiscovery.filter(
                        primaryReq = sr,
                        secondaryReq = "",
                        graph = graph,
                        client = client,
                        project = null,
                        projectBasePath = projectBasePath,
                        enhancedRequirements = emptyList(),
                        seedSelector = null,
                        onProgress = { msg ->
                            if (msg.contains("후보")) {
                                val match = Regex("\\((\\d+)개 후보\\)").find(msg)
                                if (match != null) {
                                    stage2Count = match.groupValues[1].toInt()
                                }
                            }
                        }
                    )
                    
                    val verifiedFiles = discoveryResult.relevantFiles
                    val stage3TimeMs = System.currentTimeMillis() - start
                    
                    println("=== FINAL STAGE 3 RESULT FILES FOR $name ===")
                    verifiedFiles.forEach { file -> 
                        println("RESULT_FILE: [$name] " + file.path + " (Score: " + file.score + " / Reason: " + file.discoveryReason + ")")
                    }
                    
                    val survivedGtNames = gt.filter { g -> verifiedFiles.any { vf -> vf.path.contains(g) } }
                    val survivedGt = survivedGtNames.size
                    
                    val fallback = verifiedFiles.any { it.discoveryReason.contains("Fallback") || it.discoveryReason.contains("실패") }
                    
                    results.add(RunResult(
                        project = name,
                        runIndex = i,
                        totalGt = gt.size,
                        survivedGt = survivedGt,
                        survivedGtNames = survivedGtNames,
                        stage2Count = stage2Count,
                        stage3Count = verifiedFiles.size,
                        stage3TimeMs = stage3TimeMs,
                        completed = true,
                        fallback = fallback,
                        notes = "Success"
                    ))
                    
                } catch (e: Exception) {
                    println("Error in $name: " + e.message)
                    e.printStackTrace()
                    results.add(RunResult(
                        project = name,
                        runIndex = i,
                        totalGt = gt.size,
                        survivedGt = 0,
                        survivedGtNames = emptyList(),
                        stage2Count = 0,
                        stage3Count = 0,
                        stage3TimeMs = 0,
                        completed = false,
                        fallback = false,
                        notes = "Exception: " + e.javaClass.simpleName
                    ))
                }
            }
        }
        
        val out = java.lang.StringBuilder()
        out.appendLine("=== Integration Test Report ===")
        out.appendLine("| Project | Run | GT (Surv/Total) | Survived GT Names | Stg2 Cnt | Stg3 Cnt | Stg3 Time | Completed | Fallback |")
        out.appendLine("|---|---|---|---|---|---|---|---|---|")
        for (res in results) {
            val gtStr = if (res.totalGt == 0) "N/A" else "" + res.survivedGt + "/" + res.totalGt
            val time = if (res.stage3TimeMs > 0) "" + res.stage3TimeMs + "ms" else "N/A"
            out.appendLine("| " + res.project + " | " + res.runIndex + " | " + gtStr + " | " + res.survivedGtNames.joinToString(", ") + " | " + res.stage2Count + " | " + res.stage3Count + " | " + time + " | " + res.completed + " | " + res.fallback + " |")
        }
        
        File("C:/Workspace/DEV-ASSISTANT/IDE-PLUGIN/intelliJ/ai-assistant-plugin/integration_test_report.txt").writeText(out.toString())
        File("C:/Workspace/DEV-ASSISTANT/IDE-PLUGIN/intelliJ/ai-assistant-plugin/integration_test_raw.json").writeText(gson.toJson(results))
        println(out.toString())
    }
}