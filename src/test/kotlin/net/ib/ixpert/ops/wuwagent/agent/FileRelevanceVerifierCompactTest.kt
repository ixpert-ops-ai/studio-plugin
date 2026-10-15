package net.ib.ixpert.ops.wuwagent.agent

import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.model.ChatChoice
import net.ib.ixpert.ops.wuwagent.model.ChatCompletionResponse
import net.ib.ixpert.ops.wuwagent.model.ChatMessage
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.ToolCall
import net.ib.ixpert.ops.wuwagent.model.ToolCallFunction
import net.ib.ixpert.ops.wuwagent.model.ToolDefinition
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.GraphStatistics
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Paths

/**
 * B-39 Stage 3 출력 축소(V2): 번호 기반 requiredFiles/removedFiles 형식, 판정 규칙 R3, 사유 강등 기준 5자, 구 형식 폴백, 안내 줄.
 */
class FileRelevanceVerifierCompactTest {

    private class CapturingClient(private val args: String) : LLMClient {
        var systemPrompt: String? = null
        var userPrompt: String? = null
        var toolNames: List<String> = emptyList()
        var toolProperties: Set<String> = emptySet()

        override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): OllamaChatResponse? = null

        override fun chatWithTools(
            systemPrompt: String, messages: List<ChatMessage>, maxTokens: Int?, tools: List<ToolDefinition>?, toolChoice: Any?, temperature: Double?
        ): ChatCompletionResponse? {
            this.systemPrompt = systemPrompt
            this.userPrompt = messages.last().content
            toolNames = tools.orEmpty().map { it.function.name }
            toolProperties = tools.orEmpty().flatMap { it.function.parameters.properties.keys }.toSet()
            val call = ToolCall(id = "call_1", type = "function", function = ToolCallFunction("submit_verification", args))
            return ChatCompletionResponse(id = "t", choices = listOf(ChatChoice(0, ChatMessage(role = "assistant", content = null, toolCalls = listOf(call)), "stop")))
        }

