package net.ib.ixpert.ops.wuwagent.agent

import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.model.ChatChoice
import net.ib.ixpert.ops.wuwagent.model.ChatCompletionResponse
import net.ib.ixpert.ops.wuwagent.model.ChatMessage
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.OllamaMessage
import net.ib.ixpert.ops.wuwagent.model.ToolCall
import net.ib.ixpert.ops.wuwagent.model.ToolCallFunction
import net.ib.ixpert.ops.wuwagent.model.ToolDefinition
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.GraphStatistics
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Paths

/** B-79 9: Stage 3 프롬프트에서 후보 번호를 없애고 파일 이름으로 가리키게 한다. 응답 해석은 이름과 이전 번호 방식을 모두 받는다. */
class FileRelevanceVerifierNamesB79Test {

    private val paths = listOf(
        "src/main/java/a/service/SurveyServiceImpl.java",
        "src/main/java/a/dto/ClosingLinkDto.java",
        "src/main/java/a/sql/mysql/sql_user.xml",
        "src/main/java/a/sql/mssql/sql_user.xml",
        "src/main/java/a/dao/AddressDao.java"
    )
    private val specs = paths.mapIndexed { i, p -> TargetFileSpec(i + 1, p, "MODIFY", "") }
    private val graph = ProjectGraph(generatedAt = "now", projectRoot = "/x", files = emptyMap(), relationships = emptyList(), statistics = GraphStatistics())

    /** 마지막 요청(시스템·사용자)을 기록하고 정해진 인자로 submit_verification을 호출하는 가짜 클라이언트. */
    private class Stub(val args: String) : LLMClient {
        var system = ""
        var user = ""
        var toolJson = ""
        override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): OllamaChatResponse =
            OllamaChatResponse(model = "stub", createdAt = "", message = OllamaMessage("assistant", "{}"), done = true)
        override fun chatWithTools(systemPrompt: String, messages: List<ChatMessage>, maxTokens: Int?, tools: List<ToolDefinition>?, toolChoice: Any?, temperature: Double?): ChatCompletionResponse? {
            system = systemPrompt
            user = messages.mapNotNull { it.content }.joinToString("\n")
            toolJson = com.google.gson.Gson().toJson(tools)
            return ChatCompletionResponse("x", listOf(ChatChoice(0, ChatMessage("assistant", null, toolCalls = listOf(ToolCall("t", "function", ToolCallFunction("submit_verification", args)))), "tool_calls")))
        }
        override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
    }

    private fun verify(stub: Stub, names: Boolean = true) =
        FileRelevanceVerifier(stub, graph, Paths.get("nonexistent-docs"), candidateNames = names).verify("요구사항", specs)

    @Test
    fun k1_같은_파일명이_둘_이상이면_그_후보에만_앞_폴더가_붙는다() {
        val keys = FileRelevanceVerifier.candidateKeys(paths)
        assertEquals(listOf("SurveyServiceImpl.java", "ClosingLinkDto.java", "mysql/sql_user.xml", "mssql/sql_user.xml", "AddressDao.java"), keys)
    }

    @Test
    fun k2_프롬프트에_후보_번호가_없고_이름과_경로만_있다() {
        val stub = Stub("{\"requiredFiles\":[{\"file\":\"SurveyServiceImpl.java\",\"reason\":\"이메일 저장 로직 추가\"}],\"removedFiles\":[],\"reasoning\":\"1) 요약\"}")
        verify(stub)
        assertFalse("[n] 번호 표지가 없다: ${stub.user}", Regex("(?m)^- \\[\\d+]").containsMatchIn(stub.user))
        assertFalse(stub.system, stub.system.contains("[번호]"))
        assertTrue(stub.system, stub.system.contains("번호는 없습니다"))
        assertEquals(paths, Stage3Prompt.candidates(stub.user).map { it.second })
        assertEquals(listOf("SurveyServiceImpl.java", "ClosingLinkDto.java", "mysql/sql_user.xml", "mssql/sql_user.xml", "AddressDao.java"), Stage3Prompt.names(stub.user))
        assertTrue("응답 스키마는 이름(file, string)이다: ${stub.toolJson}", stub.toolJson.contains("후보 파일 이름") && !stub.toolJson.contains("후보 번호"))
    }

    @Test
    fun k3_이름으로_온_응답이_후보에_맞게_적용된다() {
        val stub = Stub("{\"requiredFiles\":[{\"file\":\"SurveyServiceImpl.java\",\"reason\":\"이메일 저장 로직 추가\"},{\"file\":\"mysql/sql_user.xml\",\"reason\":\"컬럼 추가 SQL 수정\"}],\"removedFiles\":[\"ClosingLinkDto.java\",\"AddressDao.java\"],\"reasoning\":\"1) 요약\"}")
        val out = verify(stub)
        assertEquals(listOf(paths[0], paths[2], paths[3]), out.files.map { it.path })
        assertEquals("컬럼 추가 SQL 수정", out.files.first { it.path == paths[2] }.description)
        assertEquals(listOf(paths[1], paths[4]), out.removed)
        assertEquals("응답하지 않은 mssql 쪽은 판정 누락으로 유지된다", listOf(paths[3]), out.unjudged)
    }

    @Test
    fun k4_모호하거나_모르는_이름은_추측하지_않고_판정_누락으로_유지한다() {
        // sql_user.xml은 둘이라 앞 폴더 없이는 가리킬 수 없다. Missing.java는 후보에 없다
        val stub = Stub("{\"requiredFiles\":[{\"file\":\"SurveyServiceImpl.java\",\"reason\":\"이메일 저장 로직 추가\"}],\"removedFiles\":[\"sql_user.xml\",\"Missing.java\"],\"reasoning\":\"1) 요약\"}")
        val out = verify(stub)
        assertTrue(out.removed.isEmpty())
        assertEquals(setOf(paths[1], paths[2], paths[3], paths[4]), out.unjudged.toSet())
    }

    @Test
    fun k5_이전_번호_방식_응답도_받는다() {
        val stub = Stub("{\"requiredFiles\":[{\"n\":1,\"reason\":\"이메일 저장 로직 추가\"}],\"removedFiles\":[2,5],\"reasoning\":\"1) 요약\"}")
        val out = verify(stub)
        assertEquals(listOf(paths[1], paths[4]), out.removed)
        assertTrue(out.files.any { it.path == paths[0] })
    }

    @Test
    fun k6_번호_방식_스위치는_이전_프롬프트를_그대로_만든다() {
        val stub = Stub("{\"requiredFiles\":[{\"n\":1,\"reason\":\"이메일 저장 로직 추가\"}],\"removedFiles\":[],\"reasoning\":\"1) 요약\"}")
        verify(stub, names = false)
        assertTrue(Regex("(?m)^- \\[1] src/main/java/a/service/SurveyServiceImpl.java").containsMatchIn(stub.user))
        assertTrue(stub.system.contains("[번호]"))
    }

    @Test
    fun k7_복원_횟수를_센다() {
        val r = FileRelevanceVerifier.substituteCandidateNumbers("1) ClosingLinkDto(2,3)는 무관, 범위 밖 (9)는 그대로", paths)
        assertEquals(1, r.restored)
        assertEquals(1, r.unresolved)
        val none = FileRelevanceVerifier.substituteCandidateNumbers("번호가 없는 요약입니다.", paths)
        assertEquals(0, none.restored + none.unresolved)
    }
}
