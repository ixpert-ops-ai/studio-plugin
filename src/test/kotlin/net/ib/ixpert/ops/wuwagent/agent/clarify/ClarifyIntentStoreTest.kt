package net.ib.ixpert.ops.wuwagent.agent.clarify

import net.ib.ixpert.ops.wuwagent.agent.clarify.model.*
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

    /**
     * [회귀 테스트] 세션 격리 안전판 검증:
     * - 동일 프로젝트에서 SR_1(결제 모듈)의 디스크 기록용 파일이 남아있더라도,
     * - 새로운 SR_2(회원 탈퇴)가 단독 /analyze로 실행될 때 AnalyzeInputResolver가
     *   사용자 입력을 100% 보장하고 이전 세션의 stale intent/excludedFiles를 일절 소비하지 않음을 검증.
     */
    @Test
    fun testStaleIntentDoesNotContaminateNewStandaloneAnalyzeSession() {
        val projectRoot = tempFolder.newFolder("stale_intent_project")
        val graph = createMiniGraph()
        val graphHash = ClarificationContractStore.calculateGraphHash(graph)

        // 1. 이전 세션 SR_1 인텐트 저장 (디스크 잔존 기록)
        val staleIntent = ClarifyIntent(
            originalRequirement = "SR_1: 결제 모듈 연동 및 카드사 연계",
            refinedRequirement = "SR_1 정제문: 카드사 PG 연동",
            excludedFiles = listOf("src/main/java/com/example/OrderService.java"),
            graphHash = graphHash,
            contractVersion = "1.0"
        )
        ClarifyIntentStore.saveIntent(projectRoot, staleIntent, key = "default")

        // 2. 새 세션 SR_2 단독 /analyze 실행: inMemoryIntent = null
        val newRequirement = "SR_2: 회원 탈퇴 및 개인정보 파기 처리"
        val resolved = AnalyzeInputResolver.resolve(
            rawInput = newRequirement,
            inMemoryIntent = null
        )

        // 3. 단언 (Green): 새 입력 100% 보존 및 이전 intent/excludedFiles 완벽 차단
        assertEquals("새 세션의 사용자 입력이 100% 보존되어야 함", newRequirement, resolved.effectiveRequirement)
        assertNull("단독 /analyze 시 이전 세션 인텐트 계약이 주입되지 않아야 함", resolved.effectiveIntent)
    }

    /**
     * [UnresolvedItems] 1.2 버전 UnresolvedItem 라운드트립 직렬화 및 역직렬화 무결성 검증
     */
    @Test
    fun testUnresolvedItems_RoundTripSerializationAndLoad() {
        val projectRoot = tempFolder.newFolder("unresolved_roundtrip")
        val graph = createMiniGraph()
        val graphHash = ClarificationContractStore.calculateGraphHash(graph)

        val intent = ClarifyIntent(
            originalRequirement = "설문 발송 채널에 브랜드메시지 추가",
            refinedRequirement = "정제문",
            graphHash = graphHash,
            unresolvedItems = listOf(
                UnresolvedItem(
                    identifier = "SAPACMM0802S01",
                    kind = UnresolvedKind.NEW_CREATION,
                    filePath = null,
                    source = HintSource.USER_UTTERED,
                    utteredTurn = 1,
                    lastQuestion = "질문 원문",
                    lastAskedTurn = 1
                ),
                UnresolvedItem(
                    identifier = "SurveyServiceImpl",
                    kind = UnresolvedKind.EXISTING_REF,
                    filePath = "src/main/java/com/example/OrderService.java",
                    source = HintSource.PROPOSED_EXCLUSION,
                    utteredTurn = 2,
                    lastQuestion = null,
                    lastAskedTurn = null
                )
            ),
            contractVersion = ClarifyIntent.CURRENT_INTENT_VERSION
        )

        val saved = ClarifyIntentStore.saveIntent(projectRoot, intent, key = "test_unresolved")
        val loaded = ClarifyIntentStore.loadIntent(saved, graph)

        assertEquals(2, loaded.unresolvedItems.size)
        assertEquals("SAPACMM0802S01", loaded.unresolvedItems[0].identifier)
        assertEquals(UnresolvedKind.NEW_CREATION, loaded.unresolvedItems[0].kind)
        assertNull(loaded.unresolvedItems[0].filePath)
        assertEquals(HintSource.USER_UTTERED, loaded.unresolvedItems[0].source)
        assertEquals(1, loaded.unresolvedItems[0].utteredTurn)
        assertEquals("질문 원문", loaded.unresolvedItems[0].lastQuestion)
        assertEquals(1, loaded.unresolvedItems[0].lastAskedTurn)

        assertEquals("SurveyServiceImpl", loaded.unresolvedItems[1].identifier)
        assertEquals(UnresolvedKind.EXISTING_REF, loaded.unresolvedItems[1].kind)
        assertEquals("src/main/java/com/example/OrderService.java", loaded.unresolvedItems[1].filePath)
        assertEquals(HintSource.PROPOSED_EXCLUSION, loaded.unresolvedItems[1].source)
        assertEquals(2, loaded.unresolvedItems[1].utteredTurn)
        assertNull(loaded.unresolvedItems[1].lastQuestion)
        assertNull(loaded.unresolvedItems[1].lastAskedTurn)
    }

    /**
     * [UnresolvedItems] 1.0 및 1.1 레거시 JSON 로드 시 모든 리스트가 emptyList()로 정상 역직렬화되는지 검증
     */
    @Test
    fun testLegacyJson1_0_And_1_1_DeserializationToEmptyListsWithoutNull() {
        val projectRoot = tempFolder.newFolder("legacy_json")
        val graph = createMiniGraph()
        val graphHash = ClarificationContractStore.calculateGraphHash(graph)

        // 1.0 JSON (unresolvedItems, constraints, excludedFiles 필드 부존재)
        val legacy10Json = """
            {
              "originalRequirement": "원문",
              "refinedRequirement": "정제문",
              "graphHash": "$graphHash",
              "contractVersion": "1.0"
            }
        """.trimIndent()
        val file10 = File(projectRoot, "clarify-intent-v10.json")
        file10.writeText(legacy10Json, Charsets.UTF_8)

        val loaded10 = ClarifyIntentStore.loadIntent(file10, graph)
        assertEquals("1.0", loaded10.contractVersion)
        assertTrue("userStatements가 emptyList()이어야 함", loaded10.userStatements.isEmpty())
        assertTrue("anchorTokens가 emptyList()이어야 함", loaded10.anchorTokens.isEmpty())
        assertTrue("constraints가 emptyList()이어야 함", loaded10.constraints.isEmpty())
        assertTrue("excludedFiles가 emptyList()이어야 함", loaded10.excludedFiles.isEmpty())
        assertTrue("unresolvedItems가 emptyList()이어야 함", loaded10.unresolvedItems.isEmpty())

        // 1.1 JSON (unresolvedItems 필드 부존재)
        val legacy11Json = """
            {
              "originalRequirement": "원문 1.1",
              "refinedRequirement": "정제문 1.1",
              "graphHash": "$graphHash",
              "contractVersion": "1.1"
            }
        """.trimIndent()
        val file11 = File(projectRoot, "clarify-intent-v11.json")
        file11.writeText(legacy11Json, Charsets.UTF_8)

        val loaded11 = ClarifyIntentStore.loadIntent(file11, graph)
        assertEquals("1.1", loaded11.contractVersion)
        assertTrue("unresolvedItems가 emptyList()이어야 함", loaded11.unresolvedItems.isEmpty())
    }

    /**
     * [UnresolvedItems] 미래 버전(1.9) 로드 시 VERSION_MISMATCH 예외 발생 검증
     */
    @Test
    fun testFutureVersion_1_9_RejectedWithVersionMismatch() {
        val projectRoot = tempFolder.newFolder("future_version")
        val graph = createMiniGraph()
        val graphHash = ClarificationContractStore.calculateGraphHash(graph)

        val futureJson = """
            {
              "originalRequirement": "원문",
              "refinedRequirement": "정제문",
              "graphHash": "$graphHash",
              "contractVersion": "1.9"
            }
        """.trimIndent()
        val file = File(projectRoot, "clarify-intent-v19.json")
        file.writeText(futureJson, Charsets.UTF_8)

        try {
            ClarifyIntentStore.loadIntent(file, graph)
            fail("미래 버전(1.9) 로드 시 ContractValidationException이 발생해야 함")
        } catch (e: ContractValidationException) {
            assertEquals(ContractValidationReason.VERSION_MISMATCH, e.reason)
        }
    }

    /**
     * [UnresolvedItems] 필수 필드 누락 손상 JSON 로드 시 CORRUPT_JSON 예외 발생 검증
     */
    @Test
    fun testCorruptJson_MissingUnresolvedItemFields_ThrowsCorruptJson() {
        val projectRoot = tempFolder.newFolder("corrupt_json")
        val graph = createMiniGraph()
        val graphHash = ClarificationContractStore.calculateGraphHash(graph)

        // identifier 누락
        val corruptJson = """
            {
              "originalRequirement": "원문",
              "refinedRequirement": "정제문",
              "graphHash": "$graphHash",
              "unresolvedItems": [
                {
                  "kind": "NEW_CREATION",
                  "source": "USER_UTTERED",
                  "utteredTurn": 1
                }
              ],
              "contractVersion": "1.2"
            }
        """.trimIndent()
        val file = File(projectRoot, "clarify-intent-corrupt.json")
        file.writeText(corruptJson, Charsets.UTF_8)

        try {
            ClarifyIntentStore.loadIntent(file, graph)
            fail("필수 필드 누락 시 ContractValidationException(CORRUPT_JSON)이 발생해야 함")
        } catch (e: ContractValidationException) {
            assertEquals(ContractValidationReason.CORRUPT_JSON, e.reason)
        }
    }
}

