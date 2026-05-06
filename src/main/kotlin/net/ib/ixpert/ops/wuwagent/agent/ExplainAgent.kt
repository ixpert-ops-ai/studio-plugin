package net.ib.ixpert.ops.wuwagent.agent

import net.ib.ixpert.ops.wuwagent.prompt.PromptManager
import net.ib.ixpert.ops.wuwagent.prompt.StructureFormatter
import net.ib.ixpert.ops.wuwagent.service.EditorContextService
import net.ib.ixpert.ops.wuwagent.service.analysis.CodeAnalysisPipeline
import net.ib.ixpert.ops.wuwagent.service.analysis.model.ExtractedStructure

/**
 * 코드 설명(Explain)을 전담하는 에이전트.
 * 파이프라인을 통해 구조를 추출하고, 단일 프롬프트 템플릿에 변수를 주입하여 LLM에 요청합니다.
 * 구조 추출 성공 여부와 무관하게 동일한 출력 형식을 보장합니다.
 */
class ExplainAgent : BaseAgent() {

    private val pipeline = CodeAnalysisPipeline()

    override fun execute(
        context: AgentContext,
        onSuccess: (String) -> Unit,
        onChunk: ((String) -> Unit)?,
        onError: (String) -> Unit
    ) {
        val editor = context.editor
        if (editor == null) {
            onError("[상태 이상] 에디터 컨텍스트가 주어지지 않았습니다.")
            return
        }

        // 1. 코드 & 메타데이터 획득
        val isPartial = context.payloadText.isNotBlank()

        val fullCode = EditorContextService.extractCodeWithScope(editor, context.project).code
        val partialCode = if (isPartial) context.payloadText else fullCode

        if (partialCode.isBlank()) {
            onError("[알림] 분석할 코드를 도출하지 못했습니다.")
            return
        }

        val languageId = EditorContextService.extractLanguageId(editor, context.project) ?: "unknown"
        val fileName = EditorContextService.extractFileName(editor) ?: "unknown"
        val psiFile = EditorContextService.extractPsiFile(editor, context.project)
        val document = EditorContextService.extractDocument(editor)

        val startLine = context.startLine ?: EditorContextService.extractLineRange(editor)?.first
        val endLine = context.endLine ?: EditorContextService.extractLineRange(editor)?.second

        logger.info(
            "ExplainAgent 캡처 확인: isPartial=$isPartial, " +
            "startLine=$startLine, endLine=$endLine, " +
            "payloadLength=${context.payloadText.length}"
        )

        // 2. 파이프라인을 통한 구조 추출
        val structure: ExtractedStructure = try {
            val analysisInput = CodeAnalysisPipeline.AnalysisInput(
                code = fullCode,
                languageId = languageId,
                fileName = fileName,
                document = document,
                psiFile = psiFile,
                isPartial = isPartial,
                startLine = startLine,
                endLine = endLine
            )
            pipeline.extractStructure(analysisInput)
        } catch (e: Exception) {
            logger.warn("구조 추출 실패, 원문 사용: ${e.message}")
            ExtractedStructure.rawOnly(partialCode)
        }

        logger.info(
            "분석 시작: file=$fileName, lang=$languageId, " +
            "extraction=${structure.extractionMethod.displayName}, " +
            "symbols=${structure.symbols.size}, " +
            "hasStructure=${structure.hasStructure()}"
        )

        // 3. 단일 프롬프트 템플릿용 변수 구성
        val includeFaq = context.command == "/ragdoc"
        val promptVars = buildPromptVars(
            structure = structure,
            language = languageId,
            fileName = fileName,
            isPartial = isPartial,
            startLine = startLine,
            endLine = endLine,
            partialCode = partialCode,
            includeFaq = includeFaq
        )

        var systemPrompt = PromptManager.loadPromptWithVars("explain_prompt.txt", promptVars)
        
        // [Phase 1b] 메타그래프 컨텍스트 자동 주입
        val contextAssembler = context.project.getService(net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.ContextAssembler::class.java)
        val graphContext = contextAssembler.assemble(context, context.payloadText)
        if (graphContext.isNotBlank()) {
            systemPrompt = "$graphContext\n\n$systemPrompt"
        }
        
        val userPrompt = """
            제공된 정보와 코드를 바탕으로 시스템 메시지의 4가지 섹션 형식에 맞춰 분석해 주세요.
            [중요] 코드 개선 제안이나 추측성 분석은 절대 하지 마세요.
        """.trimIndent()

        // 4. 배너 처리 및 스트리밍 호출
        var isFirstChunk = true
        var finalContentWithBanner = ""

        val wrappedOnChunk: (String) -> Unit = { chunk ->
            if (isFirstChunk) {
                val banner = buildBanner(fileName, isPartial, startLine, endLine)
                val firstChunkWithBanner = banner + chunk
                finalContentWithBanner = firstChunkWithBanner
                onChunk?.invoke(firstChunkWithBanner)
                isFirstChunk = false
            } else {
                finalContentWithBanner += chunk
                onChunk?.invoke(chunk)
            }
        }

        callLlmStreamAsync(
            context.project,
            "WuwAgent: Explaining Code",
            systemPrompt,
            userPrompt,
            onSuccess = { _ -> onSuccess(finalContentWithBanner) },
            onChunk = wrappedOnChunk,
            onError = onError
        )
    }

