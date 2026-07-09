package net.ib.ixpert.ops.wuwagent.agent

import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.model.*
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.GraphStatistics
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Paths

class ShadowModeIntegrationTest {

    @Test
    fun `섀도우 모드에서 가드가 차단 판정을 내려도 원본 파일 목록은 변형 없이 그대로 통과한다`() {
        val mockClient = object : LLMClient {
            override fun chat(
                systemPrompt: String,
                userCode: String,
                maxTokens: Int?,
                onChunk: ((String) -> Unit)?
            ): OllamaChatResponse? = null
            
            override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()

            override fun chatWithTools(
                systemPrompt: String,
                messages: List<ChatMessage>,
                maxTokens: Int?,
                tools: List<ToolDefinition>?,
                toolChoice: Any?
            ): ChatCompletionResponse {
                return ChatCompletionResponse(
                    id = "1",
                    choices = listOf(
                        ChatChoice(
                            index = 0,
                            finishReason = "stop",
                            message = ChatMessage(
                                role = "assistant",
                                content = "{}",
                                toolCalls = listOf(
                                    ToolCall(
                                        id = "1",
                                        type = "function",
                                        function = ToolCallFunction(
                                            name = "submit_verification",
                                            arguments = """
                                                {
                                                    "fileVerdicts": [
                                                        { "filePath": "/src/A.java", "verdict": "REQUIRED", "reason": "test" },
                                                        { "filePath": "/src/B.java", "verdict": "REQUIRED", "reason": "test" }
                                                    ],
                                                    "reasoning": "test reasoning"
                                                }
                                            """.trimIndent()
                                        )
                                    )
                                )
                            )
                        )
                    )
                )
            }
        }
        
        val projectGraph = ProjectGraph(
            generatedAt = "now",
            projectRoot = "/",
            files = emptyMap(),
            relationships = emptyList(),
            statistics = GraphStatistics()
        )
        
        val verifier = FileRelevanceVerifier(mockClient, projectGraph, Paths.get("docs"))
        
        val candidates = listOf(
            TargetFileSpec(1, "/src/A.java", "MODIFY", "test"),
            TargetFileSpec(2, "/src/B.java", "MODIFY", "test")
        )
        
        val output = verifier.verify("query", candidates, additionalSystemPrompt = "## 섀도우 테스트 프로파일")
        
        assertEquals(2, output.files.size)
        assertEquals("/src/A.java", output.files[0].path)
        assertEquals("/src/B.java", output.files[1].path)
    }
}
