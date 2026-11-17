package net.ib.ixpert.ops.wuwagent.agent.clarify

import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.OllamaMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * B-87 (다)-1b: 정지(/cancel)로 끊긴 호출은 클라이언트가 "__cancelled__"를 돌려준다(OpenAIClient.kt:264-266).
 * refineRequirement는 이 값을 정제문으로 쓰지 않고 원문을 돌려주며 로그 한 줄을 남긴다. 모의 LLM만 쓰므로 서버·IDE가 필요 없다.
 */
class RefineCancelledB87Test {

    private class FakeLlm(val refineAnswer: String) : LLMClient {
        override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): OllamaChatResponse {
            val content = when {
                systemPrompt.contains("소프트웨어 요구사항 정제 전문가") -> refineAnswer
                systemPrompt.contains("ConstraintKind") -> "[]"
                else -> "{}"
            }
            return OllamaChatResponse(model = "fake", createdAt = "", message = OllamaMessage("assistant", content), done = true)
        }

        override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
    }

    private fun refined(answer: String): Pair<String, String> {
        val original = "설문 발송 처리"
        val engine = ConfirmB87Fixtures.engine(FakeLlm(answer))
        val state = engine.initSession(original, null).state.copy(userStatements = listOf("알림톡은 빼줘"))
        val buf = ByteArrayOutputStream()
        val old = System.out
        System.setOut(PrintStream(buf, true, "UTF-8"))
        try {
            val deferred = engine.buildClarifyIntentDeferred(state)
            deferred.complete(deferred.early.intent)
            return deferred.early.intent.refinedRequirement to buf.toString("UTF-8")
        } finally {
            System.setOut(old)
        }
    }

    @Test
    fun cancelledMarkIsNotUsedAsTheRefinedRequirementAndTheOriginalIsReturned() {
        val (text, log) = refined("__cancelled__")
        assertEquals("설문 발송 처리", text)
        assertTrue("로그 한 줄: $log", log.lines().count { it.contains("label=정제문 취소로 끊긴 응답(__cancelled__)") } == 1)
    }

    @Test
    fun anOrdinaryAnswerIsStillUsedAndLeavesNoCancelledLog() {
        val (text, log) = refined("알림톡 배치를 제외하고 설문 발송을 처리한다.")
        assertEquals("알림톡 배치를 제외하고 설문 발송을 처리한다.", text)
        assertFalse(log.contains("__cancelled__"))
    }
}
