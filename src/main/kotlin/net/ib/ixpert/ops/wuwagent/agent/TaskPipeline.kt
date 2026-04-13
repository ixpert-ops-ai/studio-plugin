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
     * @param originalCode   Diff 비교 기준이 되는 기존 코드 (코드 변경이 있을 때만 세팅)
     * @param modifiedCode   Diff 비교 대상이 되는 개선 코드 (코드 변경이 있을 때만 세팅)
     * @param applyScope     "선택 영역" / "전체 파일" / "" (originalCode 없을 때)
     * @param llmResponse    LLM 원문 전체 응답 (설명 포함)
     * @param extractedCode  LLM 응답에서 추출한 순수 코드 블록
     */
    data class StepResult(
        val originalCode: String?,
        val modifiedCode: String?,
        val applyScope: String,
        val llmResponse: String,
        val extractedCode: String,
        val isSuccess: Boolean = true
    )

    /**
     * 파이프라인의 각 실행 단위.
     *
     * @param label         UI에 표시될 단계 이름 (예: "1/3 코드 개선")
     * @param promptFile    사용할 시스템 프롬프트 파일명
     * @param isApplyable   결과에 Apply 버튼을 표시할지 여부
     * @param isImproveStep 코드 변경 diff 검증을 수행할지 여부 (Improve 전용, prompt 이름에 의존하지 않음)
     */
    data class AgentStep(
        val label: String,
        val promptFile: String,
        val isApplyable: Boolean = false,
        val isImproveStep: Boolean = false
    ) {
        private val logger = Logger.getInstance(AgentStep::class.java)

        /**
         * Improve step 전용 userMessage 조립.
         * - "다음 코드를 수정하라." 지시를 최상단에 배치
         * - 이전 단계 분석 결과를 [수정 요구사항]으로 포함
         * - 원본 코드를 [원본 코드] 블록으로 마지막에 배치
         * - 코드 외 출력 금지 경고를 마지막에 추가
         */
        /**
         * 부분 코드 여부 판단.
         * 아래 조건 중 하나라도 만족하면 부분 코드로 간주한다.
         * 1. 길이가 원본의 80% 미만
         * 2. class 키워드가 없는데 fun 키워드는 있음 (함수 단위 응답)
         */
        private fun isPartialCode(extracted: String, original: String): Boolean {
            if (original.isBlank()) return false
            val lengthRatio = extracted.length.toDouble() / original.length
            val hasClass = extracted.contains(Regex("\\bclass\\b|\\bobject\\b|\\binterface\\b"))
            val hasFun   = extracted.contains(Regex("\\bfun\\b"))
            return lengthRatio < 0.8 || (!hasClass && hasFun)
        }

        private fun buildImproveUserMessage(prevAnalysis: String, code: String): String = buildString {
            appendLine("다음 코드를 수정하라.")
            if (prevAnalysis.isNotBlank()) {
                appendLine()
                appendLine("[수정 요구사항]")
                appendLine(prevAnalysis)
            }
            appendLine()
            appendLine("[원본 코드]")
            appendLine("---CODE START---")
            appendLine(code)
            appendLine("---CODE END---")
            appendLine()
            append("IMPORTANT:\n코드 외 텍스트를 출력하면 실패로 간주한다.")
        }

        /**
         * OllamaClient를 직접 사용해 동기적(blocking)으로 LLM을 호출합니다.
         * TaskAgent의 단일 Backgroundable 블록 안에서 호출됩니다.
         *
         * @param previousStepResult 이전 단계의 LLM 응답 텍스트 (2단계 이상 파이프라인에서 문맥 전달용)
         */
        fun executeSync(
            context: AgentContext,
            client: OllamaClient,
            onChunk: ((String) -> Unit)? = null,
            previousStepResult: String? = null
        ): StepResult {
            val systemPrompt = PromptManager.loadPrompt(promptFile)

            var originalCode = ""
            var applyScope   = ""
            var userMessage: String

            val payload = context.payloadText.trim()
            val prevAnalysis = previousStepResult?.takeIf { it.isNotBlank() } ?: ""
            // isImproveStep이 아닌 일반 step에서는 기존 방식의 문맥 블록 사용
            val prevContext = if (prevAnalysis.isNotBlank() && !isImproveStep)
                "\n\n[이전 단계 분석 결과]\n$prevAnalysis"
            else ""

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
                    if (isImproveStep) {
                        originalCode = fileContent
                        applyScope   = matchedFile.name
                        userMessage  = buildImproveUserMessage(prevAnalysis, originalCode)
                    } else {
                        userMessage = "사용자 요청: $payload$prevContext\n\n// 파일: ${matchedFile.name}\n```\n$fileContent\n```"
                    }
                } else {
                    // ❌ 파일을 찾지 못한 경우 에디터 폴백 없이 에러 반환
                    logger.warn("AgentStep[${label}]: 명시적 파일($potentialFileName)을 찾을 수 없음")
                    return StepResult(
                        originalCode = null,
                        modifiedCode = null,
                        applyScope = "",
                        llmResponse = "[오류] 프로젝트에서 '$potentialFileName' 파일을 찾을 수 없습니다. 정확한 파일명을 입력해 주세요.",
                        extractedCode = "",
                        isSuccess = false
                    )
                }
            } else {
                // [CASE B] 파일명이 없는 일반 질문 → "에디터" 우선
                if (context.editor != null) {
                    val extraction = EditorContextService.extractCodeWithScope(context.editor, context.project)
                    if (extraction.code.isNotBlank()) {
                        originalCode = extraction.code
                        applyScope   = if (extraction.isSelection) "선택 영역" else "전체 파일"
                    }
                    userMessage = if (isImproveStep && originalCode.isNotBlank()) {
                        buildImproveUserMessage(prevAnalysis, originalCode)
                    } else {
                        when {
                            originalCode.isNotBlank() && payload.isNotBlank() ->
                                "사용자 요청: $payload$prevContext\n\n원본 코드:\n```\n$originalCode\n```"
                            originalCode.isNotBlank() && prevContext.isNotBlank() ->
                                "$prevContext\n\n원본 코드:\n```\n$originalCode\n```"
                            originalCode.isNotBlank() -> originalCode
                            else -> payload
                        }
                    }
                } else {
                    // 에디터도 없으면 마지막 수단으로 전체 검색 (기존 로직 유지)
                    val firstFile = if (payload.isNotBlank()) {
                        FileSearchService.searchFiles(context.project, payload).firstOrNull()
                    } else null

                    if (firstFile != null) {
                        val fileContent = FileSearchService.readFileContent(firstFile)
                        logger.info("AgentStep[${label}]: 일반 검색 히트 → ${firstFile.name}")
                        userMessage = "사용자 요청: $payload$prevContext\n\n// 파일: ${firstFile.name}\n```\n$fileContent\n```"
                    } else {
                        userMessage = payload + prevContext
                    }
                }
            }

            if (userMessage.isBlank()) {
                return StepResult(
                    originalCode = null,
                    modifiedCode = null,
                    applyScope = "",
                    llmResponse = "[알림] 처리할 코드나 입력이 없습니다.",
                    extractedCode = "",
                    isSuccess = false
                )
            }

            logger.warn("AgentStep[${label}] INPUT: originalCode 길이=${originalCode.length}, userMessage 길이=${userMessage.length}, prevContext 포함=${prevContext.isNotBlank()}")
            logger.info("AgentStep[${label}]: LLM 호출 시작 (prompt=$promptFile, scope=${applyScope.ifBlank { "file-search" }}, stream=${onChunk != null})")
            val response    = client.callChatApiStream(systemPrompt, userMessage, onChunk)
            val llmResponse = response?.message?.content ?: "[오류] LLM 응답을 받지 못했습니다."

            // Ollama 가 타임아웃 등으로 "[Error]..." 반환 시 오류로 간주
            val isErrorResponse = llmResponse.startsWith("[오류]") || llmResponse.startsWith("[Error]")
            var extractedCode = if (isErrorResponse) "" else EditorApplyService.extractCodeBlock(llmResponse)

            logger.warn("AgentStep[${label}] OUTPUT: llmResponse 길이=${llmResponse.length}, extractedCode 길이=${extractedCode.length}")
            logger.warn("AgentStep[${label}] extractedCode 앞 300자: ${extractedCode.take(300)}")

            // ── Improve step 전용: 부분 코드 감지 → 1회 재요청 ──────────────────
            if (isImproveStep && !isErrorResponse && originalCode.isNotBlank() && extractedCode.isNotBlank()) {
                val isPartial = isPartialCode(extractedCode, originalCode)
                logger.warn("AgentStep[${label}] 부분코드 판단: $isPartial (original=${originalCode.length}, extracted=${extractedCode.length}, ratio=${"%.0f".format(extractedCode.length * 100.0 / originalCode.length)}%)")

                if (isPartial) {
                    logger.warn("AgentStep[${label}] ⚠️ 부분 코드 감지 → 전체 코드 강제 재요청 (1회)")
                    val retryMessage = buildString {
                        append(userMessage)
                        appendLine()
                        appendLine()
                        append("반드시 전체 파일 단위의 코드를 반환하라. 일부 함수만 반환하면 실패로 간주한다.")
                    }
                    // 재요청은 스트리밍 없이 블로킹 호출 (UI 청크 중복 방지)
                    val retryResponse = client.callChatApiStream(systemPrompt, retryMessage, null)
                    val retryContent  = retryResponse?.message?.content ?: ""
                    val retryIsError  = retryContent.startsWith("[오류]") || retryContent.startsWith("[Error]")

                    if (!retryIsError && retryContent.isNotBlank()) {
                        val retryExtracted = EditorApplyService.extractCodeBlock(retryContent)
                        val retryIsPartial = isPartialCode(retryExtracted, originalCode)
                        logger.warn("AgentStep[${label}] 재요청 결과: extracted 길이=${retryExtracted.length}, 부분코드=${retryIsPartial}")

                        if (!retryIsPartial && retryExtracted.isNotBlank()) {
                            extractedCode = retryExtracted
                            logger.warn("AgentStep[${label}] ✅ 재요청 결과 채택 (길이=${extractedCode.length})")
                        } else {
                            logger.warn("AgentStep[${label}] ❌ 재요청도 부분 코드 → 원본 결과 유지")
                        }
                    } else {
                        logger.warn("AgentStep[${label}] ❌ 재요청 실패 또는 빈 응답 → 원본 결과 유지")
                    }
                }
            }
            // ─────────────────────────────────────────────────────────────────────

            // 코드 개선(Improve) step 시, 원본과 동일하거나 비정상 응답이면 실패로 처리
            val hasCodeDiff = isApplyable &&
                !isErrorResponse &&
                originalCode.isNotBlank() &&
                extractedCode.isNotBlank() &&
                originalCode != extractedCode

            val isActuallySuccess = when {
                isErrorResponse -> false
                isImproveStep && !hasCodeDiff -> false // 코드 개선 step인데 변경된 게 없으면 실패
                else -> true
            }

            return StepResult(
                originalCode = if (hasCodeDiff) originalCode else null,
                modifiedCode = if (hasCodeDiff) extractedCode else null,
                applyScope = applyScope,
                llmResponse = llmResponse,
                extractedCode = extractedCode,
                isSuccess = isActuallySuccess
            )
        }
    }

    // ──────────────────────────────────────────
    //  사전 정의된 파이프라인 목록
    // ──────────────────────────────────────────

    /**
     * 분석 → 코드 개선
     * - 1/2 개선 분석: 참고용 텍스트 (isApplyable = false)
     * - 2/2 코드 개선: Diff 대상 (isApplyable = true, isImproveStep = true)
     */
    object Improve : TaskPipeline() {
        override val steps = listOf(
            AgentStep("1/2 개선 분석", "improve_analysis_prompt.txt", isApplyable = false),
            AgentStep("2/2 코드 개선", "improve_prompt.txt",          isApplyable = true, isImproveStep = true)
        )
    }

    /** 코드 리뷰 → 개선 제안 */
    object Review : TaskPipeline() {
        override val steps = listOf(
            AgentStep("1/2 코드 리뷰", "review_prompt.txt",         isApplyable = false),
            AgentStep("2/2 개선 제안", "review_improve_prompt.txt", isApplyable = true, isImproveStep = true)
        )
    }

    /**
     * 영향 분석 (단독 파이프라인)
     * - ImpactAnalysisAction 및 직접 파이프라인 실행 시 사용
     */
    object Impact : TaskPipeline() {
        override val steps = listOf(
            AgentStep("1/1 영향 분석", "impact_prompt.txt", isApplyable = false)
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
