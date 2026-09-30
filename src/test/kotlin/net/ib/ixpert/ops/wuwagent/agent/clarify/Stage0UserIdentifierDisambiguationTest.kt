package net.ib.ixpert.ops.wuwagent.agent.clarify

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.*
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.OllamaMessage
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * [B-12] 사용자가 이미 명시한 식별자의 PENDING 재질의 방지 및 3분기 자동 확정/질의 검증 테스트.
 */
class Stage0UserIdentifierDisambiguationTest {

    private fun loadSurveyAdminGraph(): ProjectGraph? {
        val path = File("C:/Workspace/HC_card_survey_admin/survey_admin/.meta/project-graph.json")
        if (!path.exists()) return null
        return Gson().fromJson(path.readText(), ProjectGraph::class.java)
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

    private fun createSyntheticGraph(): ProjectGraph {
        val files = mapOf(
            "com/example/SurveyServiceImpl.java" to FileNode(
                path = "com/example/SurveyServiceImpl.java",
                packageName = "com.example",
                className = "SurveyServiceImpl",
                fileType = SpringFileType.SERVICE,
                layer = ArchitectureLayer.BUSINESS,
                methods = listOf(MethodSignature("executeSurvey", "void", emptyList()))
            ),
            "com/example/SurveyService.java" to FileNode(
                path = "com/example/SurveyService.java",
                packageName = "com.example",
                className = "SurveyService",
                fileType = SpringFileType.INTERFACE,
                layer = ArchitectureLayer.BUSINESS,
                methods = listOf(MethodSignature("executeSurvey", "void", emptyList()))
            ),
            "com/example/OrderController.java" to FileNode(
                path = "com/example/OrderController.java",
                packageName = "com.example",
                className = "OrderController",
                fileType = SpringFileType.CONTROLLER,
                layer = ArchitectureLayer.PRESENTATION,
                methods = listOf(MethodSignature("handleOrder", "String", emptyList()))
            ),
            "com/example/AddressDao.java" to FileNode(
                path = "com/example/AddressDao.java",
                packageName = "com.example",
                className = "AddressDao",
                fileType = SpringFileType.REPOSITORY,
                layer = ArchitectureLayer.PERSISTENCE,
                methods = listOf(MethodSignature("selectAddress", "void", emptyList()))
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

    @Test
    fun testBranch1_UtteredAndGraphExists_AutoConfirmedWithoutPendingReQuery() {
        val graph = createSyntheticGraph()
        val scanner = Stage0GraphScanner(graph, minSpecificityScore = 1.0, proposalBudget = 10)
        val mockLlmJson = """
            [
              {
                "kind": "SCOPE_LIMIT",
                "value": "SurveyServiceImpl 수정",
                "rawStatement": "SurveyServiceImpl만 수정할 거야",
                "evidence": "SurveyServiceImpl만 수정할 거야"
              }
            ]
        """.trimIndent()
        val mockLlm = createMockLlm(mockLlmJson)
        val engine = Stage0ClarificationEngine(scanner, graph, mockLlm)

        val turn0 = engine.initSession("SurveyServiceImpl만 수정할 거야")

        // 1. SurveyServiceImpl는 즉시 CONFIRMED여야 함
        val confirmedItems = turn0.state.items.filter { it.verdict == Verdict.CONFIRMED }
        val confirmedPaths = confirmedItems.mapNotNull { (it.hint as? LinkHint.ExistingRef)?.filePath }
        assertTrue("SurveyServiceImpl는 CONFIRMED 상태여야 함", confirmedPaths.any { it.contains("SurveyServiceImpl.java") })

        // 2. 동일 파일(SurveyServiceImpl)에 대한 PENDING 중복 항목이 0건이어야 함
        val pendingItems = turn0.state.items.filter { it.verdict == Verdict.PENDING }
        val pendingPaths = pendingItems.mapNotNull { (it.hint as? LinkHint.ExistingRef)?.filePath }
        assertFalse("SurveyServiceImpl는 PENDING으로 재등록/재질의되지 않아야 함", pendingPaths.any { it.contains("SurveyServiceImpl.java") })

        // 3. openQuestion에서 SurveyServiceImpl을 다시 묻지 않아야 함
        val openQ = turn0.openQuestion ?: ""
        assertFalse("openQuestion에 이미 확정된 SurveyServiceImpl 재질의가 없어야 함", openQ.contains("SurveyServiceImpl"))
    }

    @Test
    fun testExclusionContext_IdentifierInsideExcludeEvidenceIsNotConfirmed() {
        val graph = createSyntheticGraph()
        val scanner = Stage0GraphScanner(graph, minSpecificityScore = 1.0, proposalBudget = 10)
        val mockLlmJson = """
            [
              {
                "kind": "EXCLUDE_COMPONENT",
                "value": "SurveyServiceImpl 수정 제외",
                "rawStatement": "SurveyServiceImpl은 건드리지 말고 OrderController만 고쳐 줘",
                "evidence": "SurveyServiceImpl은 건드리지 말고"
              },
              {
                "kind": "SCOPE_LIMIT",
                "value": "OrderController만 수정",
                "rawStatement": "SurveyServiceImpl은 건드리지 말고 OrderController만 고쳐 줘",
                "evidence": "OrderController만 고쳐 줘"
              }
            ]
        """.trimIndent()
        val mockLlm = createMockLlm(mockLlmJson)
        val engine = Stage0ClarificationEngine(scanner, graph, mockLlm)

        val turn0 = engine.initSession("SurveyServiceImpl은 건드리지 말고 OrderController만 고쳐 줘")

        val confirmedItems = turn0.state.items.filter { it.verdict == Verdict.CONFIRMED }
        val confirmedPaths = confirmedItems.mapNotNull { (it.hint as? LinkHint.ExistingRef)?.filePath }

        // 배제 문맥인 SurveyServiceImpl은 절대 CONFIRMED가 되어서는 안 됨
        assertFalse("배제 대상인 SurveyServiceImpl은 CONFIRMED 상태가 아니어야 함", confirmedPaths.any { it.contains("SurveyServiceImpl.java") })

        // 명시적 수정 대상인 OrderController는 CONFIRMED여야 함
        assertTrue("수정 대상인 OrderController는 CONFIRMED 상태여야 함", confirmedPaths.any { it.contains("OrderController.java") })
    }

    @Test
    fun testExclusionContext_SubstringBoundary_SurveyServiceIsNotExcludedWhenSurveyServiceImplIsExcluded() {
        val graph = createSyntheticGraph()
        val scanner = Stage0GraphScanner(graph, minSpecificityScore = 1.0, proposalBudget = 10)
        val mockLlmJson = """
            [
              {
                "kind": "EXCLUDE_COMPONENT",
                "value": "SurveyServiceImpl 수정 제외",
                "rawStatement": "SurveyServiceImpl은 건드리지 말고 SurveyService만 고쳐 줘",
                "evidence": "SurveyServiceImpl은 건드리지 말고"
              },
              {
                "kind": "SCOPE_LIMIT",
                "value": "SurveyService만 수정",
                "rawStatement": "SurveyServiceImpl은 건드리지 말고 SurveyService만 고쳐 줘",
                "evidence": "SurveyService만 고쳐 줘"
              }
            ]
        """.trimIndent()
        val mockLlm = createMockLlm(mockLlmJson)
        val engine = Stage0ClarificationEngine(scanner, graph, mockLlm)

        val turn0 = engine.initSession("SurveyServiceImpl은 건드리지 말고 SurveyService만 고쳐 줘")

        val confirmedItems = turn0.state.items.filter { it.verdict == Verdict.CONFIRMED }
        val confirmedPaths = confirmedItems.mapNotNull { (it.hint as? LinkHint.ExistingRef)?.filePath }

        // 배제 문맥인 SurveyServiceImpl은 CONFIRMED 제외
        assertFalse("배제 대상인 SurveyServiceImpl은 CONFIRMED 상태가 아니어야 함", confirmedPaths.any { it.endsWith("SurveyServiceImpl.java") })

        // 한정/수정 대상인 SurveyService는 substring 오탐에 의해 배제되지 않고 CONFIRMED여야 함
        assertTrue("수정 대상인 SurveyService는 CONFIRMED 상태여야 함", confirmedPaths.any { it.endsWith("SurveyService.java") })
    }

    @Test
    fun testHallucinationGuard_NullEvidenceDowngradedToOther() {
        val graph = createSyntheticGraph()
        val scanner = Stage0GraphScanner(graph, minSpecificityScore = 1.0, proposalBudget = 10)
        val mockLlmJson = """
            [
              {
                "kind": "EXCLUDE_COMPONENT",
                "value": "기타 제외",
                "rawStatement": "OrderController를 수정할 건데 다른건 건드리지마",
                "evidence": null
              }
            ]
        """.trimIndent()
        val mockLlm = createMockLlm(mockLlmJson)
        val engine = Stage0ClarificationEngine(scanner, graph, mockLlm)

        val turn0 = engine.initSession("OrderController를 수정할 건데 다른건 건드리지마")

        val confirmedItems = turn0.state.items.filter { it.verdict == Verdict.CONFIRMED }
        val confirmedPaths = confirmedItems.mapNotNull { (it.hint as? LinkHint.ExistingRef)?.filePath }

        // evidence가 null인 EXCLUDE_COMPONENT 제약으로 인해 발화 내 유효 식별자인 OrderController가 배제되어서는 안 됨
        assertTrue("evidence가 null일 때 OrderController는 배제되지 않고 CONFIRMED여야 함", confirmedPaths.any { it.contains("OrderController.java") })
    }

    @Test
    fun testFallback_NoLlm_IdentifiersRemainPendingWithoutAutoConfirmed() {
        val graph = createSyntheticGraph()
        val scanner = Stage0GraphScanner(graph, minSpecificityScore = 1.0, proposalBudget = 10)
        // LLM이 null인 엔진 생성 (룰 폴백 상태)
        val engine = Stage0ClarificationEngine(scanner, graph, llmClient = null)

        val turn0 = engine.initSession("SurveyServiceImpl은 건드리지 말고 OrderController만 고쳐 줘")

        val confirmedItems = turn0.state.items.filter { it.verdict == Verdict.CONFIRMED }
        val confirmedPaths = confirmedItems.mapNotNull { (it.hint as? LinkHint.ExistingRef)?.filePath }

        // LLM 부재 시 배제 문맥을 판별할 수 없으므로 SurveyServiceImpl은 자동 CONFIRMED되지 않아야 함
        assertFalse("LLM이 꺼졌을 때 SurveyServiceImpl은 자동 CONFIRMED되지 않아야 함", 
            confirmedPaths.any { it.contains("SurveyServiceImpl.java") })

        // 발화 식별자는 PENDING 상태로 안전하게 보존되어야 함 (source는 USER_UTTERED)
        val pendingItems = turn0.state.items.filter { it.verdict == Verdict.PENDING }
        val surveyItem = pendingItems.find { (it.hint as? LinkHint.ExistingRef)?.filePath?.contains("SurveyServiceImpl.java") == true }
        assertNotNull("SurveyServiceImpl은 PENDING으로 보존되어야 함", surveyItem)
        assertEquals("출처는 USER_UTTERED여야 함", HintSource.USER_UTTERED, surveyItem?.source)
    }

    @Test
    fun testScenario4_NaturalSr_SAPACMM0802S01_RemainsPendingEvenWithNewKeywordInStatement() {
        val graph = createSyntheticGraph()
        val scanner = Stage0GraphScanner(graph, minSpecificityScore = 1.0, proposalBudget = 10)
        val mockLlmJson = """
            [
              {
                "kind": "NEW_MODULE",
                "value": "APCMMTrcdIsSVC 신규 추가",
                "rawStatement": "교통카드 발급정보 신규서비스 개발 건 (SAPACMM0802S01 참고). APCMMTrcdIsSVC 신규 추가",
                "evidence": "APCMMTrcdIsSVC 신규 추가"
              }
            ]
        """.trimIndent()
        val mockLlm = createMockLlm(mockLlmJson)
        val engine = Stage0ClarificationEngine(scanner, graph, mockLlm)

        val text = "교통카드 발급정보 신규서비스 개발 건 (SAPACMM0802S01 참고). APCMMTrcdIsSVC 신규 추가"
        val turn0 = engine.initSession(text)

        // 1. APCMMTrcdIsSVC는 NEW_MODULE evidence에 포함되어 있으므로 CONFIRMED 신규 생성이어야 함
        val confirmedItems = turn0.state.items.filter { it.verdict == Verdict.CONFIRMED }
        assertTrue("APCMMTrcdIsSVC는 CONFIRMED 상태여야 함", confirmedItems.any { it.statement.contains("APCMMTrcdIsSVC") && it.hint is LinkHint.NewCreation })

        // 2. 문장에 '신규' 단어가 있더라도, SAPACMM0802S01은 NEW_MODULE evidence에 없으므로 CONFIRMED가 아니어야 함
        assertFalse("단순 참고 식별자 SAPACMM0802S01은 CONFIRMED가 아니어야 함", confirmedItems.any { it.statement.contains("SAPACMM0802S01") })

        // 3. SAPACMM0802S01은 PENDING 상태로 보존되어야 함
        val pendingItems = turn0.state.items.filter { it.verdict == Verdict.PENDING }
        assertTrue("SAPACMM0802S01은 PENDING 상태로 유지되어야 함", pendingItems.any { it.statement.contains("SAPACMM0802S01") })

        // 4. openQuestion은 SAPACMM0802S01에 대해서만 묻고, APCMMTrcdIsSVC는 묻지 않아야 함
        val openQ = turn0.openQuestion ?: ""
        assertTrue("openQuestion에 SAPACMM0802S01 확인 질의가 포함되어야 함", openQ.contains("SAPACMM0802S01"))
        assertFalse("이미 확정된 APCMMTrcdIsSVC에 대해서는 질의가 없어야 함", openQ.contains("APCMMTrcdIsSVC"))
    }

    @Test
    fun testNewModule_EvidenceContainingIdentifier_BecomesConfirmed() {
        val graph = createSyntheticGraph()
        val scanner = Stage0GraphScanner(graph, minSpecificityScore = 1.0, proposalBudget = 10)
        val mockLlmJson = """
            [
              {
                "kind": "NEW_MODULE",
                "value": "NewPaymentProcessor 신규 생성",
                "rawStatement": "새로운 결제 처리기 NewPaymentProcessor 추가 필요",
                "evidence": "NewPaymentProcessor 추가 필요"
              }
            ]
        """.trimIndent()
        val mockLlm = createMockLlm(mockLlmJson)
        val engine = Stage0ClarificationEngine(scanner, graph, mockLlm)

        val turn0 = engine.initSession("새로운 결제 처리기 NewPaymentProcessor 추가 필요")

        // 1. NewPaymentProcessor는 NEW_MODULE evidence와 일치하므로 즉시 CONFIRMED여야 함
        val confirmedItems = turn0.state.items.filter { it.verdict == Verdict.CONFIRMED }
        assertTrue("NewPaymentProcessor는 CONFIRMED 상태여야 함", confirmedItems.any { it.statement.contains("NewPaymentProcessor") && it.hint is LinkHint.NewCreation })

        // 2. 미확인 질문이 없어야 함
        val openQ = turn0.openQuestion ?: ""
        assertFalse("NewPaymentProcessor에 대한 재확인 질문은 없어야 함", openQ.contains("NewPaymentProcessor"))
    }

    @Test
    fun testBranch2_UtteredAndGraphNotExists_AsksConfirmationQuestion() {
        val graph = createSyntheticGraph()
        val scanner = Stage0GraphScanner(graph, minSpecificityScore = 1.0, proposalBudget = 10)
        val engine = Stage0ClarificationEngine(scanner, graph)

        val turn0 = engine.initSession("기존 기능 개선")
        val turn1 = engine.processTurn(turn0.state, Stage0ClarificationEngine.UserInput(userStatement = "NonExistentCustomService를 연동해서 처리해줘"))

        // 1. 그래프에 없는 식별자는 확인 없이 CONFIRMED로 자동 확정되지 않아야 함 (hint와 verdict 동시 검증)
        val confirmedItems = turn1.state.items.filter { it.verdict == Verdict.CONFIRMED }
        val confirmedStatements = confirmedItems.map { it.statement }
        val confirmedHints = confirmedItems.map { it.hint }
        assertFalse("부존재 식별자는 확인 없이 자동 CONFIRMED되지 않아야 함", 
            confirmedStatements.any { it.contains("NonExistentCustomService") } || confirmedHints.any { (it as? LinkHint.ExistingRef)?.filePath?.contains("NonExistentCustomService") == true })

        // 2. 신규 생성 여부 확인 질문이 openQuestion으로 1회 발생해야 함
        val openQ = turn1.openQuestion ?: ""
        assertTrue(
            "신규 생성 여부 확인 질문이 생성되어야 함 (실제 openQ: '$openQ')",
            openQ.contains("NonExistentCustomService") && (openQ.contains("새로") || openQ.contains("신규") || openQ.contains("존재하지 않"))
        )

        // 3. NewCreation hint라도 미확인 상태에서는 PENDING이어야 함
        val pendingItems = turn1.state.items.filter { it.verdict == Verdict.PENDING }
        assertTrue("미확인 부존재 식별자는 PENDING 상태로 유지되어야 함", pendingItems.any { it.statement.contains("NonExistentCustomService") })
    }

    @Test
    fun testBranch2_TwoTurns_UnansweredUnmatchedIdentifier_RemainsPending() {
        val graph = createSyntheticGraph()
        val scanner = Stage0GraphScanner(graph, minSpecificityScore = 1.0, proposalBudget = 10)
        val mockLlmJson = """
            [
              {
                "kind": "SCOPE_LIMIT",
                "value": "OrderController 수정",
                "rawStatement": "OrderController만 고쳐줘",
                "evidence": "OrderController만 고쳐줘"
              }
            ]
        """.trimIndent()
        val mockLlm = createMockLlm(mockLlmJson)
        val engine = Stage0ClarificationEngine(scanner, graph, mockLlm)

        val turn0 = engine.initSession("SAPACMM0802S01을 참고하여 OrderController 수정")
        val turn1 = engine.processTurn(turn0.state, Stage0ClarificationEngine.UserInput(userStatement = "OrderController만 고쳐줘"))

        // 1. OrderController는 실재 노드이며 CONFIRMED
        val confirmedPaths = turn1.state.items.filter { it.verdict == Verdict.CONFIRMED }.mapNotNull { (it.hint as? LinkHint.ExistingRef)?.filePath }
        assertTrue("OrderController는 CONFIRMED여야 함", confirmedPaths.any { it.contains("OrderController.java") })

        // 2. 답변되지 않은 부존재 식별자 SAPACMM0802S01은 2턴 후에도 CONFIRMED가 아닌 PENDING으로 보존되어야 함
        val confirmedItems = turn1.state.items.filter { it.verdict == Verdict.CONFIRMED }
        assertFalse("답변되지 않은 SAPACMM0802S01은 절대 CONFIRMED가 아니어야 함", confirmedItems.any { it.statement.contains("SAPACMM0802S01") })

        val pendingItems = turn1.state.items.filter { it.verdict == Verdict.PENDING }
        assertTrue("답변되지 않은 SAPACMM0802S01은 PENDING으로 무손실 보존되어야 함", pendingItems.any { it.statement.contains("SAPACMM0802S01") })
    }

    @Test
    fun testBranch3_NotUtteredNeighborInGraph_RemainsPendingNeverAutoConfirmed() {
        val graph = createSyntheticGraph()
        val scanner = Stage0GraphScanner(graph, minSpecificityScore = 1.0, proposalBudget = 10)
        val engine = Stage0ClarificationEngine(scanner, graph)

        val turn0 = engine.initSession("SurveyServiceImpl만 수정할 거야")

        // 사용자가 명시하지 않은 인접 인터페이스 SurveyService는 PENDING으로 유지되어야 하며 자동 CONFIRMED 금지
        val confirmedItems = turn0.state.items.filter { it.verdict == Verdict.CONFIRMED }
        val confirmedPaths = confirmedItems.mapNotNull { (it.hint as? LinkHint.ExistingRef)?.filePath }
        assertFalse("언급되지 않은 SurveyService 인터페이스는 CONFIRMED가 되어서는 안 됨", confirmedPaths.any { it.endsWith("SurveyService.java") })

        val pendingItems = turn0.state.items.filter { it.verdict == Verdict.PENDING }
        val pendingPaths = pendingItems.mapNotNull { (it.hint as? LinkHint.ExistingRef)?.filePath }
        assertTrue("언급되지 않은 SurveyService 인터페이스는 PENDING으로 유지되어야 함", pendingPaths.any { it.endsWith("SurveyService.java") })
    }

    @Test
    fun testBoundaryMatching_AddWordDoesNotMatchAddressDao() {
        val graph = createSyntheticGraph()
        val scanner = Stage0GraphScanner(graph, minSpecificityScore = 1.0, proposalBudget = 10)
        val engine = Stage0ClarificationEngine(scanner, graph)

        val turn0 = engine.initSession("새로운 항목을 add 처리해줘")

        // 'add'라는 단어로 인해 AddressDao가 사용자 발화 확정(USER_UTTERED/CONFIRMED)으로 오탐 등록되지 않아야 함
        val confirmedItems = turn0.state.items.filter { it.verdict == Verdict.CONFIRMED }
        val confirmedPaths = confirmedItems.mapNotNull { (it.hint as? LinkHint.ExistingRef)?.filePath }
        assertFalse("'add' 단어로 인해 AddressDao가 자동 확정되어서는 안 됨", confirmedPaths.any { it.contains("AddressDao") })
    }

    @Test
    fun testRealSurveyAdmin_Turn1AndTurn2UserUtteredConfirmation() {
        val graph = loadSurveyAdminGraph()
        Assume.assumeNotNull("survey_admin 메타그래프가 존재할 때만 실행", graph)

        val scanner = Stage0GraphScanner(graph!!, minSpecificityScore = 1.0, proposalBudget = 10)
        val mockLlmJson = """
            [
              {
                "kind": "SCOPE_LIMIT",
                "value": "SurveyServiceImpl.java 수정",
                "rawStatement": "SurveyServiceImpl.java만 수정할 거야",
                "evidence": "SurveyServiceImpl.java만 수정할 거야"
              }
            ]
        """.trimIndent()
        val mockLlm = createMockLlm(mockLlmJson)
        val engine = Stage0ClarificationEngine(scanner, graph, mockLlm)

        // Turn 0
        val turn0 = engine.initSession("설문 발송 기능 개선")

        // Turn 1: 사용자가 구체적 파일명 명시
        val turn1 = engine.processTurn(
            state = turn0.state,
            userInput = Stage0ClarificationEngine.UserInput(userStatement = "SurveyServiceImpl.java만 수정할 거야")
        )

        val confirmedPaths = turn1.state.items
            .filter { it.verdict == Verdict.CONFIRMED }
            .mapNotNull { (it.hint as? LinkHint.ExistingRef)?.filePath }
        assertTrue("Turn 1에서 명시한 SurveyServiceImpl.java는 CONFIRMED여야 함", confirmedPaths.any { it.contains("SurveyServiceImpl.java") })

        val pendingPaths = turn1.state.items
            .filter { it.verdict == Verdict.PENDING }
            .mapNotNull { (it.hint as? LinkHint.ExistingRef)?.filePath }
        assertFalse("SurveyServiceImpl.java에 대한 PENDING 중복 항목은 0건이어야 함", pendingPaths.any { it.contains("SurveyServiceImpl.java") })
    }
}
