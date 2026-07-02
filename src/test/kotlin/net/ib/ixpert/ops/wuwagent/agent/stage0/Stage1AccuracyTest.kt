package net.ib.ixpert.ops.wuwagent.agent.stage0

import com.google.gson.GsonBuilder
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.OllamaMessage
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.*
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class Stage1AccuracyTest {

    companion object {
        var projectGraph: ProjectGraph? = null

        @JvmStatic
        @BeforeClass
        fun setup() {
            val graphPath = "C:\\Workspace\\member-market\\.meta\\project-graph.json"
            val graphFile = File(graphPath)

            if (graphFile.exists()) {
                val gson = GsonBuilder().disableHtmlEscaping().create()
                projectGraph = gson.fromJson(graphFile.readText(Charsets.UTF_8), ProjectGraph::class.java)
            }
        }
    }

    private val mockLlmClient = object : LLMClient {
        override fun chat(
            systemPrompt: String,
            userCode: String,
            maxTokens: Int?,
            onChunk: ((String) -> Unit)?
        ): OllamaChatResponse? {
            return OllamaChatResponse(
                model = "mock",
                createdAt = "",
                message = OllamaMessage("assistant", "{}"),
                done = true
            )
        }
        override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = listOf("mock")
    }

    @Test
    fun testStage1GraphTraversalDiscoveryPrecisionRecall() {
        val graph = projectGraph
        if (graph == null) {
            println("member-market project-graph.json not found, skipping test.")
            return
        }

        val fixtures = mapOf(
            "TC-01" to SeedSelectionResult(listOf("Product", "ProductCreateRequest", "ProductResponse"), ChangeIntent.MODIFY, listOf("ENTITY", "DTO", "PRESENTATION"), true, "Mock TC-01"),
            "TC-02" to SeedSelectionResult(listOf("Member", "MemberUpdateRequest", "MemberResponse"), ChangeIntent.MODIFY, listOf("ENTITY", "DTO", "PRESENTATION"), true, "Mock TC-02"),
            "TC-03" to SeedSelectionResult(listOf("Product", "ProductService", "ProductController"), ChangeIntent.MODIFY, listOf("SERVICE", "PRESENTATION"), true, "Mock TC-03"),
            "TC-04" to SeedSelectionResult(listOf("Product"), ChangeIntent.MODIFY, listOf("PRESENTATION"), true, "Mock TC-04"),
            "TC-05" to SeedSelectionResult(listOf("ProductStatus", "Product"), ChangeIntent.MODIFY, listOf("ENTITY", "PRESENTATION"), true, "Mock TC-05"),
            "TC-06" to SeedSelectionResult(listOf("Member", "MemberRepository", "MemberStatus"), ChangeIntent.CREATE, listOf("ENTITY", "REPOSITORY", "SERVICE"), false, "Mock TC-06"),
            "TC-07" to SeedSelectionResult(listOf("ChatMessage", "ChatRoom"), ChangeIntent.MODIFY, listOf("ENTITY", "SERVICE"), true, "Mock TC-07"),
            "TC-08" to SeedSelectionResult(listOf("ChatRoom", "ChatMessage"), ChangeIntent.MODIFY, listOf("ENTITY", "SERVICE"), true, "Mock TC-08"),
            "TC-09" to SeedSelectionResult(listOf("GlobalExceptionHandler", "ErrorResponse"), ChangeIntent.MODIFY, listOf("CONFIG", "PRESENTATION"), false, "Mock TC-09"),
            "TC-10" to SeedSelectionResult(listOf("ProductRepository", "Product"), ChangeIntent.MODIFY, listOf("REPOSITORY"), true, "Mock TC-10")
        )

        val mockSeedSelector = MockSeedSelector(fixtures)

        var totalExpected = 0
        var totalFound = 0

        TestCaseDefinitions.cases.forEach { tc ->
            val result = AdaptiveFileDiscovery.filter(
                primaryReq = "[${tc.srId}] " + tc.input.title,
                secondaryReq = tc.input.description,
                graph = graph,
                client = mockLlmClient,
                project = null,
                seedSelector = mockSeedSelector
            )

            val foundFilePaths = result.relevantFiles.map { it.path }

            println("\n[${tc.srId}] Files found: ${foundFilePaths.size}\n  -> ${foundFilePaths.joinToString("\n  -> ")}")
            
            tc.expectedFiles.forEach { expectedFileName ->
                totalExpected++
                val isFound = foundFilePaths.any { path -> path.endsWith(expectedFileName) }
                if (isFound) {
                    totalFound++
                    println("  [FOUND] $expectedFileName")
                } else {
                    println("  [MISSING] $expectedFileName")
                }
            }
            
            if (result.suggestedNewFiles.isNotEmpty()) {
                println("  [NEW FILES] ${result.suggestedNewFiles.map { it.suggestedPath }}")
            }
        }

        val recall = if (totalExpected > 0) (totalFound.toDouble() / totalExpected) * 100 else 0.0
        println("\n=== Total Recall: ${"%.2f".format(recall)}% ($totalFound/$totalExpected) ===")

        assertTrue("Recall is less than 80% ($recall%)", recall >= 80.0)
    }
}
