package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.model.ChatMessage

import net.ib.ixpert.ops.wuwagent.model.ToolDefinition
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Test
import java.io.File
import org.junit.Assert.assertTrue

class Stage0GateTest {
    @Test
    fun `test Stage 0 - Clarify Engine BM25 and LLM Selection`() {
        val file = File("C:/Workspace/graph/project-graph-i/project-graph.json")
        if (!file.exists()) {
            println("File not found")
            return
        }
        val gson = Gson()
        val jsonString = file.readText(Charsets.UTF_8)
        val normalizedGraph = gson.fromJson(jsonString, ProjectGraph::class.java).normalizeLegacyCollections()

        // Mock LLM client to just print the prompt and return a dummy response
        val mockLlmClient = object : LLMClient {
            override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse? {
                // Mock extracting "상품" from "주문제작여부 컬럼 추가"
                val responseMsg = net.ib.ixpert.ops.wuwagent.model.OllamaMessage(role = "assistant", content = "상품")
                return net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse(
                    model = "mock",
                    createdAt = "",
                    message = responseMsg,
                    done = true
                )
            }
            override fun chatWithTools(systemPrompt: String, messages: List<ChatMessage>, maxTokens: Int?, tools: List<ToolDefinition>?, toolChoice: Any?, temperature: Double?): net.ib.ixpert.ops.wuwagent.model.ChatCompletionResponse? {
                return null 
            }
            override fun fetchModels(baseUrl: String, apiKey: String): List<String>? {
                return null
            }
        }

        val selector = LlmSeedSelector(mockLlmClient)
        val srText = "주문제작여부 컬럼 추가"
        println("Running Stage 0 with SR Text: $srText")
        
        val result = selector.selectSeeds(srText, normalizedGraph)
        println("Output SeedClasses: ${result.seedClasses}")
    }
}
