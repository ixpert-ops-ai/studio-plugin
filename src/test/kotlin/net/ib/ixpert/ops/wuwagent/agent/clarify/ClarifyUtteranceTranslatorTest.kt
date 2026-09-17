package net.ib.ixpert.ops.wuwagent.agent.clarify

import net.ib.ixpert.ops.wuwagent.agent.clarify.model.*
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.OllamaMessage
import org.junit.Assert.*
import org.junit.Test

/**
 * ClarifyUtteranceTranslator 5대 불변식 단위 테스트:
 * (a) 제외 정확성: 자연어 발화가 현재 후보 ID로 정확히 착지
 * (b) 닫힌 선택 강제 (2층 방어선):
 *     - Layer 1: 프롬프트 지시에 따라 목록 밖 대상 지정 시 UNKNOWN / notInCandidates 반환
 *     - Layer 2: LLM이 지시를 어기고 환각된 targetId를 반환해도 코드가 결정론적으로 NOT_IN_CANDIDATES로 강등
 * (c) 번역 실패 양방향 안전판:
 *     - 실제 Exception 발생 시 UNKNOWN 안전 폴백
 *     - [Error] 에러 응답 객체 반환 시 UNKNOWN 안전 폴백
 *     - 모호한 발화 시 UNKNOWN 안전 착지
 * (d) 제약 조건 및 확정 정상 착지:
 *     - EXCLUDE_EXTERNAL, SCOPE_LIMIT 등 IntentConstraint 착지
 *     - 분석 시작 발화 COMPLETE 착지
 * (e) 추가의 최소형 격리:
 *     - 신규 검색 토큰 INCLUDE_TOKEN 착지
 */
class ClarifyUtteranceTranslatorTest {

    private fun createSampleCandidates(): List<RequirementItem> {
        val ref1 = LinkHint.ExistingRef("src/main/resources/sql/mysql/sql_address.xml", listOf("addressMapper"))
        val ref2 = LinkHint.ExistingRef("src/main/webapp/WEB-INF/views/survey/survey_write.jsp", listOf("surveyWrite"))
        val ref3 = LinkHint.ExistingRef("src/main/java/net/infobank/iss/survey/service/SurveyServiceImpl.java", listOf("SurveyServiceImpl"))

        return listOf(
            RequirementItem(
                id = RequirementItem.deriveId(ref1),
                statement = "[sql_address.xml] 주소 검색 매퍼",
                source = HintSource.SYSTEM_UNCONFIRMED,
                hint = ref1,
                anchorRationale = "주소 연관 매퍼",
                verdict = Verdict.PENDING,
                confidence = ConfidenceBucket.HIGH_CONFIDENCE
            ),
            RequirementItem(
                id = RequirementItem.deriveId(ref2),
                statement = "[survey_write.jsp] 설문 작성 화면",
                source = HintSource.SYSTEM_UNCONFIRMED,
                hint = ref2,
                anchorRationale = "설문 등록 뷰",
                verdict = Verdict.PENDING,
                confidence = ConfidenceBucket.HIGH_CONFIDENCE
            ),
            RequirementItem(
                id = RequirementItem.deriveId(ref3),
                statement = "[SurveyServiceImpl.java] 설문 서비스 구현체",
                source = HintSource.SYSTEM_UNCONFIRMED,
                hint = ref3,
                anchorRationale = "설문 핵심 서비스",
                verdict = Verdict.PENDING,
                confidence = ConfidenceBucket.HIGH_CONFIDENCE
            )
        )
    }

    private fun createMockLlm(responseJson: String): LLMClient {
        return object : LLMClient {
            override fun chat(
                systemPrompt: String,
                userCode: String,
                maxTokens: Int?,
                onChunk: ((String) -> Unit)?
            ): OllamaChatResponse {
                return OllamaChatResponse(
                    model = "test-model",
                    createdAt = "",
                    message = OllamaMessage("assistant", responseJson),
                    done = true
                )
            }
            override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
        }
    }

