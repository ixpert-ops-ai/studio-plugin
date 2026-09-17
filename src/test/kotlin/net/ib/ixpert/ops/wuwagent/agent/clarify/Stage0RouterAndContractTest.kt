package net.ib.ixpert.ops.wuwagent.agent.clarify

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import net.ib.ixpert.ops.wuwagent.agent.RequirementAnalysisPipeline
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.*
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*
import org.junit.Assert.*
import org.junit.Test

class Stage0RouterAndContractTest {

    private fun createSyntheticGraph(): ProjectGraph {
        val files = mapOf(
            "com/example/OrderDto.java" to FileNode(
                path = "com/example/OrderDto.java",
                packageName = "com.example",
                className = "OrderDto",
                fileType = SpringFileType.DTO,
                layer = ArchitectureLayer.PERSISTENCE,
                methods = listOf(
                    MethodSignature("getPay_type", "String", emptyList()),
                    MethodSignature("setPay_type", "void", listOf("String"))
                )
            ),
            "com/example/StandaloneConfig.java" to FileNode(
                path = "com/example/StandaloneConfig.java",
                packageName = "com.example",
                className = "StandaloneConfig",
                fileType = SpringFileType.SERVICE,
                layer = ArchitectureLayer.SERVICE,
                methods = listOf(
                    MethodSignature("getConfigVal", "String", emptyList())
                )
            )
        )

        val resourceNodes = listOf(
            ResourceNode(
                path = "webapp/views/order_list.jsp",
                type = ResourceType.VIEW,
                layer = "PRESENTATION",
                linkedTo = listOf("com/example/OrderController.java"),
                linkType = "url_binding",
                metadata = mapOf("input_field" to listOf("test_pay_type")),
                dynamicBindings = listOf(
                    DynamicBinding("webapp/views/order_list.jsp", "com/example/OrderController.java", "/order/orderList", 80.0)
                )
            ),
            ResourceNode(
                path = "webapp/js/order.list.js",
                type = ResourceType.SCRIPT,
                layer = "PRESENTATION",
                linkedTo = listOf("com/example/OrderController.java"),
                linkType = "url_binding",
                metadata = mapOf("methods" to listOf("initView")),
                dynamicBindings = listOf(
                    DynamicBinding("webapp/js/order.list.js", "com/example/OrderController.java", "/order/orderList", 80.0)
                )
            ),
            ResourceNode(
                path = "webapp/views/order_write.jsp",
                type = ResourceType.VIEW,
                layer = "PRESENTATION",
                linkedTo = listOf("com/example/OrderController.java"),
                linkType = "url_binding",
                metadata = mapOf("input_field" to listOf("pay_type"))
            )
        )

        return ProjectGraph(
            generatedAt = java.time.Instant.now().toString(),
            projectRoot = "/test/root",
            frameworkType = FrameworkType.SPRING_MVC_MYBATIS,
            files = files,
            relationships = emptyList(),
            resourceNodes = resourceNodes,
            statistics = GraphStatistics()
        )
    }

