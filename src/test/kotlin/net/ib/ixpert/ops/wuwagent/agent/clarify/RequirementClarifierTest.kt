package net.ib.ixpert.ops.wuwagent.agent.clarify

import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.OllamaMessage
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RequirementClarifierTest {

    class FakeLLMClient : LLMClient {
        var responseText = ""
        override fun chat(
            systemPrompt: String,
            userCode: String,
            maxTokens: Int?,
            onChunk: ((String) -> Unit)?
        ): OllamaChatResponse? {
            return OllamaChatResponse(
                model = "fake",
                createdAt = "now",
                message = OllamaMessage(role = "assistant", content = responseText),
                done = true
            )
        }
        override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = null
    }

    private lateinit var mockLlmClient: FakeLLMClient
    private lateinit var promptBuilder: ClarifyPromptBuilder
    private lateinit var clarifier: RequirementClarifier

    @Before
    fun setup() {
        mockLlmClient = FakeLLMClient()
        promptBuilder = ClarifyPromptBuilder()
        clarifier = RequirementClarifier(mockLlmClient, promptBuilder)
    }

    @Test
    fun `프롬프트에 프레임워크 정보가 정확히 포함된다`() {
        val prompt = promptBuilder.buildSystemPrompt(FrameworkType.SPRING_BOOT_JPA)
        assertTrue(prompt.contains(FrameworkType.SPRING_BOOT_JPA.displayName))
        assertFalse(prompt.contains(FrameworkType.SPRING_MVC_MYBATIS.displayName))
    }

    @Test
    fun `정상 JSON 응답을 ClarifyResult로 파싱한다`() {
        val json = """
            {"enhancedRequirements":["항목1","항목2"],
             "questions":[{"id":1,"questionText":"질문?","defaultValue":"Y"}],
             "outOfScopeNotices":[]}
        """.trimIndent()
        
        val result = clarifier.parseResponse(json)
        assertEquals(2, result.enhancedRequirements.size)
        assertEquals("항목1", result.enhancedRequirements[0])
        assertEquals(1, result.questions.size)
        assertEquals("질문?", result.questions[0].questionText)
        assertEquals(0, result.outOfScopeNotices.size)
    }

    @Test
    fun `마크다운 코드블록으로 감싸진 JSON도 파싱한다`() {
        val raw = "```json\n{\"enhancedRequirements\":[\"항목1\"],\"questions\":[],\"outOfScopeNotices\":[]}\n```"
        val result = clarifier.parseResponse(raw)
        assertEquals(1, result.enhancedRequirements.size)
        assertEquals("항목1", result.enhancedRequirements[0])
    }

    @Test
    fun `깨진 JSON은 Exception을 던진다 (clarify에서 잡힘)`() {
        val broken = "이것은 JSON이 아닙니다"
        try {
            clarifier.parseResponse(broken)
            fail("Should throw exception")
        } catch (e: Exception) {
            // Expected
        }
    }

    @Test
    fun `finalize에서 사용자가 제거한 항목이 빠진다`() {
        val clarifyResult = ClarifyResult(
            enhancedRequirements = listOf("URL 저장", "조회 응답 포함"),
            questions = emptyList(),
            outOfScopeNotices = emptyList()
        )
        val userResponse = ClarifyUserResponse(
            answers = emptyMap(),
            removedRequirements = listOf("조회 응답 포함"),
            additionalNotes = null
        )
        val final = clarifier.finalize(clarifyResult, userResponse, "원본")
        assertTrue(final.confirmedItems.contains("URL 저장"))
        assertFalse(final.confirmedItems.contains("조회 응답 포함"))
        assertTrue(final.skippedItems.contains("조회 응답 포함"))
    }

    @Test
    fun `finalize에서 질문 답변 N은 미포함된다`() {
        val clarifyResult = ClarifyResult(
            enhancedRequirements = listOf("URL 저장"),
            questions = listOf(
                ClarifyQuestion(1, "검증 로직 추가할까요?", "Y"),
                ClarifyQuestion(2, "목록조회 포함할까요?", "N")
            ),
            outOfScopeNotices = emptyList()
        )
        val userResponse = ClarifyUserResponse(
            answers = mapOf(1 to "Y", 2 to "n"), // 대소문자 무시 확인
            removedRequirements = emptyList(),
            additionalNotes = null
        )
        val final = clarifier.finalize(clarifyResult, userResponse, "원본")
        assertTrue(final.fullText.contains("검증 로직 추가할까요?"))
        assertFalse(final.fullText.contains("목록조회 포함할까요?"))
    }
}
