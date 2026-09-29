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
        val engine = Stage0ClarificationEngine(scanner, graph)

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
    fun testExclusionContext_NullEvidence_DoesNotExcludeAllIdentifiers() {
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
    fun testBranch2_UtteredAndGraphNotExists_AsksConfirmationQuestion() {
        val graph = createSyntheticGraph()
        val scanner = Stage0GraphScanner(graph, minSpecificityScore = 1.0, proposalBudget = 10)
        val engine = Stage0ClarificationEngine(scanner, graph)

        val turn0 = engine.initSession("NonExistentCustomService를 연동해서 처리해줘")

        // 1. 그래프에 없는 식별자는 임의로 CONFIRMED로 자동 확정되지 않아야 함
        val confirmedItems = turn0.state.items.filter { it.verdict == Verdict.CONFIRMED }
        val confirmedStatements = confirmedItems.map { it.statement }
        assertFalse("부존재 식별자는 확인 없이 자동 CONFIRMED되지 않아야 함", confirmedStatements.any { it.contains("NonExistentCustomService") })

        // 2. 신규 생성 여부 확인 질문이 openQuestion 또는 제안으로 1회 발생해야 함
        val openQ = turn0.openQuestion ?: ""
        assertTrue(
            "신규 생성 여부 확인 질문이 생성되어야 함 (실제 openQ: '$openQ')",
            openQ.contains("NonExistentCustomService") && (openQ.contains("새로") || openQ.contains("신규") || openQ.contains("존재하지 않"))
        )
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
        val engine = Stage0ClarificationEngine(scanner, graph)

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