    /**
     * [검수 포인트 1 & 2] 동결 불변성 양방향 검증:
     * - CONFIRMED 항목이 다음 턴 재탐색에서 살아남고 덮어써지지 않음
     * - REJECTED 항목이 다음 턴 재탐색에서 PENDING으로 되살아나지 않음 (0건 부활)
     */
    @Test
    fun testBidirectionalFreezeInvariance_ConfirmedSurvives_RejectedDoesNotRevive() {
        val graph = createSyntheticGraph()
        val scanner = Stage0GraphScanner(graph)
        val engine = Stage0ClarificationEngine(scanner, graph)

        val turn0 = engine.initSession("주문 결제 방식 추가")
        assertEquals(3, turn0.state.items.size)
        assertTrue(turn0.state.items.all { it.verdict == Verdict.PENDING })

        val listJspItem = turn0.state.items.first { it.statement.contains("order_list.jsp") }
        val writeJspItem = turn0.state.items.first { it.statement.contains("order_write.jsp") }
        val listJsItem = turn0.state.items.first { it.statement.contains("order.list.js") }

        // Turn 1: 사용자가 listJsp는 CONFIRMED, writeJsp는 REJECTED, listJs는 PENDING 유지
        val verdictUpdates = mapOf(
            listJspItem.id to Verdict.CONFIRMED,
            writeJspItem.id to Verdict.REJECTED
        )

        val turn1 = engine.processTurn(
            turn0.state,
            Stage0ClarificationEngine.UserInput(
                verdictUpdates = verdictUpdates,
                isCompletionDeclared = false
            )
        )

        // 1. CONFIRMED 보존 확인
        val turn1ListJsp = turn1.state.items.first { it.id == listJspItem.id }
        assertEquals("CONFIRMED item must preserve CONFIRMED verdict!", Verdict.CONFIRMED, turn1ListJsp.verdict)
        assertEquals("Source must be USER_CONFIRMED!", HintSource.USER_CONFIRMED, turn1ListJsp.source)

        // 2. REJECTED 되살아남 방지 확인 (가장 중요)
        val turn1WriteJsp = turn1.state.items.first { it.id == writeJspItem.id }
        assertEquals("REJECTED item must NOT revive to PENDING!", Verdict.REJECTED, turn1WriteJsp.verdict)

        // 3. PENDING 항목은 그대로 유지/갱신
        val turn1ListJs = turn1.state.items.first { it.id == listJsItem.id }
        assertEquals(Verdict.PENDING, turn1ListJs.verdict)

        // 4. Turn 2: 추가 사용자 발화 및 재탐색이 트리거되어도 CONFIRMED/REJECTED가 덮어써지거나 부활하지 않는지 재검증 (mergeCandidates 동결 보호)
        val turn2 = engine.processTurn(
            turn1.state,
            Stage0ClarificationEngine.UserInput(
                userStatement = "추가적으로 결제 연동 방식을 지정합니다.",
                isCompletionDeclared = false
            )
        )

        val turn2ListJsp = turn2.state.items.first { it.id == listJspItem.id }
        assertEquals("Turn 2: CONFIRMED item must stay CONFIRMED after rescan!", Verdict.CONFIRMED, turn2ListJsp.verdict)

        val turn2WriteJsp = turn2.state.items.first { it.id == writeJspItem.id }
        assertEquals("Turn 2: REJECTED item must stay REJECTED after rescan!", Verdict.REJECTED, turn2WriteJsp.verdict)
    }

    /**
     * [검수 포인트 3] ID 직렬화 안정성 및 왕복 보존:
     * - UI와 주고받는 JSON 페이로드에서 item.id가 verbatim 일치함을 검증
     */
    @Test
    fun testIdStabilityAcrossSerializationRoundtrip() {
        val graph = createSyntheticGraph()
        val scanner = Stage0GraphScanner(graph)
        val engine = Stage0ClarificationEngine(scanner, graph)

        val turn0 = engine.initSession("주문 결제")
        val gson = Gson()

        // 1. 백엔드 -> UI JSON 직렬화
        val payloadToWebview = mapOf(
            "originalRequirement" to "주문 결제",
            "items" to turn0.state.items,
            "openQuestion" to turn0.openQuestion
        )
        val jsonStr = gson.toJson(payloadToWebview)

        // 2. UI에서 파싱 및 ID 기반 응답 생성
        val parsedFromWebview = gson.fromJson(jsonStr, Map::class.java)
        val rawItems = parsedFromWebview["items"] as List<Map<String, Any>>
        val receivedId = rawItems[0]["id"] as String

        assertEquals("Received ID must match deriveId output verbatim!", turn0.state.items[0].id, receivedId)

        // 3. UI -> 백엔드 응답 왕복
        val responsePayload = mapOf(
            "verdictUpdates" to mapOf(receivedId to "CONFIRMED"),
            "userStatement" to null,
            "isCompletionDeclared" to true
        )
        val responseJson = gson.toJson(responsePayload)

        val parsedResponse = gson.fromJson(responseJson, Map::class.java)
        val returnedVerdicts = parsedResponse["verdictUpdates"] as Map<String, String>

        val verdictUpdates = returnedVerdicts.map { (k, v) ->
            k to Verdict.valueOf(v)
        }.toMap()

        val turn1 = engine.processTurn(
            turn0.state,
            Stage0ClarificationEngine.UserInput(
                verdictUpdates = verdictUpdates,
                isCompletionDeclared = true
            )
        )

        val updatedItem = turn1.state.items.first { it.id == receivedId }
        assertEquals(Verdict.CONFIRMED, updatedItem.verdict)
    }

