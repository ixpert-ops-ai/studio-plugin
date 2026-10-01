package net.ib.ixpert.ops.wuwagent.agent

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

class RequirementAnalysisPipelineIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

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

    /**
     * [회귀 방어 테스트] 디스크에 과거 세션 계약(previousContract)이 남아있는 상태에서
     * 새 세션(stage0Contract = null, 신규 ClarifyIntent) 실행 시 과거 trustedExistingRefs나 newCreations가 침투하지 않음을 검증.
     * - 과거의 trustedExistingRefs, newCreations는 절대 합성되지 않아야 함 (유령 오염 0건)
     * - 과거의 rejectedExistingRefs는 배제 필터로서 정상 동작해야 함
     */
    @Test
    fun testStaleContractDoesNotContaminateNewSession() = kotlinx.coroutines.runBlocking {
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
                ),
                "com/example/OldBrandMessageService.java" to FileNode(
                    path = "com/example/OldBrandMessageService.java",
                    packageName = "com.example",
                    className = "OldBrandMessageService",
                    fileType = SpringFileType.SERVICE,
                    layer = ArchitectureLayer.SERVICE
                ),
                "com/example/RejectedStaleFile.java" to FileNode(
                    path = "com/example/RejectedStaleFile.java",
                    packageName = "com.example",
                    className = "RejectedStaleFile",
                    fileType = SpringFileType.SERVICE,
                    layer = ArchitectureLayer.SERVICE
                )
            ),
            relationships = emptyList(),
            statistics = GraphStatistics()
        )

        // 과거 세션에서 남겨진 디스크 계약 시뮬레이션
        val staleTrusted = net.ib.ixpert.ops.wuwagent.agent.clarify.model.LinkHint.ExistingRef("com/example/OldBrandMessageService.java")
        val staleNew = net.ib.ixpert.ops.wuwagent.agent.clarify.model.RequirementItem(
            id = "stale_new",
            statement = "com/example/StaleBizgoClient.java",
            source = net.ib.ixpert.ops.wuwagent.agent.clarify.model.HintSource.USER_UTTERED,
            hint = net.ib.ixpert.ops.wuwagent.agent.clarify.model.LinkHint.NewCreation,
            anchorRationale = "과거 신규 생성",
            verdict = net.ib.ixpert.ops.wuwagent.agent.clarify.model.Verdict.CONFIRMED
        )
        val staleRejected = net.ib.ixpert.ops.wuwagent.agent.clarify.model.LinkHint.ExistingRef("com/example/RejectedStaleFile.java")

        val previousStaleContract = net.ib.ixpert.ops.wuwagent.agent.clarify.model.Stage0TransitionContract(
            createdAt = java.time.Instant.now().toString(),
            graphHash = "staleHash",
            trustedExistingRefs = listOf(staleTrusted),
            newCreations = listOf(staleNew),
            rejectedExistingRefs = listOf(staleRejected),
            enrichedRequirementText = "과거 브랜드메시지 요구사항"
        )

        // 신규 세션의 ClarifyIntent (설문 마감일 검색)
        val newIntent = net.ib.ixpert.ops.wuwagent.agent.clarify.model.ClarifyIntent(
            originalRequirement = "설문 마감일 검색 추가",
            refinedRequirement = "설문조사 목록 화면에 '설문 마감일' 검색 조건을 추가한다.",
            anchorTokens = listOf("ExistingService"),
            constraints = emptyList(),
            excludedFiles = emptyList(),
            graphHash = "currentHash",
            contractVersion = "1.0"
        )

        val pipeline = RequirementAnalysisPipeline(dummyClient)
        val result = pipeline.analyze(
            primaryReq = "설문 마감일 검색 추가",
            secondaryReq = "",
            projectGraph = graph,
            stage0Contract = null, // 신규 세션이므로 stage0Contract는 없음
            clarifyIntent = newIntent,
            previousContract = previousStaleContract // 디스크에서 로드된 과거 계약 주입
        )

        // 1. 과거 trustedExistingRefs가 현재 TargetFiles에 침투하지 않았음을 검증 (핵심)
        assertFalse(
            "과거 계약의 trustedExistingRefs(OldBrandMessageService)는 신규 세션 targetFiles에 침투하지 않아야 함",
            result.targetFiles.any { it.path == "com/example/OldBrandMessageService.java" && it.description.contains("Stage 0") }
        )

        // 2. 과거 newCreations가 현재 TargetFiles에 침투하지 않았음을 검증 (핵심)
        assertFalse(
            "과거 계약의 newCreations(StaleBizgoClient)는 신규 세션 targetFiles에 침투하지 않아야 함",
            result.targetFiles.any { it.path == "com/example/StaleBizgoClient.java" }
        )

        // 3. Stage 0 합성 흔적 부재 검증
        val stage0SynthesizedCount = result.targetFiles.count { it.description.contains("Stage 0 확정 기존 파일") || it.description.contains("Stage 0 사용자 신규 생성 지정") }
        assertEquals("previousContract에서 유래한 Stage 0 합성 항목은 정확히 0건이어야 함", 0, stage0SynthesizedCount)

        // 4. 과거 rejectedExistingRefs는 배제 필터로서 정상 동작하여 결과에 없어야 함
        assertFalse(
            "previousContract의 rejectedExistingRefs(RejectedStaleFile)는 결과에서 완전히 배제되어야 함",
            result.targetFiles.any { it.path == "com/example/RejectedStaleFile.java" }
        )
    }

    /**
     * [Phase 2 6대 불변식 전주기 통합 테스트]
     * (a) Intent 수신 및 파이프라인 완주 (refinedRequirement 정상 소비)
     * (b) 단독 실행 하위 호환성 (ClarifyIntent = null 시에도 자립 완주)
     * (c) Analyze 산출물 파일 계약 저장 (디스크 ClarificationContract 단독 생산)
     * (e) 0-재출현 디스크 라운드트립 보존 (디스크 저장 -> 재로드 -> 2차 Analyze 배제 및 거부 누적)
     * (f) 계약 단일 생산 SSOT (Clarify 완료 시 Intent만 저장, Analyze 완주 시에만 파일 계약 저장)
     */
    @Test
    fun testPhase2Invariants_IntentConsumption_ContractProduction_DiskRoundtripZeroRevival() = kotlinx.coroutines.runBlocking {
        val projectRoot = tempFolder.newFolder("pipeline_roundtrip")
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
            projectRoot = projectRoot.absolutePath,
            frameworkType = FrameworkType.SPRING_MVC_MYBATIS,
            files = mapOf(
                "com/example/ExistingService.java" to FileNode(
                    path = "com/example/ExistingService.java",
                    packageName = "com.example",
                    className = "ExistingService",
                    fileType = SpringFileType.SERVICE,
                    layer = ArchitectureLayer.SERVICE
                ),
                "com/example/ObsoleteService.java" to FileNode(
                    path = "com/example/ObsoleteService.java",
                    packageName = "com.example",
                    className = "ObsoleteService",
                    fileType = SpringFileType.SERVICE,
                    layer = ArchitectureLayer.SERVICE
                )
            ),
            relationships = emptyList(),
            statistics = GraphStatistics()
        )

        val graphHash = net.ib.ixpert.ops.wuwagent.agent.clarify.ClarificationContractStore.calculateGraphHash(graph)

        // 1. ClarifyIntent 생성 및 저장 (Clarify 단계 시뮬레이션)
        val clarifyIntent = net.ib.ixpert.ops.wuwagent.agent.clarify.model.ClarifyIntent(
            originalRequirement = "서비스 수정 요청",
            refinedRequirement = "ExistingService 및 ObsoleteService 기능 점검 및 수정",
            anchorTokens = listOf("ExistingService", "ObsoleteService"),
            constraints = listOf(
                net.ib.ixpert.ops.wuwagent.agent.clarify.model.IntentConstraint(
                    net.ib.ixpert.ops.wuwagent.agent.clarify.model.ConstraintKind.SCOPE_LIMIT,
                    "기존 서비스 범위 한정"
                )
            ),
            graphHash = graphHash,
            contractVersion = "1.0"
        )
        val savedIntentFile = net.ib.ixpert.ops.wuwagent.agent.clarify.ClarifyIntentStore.saveIntent(projectRoot, clarifyIntent)
        assertTrue("ClarifyIntent 파일이 디스크에 저장되어야 함", savedIntentFile.exists())

        // [불변식 f 검증]: Clarify 완결 시점에는 ClarificationContract 파일이 아직 디스크에 없어야 함 (단일 생산 SSOT)
        assertNull(
            "Clarify 단계에서는 ClarificationContract 파일이 생성되지 않아야 함",
            net.ib.ixpert.ops.wuwagent.agent.clarify.ClarificationContractStore.findContractFile(projectRoot)
        )

        val pipeline = RequirementAnalysisPipeline(dummyClient)

        // 2. 1차 Analyze 실행 (ClarifyIntent 주입, previousContract = null)
        val result1 = pipeline.analyze(
            primaryReq = clarifyIntent.originalRequirement,
            secondaryReq = "",
            projectGraph = graph,
            clarifyIntent = clarifyIntent,
            previousContract = null,
            projectRoot = projectRoot
        )

        // [불변식 a 검증]: ClarifyIntent 수신 및 파이프라인 정상 완주
        assertNotNull("1차 분석 결과가 정상 반환되어야 함", result1)
        assertTrue("후보 파일들이 탐색되어야 함", result1.targetFiles.isNotEmpty())

        // [불변식 c 검증]: Analyze 완주 후 최종 파일 계약(ClarificationContract)이 디스크에 단독 저장됨
        val contractFile1 = net.ib.ixpert.ops.wuwagent.agent.clarify.ClarificationContractStore.findContractFile(projectRoot)
        assertNotNull("Analyze 실행 완료 후 ClarificationContract 파일이 디스크에 생성되어야 함", contractFile1)
        val loadedContract1 = net.ib.ixpert.ops.wuwagent.agent.clarify.ClarificationContractStore.loadContract(contractFile1!!, graph)
        assertEquals("계약의 enrichedRequirementText에 정제문이 반영되어야 함", clarifyIntent.refinedRequirement, loadedContract1.enrichedRequirementText)
        assertTrue("trustedExistingRefs에 탐색된 파일들이 저장되어야 함", loadedContract1.trustedExistingRefs.isNotEmpty())

        // 3. 디스크 왕복 0-재출현 시뮬레이션:
        // 사용자가 ObsoleteService.java를 제외(REJECTED)하고 계약을 디스크에 갱신 저장한 시나리오
        val userRejectedContract = loadedContract1.copy(
            rejectedExistingRefs = listOf(net.ib.ixpert.ops.wuwagent.agent.clarify.model.LinkHint.ExistingRef("com/example/ObsoleteService.java")),
            trustedExistingRefs = loadedContract1.trustedExistingRefs.filter { it.filePath != "com/example/ObsoleteService.java" }
        )
        net.ib.ixpert.ops.wuwagent.agent.clarify.ClarificationContractStore.saveContract(projectRoot, userRejectedContract)

        // 디스크에서 계약 재로드 (디스크 라운드트립 무결성 확인)
        val reloadedContractFromDisk = net.ib.ixpert.ops.wuwagent.agent.clarify.ClarificationContractStore.loadContractByKey(projectRoot, graph)
        assertNotNull("디스크에서 이전 계약을 재로드할 수 있어야 함", reloadedContractFromDisk)
        assertEquals(1, reloadedContractFromDisk?.rejectedExistingRefs?.size)
        assertEquals("com/example/ObsoleteService.java", reloadedContractFromDisk?.rejectedExistingRefs?.first()?.filePath)

        // 4. 2차 Analyze 실행 (재로드된 previousContract 주입)
        val result2 = pipeline.analyze(
            primaryReq = clarifyIntent.originalRequirement,
            secondaryReq = "",
            projectGraph = graph,
            clarifyIntent = clarifyIntent,
            previousContract = reloadedContractFromDisk,
            projectRoot = projectRoot
        )

        // [불변식 e 검증]: 0-재출현 디스크 라운드트립 보존
        // - 2차 Analyze의 targetFiles에서 제외된 파일(ObsoleteService.java)이 0건 부활(완전 배제)
        assertFalse(
            "이전 계약에서 거부된 ObsoleteService.java는 2차 분석 결과에 절대 부활해서는 안 됨 (0건 부활)",
            result2.targetFiles.any { it.path == "com/example/ObsoleteService.java" }
        )

        // - 2차 Analyze 완료 후 새로 저장된 디스크 계약 아티팩트에도 거부 이력이 누적 보존되어야 함
        val contractFile2 = net.ib.ixpert.ops.wuwagent.agent.clarify.ClarificationContractStore.findContractFile(projectRoot)
        assertNotNull(contractFile2)
        val loadedContract2 = net.ib.ixpert.ops.wuwagent.agent.clarify.ClarificationContractStore.loadContract(contractFile2!!, graph)
        assertTrue(
            "2차 Analyze 완료 후 저장된 계약에도 거부 이력이 누적 보존되어야 함",
            loadedContract2.rejectedExistingRefs.any { it.filePath == "com/example/ObsoleteService.java" }
        )
    }

    /**
     * [Phase 2 순수 배선 동일성 검증 테스트]
     * 동일한 요구사항("설문 발송 채널에 브랜드메시지 추가")에 대해:
     * - 경로 1: 단독 /analyze 실행 (intent = null, previousContract = null)
     * - 경로 2: clarify -> analyze 인텐트 배선 실행 (intent 주입)
     * 
     * 검증 항목:
     * 1) 두 경로에서 산출된 targetFiles의 개수, 파일 경로 목록, 정렬 순서가 100% 비트 동일함 (순수 배선 증명)
     * 2) 4단계(Hub Penalty) 이전이므로 오탐 파일(존재 시)이 두 경로 모두에 동일하게 온전히 보존됨
     */
    @Test
    fun testPhase2PureWiringEquivalence_StandaloneVsIntentPath_IdenticalOutputs() = kotlinx.coroutines.runBlocking {
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

        // survey_admin 실물 그래프가 존재하면 실물 그래프 사용, 없으면 합성 그래프 사용
        val surveyAdminFile = java.io.File("C:/Workspace/HC_card_survey_admin/survey_admin/.meta/project-graph.json")
        val graph = if (surveyAdminFile.exists()) {
            com.google.gson.Gson().fromJson(surveyAdminFile.readText(Charsets.UTF_8), ProjectGraph::class.java)
        } else {
            ProjectGraph(
                generatedAt = Instant.now().toString(),
                projectRoot = "/test/root",
                frameworkType = FrameworkType.SPRING_MVC_MYBATIS,
                files = mapOf(
                    "src/main/java/com/example/survey/SurveyService.java" to FileNode(
                        path = "src/main/java/com/example/survey/SurveyService.java",
                        packageName = "com.example.survey",
                        className = "SurveyService",
                        fileType = SpringFileType.SERVICE,
                        layer = ArchitectureLayer.SERVICE
                    ),
                    "src/main/java/com/example/common/IpsController.java" to FileNode(
                        path = "src/main/java/com/example/common/IpsController.java",
                        packageName = "com.example.common",
                        className = "IpsController",
                        fileType = SpringFileType.CONTROLLER,
                        layer = ArchitectureLayer.PRESENTATION
                    )
                ),
                resourceNodes = listOf(
                    ResourceNode(
                        path = "src/main/resources/sql/sql_address.xml",
                        type = ResourceType.MYBATIS_MAPPER,
                        layer = "PERSISTENCE",
                        linkedTo = emptyList(),
                        linkType = "",
                        metadata = emptyMap()
                    )
                ),
                relationships = emptyList(),
                statistics = GraphStatistics()
            )
        }

        val requirement = "설문 발송 채널에 브랜드메시지 추가"
        val graphHash = net.ib.ixpert.ops.wuwagent.agent.clarify.ClarificationContractStore.calculateGraphHash(graph)

        val pipeline = RequirementAnalysisPipeline(dummyClient)

        // 경로 1: 단독 /analyze 실행 (intent = null)
        val standaloneResult = pipeline.analyze(
            primaryReq = requirement,
            secondaryReq = "",
            projectGraph = graph,
            clarifyIntent = null,
            previousContract = null
        )

        // 경로 2: ClarifyIntent 주입 실행 (intent 경로)
        val intent = net.ib.ixpert.ops.wuwagent.agent.clarify.model.ClarifyIntent(
            originalRequirement = requirement,
            refinedRequirement = requirement, // 동일 요구사항 기준
            anchorTokens = listOf("설문", "발송", "브랜드메시지"),
            constraints = emptyList(),
            graphHash = graphHash,
            contractVersion = "1.0"
        )
        val intentResult = pipeline.analyze(
            primaryReq = requirement,
            secondaryReq = "",
            projectGraph = graph,
            clarifyIntent = intent,
            previousContract = null
        )

        // 1. 결과 개수 동일성 검증
        assertEquals(
            "단독 경로와 Intent 경로의 대상 파일 개수는 100% 동일해야 함",
            standaloneResult.targetFiles.size,
            intentResult.targetFiles.size
        )

        // 2. 파일 목록 및 순서 완전 일치 검증
        val standalonePaths = standaloneResult.targetFiles.map { it.path }
        val intentPaths = intentResult.targetFiles.map { it.path }
        assertEquals(
            "단독 경로와 Intent 경로의 파일 경로 및 순서는 100% 비트 동일해야 함 (순수 배선 증명)",
            standalonePaths,
            intentPaths
        )

        println("=== [배선 전후 결과 동일성 검증 완료] ===")
        println("총 산출 파일 수: ${standalonePaths.size}개")
        standalonePaths.forEachIndexed { i, p -> println(" [${i + 1}] $p") }
    }

    /**
     * [Phase 3-2 불변식 a 실증 테스트]
     * ClarifyIntent.excludedFiles (1급 결정) 주입 시:
     * 1) [Before 실증]: excludedFiles = emptyList()일 때 ObsoleteService.java가 targetFiles에 존재함을 먼저 확인
     * 2) [After 실증]: excludedFiles = listOf(ObsoleteService.java) 주입 시 targetFiles에서 0건으로 완전히 사라짐을 대조 확인
     * 3) [디스크 라운드트립 & 영속 보존]: Analyze 완주 후 저장된 ClarificationContract의 rejectedExistingRefs에 excludedFiles가 영속화되고, 재로드 시에도 0건 부활이 보존됨을 확인
     */
    @Test
    fun testPhase32_ClarifyIntentExcludedFiles_BeforeAfterContrastAndDiskRoundtripZeroRevival() = kotlinx.coroutines.runBlocking {
        val projectRoot = tempFolder.newFolder("phase32_excluded_files_contrast")
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
            projectRoot = projectRoot.absolutePath,
            frameworkType = FrameworkType.SPRING_MVC_MYBATIS,
            files = mapOf(
                "com/example/ExistingService.java" to FileNode(
                    path = "com/example/ExistingService.java",
                    packageName = "com.example",
                    className = "ExistingService",
                    fileType = SpringFileType.SERVICE,
                    layer = ArchitectureLayer.SERVICE
                ),
                "com/example/ObsoleteService.java" to FileNode(
                    path = "com/example/ObsoleteService.java",
                    packageName = "com.example",
                    className = "ObsoleteService",
                    fileType = SpringFileType.SERVICE,
                    layer = ArchitectureLayer.SERVICE
                )
            ),
            relationships = emptyList(),
            statistics = GraphStatistics()
        )

        val graphHash = net.ib.ixpert.ops.wuwagent.agent.clarify.ClarificationContractStore.calculateGraphHash(graph)
        val pipeline = RequirementAnalysisPipeline(dummyClient)
        val requirement = "ExistingService 및 ObsoleteService 서비스 로직 수정"

        // 1. [Before 실증] excludedFiles가 없을 때 (emptyList)
        val intentBefore = net.ib.ixpert.ops.wuwagent.agent.clarify.model.ClarifyIntent(
            originalRequirement = requirement,
            refinedRequirement = requirement,
            anchorTokens = listOf("ExistingService", "ObsoleteService"),
            constraints = emptyList(),
            excludedFiles = emptyList(), // 배제 없음
            graphHash = graphHash,
            contractVersion = "1.0"
        )
        val resultBefore = pipeline.analyze(
            primaryReq = requirement,
            secondaryReq = "",
            projectGraph = graph,
            clarifyIntent = intentBefore,
            previousContract = null,
            projectRoot = projectRoot
        )

        // Before 대조 확인: ObsoleteService.java가 targetFiles에 존재해야 함
        assertTrue(
            "Before 실증: excludedFiles가 비어있을 때 ObsoleteService.java는 targetFiles에 반드시 존재해야 함",
            resultBefore.targetFiles.any { it.path == "com/example/ObsoleteService.java" }
        )
        val beforeCount = resultBefore.targetFiles.size
        println("=== [Phase 3-2 Before 실증] ===")
        println("excludedFiles = emptyList() 시 후보 수: $beforeCount, ObsoleteService 포함 여부: true")

        // 2. [After 실증] excludedFiles에 ObsoleteService.java를 명시적으로 주입
        val intentAfter = intentBefore.copy(
            excludedFiles = listOf("com/example/ObsoleteService.java")
        )
        val resultAfter = pipeline.analyze(
            primaryReq = requirement,
            secondaryReq = "",
            projectGraph = graph,
            clarifyIntent = intentAfter,
            previousContract = null,
            projectRoot = projectRoot
        )

        // After 대조 확인: ObsoleteService.java가 targetFiles에서 완전히 사라져야 함 (0건 부활)
        assertFalse(
            "After 실증: excludedFiles에 ObsoleteService.java가 주입되면 targetFiles에서 0건으로 완전히 사라져야 함",
            resultAfter.targetFiles.any { it.path == "com/example/ObsoleteService.java" }
        )
        assertTrue(
            "After 실증: ExistingService.java는 정상 유지되어야 함",
            resultAfter.targetFiles.any { it.path == "com/example/ExistingService.java" }
        )
        assertEquals(
            "After 실증: 대상 파일 수가 Before 대비 정확히 1건 감소해야 함",
            beforeCount - 1,
            resultAfter.targetFiles.size
        )
        println("=== [Phase 3-2 After 실증] ===")
        println("excludedFiles = ['com/example/ObsoleteService.java'] 시 후보 수: ${resultAfter.targetFiles.size}, ObsoleteService 포함 여부: false (0건 배제 확인)")

        // 3. [디스크 라운드트립 & 누적 보존 검증]
        // Analyze 완주 후 저장된 ClarificationContract 파일 확인
        val contractFile = net.ib.ixpert.ops.wuwagent.agent.clarify.ClarificationContractStore.findContractFile(projectRoot)
        assertNotNull("Analyze 실행 후 ClarificationContract가 디스크에 생성되어야 함", contractFile)
        val loadedContract = net.ib.ixpert.ops.wuwagent.agent.clarify.ClarificationContractStore.loadContract(contractFile!!, graph)

        assertTrue(
            "ClarifyIntent의 excludedFiles가 최종 ClarificationContract의 rejectedExistingRefs에 누적 영속화되어야 함",
            loadedContract.rejectedExistingRefs.any { it.filePath == "com/example/ObsoleteService.java" }
        )
        println("=== [Phase 3-2 디스크 영속화 검증] ===")
        println("디스크 계약 아티팩트 rejectedExistingRefs: ${loadedContract.rejectedExistingRefs.map { it.filePath }}")
    }

    /**
     * [UnresolvedItems] 파이프라인 무간섭 불변식 검증:
     * ClarifyIntent에 unresolvedItems가 포함되어 있어도 RequirementAnalysisPipeline.analyze 결과의
     * 대상 파일 목록(targetFiles), 랭킹, 가중치 및 전달 프롬프트에 0%의 영향(완전 무간섭)을 주는지 검증
     */
    @Test
    fun testNonInterferenceInvariant_UnresolvedItemsDoNotAlterPipelineAnalysisOutput() = kotlinx.coroutines.runBlocking {
        val projectRoot = tempFolder.newFolder("pipeline_non_interference")
        val recordedPrompts = mutableListOf<String>()

        val recordingClient = object : net.ib.ixpert.ops.wuwagent.client.LLMClient {
            override fun chat(
                systemPrompt: String,
                userCode: String,
                maxTokens: Int?,
                onChunk: ((String) -> Unit)?
            ): net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse? {
                recordedPrompts.add("CHAT:\n$systemPrompt\n---\n$userCode")
                return net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse(
                    model = "test",
                    createdAt = "",
                    message = net.ib.ixpert.ops.wuwagent.model.OllamaMessage("assistant", "{}"),
                    done = true
                )
            }

            override fun chatWithTools(
                systemPrompt: String,
                messages: List<net.ib.ixpert.ops.wuwagent.model.ChatMessage>,
                maxTokens: Int?,
                tools: List<net.ib.ixpert.ops.wuwagent.model.ToolDefinition>?,
                toolChoice: Any?,
                temperature: Double?
            ): net.ib.ixpert.ops.wuwagent.model.ChatCompletionResponse? {
                val promptRepresentation = "CHAT_WITH_TOOLS:\nSYSTEM: $systemPrompt\n" + messages.joinToString("\n") { "${it.role}: ${it.content}" }
                recordedPrompts.add(promptRepresentation)
                return null
            }

            override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
        }

        val graph = ProjectGraph(
            generatedAt = Instant.now().toString(),
            projectRoot = projectRoot.absolutePath,
            frameworkType = FrameworkType.SPRING_MVC_MYBATIS,
            files = mapOf(
                "com/example/OrderService.java" to FileNode(
                    path = "com/example/OrderService.java",
                    packageName = "com.example",
                    className = "OrderService",
                    fileType = SpringFileType.SERVICE,
                    layer = ArchitectureLayer.SERVICE
                ),
                "com/example/OrderRepository.java" to FileNode(
                    path = "com/example/OrderRepository.java",
                    packageName = "com.example",
                    className = "OrderRepository",
                    fileType = SpringFileType.MAPPER,
                    layer = ArchitectureLayer.PERSISTENCE
                )
            ),
            relationships = emptyList(),
            statistics = GraphStatistics()
        )

        val graphHash = net.ib.ixpert.ops.wuwagent.agent.clarify.ClarificationContractStore.calculateGraphHash(graph)
        val pipeline = RequirementAnalysisPipeline(recordingClient)
        val requirement = "주문 처리 로직 개선"

        // 1. unresolvedItems가 비어있는 기본 인텐트 실행
        val intentWithoutUnresolved = net.ib.ixpert.ops.wuwagent.agent.clarify.model.ClarifyIntent(
            originalRequirement = requirement,
            refinedRequirement = requirement,
            anchorTokens = listOf("OrderService"),
            constraints = emptyList(),
            excludedFiles = emptyList(),
            unresolvedItems = emptyList(),
            graphHash = graphHash,
            contractVersion = "1.2"
        )
        val resultWithout = pipeline.analyze(
            primaryReq = requirement,
            secondaryReq = "",
            projectGraph = graph,
            clarifyIntent = intentWithoutUnresolved,
            previousContract = null,
            projectRoot = projectRoot
        )

        val promptCountWithout = recordedPrompts.size

        // 2. unresolvedItems가 주입된 인텐트 실행
        val intentWithUnresolved = intentWithoutUnresolved.copy(
            unresolvedItems = listOf(
                net.ib.ixpert.ops.wuwagent.agent.clarify.model.UnresolvedItem(
                    identifier = "BizgoApiService",
                    kind = net.ib.ixpert.ops.wuwagent.agent.clarify.model.UnresolvedKind.NEW_CREATION,
                    filePath = null,
                    source = net.ib.ixpert.ops.wuwagent.agent.clarify.model.HintSource.USER_UTTERED,
                    utteredTurn = 1,
                    lastQuestion = "신규 생성인가요?",
                    lastAskedTurn = 1
                )
            )
        )
        val resultWith = pipeline.analyze(
            primaryReq = requirement,
            secondaryReq = "",
            projectGraph = graph,
            clarifyIntent = intentWithUnresolved,
            previousContract = null,
            projectRoot = projectRoot
        )

        // 3. 무간섭 불변성 단언: targetFiles 크기 및 경로, 랭킹 및 전달된 LLM 프롬프트 목록이 100% 동일해야 함
        val promptsWithout = recordedPrompts.take(promptCountWithout)
        val promptsWith = recordedPrompts.drop(promptCountWithout)
        assertTrue("녹음된 프롬프트 목록은 비어있지 않아야 함 (최소 1개 이상 실행되어야 비교 유효)", promptsWithout.isNotEmpty())
        assertEquals("UnresolvedItems 주입 전후 LLM에 전달된 프롬프트 목록은 100% 동일해야 함", promptsWithout, promptsWith)

        assertEquals(resultWithout.targetFiles.size, resultWith.targetFiles.size)
        assertEquals(
            resultWithout.targetFiles.map { it.path },
            resultWith.targetFiles.map { it.path }
        )
        assertEquals(
            resultWithout.targetFiles.map { it.order },
            resultWith.targetFiles.map { it.order }
        )
        println("=== [UnresolvedItems 파이프라인 무간섭 불변성 검증 완료: recorded prompts count = ${promptsWithout.size}] ===")
    }
}

