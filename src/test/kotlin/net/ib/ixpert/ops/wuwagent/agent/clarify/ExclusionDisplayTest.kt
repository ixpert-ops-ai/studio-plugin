package net.ib.ixpert.ops.wuwagent.agent.clarify

import net.ib.ixpert.ops.wuwagent.agent.clarify.model.ClarifyIntent
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.HintSource
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.UnconfirmedExclusion
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.exclusionSummaryLines
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.model.ChatCompletionResponse
import net.ib.ixpert.ops.wuwagent.model.ChatMessage
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.OllamaMessage
import net.ib.ixpert.ops.wuwagent.model.ToolDefinition
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ArchitectureLayer
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FileNode
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.GraphStatistics
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.MethodSignature
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.Relationship
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.RelationshipType
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.SpringFileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * B-39 제외 결과 표시(동작 변경 없음): 확정되지 않은 제외 요청이 ClarifyIntent에 기록되고, 저장·로드와 요약 줄이 맞는지 확인한다.
 * 가짜 LLM이 제약 추출 결과(EXCLUDE_COMPONENT / SCOPE_LIMIT 등)를 고정해서 돌려준다.
 */
class ExclusionDisplayTest {

    private class FakeLlm(private val constraintsJson: String) : LLMClient {
        override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): OllamaChatResponse {
            val content = when {
                systemPrompt.contains("소프트웨어 요구사항 정제 전문가") -> "신규 FooService 추가"
                systemPrompt.contains("ConstraintKind") -> constraintsJson
                systemPrompt.contains("개방형 질문") -> "추가로 수정할 대상이 있으신가요?"
                else -> error("unmatched prompt: [$systemPrompt]")
            }
            return OllamaChatResponse(model = "fake", createdAt = "", message = OllamaMessage("assistant", content), done = true)
        }

        override fun chatWithTools(
            systemPrompt: String, messages: List<ChatMessage>, maxTokens: Int?, tools: List<ToolDefinition>?, toolChoice: Any?, temperature: Double?
        ): ChatCompletionResponse? = error("chatWithTools를 쓰지 않는다")

