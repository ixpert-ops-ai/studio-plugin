package net.ib.ixpert.ops.wuwagent.agent.completeness

import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.LlmSeedSelector
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FileNode
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.model.ChatCompletionResponse
import net.ib.ixpert.ops.wuwagent.model.ChatMessage
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.ToolDefinition
import org.junit.Test

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.SpringFileType
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ArchitectureLayer

/**
 * [Characterization Test]
 * 이 테스트는 현재 `LlmSeedSelector`의 Track 1 (Lexical Pre-filter) 동작과 그 한계(유실 버그)를 
 * 있는 그대로 관찰하고 박제하기 위한 테스트입니다.
 * 
 * - 현재 `testPartialMatching`은 별도의 실패(red) 단언(assertion) 없이 프롬프트 후보군을 출력(println)만 하며 정상 통과(green)합니다.
 * - 7번 케이스에서 `ChatMessage`가 유실되는 현상은 **현재 시스템의 알려진 버그**입니다.
 * - 향후 Track 1/2 병렬 실행 및 순위 기반 융합(RRF) 리팩토링이 완료되면, 
 *   이 테스트에 명시적인 Assertion을 추가하여 7번 케이스에서 `ChatMessage`가 유실되지 않고 
 *   반드시 후보군에 포함됨을 검증(green)하도록 업데이트해야 합니다.
 */
class LlmSeedSelectorTest {
    
    @Test
    fun testPartialMatching() {
        var lastPrompt = ""
        val client = object : LLMClient {
            override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): OllamaChatResponse? = null
            override fun chatWithTools(systemPrompt: String, messages: List<ChatMessage>, maxTokens: Int?, tools: List<ToolDefinition>?, toolChoice: Any?): ChatCompletionResponse? {
                lastPrompt = messages.last().content ?: ""
                return ChatCompletionResponse(id = "1", choices = emptyList())
            }
            override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = null
        }
        
        fun createNode(c: String, p: String) = FileNode(
            path = p, packageName = "p", className = c, 
            fileType = SpringFileType.values()[0], layer = ArchitectureLayer.UNKNOWN
        )
        
        val filesMap = mapOf(
            "p1" to createNode("Shop", "p1"),
            "p2" to createNode("TransactionService", "p2"),
            "p3" to createNode("ChatMessage", "p3"),
            "p4" to createNode("Product", "p4"),
            "p5" to createNode("ProductService", "p5"),
            "p6" to createNode("ProductController", "p6")
        )
        
        val graph = ProjectGraph(
            generatedAt = "2026", projectRoot = "/", files = filesMap, relationships = emptyList(), statistics = net.ib.ixpert.ops.wuwagent.service.metagraph.model.GraphStatistics(0,0,0,0,0,0,0,0,0,0,0,0,0)
        )
        
        val selector = LlmSeedSelector(client)
        
        // Scenario 1: Exact match
        selector.selectSeeds("Shop 엔티티의 리뷰 집계 오류 수정", graph)
        println("=== Scenario 1: Exact Match ===\n$lastPrompt")
        
        // Scenario 2: Prefix
        selector.selectSeeds("TransactionServ 에서 완료 상태 변경", graph)
        println("=== Scenario 2: Prefix Match ===\n$lastPrompt")
        
        // Scenario 3: Substring
        selector.selectSeeds("Message 엔티티에 읽음 처리 추가", graph)
        println("=== Scenario 3: Substring ===\n$lastPrompt")
        
        // Scenario 4: Typo
        selector.selectSeeds("Prodcut 엔티티에 원산지 추가", graph)
        println("=== Scenario 4: Typo ===\n$lastPrompt")
        
        // Scenario 5: False Match
        selector.selectSeeds("Product 엔티티의 오류 수정", graph)
        println("=== Scenario 5: False Match ===\n$lastPrompt")
        
        // Scenario 6: Composite SR (Prefix + Prefix)
        selector.selectSeeds("Product 엔티티 오류 수정 및 TransactionServ에 로깅 추가", graph)
        println("=== Scenario 6: Composite (Prefix + Prefix) ===\n$lastPrompt")
        
        // Scenario 7: Composite SR (Prefix + Substring)
        selector.selectSeeds("Product 엔티티 수정 및 Message 읽음 처리", graph)
        println("=== Scenario 7: Composite (Prefix + Substring) ===\n$lastPrompt")
    }
}
