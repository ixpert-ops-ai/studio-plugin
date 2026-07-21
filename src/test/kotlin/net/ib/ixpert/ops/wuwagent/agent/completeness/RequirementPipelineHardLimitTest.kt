package net.ib.ixpert.ops.wuwagent.agent.completeness

import net.ib.ixpert.ops.wuwagent.agent.RequirementAnalysisPipeline
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.model.ChatCompletionResponse
import net.ib.ixpert.ops.wuwagent.model.ChatMessage
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.ToolDefinition
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import com.google.gson.GsonBuilder
import kotlinx.coroutines.runBlocking

class RequirementPipelineHardLimitTest {

    @Test
    fun testHardLimitBoundary() = runBlocking {
        val client = object : LLMClient {
            override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): OllamaChatResponse? = null
            override fun chatWithTools(systemPrompt: String, messages: List<ChatMessage>, maxTokens: Int?, tools: List<ToolDefinition>?, toolChoice: Any?): ChatCompletionResponse? = null
            override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = null
        }
        val gson = GsonBuilder().create()
        val pipeline = RequirementAnalysisPipeline(client)
        
        fun createGraphJson(size: Int): String {
            val filesStr = (1..size).joinToString(",") { "\"f${it}\": {\"className\": \"C${it}\", \"path\": \"p${it}\", \"packagePath\": \"p\", \"role\": \"UNKNOWN\"}" }
            return """{"frameworkType": "SPRING_BOOT", "generatedAt": "2026", "projectRoot": "/", "statistics": {}, "relationships": [], "resourceNodes": [], "files": { $filesStr }}"""
        }
        
        // N = 200 (Should pass with Soft Nudge)
        val json200 = createGraphJson(200)
        val graph200 = gson.fromJson(json200, ProjectGraph::class.java)
        
        var onChunkOutput = ""
        try {
            pipeline.analyze(
                primaryReq = "test",
                secondaryReq = "",
                projectGraph = graph200,
                enhancedRequirements = emptyList(),
                onChunk = { chunk -> onChunkOutput += chunk }
            )
        } catch (e: IllegalArgumentException) {
            if (e.message?.contains("대상 파일이 너무 많습니다") == true) {
                fail("N=200 should not throw Hard Limit exception.")
            } else {
                throw e
            }
        } catch (e: Exception) {
            // It might be fine if it throws something later in the pipeline
        }
        
        assertTrue("N=200 should reach Soft Nudge and emit Tip message", onChunkOutput.contains("Tip:") && onChunkOutput.contains("200개 파일을 대상으로 탐색"))
        
        // N = 201 (Should block)
        val json201 = createGraphJson(201)
        val graph201 = gson.fromJson(json201, ProjectGraph::class.java)
        
        var caught = false
        try {
            pipeline.analyze(
                primaryReq = "test",
                secondaryReq = "",
                projectGraph = graph201,
                enhancedRequirements = emptyList()
            )
        } catch (e: IllegalArgumentException) {
            if (e.message?.contains("대상 파일이 너무 많습니다") == true) {
                caught = true
            } else {
                println("N=201 threw different IllegalArgumentException: ${e.message}")
                e.printStackTrace()
            }
        } catch (e: Exception) {
            println("N=201 threw unexpected exception: ${e.message}")
            e.printStackTrace()
        }
        
        assertTrue("N=201 should throw Hard Limit exception.", caught)
    }
}
