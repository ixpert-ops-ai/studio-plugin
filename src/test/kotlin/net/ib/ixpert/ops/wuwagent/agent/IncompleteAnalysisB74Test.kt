package net.ib.ixpert.ops.wuwagent.agent

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.model.ChatChoice
import net.ib.ixpert.ops.wuwagent.model.ChatCompletionResponse
import net.ib.ixpert.ops.wuwagent.model.ChatMessage
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.OllamaMessage
import net.ib.ixpert.ops.wuwagent.model.ToolCall
import net.ib.ixpert.ops.wuwagent.model.ToolCallFunction
import net.ib.ixpert.ops.wuwagent.model.ToolDefinition
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.AgenticSeedSelector
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ArchitectureLayer
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FileNode
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.GraphStatistics
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.SpringFileType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * B-74: 탐색기(시드 선정)나 검증기(Stage 3)가 대체 경로로 끝나면 결과 표와 /implement 안내 대신 안내와 "다시 시도"만 낸다.
 * 정상 경로의 출력은 바뀌지 않고, project가 없는 평가 하네스·단위 테스트(기본값)는 대체 경로 결과를 그대로 쓴다.
 * R-EMAIL-PIPE: IDE 응답자 이메일 SR의 정제문 두 개를 모의 LLM(탐색기 도구 호출 재생)으로 파이프라인에 태운다.
 */
class IncompleteAnalysisB74Test {

    private val explorerPrompt = "당신은 시스템의 소스코드 및 메타그래프를 능동적으로 탐색하는 전문 AI 분석 에이전트입니다."

    /** explorer: "seeds" = 시드를 확정하는 도구 호출, "none" = 도구 호출 없는 응답, "replay" = 검색 후 확정(IDE 성공 실행 재생). verifier: "ok" 또는 "none"(도구 호출 없음). */
    private class Stub(val explorer: String, val verifier: String, val seeds: List<String>, val requiredSuffixes: List<String>) : LLMClient {
        var explorerTurns = 0
        private fun call(name: String, args: String) =
            ChatCompletionResponse("x", listOf(ChatChoice(0, ChatMessage("assistant", null, toolCalls = listOf(ToolCall("t$explorerTurns", "function", ToolCallFunction(name, args)))), "tool_calls")))

        override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): OllamaChatResponse =
            OllamaChatResponse(model = "stub", createdAt = "", message = OllamaMessage("assistant", "{}"), done = true)

