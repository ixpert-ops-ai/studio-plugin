package net.ib.ixpert.ops.wuwagent.agent

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import net.ib.ixpert.ops.wuwagent.prompt.PromptManager
import net.ib.ixpert.ops.wuwagent.prompt.StructureFormatter
import net.ib.ixpert.ops.wuwagent.service.EditorContextService
import net.ib.ixpert.ops.wuwagent.service.analysis.CodeAnalysisPipeline
import net.ib.ixpert.ops.wuwagent.service.analysis.model.ExtractedStructure

/**
 * 코드 설명(Explain)을 전담하는 에이전트.
 *
 * 컨텍스트 소스 우선순위 (배타적 적용 — 상위 소스가 있으면 하위 소스를 완전히 제외):
 *   1순위: @ 파일 첨부 (context.attachedFilesJson) → 첨부 파일 내용만, 에디터 완전 제외
 *   2순위: ExplainAction 사전 캡처 선택 영역 (context.startLine != null && payloadText 있음)
 *   2순위: 채팅 드래그 선택 영역 (editor.selectionModel.hasSelection())
 *   3순위: 에디터 전체 파일
 *   4순위: 없음 → 오류
 */
class ExplainAgent : BaseAgent() {

    private val pipeline = CodeAnalysisPipeline()

    // ── 첨부 파일 파싱 헬퍼 ───────────────────────────────────────────────────

    private data class AttachedFileInfo(val name: String, val path: String)

    private fun parseAttachedFiles(filesJson: String): List<AttachedFileInfo> {
        if (filesJson.isBlank()) return emptyList()
        return try {
            Regex("""\{"name":"([^"]+)","path":"([^"]+)"\}""")
                .findAll(filesJson)
                .map { AttachedFileInfo(it.groupValues[1], it.groupValues[2].replace("\\\\", "\\")) }
                .toList()
        } catch (e: Exception) {
            logger.warn("ExplainAgent: 첨부 파일 JSON 파싱 실패", e)
            emptyList()
        }
    }

    private data class VFileContext(
        val code: String,
        val languageId: String,
        val psiFile: PsiFile?,
        val document: Document
    )

    private fun readVirtualFileContext(
        vFile: com.intellij.openapi.vfs.VirtualFile,
        project: com.intellij.openapi.project.Project
    ): VFileContext? {
        return ApplicationManager.getApplication().runReadAction(Computable {
            val doc = FileDocumentManager.getInstance().getDocument(vFile) ?: return@Computable null
            val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(doc)
            val langId = psiFile?.language?.id
                ?: vFile.extension?.uppercase()
                ?: "unknown"
            VFileContext(doc.text, langId, psiFile, doc)
        })
    }

    // ── 진입점 ───────────────────────────────────────────────────────────────

