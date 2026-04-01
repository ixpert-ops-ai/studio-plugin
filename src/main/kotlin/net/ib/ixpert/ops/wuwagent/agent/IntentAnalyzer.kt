package net.ib.ixpert.ops.wuwagent.agent

import com.intellij.openapi.diagnostic.Logger
import net.ib.ixpert.ops.wuwagent.client.OllamaClient
import net.ib.ixpert.ops.wuwagent.prompt.PromptManager

/**
 * 사용자의 자연어 입력을 분석하여 실행할 [TaskPipeline]을 결정합니다.
 *
 * 전략:
 * 1. 키워드 기반 빠른 매핑 (LLM 호출 없이 즉시 반환)
 * 2. 키워드 불일치 시 LLM에 단답형 분류 요청 (intent_prompt.txt)
 * 3. 분류 실패 시 Chat(일반 대화)으로 폴백
 */
object IntentAnalyzer {
    private val logger = Logger.getInstance(IntentAnalyzer::class.java)

    // 키워드 → Pipeline 매핑 (순서 중요: 더 구체적인 것 먼저)
    private val keywordMap: List<Pair<List<String>, TaskPipeline>> = listOf(
        listOf("개선", "리팩토링", "리팩", "refactor", "improve", "최적화", "optimize") to TaskPipeline.Improve,
        listOf("리뷰", "review", "검토", "점검", "코드 품질")                          to TaskPipeline.Review,
        listOf("설명", "explain", "이게 뭐야", "어떻게 동작", "알려줘")                 to TaskPipeline.ExplainTask
    )

    fun analyze(userInput: String, client: OllamaClient): TaskPipeline {
        val lower = userInput.lowercase().trim()

        // 1단계: 키워드 빠른 매핑
        for ((keywords, pipeline) in keywordMap) {
            if (keywords.any { lower.contains(it) }) {
                logger.info("IntentAnalyzer: 키워드 매핑 → ${pipeline::class.simpleName}")
                return pipeline
            }
        }

        // 2단계: LLM 의도 분류
        logger.info("IntentAnalyzer: 키워드 매핑 실패 → LLM 분류 시도")
        return try {
            val systemPrompt = PromptManager.loadPrompt("intent_prompt.txt")
            val response = client.callChatApi(systemPrompt, userInput)
            val intent = response?.message?.content?.trim()?.uppercase() ?: "CHAT"
            logger.info("IntentAnalyzer: LLM 분류 결과 → $intent")

            when {
                intent.contains("IMPROVE") -> TaskPipeline.Improve
                intent.contains("REVIEW")  -> TaskPipeline.Review
                intent.contains("EXPLAIN") -> TaskPipeline.ExplainTask
                else                       -> TaskPipeline.Chat
            }
        } catch (e: Exception) {
            logger.warn("IntentAnalyzer: LLM 분류 실패, Chat으로 폴백", e)
            TaskPipeline.Chat
        }
    }
}
