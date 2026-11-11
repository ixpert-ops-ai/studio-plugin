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
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * B-85 4 (G4): Stage 3 요약 문장에 선택(경로 통과) 행의 클래스 이름이 직접 나오고 수정 동사가 붙으면 "(<이름> 규칙 판정: 수정 불필요)"를 덧붙인다. 삭제·바꿔쓰기·LLM 호출 없음.
 * 사례: 11-11 09:20 결과의 요약이 "SurveyDaoImpl·sql_survey.xml 쿼리 수정으로 충분"이라 했는데 표의 SurveyDaoImpl 행은 "확인 / 선택(경로 통과) … 수정 불필요"였다.
 */
class SummaryRoleMarkB85Test {
    private val optional = listOf("SurveyDaoImpl")
    private fun mark(text: String, files: List<String> = optional, j: ResponseWritePath.Judgement? = null) =
        ReasonGuard.annotateText(text, ReasonGuard.Context(j, null, files))

    // 11-11 09:20 요약의 실제 두 줄
    private val line1 = "1) 이메일 수집·마스킹은 SurveyDto 필드 추가, SurveyServiceImpl 마스킹, SurveyDaoImpl·sql_survey.xml 쿼리 수정으로 충분."
    private val line2 = "2) 인터페이스(SurveyService/SurveyDao)는 시그니처 변경 없이 구현체만 수정 가능, Address·User·Review·ClosingLink·SurveyQImg는 요구사항과 무관."

    @Test
    fun m1_theRealSummaryGetsTheMarkOnTheSentenceWithTheOptionalName() {
        assertEquals("$line1 (SurveyDaoImpl 규칙 판정: 수정 불필요)", mark(line1))
        // 표에 없는 인터페이스(SurveyService, SurveyDao)만 말한 2번 문장은 그대로다
        assertEquals(line2, mark(line2))
        assertEquals("$line1 (SurveyDaoImpl 규칙 판정: 수정 불필요)\n$line2", mark("$line1\n$line2"))
    }

    @Test
    fun m2_negationNearTheNameLeavesTheSentenceAlone() {
        for (s in listOf("SurveyDaoImpl은 수정할 필요가 없다.", "SurveyDaoImpl은 시그니처 변경이 없으면 수정하지 않는다.", "SurveyDaoImpl 수정은 확인되지 않았다.")) assertEquals(s, mark(s))
    }

    @Test
    fun m3_onlyOptionalNamesWithAModifyVerbAreMarked() {
        assertEquals("SurveyServiceImpl 마스킹 수정.", mark("SurveyServiceImpl 마스킹 수정.")) // 필수 행·표에 없는 이름
        assertEquals("SurveyDaoImpl을 경유한다.", mark("SurveyDaoImpl을 경유한다.")) // 수정 동사 없음
        assertEquals("SurveyDaoImpl에 반영한다.", mark("SurveyDaoImpl에 반영한다.")) // "반영"은 동사 목록에서 제외
        assertEquals("SurveyDaoImplTest를 수정한다.", mark("SurveyDaoImplTest를 수정한다.")) // 접두 일치는 같은 이름이 아니다
        assertEquals("SurveyDaoImpl을 수정한다.", mark("SurveyDaoImpl을 수정한다.", files = emptyList())) // 선택 행이 없으면 아무 일도 없다
    }

    @Test
    fun m4_fileNamesAndSeveralOptionalNames() {
        assertEquals("SurveyDaoImpl.java를 수정한다. (SurveyDaoImpl 규칙 판정: 수정 불필요)", mark("SurveyDaoImpl.java를 수정한다."))
        val two = listOf("SurveyDao", "SurveyDaoImpl")
        assertEquals("SurveyDao와 SurveyDaoImpl을 수정한다. (SurveyDao, SurveyDaoImpl 규칙 판정: 수정 불필요)", mark("SurveyDao와 SurveyDaoImpl을 수정한다.", two))
        // 부정어가 두 이름 모두 앞뒤 25자 안에 있으면(짧은 문장) 둘 다 건너뛴다: 놓치는 쪽으로 틀린다(과표시보다 누락이 낫다는 선택)
        assertEquals("SurveyDao는 변경이 없고 SurveyDaoImpl은 쿼리를 수정한다.", mark("SurveyDao는 변경이 없고 SurveyDaoImpl은 쿼리를 수정한다.", two))
        // 문장 단위라 부정어가 다른 문장에 있으면 상관없다: 앞 문장(SurveyDao는 변경이 없다)은 그대로, 뒤 문장만 표시한다
        val far = "SurveyDao는 변경이 없다. 이번에는 쿼리 구성 전체를 살펴본 뒤 SurveyDaoImpl을 수정한다."
        assertEquals("$far (SurveyDaoImpl 규칙 판정: 수정 불필요)", mark(far, two))
    }

    @Test
    fun m5_applyingTwiceChangesNothingMore_alsoNextToTheStoreMark() {
        val j = ResponseWritePath.Judgement(setOf("ISSW_SURVEY_R"), emptyList(), emptyList(), 1, emptyMap(), listOf("sql_survey.xml"))
        val inputs = listOf(line1, "$line1\n$line2", "SurveyDaoImpl이 응답 저장/조회 SQL을 수정한다. 그 밖의 파일은 그대로다.", "SurveyDaoImpl.java를 수정한다.")
        for (t in inputs) {
            val once = mark(t, optional, j)
            assertEquals(once, mark(once, optional, j))
        }
        // 저장 단정(G2)과 선택 행 표시(G4)가 한 문장에 같이 붙는다
        val both = mark("SurveyDaoImpl이 응답 저장/조회 SQL을 수정한다.", optional, j)
        assertTrue(both, both.contains("(읽은 매퍼 1개(sql_survey.xml)에 응답 테이블") || both.contains("(읽은 범위의 매퍼") || both.contains("저장 주체 미확인"))
        assertTrue(both, both.endsWith("(SurveyDaoImpl 규칙 판정: 수정 불필요)"))
    }

