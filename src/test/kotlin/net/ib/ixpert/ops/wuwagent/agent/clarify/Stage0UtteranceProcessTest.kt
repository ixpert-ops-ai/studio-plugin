package net.ib.ixpert.ops.wuwagent.agent.clarify

import net.ib.ixpert.ops.wuwagent.agent.clarify.model.*
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.OllamaMessage
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/**
 * 3-2단계: Stage0ClarificationEngine.processUtterance 6대 불변식 (a~f) 단위 및 상태 검증 테스트.
 */
class Stage0UtteranceProcessTest {

    private fun createSyntheticGraph(): ProjectGraph {
        val files = mapOf(
            "com/example/OrderDto.java" to FileNode(
                path = "com/example/OrderDto.java",
                packageName = "com.example",
                className = "OrderDto",
                fileType = SpringFileType.DTO,
                layer = ArchitectureLayer.PERSISTENCE,
                methods = listOf(
                    MethodSignature("getPay_type", "String", emptyList())
                )
            ),
            "com/example/SmsDto.java" to FileNode(
                path = "com/example/SmsDto.java",
                packageName = "com.example",
                className = "SmsDto",
                fileType = SpringFileType.DTO,
                layer = ArchitectureLayer.PERSISTENCE,
                methods = listOf(
                    MethodSignature("getSms_target", "String", emptyList())
                )
            )
        )

        val resourceNodes = listOf(
            ResourceNode(
                path = "webapp/views/order_write.jsp",
                type = ResourceType.VIEW,
                layer = "PRESENTATION",
                linkedTo = listOf("com/example/OrderController.java"),
                linkType = "url_binding",
                metadata = mapOf("input_field" to listOf("pay_type"))
            ),
            ResourceNode(
                path = "webapp/views/order_list.jsp",
                type = ResourceType.VIEW,
                layer = "PRESENTATION",
                linkedTo = listOf("com/example/OrderController.java"),
                linkType = "url_binding",
                metadata = mapOf("input_field" to listOf("pay_type"))
            ),
            ResourceNode(
                path = "webapp/sms/sms_send.jsp",
                type = ResourceType.VIEW,
                layer = "PRESENTATION",
                linkedTo = listOf("com/example/SmsController.java"),
                linkType = "url_binding",
                metadata = mapOf("input_field" to listOf("sms_token"))
            )
        )

        return ProjectGraph(
            generatedAt = Instant.now().toString(),
            projectRoot = "/test/root",
            frameworkType = FrameworkType.SPRING_MVC_MYBATIS,
            files = files,
            relationships = emptyList(),
            resourceNodes = resourceNodes,
            statistics = GraphStatistics()
        )
    }

    private fun createMockLlm(responseJson: String): LLMClient {
        return object : LLMClient {
            override fun chat(
                systemPrompt: String,
                userCode: String,
                maxTokens: Int?,
                onChunk: ((String) -> Unit)?
            ): OllamaChatResponse? {
                return OllamaChatResponse(
                    model = "mock-llm",
                    createdAt = "",
                    message = OllamaMessage("assistant", responseJson),
                    done = true
                )
            }

            override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
        }
    }

    /**
     * [불변식 a] EXCLUDE 상태 반영:
     * - "order_write.jsp 빼줘" 발화 시 해당 파일 item이 REJECTED로 전환됨
     * - buildClarifyIntent 호출 시 excludedFiles에 해당 파일 경로가 1급 결정으로 정확히 추출됨
     * - 되비추기 echoBackMessage가 정상 생성됨
     */
    @Test
    fun testInvariantA_ExcludeStatusReflectionAndIntentExtraction() {
        val graph = createSyntheticGraph()
        val scanner = Stage0GraphScanner(graph)

        val targetPath = "webapp/views/order_write.jsp"
        val ref = LinkHint.ExistingRef(targetPath, listOf("pay_type"))
        val targetId = RequirementItem.deriveId(ref)

        val llmResponse = """
            {
              "action": "EXCLUDE",
              "targetId": "$targetId",
              "message": "'$targetPath' 파일을 분석 대상에서 제외합니다."
            }
        """.trimIndent()
        val mockLlm = createMockLlm(llmResponse)
        val engine = Stage0ClarificationEngine(scanner, graph, mockLlm)

        val turn0 = engine.initSession("OrderDto 결제 방식 추가")
        val initialItem = turn0.state.items.find { (it.hint as? LinkHint.ExistingRef)?.filePath == targetPath }
        assertNotNull("초기 후보군에 order_write.jsp 컴포넌트가 존재해야 함", initialItem)
        assertEquals("초기 상태는 PENDING이어야 함", Verdict.PENDING, initialItem?.verdict)

        // 사용자 발화 실행
        val turn1 = engine.processUtterance(turn0.state, "order_write.jsp는 제외해주세요")

        // 1. REJECTED 상태 반영 확인
        val rejectedItem = turn1.state.items.find { it.id == initialItem?.id }
        assertNotNull(rejectedItem)
        assertEquals("발화 처리 후 REJECTED 상태로 전이되어야 함", Verdict.REJECTED, rejectedItem?.verdict)
        assertEquals("출처가 USER_CONFIRMED로 갱신되어야 함", HintSource.USER_CONFIRMED, rejectedItem?.source)
        assertEquals("거부 사유가 FILE_MISMATCH여야 함", RejectionReason.FILE_MISMATCH, rejectedItem?.rejectionReason)

        // 2. 되비추기(echo-back) 메시지 확인
        assertNotNull("되비추기 메시지가 존재해야 함", turn1.echoBackMessage)
        assertTrue("되비추기 메시지에 제외 안내가 포함되어야 함", turn1.echoBackMessage!!.contains("제외"))

        // 3. ClarifyIntent excludedFiles 추출 확인 (1급 결정 연동)
        val intent = engine.buildClarifyIntent(turn1.state)
        assertTrue("ClarifyIntent.excludedFiles에 제외된 파일 경로가 포함되어야 함", intent.excludedFiles.contains(targetPath))
        assertEquals(1, intent.excludedFiles.size)
    }

