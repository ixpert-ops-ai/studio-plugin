package net.ib.ixpert.ops.wuwagent.agent.clarify

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.*
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.OllamaMessage
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Test
import java.io.File

class Stage0ThreeScenarioObservationTest {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    private class MockDialogueLlmClient : LLMClient {
        override fun chat(
            systemPrompt: String,
            userCode: String,
            maxTokens: Int?,
            onChunk: ((String) -> Unit)?
        ): OllamaChatResponse? {
            val responseContent = when {
                systemPrompt.contains("소프트웨어 요구사항 정제 전문가") -> {
                    // Refinement mock
                    if (userCode.contains("Bizgo")) {
                        "기존 설문 발송 채널에 외부 Bizgo API를 연동하여 브랜드메시지 발송 채널을 추가하고 어드민을 개발한다."
                    } else if (userCode.contains("알림톡 배치는 건드리지 말고") || userCode.contains("AlimtalkTemplateBatchRepository")) {
                        "기존 알림톡 배치는 제외하고 신규 설문 발송 채널을 추가한다."
                    } else if (userCode.contains("SurveyServiceImpl")) {
                        "SurveyServiceImpl을 기반으로 설문 일괄 등록 기능을 구현한다."
                    } else {
                        userCode.substringAfter("[최초 요구사항]\n").substringBefore("\n\n").trim()
                    }
                }
                systemPrompt.contains("ConstraintKind 분류 기준") -> {
                    // Constraints extraction mock
                    if (userCode.contains("알림톡 배치는 건드리지 말고") || userCode.contains("제외")) {
                        """
                        [
                          {
                            "kind": "EXCLUDE_EXTERNAL",
                            "value": "알림톡 배치 수정 제외",
                            "rawStatement": "알림톡 배치는 건드리지 말고"
                          }
                        ]
                        """.trimIndent()
                    } else if (userCode.contains("Bizgo")) {
                        """
                        [
                          {
                            "kind": "INCLUDE_CHANNEL",
                            "value": "Bizgo API 연동",
                            "rawStatement": "외부 Bizgo API를 연동해서 브랜드메시지를 발송하도록 할 거야"
                          }
                        ]
                        """.trimIndent()
                    } else {
                        "[]"
                    }
                }
                systemPrompt.contains("개방형 질문") -> {
                    "브랜드메시지 발송을 위해 어떤 외부 API나 모듈을 연동할 계획인가요?"
                }
                else -> "{}"
            }

            return OllamaChatResponse(
                model = "mock-dialogue-qwen",
                createdAt = "",
                message = OllamaMessage("assistant", responseContent),
                done = true
            )
        }

        override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
    }

