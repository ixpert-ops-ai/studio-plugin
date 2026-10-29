package net.ib.ixpert.ops.wuwagent.agent

import net.ib.ixpert.ops.wuwagent.agent.clarify.AnalyzeInputResolver
import net.ib.ixpert.ops.wuwagent.agent.clarify.IncompleteAnalysisStore
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.ClarifyIntent
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.ConstraintKind
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.IntentConstraint
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.model.ChatChoice
import net.ib.ixpert.ops.wuwagent.model.ChatCompletionResponse
import net.ib.ixpert.ops.wuwagent.model.ChatMessage
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.OllamaMessage
import net.ib.ixpert.ops.wuwagent.model.ToolCall
import net.ib.ixpert.ops.wuwagent.model.ToolCallFunction
import net.ib.ixpert.ops.wuwagent.model.ToolDefinition
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ArchitectureLayer
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FileNode
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.GraphStatistics
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.SpringFileType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * B-75 2: 분석 미완료 뒤 "다시 시도"가 같은 인텐트(제외 파일, 제약, 정제문)로 시작한다.
 * 첫 실행(탐색기 실패)이 미완료로 끝나고, 보관한 인텐트로 다시 시작한 실행에서 제외한 파일이 빠져 있고 제약이 그대로다.
 */
class RetryIntentB75Test {

    private class Stub(val explorerFails: Boolean) : LLMClient {
        override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): OllamaChatResponse =
            OllamaChatResponse(model = "stub", createdAt = "", message = OllamaMessage("assistant", "{}"), done = true)

        private fun call(name: String, args: String) =
            ChatCompletionResponse("x", listOf(ChatChoice(0, ChatMessage("assistant", null, toolCalls = listOf(ToolCall("t", "function", ToolCallFunction(name, args)))), "tool_calls")))

        override fun chatWithTools(systemPrompt: String, messages: List<ChatMessage>, maxTokens: Int?, tools: List<ToolDefinition>?, toolChoice: Any?, temperature: Double?): ChatCompletionResponse? {
            if (systemPrompt.contains("당신은 시스템의 소스코드 및 메타그래프를")) {
                return if (explorerFails) ChatCompletionResponse("x", listOf(ChatChoice(0, ChatMessage("assistant", "도구 없이 끝냅니다"), "stop")))
                else call("confirm_final_seeds", "{\"seed_classes\":[\"SurveyServiceImpl\",\"SurveyDto\",\"ReportDao\"],\"rationale\":\"테스트\"}")
            }
            if (!systemPrompt.contains("당신은 코드 변경 범위 검증자입니다.")) return null
            val user = messages.mapNotNull { it.content }.joinToString("\n")
            val cands = Stage3Prompt.candidates(user)
            val req = cands.joinToString(",") { "{\"n\":${it.first},\"reason\":\"수정이 필요한 구체적인 사유\"}" }
            return call("submit_verification", "{\"requiredFiles\":[$req],\"removedFiles\":[],\"reasoning\":\"1) 테스트 검증\"}")
        }

