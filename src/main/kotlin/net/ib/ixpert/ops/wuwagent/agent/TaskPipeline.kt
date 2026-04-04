package net.ib.ixpert.ops.wuwagent.agent

import com.intellij.openapi.diagnostic.Logger
import net.ib.ixpert.ops.wuwagent.client.OllamaClient
import net.ib.ixpert.ops.wuwagent.prompt.PromptManager
import net.ib.ixpert.ops.wuwagent.service.EditorApplyService
import net.ib.ixpert.ops.wuwagent.service.EditorContextService
import net.ib.ixpert.ops.wuwagent.service.FileSearchService

/**
 * 사전 정의된 Agent 실행 파이프라인을 표현하는 sealed class.
 *
 * TaskAgent는 IntentAnalyzer가 반환한 TaskPipeline을 기반으로
 * 각 AgentStep을 순차적으로 실행합니다.
 */
sealed class TaskPipeline {
    abstract val steps: List<AgentStep>

    /**
     * 각 Step 실행 결과를 담는 컨테이너.
     *
     * @param originalCode   Diff 비교 기준이 되는 에디터의 기존 코드 (없으면 빈 문자열)
     * @param applyScope     "선택 영역" / "전체 파일" / "" (originalCode 없을 때)
     * @param llmResponse    LLM 원문 전체 응답 (설명 포함)
     * @param extractedCode  LLM 응답에서 추출한 순수 코드 블록
     */
    data class StepResult(
        val originalCode: String,
        val applyScope: String,
        val llmResponse: String,
        val extractedCode: String,
        val isSuccess: Boolean = true
    )

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
        fun executeSync(context: AgentContext, client: OllamaClient): StepResult {
            val systemPrompt = PromptManager.loadPrompt(promptFile)

            var originalCode = ""
            var applyScope   = ""
            var userMessage: String

            val payload = context.payloadText.trim()
            
            // ① 파일명 패턴 추출 (대문자로 시작하는 단어 또는 확장자 포함 단어)
            // (예: "MainActivity", "utils.kt", "ApiService.java")
            val filePattern = Regex("\\b([A-Z][a-zA-Z0-9]*|\\w+\\.(kt|java|xml|gradle))\\b")
            val potentialFileName = filePattern.find(payload)?.value

            if (potentialFileName != null) {
                // [CASE A] 파일명이 명시된 경우 → "파일 검색" 우선, 에디터 폴백 금지
                val matchedFile = FileSearchService.searchFiles(context.project, potentialFileName).firstOrNull()
                
                if (matchedFile != null) {
                    val fileContent = FileSearchService.readFileContent(matchedFile)
                    logger.info("AgentStep[${label}]: 명시적 파일 검색 히트 → ${matchedFile.name}")
                    userMessage = "사용자 요청: $payload\n\n// 파일: ${matchedFile.name}\n```\n$fileContent\n```"
                } else {
                    // ❌ 파일을 찾지 못한 경우 에디터 폴백 없이 에러 반환
                    logger.warn("AgentStep[${label}]: 명시적 파일($potentialFileName)을 찾을 수 없음")
                    return StepResult("", "", "[오류] 프로젝트에서 '$potentialFileName' 파일을 찾을 수 없습니다. 정확한 파일명을 입력해 주세요.", "", isSuccess = false)
                }
            } else {
                // [CASE B] 파일명이 없는 일반 질문 → "에디터" 우선
                if (context.editor != null) {
                    val extraction = EditorContextService.extractCodeWithScope(context.editor, context.project)
                    if (extraction.code.isNotBlank()) {
                        originalCode = extraction.code
                        applyScope   = if (extraction.isSelection) "선택 영역" else "전체 파일"
                    }
                    userMessage = when {
                        originalCode.isNotBlank() && payload.isNotBlank() ->
                            "사용자 요청: $payload\n\n참조 코드:\n```\n$originalCode\n```"
                        originalCode.isNotBlank() -> originalCode
                        else -> payload
                    }
                } else {
                    // 에디터도 없으면 마지막 수단으로 전체 검색 (기존 로직 유지)
                    val firstFile = if (payload.isNotBlank()) {
                        FileSearchService.searchFiles(context.project, payload).firstOrNull()
                    } else null

                    if (firstFile != null) {
                        val fileContent = FileSearchService.readFileContent(firstFile)
                        logger.info("AgentStep[${label}]: 일반 검색 히트 → ${firstFile.name}")
                        userMessage = "사용자 요청: $payload\n\n// 파일: ${firstFile.name}\n```\n$fileContent\n```"
                    } else {
                        userMessage = payload
                    }
                }
            }

            if (userMessage.isBlank()) {
                return StepResult("", "", "[알림] 처리할 코드나 입력이 없습니다.", "", isSuccess = false)
            }

            logger.info("AgentStep[${label}]: LLM 호출 시작 (prompt=$promptFile, scope=${applyScope.ifBlank { "file-search" }})")
            val response      = client.callChatApi(systemPrompt, userMessage)
            val llmResponse   = response?.message?.content ?: "[오류] LLM 응답을 받지 못했습니다."
            val extractedCode = EditorApplyService.extractCodeBlock(llmResponse)

            return StepResult(
                originalCode = originalCode,
                applyScope   = applyScope,
                llmResponse  = llmResponse,
                extractedCode = extractedCode
            )
        }
    }

    // ──────────────────────────────────────────
    //  사전 정의된 파이프라인 목록
    // ──────────────────────────────────────────

    /**
     * 코드 개선 → 영향 분석
     * - 1/2 코드 개선: Diff 대상 (isApplyable = true)
     * - 2/2 영향 분석: 참고용 텍스트 (isApplyable = false)
     */
    object Improve : TaskPipeline() {
        override val steps = listOf(
            AgentStep("1/2 코드 개선", "improve_prompt.txt", isApplyable = true),
            AgentStep("2/2 영향 분석", "impact_prompt.txt",  isApplyable = false)
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
