package net.ib.ixpert.ops.wuwagent.agent.clarify

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.model.OllamaChatRequest
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.OllamaMessage
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 이 테스트는 로컬 Ollama 서버(http://localhost:11434)가 실행 중이고,
 * qwen3-coder:30b (또는 설정된 모델)이 설치되어 있을 때만 성공합니다.
 * CI 환경 등에서는 실패할 수 있으므로 @Ignore 처리를 권장하거나 수동으로 실행하세요.
 */
@Ignore("로컬 LLM이 필요하므로 기본적으로 무시합니다.")
class RequirementClarifierIntegrationTest {

    private lateinit var clarifier: RequirementClarifier
    
    class TestLocalLlmClient : LLMClient {
        override fun chat(
            systemPrompt: String,
            userCode: String,
            maxTokens: Int?,
            onChunk: ((String) -> Unit)?
        ): OllamaChatResponse? {
            val messages = listOf(
                OllamaMessage(role = "system", content = systemPrompt),
                OllamaMessage(role = "user", content = userCode)
            )
            val req = OllamaChatRequest(model = "qwen3-coder:30b", messages = messages, stream = false)
            val json = Gson().toJson(req)
            
            val url = URL("http://localhost:11434/api/chat")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.write(json.toByteArray())
            
            if (conn.responseCode == 200) {
                return Gson().fromJson(InputStreamReader(conn.inputStream), OllamaChatResponse::class.java)
            }
            return null
        }
        override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = null
    }

    @Before
    fun setup() {
        clarifier = RequirementClarifier(TestLocalLlmClient(), ClarifyPromptBuilder())
    }

    @Test
    fun `시나리오1 - Omission 방지 - 저장 요구 시 조회 응답 포함이 보강된다`() {
        val result = clarifier.clarify(
            "상품등록시 해당 상품의 쇼핑몰 url 링크정보 저장기능 추가",
            FrameworkType.SPRING_BOOT_JPA
        )
        val hasResponseMention = result.enhancedRequirements.any { 
            it.contains("조회") || it.contains("응답") || it.contains("DTO") 
        }
        assertTrue(hasResponseMention)
        // 질문이 아예 없거나, 과도하지 않아야 함
        assertTrue(result.questions.size <= 3)
    }

    @Test
    fun `시나리오2 - Information Gain - 회원 탈퇴 기능 추가 시 양방향 선택지 질문 생성`() {
        val result = clarifier.clarify(
            "회원 탈퇴 기능 추가",
            FrameworkType.SPRING_BOOT_JPA
        )
        assertTrue(result.questions.isNotEmpty())
        val hasDataHandlingQuestion = result.questions.any {
            it.questionText.contains("게시") || it.questionText.contains("데이터") ||
            it.questionText.contains("삭제") || it.questionText.contains("처리")
        }
        assertTrue(hasDataHandlingQuestion)
    }

    @Test
    fun `시나리오3 - Commission 방지 - 게시판 목록 조회 시 무관한 기능 제안 없음`() {
        val result = clarifier.clarify(
            "게시판 목록 조회 기능 구현",
            FrameworkType.SPRING_BOOT_JPA
        )
        val hasUnrelatedSuggestion = result.questions.any {
            it.questionText.contains("댓글") || it.questionText.contains("좋아요") ||
            it.questionText.contains("알림")
        }
        assertFalse(hasUnrelatedSuggestion)
        
        val hasUnrelatedRequirement = result.enhancedRequirements.any {
            it.contains("댓글") || it.contains("좋아요") || it.contains("알림")
        }
        assertFalse(hasUnrelatedRequirement)
    }

    @Test
    fun `시나리오4 - 질문 최대 3개 제한`() {
        val result = clarifier.clarify(
            "회원가입, 로그인, 비밀번호변경, 회원탈퇴, 프로필수정 기능 전부 추가",
            FrameworkType.SPRING_BOOT_JPA
        )
        assertTrue(result.questions.size <= 3)
    }

    @Test
    fun `시나리오5 - JSON 형식 정상 반환`() {
        val result = clarifier.clarify(
            "상품 검색 필터 추가",
            FrameworkType.SPRING_BOOT_JPA
        )
        assertNotNull(result)
        assertTrue(result.enhancedRequirements.isNotEmpty())
    }
}