    /**
     * 불변식 (a) 제외 정확성 검증:
     * "주소 매퍼 빼줘" $\rightarrow$ 현재 후보 목록 내 sql_address.xml의 id로 정확히 착지
     */
    @Test
    fun testInvariantA_ExcludeAccuracy() {
        val candidates = createSampleCandidates()
        val addressItem = candidates.first { it.statement.contains("sql_address.xml") }

        val mockLlm = createMockLlm("""
            {
              "action": "EXCLUDE",
              "targetId": "${addressItem.id}",
              "message": "주소 검색 매퍼(sql_address.xml)를 분석 대상에서 제외합니다."
            }
        """.trimIndent())

        val result = ClarifyUtteranceTranslator.translate(
            utterance = "주소 매퍼 빼줘",
            currentItems = candidates,
            llmClient = mockLlm
        )

        assertEquals(UserActionKind.EXCLUDE, result.kind)
        assertEquals(addressItem.id, result.targetId)
        assertEquals("src/main/resources/sql/mysql/sql_address.xml", result.targetFilePath)
        assertFalse(result.isNotInCandidates)
        assertEquals("주소 매퍼 빼줘", result.rawStatement)
    }

    /**
     * 불변식 (b) 닫힌 선택 강제 - Layer 1 (프롬프트 유도 준수) 검증:
     * 사용자가 목록에 없는 "결제 모듈 빼줘" 발화 시, LLM이 프롬프트 규칙에 따라 UNKNOWN + notInCandidates=true 반환
     */
    @Test
    fun testInvariantB_Layer1_PromptGuidance_NotInCandidates() {
        val candidates = createSampleCandidates()

        val mockLlm = createMockLlm("""
            {
              "action": "UNKNOWN",
              "targetId": null,
              "notInCandidates": true,
              "message": "결제 모듈은 현재 후보 목록에 없습니다."
            }
        """.trimIndent())

        val result = ClarifyUtteranceTranslator.translate(
            utterance = "결제 모듈 빼줘",
            currentItems = candidates,
            llmClient = mockLlm
        )

        assertEquals(UserActionKind.UNKNOWN, result.kind)
        assertTrue("목록 밖 지정 시 isNotInCandidates 플래그가 true여야 함", result.isNotInCandidates)
        assertNull(result.targetId)
    }

    /**
     * 불변식 (b) 닫힌 선택 강제 - Layer 2 (코드 결정론적 재검증 - 핵심 방어선) 검증:
     * 사용자가 "결제 모듈 빼줘"라고 했는데, LLM이 규칙을 어기고 환각된 ID("hallucinated_payment_id")를 EXCLUDE로 반환한 상황.
     * 코드가 items에 해당 ID가 실재하지 않음을 감지하여 즉시 NOT_IN_CANDIDATES로 강등.
     */
    @Test
    fun testInvariantB_Layer2_CodeReverification_HallucinationDowngrade() {
        val candidates = createSampleCandidates()

        // LLM이 지시를 어기고 존재하지 않는 허위 ID를 EXCLUDE로 반환하는 악의적/환각 상황
        val mockHallucinatingLlm = createMockLlm("""
            {
              "action": "EXCLUDE",
              "targetId": "hallucinated_payment_id_9999",
              "message": "결제 모듈을 제외합니다."
            }
        """.trimIndent())

        val result = ClarifyUtteranceTranslator.translate(
            utterance = "결제 모듈 빼줘",
            currentItems = candidates,
            llmClient = mockHallucinatingLlm
        )

        // 2층 코드 방어선 검증: 환각된 ID는 EXCLUDE가 될 수 없고 UNKNOWN + isNotInCandidates=true로 강등됨
        assertEquals("환각된 ID는 코드가 즉시 UNKNOWN으로 강등해야 함", UserActionKind.UNKNOWN, result.kind)
        assertTrue("isNotInCandidates 플래그가 true로 강등되어야 함", result.isNotInCandidates)
        assertNull("환각된 targetId는 수용되어서는 안 됨", result.targetId)
    }