        override fun chatWithTools(systemPrompt: String, messages: List<ChatMessage>, maxTokens: Int?, tools: List<ToolDefinition>?, toolChoice: Any?, temperature: Double?): ChatCompletionResponse? {
            if (systemPrompt.contains("당신은 시스템의 소스코드 및 메타그래프를")) {
                explorerTurns++
                val confirm = "{\"seed_classes\":[${seeds.joinToString(",") { "\"$it\"" }}],\"rationale\":\"테스트 확정\"}"
                return when (explorer) {
                    "seeds" -> call("confirm_final_seeds", confirm)
                    "replay" -> if (explorerTurns == 1) call("search_graph_nodes", "{\"query\":\"survey response email\"}") else call("confirm_final_seeds", confirm)
                    else -> ChatCompletionResponse("x", listOf(ChatChoice(0, ChatMessage("assistant", "도구 없이 끝냅니다"), "stop")))
                }
            }
            if (!systemPrompt.contains("당신은 코드 변경 범위 검증자입니다.")) return null
            if (verifier == "none") return ChatCompletionResponse("x", listOf(ChatChoice(0, ChatMessage("assistant", "도구 호출 없음"), "stop")))
            val user = messages.mapNotNull { it.content }.joinToString("\n")
            val cands = Regex("(?m)^- \\[(\\d+)] (\\S+)").findAll(user).map { it.groupValues[1].toInt() to it.groupValues[2] }.toList()
            val required = cands.filter { (_, p) -> requiredSuffixes.any { p.endsWith(it) } }.ifEmpty { cands.take(1) }
            val req = required.joinToString(",") { "{\"n\":${it.first},\"reason\":\"수정이 필요한 구체적인 사유\"}" }
            val removed = cands.map { it.first }.filter { n -> required.none { it.first == n } }.joinToString(",")
            return call("submit_verification", "{\"requiredFiles\":[$req],\"removedFiles\":[$removed],\"reasoning\":\"1) 테스트 검증\"}")
        }

        override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
    }

    private val names = listOf("SurveyServiceImpl", "SurveyDto", "SurveyDaoImpl", "ReportDao", "ZetaService")
    private fun pathOf(n: String) = "com/example/$n.java"
    private fun graph() = ProjectGraph(
        generatedAt = java.time.Instant.now().toString(), projectRoot = "/test/root",
        files = names.associate { pathOf(it) to FileNode(path = pathOf(it), packageName = "com.example", className = it, fileType = SpringFileType.SERVICE, layer = ArchitectureLayer.SERVICE) },
        relationships = emptyList(), statistics = GraphStatistics()
    )

    private val req = "SurveyServiceImpl SurveyDto SurveyDaoImpl 응답자 이메일 마스킹 수정"

    private fun run(stub: Stub, incompleteOnFallback: Boolean, g: ProjectGraph = graph(), requirement: String = req): Pair<RequirementAnalysisResult, String> {
        val chunks = StringBuilder()
        val result = kotlinx.coroutines.runBlocking {
            RequirementAnalysisPipeline(null, stub, incompleteOnFallback).analyze(primaryReq = requirement, secondaryReq = "", projectGraph = g) { chunks.append(it) }
        }
        return result to chunks.toString()
    }

    @Before @After
    fun clean() {
        AnalysisSessionMemory.reset()
        RequirementAnalysisPipeline.lastResult = null
    }

    @Test
    fun i1_탐색기가_도구_없이_끝나면_표와_implement_없이_안내만_낸다() {
        val (r, out) = run(Stub("none", "ok", listOf("SurveyServiceImpl"), listOf("SurveyServiceImpl.java")), true)
        assertTrue(r.incomplete)
        assertEquals(req, r.retryRequirement)
        assertTrue(r.targetFiles.isEmpty())
        assertTrue(out, out.contains("파일 분석을 끝까지 하지 못했습니다"))
        assertFalse(out, out.contains("분석된 대상 파일 목록") || out.contains("| 순번 |") || out.contains("/implement"))
        assertFalse(out, out.contains("Score:") || out.contains("Fallback") || out.contains("Bypass"))
        assertNull("미완료 결과는 마지막 결과로 남기지 않는다(되살리기 대상 아님)", RequirementAnalysisPipeline.lastResult)
    }

    @Test
    fun i2_검증기가_도구_호출_없이_끝나도_안내만_낸다() {
        val (r, out) = run(Stub("seeds", "none", listOf("SurveyServiceImpl"), listOf("SurveyServiceImpl.java")), true)
        assertTrue(r.incomplete)
        assertTrue(r.targetFiles.isEmpty())
        assertTrue(out, out.contains("파일 분석을 끝까지 하지 못했습니다"))
        assertFalse(out, out.contains("| 순번 |") || out.contains("/implement"))
        assertNull(RequirementAnalysisPipeline.lastResult)
    }

    @Test
    fun i3_정상_경로는_결과_표가_그대로이고_안내가_없다() {
        val (r, out) = run(Stub("seeds", "ok", listOf("SurveyServiceImpl", "SurveyDto"), listOf("SurveyServiceImpl.java", "SurveyDto.java")), true)
        assertFalse(r.incomplete)
        assertNull(r.retryRequirement)
        assertTrue(r.targetFiles.any { it.path.endsWith("SurveyServiceImpl.java") })
        assertTrue(out, out.contains("### 🎯 분석된 대상 파일 목록") && out.contains("| 순번 |"))
        assertFalse(out, out.contains("파일 분석을 끝까지 하지 못했습니다"))
    }

    @Test
    fun i4_기본값은_대체_경로_결과를_그대로_쓴다_평가_하네스와_단위_테스트_불변() {
        val (r, out) = run(Stub("none", "ok", listOf("SurveyServiceImpl"), listOf("SurveyServiceImpl.java")), false)
        assertFalse(r.incomplete)
        assertFalse(out, out.contains("파일 분석을 끝까지 하지 못했습니다"))
        // project가 없으면 기본값은 꺼짐
        val chunks = StringBuilder()
        val r2 = kotlinx.coroutines.runBlocking {
            RequirementAnalysisPipeline(Stub("none", "ok", listOf("SurveyServiceImpl"), listOf("SurveyServiceImpl.java"))).analyze(primaryReq = req, secondaryReq = "", projectGraph = graph()) { chunks.append(it) }
        }
        assertFalse(r2.incomplete)
    }

    @Test
    fun i5_시드_선정_결과가_대체_경로_여부를_알린다() {
        val g = graph()
        val fallback = AgenticSeedSelector(Stub("none", "ok", listOf("SurveyServiceImpl"), emptyList())).selectSeeds(req, g)
        assertTrue(fallback.usedFallback)
        val ok = AgenticSeedSelector(Stub("seeds", "ok", listOf("SurveyServiceImpl"), emptyList())).selectSeeds(req, g)
        assertFalse(ok.usedFallback)
        assertTrue(ok.seedClasses.contains("SurveyServiceImpl"))
    }

    @Test
    fun i6_내부_진단_문구는_결과_화면에서_사용자_말로_바뀐다() {
        assertEquals(AnalysisResultWording.NO_REASON, AnalysisResultWording.description("Score: 95.0, Via: Trans [info] [Bypass: Seed Direct Dependency]"))
        assertEquals("응답자 이메일 마스킹 로직 추가", AnalysisResultWording.description("응답자 이메일 마스킹 로직 추가 [Bypass: Resource Reverse Link]"))
        assertEquals("정상 사유", AnalysisResultWording.description("정상 사유"))
        assertEquals("", AnalysisResultWording.reasoning("Fallback: Keyword matching due to LLM failure or timeout"))
        assertEquals("1) 판정 근거", AnalysisResultWording.reasoning("1) 판정 근거"))
    }

    // ── R-EMAIL-PIPE: IDE 응답자 이메일 SR의 정제문 두 개(survey_admin 그래프, 모의 LLM) ──
    private val surveyGraphFile = File("C:/Workspace/HC_card_survey_admin/survey_admin/.meta/project-graph.json")
    private val refinedNew = "관리자 설문 상세 화면용 기존 설문조사 상세 조회 기능의 백엔드를 수정하여, 응답자 이메일 정보를 앞 2자리와 도메인만 보이도록 마스킹 처리한 상태로 함께 반환하도록 하세요."
    private val refinedPrevious = "기존(관리자) 설문조사 상세 조회 기능의 백엔드를 수정하여 응답자 이메일 정보를 마스킹 처리한 상태로 함께 반환하도록 하세요."

    private fun surveyGraph(): ProjectGraph {
        Assume.assumeTrue(surveyGraphFile.isFile)
        return Gson().fromJson(surveyGraphFile.readText(Charsets.UTF_8), ProjectGraph::class.java).normalizeLegacyCollections()
    }

    @Test
    fun r_email_pipe_두_정제문_모두_도구_호출이_성공하면_시드에_설문_서비스_계층이_들어간다() {
        val g = surveyGraph()
        for (sr in listOf(refinedNew, refinedPrevious)) {
            val stub = Stub("replay", "ok", listOf("SurveyServiceImpl", "SurveyDto", "SurveyDaoImpl"), listOf("SurveyServiceImpl.java", "SurveyDto.java", "sql_survey.xml"))
            val seeds = AgenticSeedSelector(stub).selectSeeds(sr, g)
            assertFalse(seeds.usedFallback)
            assertTrue("시드에 설문 서비스 계층: ${seeds.seedClasses}", seeds.seedClasses.contains("SurveyServiceImpl"))
            val (r, out) = run(Stub("replay", "ok", listOf("SurveyServiceImpl", "SurveyDto", "SurveyDaoImpl"), listOf("SurveyServiceImpl.java", "SurveyDto.java", "sql_survey.xml")), true, g, sr)
            assertFalse(out, r.incomplete)
            assertTrue(r.targetFiles.any { it.path.endsWith("SurveyServiceImpl.java") })
            assertTrue(out, out.contains("| 순번 |") && !out.contains("파일 분석을 끝까지 하지 못했습니다"))
        }
    }

    @Test
    fun r_email_pipe_도구_호출_실패_때는_두_정제문_모두_안내와_다시_시도만_나온다() {
        val g = surveyGraph()
        for (sr in listOf(refinedNew, refinedPrevious)) {
            // 탐색기 실패(도구 없이 끝남) → 대체 경로로 CallbackInfoDto 같은 엉뚱한 시드가 잡히던 상황
            val (r1, out1) = run(Stub("none", "ok", listOf("SurveyServiceImpl"), listOf("SurveyServiceImpl.java")), true, g, sr)
            assertTrue(r1.incomplete)
            assertEquals(sr, r1.retryRequirement)
            assertTrue(out1, out1.contains("파일 분석을 끝까지 하지 못했습니다") && !out1.contains("| 순번 |") && !out1.contains("/implement"))
            // 검증기 실패
            val (r2, out2) = run(Stub("replay", "none", listOf("SurveyServiceImpl", "SurveyDto", "SurveyDaoImpl"), emptyList()), true, g, sr)
            assertTrue(r2.incomplete)
            assertTrue(out2, out2.contains("파일 분석을 끝까지 하지 못했습니다") && !out2.contains("| 순번 |"))
        }
    }
}
