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
        // B-83 4에서 바뀐 기대값: 읽은 범위에 응답 저장 경로가 없으면 "수집·저장 경로가 구현 범위에 포함됩니다" 대신 확인 범위 안에서 발견되지 않았음을 밝힌다
        // B-84 d에서 다시 바뀐 기대값(전: ℹ️ 줄 끝에 "다만 … INSERT/UPDATE는 발견되지 않았습니다 …" / 후: ℹ️ 줄에서는 빠지고 "구현 전 결정할 것" 블록의 한 문장으로 말한다. 뜻은 같고 자리가 옮겨졌다)
        assertTrue(line, line.contains("기존 데이터가 없습니다") && line.contains("새 컬럼 추가는 구현 범위에 포함됩니다"))
        assertFalse(line, line.contains("INSERT/UPDATE") || line.contains("다른 시스템"))
        assertTrue(out, out.lines().contains("> 📌 **구현 전 결정할 것**"))
        assertTrue(out, out.lines().any { it.startsWith("> - 응답 테이블(") && it.contains("에 값을 쓰는 쿼리가 읽은 매퍼") && it.contains("다른 시스템과 연계할지 정해야 합니다") })
        assertFalse(line, line.contains("수집·저장 경로가 구현 범위에 포함됩니다"))
        assertTrue(line, line.contains("사용자 답변"))
        // 수정 사유: SurveyServiceImpl 사유에 "수집·저장 경로 추가"가 붙지 않는다
        assertFalse(out, out.lines().any { it.startsWith("|") && it.contains("수집·저장 경로 추가") })
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

    // B-81 3: "모름" 답은 출처가 정해진 것이 아니므로 ⚠️ 경고가 남고, 출처를 정해 달라는 항목으로 센다(ℹ️로 내려가지 않는다)
    @Test
    fun w3b_모름_답변은_경고를_유지하고_정해_달라는_항목으로_센다() {
        for (a in listOf("잘 모르겠음", "잘 모르겠어요", "모르겠습니다", "몰라요")) {
            val (out, result) = run(intent("응답자 이메일 출처: $a"), refined)
            val warn = out.lines().single { it.startsWith("> ⚠️ 확인 안 됨:") }
            assertTrue("$a: $warn", warn.contains("사용자 답변: 출처 미정(사용자도 모름)"))
            assertEquals("$a: ℹ️ 줄은 없다", 0, out.lines().count { it.startsWith("> ℹ️ 응답자 이메일:") })
            assertEquals("$a: 정해 달라는 항목", listOf("응답자 이메일"), result.confirmItems)
            assertEquals("$a: 새 데이터 항목 아님", emptyList<String>(), result.newDataItems)
        }
    }

    // B-81 8: ℹ️ 줄(출처·수집 경로·DDL)은 줄마다 나눠 보인다(인용문 안에서 줄바꿈만 두면 한 문단으로 이어 붙는다), 출처는 Stage 0 "기록함"과 같은 정리 문장
    @Test
    fun w3c_정보_줄은_빈_인용_줄로_갈라지고_출처는_정리된_문장이다() {
        val answer = "설문 응답 테이블에 이메일 컬럼을 새로 추가해서 저장하는 방향으로 해줘"
        val (out, _) = run(intent("응답자 이메일 출처: $answer"), refined)
        // B-84 d에서 바뀐 기대값(전: 출처·저장 경로·진입점·DDL이 모두 ℹ️ 줄 3줄 이상 / 후: ℹ️ 줄은 출처 한 줄이고, 저장 주체 결정 → 컨트롤러 안내 → DDL 안내는 "구현 전 결정할 것" 블록의 줄 3개)
        val infos = out.lines().filter { it.startsWith("> ℹ️") }
        assertEquals("ℹ️ 줄은 출처 한 줄: $infos", 1, infos.size)
        val lines = out.lines()
        val header = lines.indexOf("> 📌 **구현 전 결정할 것**")
        assertTrue("블록 머리글이 있다", header > 0)
        assertEquals("블록 앞은 빈 인용 줄이다", ">", lines[header - 1])
        val bullets = lines.drop(header + 1).takeWhile { it.startsWith("> - ") }
        assertEquals("저장 주체 결정 → 컨트롤러 안내 → DDL 안내: $bullets", 3, bullets.size)
        assertTrue(bullets[0], bullets[0].contains("정해야 합니다"))
        assertTrue(bullets[1], bullets[1].contains("입력·전달하는 경로"))
        assertTrue(bullets[2], bullets[2].contains("ALTER TABLE"))
        val first = infos.first()
        assertTrue(first, first.contains("사용자 답변(출처: 설문 응답 테이블에 이메일 컬럼 신설·저장)"))
        assertFalse("발화를 통째로 인용하지 않는다: $first", first.contains("해줘") || first.contains("하는 방향으로"))
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
    // B-81 8에서 바뀐 기대값: 고정 문구("설문 관련 코드를 읽어 확인") 대신 실제로 읽은 폴더 이름으로 말한다(시드 이름·개수는 여전히 없다)
    fun w5_사용자_문구에는_시드_이름과_숫자_없이_실제_읽은_폴더_이름으로만_나온다() {
        val g = survey()
        val q = ClaimedFieldCheck.stage0Facts(original, g, surveyRoot).single().question!!
        val (out, _) = run(intent(), refined)
        val warn = out.lines().single { it.startsWith("> ⚠️ 확인 안 됨:") }
        for (t in listOf(q, warn)) {
            assertTrue(t, t.contains("폴더를 읽어 확인"))
            assertFalse("시드 이름이 없다: $t", Regex("Survey\\w*|Alimtalk\\w*|시드").containsMatchIn(t.replace("sql_survey.xml", "")))
            assertFalse("파일 수가 없다: $t", Regex("\\d+개").containsMatchIn(t))
        }
        // B-82 3-2: 단계를 밝히고 패키지 경로(survey/service)로, 업무 계층 순서(service → dao → dto)로 말한다
        assertTrue("Stage 0 질문은 구체화 단계: $q", q.contains("구체화 단계에서 ") && q.contains("survey/service"))
        assertTrue("결과는 분석 단계: $warn", warn.contains("분석 단계에서 survey/service·survey/dao·survey/dto 폴더를 읽어 확인"))
        // 읽은 파일 수는 로그·시험에만 남긴다
        File("build/b79_scope_numbers.txt").writeText("Stage 0 질문: $q\n결과 경고: $warn\n", Charsets.UTF_8)
    }

    // B-79 3c: 새로 저장인데 최종 목록에 요청 진입점(컨트롤러·화면)이 없으면 한 줄 안내, 파일은 추가하지 않는다
    @Test
    fun w6_새로_저장인데_진입점이_없으면_안내_한_줄이_붙고_파일은_늘지_않는다() {
        val (out, result) = run(intent("응답자 이메일 출처: 새 컬럼을 추가해서 저장"), refined)
        // B-84 d에서 바뀐 기대값(전: "> ℹ️ 응답자가 …" 한 줄 / 후: "구현 전 결정할 것" 블록 안의 "> - 응답자가 …" 줄, 문장은 같다)
        val note = out.lines().single { it.startsWith("> - 응답자가 이메일을 입력·전달하는 경로") }
        assertTrue(note, note.contains("분석 대상에 없습니다") && note.contains("파일을 추가하지 않았습니다"))
        assertFalse("목록에 컨트롤러·화면이 들어오지 않았다", ClaimedFieldCheck.hasEntryPoint(result.targetFiles.map { it.path }))
        // 답이 없으면(경고 줄만) 이 안내는 붙지 않는다
        val (out2, _) = run(intent(), refined)
        assertEquals(0, out2.lines().count { it.contains("입력·전달하는 경로") })
    }

    // B-84 c: 결과 표는 필수가 먼저 보이고 번호가 1부터 이어진다(분석 결과의 order 값은 그대로)
    @Test
    fun w6b_표는_필수가_먼저이고_번호가_1부터_이어진다() {
        val (out, result) = run(intent("응답자 이메일 출처: 새 컬럼을 추가해서 저장"), refined)
        val rows = out.lines().filter { Regex("^\\| \\d+ \\|").containsMatchIn(it) }
        assertTrue(out, rows.isNotEmpty())
        assertEquals("번호는 1부터 이어진다", (1..rows.size).toList(), rows.map { it.split("|")[1].trim().toInt() })
        val firstOptional = rows.indexOfFirst { it.contains("**선택(경로 통과)**") }
        if (firstOptional >= 0) assertFalse("선택 행 뒤에 필수 행이 오지 않는다", rows.drop(firstOptional).any { it.contains("**필수**") })
        // 표시 순서만 바꾼다: 파일 집합과 분석 결과(order 값)는 그대로다
        assertEquals(result.targetFiles.map { it.path }.toSet(), rows.map { it.split("|")[2].trim() }.toSet())
    }

    @Test
    fun w7_진입점_판정은_컨트롤러와_화면_파일만_본다() {
        assertTrue(ClaimedFieldCheck.hasEntryPoint(listOf("src/main/java/a/IpsController.java")))
        assertTrue(ClaimedFieldCheck.hasEntryPoint(listOf("a/survey_result.jsp", "b/SurveyDao.java")))
        assertTrue(ClaimedFieldCheck.hasEntryPoint(listOf("src/main/webapp/js/survey.result.js")))
        assertFalse(ClaimedFieldCheck.hasEntryPoint(listOf("a/SurveyServiceImpl.java", "a/SurveyDto.java", "sql/sql_survey.xml")))
    }
}