    /**
     * 불변식 (c) 번역 실패 양방향 안전판 검증:
     * 1) 실제 네트워크/타임아웃 Exception 발생 시
     * 2) [Error] 접두어 에러 응답 객체 반환 시
     * 3) 단순 모호 발화 ("음 글쎄요")
     */
    @Test
    fun testInvariantC_FailSafeDualErrorHandling() {
        val candidates = createSampleCandidates()

        // 1. Exception 발생 케이스
        val mockFailingLlm = object : LLMClient {
            override fun chat(
                systemPrompt: String,
                userCode: String,
                maxTokens: Int?,
                onChunk: ((String) -> Unit)?
            ): OllamaChatResponse? {
                throw RuntimeException("Simulated SocketTimeoutException")
            }
            override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
        }

        val resultException = ClarifyUtteranceTranslator.translate(
            utterance = "어떻게 해야 할까",
            currentItems = candidates,
            llmClient = mockFailingLlm
        )
        assertEquals(UserActionKind.UNKNOWN, resultException.kind)
        assertTrue(resultException.clarificationMessage?.contains("예외가 발생했습니다") == true)

        // 2. [Error] 에러 응답 객체 반환 케이스
        val mockErrorObjLlm = object : LLMClient {
            override fun chat(
                systemPrompt: String,
                userCode: String,
                maxTokens: Int?,
                onChunk: ((String) -> Unit)?
            ): OllamaChatResponse {
                return OllamaChatResponse(
                    model = null,
                    createdAt = null,
                    message = OllamaMessage("assistant", "[Error] OpenAI 서버 통신 실패: 504 Gateway Timeout"),
                    done = true
                )
            }
            override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
        }

        val resultErrorObj = ClarifyUtteranceTranslator.translate(
            utterance = "어떻게 해야 할까",
            currentItems = candidates,
            llmClient = mockErrorObjLlm
        )
        assertEquals(UserActionKind.UNKNOWN, resultErrorObj.kind)
        assertTrue(resultErrorObj.clarificationMessage?.contains("다시 말씀해 주세요") == true)

        // 3. 모호 발화 ("음 글쎄")
        val mockAmbiguousLlm = createMockLlm("""
            {
              "action": "UNKNOWN",
              "message": "말씀하신 내용을 정확히 이해하지 못했습니다. 다시 설명해 주세요."
            }
        """.trimIndent())

        val resultAmbiguous = ClarifyUtteranceTranslator.translate(
            utterance = "음 글쎄",
            currentItems = candidates,
            llmClient = mockAmbiguousLlm
        )
        assertEquals(UserActionKind.UNKNOWN, resultAmbiguous.kind)
    }

    /**
     * 불변식 (d) 제약 조건 및 확정 정상 착지 검증
     */
    @Test
    fun testInvariantD_ConstraintAndCompletionGrounding() {
        val candidates = createSampleCandidates()

        // 1. 제약 조건 추가 발화: "외부 API 연동은 안 해"
        val mockConstraintLlm = createMockLlm("""
            {
              "action": "ADD_CONSTRAINT",
              "constraintKind": "EXCLUDE_EXTERNAL",
              "constraintValue": "외부 API 미사용",
              "message": "외부 API 연동 배제 제약이 설정되었습니다."
            }
        """.trimIndent())

        val resultConstraint = ClarifyUtteranceTranslator.translate(
            utterance = "외부 API 연동은 안 해",
            currentItems = candidates,
            llmClient = mockConstraintLlm
        )
        assertEquals(UserActionKind.ADD_CONSTRAINT, resultConstraint.kind)
        assertNotNull(resultConstraint.constraint)
        assertEquals(ConstraintKind.EXCLUDE_EXTERNAL, resultConstraint.constraint?.kind)
        assertEquals("외부 API 미사용", resultConstraint.constraint?.value)
        assertEquals("외부 API 연동은 안 해", resultConstraint.constraint?.rawStatement)

        // 2. 확정 선언 발화: "이제 분석 시작해줘" (규칙 기반 빠른 경로)
        val resultCompletion = ClarifyUtteranceTranslator.translate(
            utterance = "이제 분석 시작해줘",
            currentItems = candidates,
            llmClient = null // 빠른 경로는 LLM 없이도 결정론적 확정
        )
        assertEquals(UserActionKind.COMPLETE, resultCompletion.kind)
    }

    /**
     * 불변식 (e) 추가의 최소형 격리 검증:
     * "알림톡 채널도 같이 봐줘" $\rightarrow$ INCLUDE_TOKEN + tokenValue: "알림톡"
     */
    @Test
    fun testInvariantE_MinimalTokenInclusion() {
        val candidates = createSampleCandidates()

        val mockIncludeLlm = createMockLlm("""
            {
              "action": "INCLUDE_TOKEN",
              "tokenValue": "알림톡",
              "message": "'알림톡' 키워드를 검색 시드에 추가합니다."
            }
        """.trimIndent())

        val result = ClarifyUtteranceTranslator.translate(
            utterance = "알림톡 채널도 같이 봐줘",
            currentItems = candidates,
            llmClient = mockIncludeLlm
        )

        assertEquals(UserActionKind.INCLUDE_TOKEN, result.kind)
        assertEquals("알림톡", result.tokenValue)
        assertEquals("알림톡 채널도 같이 봐줘", result.rawStatement)
    }
}
