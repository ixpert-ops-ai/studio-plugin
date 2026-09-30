package net.ib.ixpert.ops.wuwagent.agent.clarify

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.*
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.OllamaMessage
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.AdaptiveFileDiscovery
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Instant

class ExcludeComponentDialogueTest {

    private lateinit var surveyGraph: ProjectGraph
    private lateinit var surveyScanner: Stage0GraphScanner

    private val gtFiles = listOf(
        "SurveyServiceImpl", "SurveyDaoImpl", "SurveyDao", "SurveyDto",
        "sql_survey.xml", "survey_write.jsp", "survey.write.js",
        "survey_list.jsp", "survey.list.js",
        "BrandmessageTemplateBatchJob", "BizgoApiServiceImpl"
    )

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

    private class FailingLlmClient : LLMClient {
        override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): OllamaChatResponse? = null
        override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = null
    }

    @Before
    fun setUp() {
        val path = File("C:/Workspace/HC_card_survey_admin/survey_admin/.meta/project-graph.json")
        assertTrue("survey_admin metagraph should exist", path.exists())
        surveyGraph = Gson().fromJson(path.readText(), ProjectGraph::class.java).normalizeLegacyCollections()
        surveyScanner = Stage0GraphScanner(
            graph = surveyGraph,
            minSpecificityScore = 1.0,
            proposalBudget = 10,
            localDomainOverrides = emptyMap(),
            maxBridgeDegree = 15,
            maxExternalShared = 3
        )
    }

    /**
     * [Case A: 모든 토큰 번역 성공 시나리오]
     * 테스트 그래프에 한글 localName 및 매핑 정보가 완비되어 모든 토큰 번역이 성공하는 경우:
     * - All-Token Matching으로 AlimtalkBatchJob만 정확히 매칭되고 BrandmessageBatchJob(GT)은 제외 후보에서 보호됨.
     * - Turn 2에서 "응" 입력 시 일괄 REJECTED 확정되어 excludedFiles에 등재됨.
     */
    @Test
    fun testCaseA_AllTokensTranslated_ExactMatchAndGtProtection() {
        val files = mapOf(
            "net/infobank/iss/batch/AlimtalkBatchJob.java" to FileNode(
                path = "net/infobank/iss/batch/AlimtalkBatchJob.java",
                packageName = "net.infobank.iss.batch",
                className = "AlimtalkBatchJob",
                localName = "알림톡 배치 작업",
                koreanComments = listOf("알림톡 발송 배치"),
                fileType = SpringFileType.SERVICE,
                layer = ArchitectureLayer.SERVICE
            ),
            "net/infobank/iss/batch/BrandmessageBatchJob.java" to FileNode(
                path = "net/infobank/iss/batch/BrandmessageBatchJob.java",
                packageName = "net.infobank.iss.batch",
                className = "BrandmessageBatchJob",
                localName = "브랜드메시지 배치 작업",
                koreanComments = listOf("브랜드메시지 발송 배치"),
                fileType = SpringFileType.SERVICE,
                layer = ArchitectureLayer.SERVICE
            )
        )
        val syntheticGraph = ProjectGraph(
            generatedAt = Instant.now().toString(),
            projectRoot = "/test/root",
            frameworkType = FrameworkType.SPRING_MVC_MYBATIS,
            files = files,
            relationships = emptyList(),
            resourceNodes = emptyList(),
            statistics = GraphStatistics()
        )
        val scanner = Stage0GraphScanner(syntheticGraph)
        val mockLlm = createMockLlm(
            """
            [
              {"kind": "EXCLUDE_COMPONENT", "value": "알림톡 배치 제외", "evidence": "알림톡 배치"}
            ]
            """.trimIndent()
        )
        val engine = Stage0ClarificationEngine(scanner, syntheticGraph, mockLlm)
        val turn0 = engine.initSession("설문 발송 처리")

        // Turn 1
        val turn1 = engine.processTurn(
            state = turn0.state,
            userInput = Stage0ClarificationEngine.UserInput(userStatement = "알림톡 배치는 건드리지 마")
        )

        // 단언: All-Token Match로 AlimtalkBatchJob만 매칭되고, GT인 BrandmessageBatchJob은 매칭되지 않음
        val proposedItems = turn1.state.items.filter { it.source == HintSource.PROPOSED_EXCLUSION }
        assertEquals(1, proposedItems.size)
        assertEquals("net/infobank/iss/batch/AlimtalkBatchJob.java", (proposedItems.first().hint as LinkHint.ExistingRef).filePath)
        assertEquals(ConfidenceBucket.HIGH_CONFIDENCE, proposedItems.first().confidence)
        assertTrue(turn1.openQuestion!!.contains("제외할까요?"))

        // Turn 2: "응"으로 전체 확인
        val turn2 = engine.processTurn(
            state = turn1.state,
            userInput = Stage0ClarificationEngine.UserInput(userStatement = "응")
        )
        val intent = engine.buildClarifyIntent(turn2.state)
        assertTrue(intent.excludedFiles.contains("net/infobank/iss/batch/AlimtalkBatchJob.java"))
        assertFalse(intent.excludedFiles.contains("net/infobank/iss/batch/BrandmessageBatchJob.java"))
    }

    /**
     * [Case B: 일부 토큰만 번역된 경우 (실제 survey_admin 메타그래프 실측)]
     * "알림톡 배치는 건드리지 말고..." 발화 시:
     * - "배치"만 번역되고 "알림톡"은 번역되지 않음.
     * - 항목별 선택 요구 질문이 생성되며 모호한 상태에서는 일반 "응"으로 일괄 제외되지 않음 (GT 보호).
     * - 사용자가 명시적으로 Alimtalk 파일들을 선택(verdictUpdates)할 때만 excludedFiles에 반영됨.
     * - Analyze 파이프라인 연계 시 GT recall 100% 보존(룰 폴백 조건 7/11) 및 알림톡 3개 제외 실측.
     */
    @Test
    fun testCaseB_PartialTranslation_ItemizedSelectionAndAnalyzeIntegration() {
        val mockLlm = createMockLlm(
            """
            [
              {"kind": "EXCLUDE_COMPONENT", "value": "미등록채널 배치 제외", "evidence": "미등록채널 배치"},
              {"kind": "SCOPE_LIMIT", "value": "SurveyServiceImpl만 수정", "evidence": "SurveyServiceImpl만"}
            ]
            """.trimIndent()
        )
        val engine = Stage0ClarificationEngine(surveyScanner, surveyGraph, mockLlm)
        val turn0 = engine.initSession("기존 설문 발송에 브랜드메시지 발송 채널 추가 및 어드민 개발")

        // Turn 1
        val turn1 = engine.processTurn(
            state = turn0.state,
            userInput = Stage0ClarificationEngine.UserInput(userStatement = "미등록채널 배치는 건드리지 말고 SurveyServiceImpl만 수정할 거야")
        )

        // 1. 부분 번역 질문 생성 확인
        println("=== Case B: Turn 1 Open Question ===")
        println(turn1.openQuestion)
        assertNotNull(turn1.openQuestion)
        assertTrue("질문에 미번역 단어 '미등록채널'이 언급되어야 함", turn1.openQuestion!!.contains("미등록채널"))
        assertTrue("질문에 번역 단어 '배치'가 언급되어야 함", turn1.openQuestion!!.contains("배치"))

        val proposedExclusions = turn1.state.items.filter { it.source == HintSource.PROPOSED_EXCLUSION }
        assertTrue("배치 관련 후보들이 제안되어야 함", proposedExclusions.isNotEmpty())
        assertTrue("부분 번역 후보들은 LOW_CONFIDENCE로 분류되어야 함", proposedExclusions.all { it.confidence == ConfidenceBucket.LOW_CONFIDENCE })

        // 2. 모호한 상태에서 일반 "응" 입력 시 일괄 제외 방지 단언
        val turn2GenericConfirm = engine.processTurn(
            state = turn1.state,
            userInput = Stage0ClarificationEngine.UserInput(userStatement = "응")
        )
        val intentGeneric = engine.buildClarifyIntent(turn2GenericConfirm.state)
        assertEquals("모호한 부분 번역 상태에서 일반 '응'은 일괄 제외하지 않음", 0, intentGeneric.excludedFiles.size)

        // 3. 명시적 항목 선택(verdictUpdates) 시 선택된 파일만 excludedFiles에 반영
        val alimtalkItems = proposedExclusions.filter { 
            val path = (it.hint as? LinkHint.ExistingRef)?.filePath ?: ""
            path.contains("AlimtalkTemplateBatch")
        }
        assertEquals("Alimtalk 배치 파일 3개 식별", 3, alimtalkItems.size)

        val updates = alimtalkItems.associate { it.id to Verdict.REJECTED }
        val turn2ExplicitSelect = engine.processTurn(
            state = turn1.state,
            userInput = Stage0ClarificationEngine.UserInput(verdictUpdates = updates)
        )
        val intentExplicit = engine.buildClarifyIntent(turn2ExplicitSelect.state)
        assertEquals(3, intentExplicit.excludedFiles.size)
        assertTrue(intentExplicit.excludedFiles.contains("src/main/java/net/infobank/iss/batch/AlimtalkTemplateBatchJob.java"))
        assertTrue(intentExplicit.excludedFiles.contains("src/main/java/net/infobank/iss/batch/AlimtalkTemplateBatchRunner.java"))
        assertTrue(intentExplicit.excludedFiles.contains("src/main/java/net/infobank/iss/batch/AlimtalkTemplateBatchRepository.java"))

        // 4. Analyze 파이프라인 연계 실측 (룰 폴백 조건: LLM-off)
        val failingClient = FailingLlmClient()
        val baselineResult = AdaptiveFileDiscovery.filter(
            primaryReq = "기존 설문 발송에 브랜드메시지 발송 채널 추가 및 어드민 개발",
            secondaryReq = "",
            graph = surveyGraph,
            client = failingClient,
            project = null,
            projectBasePath = "C:/Workspace/HC_card_survey_admin/survey_admin"
        )
        val baselineFiles = baselineResult.relevantFiles
        val baselineGtRecall = gtFiles.filter { g ->
            baselineFiles.any { f -> f.path.contains(g, ignoreCase = true) || f.className.contains(g, ignoreCase = true) }
        }

        val excludedPaths = intentExplicit.excludedFiles.toSet()
        val filteredFiles = baselineFiles.filter { it.path !in excludedPaths }
        val filteredGtRecall = gtFiles.filter { g ->
            filteredFiles.any { f -> f.path.contains(g, ignoreCase = true) || f.className.contains(g, ignoreCase = true) }
        }

        println("\n=== Case B: Analyze Filtered Result (룰 폴백 조건) ===")
        println("Baseline Candidate Count: ${baselineFiles.size} -> Filtered Count: ${filteredFiles.size} (-3)")
        println("Baseline GT Recall: ${baselineGtRecall.size} / ${gtFiles.size} -> Filtered GT Recall: ${filteredGtRecall.size} / ${gtFiles.size}")

        assertEquals(30, baselineFiles.size)
        assertEquals(27, filteredFiles.size)
        assertEquals(7, filteredGtRecall.size)
        assertEquals(baselineGtRecall.toSet(), filteredGtRecall.toSet())
    }

    /**
     * ["아니요" 거부/취소 응답 검증]
     * "알림톡 배치를 제외할까요?"에 "아니요"라고 답한 경우:
     * - 제안 항목(PROPOSED_EXCLUSION)이 state.items에서 완전히 삭제됨.
     * - CONFIRMED로 전이되지 않으며, 계약(ClarifyIntent)에 0건의 흔적(trustedExistingRefs/excludedFiles 0건)만 남김.
     */
    @Test
    fun testRejectionResponse_RemovesProposedExclusion_LeavesNoConfirmedTrace() {
        val mockLlm = createMockLlm(
            """
            [
              {"kind": "EXCLUDE_COMPONENT", "value": "알림톡 배치 제외", "evidence": "알림톡 배치"}
            ]
            """.trimIndent()
        )
        val engine = Stage0ClarificationEngine(surveyScanner, surveyGraph, mockLlm)
        val turn0 = engine.initSession("설문 발송 채널 추가")

        val turn1 = engine.processTurn(
            state = turn0.state,
            userInput = Stage0ClarificationEngine.UserInput(userStatement = "알림톡 배치는 제외해줘")
        )
        assertTrue(turn1.state.items.any { it.source == HintSource.PROPOSED_EXCLUSION })

        // Turn 2: "아니요" 입력
        val turn2 = engine.processTurn(
            state = turn1.state,
            userInput = Stage0ClarificationEngine.UserInput(userStatement = "아니요")
        )

        // 단언: PROPOSED_EXCLUSION 항목이 state.items에서 완전히 제거됨
        assertFalse(
            "PROPOSED_EXCLUSION item must be removed from state.items on negative response",
            turn2.state.items.any { it.source == HintSource.PROPOSED_EXCLUSION }
        )

        // 단언: 계약에 CONFIRMED나 excludedFiles로 남지 않음
        val intent = engine.buildClarifyIntent(turn2.state)
        assertEquals(0, intent.excludedFiles.size)
        assertFalse(
            "Excluded items must NOT be converted to CONFIRMED or trustedExistingRefs",
            intent.constraints.any { it.kind == ConstraintKind.SCOPE_LIMIT }
        )
    }

    /**
     * [Case C: 번역 실패 시나리오]
     * evidence에 포함된 한글 단어가 도메인 사전에 전혀 없는 경우:
     * - 추측성 후보를 생성하지 않고, 직접 클래스/패키지명을 질의하는 개방형 질문 생성.
     */
    @Test
    fun testCaseC_NoTranslation_DirectClassQuery() {
        val mockLlm = createMockLlm(
            """
            [
              {"kind": "EXCLUDE_COMPONENT", "value": "미등록모듈 제외", "evidence": "미등록모듈"}
            ]
            """.trimIndent()
        )
        val engine = Stage0ClarificationEngine(surveyScanner, surveyGraph, mockLlm)
        val turn0 = engine.initSession("설문 발송 채널 추가")

        val turn1 = engine.processTurn(
            state = turn0.state,
            userInput = Stage0ClarificationEngine.UserInput(userStatement = "미등록모듈은 제외해줘")
        )

        println("=== Case C: Turn 1 Open Question ===")
        println(turn1.openQuestion)
        assertNotNull(turn1.openQuestion)
        assertTrue(turn1.openQuestion!!.contains("클래스나 패키지명을 알려주세요"))
        assertEquals(0, turn1.state.items.count { it.source == HintSource.PROPOSED_EXCLUSION })
    }

    /**
     * [시나리오 2 실제 경로 실측 E2E 테스트 (실제 survey_admin 메타그래프 + 실제 발화)]
     * 실제 survey_admin 메타그래프에서 "알림톡 배치는 건드리지 말고 SurveyServiceImpl만 수정할 거야" 발화 시:
     * - AlimtalkTemplateBatchJob(localName), AlimtalkTemplateBatchRunner(koreanComments)에 "알림톡"이 실재하여 Case A로 분류.
     * - 후보 목록으로 Alimtalk 배치 2개가 정확히 특정되고 확인 질문 생성.
     * - Turn 2 "응" 답변 시 일괄 REJECTED 확정되어 excludedFiles(2개)에 인계.
     * - Analyze 파이프라인 연계 시 30개 -> 28개(-2)로 배제되고 GT recall 7/11(100%) 유지 실측.
     */
    @Test
    fun testScenario2_RealSurveyAdmin_EndToEndDialogueAndAnalyzeIntegration() {
        val mockLlm = createMockLlm(
            """
            [
              {"kind": "EXCLUDE_COMPONENT", "value": "알림톡 배치 제외", "evidence": "알림톡 배치"},
              {"kind": "SCOPE_LIMIT", "value": "SurveyServiceImpl만 수정", "evidence": "SurveyServiceImpl만"}
            ]
            """.trimIndent()
        )
        val engine = Stage0ClarificationEngine(surveyScanner, surveyGraph, mockLlm)
        val turn0 = engine.initSession("기존 설문 발송에 브랜드메시지 발송 채널 추가 및 어드민 개발")

        // Turn 1: 시나리오 2 원본 발화 입력
        val turn1 = engine.processTurn(
            state = turn0.state,
            userInput = Stage0ClarificationEngine.UserInput(userStatement = "알림톡 배치는 건드리지 말고 SurveyServiceImpl만 수정할 거야")
        )

        println("=== Scenario 2: Turn 1 Open Question ===")
        println(turn1.openQuestion)
        assertNotNull(turn1.openQuestion)
        assertTrue("질문에 제외 대상 파일명 언급 확인", turn1.openQuestion!!.contains("AlimtalkTemplateBatchJob.java"))
        assertTrue("질문에 제외 대상 파일명 언급 확인", turn1.openQuestion!!.contains("AlimtalkTemplateBatchRunner.java"))
        assertTrue("확인 질문 문구 단언", turn1.openQuestion!!.contains("제외할까요?"))

        val proposedExclusions = turn1.state.items.filter { it.source == HintSource.PROPOSED_EXCLUSION }
        assertEquals("Alimtalk 배치 파일 2개 식별 (Case A 분류)", 2, proposedExclusions.size)
        assertTrue("All-Token Match로 HIGH_CONFIDENCE 분류", proposedExclusions.all { it.confidence == ConfidenceBucket.HIGH_CONFIDENCE })

        val proposedPaths = proposedExclusions.mapNotNull { (it.hint as? LinkHint.ExistingRef)?.filePath }
        assertTrue(proposedPaths.contains("src/main/java/net/infobank/iss/batch/AlimtalkTemplateBatchJob.java"))
        assertTrue(proposedPaths.contains("src/main/java/net/infobank/iss/batch/AlimtalkTemplateBatchRunner.java"))

        // Turn 2: "응"으로 전체 배제 확정
        val turn2 = engine.processTurn(
            state = turn1.state,
            userInput = Stage0ClarificationEngine.UserInput(userStatement = "응")
        )
        val intent = engine.buildClarifyIntent(turn2.state)
        assertEquals("2개 파일 배제 확정", 2, intent.excludedFiles.size)
        assertTrue(intent.excludedFiles.contains("src/main/java/net/infobank/iss/batch/AlimtalkTemplateBatchJob.java"))
        assertTrue(intent.excludedFiles.contains("src/main/java/net/infobank/iss/batch/AlimtalkTemplateBatchRunner.java"))
        assertFalse("GT인 BrandmessageTemplateBatchJob은 excludedFiles에 들어가지 않음", intent.excludedFiles.any { it.contains("Brandmessage") })

        // Analyze 연계 실측
        val failingClient = FailingLlmClient()
        val baselineResult = AdaptiveFileDiscovery.filter(
            primaryReq = "기존 설문 발송에 브랜드메시지 발송 채널 추가 및 어드민 개발",
            secondaryReq = "",
            graph = surveyGraph,
            client = failingClient,
            project = null,
            projectBasePath = "C:/Workspace/HC_card_survey_admin/survey_admin"
        )
        val baselineFiles = baselineResult.relevantFiles
        val baselineGtRecall = gtFiles.filter { g ->
            baselineFiles.any { f -> f.path.contains(g, ignoreCase = true) || f.className.contains(g, ignoreCase = true) }
        }

        val excludedPaths = intent.excludedFiles.toSet()
        val filteredFiles = baselineFiles.filter { it.path !in excludedPaths }
        val filteredGtRecall = gtFiles.filter { g ->
            filteredFiles.any { f -> f.path.contains(g, ignoreCase = true) || f.className.contains(g, ignoreCase = true) }
        }

        println("\n=== Scenario 2: Analyze Filtered Result (룰 폴백 조건) ===")
        println("Baseline Candidate Count: ${baselineFiles.size} -> Filtered Count: ${filteredFiles.size} (-2)")
        println("Baseline GT Recall: ${baselineGtRecall.size} / ${gtFiles.size} -> Filtered GT Recall: ${filteredGtRecall.size} / ${gtFiles.size}")

        assertEquals(30, baselineFiles.size)
        assertEquals(28, filteredFiles.size)
        assertEquals(7, filteredGtRecall.size)
        assertEquals(baselineGtRecall.toSet(), filteredGtRecall.toSet())
    }

    /**
     * [B-19] 긍정/부정 응답 판정 시 'y', 'n' 등 부분 문자열 매칭으로 인한 오판정 방지 검증:
     * - 배제 제안 후 사용자가 "SurveyServiceImpl도 같이 봐야 해"와 같이 'y'가 포함된 발화를 했을 때,
     * - 이를 긍정(Affirmative) 응답으로 오판정하여 배제 후보를 REJECTED로 확정해서는 안 됨.
     */
    @Test
    fun testExclusionResponse_NotTriggeredByPartialSubstringLikeSurvey() {
        val mockJson = """
            [
              {
                "kind": "EXCLUDE_COMPONENT",
                "value": "알림톡 배치 제외",
                "rawStatement": "알림톡 배치는 제외해줘",
                "evidence": "알림톡 배치"
              }
            ]
        """.trimIndent()
        val mockClient = createMockLlm(mockJson)
        val engine = Stage0ClarificationEngine(
            scanner = surveyScanner,
            graph = surveyGraph,
            llmClient = mockClient
        )

        // Turn 1: 알림톡 배치 배제 제안
        val turn0 = engine.initSession("설문 발송 채널에 브랜드메시지 추가")
        val turn1 = engine.processTurn(
            state = turn0.state,
            userInput = Stage0ClarificationEngine.UserInput(userStatement = "알림톡 배치는 제외해줘")
        )
        val pendingCount = turn1.state.items.count { it.source == HintSource.PROPOSED_EXCLUSION && it.verdict == Verdict.PENDING }
        assertTrue("1턴 후 배제 제안 PENDING 항목이 존재해야 함", pendingCount > 0)

        // Turn 2: 'y'가 포함된 일반 발화 ("SurveyServiceImpl도 같이 봐야 해")
        val turn2 = engine.processTurn(
            state = turn1.state,
            userInput = Stage0ClarificationEngine.UserInput(userStatement = "SurveyServiceImpl도 같이 봐야 해")
        )
        val intent = engine.buildClarifyIntent(turn2.state)

        assertEquals("식별자가 포함된 일반 발화는 긍정 응답으로 오판정되어 배제 확정(REJECTED)되면 안 됨", 0, intent.excludedFiles.size)
        assertTrue("배제 확정(REJECTED)된 항목이 0건이어야 함", turn2.state.items.none { it.verdict == Verdict.REJECTED })
    }
}

