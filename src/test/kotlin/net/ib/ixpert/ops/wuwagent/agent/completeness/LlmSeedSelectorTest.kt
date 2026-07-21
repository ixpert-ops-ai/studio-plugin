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
