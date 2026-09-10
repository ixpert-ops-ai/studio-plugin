package net.ib.ixpert.ops.wuwagent.agent

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class RequirementAnalysisPipelineIntegrationTest {

    @Test
    fun testPipelineInitialization() {
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
        val pipeline = RequirementAnalysisPipeline(dummyClient)
        assertNotNull(pipeline)
    }

    @Test
    fun testStage0ContractBindingInPipeline() = kotlinx.coroutines.runBlocking {
        val dummyClient = object : net.ib.ixpert.ops.wuwagent.client.LLMClient {
            override fun chat(
                systemPrompt: String,
                userCode: String,
                maxTokens: Int?,
                onChunk: ((String) -> Unit)?
            ): net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse? {
                return net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse(
                    model = "test",
                    createdAt = "",
                    message = net.ib.ixpert.ops.wuwagent.model.OllamaMessage("assistant", "{}"),
                    done = true
                )
            }
            override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
        }

        val graph = ProjectGraph(
            generatedAt = Instant.now().toString(),
            projectRoot = "/test/root",
            frameworkType = FrameworkType.SPRING_MVC_MYBATIS,
            files = mapOf(
                "com/example/ExistingService.java" to FileNode(
                    path = "com/example/ExistingService.java",
                    packageName = "com.example",
                    className = "ExistingService",
                    fileType = SpringFileType.SERVICE,
                    layer = ArchitectureLayer.SERVICE
                )
            ),
            relationships = emptyList(),
            statistics = GraphStatistics()
        )

        val newCreationItem = net.ib.ixpert.ops.wuwagent.agent.clarify.model.RequirementItem(
            id = "req_bizgo_client",
            statement = "com/example/BizgoClient.java",
            source = net.ib.ixpert.ops.wuwagent.agent.clarify.model.HintSource.USER_UTTERED,
            hint = net.ib.ixpert.ops.wuwagent.agent.clarify.model.LinkHint.NewCreation,
            anchorRationale = "신규 연동 모듈",
            verdict = net.ib.ixpert.ops.wuwagent.agent.clarify.model.Verdict.CONFIRMED,
            confidence = net.ib.ixpert.ops.wuwagent.agent.clarify.model.ConfidenceBucket.HIGH_CONFIDENCE
        )

        val existingRefItem = net.ib.ixpert.ops.wuwagent.agent.clarify.model.RequirementItem(
            id = "req_existing_service",
            statement = "com/example/ExistingService.java 수정",
            source = net.ib.ixpert.ops.wuwagent.agent.clarify.model.HintSource.USER_CONFIRMED,
            hint = net.ib.ixpert.ops.wuwagent.agent.clarify.model.LinkHint.ExistingRef(
                filePath = "com/example/ExistingService.java",
                symbols = listOf("ExistingService")
            ),
            anchorRationale = "기존 서비스",
            verdict = net.ib.ixpert.ops.wuwagent.agent.clarify.model.Verdict.CONFIRMED,
            confidence = net.ib.ixpert.ops.wuwagent.agent.clarify.model.ConfidenceBucket.HIGH_CONFIDENCE
        )

        val contract = net.ib.ixpert.ops.wuwagent.agent.clarify.model.Stage0TransitionContract(
            createdAt = java.time.Instant.now().toString(),
            graphHash = "dummyHash",
            confirmedItems = listOf(existingRefItem, newCreationItem),
            trustedExistingRefs = listOf(existingRefItem.hint as net.ib.ixpert.ops.wuwagent.agent.clarify.model.LinkHint.ExistingRef),
            newCreations = listOf(newCreationItem),
            enrichedRequirementText = "요구사항 보강 본문"
        )

        val pipeline = RequirementAnalysisPipeline(dummyClient)
        val result = pipeline.analyze(
            primaryReq = "기본 요구사항",
            secondaryReq = "",
            projectGraph = graph,
            stage0Contract = contract
        )

        // 1. trustedExistingRefs -> MODIFY 합성 확인
        val existingTarget = result.targetFiles.find { it.path == "com/example/ExistingService.java" }
        assertNotNull("trustedExistingRefs must be synthesized in targetFiles", existingTarget)
        assertEquals("Existing files must have type MODIFY", "MODIFY", existingTarget?.type)
        assertTrue("Description must reflect Stage 0 confirmation", existingTarget?.description?.contains("Stage 0 확정 기존 파일") == true)

        // 2. newCreations -> CREATE 독립 합성 및 시드 격리 확인
        val newCreationTarget = result.targetFiles.find { it.path == "com/example/BizgoClient.java" }
        assertNotNull("newCreations must be synthesized in targetFiles", newCreationTarget)
        assertEquals("New creations must have type CREATE", "CREATE", newCreationTarget?.type)
        assertTrue("Description must reflect Stage 0 new creation", newCreationTarget?.description?.contains("Stage 0 사용자 신규 생성 지정") == true)

        // 3. newCreations가 trustedExistingRefs에 섞이지 않았는지 독립성 재확인
        assertFalse("newCreations must NOT be present in trustedExistingRefs", contract.trustedExistingRefs.any { it.filePath.contains("Bizgo") })
    }
}
