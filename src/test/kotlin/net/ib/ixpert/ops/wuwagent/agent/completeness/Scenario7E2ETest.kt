package net.ib.ixpert.ops.wuwagent.agent.completeness

import net.ib.ixpert.ops.wuwagent.agent.RequirementAnalysisPipeline
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.ChatCompletionResponse
import net.ib.ixpert.ops.wuwagent.model.ChatMessage
import net.ib.ixpert.ops.wuwagent.model.ToolDefinition
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.Ignore
import org.junit.Assert.assertTrue
import java.io.File
import kotlinx.coroutines.runBlocking

class Scenario7E2ETest {
    private val graphJsonPath = "C:/Workspace/member-market/.meta/project-graph.json"
    private lateinit var graph: ProjectGraph

    @Before
    fun setup() {
        val graphFile = File(graphJsonPath)
        assumeTrue("Skipping real graph tests because the graph file does not exist", graphFile.exists())
        val gson = com.google.gson.Gson()
        graph = gson.fromJson(graphFile.readText(), ProjectGraph::class.java)
    }

    @Ignore("Manual run only: requires real VLLM server")
    @Test
    fun `test Scenario 7 E2E Pipeline with Real LLM`() {
        val primaryReq = "Product 엔티티 수정 및 Message 읽음 처리"
        val secondaryReq = ""
        val srText = "$primaryReq\n$secondaryReq"
        
        println("=== Scenario 7: Partial Match Drop Bug (E2E) ===")
        println("SR: \$primaryReq")
        
        // We need a real LLMClient. We can use the VLLM endpoint.
        val client = object : LLMClient {
            val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
            
            override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): OllamaChatResponse? {
                // Not used in this pipeline
                return null
            }
            
            override fun chatWithTools(systemPrompt: String, messages: List<ChatMessage>, maxTokens: Int?, tools: List<ToolDefinition>?, toolChoice: Any?): ChatCompletionResponse? {
                val requestBodyMap = mutableMapOf<String, Any>(
                    "model" to "Qwen/Qwen3.6-35B-A3B-FP8",
                    "messages" to listOf(
                        mapOf("role" to "system", "content" to systemPrompt)
                    ) + messages.map { mapOf("role" to it.role, "content" to it.content) }
                )
                
                if (maxTokens != null) requestBodyMap["max_tokens"] = maxTokens
                if (tools != null) requestBodyMap["tools"] = tools
                if (toolChoice != null) requestBodyMap["tool_choice"] = toolChoice
                
                val url = java.net.URL("http://vllm.ixpertops.cloud/v1/chat/completions")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json; utf-8")
                connection.setRequestProperty("Accept", "application/json")
                connection.doOutput = true
                
                try {
                    connection.outputStream.use { os ->
                        val input = gson.toJson(requestBodyMap).toByteArray(Charsets.UTF_8)
                        os.write(input, 0, input.size)
                    }
                    
                    val responseCode = connection.responseCode
                    val inputStream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
                    val responseText = inputStream.bufferedReader().use { it.readText() }
                    
                    if (responseCode in 200..299) {
                        return gson.fromJson(responseText, ChatCompletionResponse::class.java)
                    } else {
                        println("LLM API Error: \$responseCode")
                        println(responseText)
                        return null
                    }
                } catch (e: Exception) {
                    println("Exception during LLM call: \${e.message}")
                    return null
                }
            }
            override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = null
        }
        
        val pipeline = RequirementAnalysisPipeline(project = null, client = client)
        runBlocking {
            pipeline.analyze(primaryReq, secondaryReq, graph, emptyList()) { chunk ->
                print(chunk)
            }
        }
    }

    @Ignore("Manual run only: requires real VLLM server")
    @Test
    fun `test Scenario 1 E2E Pipeline (Exact Match Regression)`() {
        val primaryReq = "Shop 엔티티의 리뷰 집계 오류 수정"
        runE2EPipeline(primaryReq, "Scenario 1")
    }

    @Ignore("Manual run only: requires real VLLM server")
    @Test
    fun `test Scenario 2 E2E Pipeline (Prefix Match Regression)`() {
        val primaryReq = "TransactionServ 에서 완료 상태 변경"
        runE2EPipeline(primaryReq, "Scenario 2")
    }

    private fun runE2EPipeline(primaryReq: String, scenarioName: String) {
        val srText = "$primaryReq\n"
        println("=== $scenarioName (E2E) ===")
        println("SR: $primaryReq")
        
        val client = object : LLMClient {
            val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
            override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): OllamaChatResponse? = null
            
            override fun chatWithTools(systemPrompt: String, messages: List<ChatMessage>, maxTokens: Int?, tools: List<ToolDefinition>?, toolChoice: Any?): ChatCompletionResponse? {
                val requestBodyMap = mutableMapOf<String, Any>(
                    "model" to "Qwen/Qwen3.6-35B-A3B-FP8",
                    "messages" to listOf(mapOf("role" to "system", "content" to systemPrompt)) + messages.map { mapOf("role" to it.role, "content" to it.content) }
                )
                if (maxTokens != null) requestBodyMap["max_tokens"] = maxTokens
                if (tools != null) requestBodyMap["tools"] = tools
                if (toolChoice != null) requestBodyMap["tool_choice"] = toolChoice
                
                val url = java.net.URL("http://vllm.ixpertops.cloud/v1/chat/completions")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json; utf-8")
                connection.setRequestProperty("Accept", "application/json")
                connection.doOutput = true
                try {
                    connection.outputStream.use { os ->
                        val input = gson.toJson(requestBodyMap).toByteArray(Charsets.UTF_8)
                        os.write(input, 0, input.size)
                    }
                    val responseCode = connection.responseCode
                    val inputStream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
                    val responseText = inputStream.bufferedReader().use { it.readText() }
                    
                    if (responseCode in 200..299) {
                        return gson.fromJson(responseText, ChatCompletionResponse::class.java)
                    } else {
                        println("LLM API Error: $responseCode")
                        println(responseText)
                        return null
                    }
                } catch (e: Exception) {
                    println("Exception during LLM call: ${e.message}")
                    return null
                }
            }
            override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = null
        }
        
        val pipeline = RequirementAnalysisPipeline(project = null, client = client)
        runBlocking {
            pipeline.analyze(primaryReq, "", graph, emptyList()) { chunk ->
                print(chunk)
            }
        }
    }
}