    /**
     * 구조 추출 성공 여부에 따라 프롬프트 변수 맵을 구성합니다.
     * 두 경우 모두 동일한 키 집합을 반환하여 단일 템플릿 호환성을 보장합니다.
     */
    private fun buildPromptVars(
        structure: ExtractedStructure,
        language: String,
        fileName: String,
        isPartial: Boolean,
        startLine: Int?,
        endLine: Int?,
        partialCode: String,
        includeFaq: Boolean = false
    ): Map<String, String> {
        val analysisMode = if (isPartial) "부분 선택" else "전체 파일"
        val locationInfo = buildLocationInfo(fileName, isPartial, startLine, endLine)

        return if (structure.hasStructure()) {
            // 구조 추출 성공: StructureFormatter 변수 + 공통 메타데이터 보강
            val structureVars = StructureFormatter.toPromptVariables(
                structure = structure,
                language = language,
                fileName = fileName,
                isPartial = isPartial,
                startLine = startLine,
                endLine = endLine,
                partialCode = partialCode,
                includeFaq = includeFaq
            ).toMutableMap()

            // 공통 메타데이터 명시적 추가 (안전성 확보)
            structureVars.apply {
                this["ANALYSIS_MODE"] = analysisMode
                this["FILE_NAME"] = fileName
                this["LOCATION_INFO"] = locationInfo
            }
        } else {
            // 구조 추출 실패: 최소 컨텍스트로 폴백
            mapOf(
                "LANGUAGE" to language,
                "ANALYSIS_MODE" to analysisMode,
                "FILE_NAME" to fileName,
                "LOCATION_INFO" to locationInfo,
                "EXTRACTION_METHOD" to "원문 코드 기반 직접 분석",
                "STRUCTURE_INFO" to "구조 정보 없음 (제공된 코드를 직접 분석하여 진행합니다)",
                "THYMELEAF_INFO" to "",
                "PATTERN_GUIDE" to "",
                "FUNCTION_GUIDE" to "",
                "FAQ_SECTION" to if (includeFaq) {
                    """
                    ## 7. 자주 묻는 질문 (FAQ)
                    - 이 코드에 대해 개발자가 실무에서 물을 법한 질문 3~5개를 생성하세요.
                    - 각 질문에 대해 코드 사실에만 기반하여 2~4문장으로 답변하세요.
                    - 질문은 "~하려면?", "~는 어떻게 동작하나?", "~의 역할은?", "~를 호출하기 전에 필요한 것은?" 패턴으로 작성하세요.
                    - 아래 형식으로 작성하세요
                    ### Q. 질문 내용?
                    답변 내용 (2~4문장)
                    """.trimIndent()
                } else "",
                "KEY_CODE" to partialCode
            )
        }
    }

    /**
     * 분석 대상 정보를 표시하는 배너 문자열을 생성합니다.
     */
    private fun buildBanner(
        fileName: String,
        isPartial: Boolean,
        startLine: Int?,
        endLine: Int?
    ): String {
        return if (isPartial && startLine != null && endLine != null) {
            "### 🎯 분석 대상: `$fileName` (Line $startLine ~ $endLine)\n\n"
        } else {
            "### 🎯 분석 대상: `$fileName` (전체)\n\n"
        }
    }

    /**
     * 분석 범위 정보를 프롬프트용 문자열로 변환합니다.
     * null 안전성을 보장하여 템플릿 렌더링 오류를 방지합니다.
     */
    private fun buildLocationInfo(
        fileName: String,
        isPartial: Boolean,
        startLine: Int?,
        endLine: Int?
    ): String {
        return when {
            startLine != null && endLine != null ->
                "Line $startLine ~ $endLine in $fileName"
            isPartial ->
                "선택된 영역 in $fileName (라인 정보 없음)"
            else ->
                "전체 파일 $fileName"
        }
    }
}