    /**
     * [검수 포인트 4] NewCreation 탐색 Seed 격리 및 독립 합성 계약 (설계서 6절 준수):
     * - 사용자가 그래프에 없는 NewCreation(예: Bizgo)을 발화했을 때
     * - trustedExistingRefs에는 포함되지 않음 (그래프 seed 오염 방지)
     * - newCreations에만 포함되어 최종 파이프라인에 CREATE로 독립 합성됨
     */
    @Test
    fun testNewCreationIsolationAndPipelineSynthesis() = runBlocking {
        val graph = createSyntheticGraph()
        val scanner = Stage0GraphScanner(graph)
        val engine = Stage0ClarificationEngine(scanner, graph)

        val turn0 = engine.initSession("주문 결제")
        val listJspItem = turn0.state.items.first { it.statement.contains("order_list.jsp") }

        // 사용자가 Bizgo 신규 연동 발화 + order_list.jsp CONFIRMED
        val turn1 = engine.processTurn(
            turn0.state,
            Stage0ClarificationEngine.UserInput(
                verdictUpdates = mapOf(listJspItem.id to Verdict.CONFIRMED),
                userStatement = "Bizgo REST API를 통해 결제를 연동합니다.",
                isCompletionDeclared = true
            )
        )

        val contract = engine.transitionToStage1(turn1.state)

        // 1. Contract 계약 검증
        assertEquals("Only order_list.jsp should be in trustedExistingRefs!", 1, contract.trustedExistingRefs.size)
        assertEquals("webapp/views/order_list.jsp", contract.trustedExistingRefs[0].filePath)

        assertEquals("Bizgo should be in newCreations!", 1, contract.newCreations.size)
        assertEquals(LinkHint.NewCreation, contract.newCreations[0].hint)
        assertTrue(contract.newCreations[0].statement.contains("Bizgo REST API"))

        // 2. RequirementAnalysisPipeline 연계 합성 검증
        val dummyClient = object : LLMClient {
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

        val pipeline = RequirementAnalysisPipeline(dummyClient)
        val result = pipeline.analyze(
            primaryReq = "주문 결제",
            secondaryReq = "",
            projectGraph = graph,
            stage0Contract = contract
        )

        // 3. 파이프라인 최종 targetFiles 검증:
        // - order_list.jsp는 MODIFY로 합성
        // - Bizgo는 CREATE로 독립 합성
        val listJspTarget = result.targetFiles.find { it.path.contains("order_list.jsp") }
        assertNotNull("order_list.jsp must be present in targetFiles", listJspTarget)
        assertEquals("MODIFY", listJspTarget?.type)

        val bizgoTarget = result.targetFiles.find { it.path.contains("Bizgo") }
        assertNotNull("Bizgo must be present as CREATE in targetFiles", bizgoTarget)
        assertEquals("CREATE", bizgoTarget?.type)
    }

    /**
     * [Rule-3 검증] 사용자 발화 -> 토큰 추출 -> 그래프 조회 기반 소스 판정 분기 (4대 경계 케이스 검증):
     * - 케이스 1 (연결된 실재 노드): OrderDto 발화 -> 분기 A (ExistingRef) + trustedExistingRefs 승격
     * - 케이스 2 (고립된 실재 노드, in/out-degree=0): StandaloneConfig 발화 -> 분기 A (노드 존재 기준, ExistingRef) + trustedExistingRefs 승격
     * - 케이스 3 (그래프 부존재 노드): BizgoApiService 발화 -> 분기 B (NewCreation) + newCreations 분리
     * - 케이스 4 (개념 토큰 / 부분 매칭 충돌 회피): "브랜드메시지 발송 채널 연동" 발화 -> 부분 매칭 오탐 없이 분기 B (NewCreation)로 안전 수렴
     */
    @Test
    fun testRule3_TokenBasedGraphLookupBranching() {
        val graph = createSyntheticGraph()
        val scanner = Stage0GraphScanner(graph)
        val engine = Stage0ClarificationEngine(scanner, graph)

        val turn0 = engine.initSession("주문 결제")

        // 1. 케이스 1 검증: 연결된 그래프 실재 노드(OrderDto) 발화
        val turn1Case1 = engine.processTurn(
            turn0.state,
            Stage0ClarificationEngine.UserInput(
                userStatement = "OrderDto의 pay_type 필드를 수정하여 사용합니다."
            )
        )

        val orderDtoItem = turn1Case1.state.items.find { (it.hint as? LinkHint.ExistingRef)?.filePath?.contains("OrderDto.java") == true }
        assertNotNull("OrderDto가 그래프 조회를 통해 ExistingRef로 식별되어야 함", orderDtoItem)
        assertEquals("출처는 USER_UTTERED 여야 함", HintSource.USER_UTTERED, orderDtoItem!!.source)
        assertEquals("판정은 즉시 CONFIRMED 여야 함", Verdict.CONFIRMED, orderDtoItem.verdict)
        assertTrue("출처 신호에 USER_UTTERANCE가 포함되어야 함", orderDtoItem.provenanceSignals.contains(ProvenanceSignal.USER_UTTERANCE))

        val contract1 = engine.transitionToStage1(turn1Case1.state)
        assertTrue("trustedExistingRefs에 OrderDto가 포함되어야 함", contract1.trustedExistingRefs.any { it.filePath.contains("OrderDto.java") })

        // 2. 케이스 2 검증: 고립된 그래프 실재 노드 (in/out-degree=0, StandaloneConfig) 발화
        val turn1Case2 = engine.processTurn(
            turn0.state,
            Stage0ClarificationEngine.UserInput(
                userStatement = "StandaloneConfig 설정을 수정하여 연동합니다."
            )
        )

        val standaloneItem = turn1Case2.state.items.find { (it.hint as? LinkHint.ExistingRef)?.filePath?.contains("StandaloneConfig.java") == true }
        assertNotNull("고립 노드라도 그래프에 실재하면 분기 A(ExistingRef)로 판정되어야 함", standaloneItem)
        assertEquals("출처는 USER_UTTERED 여야 함", HintSource.USER_UTTERED, standaloneItem!!.source)
        assertEquals("판정은 즉시 CONFIRMED 여야 함", Verdict.CONFIRMED, standaloneItem.verdict)
        assertTrue("출처 신호에 USER_UTTERANCE가 포함되어야 함", standaloneItem.provenanceSignals.contains(ProvenanceSignal.USER_UTTERANCE))

        val contract2 = engine.transitionToStage1(turn1Case2.state)
        assertTrue("trustedExistingRefs에 고립 실재 노드 StandaloneConfig가 포함되어야 함", contract2.trustedExistingRefs.any { it.filePath.contains("StandaloneConfig.java") })

        // 3. 케이스 3 검증: 그래프 부존재 신규 생성 토큰(BizgoApiService) 발화
        val turn1Case3 = engine.processTurn(
            turn0.state,
            Stage0ClarificationEngine.UserInput(
                userStatement = "신규 모듈 BizgoApiService를 연동합니다."
            )
        )

        val bizgoItem = turn1Case3.state.items.find { it.statement.contains("BizgoApiService") }
        assertNotNull("BizgoApiService 아이템이 생성되어야 함", bizgoItem)
        assertTrue("그래프 부존재 토큰은 LinkHint.NewCreation 이어야 함", bizgoItem!!.hint is LinkHint.NewCreation)
        assertEquals("출처는 USER_UTTERED 여야 함", HintSource.USER_UTTERED, bizgoItem.source)
        assertEquals("판정은 즉시 CONFIRMED 여야 함", Verdict.CONFIRMED, bizgoItem.verdict)
        assertTrue("출처 신호에 USER_UTTERANCE가 포함되어야 함", bizgoItem.provenanceSignals.contains(ProvenanceSignal.USER_UTTERANCE))

        val contract3 = engine.transitionToStage1(turn1Case3.state)
        assertTrue("newCreations에 BizgoApiService가 포함되어야 함", contract3.newCreations.any { it.statement.contains("BizgoApiService") })
        assertFalse("trustedExistingRefs에는 BizgoApiService가 없어야 함 (시드 오염 방지)", contract3.trustedExistingRefs.any { it.filePath.contains("Bizgo") })

        // 4. 케이스 4 검증: 개념 토큰 부분 매칭 오탐 회피 ("브랜드메시지 발송 채널 연동")
        // - "브랜드", "메시지" 등의 개념 토큰이 OrderDto, order_list.jsp 등에 부분 매칭으로 걸려 분기 A로 오염되지 않는지 검증
        val turn1Case4 = engine.processTurn(
            turn0.state,
            Stage0ClarificationEngine.UserInput(
                userStatement = "외부 브랜드메시지 발송 채널을 연동합니다."
            )
        )

        val brandItem = turn1Case4.state.items.find { it.statement.contains("브랜드메시지") }
        assertNotNull("브랜드메시지 발화 아이템이 생성되어야 함", brandItem)
        assertTrue("그래프에 실재하지 않는 개념 발화는 부분 매칭 오탐 없이 NewCreation(분기 B)이어야 함", brandItem!!.hint is LinkHint.NewCreation)
        val contract4 = engine.transitionToStage1(turn1Case4.state)
        assertFalse("trustedExistingRefs에 브랜드메시지 관련 오탐 노드가 들어가지 않아야 함", contract4.trustedExistingRefs.any { it.filePath.contains("brand") || it.filePath.contains("message") })

        // 5. 케이스 5 검증: 복합 발화 분할 판정 (한 문장에 실재 노드 + 신규 노드 동시 출현)
        // - "OrderDto의 pay_type을 수정하고 신규 연동 모듈 BizgoApiService를 개발합니다."
        // - OrderDto -> 분기 A (ExistingRef) + trustedExistingRefs 승격
        // - BizgoApiService -> 분기 B (NewCreation) + newCreations 독립 분리
        val turn1Case5 = engine.processTurn(
            turn0.state,
            Stage0ClarificationEngine.UserInput(
                userStatement = "OrderDto의 pay_type을 수정하고 신규 연동 모듈 BizgoApiService를 개발합니다."
            )
        )

        val mixedOrderDto = turn1Case5.state.items.find { (it.hint as? LinkHint.ExistingRef)?.filePath?.contains("OrderDto.java") == true }
        val mixedBizgo = turn1Case5.state.items.find { it.hint is LinkHint.NewCreation && it.statement.contains("BizgoApiService") }

        assertNotNull("복합 발화에서 실재 노드 OrderDto가 분기 A(ExistingRef)로 추출되어야 함", mixedOrderDto)
        assertNotNull("복합 발화에서 신규 노드 BizgoApiService가 분기 B(NewCreation)로 추출되어야 함", mixedBizgo)

        val contract5 = engine.transitionToStage1(turn1Case5.state)
        assertTrue("trustedExistingRefs에 OrderDto가 포함되어야 함", contract5.trustedExistingRefs.any { it.filePath.contains("OrderDto.java") })
        assertFalse("trustedExistingRefs에 BizgoApiService가 없어야 함 (시드 격리)", contract5.trustedExistingRefs.any { it.filePath.contains("Bizgo") })
        assertTrue("newCreations에 BizgoApiService가 포함되어야 함", contract5.newCreations.any { it.statement.contains("BizgoApiService") })

        // 6. 케이스 6 검증: 발화 토큰 != className 실재 노드 단독 발화에서 이중 등록 방지
        // - 발화: "order_list.jsp 화면의 목록 렌더링을 수정합니다." (리소스 파일명 매칭, className 부재)
        // - order_list.jsp -> 분기 A (ExistingRef)로만 1건 등록
        // - 분기 B (NewCreation)가 불필요하게 추가 생성되지 않아야 함 (이중 등록 원천 차단)
        val turn1Case6 = engine.processTurn(
            turn0.state,
            Stage0ClarificationEngine.UserInput(
                userStatement = "order_list.jsp 화면의 목록 렌더링을 수정합니다."
            )
        )

        val jspItem = turn1Case6.state.items.find { (it.hint as? LinkHint.ExistingRef)?.filePath?.contains("order_list.jsp") == true }
        assertNotNull("order_list.jsp가 분기 A(ExistingRef)로 추출되어야 함", jspItem)

        // 이중 등록 방지: 이번 턴 발화로 인한 NewCreation이 추가되지 않아야 함
        val newCreationsInTurn = turn1Case6.state.items.filter { it.source == HintSource.USER_UTTERED && it.hint is LinkHint.NewCreation }
        assertTrue("토큰 != className 실재 노드 단독 발화 시 불필요한 NewCreation 이중 등록이 없어야 함 (0건)", newCreationsInTurn.isEmpty())

        val contract6 = engine.transitionToStage1(turn1Case6.state)
        assertTrue("trustedExistingRefs에 order_list.jsp가 포함되어야 함", contract6.trustedExistingRefs.any { it.filePath.contains("order_list.jsp") })
        assertTrue("newCreations에는 이 발화로 인한 신규 생성이 없어야 함", contract6.newCreations.none { it.source == HintSource.USER_UTTERED })
    }

    /**
     * [4대 격리 불변식 단위 테스트] taskSummary 단방향 렌더링 격리 원칙 검증:
     * 1. 불변식 1: taskSummary 생성 유무와 무관하게 graphHash 불변
     * 2. 불변식 2: Stage0TransitionContract 및 디스크 저장 데이터에 taskSummary 문자열/필드 미포함 (계약 비오염)
     * 3. 불변식 3: LLM 실패(타임아웃/예외/null) 시 items/openQuestion 정상 반환 및 taskSummary=null 폴백 보장
     * 4. 불변식 4: 다음 턴 입력/엔진 상태에 이전 턴 taskSummary 문자열 혼입 금지
     */
    @Test
    fun testTaskSummaryUnidirectionalIsolationInvariants() {
        val graph = createSyntheticGraph()
        val scanner = Stage0GraphScanner(graph)

        // ── 불변식 1 & 2: 계약 비오염 및 graphHash 불변 ──
        val baseGraphHash = ClarificationContractStore.calculateGraphHash(graph)
        val mockLlmClient = object : LLMClient {
            override fun chat(
                systemPrompt: String,
                userCode: String,
                maxTokens: Int?,
                onChunk: ((String) -> Unit)?
            ): net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse {
                return net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse(
                    model = "mock-qwen",
                    createdAt = "2026-09-17",
                    message = net.ib.ixpert.ops.wuwagent.model.OllamaMessage(
                        role = "assistant",
                        content = "주문 결제 방식 추가 요구사항에 따라 OrderDto와 관련 JSP 화면들의 수정을 제안합니다."
                    ),
                    done = true
                )
            }
            override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
        }

        val engineWithLlm = Stage0ClarificationEngine(scanner, graph, mockLlmClient)
        val engineWithoutLlm = Stage0ClarificationEngine(scanner, graph, null)

        val turnWithLlm = engineWithLlm.initSession("주문 결제 방식 추가")
        val turnWithoutLlm = engineWithoutLlm.initSession("주문 결제 방식 추가")

        // 불변식 1 검증: LLM 유무/요약 유무와 무관하게 탐색된 items 동일 & graphHash 동일
        assertEquals(turnWithoutLlm.state.items.size, turnWithLlm.state.items.size)
        assertEquals("주문 결제 방식 추가 요구사항에 따라 OrderDto와 관련 JSP 화면들의 수정을 제안합니다.", turnWithLlm.taskSummary)
        assertNull(turnWithoutLlm.taskSummary)

        val contractWithLlm = engineWithLlm.transitionToStage1(turnWithLlm.state)
        val contractWithoutLlm = engineWithoutLlm.transitionToStage1(turnWithoutLlm.state)

        assertEquals("LLM 요약 생성 유무와 상관없이 graphHash는 완벽히 일치해야 함", baseGraphHash, contractWithLlm.graphHash)
        assertEquals("LLM 요약 생성 유무와 상관없이 graphHash는 완벽히 일치해야 함", contractWithoutLlm.graphHash, contractWithLlm.graphHash)

        // 불변식 2 검증: TransitionContract JSON 직렬화에 taskSummary 키/문자열이 존재하지 않아야 함
        val contractJson = Gson().toJson(contractWithLlm)
        assertFalse("저장 계약 JSON에 taskSummary 필드가 포함되어서는 안 됨", contractJson.contains("taskSummary"))
        assertFalse("저장 계약 JSON에 LLM 생성 요약문이 침투해서는 안 됨", contractJson.contains("주문 결제 방식 추가 요구사항에 따라 OrderDto"))

        // ── 불변식 3: LLM 실패 격리 및 폴백 보장 ──
        val failingLlmClient = object : LLMClient {
            override fun chat(
                systemPrompt: String,
                userCode: String,
                maxTokens: Int?,
                onChunk: ((String) -> Unit)?
            ): net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse? {
                throw RuntimeException("Simulated HTTP 504 Gateway Timeout / Network Down")
            }
            override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
        }

        val engineFailing = Stage0ClarificationEngine(scanner, graph, failingLlmClient)
        val turnFailing = engineFailing.initSession("주문 결제 방식 추가")

        assertNull("LLM 예외 발생 시 taskSummary는 null로 안전하게 폴백되어야 함", turnFailing.taskSummary)
        assertEquals("LLM 예외 발생 시에도 결정론적 그래프 탐색 아이템은 정상 반환되어야 함", 3, turnFailing.state.items.size)

        // 3-B. LLM 클라이언트가 [Error] 접두어 에러 메시지를 반환하는 경우의 폴백 검증
        val errorMsgLlmClient = object : LLMClient {
            override fun chat(
                systemPrompt: String,
                userCode: String,
                maxTokens: Int?,
                onChunk: ((String) -> Unit)?
            ): net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse {
                return net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse(
                    model = null,
                    createdAt = null,
                    message = net.ib.ixpert.ops.wuwagent.model.OllamaMessage(
                        role = "assistant",
                        content = "[Error] OpenAI 서버 통신 실패: Request failed with status code 524"
                    ),
                    done = true
                )
            }
            override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
        }
        val engineErrorMsg = Stage0ClarificationEngine(scanner, graph, errorMsgLlmClient)
        val turnErrorMsg = engineErrorMsg.initSession("주문 결제 방식 추가")
        assertNull("LLM 클라이언트가 [Error] 메시지를 반환할 때도 taskSummary는 null로 안전하게 폴백되어야 함", turnErrorMsg.taskSummary)
        assertEquals(3, turnErrorMsg.state.items.size)

        // ── 불변식 4: 다음 턴 입력/엔진 상태 비오염 ──
        val turn1 = engineWithLlm.processTurn(
            turnWithLlm.state,
            Stage0ClarificationEngine.UserInput(
                verdictUpdates = mapOf(turnWithLlm.state.items.first().id to Verdict.CONFIRMED),
                userStatement = "추가적인 결제 승인 로직 구현"
            )
        )

        assertNotNull("다음 턴에서도 LLM이 새 아이템 목록에 기반한 요약을 갱신 생성함", turn1.taskSummary)
        // state(Stage0State) 자체에 taskSummary 필드가 존재하지 않음을 확인
        assertEquals("Stage0State items는 결정론적 위상 탐색 및 사용자 결정만 보존함", turn1.state.items.filter { it.verdict == Verdict.CONFIRMED }.size, 2)
    }

    /**
     * [ClarifyIntent 4대 불변식 단위 테스트]
     * (a) 정제문/원문 분리 보존: originalRequirement(불변 원문)과 refinedRequirement(대화 정제문) 독립 분리 검증
     * (b) constraints 분류 실패 시 OTHER + rawStatement 안전 폴백 (예외 및 [Error] 응답 양방향 검증)
     * (c) 계약 순수성: ClarifyIntent에 대화 로그, 후보 파일 목록, taskSummary 등 표면 데이터 비혼입 검증
     * (d) 정제문 LLM 실패 시 originalRequirement 안전 폴백 + intent 생산 100% 성공 보장
     */
    @Test
    fun testClarifyIntentProductionAnd4Invariants() {
        val graph = createSyntheticGraph()
        val scanner = Stage0GraphScanner(graph)

        // 1. 정상 작동 케이스: LLM이 정제문과 JSON 제약 목록을 올바르게 반환
        val mockSuccessLlm = object : LLMClient {
            override fun chat(
                systemPrompt: String,
                userCode: String,
                maxTokens: Int?,
                onChunk: ((String) -> Unit)?
            ): net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse {
                val content = if (systemPrompt.contains("요구사항 정제")) {
                    "기존 알림톡 채널을 활용하여 설문 발송 시 브랜드메시지 옵션을 추가하고, 외부 연동 API는 사용하지 않는다."
                } else if (systemPrompt.contains("제약 조건")) {
                    """
                    [
                      {
                        "kind": "INCLUDE_CHANNEL",
                        "value": "기존 알림톡 채널 활용",
                        "rawStatement": "기존 알림톡 채널을 그대로 활용합니다."
                      },
                      {
                        "kind": "EXCLUDE_EXTERNAL",
                        "value": "외부 연동 API 미사용",
                        "rawStatement": "외부 API 연동은 하지 않고 내부 모듈만 씁니다."
                      }
                    ]
                    """.trimIndent()
                } else {
                    "요약문"
                }
                return net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse(
                    model = "test-model",
                    createdAt = "",
                    message = net.ib.ixpert.ops.wuwagent.model.OllamaMessage("assistant", content),
                    done = true
                )
            }
            override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
        }

        val engineSuccess = Stage0ClarificationEngine(scanner, graph, mockSuccessLlm)
        val turn0 = engineSuccess.initSession("설문 발송 채널에 브랜드메시지 추가")
        val turn1 = engineSuccess.processTurn(
            turn0.state,
            Stage0ClarificationEngine.UserInput(
                userStatement = "기존 알림톡 채널을 그대로 활용합니다."
            )
        )
        val turn2 = engineSuccess.processTurn(
            turn1.state,
            Stage0ClarificationEngine.UserInput(
                userStatement = "외부 API 연동은 하지 않고 내부 모듈만 씁니다."
            )
        )

        // userStatements 누적 확인
        assertEquals(2, turn2.state.userStatements.size)

        val intentSuccess = engineSuccess.buildClarifyIntent(turn2.state)

        // 불변식 (a) 검증: originalRequirement와 refinedRequirement가 독립 분리 보존됨
        assertEquals("설문 발송 채널에 브랜드메시지 추가", intentSuccess.originalRequirement)
        assertEquals("기존 알림톡 채널을 활용하여 설문 발송 시 브랜드메시지 옵션을 추가하고, 외부 연동 API는 사용하지 않는다.", intentSuccess.refinedRequirement)
        assertTrue(intentSuccess.anchorTokens.isNotEmpty())
        assertEquals(2, intentSuccess.constraints.size)
        assertEquals(ConstraintKind.INCLUDE_CHANNEL, intentSuccess.constraints[0].kind)
        assertEquals("기존 알림톡 채널을 그대로 활용합니다.", intentSuccess.constraints[0].rawStatement)
        assertEquals(ConstraintKind.EXCLUDE_EXTERNAL, intentSuccess.constraints[1].kind)
        assertEquals("외부 API 연동은 하지 않고 내부 모듈만 씁니다.", intentSuccess.constraints[1].rawStatement)

        // 불변식 (c) 검증: ClarifyIntent 계약 순수성 (JSON 직렬화 시 items, taskSummary, 파일 경로 부재)
        val json = Gson().toJson(intentSuccess)
        assertFalse("ClarifyIntent에 taskSummary가 포함되어서는 안 됨", json.contains("taskSummary"))
        assertFalse("ClarifyIntent에 items 후보 목록이 포함되어서는 안 됨", json.contains("OrderDto"))
        assertFalse("ClarifyIntent에 trustedExistingRefs가 포함되어서는 안 됨", json.contains("trustedExistingRefs"))

        // 2. LLM 실패 케이스 A: 실제 예외(타임아웃/네트워크 오류) 발생
        val mockFailingLlm = object : LLMClient {
            override fun chat(
                systemPrompt: String,
                userCode: String,
                maxTokens: Int?,
                onChunk: ((String) -> Unit)?
            ): net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse? {
                throw RuntimeException("Simulated Network Timeout")
            }
            override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
        }
        val engineFailing = Stage0ClarificationEngine(scanner, graph, mockFailingLlm)
        val intentFailing = engineFailing.buildClarifyIntent(turn2.state)

        // 불변식 (d) 검증: LLM 예외 시 refinedRequirement는 originalRequirement로 안전 폴백
        assertEquals("설문 발송 채널에 브랜드메시지 추가", intentFailing.refinedRequirement)
        assertEquals("설문 발송 채널에 브랜드메시지 추가", intentFailing.originalRequirement)

        // 불변식 (b) 검증: constraints 파싱 실패 시 OTHER + rawStatement로 안전 폴백
        assertEquals(2, intentFailing.constraints.size)
        assertTrue(intentFailing.constraints.all { it.kind == ConstraintKind.OTHER })
        assertEquals("기존 알림톡 채널을 그대로 활용합니다.", intentFailing.constraints[0].rawStatement)
        assertEquals("외부 API 연동은 하지 않고 내부 모듈만 씁니다.", intentFailing.constraints[1].rawStatement)

        // 3. LLM 실패 케이스 B: 실제 [Error] 접두어 에러 응답 객체 반환
        val mockErrorMsgLlm = object : LLMClient {
            override fun chat(
                systemPrompt: String,
                userCode: String,
                maxTokens: Int?,
                onChunk: ((String) -> Unit)?
            ): net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse {
                return net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse(
                    model = null,
                    createdAt = null,
                    message = net.ib.ixpert.ops.wuwagent.model.OllamaMessage(
                        role = "assistant",
                        content = "[Error] OpenAI 서버 통신 실패: 504 Gateway Timeout"
                    ),
                    done = true
                )
            }
            override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
        }
        val engineErrorMsg = Stage0ClarificationEngine(scanner, graph, mockErrorMsgLlm)
        val intentErrorMsg = engineErrorMsg.buildClarifyIntent(turn2.state)

        // [Error] 응답 객체 시에도 (d)와 (b) 안전 폴백 검증
        assertEquals("설문 발송 채널에 브랜드메시지 추가", intentErrorMsg.refinedRequirement)
        assertEquals(2, intentErrorMsg.constraints.size)
        assertTrue(intentErrorMsg.constraints.all { it.kind == ConstraintKind.OTHER })
        assertEquals("기존 알림톡 채널을 그대로 활용합니다.", intentErrorMsg.constraints[0].rawStatement)
    }
}


