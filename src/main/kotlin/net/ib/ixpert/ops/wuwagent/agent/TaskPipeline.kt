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
        /** UI 표시 전 전처리가 된 llmResponse와 달리, 파이프라인 내부 전달용 원문 응답.
         *  예: [IMPROVE_TARGETS] 블록이 포함된 Step1 전체 응답 → Step2 parseImproveTargets에서 사용 */
        val rawLlmResponse: String = llmResponse,
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
        val isImproveStep: Boolean = false,
        /** 코드가 없을 때 LLM 호출 없이 반환할 안내 메시지. null이면 기존 폴백 동작 유지. */
        val chatFallbackMessage: String? = null,
        /** true이면 이전 단계 결과(원본·개선 코드·분석)를 프롬프트 변수로 주입해 안정성 평가 수행 */
        val isStabilityStep: Boolean = false
    ) {
        private val logger = Logger.getInstance(AgentStep::class.java)

        // ─────────────────────────────────────────────────────────
        //  @ 파일 첨부 헬퍼
        // ─────────────────────────────────────────────────────────
        private data class AttachedFile(val fileName: String, val content: String)

        /**
         * payload 내 [첨부 파일] 섹션에서 첫 번째 파일의 이름·내용을 추출합니다.
         * buildAttachedFileContext 포맷:
         *   [첨부 파일]
         *   // 파일: {name}
         *   ```
         *   {content}
         *   ```
         */
        private fun extractFirstAttachedFile(payload: String): AttachedFile? {
            if (!payload.contains("[첨부 파일]")) return null
            val regex = Regex("""// 파일: ([^\n]+)\n```[^\n]*\n([\s\S]*?)\n```""")
            val match = regex.find(payload) ?: return null
            val fileName = match.groupValues[1].trim()
            val content  = match.groupValues[2].trim()
            return if (fileName.isNotBlank() && content.isNotBlank()) AttachedFile(fileName, content) else null
        }

        /**
         * SEARCH 블록 앞뒤 빈 줄 정규화 (들여쓰기는 유지, 상하단 공백 줄만 제거).
         */
        private fun normalizeSrBlocks(response: String): String {
            val pattern = Regex(
                """<<<<<<< SEARCH\r?\n(.*?)\r?\n=======\r?\n(.*?)\r?\n>>>>>>> REPLACE""",
                setOf(RegexOption.DOT_MATCHES_ALL)
            )
            return pattern.replace(response) { m ->
                val search  = m.groupValues[1].trimStart('\n', '\r').trimEnd('\n', '\r')
                val replace = m.groupValues[2]
                "<<<<<<< SEARCH\n$search\n=======\n$replace\n>>>>>>> REPLACE"
            }
        }

        /**
         * 함수 시그니처 첫 줄로 위치를 찾아 블록 전체를 교체하는 부분 매칭 fallback.
         */
        private fun partialMatchApply(originalCode: String, llmResponse: String): String? {
            val pattern = Regex(
                """<<<<<<< SEARCH\r?\n(.*?)\r?\n=======\r?\n(.*?)\r?\n>>>>>>> REPLACE""",
                setOf(RegexOption.DOT_MATCHES_ALL)
            )
            val blocks = pattern.findAll(llmResponse).toList()
            if (blocks.isEmpty()) return null

            var result = originalCode
            var anyApplied = false

            for (match in blocks) {
                val searchBlock  = match.groupValues[1].trim()
                val replaceBlock = match.groupValues[2]
                val sigLine = searchBlock.lines().firstOrNull { it.isNotBlank() }?.trimEnd() ?: continue

                val lines  = result.lines()
                val sigIdx = lines.indexOfFirst { it.trimEnd() == sigLine }
                if (sigIdx == -1) {
                    logger.warn("partialMatchApply: 시그니처 매칭 실패 → '${sigLine.take(80)}'")
                    continue
                }

                var blockStart = sigIdx
                while (blockStart > 0 && lines[blockStart - 1].trim().startsWith("@")) blockStart--

                var depth = 0; var braceFound = false; var blockEnd = sigIdx
                outer@ for (i in sigIdx until lines.size) {
                    for (ch in lines[i]) {
                        when (ch) {
                            '{' -> { depth++; braceFound = true }
                            '}' -> { depth--; if (braceFound && depth == 0) { blockEnd = i; break@outer } }
                        }
                    }
                }

                result = (lines.subList(0, blockStart) + replaceBlock.lines() +
                          lines.subList(blockEnd + 1, lines.size)).joinToString("\n")
                anyApplied = true
                logger.info("partialMatchApply: 부분 매칭 성공 → '${sigLine.take(80)}'")
            }
            return if (anyApplied) result else null
        }

        /**
         * SEARCH/REPLACE 병합 3단계 전략:
         * 1) 직접 매칭 → 2) SEARCH 블록 빈 줄 정규화 후 재시도 → 3) 시그니처 첫 줄 부분 매칭
         */
        private fun flexibleApplySearchReplace(originalCode: String, llmResponse: String): String? {
            EditorApplyService.applySearchReplace(originalCode, llmResponse)?.let { return it }

            val normalized = normalizeSrBlocks(llmResponse)
            if (normalized != llmResponse) {
                EditorApplyService.applySearchReplace(originalCode, normalized)?.let {
                    logger.info("AgentStep[${label}] Search/Replace 정규화 후 매칭 성공")
                    return it
                }
            }

            partialMatchApply(originalCode, llmResponse)?.let {
                logger.info("AgentStep[${label}] Search/Replace 부분 매칭 성공")
                return it
            }

            return null
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
            previousStepResult: String? = null,
            allPreviousResults: List<StepResult> = emptyList()
        ): StepResult {
            // ─ 안정성 평가 Step (isStabilityStep=true) ─────────────────────────
            if (isStabilityStep) {
                val originalCode = if (context.editor != null)
                    EditorContextService.extractCodeWithScope(context.editor, context.project).code
                else ""

                val improvedCode = allPreviousResults.getOrNull(1)
                    ?.extractedCode?.takeIf { it.isNotBlank() }
                    ?: allPreviousResults.getOrNull(1)?.rawLlmResponse
                    ?: ""

                val analysisResult = allPreviousResults.getOrNull(0)?.rawLlmResponse ?: ""

                val language = context.editor?.virtualFile?.extension?.uppercase() ?: "Unknown"

                val stabilitySystemPrompt = PromptManager.loadPromptWithVars(
                    promptFile, mapOf(
                        "LANGUAGE"        to language,
                        "ORIGINAL_CODE"   to originalCode,
                        "IMPROVED_CODE"   to improvedCode,
                        "ANALYSIS_RESULT" to analysisResult
                    )
                )
                val userMessage = "위 원본 코드와 개선된 코드를 비교하여 안정성을 평가하세요."

                logger.info("AgentStep[${label}]: 안정성 평가 LLM 호출 시작")
                val response = client.callChatApiStream(stabilitySystemPrompt, userMessage, onChunk)
                val rawLlmResponse = response?.message?.content ?: "[오류] LLM 응답을 받지 못했습니다."
                val llmResponse = rawLlmResponse.trimEnd()
                val isError = llmResponse.startsWith("[오류]") || llmResponse.startsWith("[Error]")

                return StepResult(
                    originalCode  = null,
                    modifiedCode  = null,
                    applyScope    = "",
                    llmResponse   = llmResponse,
                    rawLlmResponse = rawLlmResponse,
                    extractedCode = "",
                    isSuccess     = !isError
                )
            }

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

            // ─ 1순위: @ 파일 첨부 ([첨부 파일] 섹션 감지) ─────────────────────────
            val attachedFile = extractFirstAttachedFile(payload)
            val userRequest  = if (attachedFile != null)
                payload.substringBefore("[첨부 파일]").trim().ifBlank { "코드를 분석해줘" }
            else
                payload

            if (attachedFile != null) {
                originalCode = attachedFile.content
                applyScope   = attachedFile.fileName
                logger.info("AgentStep[${label}]: @ 파일 첨부 감지 → ${attachedFile.fileName}")
                userMessage = if (isImproveStep) {
                    buildString {
                        appendLine("다음 코드를 수정하라.")
                        appendLine()
                        appendLine("[원본 코드]")
                        appendLine("---CODE START---")
                        appendLine(originalCode)
                        appendLine("---CODE END---")
                        appendLine()
                        append("IMPORTANT:\n코드 외 텍스트를 출력하면 실패로 간주한다.")
                    }
                } else {
                    "사용자 요청: $userRequest$prevContext\n\n// 파일: ${attachedFile.fileName}\n```\n${attachedFile.content}\n```"
                }

            } else if (context.editor != null) {
                // ─ 2순위(선택) / 3순위(전체): 에디터 기반 ────────────────────────
                val extraction = EditorContextService.extractCodeWithScope(context.editor, context.project)
                if (extraction.code.isNotBlank()) {
                    originalCode = extraction.code
                    applyScope   = if (extraction.isSelection) "선택 영역" else "전체 파일"
                }
                userMessage = if (isImproveStep && originalCode.isNotBlank()) {
                    buildString {
                        appendLine("다음 코드를 수정하라.")
                        appendLine()
                        appendLine("[원본 코드]")
                        appendLine("---CODE START---")
                        appendLine(originalCode)
                        appendLine("---CODE END---")
                        appendLine()
                        append("IMPORTANT:\n코드 외 텍스트를 출력하면 실패로 간주한다.")
                    }
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
                // ─ 4순위: 에디터도 없음 ───────────────────────────────────────────
                if (chatFallbackMessage != null) {
                    return StepResult(
                        originalCode  = null,
                        modifiedCode  = null,
                        applyScope    = "",
                        llmResponse   = chatFallbackMessage,
                        extractedCode = "",
                        isSuccess     = false
                    )
                }
                // chatFallbackMessage 미설정 시 기존 폴백: 파일명 패턴 검색 → 전체 텍스트 검색
                val filePattern = Regex(
                    "\\b([A-Z][a-zA-Z0-9]*|\\w+\\.(kt|java|xml|gradle|ts|tsx|js|jsx|py|sql|json|yaml|yml|html|css|sh|md))\\b"
                )
                val potentialFileName = filePattern.find(payload)?.value

                if (potentialFileName != null) {
                    val matchedFile = FileSearchService.searchFiles(context.project, potentialFileName).firstOrNull()
                    if (matchedFile != null) {
                        val fileContent = FileSearchService.readFileContent(matchedFile)
                        logger.info("AgentStep[${label}]: 파일 검색 히트 → ${matchedFile.name}")
                        originalCode = fileContent
                        applyScope   = matchedFile.name
                        userMessage  = if (isImproveStep) {
                            buildString {
                                appendLine("다음 코드를 수정하라.")
                                appendLine()
                                appendLine("[원본 코드]")
                                appendLine("---CODE START---")
                                appendLine(originalCode)
                                appendLine("---CODE END---")
                                appendLine()
                                append("IMPORTANT:\n코드 외 텍스트를 출력하면 실패로 간주한다.")
                            }
                        } else {
                            "사용자 요청: $payload$prevContext\n\n// 파일: ${matchedFile.name}\n```\n$fileContent\n```"
                        }
                    } else {
                        logger.warn("AgentStep[${label}]: 파일($potentialFileName)을 찾을 수 없음")
                        return StepResult(
                            originalCode  = null,
                            modifiedCode  = null,
                            applyScope    = "",
                            llmResponse   = "[오류] 프로젝트에서 '$potentialFileName' 파일을 찾을 수 없습니다. 정확한 파일명을 입력해 주세요.",
                            extractedCode = "",
                            isSuccess     = false
                        )
                    }
                } else {
                    val firstFile = if (payload.isNotBlank())
                        FileSearchService.searchFiles(context.project, payload).firstOrNull()
                    else null

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

            val filteredOnChunk = onChunk

            val response    = client.callChatApiStream(systemPrompt, userMessage, filteredOnChunk)
            val rawLlmResponse = response?.message?.content ?: "[오류] LLM 응답을 받지 못했습니다."

            val llmResponse = rawLlmResponse.trimEnd()

            // Ollama 가 타임아웃 등으로 "[Error]..." 반환 시 오류로 간주
            val isErrorResponse = llmResponse.startsWith("[오류]") || llmResponse.startsWith("[Error]")
            var extractedCode = if (isErrorResponse) {
                ""
            } else if (isImproveStep && originalCode.isNotBlank()) {
                val merged = flexibleApplySearchReplace(originalCode, llmResponse)
                if (merged != null) {
                    logger.info("AgentStep[${label}] Search/Replace 병합 성공 (길이=${merged.length})")
                    merged
                } else {
                    logger.warn("AgentStep[${label}] Search/Replace 파싱 실패 → fallback")
                    EditorApplyService.extractCodeBlock(llmResponse)
                }
            } else {
                EditorApplyService.extractCodeBlock(llmResponse)
            }

            logger.warn("AgentStep[${label}] OUTPUT: llmResponse 길이=${llmResponse.length}, extractedCode 길이=${extractedCode.length}")

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
                rawLlmResponse = rawLlmResponse,
                extractedCode = extractedCode,
                isSuccess = isActuallySuccess
            )
        }
    }

    // ──────────────────────────────────────────
    //  사전 정의된 파이프라인 목록
    // ──────────────────────────────────────────

    /**
     * 분석 → 코드 개선 → 안정성 평가
     * - 1/3 개선 분석: 참고용 텍스트 (isApplyable = false)
     * - 2/3 코드 개선: 개선 코드 출력 (isApplyable = false)
     * - 3/3 안정성 평가: 원본/개선 코드 비교 후 위험도 평가 (isStabilityStep = true)
     */
    object Improve : TaskPipeline() {
        override val steps = listOf(
            AgentStep(
                label               = "1/3 개선 분석",
                promptFile          = "improve_analysis_prompt.txt",
                isApplyable         = false,
                chatFallbackMessage = "개선할 코드가 없습니다. 파일을 열거나 @파일을 선택해주세요."
            ),
            AgentStep("2/3 코드 개선", "improve_prompt.txt", isApplyable = false),
            AgentStep("3/3 안정성 평가", "stability_check_prompt.txt", isApplyable = false, isStabilityStep = true)
        )
    }

    /** 코드 리뷰 → 개선 제안 */
    object Review : TaskPipeline() {
        override val steps = listOf(
            AgentStep(
                label              = "1/2 코드 리뷰",
                promptFile         = "review_prompt.txt",
                isApplyable        = false,
                chatFallbackMessage = "리뷰할 코드가 없습니다. 파일을 열거나 @파일을 선택해주세요."
            ),
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

    /** 쿼리 / SQL 검증 (QueryValidationAgent 직접 호출) */
    object QueryValidation : TaskPipeline() {
        override val steps = listOf(
            AgentStep("1/1 쿼리 검증", "query_validation_prompt.txt", isApplyable = false)
        )
    }

    /** 테스트 코드 생성 (GenerateTestAgent 직접 호출) */
    object GenerateTest : TaskPipeline() {
        override val steps = listOf(
            AgentStep("1/1 테스트 생성", "generate_test_prompt.md", isApplyable = false)
        )
    }

    /** 분석 문서 생성 (DocGenerateAgent 직접 호출) */
    object DocGenerate : TaskPipeline() {
        override val steps = listOf(
            AgentStep("1/1 분석 문서 생성", "explain_prompt.txt", isApplyable = false)
        )
    }

    /** 일반 대화 (폴백) */
    object Chat : TaskPipeline() {
        override val steps = listOf(
            AgentStep("1/1 일반 답변", "chat_prompt.txt", isApplyable = false)
        )
    }
}
