package net.ib.ixpert.ops.wuwagent.agent.clarify

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.*
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.OllamaMessage
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class Stage0ThreeScenarioLiveLlmObservationTest {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    class DirectVllmClient(
        private val endpointUrl: String = "http://vllm.ixpertops.cloud/v1/chat/completions",
        private val modelName: String = "qwen3.8-27b",
        private val temperature: Double = 0.1
    ) : LLMClient {
        private val gson = Gson()

        override fun chat(
            systemPrompt: String,
            userCode: String,
            maxTokens: Int?,
            onChunk: ((String) -> Unit)?
        ): OllamaChatResponse? {
            return try {
                val url = URL(endpointUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.connectTimeout = 30000
                conn.readTimeout = 60000
                conn.doOutput = true

                val requestBody = mapOf(
                    "model" to modelName,
                    "messages" to listOf(
                        mapOf("role" to "system", "content" to systemPrompt),
                        mapOf("role" to "user", "content" to userCode)
                    ),
                    "temperature" to temperature,
                    "max_tokens" to (maxTokens ?: 500),
                    "stream" to false
                )

                val jsonStr = gson.toJson(requestBody)
                conn.outputStream.use { os ->
                    os.write(jsonStr.toByteArray(Charsets.UTF_8))
                }

                val responseCode = conn.responseCode
                if (responseCode != 200) {
                    val errorText = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    System.err.println("[LiveLLM] Error ($responseCode): $errorText")
                    return null
                }

                val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                val parsed = JsonParser.parseString(responseText).asJsonObject
                val choices = parsed.getAsJsonArray("choices")
                val content = choices[0].asJsonObject.getAsJsonObject("message").get("content").asString

                OllamaChatResponse(
                    model = modelName,
                    createdAt = "",
                    message = OllamaMessage("assistant", content),
                    done = true
                )
            } catch (e: Exception) {
                System.err.println("[LiveLLM] Exception: " + e.message)
                null
            }
        }

        override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = listOf(modelName)
    }

    @Test
    fun runThreeScenariosThreeRunsWithLiveLlm() {
        org.junit.Assume.assumeTrue(
            "Live vLLM 테스트 실행은 -DrunLiveLlmTests=true 또는 환경변수 RUN_LIVE_LLM_TESTS=true 명시 시에만 활성화됩니다.",
            System.getProperty("runLiveLlmTests") == "true" || System.getenv("RUN_LIVE_LLM_TESTS") == "true"
        )

        val surveyAdminPath = File("C:/Workspace/HC_card_survey_admin/survey_admin/.meta/project-graph.json")
        val ismPath = File("C:/Workspace/graph/project-graph-i/project-graph.json")
        val apcPath = File("C:/Workspace/graph/project-graph-a/project-graph.json")

        val surveyGraph = Gson().fromJson(surveyAdminPath.readText(), ProjectGraph::class.java).normalizeLegacyCollections()
        val ismGraph = Gson().fromJson(ismPath.readText(), ProjectGraph::class.java).normalizeLegacyCollections()
        val apcGraph = Gson().fromJson(apcPath.readText(), ProjectGraph::class.java).normalizeLegacyCollections()
        val liveLlm = DirectVllmClient(temperature = 0.1)

        println("================================================================================")
        println("=== STAGE 0 FOUR SCENARIO LIVE VLLM LOSSLESS HANDOVER OBSERVATION (3 RUNS) ===")
        println("=== LLM 출처: live (model=qwen3.8-27b, temp=0.1) ===")
        println("================================================================================")

        val scenarios = listOf(
            Tuple4(
                "시나리오 1 (식별자 명시)",
                "설문 일괄 등록 개발인데 SurveyServiceImpl 쪽이야",
                listOf("SurveyServiceImpl 파일만 수정하면 돼"),
                surveyGraph
            ),
            Tuple4(
                "시나리오 2 (범위/배제 제약)",
                "설문 발송 채널에 브랜드메시지 추가",
                listOf("알림톡 배치는 건드리지 말고 SurveyServiceImpl만 수정할 거야"),
                surveyGraph
            ),
            Tuple4(
                "시나리오 3 (외부 API 연동)",
                "설문 발송 채널에 브랜드메시지 추가",
                listOf("외부 Bizgo API를 연동해서 브랜드메시지를 발송하도록 할 거야"),
                surveyGraph
            ),
            Tuple4(
                "시나리오 4 (APC 복합 식별자 4종)",
                "교통카드 발급정보 신규서비스 개발 건 (SAPACMM0802S01 참고). APCMMTrcdIsSVC 신규 추가, aCMBTBAPC024DEM.selTrcdIsInf 참조하여 조회",
                listOf("APCMMTrcdIsSVC와 selTrcdIsInf 위주로 작업해줘"),
                apcGraph
            )
        )

        for ((scName, originalReq, userStmts, targetGraph) in scenarios) {
            println("\n\n" + "#".repeat(80))
            println("### [$scName]")
            println("### Original SR: \"$originalReq\"")
            println("### User Statements: $userStmts")
            println("#".repeat(80))

            val scanner = Stage0GraphScanner(
                graph = targetGraph,
                minSpecificityScore = 1.0,
                proposalBudget = 10,
                localDomainOverrides = emptyMap()
            )

            for (run in 1..3) {
                println("\n--- [Run $run / 3] ---")
                val engine = Stage0ClarificationEngine(scanner, targetGraph, liveLlm)

                var state = engine.initSession(originalReq).state
                for (stmt in userStmts) {
                    val turnResult = engine.processTurn(
                        state,
                        Stage0ClarificationEngine.UserInput(userStatement = stmt)
                    )
                    state = turnResult.state
                }

                val intent = engine.buildClarifyIntent(state)
                val audit = intent.retentionAudit

                println("  [LLM 출처: live]")
                println("  - rawRefinedRequirement: \"${audit?.rawRefinedRequirement}\"")
                println("  - effectiveRefinedRequirement: \"${intent.refinedRequirement}\"")
                println("  - expectedIdentifiers: ${audit?.expectedIdentifiers}")
                println("  - missingBeforeFix: ${audit?.missingBeforeFix}")
                println("  - wasRetainedWithoutModification: ${audit?.wasRetainedWithoutModification}")
                println("  - scopeModifierDropped: ${audit?.scopeModifierDropped}")
                println("  - auxiliaryIdentifiers: ${audit?.auxiliaryIdentifiers}")
                println("  - userStatements (${intent.userStatements.size}건): ${intent.userStatements}")
                println("  - constraints (${intent.constraints.size}건):")
                for (c in intent.constraints) {
                    println("      * [${c.kind}] value=\"${c.value}\", evidence=\"${c.evidence}\", raw=\"${c.rawStatement}\"")
                }
            }
        }
    }

    private data class Tuple4<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