    /**
     * [불변식 b] INCLUDE_TOKEN 상태 반영 (최소형):
     * - "SMS 발송 기능도 추가해줘" 발화 시 seedSet에 토큰 누적
     * - rescanUnverified를 통해 sms_send.jsp 관련 후보가 병합됨
     * - 되비추기 echoBackMessage 확인
     */
    @Test
    fun testInvariantB_IncludeTokenStatusReflectionAndRescan() {
        val graph = createSyntheticGraph()
        val scanner = Stage0GraphScanner(graph)

        val llmResponse = """
            {
              "action": "INCLUDE_TOKEN",
              "tokenValue": "sms_send",
              "message": "'sms_send' 토큰을 검색 시드에 추가합니다."
            }
        """.trimIndent()
        val mockLlm = createMockLlm(llmResponse)
        val engine = Stage0ClarificationEngine(scanner, graph, mockLlm)

        // sms_send.jsp가 포함되지 않은 초기 요구사항으로 시작
        val turn0 = engine.initSession("OrderDto 결제 방식 추가")
        assertFalse("초기에는 sms_send.jsp가 없어야 함", turn0.state.items.any { it.statement.contains("sms_send.jsp") })

        // 사용자 토큰 추가 발화 실행
        val turn1 = engine.processUtterance(turn0.state, "SMS 알림 연동도 같이 고려해주세요")

        // 1. seedSet에 신규 토큰 누적 확인
        assertTrue("seedSet에 sms_send 토큰이 누적되어야 함", turn1.state.seedSet.any { it.value == "sms_send" })

        // 2. 재탐색을 통해 새 후보가 병합되었는지 확인
        assertTrue("재탐색 후 sms_send.jsp 컴포넌트가 후보 목록에 추가되어야 함", turn1.state.items.any { (it.hint as? LinkHint.ExistingRef)?.filePath == "webapp/sms/sms_send.jsp" })

        // 3. 되비추기 메시지 확인
        assertNotNull(turn1.echoBackMessage)
        assertTrue(turn1.echoBackMessage!!.contains("sms_send"))
    }

    /**
     * [불변식 c] ADD_CONSTRAINT 상태 반영:
     * - "외부 API는 사용하지 마" 발화 시 userStatements에 누적
     * - buildClarifyIntent 호출 시 IntentConstraint로 자동 수렴됨
     * - 되비추기 echoBackMessage 확인
     */
    @Test
    fun testInvariantC_AddConstraintStatusReflectionAndClassification() {
        val graph = createSyntheticGraph()
        val scanner = Stage0GraphScanner(graph)

        val utteranceJson = """
            {
              "action": "ADD_CONSTRAINT",
              "constraintKind": "EXCLUDE_EXTERNAL",
              "constraintValue": "외부 API 연동 배제",
              "message": "제약 조건이 추가되었습니다: 외부 API 연동 배제"
            }
        """.trimIndent()

        val constraintsJson = """
            [
              {
                "kind": "EXCLUDE_EXTERNAL",
                "value": "외부 API 연동 배제",
                "rawStatement": "외부 API는 일절 사용하지 마세요",
                "evidence": "외부 API"
              }
            ]
        """.trimIndent()

        val mockLlm = object : LLMClient {
            override fun chat(
                systemPrompt: String,
                userCode: String,
                maxTokens: Int?,
                onChunk: ((String) -> Unit)?
            ): OllamaChatResponse? {
                val content = if (systemPrompt.contains("발화 번역")) utteranceJson else constraintsJson
                return OllamaChatResponse(
                    model = "mock-llm",
                    createdAt = "",
                    message = OllamaMessage("assistant", content),
                    done = true
                )
            }
            override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
        }

        val engine = Stage0ClarificationEngine(scanner, graph, mockLlm)
        val turn0 = engine.initSession("주문 기능")

        val turn1 = engine.processUtterance(turn0.state, "외부 API는 일절 사용하지 마세요")

        // 1. userStatements 누적 확인
        assertTrue("userStatements에 발화가 누적되어야 함", turn1.state.userStatements.contains("외부 API는 일절 사용하지 마세요"))

        // 2. 되비추기 메시지 확인
        assertNotNull(turn1.echoBackMessage)
        assertTrue(turn1.echoBackMessage!!.contains("제약 조건"))

        // 3. buildClarifyIntent에서 IntentConstraint 수렴 확인
        val intent = engine.buildClarifyIntent(turn1.state)
        assertEquals(1, intent.constraints.size)
        assertEquals(ConstraintKind.EXCLUDE_EXTERNAL, intent.constraints.first().kind)
        assertEquals("외부 API 연동 배제", intent.constraints.first().value)
    }

