package net.ib.ixpert.ops.wuwagent.agent

import com.intellij.openapi.diagnostic.Logger
import net.ib.ixpert.ops.wuwagent.client.LLMClient
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
        listOf("문서 생성", "분석 문서", "doc 생성", "generate doc")                                              to TaskPipeline.DocGenerate,
        listOf("단위 테스트", "unit test", "테스트 리포트", "테스트 코드 생성", "junit 테스트")                         to TaskPipeline.UnitTestReport,
        listOf("코드 개선", "이 코드 개선", "코드를 개선", "리팩토링", "리팩", "refactor", "improve", "코드 최적화", "코드를 최적화") to TaskPipeline.Improve,
        listOf("코드 설명", "이 코드 설명", "코드 분석해줘", "explain this code", "explain the code")              to TaskPipeline.ExplainTask,
        listOf("영향 분석", "영향도", "impact analyze")                                                           to TaskPipeline.Impact,
        listOf("쿼리 검증", "query validation", "sql 검증")                                                       to TaskPipeline.QueryValidation
    )

    // 에디터 컨텍스트(열린 파일 또는 @첨부)가 없으면 Chat으로 폴백해야 하는 파이프라인
    private val requiresEditorContext = setOf(
        TaskPipeline.Improve,
        TaskPipeline.ExplainTask,
        TaskPipeline.Impact,
        TaskPipeline.QueryValidation,
        TaskPipeline.UnitTestReport
    )

    fun analyze(userInput: String, client: LLMClient, hasEditorContext: Boolean = false): TaskPipeline {
        val lower = userInput.lowercase().trim()

        // 1단계: 키워드 빠른 매핑
        for ((keywords, pipeline) in keywordMap) {
            if (keywords.any { lower.contains(it) }) {
                // 코드 처리 파이프라인은 열린 파일 또는 @첨부 파일이 없으면 Chat으로 폴백
                if (pipeline in requiresEditorContext && !hasEditorContext) {
                    logger.info("IntentAnalyzer: 키워드 매핑(${pipeline::class.simpleName}) → 에디터 컨텍스트 없음 → Chat 폴백")
                    return TaskPipeline.Chat
                }
                logger.info("IntentAnalyzer: 키워드 매핑 → ${pipeline::class.simpleName}")
                return pipeline
            }
        }

        // 2단계: LLM 의도 분류
        logger.info("IntentAnalyzer: 키워드 매핑 실패 → LLM 분류 시도")
        return try {
            val systemPrompt = PromptManager.loadPrompt("intent_prompt.txt")
            val response = client.chat(systemPrompt, userInput)
            val intent = response?.message?.content?.trim()?.uppercase() ?: "CHAT"
            logger.info("IntentAnalyzer: LLM 분류 결과 → $intent")

            val llmPipeline = when {
                intent.contains("IMPROVE") -> TaskPipeline.Improve
                intent.contains("EXPLAIN") -> TaskPipeline.ExplainTask
                else                       -> TaskPipeline.Chat
            }
            // 키워드 매핑과 동일하게: 코드 처리 파이프라인은 에디터 컨텍스트 없으면 Chat으로 폴백
            if (llmPipeline in requiresEditorContext && !hasEditorContext) {
                logger.info("IntentAnalyzer: LLM 분류(${llmPipeline::class.simpleName}) → 에디터 컨텍스트 없음 → Chat 폴백")
                TaskPipeline.Chat
            } else {
                llmPipeline
            }
        } catch (e: Exception) {
            logger.warn("IntentAnalyzer: LLM 분류 실패, Chat으로 폴백", e)
            TaskPipeline.Chat
        }
    }
}
