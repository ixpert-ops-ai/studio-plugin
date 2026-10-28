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
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ArchitectureLayer
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FileNode
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.GraphStatistics
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.SpringFileType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * B-75 5: 결과 요약이 "기존 조회/전달된다"고 주장하는 필드를 시드의 업무 코드에서 찾지 못하면 결과 화면에 경고 한 줄을 붙인다.
 * 주장이 없거나, 필드가 실제로 있거나, 범위 파일을 못 읽은 경우에는 출력이 바뀌지 않는다(오탐 방지).
 */
class ClaimedFieldPipelineB75Test {

    private class Stub(val seeds: List<String>, val reasoning: String) : LLMClient {
        override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): OllamaChatResponse =
            OllamaChatResponse(model = "stub", createdAt = "", message = OllamaMessage("assistant", "{}"), done = true)
        private fun call(name: String, args: String) =
            ChatCompletionResponse("x", listOf(ChatChoice(0, ChatMessage("assistant", null, toolCalls = listOf(ToolCall("t", "function", ToolCallFunction(name, args)))), "tool_calls")))
        override fun chatWithTools(systemPrompt: String, messages: List<ChatMessage>, maxTokens: Int?, tools: List<ToolDefinition>?, toolChoice: Any?, temperature: Double?): ChatCompletionResponse? {
            if (systemPrompt.contains("당신은 시스템의 소스코드 및 메타그래프를"))
                return call("confirm_final_seeds", "{\"seed_classes\":[${seeds.joinToString(",") { "\"$it\"" }}],\"rationale\":\"테스트\"}")
            if (!systemPrompt.contains("당신은 코드 변경 범위 검증자입니다.")) return null
            val user = messages.mapNotNull { it.content }.joinToString("\n")
            val cands = Regex("(?m)^- \\[(\\d+)] (\\S+)").findAll(user).map { it.groupValues[1].toInt() }.toList()
            val req = cands.take(2).joinToString(",") { "{\"n\":$it,\"reason\":\"수정이 필요한 구체적인 사유\"}" }
            val removed = cands.drop(2).joinToString(",")
            val reasoningJson = Gson().toJson(reasoning)
            return call("submit_verification", "{\"requiredFiles\":[$req],\"removedFiles\":[$removed],\"reasoning\":$reasoningJson}")
        }
        override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
    }

    private val surveyRoot = File("C:/Workspace/HC_card_survey_admin/survey_admin")
    private val srToday = "관리자 설문 상세 화면용 기존 설문조사 상세 조회 기능에서 응답자 이메일 정보를 앞 2자리와 도메인만 보이도록 마스킹하여 함께 반환하도록 백엔드를 수정한다."
    private val claim = "1) 이메일 마스킹은 Service에서 처리합니다.\n2) DAO, Mapper는 이메일 원본 데이터를 그대로 조회/전달하므로 수정 불필요합니다."

    private fun survey(): ProjectGraph {
        Assume.assumeTrue(File(surveyRoot, ".meta/project-graph.json").isFile)
        return Gson().fromJson(File(surveyRoot, ".meta/project-graph.json").readText(Charsets.UTF_8), ProjectGraph::class.java).normalizeLegacyCollections()
    }

    private fun run(stub: Stub, g: ProjectGraph, sr: String, root: File?): String {
        val chunks = StringBuilder()
        kotlinx.coroutines.runBlocking {
            RequirementAnalysisPipeline(null, stub, true).analyze(primaryReq = sr, secondaryReq = "", projectGraph = g, projectRoot = root) { chunks.append(it) }
        }
        return chunks.toString()
    }

    @Before @After
    fun clean() {
        AnalysisSessionMemory.reset()
        RequirementAnalysisPipeline.lastResult = null
    }

    @Test
    fun c1_오늘_결과처럼_이메일_원본_조회를_주장하는데_설문_코드에_없으면_경고가_한_줄_붙는다() {
        val out = run(Stub(listOf("SurveyServiceImpl", "SurveyDaoImpl", "SurveyDto"), claim), survey(), srToday, surveyRoot)
        val warn = out.lines().filter { it.startsWith("> ⚠️ 확인 안 됨:") }
        assertEquals(out, 1, warn.size)
        assertTrue(warn[0], warn[0].contains("응답자 이메일을 조회하는 코드/SQL을 찾지 못했습니다"))
        assertTrue(warn[0], warn[0].contains("sql_survey.xml:670"))
        assertTrue("요약은 그대로 나온다", out.contains("DAO, Mapper는 이메일 원본 데이터를 그대로 조회/전달하므로 수정 불필요합니다."))
    }

    @Test
    fun c2_요약에_주장_문구가_없어도_SR의_항목을_업무_코드에서_찾지_못하면_경고가_나온다() {
        // B-76 3d: 요약 문구가 아니라 SR에서 항목을 뽑는다(어제 요약의 "그대로 조회"가 아니라 오늘 요약의 "이미 존재" 표현도 잡는다)
        val summary = "1) 응답자 이메일은 기존 설문 응답 데이터에 이미 존재하므로 SurveyDto에 필드 추가와 MyBatis mapper SQL 컬럼 추가만 하면 된다."
        val out = run(Stub(listOf("SurveyServiceImpl", "SurveyDaoImpl", "SurveyDto"), summary), survey(), srToday, surveyRoot)
        assertEquals(out, 1, out.lines().count { it.startsWith("> ⚠️ 확인 안 됨:") })
    }

    @Test
    fun c2b_SR에_데이터_항목이_없으면_경고가_없다() {
        val out = run(Stub(listOf("SurveyServiceImpl", "SurveyDaoImpl", "SurveyDto"), claim), survey(), "설문 상세 조회 기능의 응답 건수 표시를 수정한다", surveyRoot)
        assertFalse(out, out.contains("확인 안 됨"))
    }

    @Test
    fun c3_이메일이_실제로_있는_업무_코드에서는_경고가_없다() {
        val out = run(Stub(listOf("UserServiceImpl", "UserDto", "UserDaoImpl"), claim), survey(), "관리자 계정 이메일 표시 기능을 확인한다", surveyRoot)
        assertFalse(out, out.contains("확인 안 됨"))
    }

    @Test
    fun c4_범위_파일을_읽지_못하면_경고를_내지_않는다() {
        // 소스 루트가 없거나(null) 파일이 없는 합성 그래프에서는 침묵한다
        val g = survey()
        val noRoot = run(Stub(listOf("SurveyServiceImpl", "SurveyDaoImpl", "SurveyDto"), claim), g, srToday, null)
        assertFalse(noRoot, noRoot.contains("확인 안 됨"))
        val synthetic = ProjectGraph(
            generatedAt = "now", projectRoot = "/x",
            files = listOf("SurveyServiceImpl", "SurveyDto").associate { "a/$it.java" to FileNode(path = "a/$it.java", packageName = "a", className = it, fileType = SpringFileType.SERVICE, layer = ArchitectureLayer.SERVICE) },
            relationships = emptyList(), statistics = GraphStatistics()
        )
        val out = run(Stub(listOf("SurveyServiceImpl", "SurveyDto"), claim), synthetic, srToday, File("C:/no/such/root"))
        assertFalse(out, out.contains("확인 안 됨"))
    }
}
