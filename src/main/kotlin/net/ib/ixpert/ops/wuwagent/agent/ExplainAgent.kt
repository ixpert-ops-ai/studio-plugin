package net.ib.ixpert.ops.wuwagent.agent

import net.ib.ixpert.ops.wuwagent.prompt.PromptManager
import net.ib.ixpert.ops.wuwagent.prompt.StructureFormatter
import net.ib.ixpert.ops.wuwagent.service.EditorContextService
import net.ib.ixpert.ops.wuwagent.service.analysis.CodeAnalysisPipeline
import net.ib.ixpert.ops.wuwagent.service.analysis.model.ExtractedStructure

/**
 * 코드 설명(Explain)을 전담하는 에이전트.
 * 파이프라인을 통해 구조를 추출하고, 구조 기반 프롬프트로 LLM에 요청합니다.
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
        
        // 파이프라인(구조 추출)은 문맥 파악을 위해 항상 전체 코드를 기반으로 구문 분석을 수행해야 함
        val fullCode = EditorContextService.extractCodeWithScope(editor, context.project).code
        val partialCode = if (isPartial) context.payloadText else fullCode
        
        if (partialCode.isBlank()) {
            onError("[알림] 분석할 코드를 도출하지 못했습니다.")
            return
        }

        val languageId = EditorContextService.extractLanguageId(editor, context.project)
        val fileName = EditorContextService.extractFileName(editor)
        val psiFile = EditorContextService.extractPsiFile(editor, context.project)
        val document = EditorContextService.extractDocument(editor)
        
        // Context에 저장된 캡처된 selection bounds를 최우선으로 사용
        val startLine = context.startLine ?: EditorContextService.extractLineRange(editor)?.first
        val endLine = context.endLine ?: EditorContextService.extractLineRange(editor)?.second

        logger.info("ExplainAgent 캡처 확인: isPartial=$isPartial, startLine=$startLine, endLine=$endLine, payloadLength=${context.payloadText.length}")

        // 2. 파이프라인을 통한 구조 추출 (PSI → Regex → Raw fallback)
        val structure: ExtractedStructure = try {
            val analysisInput = CodeAnalysisPipeline.AnalysisInput(
                code = fullCode, // 구조 추출에는 항상 전체 코드가 들어갑니다. (내부에서 startLine/endLine으로 필터링됨)
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

        // 3. 프롬프트 생성 (구조 있으면 구조 기반, 없으면 기존 원문 기반)
        val systemPrompt: String
        val userPrompt: String

        if (structure.hasStructure()) {
            val vars = StructureFormatter.toPromptVariables(
                structure = structure,
                language = languageId,
                fileName = fileName,
                isPartial = isPartial,
                startLine = startLine,
                endLine = endLine,
                partialCode = partialCode
            )
            systemPrompt = PromptManager.loadPromptWithVars("explain_structured_prompt.txt", vars)
            userPrompt = "위 구조 정보를 바탕으로 코드를 분석해 주세요."
        } else {
            systemPrompt = PromptManager.loadPrompt("explain_prompt.txt")
            userPrompt = partialCode
        }

        var isFirstChunk = true
        var finalContentWithBanner = ""

        val wrappedOnChunk: (String) -> Unit = { chunk ->
            if (isFirstChunk) {
                val banner = if (isPartial && startLine != null && endLine != null) {
                    "### 🎯 분석 대상: `${fileName}` (Line $startLine ~ $endLine)\n\n"
                } else {
                    "### 🎯 분석 대상: `${fileName}` (전체)\n\n"
                }
                val firstChunkWithBanner = banner + chunk
                finalContentWithBanner = firstChunkWithBanner
                onChunk?.invoke(firstChunkWithBanner)
                isFirstChunk = false
            } else {
                finalContentWithBanner += chunk
                onChunk?.invoke(chunk)
            }
        }

        // 5. LLM 스트리밍 호출
        callLlmStreamAsync(
            context.project, 
            "WuwAgent: Explaining Code", 
            systemPrompt, 
            userPrompt, 
            onSuccess = { _ ->
                onSuccess(finalContentWithBanner)
            },
            onChunk = wrappedOnChunk,
            onError = onError
        )
    }
}