        override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
    }

    private val graph = ProjectGraph(generatedAt = "now", projectRoot = "/", files = emptyMap(), relationships = emptyList(), statistics = GraphStatistics())

    private fun candidates(n: Int) = (1..n).map { TargetFileSpec(it, "/src/F$it.java", "MODIFY", "test") }

    private fun verify(args: String, n: Int): Pair<FileRelevanceVerifier.VerificationOutput, CapturingClient> {
        val client = CapturingClient(args)
        val out = FileRelevanceVerifier(client, graph, Paths.get("docs")).verify("요구사항", candidates(n))
        return out to client
    }

    // C1 새 형식: 요구 목록은 사유와 함께 유지, 제거 목록은 제거, 둘 다 없는 후보는 유지하고 판정 누락으로 기록. 프롬프트·스키마도 새 형식.
    @Test
    fun c1_compactFormatAppliesRuleR3AndPromptUsesNumbers() {
        val args = """{"requiredFiles":[{"n":1,"reason":"로그인에서 정지 회원 차단 추가"},{"n":2,"reason":"정지 처리 메서드 추가"}],"removedFiles":[3],"reasoning":"1) 가. 2) 나."}"""
        val (out, client) = verify(args, 4)
        assertEquals(listOf("/src/F1.java", "/src/F2.java", "/src/F4.java"), out.files.map { it.path })
        assertEquals("로그인에서 정지 회원 차단 추가", out.files[0].description)
        assertEquals("Stage 3 판정 누락으로 유지", out.files[2].description)
        assertEquals(listOf("/src/F3.java"), out.removed)
        assertEquals(listOf("/src/F4.java"), out.unjudged)
        assertEquals("1) 가. 2) 나.", out.reasoning)
        assertTrue("후보에 번호가 붙어야 함: ${client.userPrompt}", client.userPrompt!!.contains("- [1] /src/F1.java") && client.userPrompt!!.contains("- [4] /src/F4.java"))
        assertTrue(client.systemPrompt!!.contains("removedFiles"))
        assertEquals(listOf("submit_verification"), client.toolNames)
        assertEquals(setOf("requiredFiles", "removedFiles", "reasoning"), client.toolProperties)
    }

    // C2 범위 밖·0 이하 번호는 무시하고, 요구와 제거 양쪽에 있는 후보는 유지한다.
    @Test
    fun c2_outOfRangeIgnoredAndRequiredWinsOverRemoved() {
        val args = """{"requiredFiles":[{"n":1,"reason":"필드 추가가 필요함"},{"n":7,"reason":"범위 밖 번호는 무시됨"}],"removedFiles":[1,9,0,-2],"reasoning":"1) 가."}"""
        val (out, _) = verify(args, 3)
        assertEquals(listOf("/src/F1.java", "/src/F2.java", "/src/F3.java"), out.files.map { it.path })
        assertEquals(emptyList<String>(), out.removed)
        assertEquals(listOf("/src/F2.java", "/src/F3.java"), out.unjudged)
    }

    // C3 사유 강등 기준 5자: 4자 사유는 근거 미제시로 제거, 5자는 유지
    @Test
    fun c3_reasonDemotionThresholdIsFiveCharacters() {
        val args = """{"requiredFiles":[{"n":1,"reason":"네글자요"},{"n":2,"reason":"다섯글자요"}],"removedFiles":[],"reasoning":"1) 가."}"""
        val (out, _) = verify(args, 2)
        assertEquals(listOf("/src/F2.java"), out.files.map { it.path })
        assertEquals(listOf("/src/F1.java"), out.removed)
        assertEquals(emptyList<String>(), out.unjudged)
    }

    // C4 구 형식(fileVerdicts)은 그대로 해석하고, UNNECESSARY 후보를 제거 목록에 기록한다.
    @Test
    fun c4_oldFormatStillWorksAndRecordsRemoved() {
        val args = """{"fileVerdicts":[{"filePath":"/src/F1.java","verdict":"REQUIRED","reason":"충분히 구체적인 수정 내용입니다"},{"filePath":"/src/F2.java","verdict":"UNNECESSARY","reason":"무관"}],"reasoning":"1) 가."}"""
        val (out, _) = verify(args, 2)
        assertEquals(listOf("/src/F1.java"), out.files.map { it.path })
        assertEquals("충분히 구체적인 수정 내용입니다", out.files[0].description)
        assertEquals(listOf("/src/F2.java"), out.removed)
        assertEquals(emptyList<String>(), out.unjudged)
    }

    // C5 안내 줄: 제거 후보는 10개까지 이름을 보이고 나머지는 "외 N개", 사유는 제공하지 않음. 판정 누락 줄. 남은 파일은 제거 목록에서 뺀다.
    @Test
    fun c5_noticeLines() {
        val removed = (1..12).map { "/src/R$it.java" }
        val out = FileRelevanceVerifier.VerificationOutput(files = emptyList(), reasoning = "", removed = removed, unjudged = listOf("/src/U1.java", "/src/U2.java"))
        val lines = out.noticeLines()
        assertEquals(
            "- Stage 3에서 제외된 후보 12개: R1.java, R2.java, R3.java, R4.java, R5.java, R6.java, R7.java, R8.java, R9.java, R10.java 외 2개 (사유: 제공하지 않음)",
            lines[0]
        )
        assertEquals("- 판정 누락으로 유지된 후보: U1.java, U2.java", lines[1])
        val kept = out.noticeLines(keptPaths = setOf("/src/R1.java", "/src/R2.java"))
        assertTrue(kept[0].startsWith("- Stage 3에서 제외된 후보 10개: R3.java"))
        assertEquals(emptyList<String>(), FileRelevanceVerifier.VerificationOutput(emptyList(), "").noticeLines())
    }

    // C6 판정 목록이 모두 비어 있으면 모든 후보를 유지하고 전부 판정 누락으로 기록한다(구 형식의 "목록 없음 → 전부 유지"와 같은 안전 동작).
    @Test
    fun c6_emptyListsKeepEverythingAndMarkAllUnjudged() {
        val (out, _) = verify("""{"requiredFiles":[],"removedFiles":[],"reasoning":"1) 가."}""", 3)
        assertEquals(3, out.files.size)
        assertEquals(emptyList<String>(), out.removed)
        assertEquals(listOf("/src/F1.java", "/src/F2.java", "/src/F3.java"), out.unjudged)
    }
}
