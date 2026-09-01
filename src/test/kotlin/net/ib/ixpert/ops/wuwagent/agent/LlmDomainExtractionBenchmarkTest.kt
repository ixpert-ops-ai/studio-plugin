package net.ib.ixpert.ops.wuwagent.agent

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.OllamaMessage
import org.junit.Test
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.assertTrue

class LlmDomainExtractionBenchmarkTest {

    class VllmClient : LLMClient {
        private val gson = Gson()
        private val serverUrl = "http://vllm.ixpertops.cloud/v1/chat/completions"
        private val modelName = "Qwen/Qwen3.8-27B-FP8"
        
        override fun chat(
            systemPrompt: String,
            userCode: String,
            maxTokens: Int?,
            onChunk: ((String) -> Unit)?
        ): OllamaChatResponse? {
            val messagesList = listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to userCode)
            )
            val requestBody = mapOf(
                "model" to modelName,
                "messages" to messagesList,
                "stream" to false,
                "temperature" to 0.0,
                "max_tokens" to (maxTokens ?: 50)
            )
            
            val url = URL(serverUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.doOutput = true
            
            try {
                conn.outputStream.use { os ->
                    val input = gson.toJson(requestBody).toByteArray(Charsets.UTF_8)
                    os.write(input, 0, input.size)
                }
                
                if (conn.responseCode in 200..299) {
                    val responseStr = InputStreamReader(conn.inputStream, Charsets.UTF_8).use { it.readText() }
                    val jsonMap = gson.fromJson(responseStr, Map::class.java)
                    val choices = jsonMap["choices"] as? List<Map<String, Any>>
                    val content = (choices?.firstOrNull()?.get("message") as? Map<String, Any>)?.get("content") as? String ?: ""
                    return OllamaChatResponse(
                        model = modelName,
                        createdAt = "",
                        message = OllamaMessage("assistant", content),
                        done = true
                    )
                } else {
                    println("API Error: ${conn.responseCode} - ${InputStreamReader(conn.errorStream, Charsets.UTF_8).use { it.readText() }}")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return null
        }
        override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = null
    }

    private val domainSystemPrompt = """
        당신은 엔터프라이즈 시스템의 도메인 분석 전문가입니다.
        주어진 요구사항(SR)을 읽고, 이 요구사항이 시스템의 어느 핵심 도메인들에 속할 가능성이 있는지 연관된 핵심 도메인 명사(예: 상품, 주문, 클레임, 회원, 전시, 마케팅, 쿠폰, 장바구니 등)를 3~5개 유추하세요.
        반드시 명사 단어들만 쉼표(,)로 구분하여 정확하게 답변해야 하며, 마침표나 추가 설명은 절대 포함하지 마세요. (예: "상품, 주문, 전시")
    """.trimIndent()

    data class TestCase(val type: String, val srText: String, val targetKeyword: String, val description: String)

    @Test
    fun `benchmark LLM domain extraction zero shot`() {
        val client = VllmClient()
        val testCases = listOf(
            TestCase("Easy", "주문 결제 시 무통장 입금 기한 연장", "주문", "명백한 주문 도메인"),
            TestCase("Easy", "마케팅 기획전 쿠폰 발급 제한 조건 변경", "마케팅", "명백한 마케팅 도메인"),
            TestCase("Easy", "전시 메인 배너 롤링 속도 및 순서 설정", "전시", "명백한 전시 도메인"),
            TestCase("Easy", "휴면 회원 전환 전 안내 이메일 발송 배치", "회원", "명백한 회원 도메인"),
            
            TestCase("Trap", "주문제작여부 상품 속성 컬럼 추가", "상품", "주문처럼 보이지만 상품 속성 추가"),
            TestCase("Trap", "메인 화면 상단 장바구니 담긴 상품 개수 표시 배지 노출", "전시", "장바구니나 상품처럼 보이지만 화면 UI(전시)"),
            TestCase("Trap", "상품 배송 지연에 따른 부분 취소 및 환불 금액 재계산", "클레임", "상품/배송처럼 보이지만 환불/취소(클레임) 처리"),
            TestCase("Trap", "특정 임직원 회원의 어드민 내 주문 내역 목록 조회 조건 추가", "주문", "회원처럼 보이지만 주문 내역 조회 로직")
        )

        var totalScore = 0
        var easyScore = 0
        var trapScore = 0

        println("\n=======================================================")
        println("🚀 LLM Domain Extraction Benchmark (Zero-Shot)")
        println("=======================================================\n")

        for (tc in testCases) {
            println("--- [${tc.type}] SR: \"${tc.srText}\" (Target: ${tc.targetKeyword}) ---")
            var matchCount = 0
            val iterations = 5
            
            for (i in 1..iterations) {
                val res = client.chat(domainSystemPrompt, tc.srText, 50, null)
                val rawOutput = res?.message?.content?.trim() ?: "NULL"
                val cleanedOutput = rawOutput.replace(Regex("[^가-힣a-zA-Z]"), "")
                
                val isMatch = cleanedOutput.contains(tc.targetKeyword)
                if (isMatch) matchCount++
                
                println("  Iter $i -> Raw: \"$rawOutput\" => ${if (isMatch) "✅ MATCHES Target (${tc.targetKeyword})" else "❌ MISSES Target (${tc.targetKeyword})"}")
            }
            
            println("=> Result: $matchCount / $iterations\n")
            if (matchCount >= 4) {
                totalScore++
                if (tc.type == "Easy") easyScore++ else trapScore++
            }
        }

        println("=======================================================")
        println("📊 Benchmark Results (Passed if 4/5 or 5/5 matches)")
        println("Easy Cases: $easyScore / 4")
        println("Trap Cases: $trapScore / 4")
        println("Total Score: $totalScore / 8")
        println("=======================================================\n")
    }
}
