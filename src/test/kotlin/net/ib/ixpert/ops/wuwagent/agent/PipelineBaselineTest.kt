package net.ib.ixpert.ops.wuwagent.agent

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.AdaptiveFileDiscovery
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.SeedSelector
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.SeedSelectionResult
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.AgenticSeedSelector
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraphQueryable
import org.junit.Test
import java.io.File

class PipelineBaselineTest {
    
    private fun runBaselineTest(testName: String, srText: String) {
        val graphPath = "C:/Workspace/HC_card_survey_admin/survey_admin/.meta/project-graph-before.json"
        val graphJson = File(graphPath).readText(Charsets.UTF_8)
        val projectGraph = Gson().fromJson(graphJson, ProjectGraph::class.java)

        val client = PipelineE2ETestVllmClient()

        val bypassOffSelector = object : SeedSelector {
            val real = AgenticSeedSelector(client)
            override fun selectSeeds(srText: String, graph: ProjectGraphQueryable, projectBasePath: String?): SeedSelectionResult {
                val result = real.selectSeeds(srText, graph)
                return result.copy(judgePicks = emptyList())
            }
        }

        val discoveryResult = AdaptiveFileDiscovery.filter(
            primaryReq = srText,
            secondaryReq = "",
            graph = projectGraph,
            client = client,
            project = null,
            enhancedRequirements = emptyList(),
            seedSelector = bypassOffSelector
        )

        val targets = listOf("sql_survey.xml", "SurveyServiceImpl.java", "SurveyDaoImpl.java")

        println("\n=== BASELINE MEASUREMENT LOG ($testName) ===")
        targets.forEach { target ->
            val isSeeded = discoveryResult.metadata.seedClasses.any { it.contains(target.substringBefore(".")) }
            val trace = discoveryResult.metadata.expansionTrace.entries.find { it.key.contains(target) }
            val scored = discoveryResult.relevantFiles.find { it.path.contains(target) }

            val step1 = if (isSeeded) "SEEDED" else "NOT_SEEDED"
            val step2 = if (trace != null) "VIA_${trace.value.via} (Hop ${trace.value.hop})" else "NOT_IN_TRACE"
            val step3 = if (scored != null) "SCORE: ${scored.score} (Pass)" else "FAILED_CUTOFF_OR_NOT_SCORED"
            val step4 = if (scored != null) "FOUND" else "NOT_FOUND"

            println("[$target]")
            println("  Step 1 (Seed)  : $step1")
            println("  Step 2 (Hop)   : $step2")
            println("  Step 3 (Score) : $step3")
            println("  Step 4 (Result): $step4\n")
        }
        println("=== END LOG ===\n")
    }

    @Test
    fun testSurveyAdminBaselineNatural() {
        runBaselineTest("NATURAL SR", "설문 발송할 때 현대카드 브랜드메시지로도 보낼 수 있게 해달라")
    }

    @Test
    fun testSurveyAdminBaselineKeyword() {
        runBaselineTest("KEYWORD SR", "브랜드메시지 어드민 기능 개발 (설문 발송 시 브랜드메시지 템플릿과 채널을 선택하여 발송할 수 있도록 화면과 로직 추가)")
    }
}
