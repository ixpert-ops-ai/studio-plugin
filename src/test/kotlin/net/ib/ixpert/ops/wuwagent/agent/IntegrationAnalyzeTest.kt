package net.ib.ixpert.ops.wuwagent.agent

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.OllamaMessage
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import net.ib.ixpert.ops.wuwagent.model.ChatCompletionResponse
import net.ib.ixpert.ops.wuwagent.model.ChatMessage
import net.ib.ixpert.ops.wuwagent.model.ToolDefinition
import kotlinx.coroutines.runBlocking

class TestVllmClient : LLMClient {
    private val gson = Gson()
    private val serverUrl = "http://vllm.ixpertops.cloud/v1/chat/completions"
    private val modelName = "Qwen/Qwen3.8-27B-FP8"
    
    override fun chat(
        systemPrompt: String,
        userCode: String,
        maxTokens: Int?,
        onChunk: ((String) -> Unit)?
    ): OllamaChatResponse? {
        val messagesList = listOf(
            mapOf("role" to "system", "content" to systemPrompt),
            mapOf("role" to "user", "content" to userCode)
        )
        val requestBody = mapOf(
            "model" to modelName,
            "messages" to messagesList,
            "stream" to false,
            "temperature" to 0.0,
            "max_tokens" to (maxTokens ?: 8192)
        )
        
        val jsonPayload = gson.toJson(requestBody)
        val responseStr = sendPost(jsonPayload)
        
        val json = com.google.gson.JsonParser.parseString(responseStr).asJsonObject
        val content = json.getAsJsonArray("choices")
            ?.get(0)?.asJsonObject
            ?.getAsJsonObject("message")
            ?.get("content")?.asString ?: ""
            
        return OllamaChatResponse(
            model = modelName,
            createdAt = null,
            message = OllamaMessage("assistant", content),
            done = true
        )
    }

    override fun chatWithTools(
        systemPrompt: String,
        messages: List<ChatMessage>,
        maxTokens: Int?,
        tools: List<ToolDefinition>?,
        toolChoice: Any?, temperature: Double?
    ): ChatCompletionResponse? {
        val requestMessages = mutableListOf<Map<String, Any?>>()
        requestMessages.add(mapOf("role" to "system", "content" to systemPrompt))
        
        for (msg in messages) {
            val map = mutableMapOf<String, Any?>("role" to msg.role)
            if (msg.content != null) map["content"] = msg.content
            if (msg.name != null) map["name"] = msg.name
            if (msg.toolCallId != null) map["tool_call_id"] = msg.toolCallId
            if (msg.toolCalls != null) map["tool_calls"] = msg.toolCalls
            requestMessages.add(map)
        }

        val requestBody = mutableMapOf<String, Any?>(
            "model" to modelName,
            "messages" to requestMessages,
            "stream" to false,
            "temperature" to 0.0,
            "max_tokens" to (maxTokens ?: 8192)
        )
        
        if (!tools.isNullOrEmpty()) {
            requestBody["tools"] = tools
            if (toolChoice != null) {
                requestBody["tool_choice"] = toolChoice
            }
        }
        
        val jsonPayload = gson.toJson(requestBody)
        val responseStr = sendPost(jsonPayload)
        
        return gson.fromJson(responseStr, ChatCompletionResponse::class.java)
    }

    override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = listOf(modelName)
    
    private fun sendPost(jsonPayload: String): String {
        val url = URL(serverUrl)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        connection.connectTimeout = 60000
        connection.readTimeout = 120000
        
        connection.outputStream.use { os ->
            os.write(jsonPayload.toByteArray(Charsets.UTF_8))
        }
        
        val status = connection.responseCode
        if (status >= 400) {
            val errorStream = connection.errorStream
            val errorResponse = if (errorStream != null) {
                InputStreamReader(errorStream).readText()
            } else {
                "No error stream"
            }
            throw RuntimeException("HTTP " + status + ": " + errorResponse)
        }
        
        return InputStreamReader(connection.inputStream).readText()
    }
}

