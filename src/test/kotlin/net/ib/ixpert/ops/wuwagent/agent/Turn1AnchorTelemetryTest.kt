package net.ib.ixpert.ops.wuwagent.agent

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.model.*
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.AgenticSeedSelector
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Turn1AnchorTelemetryTest {

    private val gson = Gson()

    class MockLlmClient(private val toolCallsPerTurn: List<ToolCall>) : LLMClient {
        var currentTurn = 0

        override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): OllamaChatResponse? = null

        override fun chatWithTools(
            systemPrompt: String,
            messages: List<ChatMessage>,
            maxTokens: Int?,
            tools: List<ToolDefinition>?,
            toolChoice: Any?,
            temperature: Double?
        ): ChatCompletionResponse? {
            val tc = if (currentTurn < toolCallsPerTurn.size) toolCallsPerTurn[currentTurn] else toolCallsPerTurn.last()
            currentTurn++
            return ChatCompletionResponse(
                id = "mock-id-$currentTurn",
                choices = listOf(
                    ChatChoice(
                        index = 0,
                        message = ChatMessage(
                            role = "assistant",
                            content = "Turn $currentTurn reasoning",
                            toolCalls = listOf(tc)
                        ),
                        finishReason = "tool_calls"
                    )
                )
            )
        }

        override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = null
    }

    private fun loadGraph(path: String): ProjectGraph {
        val file = File(path)
        assertTrue(file.exists(), "Graph file must exist: $path")
        return gson.fromJson(file.readText(Charsets.UTF_8), ProjectGraph::class.java).normalizeLegacyCollections()
    }

    @Test
    fun testNormalCaseSurveyAdminPureKoreanNoAnchorMissing() {
        val graph = loadGraph("C:/Workspace/HC_card_survey_admin/survey_admin/.meta/project-graph.json")
        val toolCalls = listOf(
            ToolCall(
                id = "call-1",
                type = "function",
                function = ToolCallFunction(
                    name = "search_graph_nodes",
                    arguments = """{"query": "설문 발송 브랜드메시지", "domain_hint": "survey"}"""
                )
            ),
            ToolCall(
                id = "call-2",
                type = "function",
                function = ToolCallFunction(
                    name = "confirm_final_seeds",
                    arguments = """{"seed_classes": ["SurveyServiceImpl"], "rationale": "Found service"}"""
                )
            )
        )
        val client = MockLlmClient(toolCalls)
        val selector = AgenticSeedSelector(client)
        val result = selector.selectSeeds("기존 설문 발송에 브랜드메시지 발송 채널 추가 및 어드민 개발", graph)

        assertTrue(result.seedClasses.isNotEmpty())
        assertEquals("SurveyServiceImpl", result.seedClasses.first())
    }

    @Test
    fun testNormalCaseMemberMarketZeroMatchesDelegatesToTurn2() {
        val graph = loadGraph("C:/Workspace/member-market/.meta/project-graph.json")
        val toolCalls = listOf(
            ToolCall(
                id = "call-1",
                type = "function",
                function = ToolCallFunction(
                    name = "search_graph_nodes",
                    arguments = """{"query": "단말기 스펙", "domain_hint": null}"""
                )
            ),
            ToolCall(
                id = "call-2",
                type = "function",
                function = ToolCallFunction(
                    name = "search_graph_nodes",
                    arguments = """{"query": "Product", "domain_hint": "product"}"""
                )
            ),
            ToolCall(
                id = "call-3",
                type = "function",
                function = ToolCallFunction(
                    name = "confirm_final_seeds",
                    arguments = """{"seed_classes": ["ProductController"], "rationale": "Found controller"}"""
                )
            )
        )
        val client = MockLlmClient(toolCalls)
        val selector = AgenticSeedSelector(client)
        val result = selector.selectSeeds("상품 상세 내 단말기 스펙 조회", graph)

        assertTrue(result.seedClasses.isNotEmpty())
        assertEquals("ProductController", result.seedClasses.first())
    }

    @Test
    fun testNormalCaseCareMemberPureKoreanNoAnchorMissing() {
        val graph = loadGraph("C:/Workspace/graph/project-graph-i/project-graph.json")
        val toolCalls = listOf(
            ToolCall(
                id = "call-1",
                type = "function",
                function = ToolCallFunction(
                    name = "search_graph_nodes",
                    arguments = """{"query": "케어회원 조회", "domain_hint": "care"}"""
                )
            ),
            ToolCall(
                id = "call-2",
                type = "function",
                function = ToolCallFunction(
                    name = "confirm_final_seeds",
                    arguments = """{"seed_classes": ["CareMemberMgmtController"], "rationale": "Found controller"}"""
                )
            )
        )
        val client = MockLlmClient(toolCalls)
        val selector = AgenticSeedSelector(client)
        val result = selector.selectSeeds("케어회원 관리 화면 조회", graph)

        assertTrue(result.seedClasses.isNotEmpty())
        assertEquals("CareMemberMgmtController", result.seedClasses.first())
    }

    @Test
    fun testNegativeCasePDsbUseTriggersAnchorMissing() {
        val graph = loadGraph("C:/Workspace/graph/project-graph-i/project-graph.json")
        val toolCalls = listOf(
            ToolCall(
                id = "call-1",
                type = "function",
                function = ToolCallFunction(
                    name = "search_graph_nodes",
                    arguments = """{"query": "포인트 사용내역", "domain_hint": "point"}"""
                )
            ),
            ToolCall(
                id = "call-2",
                type = "function",
                function = ToolCallFunction(
                    name = "confirm_final_seeds",
                    arguments = """{"seed_classes": ["WfrPRequest"], "rationale": "Fallback pick"}"""
                )
            )
        )
        val client = MockLlmClient(toolCalls)
        val selector = AgenticSeedSelector(client)
        val result = selector.selectSeeds("개인별 포인트유형별 사용내역 조회 화면 및 엑셀 다운로드 개발", graph)

        assertTrue(result.seedClasses.isNotEmpty())
    }

    @Test
    fun testL1InteractiveClarificationRecoversPDsbUse() {
        val graph = loadGraph("C:/Workspace/graph/project-graph-i/project-graph.json")
        val toolCalls = listOf(
            // Turn 1: 1턴 순한글 검색 -> 55점 플래토 발생
            ToolCall(
                id = "call-1",
                type = "function",
                function = ToolCallFunction(
                    name = "search_graph_nodes",
                    arguments = """{"query": "포인트 사용내역", "domain_hint": "point"}"""
                )
            ),
            // Turn 2: 사용자 비즈니스 힌트("통계 리포트 화면") 수신 후 'st' 도메인으로 전환
            ToolCall(
                id = "call-2",
                type = "function",
                function = ToolCallFunction(
                    name = "search_graph_nodes",
                    arguments = """{"query": "pnt use", "domain_hint": "st"}"""
                )
            ),
            // Turn 3: PAtcUseResponse inspect하여 경로에서 'pstat' 디렉토리 약어 추출
            ToolCall(
                id = "call-3",
                type = "function",
                function = ToolCallFunction(
                    name = "inspect_node_detail",
                    arguments = """{"class_name": "PAtcUseResponse"}"""
                )
            ),
            // Turn 4: pstat 쿼리로 PDsbUseController 직격
            ToolCall(
                id = "call-4",
                type = "function",
                function = ToolCallFunction(
                    name = "search_graph_nodes",
                    arguments = """{"query": "pstat", "domain_hint": "st/pstat"}"""
                )
            ),
            // Turn 5: 핵심 Seed 3종 확정
            ToolCall(
                id = "call-5",
                type = "function",
                function = ToolCallFunction(
                    name = "confirm_final_seeds",
                    arguments = """{"seed_classes": ["PDsbUseController", "PDsbUseServiceImpl", "ECSITBISM015Mapper"], "rationale": "L1 hint guided to pstat statistics domain"}"""
                )
            )
        )
        val client = MockLlmClient(toolCalls)
        val mockBridge = L1ClarificationBridge { query, topCandidates, domains ->
            // 사용자가 입력한 순수 업무 교정 발화 시뮬레이션
            "통계 리포트 화면"
        }
        val selector = AgenticSeedSelector(client, clarificationBridge = mockBridge)
        val result = selector.selectSeeds("개인별 포인트유형별 사용내역 조회 화면 및 엑셀 다운로드 개발", graph)

        assertTrue(result.seedClasses.contains("PDsbUseController"))
        assertTrue(result.seedClasses.contains("PDsbUseServiceImpl"))
        assertTrue(result.seedClasses.contains("ECSITBISM015Mapper"))
        assertEquals(3, result.seedClasses.size)
    }

    @Test
    fun testHeadlessFallbackWhenBridgeReturnsNull() {
        val graph = loadGraph("C:/Workspace/graph/project-graph-i/project-graph.json")
        val toolCalls = listOf(
            ToolCall(
                id = "call-1",
                type = "function",
                function = ToolCallFunction(
                    name = "search_graph_nodes",
                    arguments = """{"query": "포인트 사용내역", "domain_hint": "point"}"""
                )
            ),
            ToolCall(
                id = "call-2",
                type = "function",
                function = ToolCallFunction(
                    name = "search_graph_nodes",
                    arguments = """{"query": "Point", "domain_hint": null}"""
                )
            ),
            ToolCall(
                id = "call-3",
                type = "function",
                function = ToolCallFunction(
                    name = "confirm_final_seeds",
                    arguments = """{"seed_classes": ["WfrPRequest"], "rationale": "Autonomous fallback pick"}"""
                )
            )
        )
        val client = MockLlmClient(toolCalls)
        // Bridge가 null인 헤드리스/비대면 환경 시뮬레이션
        val selector = AgenticSeedSelector(client, clarificationBridge = null)
        val result = selector.selectSeeds("개인별 포인트유형별 사용내역 조회 화면 및 엑셀 다운로드 개발", graph)

        assertTrue(result.seedClasses.isNotEmpty())
        assertEquals("WfrPRequest", result.seedClasses.first())
    }
}

