package net.ib.ixpert.ops.wuwagent.agent.clarify

import com.google.gson.Gson
import com.google.gson.JsonParser
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.*
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.OllamaMessage
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class Stage0SurveyAdminLiveLlmVerificationTest {

    class DirectOpenAiClient(
        private val endpointUrl: String = "http://vllm.ixpertops.cloud/v1/chat/completions",
        private val modelName: String = "qwen3.8-27b"
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
                    "temperature" to 0.1,
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

    private fun loadSurveyAdminGraph(): ProjectGraph? {
        val path = File("C:/Workspace/HC_card_survey_admin/survey_admin/.meta/project-graph.json")
        if (!path.exists()) return null
        return Gson().fromJson(path.readText(), ProjectGraph::class.java)
    }

    @Test
    fun testPhase33_LiveLlmSurveyAdminThreeUtteranceTypes() {
        val graph = loadSurveyAdminGraph()
        assertNotNull("survey_admin 메타그래프 로드 성공", graph)

        val liveLlm = DirectOpenAiClient()
        val scanner = Stage0GraphScanner(
            graph = graph!!,
            minSpecificityScore = 1.0,
            proposalBudget = 10,
            localDomainOverrides = emptyMap(),
            maxBridgeDegree = 15,
            maxExternalShared = 3
        )
        val engine = Stage0ClarificationEngine(scanner, graph, liveLlm)

        val initialReq = "설문 관리 화면 개발 및 주소록 검색 매퍼 연동"
        println("\n============================================================")
        println("[Stage 0] Live LLM 3종 발화 E2E 실증 (survey_admin)")
        println("초기 요구사항: " + initialReq)
        println("============================================================")

        val turn0 = engine.initSession(initialReq)
        val initialItems = turn0.state.items
        println("\n[Turn 0 초기 후보군 (" + initialItems.size + "개)]")
        initialItems.forEachIndexed { idx, item ->
            val path = (item.hint as? LinkHint.ExistingRef)?.filePath ?: item.statement
            println(" " + (idx + 1) + ". [" + item.id + "] " + path + " | verdict=" + item.verdict + " | conf=" + item.confidence)
        }

        val addressItem = initialItems.find { it.statement.contains("sql_address.xml") || (it.hint as? LinkHint.ExistingRef)?.filePath?.contains("sql_address.xml") == true }
        assertNotNull("초기 후보 목록에 sql_address.xml이 포함되어 있어야 함", addressItem)
        assertEquals("초기 상태는 PENDING이어야 함", Verdict.PENDING, addressItem?.verdict)

        val otherItemsBefore = initialItems.filter { it.id != addressItem?.id }.map { it.id to it.verdict }

        // 1. (가) 정확 매핑: "주소 매퍼 빼줘"
        val utterance1 = "주소 매퍼 빼줘"
        println("\n------------------------------------------------------------")
        println(">>> 사용자 발화 (가 - 정확 매핑): \"" + utterance1 + "\"")
        println("------------------------------------------------------------")

        val turn1 = engine.processUtterance(turn0.state, utterance1)
        println("[Turn 1 AI 응답 되비추기]: " + turn1.echoBackMessage)

        val addressItemTurn1 = turn1.state.items.find { it.id == addressItem?.id }
        assertNotNull(addressItemTurn1)
        println("[Turn 1 대상 카드 상태]: id=" + addressItemTurn1?.id + ", verdict=" + addressItemTurn1?.verdict + ", reason=" + addressItemTurn1?.rejectionReason)

        assertEquals("실환경 LLM 번역 결과 sql_address.xml 카드가 REJECTED로 전이되어야 함", Verdict.REJECTED, addressItemTurn1?.verdict)
        assertEquals(RejectionReason.FILE_MISMATCH, addressItemTurn1?.rejectionReason)

        val otherItemsTurn1 = turn1.state.items.filter { it.id != addressItem?.id }.map { it.id to it.verdict }
        assertEquals("주소 매퍼 제외 시 다른 모든 카드의 verdict 유지", otherItemsBefore, otherItemsTurn1)
        println("=> [관찰 확인] 주소 매퍼 외 " + otherItemsTurn1.size + "개 카드의 로컬 상태 100% 보존 확인")

        // 2. (나) 닫힌 선택 강제: "결제 모듈 빼줘" (survey_admin 후보에 없는 것)
        val utterance2 = "결제 모듈 빼줘"
        println("\n------------------------------------------------------------")
        println(">>> 사용자 발화 (나 - 닫힌 선택 강제): \"" + utterance2 + "\"")
        println("------------------------------------------------------------")

        val itemsCountBeforeTurn2 = turn1.state.items.size
        val turn2 = engine.processUtterance(turn1.state, utterance2)
        println("[Turn 2 AI 응답 되비추기]: " + turn2.echoBackMessage)

        assertEquals("후보 목록 외 대상 지목 시 후보 개수 불변 (No-Op)", itemsCountBeforeTurn2, turn2.state.items.size)
        val addressItemTurn2 = turn2.state.items.find { it.id == addressItem?.id }
        assertEquals("이전 제외 상태(REJECTED) 유지", Verdict.REJECTED, addressItemTurn2?.verdict)
        assertNotNull(turn2.echoBackMessage)
        println("=> [관찰 확인] 실환경 LLM 환각 차단 및 No-Op (0건 훼손) 완벽 작동 확인")

        // 3. (다) 모호 발화: "음... 글쎄요"
        val utterance3 = "음... 글쎄요"
        println("\n------------------------------------------------------------")
        println(">>> 사용자 발화 (다 - 모호 발화): \"" + utterance3 + "\"")
        println("------------------------------------------------------------")

        val turn3 = engine.processUtterance(turn2.state, utterance3)
        println("[Turn 3 AI 응답 되비추기]: " + turn3.echoBackMessage)

        assertEquals("모호 발화 시 후보 개수 불변 (No-Op)", itemsCountBeforeTurn2, turn3.state.items.size)
        assertNotNull(turn3.echoBackMessage)
        println("=> [관찰 확인] 실환경 LLM 모호 발화 안전 수렴 및 No-Op 확인")

        // 4. 대화 완결: "분석 시작해줘" -> COMPLETE
        val utterance4 = "분석 시작해줘"
        println("\n------------------------------------------------------------")
        println(">>> 사용자 발화 (완결): \"" + utterance4 + "\"")
        println("------------------------------------------------------------")

        val turn4 = engine.processUtterance(turn3.state, utterance4)
        println("[Turn 4 AI 응답 되비추기]: " + turn4.echoBackMessage)
        assertTrue("isReadyForStage1 == true", turn4.isReadyForStage1)

        val clarifyIntent = engine.buildClarifyIntent(turn4.state)
        println("\n[최종 생성된 ClarifyIntent 계약]")
        println(" - refinedRequirement: " + clarifyIntent.refinedRequirement)
        println(" - anchorTokens: " + clarifyIntent.anchorTokens)
        println(" - excludedFiles: " + clarifyIntent.excludedFiles)
        println(" - constraints: " + clarifyIntent.constraints.size + "개")

        assertTrue("ClarifyIntent.excludedFiles에 sql_address.xml 포함", clarifyIntent.excludedFiles.any { it.contains("sql_address.xml") })

        println("\n============================================================")
        println(">>> Phase 3-3 (7b) Live LLM 3종 발화 E2E 검증 100% 성공!")
        println("============================================================\n")
    }
}
