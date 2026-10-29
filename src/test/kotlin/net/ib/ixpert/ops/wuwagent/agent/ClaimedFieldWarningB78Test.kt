package net.ib.ixpert.ops.wuwagent.agent

import com.google.gson.Gson
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
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * B-78 2: claimed-field 경고. (a) 출처 답변이 있으면 문구가 바뀐다 (b) 항목은 원문 SR에서 뽑는다("테이블에 이메일" 깨짐 재현)
 * (c) 미해결 경고가 있으면 /implement 안내 문구가 바뀐다 (d) 읽은 파일 수가 무엇을 센 것인지 문구에 밝힌다.
 */
class ClaimedFieldWarningB78Test {

    private class Stub(val seeds: List<String>) : LLMClient {
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
            return call("submit_verification", "{\"requiredFiles\":[$req],\"removedFiles\":[${cands.drop(2).joinToString(",")}],\"reasoning\":\"요약입니다.\"}")
        }
        override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
    }

    private val surveyRoot = File("C:/Workspace/HC_card_survey_admin/survey_admin")
    private val original = "기존 설문조사 상세 조회 기능에 응답자 이메일 정보도 같이 내려주도록 백엔드를 수정해주세요"
    // 2026-10-29 IDE: 정제문(97자)에는 "테이블에 이메일"이 나온다
    private val refined = "설문 응답 테이블에 이메일 컬럼을 추가하여 수집하고, 관리자 설문 상세 조회 시 응답자 이메일을 앞 2자리와 도메인만 보이도록 마스킹하여 반환한다."
    private val seeds = listOf("SurveyServiceImpl", "SurveyDaoImpl", "SurveyDto", "sql_survey.xml")

    private fun survey(): ProjectGraph {
        Assume.assumeTrue(File(surveyRoot, ".meta/project-graph.json").isFile)
        return Gson().fromJson(File(surveyRoot, ".meta/project-graph.json").readText(Charsets.UTF_8), ProjectGraph::class.java).normalizeLegacyCollections()
    }

    private fun intent(vararg constraintValues: String) = ClarifyIntent(
        originalRequirement = original, refinedRequirement = refined, graphHash = "h",
        constraints = constraintValues.map { IntentConstraint(ConstraintKind.OTHER, it, rawStatement = it, evidence = it) }
    )

    private fun run(clarify: ClarifyIntent?, sr: String): Pair<String, RequirementAnalysisResult> {
        val chunks = StringBuilder()
        val result = kotlinx.coroutines.runBlocking {
            RequirementAnalysisPipeline(null, Stub(seeds), true).analyze(primaryReq = sr, secondaryReq = "", projectGraph = survey(), clarifyIntent = clarify, projectRoot = surveyRoot) { chunks.append(it) }
        }
        return chunks.toString() to result
    }

    @Before @After
    fun clean() {
        AnalysisSessionMemory.reset()
        RequirementAnalysisPipeline.lastResult = null
    }

    @Test
    fun w1_정제문으로_뽑으면_테이블에_이메일로_깨지고_원문으로_뽑으면_응답자_이메일이다() {
        val g = survey()
        val seedPaths = g.files.values.filter { it.className in seeds }.map { it.path }
        // 깨짐 재현(수정 전 방식: 정제문에서 항목 추출)
        val fromRefined = ClaimedFieldCheck.check(refined, g, surveyRoot, seedPaths).single()
        assertEquals("테이블에 이메일", fromRefined.label)
        // 수정 후: 파이프라인은 원문에서 뽑는다
        val (out, _) = run(intent(), refined)
        val line = out.lines().single { it.startsWith("> ⚠️ 확인 안 됨:") }
        assertTrue(line, line.contains("응답자 이메일을 조회하는 코드/SQL을 찾지 못했습니다"))
        assertFalse(line, line.contains("테이블에 이메일"))
    }

    @Test
    fun w2_새로_저장_답변이_있으면_기존_데이터_없음으로_바뀌고_출처를_정해_달라는_말은_빠진다() {
        val (out, result) = run(intent("응답자 이메일 출처: 설문 응답 테이블에 이메일 컬럼을 새로 추가해서 저장하는 방향으로 해줘"), refined)
        val line = out.lines().single { it.startsWith("> ⚠️ 확인 안 됨:") }
        assertTrue(line, line.contains("기존 데이터 없음 → 컬럼 추가·수집 경로 필요"))
        assertTrue(line, line.contains("사용자 답변"))
        assertEquals("출처를 정해 달라는 항목은 없다", emptyList<String>(), result.confirmItems)
        assertEquals(listOf("응답자 이메일"), result.newDataItems)
    }

    @Test
    fun w3_다른_테이블_조인_답변은_출처_지정됨으로_보인다() {
        val (out, result) = run(intent("응답자 이메일 출처: 다른 테이블에서 조인해서 가져오세요"), refined)
        val line = out.lines().single { it.startsWith("> ⚠️ 확인 안 됨:") }
        assertTrue(line, line.contains("출처 지정됨(조인 대상 확인 필요)"))
        assertFalse(line, line.contains("기존 데이터 없음"))
        assertEquals(emptyList<String>(), result.newDataItems)
    }

    @Test
    fun w4_답변이_없으면_출처를_정해_달라는_항목이고_implement_안내가_바뀐다() {
        val (_, result) = run(intent(), refined)
        assertEquals(listOf("응답자 이메일"), result.confirmItems)
        val hint = ClaimedFieldCheck.implementHint(result.confirmItems)
        assertTrue(hint, hint.contains("응답자 이메일의 출처가 정해지지 않았습니다") && hint.contains("/implement"))
        assertNotEquals("미해결 경고가 있으면 기본 문구와 다르다", ClaimedFieldCheck.implementHint(emptyList()), hint)
        assertEquals("💡 위 파일들의 구체적인 코드 수정을 원하시면 `/implement`를 입력하세요.", ClaimedFieldCheck.implementHint(emptyList()))
        assertTrue(ClaimedFieldCheck.newDataCaution(listOf("응답자 이메일")).contains("새 컬럼 추가와 수집·저장 경로"))
    }

    @Test
    fun w5_읽은_파일_수는_무엇을_센_것인지_밝히고_Stage0와_결과의_숫자가_다른_이유가_시드_차이다() {
        val g = survey()
        val facts = ClaimedFieldCheck.stage0Facts(original, g, surveyRoot)
        val q = facts.single().question!!
        assertTrue(q, q.contains("폴더의 소스·매퍼") && q.contains("개를 읽어 확인"))
        val (out, _) = run(intent(), refined)
        val line = out.lines().single { it.startsWith("> ⚠️ 확인 안 됨:") }
        assertTrue(line, Regex("시드 \\w+ 등 3개 폴더의 소스·매퍼 \\d+개를 읽어 확인").containsMatchIn(line))
        val n0 = Regex("매퍼 (\\d+)개").find(q)!!.groupValues[1]
        val n1 = Regex("매퍼 (\\d+)개").find(line)!!.groupValues[1]
        File("build/b78_scope_numbers.txt").writeText("Stage 0 질문: $n0 (키워드 시드 ${q.substringAfter("(").substringBefore(" 폴더")})\n결과 경고: $n1 (탐색기 시드 ${seeds.size}개 중 자바 클래스 3개)\n", Charsets.UTF_8)
    }
}
