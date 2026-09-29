package net.ib.ixpert.ops.wuwagent.agent.clarify

import com.google.gson.Gson
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
import java.util.concurrent.atomic.AtomicInteger

class Stage0B12ReAskLiveLlmObservationTest {

    class TrackingVllmClient(
        private val endpointUrl: String = "http://vllm.ixpertops.cloud/v1/chat/completions",
        private val modelName: String = "qwen3.8-27b",
        private val temperature: Double = 0.1
    ) : LLMClient {
        private val gson = Gson()
        val successCount = AtomicInteger(0)
        val failCount = AtomicInteger(0)

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
                    System.err.println("[LiveLLM] Error (" + responseCode + "): " + errorText)
                    failCount.incrementAndGet()
                    return null
                }

                val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                val parsed = JsonParser.parseString(responseText).asJsonObject
                val choices = parsed.getAsJsonArray("choices")
                val content = choices[0].asJsonObject.getAsJsonObject("message").get("content").asString

                successCount.incrementAndGet()
                OllamaChatResponse(
                    model = modelName,
                    createdAt = "",
                    message = OllamaMessage("assistant", content),
                    done = true
                )
            } catch (e: Exception) {
                System.err.println("[LiveLLM] Exception: " + e.message)
                failCount.incrementAndGet()
                null
            }
        }

        override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = listOf(modelName)
    }

    data class ScenarioSpec(
        val name: String,
        val turn1Req: String,
        val turn2Stmt: String,
        val turn1Identifiers: List<String>,
        val graph: ProjectGraph
    )

    @Test
    fun runTwoTurnObservation_Scenario1And4_ThreeRunsEach() {
        org.junit.Assume.assumeTrue(
            "Live vLLM 테스트 실행은 -DrunLiveLlmTests=true 또는 환경변수 RUN_LIVE_LLM_TESTS=true 명시 시에만 활성화됩니다.",
            System.getProperty("runLiveLlmTests") == "true" || System.getenv("RUN_LIVE_LLM_TESTS") == "true"
        )

        val surveyAdminPath = File("C:/Workspace/HC_card_survey_admin/survey_admin/.meta/project-graph.json")
        val apcPath = File("C:/Workspace/graph/project-graph-a/project-graph.json")

        val surveyGraph = Gson().fromJson(surveyAdminPath.readText(), ProjectGraph::class.java).normalizeLegacyCollections()
        val apcGraph = Gson().fromJson(apcPath.readText(), ProjectGraph::class.java).normalizeLegacyCollections()

        val specs = listOf(
            ScenarioSpec(
                name = "시나리오 1",
                turn1Req = "설문 일괄 등록 개발인데 SurveyServiceImpl 쪽이야",
                turn2Stmt = "SurveyServiceImpl 파일만 수정하면 돼",
                turn1Identifiers = listOf("SurveyServiceImpl"),
                graph = surveyGraph
            ),
            ScenarioSpec(
                name = "시나리오 4",
                turn1Req = "교통카드 발급정보 신규서비스 개발 건 (SAPACMM0802S01 참고). APCMMTrcdIsSVC 신규 추가, aCMBTBAPC024DEM.selTrcdIsInf 참조하여 조회",
                turn2Stmt = "APCMMTrcdIsSVC와 selTrcdIsInf 위주로 작업해줘",
                turn1Identifiers = listOf("SAPACMM0802S01", "APCMMTrcdIsSVC", "aCMBTBAPC024DEM", "selTrcdIsInf"),
                graph = apcGraph
            )
        )

        println("\n" + "=".repeat(100))
        println("=== 2턴 LIVE LLM 측정 결과 (시나리오 1 & 4 각 3회) ===")
        println("=".repeat(100))

        for (spec in specs) {
            println("\n### [" + spec.name + "]")
            val scanner = Stage0GraphScanner(
                graph = spec.graph,
                minSpecificityScore = 1.0,
                proposalBudget = 10,
                localDomainOverrides = emptyMap()
            )

            for (run in 1..3) {
                val liveLlm = TrackingVllmClient(temperature = 0.1)
                val engine = Stage0ClarificationEngine(scanner, spec.graph, liveLlm)

                // Turn 1: initSession
                val turn1Result = engine.initSession(spec.turn1Req)
                val turn1OpenQ = turn1Result.openQuestion ?: "(질문 없음)"

                // Turn 2: processTurn
                val turn2Result = engine.processTurn(
                    turn1Result.state,
                    Stage0ClarificationEngine.UserInput(userStatement = spec.turn2Stmt)
                )

                val turn2ConfirmedPaths = turn2Result.state.items
                    .filter { it.verdict == Verdict.CONFIRMED }
                    .mapNotNull { (it.hint as? LinkHint.ExistingRef)?.filePath ?: (it.hint as? LinkHint.NewCreation)?.let { "NewCreation({it.className ?: \"신규\"})" } }
                
                val turn2PendingPaths = turn2Result.state.items
                    .filter { it.verdict == Verdict.PENDING }
                    .mapNotNull { (it.hint as? LinkHint.ExistingRef)?.filePath }

                val turn2OpenQ = turn2Result.openQuestion ?: "(질문 없음)"
                
                // 다시 물은 식별자 검사: 1턴 식별자 중 2턴 openQuestion에 포함된 것
                val reAsked = spec.turn1Identifiers.filter { id ->
                    turn2OpenQ.contains(id, ignoreCase = true)
                }

                println("--- Run " + run + " ---")
                println("  LLM 호출 성공/실패: " + liveLlm.successCount.get() + " / " + liveLlm.failCount.get())
                println("  1턴 식별자: " + spec.turn1Identifiers)
                println("  1턴 openQuestion: \"" + turn1OpenQ + "\"")
                println("  2턴 CONFIRMED 경로: " + turn2ConfirmedPaths)
                println("  2턴 PENDING 경로: " + turn2PendingPaths)
                println("  2턴 openQuestion: \"" + turn2OpenQ + "\"")
                println("  다시 물은 식별자 수: " + reAsked.size + " (목록: " + reAsked + ")")
                println("  모델 ID: qwen3.8-27b")

                org.junit.Assert.assertEquals(
                    "2턴 openQuestion에서 1턴 식별자를 다시 묻지 않아야 함 (" + spec.name + " Run " + run + ")",
                    0,
                    reAsked.size
                )
            }
        }
    }
}