        override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
    }

    // 4조건 테스트와 같은 그래프(SurveyServiceImpl 등)
    private fun graph(): ProjectGraph {
        val files = mapOf(
            "com/example/OrderDto.java" to FileNode(
                path = "com/example/OrderDto.java", packageName = "com.example", className = "OrderDto",
                fileType = SpringFileType.DTO, layer = ArchitectureLayer.PERSISTENCE,
                methods = listOf(MethodSignature("getPay_type", "String", emptyList()))
            ),
            "com/example/SurveyServiceImpl.java" to FileNode(
                path = "com/example/SurveyServiceImpl.java", packageName = "com.example", className = "SurveyServiceImpl",
                localName = "SurveyServiceImpl", koreanComments = listOf("SurveyServiceImpl 설문 서비스 구현체"),
                fileType = SpringFileType.SERVICE, layer = ArchitectureLayer.BUSINESS,
                methods = listOf(MethodSignature("executeSurvey", "void", emptyList()))
            ),
            "com/example/SurveyService.java" to FileNode(
                path = "com/example/SurveyService.java", packageName = "com.example", className = "SurveyService",
                localName = "SurveyServiceImpl", koreanComments = listOf("SurveyServiceImpl 설문 서비스 인터페이스"),
                fileType = SpringFileType.INTERFACE, layer = ArchitectureLayer.BUSINESS,
                methods = listOf(MethodSignature("executeSurvey", "void", emptyList()))
            )
        )
        return ProjectGraph(
            generatedAt = java.time.Instant.now().toString(), projectRoot = "/test/root", frameworkType = FrameworkType.SPRING_MVC_MYBATIS,
            files = files,
            relationships = listOf(Relationship(source = "com/example/SurveyServiceImpl.java", target = "com/example/SurveyService.java", type = RelationshipType.IMPLEMENTS)),
            resourceNodes = emptyList(), statistics = GraphStatistics()
        )
    }

    private val excludeEvidence = "SurveyServiceImpl은 건드리지 말고"
    private val excludeAndScope = """
        [
          {"kind": "EXCLUDE_COMPONENT", "value": "SurveyServiceImpl 수정 제외", "rawStatement": "SurveyServiceImpl은 건드리지 말고 FooDto만 수정해줘", "evidence": "SurveyServiceImpl은 건드리지 말고"},
          {"kind": "SCOPE_LIMIT", "value": "FooDto만 수정", "rawStatement": "SurveyServiceImpl은 건드리지 말고 FooDto만 수정해줘", "evidence": "FooDto만 수정해줘"}
        ]
    """.trimIndent()
    private val statement = "SurveyServiceImpl은 건드리지 말고 FooDto만 수정해줘"

    private fun engine(constraintsJson: String): Stage0ClarificationEngine {
        val g = graph()
        return Stage0ClarificationEngine(Stage0GraphScanner(g, minSpecificityScore = 1.0, proposalBudget = 10), g, llmClient = FakeLlm(constraintsJson))
    }

    /** UI 경로처럼 발화를 userStatements에만 쌓은 상태에서 의도를 만든다(제외 제안 항목 없음, excludedFiles 비어 있음). */
    private fun intentWithStatement(e: Stage0ClarificationEngine): ClarifyIntent {
        val state = e.initSession("주문 기능 개발").state
        return e.buildClarifyIntent(state.copy(userStatements = listOf(statement)))
    }

    // E1 제외 문장(EXCLUDE_COMPONENT)이 확정되지 않았으면 미확정으로 기록된다. 후보 파일은 증거 구문 탐색 결과다.
    @Test
    fun e1_unconfirmedExclusionIsRecorded() {
        val e = engine(excludeAndScope)
        val intent = intentWithStatement(e)
        assertEquals(emptyList<String>(), intent.excludedFiles)
        assertEquals(1, intent.unconfirmedExclusions.size)
        val u = intent.unconfirmedExclusions.single()
        assertEquals(excludeEvidence, u.evidence)
        assertEquals(e.resolveExclusionCandidates(excludeEvidence).candidates.map { it.path }.distinct(), u.candidateFiles)
        assertTrue(u.candidateFiles.contains("com/example/SurveyServiceImpl.java"))
    }

    // E2 범위 한정(SCOPE_LIMIT)만 있으면 미확정 제외가 아니다("SurveyServiceImpl만 수정" 같은 문장).
    @Test
    fun e2_scopeLimitIsNotAnUnconfirmedExclusion() {
        val json = """[{"kind": "SCOPE_LIMIT", "value": "FooDto만 수정", "rawStatement": "$statement", "evidence": "FooDto만 수정해줘"}]"""
        val intent = intentWithStatement(engine(json))
        assertEquals(emptyList<UnconfirmedExclusion>(), intent.unconfirmedExclusions)
    }

    // E3 대상 파일을 특정하지 못한 제외 문장도 기록된다(후보 목록이 비어 있음).
    @Test
    fun e3_exclusionWithoutResolvableTargetHasNoCandidates() {
        val json = """[{"kind": "EXCLUDE_COMPONENT", "value": "건드리지 마세요", "rawStatement": "$statement", "evidence": "건드리지 마세요"}]"""
        val e = engine(json)
        val state = e.initSession("주문 기능 개발").state
        val intent = e.buildClarifyIntent(state.copy(userStatements = listOf("건드리지 마세요")))
        assertEquals(1, intent.unconfirmedExclusions.size)
        assertEquals("건드리지 마세요", intent.unconfirmedExclusions.single().evidence)
        assertEquals(e.resolveExclusionCandidates("건드리지 마세요").candidates.map { it.path }.distinct(), intent.unconfirmedExclusions.single().candidateFiles)
    }

    // E4 제외가 확정(REJECTED)되면 미확정이 아니고, 확정 목록에 나온다. 화면 payload용 보기도 확정 파일을 보여 준다.
    @Test
    fun e4_confirmedExclusionIsNotUnconfirmed() {
        val e = engine(excludeAndScope)
        val turn0 = e.initSession("주문 기능 개발")
        val turn1 = e.processTurn(turn0.state, Stage0ClarificationEngine.UserInput(userStatement = statement))
        val pending = e.exclusionView(turn1.state)
        assertTrue("제안 상태로 대기 중인 제외 후보가 보여야 함: $pending", pending.pendingExclusions.contains("com/example/SurveyServiceImpl.java"))
        assertEquals(emptyList<String>(), pending.excludedFiles)
        val turn2 = e.processTurn(turn1.state, Stage0ClarificationEngine.UserInput(userStatement = "응"))
        val view = e.exclusionView(turn2.state)
        assertTrue(view.excludedFiles.contains("com/example/SurveyServiceImpl.java"))
        assertEquals(emptyList<String>(), view.pendingExclusions)
        val intent = e.buildClarifyIntent(turn2.state)
        assertTrue(intent.excludedFiles.contains("com/example/SurveyServiceImpl.java"))
        assertEquals(emptyList<UnconfirmedExclusion>(), intent.unconfirmedExclusions)
        assertTrue(intent.exclusionSummaryLines().first().startsWith("- ✅ 제외 확정: "))
        assertTrue(turn2.state.items.any { it.source == HintSource.USER_CONFIRMED })
    }

    // E5 요약 줄: 미확정 문구는 행동 안내를 포함하고, 둘 다 없으면 아무 줄도 없다.
    @Test
    fun e5_summaryLines() {
        val none = ClarifyIntent(originalRequirement = "원문", refinedRequirement = "정제문", graphHash = "h")
        assertEquals(emptyList<String>(), none.exclusionSummaryLines())

        val withCandidates = none.copy(unconfirmedExclusions = listOf(UnconfirmedExclusion("알림톡 배치는 건드리지 말고", listOf("a/AlimtalkTemplateBatchJob.java", "a/AlimtalkTemplateBatchRunner.java"))))
        assertEquals(
            listOf("- ⚠️ 제외 요청이 확정되지 않았습니다: '알림톡 배치는 건드리지 말고' (후보: AlimtalkTemplateBatchJob.java, AlimtalkTemplateBatchRunner.java). 분석 결과에 포함될 수 있습니다. 제외하려면 /clarify 단계에서 목록의 체크를 해제하세요."),
            withCandidates.exclusionSummaryLines()
        )

        val noTarget = none.copy(unconfirmedExclusions = listOf(UnconfirmedExclusion("건드리지 마세요")))
        assertEquals(
            listOf("- ⚠️ 제외 요청이 확정되지 않았습니다: '건드리지 마세요' (대상 파일을 특정하지 못했습니다). 분석 결과에 포함될 수 있습니다. 제외하려면 /clarify 단계에서 목록의 체크를 해제하세요."),
            noTarget.exclusionSummaryLines()
        )

        val both = none.copy(excludedFiles = listOf("x/Job.java"), unconfirmedExclusions = noTarget.unconfirmedExclusions)
        assertEquals(2, both.exclusionSummaryLines().size)
        assertEquals("- ✅ 제외 확정: Job.java", both.exclusionSummaryLines()[0])
    }

    // E6 저장·로드: 새 필드가 왕복하고, 이 필드가 없는 과거 저장 파일도 빈 목록으로 로드된다.
    @Test
    fun e6_storeRoundTripAndOldFileLoading() {
        val e = engine(excludeAndScope)
        val g = graph()
        val intent = intentWithStatement(e)
        assertEquals(1, intent.unconfirmedExclusions.size)

        val dir = Files.createTempDirectory("exclusion_display_test").toFile()
        try {
            val file = ClarifyIntentStore.saveIntent(dir, intent, key = "e6")
            val loaded = ClarifyIntentStore.loadIntent(file, g)
            assertEquals(intent.unconfirmedExclusions, loaded.unconfirmedExclusions)

            // 과거 저장 파일: unconfirmedExclusions 키가 없는 JSON
            val root = com.google.gson.JsonParser.parseString(file.readText(Charsets.UTF_8)).asJsonObject
            root.remove("unconfirmedExclusions")
            val oldFile = java.io.File(file.parentFile, "old_e6.json")
            oldFile.writeText(root.toString(), Charsets.UTF_8)
            val oldLoaded = ClarifyIntentStore.loadIntent(oldFile, g)
            assertEquals(emptyList<UnconfirmedExclusion>(), oldLoaded.unconfirmedExclusions)
            assertEquals(intent.excludedFiles, oldLoaded.excludedFiles)
            assertEquals(intent.refinedRequirement, oldLoaded.refinedRequirement)
        } finally {
            dir.deleteRecursively()
        }
    }
}