    override fun execute(
        context: AgentContext,
        onSuccess: (String) -> Unit,
        onChunk: ((String) -> Unit)?,
        onError: (String) -> Unit
    ) {
        // ── 1순위: @ 파일 첨부 → 에디터 완전 제외 ────────────────────────────
        val attachedFiles = parseAttachedFiles(context.attachedFilesJson)
        if (attachedFiles.isNotEmpty()) {
            val first = attachedFiles[0]
            val vFile = LocalFileSystem.getInstance().findFileByPath(first.path)
            if (vFile == null) {
                onError("[오류] 첨부 파일을 찾을 수 없습니다: ${first.name}")
                return
            }
            val vCtx = readVirtualFileContext(vFile, context.project)
            if (vCtx == null) {
                onError("[오류] 첨부 파일 내용을 읽을 수 없습니다: ${first.name}")
                return
            }
            logger.info("ExplainAgent: 1순위 @ 파일 첨부 → ${first.name} (${vCtx.code.length}자)")
            runAnalysis(
                context = context,
                code = vCtx.code,
                fullCode = vCtx.code,
                isPartial = false,
                languageId = vCtx.languageId,
                fileName = first.name,
                psiFile = vCtx.psiFile,
                document = vCtx.document,
                startLine = null,
                endLine = null,
                onSuccess = onSuccess,
                onChunk = onChunk,
                onError = onError
            )
            return
        }

        // ── 에디터 필수 (2 / 3순위) ──────────────────────────────────────────
        val editor = context.editor
        if (editor == null) {
            onSuccess("설명할 코드가 없습니다. 파일을 열거나 @파일을 선택해주세요.")
            return
        }

        val languageId = EditorContextService.extractLanguageId(editor, context.project).ifBlank { "unknown" }
        val fileName   = EditorContextService.extractFileName(editor).ifBlank { "unknown" }
        val psiFile    = EditorContextService.extractPsiFile(editor, context.project)
        val document   = EditorContextService.extractDocument(editor)
        val scopeResult = EditorContextService.extractCodeWithScope(editor, context.project)

        // ── 2순위 (ExplainAction): 메뉴 열리기 전 사전 캡처된 선택 영역 ─────
        // ExplainAction은 우클릭 시점에 선택 텍스트를 payloadText에, 라인 번호를 startLine/endLine에 담음
        if (context.startLine != null && context.payloadText.isNotBlank()) {
            logger.info("ExplainAgent: 2순위 ExplainAction 선택 영역 → ${context.payloadText.length}자 (L${context.startLine}~${context.endLine})")
            runAnalysis(
                context = context,
                code = context.payloadText,
                fullCode = scopeResult.code,   // PSI는 전체 파일 기준
                isPartial = true,
                languageId = languageId,
                fileName = fileName,
                psiFile = psiFile,
                document = document,
                startLine = context.startLine,
                endLine = context.endLine,
                onSuccess = onSuccess,
                onChunk = onChunk,
                onError = onError
            )
            return
        }

        // ── 2순위 (채팅): 에디터 현재 드래그 선택 영역 ──────────────────────
        if (scopeResult.isSelection) {
            logger.info("ExplainAgent: 2순위 드래그 선택 영역 → ${scopeResult.code.length}자 (L${scopeResult.startLine}~${scopeResult.endLine})")
            runAnalysis(
                context = context,
                code = scopeResult.code,
                fullCode = scopeResult.code,
                isPartial = true,
                languageId = languageId,
                fileName = fileName,
                psiFile = psiFile,
                document = document,
                startLine = scopeResult.startLine,
                endLine = scopeResult.endLine,
                onSuccess = onSuccess,
                onChunk = onChunk,
                onError = onError
            )
            return
        }

        // ── 3순위: 에디터 전체 파일 ─────────────────────────────────────────
        if (scopeResult.code.isBlank()) {
            onSuccess("설명할 코드가 없습니다. 파일을 열거나 @파일을 선택해주세요.")
            return
        }
        logger.info("ExplainAgent: 3순위 전체 파일 → $fileName (${scopeResult.code.length}자)")
        runAnalysis(
            context = context,
            code = scopeResult.code,
            fullCode = scopeResult.code,
            isPartial = false,
            languageId = languageId,
            fileName = fileName,
            psiFile = psiFile,
            document = document,
            startLine = null,
            endLine = null,
            onSuccess = onSuccess,
            onChunk = onChunk,
            onError = onError
        )
    }

    // ── 공통 분석 실행 ────────────────────────────────────────────────────────

    /**
     * @param code      KEY_CODE로 LLM에 전달될 실제 분석 대상 코드
     * @param fullCode  PSI 구조 추출용 전체 파일 코드 (선택 영역의 경우에도 전체 파일 가능)
     */
    private fun runAnalysis(
        context: AgentContext,
        code: String,
        fullCode: String,
        isPartial: Boolean,
        languageId: String,
        fileName: String,
        psiFile: PsiFile?,
        document: Document,
        startLine: Int?,
        endLine: Int?,
        onSuccess: (String) -> Unit,
        onChunk: ((String) -> Unit)?,
        onError: (String) -> Unit
    ) {
        // 구조 추출 (document null-safe: AnalysisInput은 non-nullable Document 요구)
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
            logger.warn("ExplainAgent: 구조 추출 실패, 원문 사용: ${e.message}")
            ExtractedStructure.rawOnly(code)
        }

