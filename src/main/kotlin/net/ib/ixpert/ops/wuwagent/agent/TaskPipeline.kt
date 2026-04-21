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
        val isImproveStep: Boolean = false
    ) {
        private val logger = Logger.getInstance(AgentStep::class.java)

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
         * Step 1 분석 결과에서 [IMPROVE_TARGETS] 블록을 파싱한다.
         * @return Pair(isFullFile, functionList)
         *   - isFullFile=true  : FULL_FILE 또는 블록 없음 → 전체 개선
         *   - isFullFile=false : 특정 함수 목록 반환
         */
        private fun parseImproveTargets(prevAnalysis: String): Pair<Boolean, List<String>> {
            val regex = Regex("""\[IMPROVE_TARGETS](.*?)\[/IMPROVE_TARGETS]""", RegexOption.DOT_MATCHES_ALL)
            val match = regex.find(prevAnalysis)
                ?: return Pair(true, emptyList())          // 블록 없음 → 전체 개선

            val content = match.groupValues[1].trim()
            if (content == "FULL_FILE") return Pair(true, emptyList())

            val functions = content.lines()
                .map { it.trim().removePrefix("-").trim().substringBefore("//").trim() }
                .filter { it.isNotBlank() }

            return if (functions.isEmpty()) Pair(true, emptyList())
            else Pair(false, functions)
        }

        /**
         * [IMPROVE_TARGETS]에 나열된 함수명 기준으로 원본 코드에서 해당 함수 블록만 추출한다.
         *
         * - 함수 선언 라인(수식어 포함) 탐색 → 위쪽 어노테이션 포함 → 중괄호 depth 추적으로 끝 결정
         * - Expression body(`fun f() = ...`)는 중괄호 없음 → 선언 라인만 포함
         * - 함수를 찾지 못하면 warn 후 skip
         * - 여러 함수는 빈 줄 하나("\n\n")로 구분하여 join
         */
        private fun extractFunctionBlocks(code: String, functions: List<String>): String {
            val lines = code.lines()
            val blocks = mutableListOf<String>()

            for (funcName in functions) {
                val funRegex = Regex(
                    """^\s*(?:(?:private|public|protected|internal|override|suspend|inline|open|abstract|operator|infix|tailrec|external|actual|expect)\s+)*fun\s+${Regex.escape(funcName)}\s*[(<]"""
                )
                val startLine = lines.indexOfFirst { funRegex.containsMatchIn(it) }
                if (startLine == -1) {
                    logger.warn("extractFunctionBlocks: '$funcName' 함수 미발견 → skip")
                    continue
                }

                // 선언 라인 위쪽의 어노테이션 라인 포함
                var blockStart = startLine
                while (blockStart > 0 && lines[blockStart - 1].trim().startsWith("@")) blockStart--

                // 중괄호 depth 추적으로 함수 끝 탐색
                var depth = 0
                var braceFound = false
                var blockEnd = startLine
                outer@ for (i in startLine until lines.size) {
                    for (ch in lines[i]) {
                        when (ch) {
                            '{' -> { depth++; braceFound = true }
                            '}' -> {
                                depth--
                                if (braceFound && depth == 0) { blockEnd = i; break@outer }
                            }
                        }
                    }
                }
                // braceFound=false → expression body, startLine만 포함 (blockEnd=startLine 유지)

                blocks.add(lines.subList(blockStart, blockEnd + 1).joinToString("\n"))
                logger.info("extractFunctionBlocks: '$funcName' 추출 완료 (라인 $blockStart~$blockEnd)")
            }

            return blocks.joinToString("\n\n")
        }

        /**
         * Improve step 전용 userMessage 조립.
         *
         * - FULL_FILE 또는 타깃 미지정: 전체 코드 전달
         * - 특정 함수 목록: 해당 함수 블록만 추출하여 전달 (컨텍스트 절약)
         *   → 병합(flexibleApplySearchReplace)은 여전히 전체 originalCode 기준으로 수행
         */
        private fun buildImproveUserMessage(prevAnalysis: String, code: String): String {
            val (isFullFile, functions) = parseImproveTargets(prevAnalysis)
            logger.info("buildImproveUserMessage: isFullFile=$isFullFile, targets=${functions.joinToString()}")

            val codeForLlm = if (!isFullFile && functions.isNotEmpty()) {
                val extracted = extractFunctionBlocks(code, functions)
                if (extracted.isNotBlank()) {
                    logger.info("buildImproveUserMessage: 함수 블록 추출 성공 (${extracted.length}자 / 원본 ${code.length}자)")
                    extracted
                } else {
                    logger.warn("buildImproveUserMessage: 함수 블록 추출 실패 → 전체 코드 fallback")
                    code
                }
            } else {
                code
            }

            return buildString {
                appendLine("다음 코드를 수정하라.")
                appendLine()
                if (isFullFile) {
                    appendLine("[수정 요구사항]")
                    appendLine("전체 파일 구조 개선")
                } else {
                    appendLine("[수정 요구사항]")
                    appendLine("개선 대상 함수: [${functions.joinToString(", ")}]")
                    appendLine("위 함수들을 Search/Replace 포맷으로 반환하라.")
                }
                appendLine()
                appendLine("[원본 코드]")
                appendLine("---CODE START---")
                appendLine(codeForLlm)
                appendLine("---CODE END---")
                appendLine()
                append("IMPORTANT:\n코드 외 텍스트를 출력하면 실패로 간주한다.")
            }
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
                    // isImproveStep 여부와 관계없이 originalCode 항상 세팅 (Step1 요약 계산용)
                    originalCode = fileContent
                    applyScope   = matchedFile.name
                    if (isImproveStep) {
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

            // ── [IMPROVE_TARGETS] 블록 스트리밍 필터 ────────────────────────────
            // 분석 step(isImproveStep=false)에서 [IMPROVE_TARGETS] 블록이 UI로 스트리밍되지 않도록 차단.
            // lookahead 버퍼로 태그 경계를 안전하게 감지한 뒤, 태그 이전까지만 전송한다.
            val IMPROVE_TAG = "[IMPROVE_TARGETS]"
            val filteredOnChunk: ((String) -> Unit)? = if (onChunk != null && !isImproveStep) {
                val lookahead = IMPROVE_TAG.length
                val pending   = StringBuilder()
                var suppressed = false
                { chunk ->
                    if (!suppressed) {
                        pending.append(chunk)
                        val idx = pending.indexOf(IMPROVE_TAG)
                        if (idx >= 0) {
                            suppressed = true
                            // 태그 이전까지만 전송 (뒤따르는 빈 줄/공백 제거)
                            val before = pending.substring(0, idx).trimEnd()
                            if (before.isNotEmpty()) onChunk(before)
                            logger.info("AgentStep[${label}] 청크 필터: [IMPROVE_TARGETS] 감지 → 이후 청크 차단")
                        } else {
                            // 태그 미감지 — lookahead 버퍼 유지하며 안전 구간 전송
                            val safeLen = maxOf(0, pending.length - lookahead)
                            if (safeLen > 0) {
                                onChunk(pending.substring(0, safeLen))
                                pending.delete(0, safeLen)
                            }
                        }
                    }
                }
            } else onChunk
            // ──────────────────────────────────────────────────────────────────────

            val response    = client.callChatApiStream(systemPrompt, userMessage, filteredOnChunk)
            val rawLlmResponse = response?.message?.content ?: "[오류] LLM 응답을 받지 못했습니다."

            // UI 표시용 llmResponse: [IMPROVE_TARGETS] 블록 제거
            var llmResponse = rawLlmResponse
                .replace(Regex("""\s*\[IMPROVE_TARGETS].*?\[/IMPROVE_TARGETS]""", RegexOption.DOT_MATCHES_ALL), "")
                .trimEnd()

            // ── Step1 분석 말풍선 요약 줄 추가 ───────────────────────────────────
            // [IMPROVE_TARGETS] 블록이 있는 분석 step + originalCode 확보된 경우에만 표시.
            // 형식: [대상 함수: 함수명1, 함수명2 / 전달 코드: XXX자]
            if (promptFile == "improve_analysis_prompt.txt" && originalCode.isNotBlank() && rawLlmResponse.contains("[IMPROVE_TARGETS]")) {
                val (isFullFile, functions) = parseImproveTargets(rawLlmResponse)
                val codeLen = if (!isFullFile && functions.isNotEmpty()) {
                    val extracted = extractFunctionBlocks(originalCode, functions)
                    if (extracted.isNotBlank()) extracted.length else originalCode.length
                } else {
                    originalCode.length
                }
                val targetLabel = if (isFullFile || functions.isEmpty()) "전체 파일" else functions.joinToString(", ")
                llmResponse += "\n\n[대상 함수: $targetLabel / 전달 코드: ${codeLen}자]"
            }
            // ──────────────────────────────────────────────────────────────────────

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

    /** 쿼리 / SQL 검증 (QueryValidationAgent 직접 호출) */
    object QueryValidation : TaskPipeline() {
        override val steps = listOf(
            AgentStep("1/1 쿼리 검증", "query_validation_prompt.txt", isApplyable = false)
        )
    }

    /** 테스트 코드 생성 (GenerateTestAgent 직접 호출) */
    object GenerateTest : TaskPipeline() {
        override val steps = listOf(
            AgentStep("1/1 테스트 생성", "generate_test_prompt.txt", isApplyable = false)
        )
    }

    /** 일반 대화 (폴백) */
    object Chat : TaskPipeline() {
        override val steps = listOf(
            AgentStep("1/1 일반 답변", "chat_prompt.txt", isApplyable = false)
        )
    }
}