    @Test
    fun observeThreeClarifyScenarios() {
        val surveyAdminPath = File("C:/Workspace/HC_card_survey_admin/survey_admin/.meta/project-graph.json")
        val ismPath = File("C:/Workspace/graph/project-graph-i/project-graph.json")

        val surveyGraph = Gson().fromJson(surveyAdminPath.readText(), ProjectGraph::class.java).normalizeLegacyCollections()
        val ismGraph = Gson().fromJson(ismPath.readText(), ProjectGraph::class.java).normalizeLegacyCollections()
        val llmClient = MockDialogueLlmClient()

        println("================================================================================")
        println("=== STAGE 0 THREE SCENARIO DIALOGUE & INTENT LOSSLESS OBSERVATION ===")
        println("================================================================================")

        // ─────────────────────────────────────────────────────────────────────────────
        // 시나리오 1: 식별자가 섞인 요청 (SurveyServiceImpl 명시)
        // ─────────────────────────────────────────────────────────────────────────────
        println("\n\n################################################################################")
        println("### [시나리오 1] 식별자가 섞인 요청")
        println("### Initial SR: \"설문 일괄 등록 개발인데 SurveyServiceImpl 쪽이야\"")
        println("################################################################################")

        val scanner1 = Stage0GraphScanner(surveyGraph)
        val engine1 = Stage0ClarificationEngine(scanner1, surveyGraph, llmClient)

        // Turn 0: initSession
        val turn0_1 = engine1.initSession("설문 일괄 등록 개발인데 SurveyServiceImpl 쪽이야")
        println("\n[Turn 0 - Initial Proposals]")
        println("- Open Question: ${turn0_1.openQuestion ?: "(None)"}")
        println("- Proposed Items (${turn0_1.state.items.size}개):")
        turn0_1.state.items.forEachIndexed { i, it ->
            println("  ${i + 1}. [${it.hint.javaClass.simpleName}] ${it.statement} (Verdict: ${it.verdict}, Conf: ${it.confidence})")
        }

        // Turn 1: User explicitly confirms SurveyServiceImpl and finishes
        val userTurn1_1 = Stage0ClarificationEngine.UserInput(
            userStatement = "맞아 SurveyServiceImpl 파일만 수정하면 돼",
            isCompletionDeclared = true
        )
        val turn1_1 = engine1.processTurn(turn0_1.state, userTurn1_1)
        val intent1 = engine1.buildClarifyIntent(turn1_1.state)

        println("\n[Generated ClarifyIntent for Scenario 1]")
        println(gson.toJson(intent1))

        // ─────────────────────────────────────────────────────────────────────────────
        // 시나리오 2: 모호한 요청 (개방형 질문 유발 및 답변 정제)
        // ─────────────────────────────────────────────────────────────────────────────
        println("\n\n################################################################################")
        println("### [시나리오 2] 모호한 요청")
        println("### Initial SR: \"설문 발송에 브랜드메시지 채널 추가\"")
        println("################################################################################")

        val scanner2 = Stage0GraphScanner(surveyGraph)
        val engine2 = Stage0ClarificationEngine(scanner2, surveyGraph, llmClient)

        // Turn 0: initSession
        val turn0_2 = engine2.initSession("설문 발송에 브랜드메시지 채널 추가")
        println("\n[Turn 0 - Initial Proposals]")
        println("- Open Question: ${turn0_2.openQuestion ?: "(None)"}")
        println("- Proposed Items (${turn0_2.state.items.size}개):")
        turn0_2.state.items.take(5).forEachIndexed { i, it ->
            println("  ${i + 1}. [${it.hint.javaClass.simpleName}] ${it.statement}")
        }

        // Turn 1: User answers open question with Bizgo API
        val userTurn1_2 = Stage0ClarificationEngine.UserInput(
            userStatement = "외부 Bizgo API를 연동해서 브랜드메시지를 발송하도록 할 거야",
            isCompletionDeclared = true
        )
        val turn1_2 = engine2.processTurn(turn0_2.state, userTurn1_2)
        val intent2 = engine2.buildClarifyIntent(turn1_2.state)

        println("\n[Generated ClarifyIntent for Scenario 2]")
        println(gson.toJson(intent2))

        // ─────────────────────────────────────────────────────────────────────────────
        // 시나리오 3: 배제가 들어간 요청 (특정 파일/배치 제외)
        // ─────────────────────────────────────────────────────────────────────────────
        println("\n\n################################################################################")
        println("### [시나리오 3] 배제가 들어간 요청")
        println("### Initial SR: \"설문 발송 채널 추가\"")
        println("### User Utterance: \"알림톡 배치는 건드리지 말고, AlimtalkTemplateBatchRepository는 제외해줘\"")
        println("################################################################################")

        val scanner3 = Stage0GraphScanner(surveyGraph)
        val engine3 = Stage0ClarificationEngine(scanner3, surveyGraph, llmClient)

        // Turn 0: initSession
        val turn0_3 = engine3.initSession("설문 발송 채널 추가")

        // Target file to reject
        val batchRepoItem = turn0_3.state.items.find { it.statement.contains("AlimtalkTemplateBatchRepository") }
        val verdictUpdates = if (batchRepoItem != null) {
            mapOf(batchRepoItem.id to Verdict.REJECTED)
        } else {
            emptyMap()
        }

        // Turn 1: User rejects file and adds statement
        val userTurn1_3 = Stage0ClarificationEngine.UserInput(
            verdictUpdates = verdictUpdates,
            userStatement = "알림톡 배치는 건드리지 말고, AlimtalkTemplateBatchRepository는 제외해줘",
            isCompletionDeclared = true
        )
        val turn1_3 = engine3.processTurn(turn0_3.state, userTurn1_3)
        val intent3 = engine3.buildClarifyIntent(turn1_3.state)

        println("\n[Generated ClarifyIntent for Scenario 3]")
        println(gson.toJson(intent3))
    }
}