        logger.info(
            "ExplainAgent 분석: file=$fileName, lang=$languageId, " +
            "extraction=${structure.extractionMethod.displayName}, " +
            "symbols=${structure.symbols.size}, hasStructure=${structure.hasStructure()}"
        )

        // 프롬프트 변수 구성
        val includeFaq = context.command == "/ragdoc"
        val promptVars = buildPromptVars(
            structure = structure,
            language = languageId,
            fileName = fileName,
            isPartial = isPartial,
            startLine = startLine,
            endLine = endLine,
            partialCode = code,
            includeFaq = includeFaq
        )

        var systemPrompt = PromptManager.loadPromptWithVars("explain_prompt.txt", promptVars)

        // [Phase 1b] 메타그래프 컨텍스트 자동 주입
        val contextAssembler = context.project.getService(
            net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.ContextAssembler::class.java
        )
        val graphContext = contextAssembler.assemble(context, context.payloadText)
        if (graphContext.isNotBlank()) {
            systemPrompt = "$graphContext\n\n$systemPrompt"
        }

        val userPrompt = """
            제공된 정보와 코드를 바탕으로 시스템 메시지의 정해진 섹션 형식에 맞춰 분석해 주세요.
            [중요] 코드 개선 제안이나 추측성 분석은 절대 하지 마세요.
        """.trimIndent()

        // 배너 처리 및 스트리밍 호출
        var isFirstChunk = true
        var finalContentWithBanner = ""

        val wrappedOnChunk: (String) -> Unit = { chunk ->
            if (isFirstChunk) {
                val banner = buildBanner(promptVars, isPartial, startLine, endLine)
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
            "iXpert AI Assistant: Explaining Code",
            systemPrompt,
            userPrompt,
            onSuccess = { _ -> onSuccess(finalContentWithBanner) },
            onChunk = wrappedOnChunk,
            onError = onError
        )
    }

    // ── 프롬프트 변수 구성 ────────────────────────────────────────────────────

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

            structureVars.apply {
                this["FILE_NAME"] = fileName
                this["LOCATION_INFO"] = locationInfo
            }
        } else {
            mapOf(
                "LANGUAGE" to language,
                "ANALYSIS_MODE" to if (isPartial) "선택 영역 분석" else "전체 파일 (토큰 최적화: 핵심 메서드 본문만 제공, 나머지는 구조 정보로 대체)",
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

    private fun buildBanner(
        promptVars: Map<String, String>,
        isPartial: Boolean,
        startLine: Int?,
        endLine: Int?
    ): String {
        val fileName = promptVars["FILE_NAME"] ?: "Unknown"
        val packageName = promptVars["PACKAGE_NAME"] ?: "(식별 불가)"
        val language = promptVars["LANGUAGE"] ?: "Unknown"
        val fileType = promptVars["FILE_TYPE"] ?: "script"
        val dateStr = promptVars["ANALYZED_DATE"] ?: java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_DATE)

        val rawDeps = promptVars["DEPENDENCIES"]
        val dependenciesStr = if (rawDeps.isNullOrBlank()) "[]" else """["$rawDeps"]"""

        val yamlFrontmatter = """
            ```yaml
            ---
            file: "$fileName"
            package: "$packageName"
            language: "$language"
            type: "$fileType"
            dependencies: $dependenciesStr
            analyzed_date: "$dateStr"
            ---
            ```

        """.trimIndent()

        val bannerTitle = if (isPartial && startLine != null && endLine != null) {
            "### 🎯 분석 대상: `$fileName` (Line $startLine ~ $endLine)\n\n"
        } else {
            "### 🎯 분석 대상: `$fileName` (전체)\n\n"
        }

        return yamlFrontmatter + bannerTitle
    }

    private fun buildLocationInfo(
        fileName: String,
        isPartial: Boolean,
        startLine: Int?,
        endLine: Int?
    ): String {
        return when {
            startLine != null && endLine != null -> "Line $startLine ~ $endLine in $fileName"
            isPartial -> "선택된 영역 in $fileName (라인 정보 없음)"
            else -> "전체 파일 $fileName"
        }
    }
}
