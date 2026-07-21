package net.ib.ixpert.ops.wuwagent.agent

import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.OllamaMessage
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*
import org.junit.Test
import org.junit.Assert.*

class PipelineGuardIntegrationTest {

    class DummyLLMClient : LLMClient {
        var callCount = 0
        override fun chat(
            systemPrompt: String,
            userCode: String,
            maxTokens: Int?,
            onChunk: ((String) -> Unit)?
        ): OllamaChatResponse? {
            return null
        }
        
        override fun chatWithTools(
            systemPrompt: String,
            messages: List<net.ib.ixpert.ops.wuwagent.model.ChatMessage>,
            maxTokens: Int?,
            tools: List<net.ib.ixpert.ops.wuwagent.model.ToolDefinition>?,
            toolChoice: Any?
        ): net.ib.ixpert.ops.wuwagent.model.ChatCompletionResponse? {
            callCount++
            println("--- LLM CALL $callCount ---")
            println("System Prompt: $systemPrompt")
            
            val isDiscovery = systemPrompt.contains("Seed", ignoreCase = true) || systemPrompt.contains("추출", ignoreCase = true) || systemPrompt.contains("분석가", ignoreCase = true)
            val isVerifier = systemPrompt.contains("검증", ignoreCase = true) || systemPrompt.contains("관련성", ignoreCase = true) || systemPrompt.contains("Verifier", ignoreCase = true)
            
            val toolName = tools?.firstOrNull()?.function?.name ?: "unknown"
            println("Tool Name: $toolName")
            
            val content = if (toolName == "submit_seeds" || isDiscovery) {
                """
                {
                  "reasoning": "Test reasoning",
                  "changeIntent": "CREATE",
                  "layerHint": ["SERVICE"],
                  "frontendRelevant": false,
                  "seedClasses": ["OrderController"]
                }
                """.trimIndent()
            } else if (toolName == "propose_new_files") {
                """
                {
                  "proposals": [
                    {
                      "suggestedPath": "src/main/java/NewOrderService.java",
                      "suggestedFileType": "SERVICE",
                      "reason": "need service",
                      "referencePattern": "OrderController.java"
                    }
                  ]
                }
                """.trimIndent()
            } else if (toolName == "submit_verification" || isVerifier) {
                """
                {
                  "reasoning": "Verified.",
                  "fileVerdicts": [
                    {
                      "filePath": "src/main/java/OrderController.java",
                      "verdict": "REQUIRED",
                      "reason": "This is absolutely needed."
                    },
                    {
                      "filePath": "src/main/java/NewOrderService.java",
                      "verdict": "REQUIRED",
                      "reason": "This is also absolutely needed."
                    }
                  ]
                }
                """.trimIndent()
            } else "{}"

            println("Returning content for tool '$toolName': $content")
            
            return net.ib.ixpert.ops.wuwagent.model.ChatCompletionResponse(
                id = "test-123",
                choices = listOf(
                    net.ib.ixpert.ops.wuwagent.model.ChatChoice(
                        index = 0,
                        message = net.ib.ixpert.ops.wuwagent.model.ChatMessage(
                            role = "assistant",
                            content = "",
                            toolCalls = listOf(
                                net.ib.ixpert.ops.wuwagent.model.ToolCall(
                                    id = "call-1",
                                    type = "function",
                                    function = net.ib.ixpert.ops.wuwagent.model.ToolCallFunction(
                                        name = toolName,
                                        arguments = content
                                    )
                                )
                            )
                        ),
                        finishReason = "tool_calls"
                    )
                )
            )
        }
        override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = null
    }

    @Test
    fun `test guard is invoked without breaking pipeline`() {
        kotlinx.coroutines.runBlocking {
        val client = DummyLLMClient()
        val pipeline = RequirementAnalysisPipeline(null, client)
        
        val projectGraph = ProjectGraph(
            generatedAt = "now",
            projectRoot = "fake-project",
            frameworkType = FrameworkType.SPRING_BOOT_JPA,
            files = mapOf(
                "src/main/java/OrderController.java" to FileNode(
                    path = "src/main/java/OrderController.java",
                    packageName = "com.example",
                    className = "OrderController",
                    layer = ArchitectureLayer.PRESENTATION,
                    fileType = SpringFileType.CONTROLLER,
                    methods = emptyList()
                )
            ),
            relationships = emptyList(),
            statistics = GraphStatistics(0, 0, 0)
        )
        
        val result = pipeline.analyze("Test Requirement", "", projectGraph)
        
        // Assert the pipeline finishes successfully
        assertNotNull(result)
        println("Target Files: ${result.targetFiles.map { it.path }}")
        assertTrue("OrderController should be present", result.targetFiles.any { it.path.contains("OrderController.java") })
        assertTrue("NewOrderService should be present", result.targetFiles.any { it.path.contains("NewOrderService.java") })
        
        // Assert log was created
        val logDir = java.io.File("fake-project", ".wuwagent")
        val logFile = java.io.File(logDir, "shadow_logs.jsonl")
        assertTrue("Shadow log file should be created by GuardIntegration", logFile.exists())
        
        // Read log and verify SrFacts heuristics
        val lines = logFile.readLines()
        assertTrue(lines.isNotEmpty())
        val lastLog = lines.last()
        
        assertTrue("Log should contain srFactsSource heuristic", lastLog.contains("\"srFactsSource\":\"heuristic-from-pipeline-output\""))
        
        // Parse the JSON and verify the heuristic output
        val json = com.google.gson.JsonParser.parseString(lastLog).asJsonObject
        val verdict = json.get("verdict").asString
        
        // We mocked NewOrderService.java (CREATE), so addsNewMethod should be true.
        // It's a JPA project and creating a service doesn't necessarily trigger block unless it creates a DAO or entity without XML or vice-versa.
        // But the main point is that the verdict must be logically sound without NPE.
        assertTrue(verdict == "PASS" || verdict == "WOULD_BLOCK" || verdict == "WARN")
        
        // Cleanup
        logDir.deleteRecursively()
        }
    }
}
