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
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * B-85 4: 결과 화면의 바이트 고정(IDE·LLM 없음, 로컬 survey_admin 그래프가 있을 때만). 모의 LLM으로 만든 결과 마크다운 전체가 고정된 파일(src/test/resources/b85/golden_*.md)과 같아야 한다.
 * PathRoles.classify를 요약보다 먼저 계산하도록 옮기고 요약 검사(G4)를 더한 뒤에도, 모의 요약("요약입니다.")에는 선택 행의 이름이 없으므로 결과 표를 포함한 출력이 바이트까지 같아야 한다.
 * 고정 파일은 -Db85WriteGolden=true로 다시 만들 수 있다(결과 형식을 일부러 바꿀 때만, 이유를 커밋 메시지에 적는다).
 * 아래 recordedTables는 11-03 17:46과 11-11 09:20 IDE 결과 표의 결정적 부분(필수/선택 표시와 사유)을 지금의 판정(PathRoles)이 그대로 내는지 본다.
 */
class ResultGoldenB85Test {
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
    private val refined = "설문 응답 테이블에 이메일 컬럼을 추가하여 수집하고, 관리자 설문 상세 조회 시 응답자 이메일을 앞 2자리와 도메인만 노출되도록 마스킹 처리하여 반환하도록 백엔드를 수정한다."
    private val seeds = listOf("SurveyServiceImpl", "SurveyDaoImpl", "SurveyDto", "sql_survey.xml")

    private fun survey(): ProjectGraph {
        Assume.assumeTrue("로컬 그래프 파일 없음", File(surveyRoot, ".meta/project-graph.json").isFile)
        return Gson().fromJson(File(surveyRoot, ".meta/project-graph.json").readText(Charsets.UTF_8), ProjectGraph::class.java).normalizeLegacyCollections()
    }

    private fun intent(vararg values: String) = ClarifyIntent(
        originalRequirement = original, refinedRequirement = refined, graphHash = "h",
        constraints = values.map { IntentConstraint(ConstraintKind.OTHER, it, rawStatement = it, evidence = it) }
    )

    private fun output(clarify: ClarifyIntent?): String {
        val chunks = StringBuilder()
        kotlinx.coroutines.runBlocking {
            RequirementAnalysisPipeline(null, Stub(seeds), true).analyze(primaryReq = refined, secondaryReq = "", projectGraph = survey(), clarifyIntent = clarify, projectRoot = surveyRoot) { chunks.append(it) }
        }
        return chunks.toString().replace("\r\n", "\n")
    }

    @Before @After
    fun clean() {
        AnalysisSessionMemory.reset()
        RequirementAnalysisPipeline.lastResult = null
    }

    private fun check(name: String, actual: String) {
        val file = File("src/test/resources/b85/golden_$name.md")
        if (System.getProperty("b85WriteGolden") == "true") {
            file.parentFile.mkdirs()
            file.writeText(actual, Charsets.UTF_8)
            return
        }
        assertEquals("결과 화면이 고정된 파일($name)과 같아야 한다", file.readText(Charsets.UTF_8).replace("\r\n", "\n"), actual)
    }

    @Test
    fun g1_noAnswer_warningLineAndTable() = check("no_answer", output(intent()))

    @Test
    fun g2_newDataAnswer_decisionBlockAndTable() = check("new_data", output(intent("응답자 이메일 출처: 설문 응답 테이블에 이메일 컬럼을 새로 추가해서 저장하는 방향으로 해줘")))

    @Test
    fun g3_joinAnswer_infoLineAndTable() = check("join", output(intent("응답자 이메일 출처: 다른 테이블에서 조인해서 가져오세요")))

    // 11-03 17:46, 11-11 09:20 IDE 결과 표의 결정적 부분. 11-11 문장은 앞 80자만 로그에 있어 판정 낱말(마스킹)이 같은 97자 문장으로 대신한다.
    @Test
    fun r1_recordedTablesKeepTheirRoleTextToday() {
        val g = survey()
        val srText = refined
        val root = surveyRoot
        val base = "src/main/java/net/infobank/iss/"
        fun roles(vararg names: String): Map<String, PathRoles.Hint> {
            val paths = names.map { n -> g.files.keys.firstOrNull { it.endsWith("/$n") } ?: (g.resourceNodes.firstOrNull { it.path.endsWith("/$n") }?.path ?: (base + n)) }
            return PathRoles.classify(g, root, paths, srText)
        }
        fun text(m: Map<String, PathRoles.Hint>, name: String): String = PathRoles.describe(m.entries.first { it.key.endsWith("/$name") }.value, "")
        // 17:46: SurveyDaoImpl(선택), SurveyDao(선택), sql_survey.xml(필수), SurveyDto(필수), SurveyServiceImpl(필수)
        val a = roles("SurveyDaoImpl.java", "SurveyDao.java", "sql_survey.xml", "SurveyDto.java", "SurveyServiceImpl.java")
        assertEquals("**선택(경로 통과)** · 기존 메서드(selectResultList, selectSurveyList 등)가 SurveyDto를 그대로 받거나 반환합니다. 시그니처가 바뀌지 않으면 수정할 필요가 없습니다", text(a, "SurveyDaoImpl.java"))
        assertEquals(text(a, "SurveyDaoImpl.java"), text(a, "SurveyDao.java"))
        assertEquals("**필수** · 매퍼 resultType/parameterType이 SurveyDto라서 응답 항목 추가는 SELECT 컬럼 추가가 필요합니다", text(a, "sql_survey.xml"))
        assertEquals("**필수** · 매퍼가 SurveyDto로 결과를 받으므로 응답 항목은 이 DTO의 필드로 추가됩니다", text(a, "SurveyDto.java"))
        assertEquals("**필수** · SR이 값을 가공(마스킹)하므로 그 처리가 들어갈 비즈니스 로직 위치입니다", text(a, "SurveyServiceImpl.java"))
        // 09:20: 같은 낱말이므로 네 행이 같은 결정적 문구(SurveyDaoImpl만 선택)
        val b = roles("SurveyServiceImpl.java", "SurveyDto.java", "sql_survey.xml", "SurveyDaoImpl.java")
        assertEquals(text(a, "SurveyDaoImpl.java"), text(b, "SurveyDaoImpl.java"))
        assertEquals(text(a, "sql_survey.xml"), text(b, "sql_survey.xml"))
    }
}
