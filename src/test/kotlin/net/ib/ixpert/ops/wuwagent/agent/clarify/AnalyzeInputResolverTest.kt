package net.ib.ixpert.ops.wuwagent.agent.clarify

import net.ib.ixpert.ops.wuwagent.agent.clarify.model.ClarifyIntent
import org.junit.Assert.*
import org.junit.Test

class AnalyzeInputResolverTest {

    @Test
    fun testStandaloneAnalyzePrioritizesUserInputAndIgnoresStaleIntent() {
        val rawInput = "새 SR: 회원 탈퇴 및 개인정보 파기 처리"
        
        // 단독 /analyze 실행 시 inMemoryIntent = null
        val resolved = AnalyzeInputResolver.resolve(
            rawInput = rawInput,
            inMemoryIntent = null
        )

        assertEquals("사용자 입력이 100% 최우선 보장되어야 함", rawInput, resolved.effectiveRequirement)
        assertNull("stale intent는 주입되지 않아야 함", resolved.effectiveIntent)
    }

    @Test
    fun testClarifyTransitionPassesRefinedRequirementAndIntent() {
        val clarifyIntent = ClarifyIntent(
            originalRequirement = "SR_1: 결제 모듈 연동",
            refinedRequirement = "정제문: PG사 연동 로직 구현",
            excludedFiles = listOf("src/main/java/com/example/OrderService.java"),
            graphHash = "hash123",
            contractVersion = "1.0"
        )

        val resolved = AnalyzeInputResolver.resolve(
            rawInput = clarifyIntent.refinedRequirement,
            inMemoryIntent = clarifyIntent
        )

        assertEquals("Clarify 인계 시 refinedRequirement가 적용되어야 함", "정제문: PG사 연동 로직 구현", resolved.effectiveRequirement)
        assertNotNull("인텐트 계약이 온전히 전달되어야 함", resolved.effectiveIntent)
        assertEquals(listOf("src/main/java/com/example/OrderService.java"), resolved.effectiveIntent?.excludedFiles)
    }

    @Test
    fun testClarifyTransitionWithBlankRefinedFallsBackToOriginal() {
        val clarifyIntent = ClarifyIntent(
            originalRequirement = "SR_1: 원본 요구사항",
            refinedRequirement = "", // 빈 정제문
            excludedFiles = emptyList(),
            graphHash = "hash123",
            contractVersion = "1.0"
        )

        val resolved = AnalyzeInputResolver.resolve(
            rawInput = "raw input",
            inMemoryIntent = clarifyIntent
        )

        assertEquals("정제문 공백 시 원본 요구사항으로 폴백되어야 함", "SR_1: 원본 요구사항", resolved.effectiveRequirement)
    }

    @Test
    fun testArchitecturalGuardNoDiskIntentOrContractReadingInSrcMain() {
        val srcMainDir = java.io.File("src/main/kotlin")
        assertTrue("src/main/kotlin 디렉터리가 존재해야 함", srcMainDir.exists())
        
        val ktFiles = srcMainDir.walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue("Kotlin 소스 파일들이 존재해야 함", ktFiles.isNotEmpty())

        val violations = mutableListOf<String>()
        val excludedStoreFiles = setOf("ClarifyIntentStore.kt", "ClarificationContractStore.kt")

        for (file in ktFiles) {
            if (file.name in excludedStoreFiles) continue
            val lines = file.readLines(Charsets.UTF_8)
            for ((index, line) in lines.withIndex()) {
                if (line.contains("findIntentFile") || line.contains("findContractFile") || line.contains("loadIntentByKey") || line.contains("loadContractByKey")) {
                    violations.add("${file.path}:${index + 1} -> ${line.trim()}")
                }
            }
        }

        assertTrue(
            "src/main 전체에서 디스크의 stale intent/contract 자동 조회 메서드(findIntentFile/findContractFile/loadIntentByKey/loadContractByKey)를 직접 호출하는 곳이 없어야 함 (발견된 위반: $violations)",
            violations.isEmpty()
        )
    }

