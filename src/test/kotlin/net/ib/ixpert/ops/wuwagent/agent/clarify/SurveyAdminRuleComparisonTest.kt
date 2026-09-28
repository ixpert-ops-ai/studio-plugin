package net.ib.ixpert.ops.wuwagent.agent.clarify

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.AdaptiveFileDiscovery
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Test
import java.io.File

class SurveyAdminRuleComparisonTest {

    private class FailingLlmClient : LLMClient {
        override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): OllamaChatResponse? = null
        override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = null
    }

    @Test
    fun compareInputsUnderRuleFallback() {
        val path = File("C:/Workspace/HC_card_survey_admin/survey_admin/.meta/project-graph.json")
        val graph = Gson().fromJson(path.readText(), ProjectGraph::class.java).normalizeLegacyCollections()
        val failingClient = FailingLlmClient()

        val gt = listOf(
            "SurveyServiceImpl", "SurveyDaoImpl", "SurveyDao", "SurveyDto",
            "sql_survey.xml", "survey_write.jsp", "survey.write.js",
            "survey_list.jsp", "survey.list.js",
            "BrandmessageTemplateBatchJob", "BizgoApiServiceImpl"
        )

        val inputA = "기존 설문 발송에 브랜드메시지 발송 채널 추가 및 어드민 개발"
        val inputB = "SurveyServiceImpl, sql_survey.xml 기반 기존 설문 발송에 브랜드메시지 발송 채널 추가 및 어드민 개발"

        fun runInput(name: String, sr: String) {
            println("=========================================================================")
            println("▶ Testing $name: \"$sr\"")
            println("=========================================================================")
            val result = AdaptiveFileDiscovery.filter(
                primaryReq = sr,
                secondaryReq = "",
                graph = graph,
                client = failingClient,
                project = null,
                projectBasePath = "C:/Workspace/HC_card_survey_admin/survey_admin"
            )

            val meta = result.metadata
            println("1. Seeds (${meta.seedClasses.size}개): ${meta.seedClasses}")
            println("2. Total Candidates Expanded: ${meta.totalCandidates}개")
            println("3. Relevant Files Selected (${result.relevantFiles.size}개):")
            result.relevantFiles.forEachIndexed { i, f ->
                val isGt = gt.any { g -> f.path.contains(g, ignoreCase = true) || f.className.contains(g, ignoreCase = true) }
                val mark = if (isGt) "★ [GT]" else "  [FP]"
                println("   ${i+1}. $mark ${f.path} (Score: ${f.score}, Reason: ${f.discoveryReason})")
            }

            val survivedGt = gt.filter { g -> result.relevantFiles.any { f -> f.path.contains(g, ignoreCase = true) || f.className.contains(g, ignoreCase = true) } }
            println("4. GT Recall: ${survivedGt.size} / ${gt.size}")
            println("   Survived GTs: $survivedGt")
            val missingGt = gt.filter { it !in survivedGt }
            println("   Missing GTs: $missingGt")
            println()
        }

        runInput("Input A (자연어 원문)", inputA)
        runInput("Input B (식별자 추가)", inputB)
    }
}
