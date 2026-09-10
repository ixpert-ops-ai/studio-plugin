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
    }
}
