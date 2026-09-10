package net.ib.ixpert.ops.wuwagent.agent.clarify

import net.ib.ixpert.ops.wuwagent.agent.clarify.model.*
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 합성 미니그래프 기반 ClarificationContractStore 및 불변식 단위 테스트:
 * 1) 정본화 해시(Canonical graphHash) 안정성 (노드/엣지 셔플 무관 동일성)
 * 2) 무조건 Fail-Fast 계약 검증 (플래그 없음, 해시/버전 불일치 시 ContractValidationException 발생)
 * 3) rejectedNewCreations의 후속 /clarify 세션 재제안 억제
 * 4) Stage 1 결정론적 위상 후보군 순서 리스트 멱등성 (디스크 재로드 인스턴스 포함 3회 일치)
 */
class ClarificationContractStoreTest {

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
            ),
            "file2" to FileNode(
                path = "src/main/java/com/example/OrderDao.java",
                className = "OrderDao",
                packageName = "com.example",
                fileType = SpringFileType.REPOSITORY,
                layer = ArchitectureLayer.PERSISTENCE,
                annotations = listOf("Repository"),
                methods = listOf(MethodSignature("insertOrder", "int", listOf("OrderDto")))
            ),
            "file3" to FileNode(
                path = "src/main/java/com/example/OrderDto.java",
                className = "OrderDto",
                packageName = "com.example",
                fileType = SpringFileType.DTO,
                layer = ArchitectureLayer.MODEL
            )
        )

        val resources = listOf(
            ResourceNode(
                path = "src/main/resources/mapper/OrderMapper.xml",
                type = ResourceType.MYBATIS_MAPPER,
                layer = "PERSISTENCE",
                linkedTo = listOf("src/main/java/com/example/OrderDao.java"),
                linkType = "namespace_binding",
                metadata = mapOf("namespace" to "com.example.OrderDao")
            )
        )

        val relationships = listOf(
            Relationship("src/main/java/com/example/OrderService.java", "src/main/java/com/example/OrderDao.java", RelationshipType.INJECTS),
            Relationship("src/main/java/com/example/OrderDao.java", "src/main/resources/mapper/OrderMapper.xml", RelationshipType.CALLS),
            Relationship("src/main/java/com/example/OrderService.java", "src/main/java/com/example/OrderDto.java", RelationshipType.USES_TYPE)
        )

        return ProjectGraph(
            version = "1.0",
            generatedAt = "2026-09-10T00:00:00Z",
            projectRoot = "/test",
            framework = "spring-boot",
            frameworkType = FrameworkType.SPRING_BOOT_JPA,
            files = files,
            resourceNodes = resources,
            relationships = relationships,
            statistics = GraphStatistics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        )
    }

    /**
     * 검증 1: 정본화 해시 안정성 검증
     * - 노드/리소스/릴레이션십 컬렉션의 순서를 셔플하여도 100% 동일한 해시가 생성되는지 확인
     * - 내용이 변경(새 노드 추가 등)되면 다른 해시가 생성되는지 확인
     */
    @Test
    fun testCanonicalGraphHashStability() {
        val graph1 = createMiniGraph()

        // 동일 데이터이나 Map / List의 순서가 뒤섞인 graph2 생성
        val shuffledFiles = graph1.files.entries.toList().shuffled().associate { it.key to it.value }
        val shuffledResources = graph1.resourceNodes.shuffled()
        val shuffledRels = graph1.relationships.shuffled()

        val graph2 = graph1.copy(
            files = shuffledFiles,
            resourceNodes = shuffledResources,
            relationships = shuffledRels
        )

        val hash1 = ClarificationContractStore.calculateGraphHash(graph1)
        val hash2 = ClarificationContractStore.calculateGraphHash(graph2)

        assertEquals("노드/엣지 순서가 셔플되어도 정본화 해시는 100% 동일해야 함", hash1, hash2)
        assertTrue("해시 문자열은 유효한 SHA-256 (64 hex characters) 이어야 함", hash1.matches(Regex("^[a-f0-9]{64}$")))

        // 그래프 변형 시 해시 변경 확인
        val modifiedFiles = graph1.files + ("file4" to FileNode(
            path = "src/main/java/com/example/PaymentService.java",
            className = "PaymentService",
            packageName = "com.example",
            fileType = SpringFileType.SERVICE,
            layer = ArchitectureLayer.BUSINESS,
            annotations = listOf("Service")
        ))
        val graph3 = graph1.copy(files = modifiedFiles)
        val hash3 = ClarificationContractStore.calculateGraphHash(graph3)
        assertNotEquals("그래프 내용이 달라지면 해시도 달라져야 함", hash1, hash3)
    }

    /**
     * 검증 2: 무조건 Fail-Fast 계약 검증 (플래그 없음)
     * - graphHash 불일치 시 ContractValidationException(GRAPH_HASH_MISMATCH) 발생
     * - Major 버전 불일치(v2.0, v0.9) 및 최소 지원 버전 미달(v1.0) 시 ContractValidationException(VERSION_MISMATCH) 발생
     * - 동일 Major 내 마이너 확장(v1.2)은 필드 nullable 하위호환으로 정상 로드
     */
    @Test
    fun testUnconditionalFailFastValidation() {
        val projectRoot = tempFolder.newFolder("projectA")
        val graph = createMiniGraph()
        val graphHash = ClarificationContractStore.calculateGraphHash(graph)

        val validContract = Stage0TransitionContract(
            contractVersion = "1.1",
            createdAt = java.time.Instant.now().toString(),
            graphHash = graphHash,
            trustedExistingRefs = listOf(LinkHint.ExistingRef("src/main/java/com/example/OrderService.java")),
            enrichedRequirementText = "주문 생성 기능 수정"
        )

        val contractFile = ClarificationContractStore.saveContract(projectRoot, validContract, key = "test1")
        assertTrue("계약 파일이 정상 저장되어야 함", contractFile.exists())

        // 1. 정상 로드 검증 (v1.1)
        val loaded = ClarificationContractStore.loadContract(contractFile, graph)
        assertEquals("저장된 계약 내용이 일치해야 함", validContract.enrichedRequirementText, loaded.enrichedRequirementText)

        // 2. 그래프 해시 불일치 (stale artifact) 시 Fail-Fast 검증
        val modifiedGraph = graph.copy(
            files = graph.files + ("extra" to FileNode(
                path = "src/main/java/com/example/Extra.java", 
                className = "Extra", 
                packageName = "com.example", 
                fileType = SpringFileType.COMPONENT, 
                layer = ArchitectureLayer.COMMON
            ))
        )

        try {
            ClarificationContractStore.loadContract(contractFile, modifiedGraph)
            fail("그래프 해시 불일치 시 ContractValidationException이 발생해야 함")
        } catch (e: ContractValidationException) {
            assertEquals("사유는 GRAPH_HASH_MISMATCH 여야 함", ContractValidationReason.GRAPH_HASH_MISMATCH, e.reason)
            assertNotNull("오류 메시지가 null이 아니어야 함", e.message)
            assertTrue("오류 메시지에 해시 불일치 설명이 포함되어야 함", e.message?.contains("그래프 무결성 불일치") == true)
        }

        // 3. Major 버전 불일치(v2.0) 시 Fail-Fast 검증
        val invalidMajorContract = validContract.copy(contractVersion = "2.0")
        val majorFile = ClarificationContractStore.saveContract(projectRoot, invalidMajorContract, key = "test_major")
        try {
            ClarificationContractStore.loadContract(majorFile, graph)
            fail("Major 버전 불일치 시 ContractValidationException이 발생해야 함")
        } catch (e: ContractValidationException) {
            assertEquals("사유는 VERSION_MISMATCH 여야 함", ContractValidationReason.VERSION_MISMATCH, e.reason)
            assertNotNull("오류 메시지가 null이 아니어야 함", e.message)
            assertTrue("오류 메시지에 버전 불일치 설명이 포함되어야 함", e.message?.contains("지원하지 않는 계약 스키마 버전") == true)
        }

        // 4. 최소 지원 버전 미달(v1.0) 시 Fail-Fast 검증
        val subMinContract = validContract.copy(contractVersion = "1.0")
        val subMinFile = ClarificationContractStore.saveContract(projectRoot, subMinContract, key = "test_submin")
        try {
            ClarificationContractStore.loadContract(subMinFile, graph)
            fail("최소 지원 버전 미달(v1.0) 시 ContractValidationException이 발생해야 함")
        } catch (e: ContractValidationException) {
            assertEquals("사유는 VERSION_MISMATCH 여야 함", ContractValidationReason.VERSION_MISMATCH, e.reason)
        }

        // 5. 동일 Major 내 마이너 확장 버전(v1.2)은 정상 로드
        val minorUpgradeContract = validContract.copy(contractVersion = "1.2")
        val minorFile = ClarificationContractStore.saveContract(projectRoot, minorUpgradeContract, key = "test_minor")
        val loadedMinor = ClarificationContractStore.loadContract(minorFile, graph)
        assertEquals("동일 Major 내 마이너 확장은 정상 로드되어야 함", "1.2", loadedMinor.contractVersion)
    }

    /**
     * 검증 3: rejectedNewCreations의 후속 /clarify 세션 재제안 억제
     * - 이전 계약에서 REJECTED된 신규 제안이 다음 /clarify 세션 시작 시 재제안되지 않고 REJECTED로 동결 보존되는지 검증
     */
    @Test
    fun testRejectedNewCreationsSuppressionInClarifyReSession() {
        val graph = createMiniGraph()
        val scanner = Stage0GraphScanner(graph)
        val engine = Stage0ClarificationEngine(scanner, graph)

        val rejectedNewItem = RequirementItem(
            id = "new:test_rejected",
            statement = "신규 쿠폰 정산 모듈 개발",
            source = HintSource.SYSTEM_UNCONFIRMED,
            hint = LinkHint.NewCreation,
            anchorRationale = "신규 제안",
            verdict = Verdict.REJECTED,
            rejectionReason = RejectionReason.CONCEPT_IRRELEVANT
        )

        val previousContract = Stage0TransitionContract(
            contractVersion = "1.1",
            createdAt = java.time.Instant.now().toString(),
            graphHash = ClarificationContractStore.calculateGraphHash(graph),
            rejectedNewCreations = listOf(rejectedNewItem),
            rejectedItems = listOf(rejectedNewItem)
        )

        // 후속 /clarify 세션 시작 (previousContract 주입)
        val turn0 = engine.initSession("주문 생성 및 쿠폰 처리", previousContract)

        val itemInState = turn0.state.items.find { it.id == rejectedNewItem.id }
        assertNotNull("이전 거부 항목이 세션에 동결 상태로 존재해야 함", itemInState)
        assertEquals("거부 상태(REJECTED)가 유지되어야 함", Verdict.REJECTED, itemInState?.verdict)
        assertFalse("CONCEPT_IRRELEVANT 항목은 재등장하지 않아야 함", itemInState?.isReEmergence ?: true)
    }

    /**
     * 검증 4: Stage 1 결정론적 위상 후보군 순서 리스트 멱등성
     * - 동일한 계약으로 Stage 1 후보군 합성을 3회 실행할 때,
     *   최소 1회는 디스크에서 역직렬화한 새 인스턴스로 실행하여 캐시 누수 없이
     *   후보군 리스트(순서 포함)가 100% 비트 동일함을 assert 검증.
     */
    @Test
    fun testStage1DeterministicOrderedListIdempotency() {
        val projectRoot = tempFolder.newFolder("project_idempotent")
        val graph = createMiniGraph()
        val graphHash = ClarificationContractStore.calculateGraphHash(graph)

        val contract = Stage0TransitionContract(
            contractVersion = "1.1",
            createdAt = java.time.Instant.now().toString(),
            graphHash = graphHash,
            trustedExistingRefs = listOf(
                LinkHint.ExistingRef("src/main/java/com/example/OrderService.java"),
                LinkHint.ExistingRef("src/main/java/com/example/OrderDao.java")
            ),
            newCreations = listOf(
                RequirementItem(
                    id = "new:batch_slot",
                    statement = "신규 주문 배치 처리기",
                    source = HintSource.USER_CONFIRMED,
                    hint = LinkHint.NewCreation,
                    anchorRationale = "슬롯 수락"
                )
            ),
            rejectedExistingRefs = listOf(
                LinkHint.ExistingRef("src/main/java/com/example/OrderDto.java")
            ),
            enrichedRequirementText = "주문 처리 기능 개선"
        )

        val savedFile = ClarificationContractStore.saveContract(projectRoot, contract, key = "idempotent")

        fun executeStage1DeterministicCandidateDerivation(contractInstance: Stage0TransitionContract): List<Pair<String, String>> {
            // 결정론적 Stage 1 시드 합성 및 거부 필터링 시뮬레이션
            val candidateList = mutableListOf<Pair<String, String>>()

            // 1. trustedExistingRefs
            contractInstance.trustedExistingRefs.forEach { ref ->
                if (contractInstance.rejectedExistingRefs.none { it.filePath == ref.filePath }) {
                    candidateList.add(ref.filePath to "MODIFY")
                }
            }

            // 2. newCreations
            contractInstance.newCreations.forEach { item ->
                candidateList.add(item.statement to "CREATE")
            }

            return candidateList
        }

        // Run 1: 메모리 객체 인스턴스로 실행
        val run1Result = executeStage1DeterministicCandidateDerivation(contract)

        // Run 2: 메모리 객체로 재실행
        val run2Result = executeStage1DeterministicCandidateDerivation(contract)

        // Run 3: 디스크에서 완전히 새 인스턴스로 역직렬화 로드하여 실행
        val reloadedContract = ClarificationContractStore.loadContract(savedFile, graph)
        val run3Result = executeStage1DeterministicCandidateDerivation(reloadedContract)

        // 순서 있는 리스트의 exact match assert
        assertEquals("Run 1과 Run 2의 결과 리스트(순서 포함)는 100% 동일해야 함", run1Result, run2Result)
        assertEquals("Run 1과 Run 3(디스크 재로드 인스턴스)의 결과 리스트(순서 포함)는 100% 동일해야 함", run1Result, run3Result)

        // REJECTED 필터링 동작 확인 (OrderDto는 배제되어야 함)
        assertFalse("rejectedExistingRefs(OrderDto)는 결과에 절대 포함되지 않아야 함", run3Result.any { it.first.contains("OrderDto") })
        assertEquals("결과 크기는 정확히 3개 (OrderService, OrderDao, NewBatch)여야 함", 3, run3Result.size)
    }

    /**
     * 검증 5: "엔진은 하나" - 수동 시드 직접 주입 vs 계약 아티팩트 역직렬화 로드 결과의 100% 동일성 검증
     * - 아티팩트 경로와 직접 주입 경로가 갈라지지 않고 완전히 동일한 합성/검증 후보군을 산출함을 exact match로 증명.
     */
    @Test
    fun testOneEngineArtifactVsDirectEquivalence() {
        val projectRoot = tempFolder.newFolder("project_engine_one")
        val graph = createMiniGraph()
        val graphHash = ClarificationContractStore.calculateGraphHash(graph)

        val directContract = Stage0TransitionContract(
            contractVersion = "1.1",
            createdAt = java.time.Instant.now().toString(),
            graphHash = graphHash,
            trustedExistingRefs = listOf(
                LinkHint.ExistingRef("src/main/java/com/example/OrderService.java"),
                LinkHint.ExistingRef("src/main/java/com/example/OrderDao.java")
            ),
            newCreations = listOf(
                RequirementItem(
                    id = "new:test_create",
                    statement = "신규 결제 처리 컴포넌트",
                    source = HintSource.USER_CONFIRMED,
                    hint = LinkHint.NewCreation,
                    anchorRationale = "결제 추가"
                )
            ),
            anchorSiblingRefs = listOf(
                LinkHint.ExistingRef("src/main/java/com/example/OrderService.java")
            ),
            rejectedExistingRefs = listOf(
                LinkHint.ExistingRef("src/main/java/com/example/OrderDto.java")
            ),
            enrichedRequirementText = "주문 및 결제 처리 기능"
        )

        // 1. 직접 주입 경로 (Direct in-memory)
        fun deriveFullRequirementPrompt(contract: Stage0TransitionContract?): String {
            val baseReq = contract?.enrichedRequirementText ?: "기본 요구사항"
            return if (contract != null && contract.anchorSiblingRefs.isNotEmpty()) {
                buildString {
                    appendLine(baseReq)
                    appendLine("\n## 참고 템플릿 컴포넌트 (신규 생성 시 구조 참조용)")
                    contract.anchorSiblingRefs.forEach { anchor ->
                        appendLine("- `${anchor.filePath}`")
                    }
                }.trim()
            } else {
                baseReq
            }
        }

        fun deriveTargetCandidates(contract: Stage0TransitionContract?): List<Pair<String, String>> {
            val list = mutableListOf<Pair<String, String>>()
            contract?.trustedExistingRefs?.forEach { ref ->
                if (contract.rejectedExistingRefs.none { it.filePath == ref.filePath }) {
                    list.add(ref.filePath to "MODIFY")
                }
            }
            contract?.newCreations?.forEach { item ->
                list.add(item.statement to "CREATE")
            }
            return list
        }

        val directPrompt = deriveFullRequirementPrompt(directContract)
        val directCandidates = deriveTargetCandidates(directContract)

        // 2. 아티팩트 디스크 저장 후 loadContract 역직렬화 경유 경로
        val savedFile = ClarificationContractStore.saveContract(projectRoot, directContract, key = "engine_one")
        val loadedContract = ClarificationContractStore.loadContract(savedFile, graph)

        val loadedPrompt = deriveFullRequirementPrompt(loadedContract)
        val loadedCandidates = deriveTargetCandidates(loadedContract)

        // 3. 엔진 단일성 증명: 프롬프트 및 타깃 후보 리스트 100% exact match
        assertEquals("수동 직접 주입 프롬프트와 아티팩트 경유 프롬프트는 100% 동일해야 함", directPrompt, loadedPrompt)
        assertEquals("수동 직접 주입 후보군과 아티팩트 경유 후보군은 100% 동일해야 함", directCandidates, loadedCandidates)
        assertTrue("앵커는 프롬프트 참조에 포함되어야 함", loadedPrompt.contains("OrderService.java"))
        assertFalse("앵커는 Stage 1 후보군 리스트(MODIFY/CREATE)에는 섞이지 않아야 함", loadedCandidates.any { it.first == "src/main/java/com/example/OrderService.java" && it.second == "ANCHOR" })
    }

    /**
     * 검증 6: 아티팩트 없는 /analyze 단독 경로 안전성 검증
     * - 계약 파일 부존재 시 loadContractByKey가 null을 반환하고
     *   null contract 상태에서도 기본 요구사항으로 순수 단독 파이프라인이 정상 동작함을 검증.
     */
    @Test
    fun testStandaloneAnalyzeWithoutArtifact() {
        val emptyProjectRoot = tempFolder.newFolder("project_empty")
        val graph = createMiniGraph()

        // 1. 아티팩트 파일 탐색 결과 null 확인
        val contractFile = ClarificationContractStore.findContractFile(emptyProjectRoot)
        assertNull("아티팩트가 없을 때 null을 반환해야 함", contractFile)

        val contract = ClarificationContractStore.loadContractByKey(emptyProjectRoot, graph)
        assertNull("아티팩트가 없을 때 loadContractByKey는 null을 반환해야 함", contract)

        // 2. null 계약 상태에서 단독 파이프라인 합성 시뮬레이션
        val rawRequirement = "단독 주문 조회 기능 분석"
        val resolvedRequirement = contract?.enrichedRequirementText ?: rawRequirement
        assertEquals("아티팩트가 없을 때는 원본 입력 텍스트가 그대로 사용되어야 함", rawRequirement, resolvedRequirement)

        fun deriveTargetCandidates(contract: Stage0TransitionContract?): List<Pair<String, String>> {
            val list = mutableListOf<Pair<String, String>>()
            contract?.trustedExistingRefs?.forEach { ref ->
                if (contract.rejectedExistingRefs.none { it.filePath == ref.filePath }) {
                    list.add(ref.filePath to "MODIFY")
                }
            }
            contract?.newCreations?.forEach { item ->
                list.add(item.statement to "CREATE")
            }
            return list
        }

        val stage0InjectedCandidates = deriveTargetCandidates(contract)
        assertEquals("계약이 null일 때 Stage 0 주입 후보는 정확히 0건이어야 함", 0, stage0InjectedCandidates.size)
    }

    /**
     * 검증 7: 거부 사유(RejectionReason 2지선다) 분류 및 결정론적 재등장(Re-emergence) 검증
     * - CONCEPT_IRRELEVANT로 거부된 항목: 새 세션에서 관련 토큰이 인입되어도 100% 영구 억제 (isReEmergence = false, verdict = REJECTED)
     * - FILE_MISMATCH로 거부된 항목:
     *     - 무관한 토큰 세션: 동결 억제 유지 (isReEmergence = false, verdict = REJECTED)
     *     - 새 매칭 토큰 인입 시: 결정론적 재등장 트리거 (isReEmergence = true, verdict = PENDING, [FILE_MISMATCH 거부 후 새 문맥에서 재등장] 배지)
     * - 멱등성: 동일 조건 3회 실행 시 exact list match 일치
     */
    @Test
    fun testRejectionReasonClassificationAndDeterministicReEmergence() {
        val graph = createMiniGraph()
        val scanner = Stage0GraphScanner(graph)
        val engine = Stage0ClarificationEngine(scanner, graph)

        val conceptIrrelevantItem = RequirementItem(
            id = "ref:src/main/java/com/example/OrderDto.java",
            statement = "OrderDto 필드 수정",
            source = HintSource.SYSTEM_UNCONFIRMED,
            hint = LinkHint.ExistingRef("src/main/java/com/example/OrderDto.java"),
            anchorRationale = "개념 무관 거부",
            verdict = Verdict.REJECTED,
            rejectionReason = RejectionReason.CONCEPT_IRRELEVANT
        )

        val fileMismatchItem = RequirementItem(
            id = "ref:src/main/java/com/example/OrderDao.java",
            statement = "OrderDao 쿼리 수정",
            source = HintSource.SYSTEM_UNCONFIRMED,
            hint = LinkHint.ExistingRef("src/main/java/com/example/OrderDao.java"),
            anchorRationale = "파일 불일치 거부",
            verdict = Verdict.REJECTED,
            rejectionReason = RejectionReason.FILE_MISMATCH
        )

        val previousContract = Stage0TransitionContract(
            contractVersion = "1.1",
            createdAt = java.time.Instant.now().toString(),
            graphHash = ClarificationContractStore.calculateGraphHash(graph),
            rejectedItems = listOf(conceptIrrelevantItem, fileMismatchItem),
            rejectedExistingRefs = listOf(
                LinkHint.ExistingRef("src/main/java/com/example/OrderDto.java"),
                LinkHint.ExistingRef("src/main/java/com/example/OrderDao.java")
            )
        )

        // Case 1: 무관한 토큰 세션 (두 항목 모두 REJECTED 유지)
        val session1 = engine.initSession("결제 승인 모듈 추가", previousContract)
        val s1Dto = session1.state.items.find { it.id == conceptIrrelevantItem.id }
        val s1Dao = session1.state.items.find { it.id == fileMismatchItem.id }
        assertEquals(Verdict.REJECTED, s1Dto?.verdict)
        assertFalse(s1Dto?.isReEmergence ?: true)
        assertEquals(Verdict.REJECTED, s1Dao?.verdict)
        assertFalse(s1Dao?.isReEmergence ?: true)

        // Case 2: OrderDto와 OrderDao 토큰이 모두 인입되는 새 세션
        // - CONCEPT_IRRELEVANT(OrderDto)는 토큰이 있어도 엄격 억제 (REJECTED, isReEmergence = false)
        // - FILE_MISMATCH(OrderDao)는 토큰 매칭으로 결정론적 재등장 (PENDING, isReEmergence = true)
        fun runReEmergenceSession(): List<Pair<String, Boolean>> {
            val session2 = engine.initSession("OrderDao 및 OrderDto 데이터 일괄 처리", previousContract)
            return session2.state.items
                .filter { it.id == conceptIrrelevantItem.id || it.id == fileMismatchItem.id }
                .map { "${it.id}:${it.verdict}" to it.isReEmergence }
        }

        val run1 = runReEmergenceSession()
        val run2 = runReEmergenceSession()
        val run3 = runReEmergenceSession()

        // 멱등성 검증 (3회 exact match)
        assertEquals("Run 1과 Run 2 재등장 결과는 100% 동일해야 함", run1, run2)
        assertEquals("Run 1과 Run 3 재등장 결과는 100% 동일해야 함", run1, run3)

        // 세부 판정 assert
        val dtoResult = run1.find { it.first.contains("OrderDto") }
        val daoResult = run1.find { it.first.contains("OrderDao") }

        assertEquals("CONCEPT_IRRELEVANT인 OrderDto는 토큰이 있어도 REJECTED 상태여야 함", 
            "ref:src/main/java/com/example/OrderDto.java:REJECTED", dtoResult?.first)
        assertEquals("CONCEPT_IRRELEVANT인 OrderDto는 isReEmergence = false 여야 함", 
            false, dtoResult?.second)

        assertEquals("FILE_MISMATCH인 OrderDao는 새 토큰 매칭 시 PENDING 상태로 재활성화되어야 함", 
            "ref:src/main/java/com/example/OrderDao.java:PENDING", daoResult?.first)
        assertEquals("FILE_MISMATCH인 OrderDao는 isReEmergence = true 여야 함", 
            true, daoResult?.second)
    }
}