        override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
    }

    private val names = listOf("SurveyServiceImpl", "SurveyDto", "ReportDao", "ZetaService")
    private fun pathOf(n: String) = "com/example/$n.java"
    private fun graph() = ProjectGraph(
        generatedAt = java.time.Instant.now().toString(), projectRoot = "/test/root",
        files = names.associate { pathOf(it) to FileNode(path = pathOf(it), packageName = "com.example", className = it, fileType = SpringFileType.SERVICE, layer = ArchitectureLayer.SERVICE) },
        relationships = emptyList(), statistics = GraphStatistics()
    )

    private val original = "SurveyServiceImpl SurveyDto ReportDao 응답자 이메일 마스킹 수정"
    private val excluded = pathOf("ReportDao")
    private val constraint = IntentConstraint(kind = ConstraintKind.EXCLUDE_COMPONENT, value = "ReportDao 제외", rawStatement = "ReportDao는 건드리지 마세요", evidence = "ReportDao는 건드리지 마세요")

    private fun intent() = ClarifyIntent(
        originalRequirement = original, refinedRequirement = original, userStatements = listOf("ReportDao는 건드리지 마세요"),
        constraints = listOf(constraint), excludedFiles = listOf(excluded), graphHash = "h"
    )

    private fun run(stub: Stub, rawInput: String, intent: ClarifyIntent?): Pair<RequirementAnalysisResult, net.ib.ixpert.ops.wuwagent.agent.clarify.ResolvedAnalyzeInput> {
        val resolved = AnalyzeInputResolver.resolve(rawInput, intent)
        val result = kotlinx.coroutines.runBlocking {
            RequirementAnalysisPipeline(null, stub, true).analyze(
                primaryReq = resolved.effectiveRequirement, secondaryReq = "", projectGraph = graph(),
                clarifyIntent = resolved.effectiveIntent, analyzeInput = resolved
            )
        }
        return result to resolved
    }

    @Before @After
    fun clean() {
        AnalysisSessionMemory.reset()
        RequirementAnalysisPipeline.lastResult = null
        IncompleteAnalysisStore.clear()
    }

    @Test
    fun p1_미완료_뒤_다시_시도하면_제외한_파일이_빠져_있고_제약과_정제문이_그대로다() {
        // 첫 실행: 탐색기 실패 → 미완료, 인텐트를 보관한다(라우터가 하는 일)
        val intent = intent()
        val (first, _) = run(Stub(explorerFails = true), original, intent)
        assertTrue(first.incomplete)
        val key = IncompleteAnalysisStore.put(original, intent)

        // 다시 시도: 보관한 입력과 인텐트로 시작한다(라우터의 /analyze-retry)
        val entry = IncompleteAnalysisStore.get(key)
        assertNotNull(entry)
        assertSame("같은 인텐트 객체", intent, entry!!.intent)
        assertEquals(original, entry.rawInput)
        val (second, resolved) = run(Stub(explorerFails = false), entry.rawInput, entry.intent)

        assertFalse(second.incomplete)
        // 제외한 파일이 다시 시도 뒤에도 빠져 있다
        assertTrue("제외한 파일이 결과에 없어야 한다: ${second.targetFiles.map { it.path }}", second.targetFiles.none { it.path == excluded })
        assertTrue("빠진 후보에 사용자 제외로 남는다", second.removedCandidates.orEmpty().any { it.path == excluded && it.source == RemovedSource.USER_EXCLUDED })
        assertTrue(second.targetFiles.any { it.path.endsWith("SurveyServiceImpl.java") })
        // 제약과 정제문이 유지된다
        assertEquals(listOf(constraint), resolved.effectiveIntent?.constraints)
        assertEquals(listOf(excluded), resolved.excludedFiles)
        assertEquals(original, resolved.rawRefined)
    }

    @Test
    fun p2_재시도_키가_없거나_밀려난_경우는_null이고_보관은_5개까지다() {
        assertNull(IncompleteAnalysisStore.get(null))
        assertNull(IncompleteAnalysisStore.get("없는 키"))
        val keys = (1..7).map { IncompleteAnalysisStore.put("입력 $it", null) }
        assertNull("가장 오래된 항목은 밀려난다", IncompleteAnalysisStore.get(keys[0]))
        assertNull(IncompleteAnalysisStore.get(keys[1]))
        assertEquals("입력 7", IncompleteAnalysisStore.get(keys[6])!!.rawInput)
    }

    @Test
    fun p3_라우터는_인텐트가_있을_때만_키를_만들고_재시도_분기가_보관한_인텐트로_시작한다() {
        val router = File("src/main/kotlin/net/ib/ixpert/ops/wuwagent/action/WebviewActionRouter.kt").readText(Charsets.UTF_8)
        assertTrue(router.contains("if (inMemoryClarifyIntent != null) {") && router.contains("IncompleteAnalysisStore.put(rawInput, inMemoryClarifyIntent)"))
        val retry = router.substringAfter("\"/analyze-retry\" -> {").substringBefore("// ── 요구사항 구체화 확인")
        assertTrue(retry.contains("IncompleteAnalysisStore.get(payload[\"retryKey\"])"))
        assertTrue(retry.contains("executeAnalyzePipeline(project, bridge, entry.rawInput, messageId, entry.intent)"))
        // 키가 없으면(인텐트 없이 시작한 분석) 요구사항만으로 다시 시작한다
        assertTrue(retry.contains("executeAnalyzePipeline(project, bridge, requirement, messageId, null)"))
    }
}
