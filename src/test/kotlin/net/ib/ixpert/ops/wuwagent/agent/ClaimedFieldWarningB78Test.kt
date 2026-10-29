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
            val cands = Stage3Prompt.candidates(user).map { it.first }
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

    // B-79 3a: 답으로 해소된 항목은 "⚠️ 확인 안 됨" 줄 없이 ℹ️ 줄만 보인다(B-78 w2에서 바뀐 기대값)
    @Test
    fun w2_새로_저장_답변이_있으면_경고_줄_없이_정보_줄만_보이고_출처를_정해_달라는_말은_빠진다() {
        val (out, result) = run(intent("응답자 이메일 출처: 설문 응답 테이블에 이메일 컬럼을 새로 추가해서 저장하는 방향으로 해줘"), refined)
        assertEquals("경고 줄은 없다", 0, out.lines().count { it.startsWith("> ⚠️ 확인 안 됨:") })
        val line = out.lines().single { it.startsWith("> ℹ️ 응답자 이메일:") }
        assertTrue(line, line.contains("기존 데이터가 없습니다") && line.contains("새 컬럼 추가와 수집·저장 경로가 구현 범위에 포함됩니다"))
        assertTrue(line, line.contains("사용자 답변"))
        assertEquals("출처를 정해 달라는 항목은 없다", emptyList<String>(), result.confirmItems)
        assertEquals(listOf("응답자 이메일"), result.newDataItems)
    }

    // B-78 w3에서 바뀐 기대값: 다른 테이블 조인 답도 경고 줄 없이 ℹ️ 줄
    @Test
    fun w3_다른_테이블_조인_답변은_경고_줄_없이_출처_지정됨_정보_줄로_보인다() {
        val (out, result) = run(intent("응답자 이메일 출처: 다른 테이블에서 조인해서 가져오세요"), refined)
        assertEquals(0, out.lines().count { it.startsWith("> ⚠️ 확인 안 됨:") })
        val line = out.lines().single { it.startsWith("> ℹ️ 응답자 이메일:") }
        assertTrue(line, line.contains("출처가 지정되었습니다(조인 대상 확인 필요)"))
        assertFalse(line, line.contains("기존 데이터가 없습니다"))
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
    }

    // B-79 3b: 사용자 문구에서 시드 클래스 이름, 폴더 수, 파일 수를 뺀다(B-78 w5에서 바뀐 기대값). Stage 0 질문과 결과 경고 둘 다.
    @Test
    fun w5_사용자_문구에는_시드_이름과_숫자_없이_설문_관련_코드를_읽어_확인으로만_나온다() {
        val g = survey()
        val q = ClaimedFieldCheck.stage0Facts(original, g, surveyRoot).single().question!!
        val (out, _) = run(intent(), refined)
        val warn = out.lines().single { it.startsWith("> ⚠️ 확인 안 됨:") }
        for (t in listOf(q, warn)) {
            assertTrue(t, t.contains("관련 코드를 읽어 확인"))
            assertFalse("시드 이름이 없다: $t", Regex("Survey\\w*|Alimtalk\\w*|시드").containsMatchIn(t.replace("sql_survey.xml", "")))
            assertFalse("폴더 수·파일 수가 없다: $t", Regex("\\d+개").containsMatchIn(t) || t.contains("폴더"))
        }
        assertTrue("탐색기 시드(Survey*)는 설문으로 말한다: $warn", warn.contains("설문 관련 코드를 읽어 확인"))
        // 읽은 파일 수는 로그·시험에만 남긴다
        File("build/b79_scope_numbers.txt").writeText("Stage 0 질문: $q\n결과 경고: $warn\n", Charsets.UTF_8)
    }

    // B-79 3c: 새로 저장인데 최종 목록에 요청 진입점(컨트롤러·화면)이 없으면 한 줄 안내, 파일은 추가하지 않는다
    @Test
    fun w6_새로_저장인데_진입점이_없으면_안내_한_줄이_붙고_파일은_늘지_않는다() {
        val (out, result) = run(intent("응답자 이메일 출처: 새 컬럼을 추가해서 저장"), refined)
        val note = out.lines().single { it.startsWith("> ℹ️ 응답자가 이메일을 입력·전달하는 경로") }
        assertTrue(note, note.contains("분석 대상에 없습니다") && note.contains("파일을 추가하지 않았습니다"))
        assertFalse("목록에 컨트롤러·화면이 들어오지 않았다", ClaimedFieldCheck.hasEntryPoint(result.targetFiles.map { it.path }))
        // 답이 없으면(경고 줄만) 이 안내는 붙지 않는다
        val (out2, _) = run(intent(), refined)
        assertEquals(0, out2.lines().count { it.contains("입력·전달하는 경로") })
    }

    @Test
    fun w7_진입점_판정은_컨트롤러와_화면_파일만_본다() {
        assertTrue(ClaimedFieldCheck.hasEntryPoint(listOf("src/main/java/a/IpsController.java")))
        assertTrue(ClaimedFieldCheck.hasEntryPoint(listOf("a/survey_result.jsp", "b/SurveyDao.java")))
        assertTrue(ClaimedFieldCheck.hasEntryPoint(listOf("src/main/webapp/js/survey.result.js")))
        assertFalse(ClaimedFieldCheck.hasEntryPoint(listOf("a/SurveyServiceImpl.java", "a/SurveyDto.java", "sql/sql_survey.xml")))
    }
}
