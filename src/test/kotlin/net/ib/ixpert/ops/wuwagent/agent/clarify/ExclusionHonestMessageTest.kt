package net.ib.ixpert.ops.wuwagent.agent.clarify

import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.OllamaMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-51 0단계: 시스템이 실제와 다른 말을 하지 않게 한다.
 * ADD_CONSTRAINT 안내문은 모델의 message("제외했습니다" 등)를 쓰지 않고 실제 상태(제약으로 기록, 파일 제외는 미적용)를 말한다.
 * (미확정 제외 경고 문구는 ExclusionDisplayTest.e5_summaryLines에서 고정한다.)
 */
class ExclusionHonestMessageTest {

    private fun llm(json: String) = object : LLMClient {
        override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): OllamaChatResponse =
            OllamaChatResponse(model = "test-model", createdAt = "", message = OllamaMessage("assistant", json), done = true)
        override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
    }

    // 실측(D5, S3, SR-12)에서 모델이 ADD_CONSTRAINT로 내면서 "제외하도록 제약 조건을 등록했습니다"라고 안내한 경우
    private val claimsExclusion = """
        {
          "action": "ADD_CONSTRAINT",
          "constraintKind": "SCOPE_LIMIT",
          "constraintValue": "알림톡 배치 수정 제외",
          "message": "알림톡 배치 관련 파일은 제외하고 SurveyServiceImpl.java만 수정 대상으로 한정하는 제약 조건을 추가했습니다."
        }
    """.trimIndent()

    @Test
    fun h1_addConstraintForAnExclusionSentenceDoesNotClaimExclusion() {
        val r = ClarifyUtteranceTranslator.translate("알림톡 배치는 건드리지 말고 SurveyServiceImpl만 수정할 거야", emptyList(), llm(claimsExclusion))
        assertEquals(UserActionKind.ADD_CONSTRAINT, r.kind)
        val msg = r.clarificationMessage!!
        assertEquals(
            "제약 조건으로 기록했습니다: 알림톡 배치 수정 제외. 이 제약만으로는 파일이 분석에서 제외되지 않습니다. 특정 파일을 빼려면 파일명을 지정해 주세요.",
            msg
        )
        assertFalse("모델이 만든 '제외하고'/'추가했습니다' 문구를 그대로 내보내지 않는다", msg.contains("제외하고") || msg.contains("추가했습니다"))
    }

    @Test
    fun h2_addConstraintWithoutExclusionWordsHasNoExclusionNote() {
        val json = """{"action": "ADD_CONSTRAINT", "constraintKind": "INCLUDE_CHANNEL", "constraintValue": "SMS 발송도 고려", "message": "SMS 채널을 포함하는 제약이 설정되었습니다."}"""
        val r = ClarifyUtteranceTranslator.translate("SMS 발송도 같이 고려해 줘", emptyList(), llm(json))
        assertEquals(UserActionKind.ADD_CONSTRAINT, r.kind)
        assertEquals("제약 조건으로 기록했습니다: SMS 발송도 고려.", r.clarificationMessage)
    }

    @Test
    fun h3_exclusionWordRule() {
        listOf("알림톡 배치는 건드리지 말고", "주소 매퍼 빼줘", "로그인 컨트롤러 제외", "관리 화면은 그대로 두세요", "외부 API는 배제").forEach {
            assertTrue(it, ExclusionWords.containsExclusionWord(it))
        }
        listOf("SMS 발송도 같이 고려해 줘", "목록 조회하는 쪽이에요.", "이제 분석 시작해줘", "").forEach {
            assertFalse(it, ExclusionWords.containsExclusionWord(it))
        }
        assertFalse(ExclusionWords.containsExclusionWord(null))
    }
}
