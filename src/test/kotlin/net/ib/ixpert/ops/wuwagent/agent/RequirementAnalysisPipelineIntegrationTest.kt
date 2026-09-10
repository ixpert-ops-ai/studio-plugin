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

    /**
     * P2 검증: 계약 아티팩트 없는 단독 /analyze 파이프라인 실제 구동 E2E 테스트
     * - stage0Contract = null 인 상태에서 실제 suspend analyze()를 코루틴으로 호출하여 끝까지 완주
     * - discovery가 실제 후보를 산출하여 targetFiles가 비어있지 않음을 먼저 assert (위양성 방지)
     * - 산출된 모든 targetFiles에 "Stage 0" 합성 흔적(기존 확정/신규 생성 주입)이 100% 부재함을 assert
     * - 요약 및 경고 등 파이프라인 결과 객체의 구조적 유효성을 assert
     */
    @Test
    fun testStandalonePipelineAnalyzeE2EWithoutContract() = kotlinx.coroutines.runBlocking {
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

        val pipeline = RequirementAnalysisPipeline(dummyClient)
        val result = pipeline.analyze(
            primaryReq = "ExistingService 로직 수정",
            secondaryReq = "",
            projectGraph = graph,
            stage0Contract = null // 계약 부재 (단독 /analyze 경로)
        )

        // 1. 파이프라인 정상 완주 및 결과 객체 유효성 확인
        assertNotNull("파이프라인 결과 객체가 정상 생성되어야 함", result)
        assertNotNull("요약 텍스트가 null이 아니어야 함", result.summary)

        // 2. Discovery가 실제로 후보를 산출했는지 확인 (공집합 자동통과 위양성 방지)
        assertTrue("단독 파이프라인에서 실제 탐색 후보 파일이 1건 이상 산출되어야 함", result.targetFiles.isNotEmpty())

        // 3. Stage 0 합성 흔적 부재 검증 (핵심)
        assertTrue(
            "계약이 null일 때 어떤 targetFile의 description에도 'Stage 0' 합성 문구가 없어야 함",
            result.targetFiles.none { it.description.contains("Stage 0") }
        )

        // 4. 계약 기반 신규 생성(CREATE) 주입 부재 확인
        val createCount = result.targetFiles.count { it.type == "CREATE" && it.description.contains("Stage 0") }
        assertEquals("계약이 없을 때 Stage 0 유래 CREATE 항목은 정확히 0건이어야 함", 0, createCount)
    }
}