    @Test
    fun m6_tableRowsDoNotGetTheSummaryMark() {
        // 표의 사유에는 G4를 쓰지 않는다(그 행이 이미 자기 규칙 판정을 가진다)
        val row = ReasonGuard.annotateRow("SurveyDaoImpl 쿼리 수정", ReasonGuard.Context(null, PathRoles.Role.OPTIONAL, optional))
        assertEquals("SurveyDaoImpl 쿼리 수정 (규칙 판정: 수정 불필요)", row)
    }

    // ── 실제 파이프라인: 요약에 선택 행 이름과 수정 동사가 나오면 요약 문장과 표(확인 / 규칙 판정)가 같은 판정을 말한다(모의 LLM, 로컬 그래프가 있을 때만) ──
    private class Stub(val summary: String) : LLMClient {
        override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): OllamaChatResponse =
            OllamaChatResponse(model = "stub", createdAt = "", message = OllamaMessage("assistant", "{}"), done = true)

        private fun call(name: String, args: String) =
            ChatCompletionResponse("x", listOf(ChatChoice(0, ChatMessage("assistant", null, toolCalls = listOf(ToolCall("t", "function", ToolCallFunction(name, args)))), "tool_calls")))

        override fun chatWithTools(systemPrompt: String, messages: List<ChatMessage>, maxTokens: Int?, tools: List<ToolDefinition>?, toolChoice: Any?, temperature: Double?): ChatCompletionResponse? {
            if (systemPrompt.contains("당신은 시스템의 소스코드 및 메타그래프를"))
                return call("confirm_final_seeds", "{\"seed_classes\":[\"SurveyServiceImpl\",\"SurveyDaoImpl\",\"SurveyDto\",\"sql_survey.xml\"],\"rationale\":\"테스트\"}")
            if (!systemPrompt.contains("당신은 코드 변경 범위 검증자입니다.")) return null
            val user = messages.mapNotNull { it.content }.joinToString("\n")
            val keep = listOf("SurveyServiceImpl.java", "SurveyDto.java", "sql_survey.xml", "SurveyDaoImpl.java")
            val cands = Stage3Prompt.candidates(user)
            val req = cands.filter { c -> keep.any { c.second.endsWith("/$it") } }.joinToString(",") { "{\"n\":${it.first},\"reason\":\"수정이 필요한 구체적인 사유\"}" }
            val removed = cands.filter { c -> keep.none { c.second.endsWith("/$it") } }.joinToString(",") { it.first.toString() }
            return call("submit_verification", "{\"requiredFiles\":[$req],\"removedFiles\":[$removed],\"reasoning\":\"$summary\"}")
        }

        override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
    }

    @Before @After
    fun clean() {
        AnalysisSessionMemory.reset()
        RequirementAnalysisPipeline.lastResult = null
    }

    @Test
    fun p1_thePipelineSummaryAndTheTableAgree() {
        val root = File("C:/Workspace/HC_card_survey_admin/survey_admin")
        Assume.assumeTrue("로컬 그래프 파일 없음", File(root, ".meta/project-graph.json").isFile)
        val g = Gson().fromJson(File(root, ".meta/project-graph.json").readText(Charsets.UTF_8), ProjectGraph::class.java).normalizeLegacyCollections()
        val sr = "설문 응답 테이블에 이메일 컬럼을 추가하여 수집하고, 관리자 설문 상세 조회 시 응답자 이메일을 앞 2자리와 도메인만 노출되도록 마스킹 처리하여 반환하도록 백엔드를 수정한다."
        val summary = "이메일 수집·마스킹은 SurveyDto 필드 추가, SurveyServiceImpl 마스킹, SurveyDaoImpl·sql_survey.xml 쿼리 수정으로 충분."
        val chunks = StringBuilder()
        kotlinx.coroutines.runBlocking {
            RequirementAnalysisPipeline(null, Stub(summary), true).analyze(primaryReq = sr, secondaryReq = "", projectGraph = g, clarifyIntent = null, projectRoot = root) { chunks.append(it) }
        }
        val out = chunks.toString().replace("\r\n", "\n")
        val summaryLine = out.lines().single { it.startsWith("이메일 수집·마스킹은") }
        assertEquals("$summary (SurveyDaoImpl 규칙 판정: 수정 불필요)", summaryLine)
        // 표: 필수 3행이 먼저, 선택 SurveyDaoImpl은 "확인" 행이고 같은 판정을 말한다
        val rows = out.lines().filter { Regex("^\\| \\d+ \\|").containsMatchIn(it) }
        assertEquals(4, rows.size)
        val daoRow = rows.single { it.contains("SurveyDaoImpl.java") }
        assertTrue(daoRow, daoRow.contains("| 확인 |") && daoRow.contains("**선택(경로 통과)**") && daoRow.contains("(규칙 판정: 수정 불필요)"))
        assertTrue(rows.last().contains("SurveyDaoImpl.java"))
        // 요약의 표시는 선택 행 하나에만 붙는다
        assertEquals(1, summaryLine.split("규칙 판정:").size - 1)
        assertFalse(summaryLine.contains("SurveyDto 규칙 판정") || summaryLine.contains("SurveyServiceImpl 규칙 판정"))
    }
}