    /**
     * [불변식 d] COMPLETE 상태 반영:
     * - "이제 분석해줘" 발화 시 isReadyForStage1 = true 및 isExhausted = true 전이
     * - 되비추기 echoBackMessage 확인
     */
    @Test
    fun testInvariantD_CompleteStatusReflection() {
        val graph = createSyntheticGraph()
        val scanner = Stage0GraphScanner(graph)

        val llmResponse = """
            {
              "action": "COMPLETE",
              "message": "요구사항 구체화를 완료하고 분석을 시작합니다."
            }
        """.trimIndent()
        val mockLlm = createMockLlm(llmResponse)
        val engine = Stage0ClarificationEngine(scanner, graph, mockLlm)

        val turn0 = engine.initSession("주문 기능")
        assertFalse("초기에는 isReadyForStage1이 false여야 함", turn0.isReadyForStage1)

        val turn1 = engine.processUtterance(turn0.state, "이대로 분석 시작해줘")

        assertTrue("COMPLETE 발화 시 isReadyForStage1이 true여야 함", turn1.isReadyForStage1)
        assertTrue("COMPLETE 발화 시 isExhausted가 true여야 함", turn1.isExhausted)
        assertNotNull(turn1.echoBackMessage)
        assertTrue(turn1.echoBackMessage!!.contains("완료"))
    }

    /**
     * [불변식 e] NOT_IN_CANDIDATES & UNKNOWN No-Op 불변성:
     * - 목록 밖 대상 지목 또는 알 수 없는 모호한 발화 시 items 목록이 단 1건도 훼손되지 않음 (No-Op)
     * - 안내 되비추기 echoBackMessage 정상 반환
     */
    @Test
    fun testInvariantE_NotInCandidatesAndUnknownNoOpInvariance() {
        val graph = createSyntheticGraph()
        val scanner = Stage0GraphScanner(graph)

        val llmResponse1 = """
            {
              "action": "EXCLUDE",
              "targetId": "req_non_existent_12345",
              "notInCandidates": true,
              "message": "지정하신 파일은 현재 후보 목록에 존재하지 않습니다."
            }
        """.trimIndent()
        val mockLlm1 = createMockLlm(llmResponse1)
        val engine1 = Stage0ClarificationEngine(scanner, graph, mockLlm1)

        val turn0 = engine1.initSession("주문 기능")
        val initialItemsCount = turn0.state.items.size
        val initialItemsSnapshot = turn0.state.items.map { it.copy() }

        // 1. 목록 밖 대상 지목 발화
        val turn1 = engine1.processUtterance(turn0.state, "목록에 없는 test_mapper.xml 제외해줘")

        // No-Op 검증: items 크기 및 내용 100% 동일
        assertEquals("후보군 외 지목 시 items 개수가 변하지 않아야 함 (No-Op)", initialItemsCount, turn1.state.items.size)
        assertEquals("후보군 외 지목 시 기존 items의 verdict가 전혀 손상되지 않아야 함", initialItemsSnapshot, turn1.state.items)
        assertNotNull(turn1.echoBackMessage)
        assertTrue(turn1.echoBackMessage!!.contains("존재하지 않습니다"))

        // 2. 단순 모호 발화 (UNKNOWN)
        val llmResponse2 = """
            {
              "action": "UNKNOWN",
              "message": "말씀하신 내용을 정확히 이해하지 못했습니다. 다시 설명해 주세요."
            }
        """.trimIndent()
        val mockLlm2 = createMockLlm(llmResponse2)
        val engine2 = Stage0ClarificationEngine(scanner, graph, mockLlm2)

        val turn2 = engine2.processUtterance(turn1.state, "음... 글쎄요...")
        assertEquals("모호 발화 시 items 개수가 변하지 않아야 함 (No-Op)", initialItemsCount, turn2.state.items.size)
        assertEquals("모호 발화 시 기존 items의 verdict가 전혀 손상되지 않아야 함", initialItemsSnapshot, turn2.state.items)
        assertNotNull(turn2.echoBackMessage)
        assertTrue(turn2.echoBackMessage!!.contains("이해하지 못했습니다"))
    }

    /**
     * [불변식 f] userStatements 추적 보존:
     * - 여러 턴에 걸쳐 전달된 모든 사용자 발화가 순서대로 빠짐없이 state.userStatements에 누적됨
     */
    @Test
    fun testInvariantF_UserStatementsSequentialTracking() {
        val graph = createSyntheticGraph()
        val scanner = Stage0GraphScanner(graph)

        val mockLlm = createMockLlm("""{"action": "UNKNOWN", "message": "확인했습니다."}""")
        val engine = Stage0ClarificationEngine(scanner, graph, mockLlm)

        val turn0 = engine.initSession("주문 기능")
        assertEquals(0, turn0.state.userStatements.size)

        val turn1 = engine.processUtterance(turn0.state, "첫 번째 발화입니다.")
        val turn2 = engine.processUtterance(turn1.state, "두 번째 발화입니다.")
        val turn3 = engine.processUtterance(turn2.state, "세 번째 발화입니다.")

        assertEquals(3, turn3.state.userStatements.size)
        assertEquals("첫 번째 발화입니다.", turn3.state.userStatements[0])
        assertEquals("두 번째 발화입니다.", turn3.state.userStatements[1])
        assertEquals("세 번째 발화입니다.", turn3.state.userStatements[2])
    }

