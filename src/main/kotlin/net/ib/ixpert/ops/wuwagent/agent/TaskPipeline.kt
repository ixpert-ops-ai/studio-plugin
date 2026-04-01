package net.ib.ixpert.ops.wuwagent.agent

import com.intellij.openapi.diagnostic.Logger
import net.ib.ixpert.ops.wuwagent.client.OllamaClient
import net.ib.ixpert.ops.wuwagent.prompt.PromptManager
import net.ib.ixpert.ops.wuwagent.service.EditorContextService

/**
 * 사전 정의된 Agent 실행 파이프라인을 표현하는 sealed class.
 *
 * TaskAgent는 IntentAnalyzer가 반환한 TaskPipeline을 기반으로
 * 각 AgentStep을 순차적으로 실행합니다.
 */
sealed class TaskPipeline {
    abstract val steps: List<AgentStep>

    /**
     * 파이프라인의 각 실행 단위.
     *
     * @param label      UI에 표시될 단계 이름 (예: "1/3 코드 개선")
     * @param promptFile 사용할 시스템 프롬프트 파일명
     * @param isApplyable 결과에 Apply 버튼을 표시할지 여부
     */
    data class AgentStep(
        val label: String,
        val promptFile: String,
        val isApplyable: Boolean = false
    ) {
        private val logger = Logger.getInstance(AgentStep::class.java)

        /**
         * OllamaClient를 직접 사용해 동기적(blocking)으로 LLM을 호출합니다.
         * TaskAgent의 단일 Backgroundable 블록 안에서 호출됩니다.
         */
        fun executeSync(context: AgentContext, client: OllamaClient): String {
            val systemPrompt = PromptManager.loadPrompt(promptFile)

            // 에디터 코드가 있다면 사용자의 입력(질문/요청)과 함께 묶어서 전송합니다.
            val userMessage = if (context.editor != null) {
                val code = EditorContextService.extractCode(context.editor, context.project)
                if (code.isNotBlank() && context.payloadText.isNotBlank()) {
                    "사용자 요청: ${context.payloadText}\n\n참조 코드:\n```\n$code\n```"
                } else if (code.isNotBlank()) {
                    code
                } else {
                    context.payloadText
                }
            } else {
                context.payloadText
            }

            if (userMessage.isBlank()) {
                return "[알림] 처리할 코드나 입력이 없습니다."
            }

            logger.info("AgentStep[${label}]: LLM 호출 시작 (prompt=$promptFile)")
            val response = client.callChatApi(systemPrompt, userMessage)
            return response?.message?.content ?: "[오류] LLM 응답을 받지 못했습니다."
        }
    }

    // ──────────────────────────────────────────
    //  사전 정의된 파이프라인 목록
    // ──────────────────────────────────────────

    /** 코드 개선 → 영향 분석 → 테스트 생성 */
    object Improve : TaskPipeline() {
        override val steps = listOf(
            AgentStep("1/3 코드 개선",   "improve_prompt.txt", isApplyable = true),
            AgentStep("2/3 영향 분석",   "impact_prompt.txt",  isApplyable = false),
            AgentStep("3/3 테스트 생성", "test_prompt.txt",    isApplyable = true)
        )
    }

    /** 코드 리뷰 → 개선 제안 */
    object Review : TaskPipeline() {
        override val steps = listOf(
            AgentStep("1/2 코드 리뷰",   "review_prompt.txt",  isApplyable = false),
            AgentStep("2/2 개선 제안",   "improve_prompt.txt", isApplyable = true)
        )
    }

    /** 코드 설명 */
    object ExplainTask : TaskPipeline() {
        override val steps = listOf(
            AgentStep("1/1 코드 설명", "explain_prompt.txt", isApplyable = false)
        )
    }

    /** 일반 대화 (폴백) */
    object Chat : TaskPipeline() {
        override val steps = listOf(
            AgentStep("1/1 일반 답변", "chat_prompt.txt", isApplyable = false)
        )
    }
}
