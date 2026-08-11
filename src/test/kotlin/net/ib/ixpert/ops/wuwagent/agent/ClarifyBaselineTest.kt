package net.ib.ixpert.ops.wuwagent.agent

import kotlinx.coroutines.runBlocking
import net.ib.ixpert.ops.wuwagent.agent.clarify.ClarifyPromptBuilder
import net.ib.ixpert.ops.wuwagent.agent.clarify.RequirementClarifier
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.AdaptiveFileDiscovery
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import com.google.gson.Gson
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class ClarifyBaselineTest {
    companion object {
        lateinit var graph: ProjectGraph
        lateinit var client: LLMClient
        lateinit var clarifier: RequirementClarifier
        
        @JvmStatic
        @BeforeClass
        fun setup() {
            val jsonFile = File("C:/Workspace/graph/project-graph-i/project-graph.json")
            val jsonString = jsonFile.readText(Charsets.UTF_8)
            graph = Gson().fromJson(jsonString, ProjectGraph::class.java).normalizeLegacyCollections()
            client = PipelineE2ETestVllmClient()
            clarifier = RequirementClarifier(client, ClarifyPromptBuilder())
        }
    }

    data class TestCase(
        val type: String,
        val srText: String,
        val targetClass: String
    )

    private val testCases = listOf(
        TestCase("Easy", "주문 결제 연장 결제기한 처리", "OrderProcessBaseRequest"),
        TestCase("Easy", "새 쿠폰앱 전환 기획전 마케팅 푸시", "CponInfoRequest"),
        TestCase("Easy", "앱 전면홈 띠배너 운영자 관리 기능", "BnnrMngtServiceImpl"),
        TestCase("Easy", "탈퇴 회원 전환 전 임시 분리 보관", "MbrInfoServiceImpl"),
        TestCase("Trap", "주문상품 배송 준비 상태 추가", "PdSaveServiceImpl"),
        TestCase("Trap", "새 화면 (GNB) 전시 노출 순서 변경", "GnbMenuMstServiceImpl"),
        TestCase("Trap", "상품 불량 및 파손 부분 클레임 환불 계좌 처리", "ClaimMgmtServiceImpl"),
        TestCase("Trap", "특정 몰 회원 아이디별 주문 조회 추가", "OrdrInqrListResponse")
    )

    @Test
    fun runBaseline() = runBlocking {
        println("=== STAGE 0 (CLARIFY) BASELINE TEST ===")
        
        // Results Table Builder
        val results = StringBuilder()
        results.append(String.format("%-10s | %-30s | %-20s | %-12s | %-10s | %-15s | %-15s\n", 
            "Type", "SR", "Target GT", "GT In Graph?", "Ctrl Rank", "Exp Rank", "Impact"))
        results.append("-".repeat(125)).append("\n")

        val downgradedCases = mutableListOf<String>()

        testCases.forEachIndexed { idx, tc ->
            val tcNum = idx + 1
            println("\n\n=========================================================================")
            println("▶ [TC\$tcNum: \${tc.type}] SR: \${tc.srText}")
            
            // Check if GT exists in graph
            val gtInGraph = if (graph.files.values.any { it.className.contains(tc.targetClass, ignoreCase = true) }) "Yes" else "No"
            println("Target GT: \${tc.targetClass} (In Graph: \$gtInGraph)")

            // 1. Stage 0: LLM Clarify
            var clarifyOutput = ""
            var enhancedRequirements = emptyList<String>()
            try {
                val clarifyResult = clarifier.clarify(tc.srText, graph.frameworkType, "")
                enhancedRequirements = clarifyResult.enhancedRequirements
                clarifyOutput = enhancedRequirements.joinToString(". ")
                println("Stage 0 Output:\n\$clarifyOutput")
            } catch (e: Exception) {
                println("Stage 0 Failed: \${e.message}")
            }

            val secondaryReq = if (clarifyOutput.isNotBlank()) clarifyOutput + "." else ""

            // 2. Stage 1 (Control): SR 단독
            val ctrlResult = AdaptiveFileDiscovery.filter(
                primaryReq = tc.srText,
                secondaryReq = "",
                graph = graph,
                client = client,
                project = null
            )
            val ctrlRank = ctrlResult.relevantFiles.indexOfFirst { it.path.contains(tc.targetClass, ignoreCase = true) } + 1
            val ctrlRankStr = if (ctrlRank > 0) ctrlRank.toString() else "N/A"
            println("Control Rank (No Stage 0): \$ctrlRankStr")

            // 3. Stage 1 (Experiment): SR + Stage 0
            val expResult = AdaptiveFileDiscovery.filter(
                primaryReq = tc.srText,
                secondaryReq = secondaryReq,
                graph = graph,
                client = client,
                project = null
            )
            val expRank = expResult.relevantFiles.indexOfFirst { it.path.contains(tc.targetClass, ignoreCase = true) } + 1
            val expRankStr = if (expRank > 0) expRank.toString() else "N/A"
            println("Experiment Rank (With Stage 0): \$expRankStr")

            // Analyze Impact
            var impact = "Neutral"
            if (ctrlRank > 0 && expRank > 0) {
                if (expRank < ctrlRank) impact = "Improved"
                else if (expRank > ctrlRank) impact = "Degraded"
            } else if (ctrlRank == 0 && expRank > 0) {
                impact = "Found (New)"
            } else if (ctrlRank > 0 && expRank == 0) {
                impact = "Lost"
            }
            
            if (impact == "Degraded" || impact == "Lost") {
                downgradedCases.add("TC\$tcNum (\${tc.srText})\n- GT: \${tc.targetClass}\n- Control: \$ctrlRankStr -> Exp: \$expRankStr\n- Stage 0 Text: \$clarifyOutput\n")
            }

            // Append to table
            val srShort = if (tc.srText.length > 25) tc.srText.substring(0, 22) + "..." else tc.srText
            results.append(String.format("%-10s | %-30s | %-20s | %-12s | %-10s | %-15s | %-15s\n", 
                tc.type, srShort, tc.targetClass, gtInGraph, ctrlRankStr, expRankStr, impact))
        }

        println("\n\n=========================================================================")
        println("### FINAL RESULTS TABLE ###")
        println(results.toString())
        
        println("\n### DOWNGRADED CASES (순위 하락) ###")
        if (downgradedCases.isEmpty()) {
            println("None! (모든 케이스에서 순위가 유지되거나 상승함)")
        } else {
            downgradedCases.forEach { println(it) }
        }
    }
}
