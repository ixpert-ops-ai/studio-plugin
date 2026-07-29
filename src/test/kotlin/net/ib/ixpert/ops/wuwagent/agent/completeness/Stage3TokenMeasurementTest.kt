package net.ib.ixpert.ops.wuwagent.agent.completeness

import org.junit.Test
import org.junit.Ignore
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import net.ib.ixpert.ops.wuwagent.agent.FileRelevanceVerifier
import net.ib.ixpert.ops.wuwagent.agent.TargetFileSpec
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import java.nio.file.Paths
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.model.*

class Stage3TokenMeasurementTest {

    @Ignore("Manual run only: requires real VLLM server")
    @Test
    fun testStage3TokenBudget() {
        val graphFile = File("C:\\Users\\dffrp\\Downloads\\project-graph_a\\project-graph.json")
        val gson = GsonBuilder().setPrettyPrinting().create()
        val graph = gson.fromJson(graphFile.readText(), ProjectGraph::class.java)
        
        // Take 30 actual nodes from the graph
        val targetSpecs = graph.files.keys.take(30).mapIndexed { index, path ->
            TargetFileSpec(
                order = index + 1,
                path = path,
                type = "MODIFY",
                description = "Score: 100, Via: TEST"
            )
        }
        
        var capturedPrompt = ""
        var capturedSystemPrompt = ""
        var capturedTools: List<ToolDefinition>? = null
        
        val dummyClient = object : LLMClient {
            override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): OllamaChatResponse? = null
            override fun chatWithTools(systemPrompt: String, messages: List<ChatMessage>, maxTokens: Int?, tools: List<ToolDefinition>?, toolChoice: Any?): ChatCompletionResponse? {
                capturedSystemPrompt = systemPrompt
                capturedPrompt = messages.first().content ?: ""
                capturedTools = tools
                return null
            }
            override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = null
        }
        
        val verifier = FileRelevanceVerifier(dummyClient, graph, Paths.get("dummy/docs"))
        verifier.verify("테스트 요구사항", targetSpecs)
        
        val requestBodyMap = mapOf(
            "model" to "Qwen/Qwen3.6-35B-A3B-FP8",
            "max_tokens" to 1,
            "messages" to listOf(
                mapOf("role" to "system", "content" to capturedSystemPrompt),
                mapOf("role" to "user", "content" to capturedPrompt)
            ),
            "tools" to capturedTools,
            "tool_choice" to mapOf(
                "type" to "function",
                "function" to mapOf("name" to "submit_verification")
            )
        )
        
        val requestBodyJson = gson.toJson(requestBodyMap)
        
        println("\n=== Sending Request to VLLM Server for Stage 3 (30 candidates) ===")
        val url = URL("http://vllm.ixpertops.cloud/v1/chat/completions")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json; utf-8")
        connection.setRequestProperty("Accept", "application/json")
        connection.doOutput = true
        
        try {
            connection.outputStream.use { os ->
                val input = requestBodyJson.toByteArray(Charsets.UTF_8)
                os.write(input, 0, input.size)
            }
            
            val responseCode = connection.responseCode
            println("Response Code: $responseCode")
            
            val inputStream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val responseText = inputStream.bufferedReader().use { it.readText() }
            
            val respJson = gson.fromJson(responseText, JsonObject::class.java)
            
            if (respJson.has("usage")) {
                val usage = respJson.getAsJsonObject("usage")
                val promptTokens = usage.get("prompt_tokens")?.asInt
                println("\n=== RESULT ===")
                println("Stage 3 (30 files) prompt_tokens: $promptTokens")
            } else {
                println("No usage object found in response.")
                println(responseText)
            }
        } catch (e: Exception) {
            println("Error during HTTP request: ${e.message}")
        }
    }
}