    /**
     * [Phase 3-3 핵심 관문]
     * 1. 실환경 3종 발화 (가: 정확 매핑, 나: 닫힌 선택 강제 No-Op, 다: 모호 발화 No-Op) 시나리오 E2E 실증
     * 2. 완료 후 인텐트 계약 저장 시 echoBackMessage가 디스크(intent.json / clarification-contract.json)에 단 1글자도 침투/저장되지 않음을 디스크 grep 실측
     */
    @Test
    fun testPhase33_ThreeLiveUtteranceTypesAndContractNonPersistenceDiskGrep() {
        val graph = createSyntheticGraph()
        val scanner = Stage0GraphScanner(graph)

        val targetPath = "webapp/views/order_write.jsp"
        val ref = LinkHint.ExistingRef(targetPath, listOf("pay_type"))
        val targetId = RequirementItem.deriveId(ref)

        val marker1 = "ECHO_BACK_MARKER_EXCLUDE_ORDER_WRITE"
        val marker2 = "ECHO_BACK_MARKER_NOT_IN_CANDIDATES_PAYMENT"
        val marker3 = "ECHO_BACK_MARKER_UNKNOWN_AMBIGUOUS"
        val marker4 = "ECHO_BACK_MARKER_COMPLETE_START_ANALYZE"

        val mockLlm = object : LLMClient {
            override fun chat(
                systemPrompt: String,
                userCode: String,
                maxTokens: Int?,
                onChunk: ((String) -> Unit)?
            ): OllamaChatResponse {
                val responseJson = when {
                    systemPrompt.contains("5가지 액션") || systemPrompt.contains("발화 번역") -> {
                        when {
                            userCode.contains("order_write.jsp 빼줘") ->
                                """{"action": "EXCLUDE", "targetId": "$targetId", "message": "$marker1: order_write.jsp를 제외했습니다."}"""
                            userCode.contains("결제 모듈 빼줘") ->
                                """{"action": "EXCLUDE", "targetId": "req_payment_fake_id", "notInCandidates": true, "message": "$marker2: 결제 모듈은 후보 목록에 없습니다."}"""
                            userCode.contains("음... 글쎄요") ->
                                """{"action": "UNKNOWN", "message": "$marker3: 무슨 말씀인지 이해하지 못했습니다."}"""
                            userCode.contains("분석 시작해줘") ->
                                """{"action": "COMPLETE", "message": "$marker4: 분석을 시작합니다."}"""
                            else ->
                                """{"action": "UNKNOWN", "message": "알 수 없는 발화입니다."}"""
                        }
                    }
                    systemPrompt.contains("정제 전문가") ->
                        "OrderDto 결제 방식 추가 (order_write.jsp 제외)"
                    systemPrompt.contains("ConstraintKind") || systemPrompt.contains("제약 조건") ->
                        """[{"kind": "SCOPE_LIMIT", "value": "order_write.jsp 제외", "rawStatement": "order_write.jsp 빼줘"}]"""
                    systemPrompt.contains("앵커 토큰") || systemPrompt.contains("토큰") ->
                        """["OrderDto", "pay_type"]"""
                    systemPrompt.contains("도메인") || systemPrompt.contains("Domain") ->
                        """{"domainPackage": "order"}"""
                    systemPrompt.contains("요약") || systemPrompt.contains("Summary") ->
                        """{"summary": "작업 요약"}"""
                    else ->
                        """{}"""
                }
                return OllamaChatResponse(
                    model = "mock-llm",
                    createdAt = "",
                    message = OllamaMessage("assistant", responseJson),
                    done = true
                )
            }
            override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
        }

        val engine = Stage0ClarificationEngine(scanner, graph, mockLlm)
        val turn0 = engine.initSession("OrderDto 결제 방식 추가")
        val initialItemsCount = turn0.state.items.size

        // (가) 정확 매핑: "order_write.jsp 빼줘"
        val turn1 = engine.processUtterance(turn0.state, "order_write.jsp 빼줘")
        val rejectedItem = turn1.state.items.find { it.id == targetId }
        assertNotNull(rejectedItem)
        assertEquals(Verdict.REJECTED, rejectedItem?.verdict)
        assertTrue(turn1.echoBackMessage!!.contains(marker1))

        // (나) 닫힌 선택 강제: "결제 모듈 빼줘" (목록 외 대상) -> No-Op 0건 훼손
        val turn2 = engine.processUtterance(turn1.state, "결제 모듈 빼줘")
        assertEquals("후보 외 지목 시 아이템 개수 불변 (No-Op)", initialItemsCount, turn2.state.items.size)
        assertEquals(Verdict.REJECTED, turn2.state.items.find { it.id == targetId }?.verdict)
        assertTrue(turn2.echoBackMessage!!.contains(marker2))

        // (다) 모호 발화: "음... 글쎄요" -> No-Op 0건 훼손
        val turn3 = engine.processUtterance(turn2.state, "음... 글쎄요")
        assertEquals("모호 발화 시 아이템 개수 불변 (No-Op)", initialItemsCount, turn3.state.items.size)
        assertTrue(turn3.echoBackMessage!!.contains(marker3))

        // 대화 완결: "분석 시작해줘" -> COMPLETE (규칙 기반 빠른 확정)
        val turn4 = engine.processUtterance(turn3.state, "분석 시작해줘")
        assertTrue("COMPLETE 발화 시 isReadyForStage1 == true", turn4.isReadyForStage1)
        assertNotNull(turn4.echoBackMessage)
        assertTrue(turn4.echoBackMessage!!.contains("완료"))

        // 인텐트 및 계약 생성
        val clarifyIntent = engine.buildClarifyIntent(turn4.state)
        assertTrue("ClarifyIntent.excludedFiles에 제외 대상 포함", clarifyIntent.excludedFiles.contains(targetPath))

        // 디스크 저장 (임시 디렉토리)
        val tempDir = java.nio.file.Files.createTempDirectory("clarify_purity_test").toFile()
        try {
            val savedIntentFile = ClarifyIntentStore.saveIntent(tempDir, clarifyIntent)
            val contract = engine.transitionToStage1(turn4.state)
            val savedContractFile = ClarificationContractStore.saveContract(tempDir, contract)

            assertTrue("intent.json 파일이 존재해야 함", savedIntentFile.exists())
            assertTrue("clarification-contract.json 파일이 존재해야 함", savedContractFile.exists())

            val intentContent = savedIntentFile.readText()
            val contractContent = savedContractFile.readText()

            // 1. 제외 파일 정상 영속화 검증
            assertTrue("intent.json에 excludedFiles에 order_write.jsp가 기록되어야 함", intentContent.contains("order_write.jsp"))

            // 2. 계약 비저장 불변성 디스크 grep 실측: echoBackMessage 및 모든 마커/문구가 0건(단 1글자도 침투하지 않음)
            assertFalse("intent.json에 echoBackMessage 필드가 없어야 함", intentContent.contains("echoBackMessage"))
            assertFalse("intent.json에 마커 1이 침투하지 않아야 함", intentContent.contains(marker1))
            assertFalse("intent.json에 마커 2가 침투하지 않아야 함", intentContent.contains(marker2))
            assertFalse("intent.json에 마커 3이 침투하지 않아야 함", intentContent.contains(marker3))
            assertFalse("intent.json에 완료 안내 문구가 침투하지 않아야 함", intentContent.contains("요구사항 구체화를 완료하고"))

            assertFalse("clarification-contract.json에 echoBackMessage 필드가 없어야 함", contractContent.contains("echoBackMessage"))
            assertFalse("clarification-contract.json에 마커 1이 침투하지 않아야 함", contractContent.contains(marker1))
            assertFalse("clarification-contract.json에 마커 2가 침투하지 않아야 함", contractContent.contains(marker2))
            assertFalse("clarification-contract.json에 마커 3이 침투하지 않아야 함", contractContent.contains(marker3))
            assertFalse("clarification-contract.json에 완료 안내 문구가 침투하지 않아야 함", contractContent.contains("요구사항 구체화를 완료하고"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * [UnresolvedItems] 질문 선택 우선순위에 따른 억제 검증:
     * 배제 질문이 선택되었을 때 미확인 식별자(FooService)의 lastQuestion 및 lastAskedTurn은 null이어야 함
     */
    @Test
    fun testQuestionSelectionSuppression_ExclusionQuestionChosen_UnmatchedItemHasNullLastQuestion() {
        val graph = createSyntheticGraph()
        val scanner = Stage0GraphScanner(graph, minSpecificityScore = 1.0, proposalBudget = 10)
        val mockLlmJson = """
            [
              {
                "kind": "EXCLUDE_COMPONENT",
                "value": "sms_send.jsp",
                "rawStatement": "sms_send.jsp는 건드리지 말고 신규 FooService를 추가해줘",
                "evidence": "sms_send.jsp는 건드리지 말고"
              }
            ]
        """.trimIndent()
        val mockLlm = object : LLMClient {
            override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): OllamaChatResponse {
                return OllamaChatResponse(
                    model = "mock",
                    createdAt = "",
                    message = OllamaMessage("assistant", mockLlmJson),
                    done = true
                )
            }
            override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
        }
        val engine = Stage0ClarificationEngine(scanner, graph, llmClient = mockLlm)

        val turn0 = engine.initSession("주문 조회")

        // Turn 1: 배제 제약 + 미확인 식별자가 동시에 포함된 발화
        // "sms_send.jsp는 건드리지 말고 신규 FooService를 추가해줘"
        val turn1 = engine.processTurn(
            turn0.state,
            Stage0ClarificationEngine.UserInput(
                userStatement = "sms_send.jsp는 건드리지 말고 신규 FooService를 추가해줘"
            )
        )

        // 배제 질문(sms_send.jsp 관련)이 openQuestion으로 우선 채택됨
        assertNotNull(turn1.openQuestion)
        assertTrue("배제 질문이 선택되어야 함", turn1.openQuestion!!.contains("sms_send.jsp") || turn1.openQuestion!!.contains("제외"))

        val intent = engine.buildClarifyIntent(turn1.state)
        val fooItem = intent.unresolvedItems.find { it.identifier == "FooService" }
        assertNotNull("FooService가 unresolvedItems에 존재해야 함", fooItem)
        assertNull("배제 질문이 선택되었으므로 FooService의 lastQuestion은 null이어야 함", fooItem?.lastQuestion)
        assertNull("배제 질문이 선택되었으므로 FooService의 lastAskedTurn은 null이어야 함", fooItem?.lastAskedTurn)
    }

    /**
     * [UnresolvedItems] 발화 턴 번호 추적 및 불변성 검증:
     * Turn 1에서 발화된 FooService는 utteredTurn == 1,
     * Turn 2에서 처음 발화된 BarService는 utteredTurn == 2,
     * Turn 2에서 재발화된 FooService는 utteredTurn == 1 유지
     */
    @Test
    fun testTurnNumbers_Turn2FirstUtteredItemHasTurn2_ReUtteredFirstTurnItemHasTurn1() {
        val graph = createSyntheticGraph()
        val scanner = Stage0GraphScanner(graph, minSpecificityScore = 1.0, proposalBudget = 10)
        val engine = Stage0ClarificationEngine(scanner, graph, llmClient = null)

        // Turn 0: FooService 발화 (턴 1에 해당)
        val turn0 = engine.initSession("FooService 신규 개발")
        val intent0 = engine.buildClarifyIntent(turn0.state)
        val foo0 = intent0.unresolvedItems.find { it.identifier == "FooService" }
        assertNotNull(foo0)
        assertEquals(1, foo0?.utteredTurn)

        // Turn 1 (대화 2번째 턴): BarService 신규 발화 + FooService 재언급
        val turn1 = engine.processTurn(
            turn0.state,
            Stage0ClarificationEngine.UserInput(
                userStatement = "BarService도 추가하고 FooService도 계속 필요해"
            )
        )

        val intent1 = engine.buildClarifyIntent(turn1.state)
        val foo1 = intent1.unresolvedItems.find { it.identifier == "FooService" }
        val bar1 = intent1.unresolvedItems.find { it.identifier == "BarService" }

        assertNotNull(foo1)
        assertNotNull(bar1)
        assertEquals("재언급된 FooService의 최초 발화 턴(1)은 보존되어야 함", 1, foo1?.utteredTurn)
        assertEquals("2번째 턴에서 최초 발화된 BarService의 utteredTurn은 2여야 함", 2, bar1?.utteredTurn)
    }

    private fun createSyntheticPartitionGraph(): ProjectGraph {
        val files = mapOf(
            "com/example/OrderDto.java" to FileNode(
                path = "com/example/OrderDto.java",
                packageName = "com.example",
                className = "OrderDto",
                fileType = SpringFileType.DTO,
                layer = ArchitectureLayer.PERSISTENCE,
                methods = listOf(MethodSignature("getPay_type", "String", emptyList()))
            ),
            "com/example/SurveyServiceImpl.java" to FileNode(
                path = "com/example/SurveyServiceImpl.java",
                packageName = "com.example",
                className = "SurveyServiceImpl",
                localName = "SurveyServiceImpl",
                koreanComments = listOf("SurveyServiceImpl 설문 서비스 구현체"),
                fileType = SpringFileType.SERVICE,
                layer = ArchitectureLayer.BUSINESS,
                methods = listOf(MethodSignature("executeSurvey", "void", emptyList()))
            ),
            "com/example/SurveyService.java" to FileNode(
                path = "com/example/SurveyService.java",
                packageName = "com.example",
                className = "SurveyService",
                localName = "SurveyServiceImpl",
                koreanComments = listOf("SurveyServiceImpl 설문 서비스 인터페이스"),
                fileType = SpringFileType.INTERFACE,
                layer = ArchitectureLayer.BUSINESS,
                methods = listOf(MethodSignature("executeSurvey", "void", emptyList()))
            )
        )
        val rels = listOf(
            Relationship(
                source = "com/example/SurveyServiceImpl.java",
                target = "com/example/SurveyService.java",
                type = RelationshipType.IMPLEMENTS
            )
        )
        return ProjectGraph(
            generatedAt = Instant.now().toString(),
            projectRoot = "/test/root",
            frameworkType = FrameworkType.SPRING_MVC_MYBATIS,
            files = files,
            relationships = rels,
            resourceNodes = emptyList(),
            statistics = GraphStatistics()
        )
    }

    /**
     * [UnresolvedItems] 4대 조건 무손실 분할 불변식(Lossless Partitioning) 검증:
     * (1) LLM-off
     * (2) LLM-on proposed exclusion
     * (3) LLM-on "yes" (affirmative)
     * (4) LLM-on "no" (negative)
     */
    @Test
    fun testLosslessPartitioningInvariant_AcrossAllFourConditions() {
        val graph = createSyntheticPartitionGraph()
        val scanner = Stage0GraphScanner(graph, minSpecificityScore = 1.0, proposalBudget = 10)

        // 조건 (1) LLM-off
        val engineOff = Stage0ClarificationEngine(scanner, graph, llmClient = null)
        val turn0Off = engineOff.initSession("신규 서비스 FooService 개발")
        val intent0Off = engineOff.buildClarifyIntent(turn0Off.state)
        assertLosslessPartitioning(
            intent0Off,
            turn0Off.state,
            graph,
            listOf("신규 서비스 FooService 개발"),
            "Condition 1 LLM-off"
        )

        // Mock LLM for exclusion
        val mockLlmJson = """
            [
              {
                "kind": "EXCLUDE_COMPONENT",
                "value": "SurveyServiceImpl 수정 제외",
                "rawStatement": "SurveyServiceImpl은 건드리지 말고 신규 FooService를 추가해줘",
                "evidence": "SurveyServiceImpl은 건드리지 말고"
              }
            ]
        """.trimIndent()
        val mockLlm = object : LLMClient {
            override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): OllamaChatResponse {
                return OllamaChatResponse(
                    model = "mock",
                    createdAt = "",
                    message = OllamaMessage("assistant", mockLlmJson),
                    done = true
                )
            }
            override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
        }

        // 조건 (2) LLM-on proposed exclusion
        val engineOn = Stage0ClarificationEngine(scanner, graph, mockLlm)
        val turn0On = engineOn.initSession("주문 기능 개발")
        val turn1Proposed = engineOn.processTurn(
            turn0On.state,
            Stage0ClarificationEngine.UserInput(userStatement = "SurveyServiceImpl은 건드리지 말고 신규 FooService를 추가해줘")
        )

        // 조건 2 가드: 배제 후보가 실제로 1건 이상 생성되었음을 먼저 단언 (비어있는 테스트 방지)
        val proposedExclusions = turn1Proposed.state.items.filter { it.source == HintSource.PROPOSED_EXCLUSION }
        assertTrue("조건 2: 배제 제안 후보가 1건 이상 생성되어야 함", proposedExclusions.isNotEmpty())
        assertEquals("배제 후보는 SurveyServiceImpl과 인접 SurveyService 2건이어야 함", 2, proposedExclusions.size)

        val intentProposed = engineOn.buildClarifyIntent(turn1Proposed.state)
        assertLosslessPartitioning(
            intentProposed,
            turn1Proposed.state,
            graph,
            listOf("주문 기능 개발", "SurveyServiceImpl은 건드리지 말고 신규 FooService를 추가해줘"),
            "Condition 2 Proposed Exclusion"
        )

        // 조건 (3) LLM-on "yes" (배제 확정)
        val turn2Yes = engineOn.processTurn(
            turn1Proposed.state,
            Stage0ClarificationEngine.UserInput(userStatement = "응")
        )
        val intentYes = engineOn.buildClarifyIntent(turn2Yes.state)
        assertTrue("조건 3: excludedFiles에 SurveyServiceImpl.java가 포함되어야 함", intentYes.excludedFiles.any { it.contains("SurveyServiceImpl.java") })
        assertTrue("조건 3: excludedFiles에 인접 SurveyService.java가 포함되어야 함", intentYes.excludedFiles.any { it.contains("SurveyService.java") })
        assertLosslessPartitioning(
            intentYes,
            turn2Yes.state,
            graph,
            listOf("주문 기능 개발", "SurveyServiceImpl은 건드리지 말고 신규 FooService를 추가해줘", "응"),
            "Condition 3 Affirmative Yes"
        )
        // 조건 3 인접 후보 SurveyService 튜플 출력 (고신뢰도 일괄 제외에 포함)
        val adjYesExcluded = intentYes.excludedFiles.any { it.contains("SurveyService.java") }
        val adjYesUnresolved = intentYes.unresolvedItems.any { it.identifier == "SurveyService" }
        val adjYesConfirmed = turn2Yes.state.items.any { it.verdict == Verdict.CONFIRMED && (it.hint as? LinkHint.ExistingRef)?.filePath?.contains("SurveyService.java") == true }
        val adjYesSum = (if (adjYesConfirmed) 1 else 0) + (if (adjYesExcluded) 1 else 0) + (if (adjYesUnresolved) 1 else 0)
        println("[Condition 3 Affirmative Yes] adjacent token=SurveyService -> (anchor=false, confirmed=$adjYesConfirmed, excluded=$adjYesExcluded, unresolved=$adjYesUnresolved, sum=$adjYesSum)")

        // 조건 (4) LLM-on "no" (배제 취소 및 발화 식별자 복원)
        val turn2No = engineOn.processTurn(
            turn1Proposed.state,
            Stage0ClarificationEngine.UserInput(userStatement = "아니요")
        )
        val intentNo = engineOn.buildClarifyIntent(turn2No.state)

        // 1) 3위치 분할 합계 불변식(sum == 1)을 최우선 평가하여 유실/초과 즉시 감지
        assertLosslessPartitioning(
            intentNo,
            turn2No.state,
            graph,
            listOf("주문 기능 개발", "SurveyServiceImpl은 건드리지 말고 신규 FooService를 추가해줘", "아니요"),
            "Condition 4 Negative No"
        )

        // 2) 조건 4 개별 항목 및 출처 정밀 단언
        assertEquals("조건 4: excludedFiles는 0건이어야 함", 0, intentNo.excludedFiles.size)
        val restoredItem = turn2No.state.items.find { (it.hint as? LinkHint.ExistingRef)?.filePath?.contains("SurveyServiceImpl.java") == true }
        assertNotNull("조건 4: 사용자 명시 SurveyServiceImpl은 USER_UTTERED로 복원되어야 함", restoredItem)
        assertEquals(HintSource.USER_UTTERED, restoredItem?.source)
        assertEquals(Verdict.PENDING, restoredItem?.verdict)
        assertFalse("조건 4: 미언급 인접 SurveyService는 PROPOSED_EXCLUSION에 남아있지 않아야 함", turn2No.state.items.any { it.source == HintSource.PROPOSED_EXCLUSION })

        // 3) 조건 4 미언급 인접 후보 SurveyService 단언: 세 위치 어디에도 없어야 함 (sum == 0)
        val adjNoExcluded = intentNo.excludedFiles.any { it.contains("SurveyService.java") }
        val adjNoUnresolved = intentNo.unresolvedItems.any { it.identifier == "SurveyService" }
        val adjNoConfirmed = turn2No.state.items.any { it.verdict == Verdict.CONFIRMED && (it.hint as? LinkHint.ExistingRef)?.filePath?.contains("SurveyService.java") == true }
        val adjNoSum = (if (adjNoConfirmed) 1 else 0) + (if (adjNoExcluded) 1 else 0) + (if (adjNoUnresolved) 1 else 0)
        println("[Condition 4 Negative No] adjacent token=SurveyService -> (anchor=false, confirmed=$adjNoConfirmed, excluded=$adjNoExcluded, unresolved=$adjNoUnresolved, sum=$adjNoSum)")
        assertFalse("조건 4: 인접 SurveyService는 excludedFiles에 없어야 함", adjNoExcluded)
        assertFalse("조건 4: 인접 SurveyService는 unresolvedItems에 없어야 함", adjNoUnresolved)
        assertFalse("조건 4: 인접 SurveyService는 confirmedItems에 없어야 함", adjNoConfirmed)
        assertEquals("조건 4: 미언급 인접 SurveyService의 세 위치 합계는 0이어야 함", 0, adjNoSum)
    }

    private fun assertLosslessPartitioning(
        intent: ClarifyIntent,
        state: Stage0State,
        graph: ProjectGraph,
        allStatements: List<String>,
        conditionLabel: String
    ) {
        val utteredIdentifiers = IdentifierRetentionChecker.extractIdentifiers(allStatements)
            .filter { id -> id != "OrderDto" } // 발화에 포함된 식별자 대상

        val confirmedBaseNames = state.items
            .filter { it.verdict == Verdict.CONFIRMED }
            .mapNotNull { (it.hint as? LinkHint.ExistingRef)?.filePath?.substringAfterLast("/")?.substringBeforeLast(".") }
            .toSet()

        val excludedBaseNames = intent.excludedFiles
            .map { it.substringAfterLast("/").substringBeforeLast(".") }
            .toSet()

        val unresolvedIdentifiers = intent.unresolvedItems
            .map { it.identifier }
            .toSet()

        for (token in utteredIdentifiers) {
            val isAnchor = intent.anchorTokens.any { it.equals(token, ignoreCase = true) }
            val isConfirmed = confirmedBaseNames.contains(token)
            val isExcluded = excludedBaseNames.contains(token)
            val isUnresolved = unresolvedIdentifiers.contains(token)

            val confirmedCount = if (isConfirmed) 1 else 0
            val excludedCount = if (isExcluded) 1 else 0
            val unresolvedCount = if (isUnresolved) 1 else 0
            val sum = confirmedCount + excludedCount + unresolvedCount

            println("[$conditionLabel] token=$token -> (anchor=$isAnchor, confirmed=$isConfirmed, excluded=$isExcluded, unresolved=$isUnresolved, sum=$sum)")

            assertEquals(
                "[$conditionLabel] 식별자 '$token'의 3위치 합계는 정확히 1이어야 함 (confirmed=$confirmedCount, excluded=$excludedCount, unresolved=$unresolvedCount)",
                1,
                sum
            )
        }
    }

    /**
     * [Fail-Fast] USER_UTTERED PENDING 항목에 utteredIdentifier가 null인 경우
     * 문자열 statement 폴백으로 식별자를 유추하지 않고 즉시 IllegalStateException(fail-fast)을 던지는지 검증
     */
    @Test
    fun testBuildClarifyIntent_UserUtteredPendingItemWithoutUtteredIdentifier_ThrowsIllegalStateException() {
        val graph = createSyntheticPartitionGraph()
        val scanner = Stage0GraphScanner(graph, minSpecificityScore = 1.0, proposalBudget = 10)
        val engine = Stage0ClarificationEngine(scanner, graph, llmClient = null)

        val invalidItem = RequirementItem(
            id = "invalid_user_item",
            statement = "미확인 부존재 식별자: SomeService",
            source = HintSource.USER_UTTERED,
            hint = LinkHint.NewCreation,
            anchorRationale = "사용자 발화 미확인 식별자",
            verdict = Verdict.PENDING,
            utteredIdentifier = null // 의도적으로 null 누락 주입
        )
        val state = Stage0State(
            originalRequirement = "신규 기능 개발",
            items = listOf(invalidItem),
            seedSet = emptySet(),
            userStatements = listOf("신규 기능 개발")
        )

        var exceptionThrown = false
        try {
            engine.buildClarifyIntent(state)
        } catch (e: IllegalStateException) {
            exceptionThrown = true
            println("Expected exception caught: ${e.message}")
        }
        assertTrue("USER_UTTERED PENDING 항목에 utteredIdentifier가 null이면 fail-fast로 IllegalStateException을 던져야 함", exceptionThrown)
    }
}