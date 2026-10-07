package net.ib.ixpert.ops.wuwagent.agent.clarify

import net.ib.ixpert.ops.wuwagent.agent.RequirementAnalysisPipeline
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.ClarifyIntent
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.model.ChatCompletionResponse
import net.ib.ixpert.ops.wuwagent.model.ChatMessage
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.OllamaMessage
import net.ib.ixpert.ops.wuwagent.model.ToolDefinition
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ArchitectureLayer
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FileNode
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.GraphStatistics
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.SpringFileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [B-33] 정제문이 원문과 다를 때 Resolver → analyze() → 시드 프롬프트 경로 고정.
 * Router(executeAnalyzePipeline)는 private이며 IDE 의존성이 있어 호출하지 않고,
 * 같은 순서(Resolver 출력 → analyze)를 직접 호출한다 (B-35).
 */
class AnalyzeRefinedSeedPromptTest {

    /** 정해진 표지 시스템 프롬프트만 허용하고, 그 외 프롬프트는 기록 후 error로 즉시 실패시키는 Mock. */
    private class StrictRecordingLlm : LLMClient {
        val seedPrompts = mutableListOf<String>()
        val allUserPrompts = mutableListOf<String>()
        val unmatched = mutableListOf<String>()

        private fun route(systemPrompt: String, userPrompts: List<String>) {
            allUserPrompts.addAll(userPrompts)
            when {
                systemPrompt.contains("당신은 프로젝트 아키텍트입니다.") -> Unit
                systemPrompt.contains("당신은 코드 변경 범위 검증자입니다.") -> Unit
                systemPrompt.contains("당신은 시스템의 소스코드 및 메타그래프를 능동적으로 탐색하는 전문 AI 분석 에이전트입니다.") ->
                    seedPrompts.addAll(userPrompts)
                else -> {
                    unmatched.add(systemPrompt)
                    error("unmatched prompt in StrictRecordingLlm: [systemPrompt=$systemPrompt]")
                }
            }
        }

        override fun chat(
            systemPrompt: String,
            userCode: String,
            maxTokens: Int?,
            onChunk: ((String) -> Unit)?
        ): OllamaChatResponse {
            route(systemPrompt, listOf(userCode))
            return OllamaChatResponse(
                model = "strict-recording-llm",
                createdAt = "",
                message = OllamaMessage("assistant", "{}"),
                done = true
            )
        }

        override fun chatWithTools(
            systemPrompt: String,
            messages: List<ChatMessage>,
            maxTokens: Int?,
            tools: List<ToolDefinition>?,
            toolChoice: Any?,
            temperature: Double?
        ): ChatCompletionResponse? {
            route(systemPrompt, messages.mapNotNull { it.content })
            return null
        }

        override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
    }

    @Test
    fun testSeedPromptCarriesRefinedRequirementAndNotOriginalWhenTheyDiffer() = kotlinx.coroutines.runBlocking {
        val graph = ProjectGraph(
            generatedAt = java.time.Instant.now().toString(),
            projectRoot = "/test/root",
            files = mapOf(
                "com/example/TargetService.java" to FileNode(
                    path = "com/example/TargetService.java",
                    packageName = "com.example",
                    className = "TargetService",
                    fileType = SpringFileType.SERVICE,
                    layer = ArchitectureLayer.SERVICE
                )
            ),
            relationships = emptyList(),
            statistics = GraphStatistics()
        )

        val clarifyIntent = ClarifyIntent(
            originalRequirement = "원문 전용 문구 A",
            refinedRequirement = "정제문 전용 문구 B",
            anchorTokens = listOf("TargetService"),
            constraints = emptyList(),
            excludedFiles = emptyList(),
            graphHash = "dummyHash",
            contractVersion = "1.0"
        )

        val resolved = AnalyzeInputResolver.resolve(
            rawInput = clarifyIntent.originalRequirement,
            inMemoryIntent = clarifyIntent
        )
        assertEquals("Resolver는 정제문을 effectiveRequirement로 돌려줘야 함", "정제문 전용 문구 B", resolved.effectiveRequirement)

        val llm = StrictRecordingLlm()
        val pipeline = RequirementAnalysisPipeline(llm)
        val result = pipeline.analyze(
            primaryReq = resolved.effectiveRequirement,
            secondaryReq = "",
            projectGraph = graph,
            clarifyIntent = resolved.effectiveIntent,
            previousContract = null,
            projectRoot = null
        )

        assertNotNull(result)
        assertTrue("strict mock에서 표지와 일치하지 않는 프롬프트가 없어야 함: ${llm.unmatched}", llm.unmatched.isEmpty())
        val seedPrompt = llm.seedPrompts.firstOrNull { it.contains("요구사항(SR):") }
        assertNotNull("AgenticSeedSelector의 '요구사항(SR):' 시드 프롬프트가 기록되어야 함", seedPrompt)
        assertTrue(
            "시드 프롬프트에 정제문 전용 문구 B가 있어야 함",
            seedPrompt!!.contains("정제문 전용 문구 B")
        )
        assertFalse(
            "시드 프롬프트에 원문 전용 문구 A가 없어야 함",
            seedPrompt.contains("원문 전용 문구 A")
        )
    }
}
