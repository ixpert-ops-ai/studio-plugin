package net.ib.ixpert.ops.wuwagent.agent.stage0

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.model.ChatCompletionResponse
import net.ib.ixpert.ops.wuwagent.model.ChatMessage
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.OllamaMessage
import net.ib.ixpert.ops.wuwagent.model.ToolDefinition
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.AdaptiveFileDiscovery
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.LlmSeedSelector
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import net.ib.ixpert.ops.wuwagent.service.metagraph.trimmer.ContextTrimmer
import org.junit.Test
import java.io.File
import org.junit.Ignore

class E2EPipelineIntegrationTest {
    
    private val artifactDir = "C:\\Users\\dffrp\\.gemini\\antigravity\\brain\\1cf1c9f8-1e55-4577-826a-dabb138299c6"

    @Test
    fun testE2ESimulationReport() {
        val graphFile = File("C:\\Workspace\\member-market\\.meta\\project-graph.json")
        if (!graphFile.exists()) return
        
        val gson = GsonBuilder().disableHtmlEscaping().create()
        val graph = gson.fromJson(graphFile.readText(Charsets.UTF_8), ProjectGraph::class.java)

        // Custom SimpleOllamaClient mapped to OpenAI API for vLLM testing
        val ollamaClient = object : LLMClient {
            val serverUrl = "https://vllm.ixpertops.cloud/v1/chat/completions"
            val modelName = "Qwen/Qwen3.6-35B-A3B-FP8" 

            override fun chat(
                systemPrompt: String,
                userCode: String,
                maxTokens: Int?,
                onChunk: ((String) -> Unit)?
            ): OllamaChatResponse? {
                try {
                    val messages = listOf(
                        mapOf("role" to "system", "content" to systemPrompt),
                        mapOf("role" to "user", "content" to userCode)
                    )
                    // OpenAI Format
                    val jsonPayload = GsonBuilder().create().toJson(
                        mapOf(
                            "model" to modelName,
                            "messages" to messages,
                            "stream" to false,
                            "max_tokens" to (maxTokens ?: 1500),
                            "temperature" to 0.0
                        )
                    )
                    
                    val connection = java.net.URL(serverUrl).openConnection() as java.net.HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.connectTimeout = 10000
                    connection.readTimeout = 120000 // 2 minutes per request for reasoning models
                    connection.doOutput = true
                    connection.outputStream.write(jsonPayload.toByteArray(Charsets.UTF_8))
                    
                    val responseCode = connection.responseCode
                    if (responseCode != 200) {
                        val errorStream = connection.errorStream?.bufferedReader()?.readText()
                        throw Exception("HTTP \$responseCode : \$errorStream")
                    }

                    val responseStr = connection.inputStream.bufferedReader().readText()
                    val jsonObj = JsonParser.parseString(responseStr).asJsonObject
                    if (jsonObj.has("error")) throw Exception(jsonObj.get("error").toString())
                    
                    // Parse OpenAI response format -> OllamaChatResponse
                    val choices = jsonObj.getAsJsonArray("choices")
                    val messageObj = choices.get(0).asJsonObject.getAsJsonObject("message")
                    
                    var content = ""
                    if (messageObj.has("content") && !messageObj.get("content").isJsonNull) {
                        content = messageObj.get("content").asString
                    } else if (messageObj.has("reasoning_content") && !messageObj.get("reasoning_content").isJsonNull) {
                        content = messageObj.get("reasoning_content").asString
                    }
                    
                    return OllamaChatResponse(model = modelName, createdAt = "", message = OllamaMessage("assistant", content), done = true)
                } catch (e: Exception) {
                    println("Test LLM API failed: \${e.message}")
                    return null
                }
            }

            override fun chatWithTools(
                systemPrompt: String,
                messages: List<ChatMessage>,
                maxTokens: Int?,
                tools: List<ToolDefinition>?,
                toolChoice: Any?
            ): ChatCompletionResponse? = null

            override fun fetchModels(baseUrl: String, apiKey: String): List<String>? {
                // Return dummy to bypass the check
                return listOf("Qwen/Qwen3.6-35B-A3B-FP8")
            }
        }
        
        // Ensure graph file exists
        if (!graphFile.exists()) {
            println("Graph file not found. Skipping E2E test.")
            return
        }

        val projectRoot = File("C:\\Workspace\\member-market")
        val report = StringBuilder()
        report.appendLine("# [E2E Simulation Report (LlmSeedSelector)]")
        report.appendLine("This report shows the full pipeline from raw text SR to final code skeleton via actual LLM inference.\n")

        val targetTcs = (1..10).map { "TC-%02d".format(it) }

        for (tcId in targetTcs) {
            val tc = TestCaseDefinitions.cases.find { it.srId == tcId } ?: continue
            
            report.appendLine("## Scenario: ${tc.srId} - ${tc.input.title}")
            report.appendLine("> **Requirement**: ${tc.input.description}\n")

            // E2E Pipeline Core
            val discoveryResult = AdaptiveFileDiscovery.filter(
                primaryReq = "[${tc.srId}] " + tc.input.title,
                secondaryReq = tc.input.description,
                graph = graph,
                client = ollamaClient,
                project = null,
                seedSelector = LlmSeedSelector(ollamaClient) // Use real LLM Seed Selector
            )

            val fileContents = mutableMapOf<String, String>()
            for (scoredFile in discoveryResult.relevantFiles) {
                val path = scoredFile.path
                val file = File(projectRoot, path)
                if (file.exists()) {
                    fileContents[path] = file.readText(Charsets.UTF_8)
                }
            }

            val trimmedFiles = mutableMapOf<String, String>()
            for (entry in fileContents) {
                val path = entry.key
                val content = entry.value
                val isSeed = discoveryResult.relevantFiles.find { it.path == path }?.hopDistance == 0
                val trimmed = ContextTrimmer.trimFile(path, content, isSeed)
                trimmedFiles[path] = trimmed
            }

            report.appendLine("### [Stage 1] Discovery Trace (by LLM)")
            report.appendLine("- **LLM Seed Classes**: ${discoveryResult.metadata.seedClasses.joinToString()}")
            report.appendLine("- **Change Intent**: ${discoveryResult.metadata.changeIntent}")
            report.appendLine("- **Total Candidates**: ${discoveryResult.metadata.totalCandidates}")
            report.appendLine("- **Filtered To**: ${discoveryResult.metadata.filteredTo}\n")

            report.appendLine("#### Final List & Scores")
            discoveryResult.relevantFiles.forEach { sf ->
                report.appendLine("- `${sf.path}` (Score: ${sf.score}, Hop: ${sf.hopDistance}, Via: ${sf.discoveryReason})")
            }
            report.appendLine()

            report.appendLine("### [Stage 2] Context Trimming")
            if (trimmedFiles.isEmpty()) {
                report.appendLine("- No files.")
            }
            trimmedFiles.forEach { (path, trimmed) ->
                val originalLength = fileContents[path]?.length ?: 0
                val trimmedLength = trimmed.length
                val ratio = if (originalLength > 0) ((originalLength - trimmedLength).toDouble() / originalLength * 100).toInt() else 0
                report.appendLine("- `$path` : ${originalLength}B -> **${trimmedLength}B** (${ratio}% token saving)")
            }
            report.appendLine()

            report.appendLine("### [Stage 4] Prompt Injections")
            
            if (discoveryResult.suggestedNewFiles.isNotEmpty()) {
                report.appendLine("#### New File Proposals")
                discoveryResult.suggestedNewFiles.forEachIndexed { index, proposal ->
                    report.appendLine("**${index + 1}. ${proposal.suggestedPath.substringAfterLast('/')}**")
                    report.appendLine("- Path: `${proposal.suggestedPath}`")
                    report.appendLine("- Reason: ${proposal.reason}")
                    report.appendLine("- Reference: ${proposal.referencePattern}\n")
                }
            }

            report.appendLine("#### Modified Files (Samples)")
            var count = 0
            for (entry in trimmedFiles) {
                if (count >= 2) break
                val path = entry.key
                val content = entry.value
                report.appendLine("##### `$path`")
                val ext = path.substringAfterLast('.', "txt")
                report.appendLine("```$ext")
                report.appendLine(content)
                report.appendLine("```")
                report.appendLine()
                count++
            }
            report.appendLine("---\n")
        }

        File(artifactDir, "e2e_simulation_report.md").writeText(report.toString(), Charsets.UTF_8)
    }
}