class IntegrationAnalyzeTest {

    @Test
    fun testFullPipeline() {
        val graphFile = File("C:/Workspace/member-market/.meta/project-graph.json")
        assumeTrue("Graph file exists", graphFile.exists())
        val graph = Gson().fromJson(graphFile.readText(), ProjectGraph::class.java).normalizeLegacyCollections()
        println("[DEBUG-TEST] resourceNodes size: ${graph.resourceNodes.size}")
        graph.resourceNodes.forEach { println("[DEBUG-TEST] resource path: ${it.path}") }

        val client = TestVllmClient()
        val pipeline = RequirementAnalysisPipeline(client)
        
        val primaryReq = "상품 조회와 상품 등록 기능을 추가하고, 상품 정보를 반환하는 DTO를 수정해주세요."
        val secondaryReq = ""
        
        val gts = listOf(
            "member-market-api/src/main/java/com/membermarket/domain/product/Product.java",
            "member-market-api/src/main/java/com/membermarket/domain/product/ProductRepository.java",
            "member-market-api/src/main/java/com/membermarket/api/product/ProductService.java",
            "member-market-api/src/main/java/com/membermarket/api/product/dto/ProductCreateRequest.java",
            "member-market-api/src/main/java/com/membermarket/api/product/ProductController.java",
            "member-market-web/src/views/product/ProductCreateView.vue"
        )
        
        val outFile = File("C:/Workspace/member-market/integration-test-result.txt")
        outFile.writeText("=== Full Pipeline E2E Integration Test (vLLM) ===\n")
        outFile.appendText("SR: " + primaryReq + "\n")
        outFile.appendText("Temperature: 0.0\n")
        outFile.appendText("Note: 통합 베이스라인(변경 3종 모두 적용) + Stage 0 접근범위 갭 미포함\n\n")

        val results = mutableListOf<RequirementAnalysisResult>()
        
        runBlocking {
            for (i in 1..3) {
                println("Running iteration " + i + "...")
                outFile.appendText("--- Iteration " + i + " ---\n")
                
                try {
                    val result = pipeline.analyze(primaryReq, secondaryReq, graph, emptyList()) { chunk -> 
                        print(chunk)
                    }
                    results.add(result)
                    
                    val allTargets = result.targetFiles.map { it.path }.toSet()
                    outFile.appendText("Total Target Files: " + allTargets.size + "\n")
                    for (path in allTargets) {
                        val isGt = gts.contains(path)
                        outFile.appendText("  " + path + " (GT: " + isGt + ")\n")
                    }
                    outFile.appendText("\n")
                } catch(e: Exception) {
                    outFile.appendText("Iteration " + i + " failed: " + e.message + "\n")
                    e.printStackTrace()
                }
            }
        }
        
        outFile.appendText("=== Summary Table (3 Iterations) ===\n")
        outFile.appendText(String.format("%-85s | %-5s | %-5s | %-5s\n", "Ground Truth File", "Run 1", "Run 2", "Run 3"))
        outFile.appendText("-".repeat(110) + "\n")
        
        for (gt in gts) {
            val runs = (0 until 3).map { i ->
                if (i < results.size) {
                    val all = results[i].targetFiles.map { it.path }
                    if (all.contains(gt)) "O" else "X"
                } else "N/A"
            }
            outFile.appendText(String.format("%-85s | %-5s | %-5s | %-5s\n", gt, runs[0], runs[1], runs[2]))
        }
        
        outFile.appendText("\n=== False Positives (Non-GT Files) ===\n")
        for (i in 0 until results.size) {
            val all = results[i].targetFiles.map { it.path }
            val fp = all.filter { !gts.contains(it) }
            outFile.appendText("Run " + (i+1) + " FPs (" + fp.size + "):\n")
            fp.forEach { outFile.appendText("  - " + it + "\n") }
        }
        
        println("Test complete. Results written to " + outFile.absolutePath)
    }
}
