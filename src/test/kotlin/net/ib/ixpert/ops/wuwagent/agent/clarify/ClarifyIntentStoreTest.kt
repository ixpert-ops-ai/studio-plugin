package net.ib.ixpert.ops.wuwagent.agent.clarify

import net.ib.ixpert.ops.wuwagent.agent.clarify.model.ClarifyIntent
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.ConstraintKind
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.IntentConstraint
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * ClarifyIntentStore 단위 테스트:
 * 1) 디스크 영속 저장 및 로드 무결성 (Round-Trip)
 * 2) 정본화 그래프 해시 불일치 시 Fail-Fast (GRAPH_HASH_MISMATCH)
 * 3) 버전 불일치 시 Fail-Fast (VERSION_MISMATCH)
 * 4) 파일 부존재 시 Fail-Fast (FILE_NOT_FOUND)
 */
class ClarifyIntentStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createMiniGraph(): ProjectGraph {
        val files = mapOf(
            "file1" to FileNode(
                path = "src/main/java/com/example/OrderService.java",
                className = "OrderService",
                packageName = "com.example",
                fileType = SpringFileType.SERVICE,
                layer = ArchitectureLayer.BUSINESS,
                annotations = listOf("Service"),
                methods = listOf(MethodSignature("createOrder", "void", listOf("OrderDto")))
            )
        )

        return ProjectGraph(
            version = "1.0",
            generatedAt = "2026-09-10T00:00:00Z",
            projectRoot = "/test",
            framework = "spring-boot",
            frameworkType = FrameworkType.SPRING_BOOT_JPA,
            files = files,
            resourceNodes = emptyList(),
            relationships = emptyList(),
            statistics = GraphStatistics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        )
    }

    @Test
    fun testClarifyIntentSaveAndLoadRoundTrip() {
        val projectRoot = tempFolder.newFolder("intent_project")
        val graph = createMiniGraph()
        val graphHash = ClarificationContractStore.calculateGraphHash(graph)

        val intent = ClarifyIntent(
            originalRequirement = "설문 발송 채널에 브랜드메시지 추가",
            refinedRequirement = "기존 알림톡 채널을 활용하여 브랜드메시지 추가, 외부 API 미사용",
            anchorTokens = listOf("설문", "브랜드메시지", "알림톡"),
            constraints = listOf(
                IntentConstraint(ConstraintKind.INCLUDE_CHANNEL, "알림톡 채널 활용", "기존 알림톡 채널을 그대로 사용합니다."),
                IntentConstraint(ConstraintKind.EXCLUDE_EXTERNAL, "외부 API 미사용", "외부 API 연동은 하지 않습니다.")
            ),
            graphHash = graphHash,
            contractVersion = "1.0"
        )

        val savedFile = ClarifyIntentStore.saveIntent(projectRoot, intent, key = "order_survey")
        assertTrue("인텐트 파일이 디스크에 존재해야 함", savedFile.exists())
        assertEquals("clarify-intent-order_survey.json", savedFile.name)

        // .gitignore 자동 생성 확인
        val gitignore = File(projectRoot, ".wuwagent/.gitignore")
        assertTrue(".gitignore 파일이 자동 생성되어야 함", gitignore.exists())
        assertTrue(".gitignore에 와일드카드가 포함되어야 함", gitignore.readText().contains("*"))

        // 로드 및 불변성 검증
        val loaded = ClarifyIntentStore.loadIntent(savedFile, graph)
        assertEquals(intent.originalRequirement, loaded.originalRequirement)
        assertEquals(intent.refinedRequirement, loaded.refinedRequirement)
        assertEquals(intent.anchorTokens, loaded.anchorTokens)
        assertEquals(2, loaded.constraints.size)
        assertEquals(ConstraintKind.INCLUDE_CHANNEL, loaded.constraints[0].kind)
        assertEquals("기존 알림톡 채널을 그대로 사용합니다.", loaded.constraints[0].rawStatement)
        assertEquals(graphHash, loaded.graphHash)
    }

    @Test
    fun testClarifyIntentFailFastOnGraphHashMismatch() {
        val projectRoot = tempFolder.newFolder("intent_mismatch")
        val graph = createMiniGraph()
        val graphHash = ClarificationContractStore.calculateGraphHash(graph)

        val intent = ClarifyIntent(
            originalRequirement = "원문",
            refinedRequirement = "정제문",
            graphHash = graphHash,
            contractVersion = "1.0"
        )

        val savedFile = ClarifyIntentStore.saveIntent(projectRoot, intent, key = "test_hash")

        // 그래프가 변경된 상황 시뮬레이션
        val modifiedGraph = graph.copy(
            files = graph.files + ("file2" to FileNode(
                path = "src/main/java/com/example/NewService.java",
                className = "NewService",
                packageName = "com.example",
                fileType = SpringFileType.SERVICE,
                layer = ArchitectureLayer.BUSINESS
            ))
        )

        try {
            ClarifyIntentStore.loadIntent(savedFile, modifiedGraph)
            fail("그래프 해시 불일치 시 ContractValidationException이 발생해야 함")
        } catch (e: ContractValidationException) {
            assertEquals(ContractValidationReason.GRAPH_HASH_MISMATCH, e.reason)
            assertTrue(e.message?.contains("그래프 무결성 불일치") == true)
        }
    }

    @Test
    fun testClarifyIntentFailFastOnVersionMismatch() {
        val projectRoot = tempFolder.newFolder("intent_version")
        val graph = createMiniGraph()
        val graphHash = ClarificationContractStore.calculateGraphHash(graph)

        val invalidVersionIntent = ClarifyIntent(
            originalRequirement = "원문",
            refinedRequirement = "정제문",
            graphHash = graphHash,
            contractVersion = "2.0" // 지원하지 않는 메이저 버전
        )

        val savedFile = ClarifyIntentStore.saveIntent(projectRoot, invalidVersionIntent, key = "test_ver")

        try {
            ClarifyIntentStore.loadIntent(savedFile, graph)
            fail("지원하지 않는 버전 시 ContractValidationException이 발생해야 함")
        } catch (e: ContractValidationException) {
            assertEquals(ContractValidationReason.VERSION_MISMATCH, e.reason)
            assertTrue(e.message?.contains("지원하지 않는 인텐트 스키마 버전") == true)
        }
    }

    @Test
    fun testClarifyIntentFailFastOnFileNotFound() {
        val graph = createMiniGraph()
        val nonExistentFile = File("C:/non/existent/path/clarify-intent.json")

        try {
            ClarifyIntentStore.loadIntent(nonExistentFile, graph)
            fail("파일 부존재 시 ContractValidationException이 발생해야 함")
        } catch (e: ContractValidationException) {
            assertEquals(ContractValidationReason.FILE_NOT_FOUND, e.reason)
        }
    }
}
