package net.ib.ixpert.ops.wuwagent.agent

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Paths
import java.time.Instant

class FileRelevanceVerifierTest {

    @Test
    fun testFileRelevanceVerifier_EmptyCandidates() {
        val graph = ProjectGraph(
            generatedAt = Instant.now().toString(),
            projectRoot = "/test",
            statistics = GraphStatistics(),
            relationships = emptyList(),
            files = emptyMap()
        )
        val dummyClient = object : net.ib.ixpert.ops.wuwagent.client.LLMClient {
            override fun chat(
                systemPrompt: String,
                userCode: String,
                maxTokens: Int?,
                onChunk: ((String) -> Unit)?
            ): net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse? {
                return null
            }
            override fun fetchModels(baseUrl: String, apiKey: String): List<String>? {
                return null
            }
        }
        
        val verifier = FileRelevanceVerifier(
            llmClient = dummyClient,
            graph = graph,
            mdRoot = Paths.get("/test/docs")
        )
        
        val result = verifier.verify("테스트 요구사항", emptyList())
        assertTrue(result.isEmpty())
    }
}