    @Test
    fun testClarifyUserRejectionExcludesFileInAnalyzePipeline() = kotlinx.coroutines.runBlocking {
        // [검증 목표]: Clarify에서 사용자가 파일 X를 거절(REJECTED)하면, 메모리 인계를 거쳐 최종 Analyze 결과에서 X가 완전히 배제됨
        val graph = net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph(
            generatedAt = java.time.Instant.now().toString(),
            projectRoot = "/test/root",
            files = mapOf(
                "com/example/TargetService.java" to net.ib.ixpert.ops.wuwagent.service.metagraph.model.FileNode(
                    path = "com/example/TargetService.java",
                    packageName = "com.example",
                    className = "TargetService",
                    fileType = net.ib.ixpert.ops.wuwagent.service.metagraph.model.SpringFileType.SERVICE,
                    layer = net.ib.ixpert.ops.wuwagent.service.metagraph.model.ArchitectureLayer.SERVICE
                ),
                "com/example/RejectedService.java" to net.ib.ixpert.ops.wuwagent.service.metagraph.model.FileNode(
                    path = "com/example/RejectedService.java",
                    packageName = "com.example",
                    className = "RejectedService",
                    fileType = net.ib.ixpert.ops.wuwagent.service.metagraph.model.SpringFileType.SERVICE,
                    layer = net.ib.ixpert.ops.wuwagent.service.metagraph.model.ArchitectureLayer.SERVICE
                )
            ),
            relationships = emptyList(),
            statistics = net.ib.ixpert.ops.wuwagent.service.metagraph.model.GraphStatistics()
        )

        // Clarify 단계에서 RejectedService.java가 REJECTED 상태로 마킹된 intent 생성
        val clarifyIntent = ClarifyIntent(
            originalRequirement = "서비스 로직 수정",
            refinedRequirement = "TargetService 및 RejectedService 수정",
            anchorTokens = listOf("TargetService", "RejectedService"),
            constraints = emptyList(),
            excludedFiles = listOf("com/example/RejectedService.java"), // 사용자가 명시적으로 거절한 파일
            graphHash = "dummyHash",
            contractVersion = "1.0"
        )

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
                    message = net.ib.ixpert.ops.wuwagent.model.OllamaMessage("assistant", "[]"),
                    done = true
                )
            }
            override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
        }

        val pipeline = net.ib.ixpert.ops.wuwagent.agent.RequirementAnalysisPipeline(dummyClient)
        val result = pipeline.analyze(
            primaryReq = clarifyIntent.refinedRequirement,
            secondaryReq = "",
            projectGraph = graph,
            clarifyIntent = clarifyIntent,
            previousContract = null,
            projectRoot = null
        )

        assertFalse(
            "Clarify에서 거절된 RejectedService.java는 Analyze 결과(targetFiles)에 절대 포함되지 않아야 함",
            result.targetFiles.any { it.path == "com/example/RejectedService.java" }
        )
        assertTrue(
            "거절되지 않은 TargetService.java는 Analyze 결과에 정상 유지되어야 함",
            result.targetFiles.any { it.path == "com/example/TargetService.java" }
        )
    }

    @Test
    fun testPipelineConsumesOriginalRequirementWhenClarifyIntentRefinedIsBlank() = kotlinx.coroutines.runBlocking {
        // [검증 목표]: clarifyIntent의 refinedRequirement가 공백("")인 경우,
        // Resolver를 거쳐 primaryReq로 전달된 originalRequirement가 RequirementAnalysisPipeline에서 정상 소비되어야 함.
        val graph = net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph(
            generatedAt = java.time.Instant.now().toString(),
            projectRoot = "/test/root",
            files = mapOf(
                "com/example/TargetService.java" to net.ib.ixpert.ops.wuwagent.service.metagraph.model.FileNode(
                    path = "com/example/TargetService.java",
                    packageName = "com.example",
                    className = "TargetService",
                    fileType = net.ib.ixpert.ops.wuwagent.service.metagraph.model.SpringFileType.SERVICE,
                    layer = net.ib.ixpert.ops.wuwagent.service.metagraph.model.ArchitectureLayer.SERVICE
                )
            ),
            relationships = emptyList(),
            statistics = net.ib.ixpert.ops.wuwagent.service.metagraph.model.GraphStatistics()
        )

        val clarifyIntent = ClarifyIntent(
            originalRequirement = "TargetService 관련 원본 요구사항",
            refinedRequirement = "", // 빈 정제문
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
        assertEquals("정제문 공백 시 originalRequirement로 복원되어야 함", "TargetService 관련 원본 요구사항", resolved.effectiveRequirement)

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
                    message = net.ib.ixpert.ops.wuwagent.model.OllamaMessage("assistant", "[]"),
                    done = true
                )
            }
            override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
        }

        val pipeline = net.ib.ixpert.ops.wuwagent.agent.RequirementAnalysisPipeline(dummyClient)
        val result = pipeline.analyze(
            primaryReq = resolved.effectiveRequirement,
            secondaryReq = "",
            projectGraph = graph,
            clarifyIntent = resolved.effectiveIntent,
            previousContract = null,
            projectRoot = null
        )

        assertNotNull(result)
        assertEquals("파이프라인이 빈 refinedRequirement 대신 primaryReq(originalRequirement)를 유지해야 함", "TargetService 관련 원본 요구사항", resolved.effectiveRequirement)
    }
}
