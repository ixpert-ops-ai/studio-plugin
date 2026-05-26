package net.ib.ixpert.ops.wuwagent.agent

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import net.ib.ixpert.ops.wuwagent.prompt.PromptManager
import net.ib.ixpert.ops.wuwagent.prompt.StructureFormatter
import net.ib.ixpert.ops.wuwagent.service.EditorContextService
import net.ib.ixpert.ops.wuwagent.service.MarkdownFileService
import net.ib.ixpert.ops.wuwagent.service.analysis.CodeAnalysisPipeline
import net.ib.ixpert.ops.wuwagent.service.analysis.model.ExtractedStructure

/**
 * 소스 파일 분석 결과를 Markdown 문서로 생성하는 에이전트.
 *
 * - **단일 파일**: [execute] — 에디터에 열린 파일을 [ExplainAgent]에 위임하여 분석
 * - **다중 파일**: [executeBatch] — 디렉토리 내 파일들을 순차적으로 직접 분석
 *
 * 기존 ExplainAgent의 분석 파이프라인(CodeAnalysisPipeline + explain_prompt.txt)을 재사용합니다.
 */
class DocGenerateAgent : BaseAgent() {

    private val pipeline = CodeAnalysisPipeline()

    // ──────────────────────────────────────────
    //  단일 파일 분석 (에디터 기반 — 기존 로직)
    // ──────────────────────────────────────────
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

        val fileName = EditorContextService.extractFileName(editor)
        if (fileName.isNullOrBlank()) {
            onError("[알림] 파일명을 가져올 수 없습니다.")
            return
        }

        logger.info("DocGenerateAgent: 단일 파일 분석 문서 생성 시작 → $fileName")

        val command = context.command ?: "/doc"
        val subDir = if (command == "/ragdoc") "rag_docs" else "docs"

        val explainContext = AgentContext(
            project = context.project,
            editor = editor,
            payloadText = "",
            command = command
        )

        ExplainAgent().execute(
            explainContext,
            onSuccess = { analysisResult ->
                try {
                    val savedPath = MarkdownFileService.saveAnalysisDoc(
                        context.project, fileName, analysisResult, subDir
                    )
                    logger.info("DocGenerateAgent: 분석 문서 저장 완료 → $savedPath")

                    val vFile = MarkdownFileService.findSavedFile(context.project, fileName, subDir)
                    if (vFile != null) {
                        ApplicationManager.getApplication().invokeLater {
                            FileEditorManager.getInstance(context.project).openFile(vFile, true)
                        }
                    }

                    val relativePath = savedPath.removePrefix("${context.project.basePath}/")
                    onSuccess("✅ 분석 문서가 생성되었습니다: `$relativePath`")
                } catch (e: Exception) {
                    logger.error("DocGenerateAgent: 문서 저장 실패", e)
                    onError("[오류] 분석 문서 저장 중 오류가 발생했습니다: ${e.message}")
                }
            },
            onChunk = onChunk,
            onError = onError
        )
    }

    // ──────────────────────────────────────────
    //  다중 파일 일괄 분석 (디렉토리 기반)
    // ──────────────────────────────────────────

    /**
     * 다중 파일을 순차적으로 분석하여 각각 MD 파일로 저장합니다.
     * Backgroundable Task 내부에서 동기적으로 LLM을 호출합니다.
     *
     * @param project         현재 프로젝트
     * @param files           분석 대상 VirtualFile 목록
     * @param onStepProgress  파일별 진행 알림 (fileName, current, total, status)
     * @param onComplete      전체 완료 시 요약 메시지
     * @param onError         오류 발생 시
     */
    fun executeBatch(
        project: Project,
        files: List<VirtualFile>,
        command: String = "/doc",
        onStepProgress: (fileName: String, current: Int, total: Int, status: String) -> Unit,
        onComplete: (summary: String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (files.isEmpty()) {
            onError("[알림] 분석 대상 소스 파일이 없습니다.")
            return
        }

        val total = files.size
        logger.info("DocGenerateAgent: 일괄 분석 시작 → ${total}개 파일")

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "분석 문서 일괄 생성", true) {
            override fun run(indicator: ProgressIndicator) {
                TaskCancellationToken.reset()
                TaskCancellationToken.backgroundThread = Thread.currentThread()

                indicator.isIndeterminate = false
                val successList = mutableListOf<String>()
                val failList = mutableListOf<String>()

                for ((index, file) in files.withIndex()) {
                    // 취소 확인
                    if (indicator.isCanceled || TaskCancellationToken.isCancelled.get()) {
                        logger.info("DocGenerateAgent: 일괄 분석 취소됨 (${index}/${total})")
                        break
                    }

                    val fileName = file.name
                    val current = index + 1
                    indicator.fraction = current.toDouble() / total
                    indicator.text = "$current/$total: $fileName 분석 중..."

                    // step 시작 알림
                    onStepProgress(fileName, current, total, "started")

                    val subDir = if (command == "/ragdoc") "rag_docs" else "docs"

                    try {
                        val result = analyzeFileSync(project, file, command)
                        if (result != null) {
                            val savedPath = MarkdownFileService.saveAnalysisDoc(project, fileName, result, subDir)
                            val relativePath = savedPath.removePrefix("${project.basePath}/")
                            successList.add(relativePath)
                            logger.info("DocGenerateAgent: [$current/$total] $fileName → 저장 완료")
                            onStepProgress(fileName, current, total, "completed")
                        } else {
                            failList.add(fileName)
                            logger.warn("DocGenerateAgent: [$current/$total] $fileName → 분석 실패")
                            onStepProgress(fileName, current, total, "failed")
                        }
                    } catch (e: Exception) {
                        failList.add(fileName)
                        logger.error("DocGenerateAgent: [$current/$total] $fileName → 오류", e)
                        onStepProgress(fileName, current, total, "failed")
                    }
                }

                // 최종 요약 생성
                val summary = buildString {
                    appendLine("## 📄 분석 문서 생성 완료")
                    appendLine()
                    appendLine("- **성공**: ${successList.size}개 파일")
                    appendLine("- **실패**: ${failList.size}개 파일")
                    appendLine()
                    if (successList.isNotEmpty()) {
                        appendLine("### ✅ 생성된 문서")
                        for (path in successList) {
                            appendLine("- `$path`")
                        }
                    }
                    if (failList.isNotEmpty()) {
                        appendLine()
                        appendLine("### ❌ 실패한 파일")
                        for (name in failList) {
                            appendLine("- `$name`")
                        }
                    }
                }

                onComplete(summary)
            }
        })
    }

    /**
     * 단일 파일을 동기적으로 분석합니다 (Editor 없이 VirtualFile 기반).
     * ExplainAgent의 핵심 로직(파이프라인 + 프롬프트 + LLM)을 직접 수행합니다.
     *
     * @return 분석 결과 Markdown 문자열, 실패 시 null
     */
    private fun analyzeFileSync(project: Project, file: VirtualFile, command: String = "/doc"): String? {
        val code = MarkdownFileService.readFileContent(file)
        if (code.isBlank()) {
            logger.warn("DocGenerateAgent: 빈 파일 건너뜀 → ${file.name}")
            return null
        }

        val fileName = file.name
        val languageId = MarkdownFileService.inferLanguageId(file)

        // Document & PsiFile 획득 (ReadAction 필요)
        val document = ApplicationManager.getApplication().runReadAction(Computable {
            FileDocumentManager.getInstance().getDocument(file)
        })
        val psiFile = if (document != null) {
            ApplicationManager.getApplication().runReadAction(Computable {
                PsiDocumentManager.getInstance(project).getPsiFile(document)
            })
        } else null

        // 구조 추출 (실패 시 원문 기반 폴백)
        val structure: ExtractedStructure = try {
            if (document != null) {
                val analysisInput = CodeAnalysisPipeline.AnalysisInput(
                    code = code,
                    languageId = languageId,
                    fileName = fileName,
                    document = document,
                    psiFile = psiFile,
                    isPartial = false,
                    startLine = null,
                    endLine = null
                )
                pipeline.extractStructure(analysisInput)
            } else {
                ExtractedStructure.rawOnly(code)
            }
        } catch (e: Exception) {
            logger.warn("DocGenerateAgent: 구조 추출 실패, 원문 사용 → ${file.name}: ${e.message}")
            ExtractedStructure.rawOnly(code)
        }

        // 프롬프트 변수 구성
        val promptVars = if (structure.hasStructure()) {
            val structureVars = StructureFormatter.toPromptVariables(
                structure = structure,
                language = languageId,
                fileName = fileName,
                isPartial = false,
                startLine = null,
                endLine = null,
                partialCode = code,
                includeFaq = (command == "/ragdoc")
            ).toMutableMap()
            structureVars.apply {
                this["ANALYSIS_MODE"] = "전체 파일"
                this["FILE_NAME"] = fileName
                this["LOCATION_INFO"] = "전체 파일 $fileName"
            }
        } else {
            mapOf(
                "LANGUAGE" to languageId,
                "ANALYSIS_MODE" to "전체 파일",
                "FILE_NAME" to fileName,
                "LOCATION_INFO" to "전체 파일 $fileName",
                "EXTRACTION_METHOD" to "원문 코드 기반 직접 분석",
                "STRUCTURE_INFO" to "구조 정보 없음 (제공된 코드를 직접 분석하여 진행합니다)",
                "THYMELEAF_INFO" to "",
                "PATTERN_GUIDE" to "",
                "FUNCTION_GUIDE" to "",
                "FAQ_SECTION" to if (command == "/ragdoc") {
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
                "KEY_CODE" to code
            )
        }

        val systemPrompt = PromptManager.loadPromptWithVars("explain_prompt.txt", promptVars)
        val userPrompt = """
            제공된 정보와 코드를 바탕으로 시스템 메시지의 4가지 섹션 형식에 맞춰 분석해 주세요.
            [중요] 코드 개선 제안이나 추측성 분석은 절대 하지 마세요.
        """.trimIndent()

        // LLM 동기 호출 (스트리밍 없이 블로킹)
        val response = ollamaClient.chat(systemPrompt, userPrompt)
        val resultText = response?.message?.content

        if (resultText.isNullOrBlank() || resultText.startsWith("[Error]")) {
            logger.error("DocGenerateAgent: LLM 응답 오류 → ${file.name}: $resultText")
            return null
        }

        // 배너 추가
        val banner = buildBanner(promptVars)
        return banner + resultText
    }

    /**
     * 분석 대상 정보를 표시하는 배너 문자열(YAML Frontmatter 포함)을 생성합니다.
     */
    private fun buildBanner(promptVars: Map<String, String>): String {
        val fileName = promptVars["FILE_NAME"] ?: "Unknown"
        val packageName = promptVars["PACKAGE_NAME"] ?: "(식별 불가)"
        val language = promptVars["LANGUAGE"] ?: "Unknown"
        val fileType = promptVars["FILE_TYPE"] ?: "script"
        val dateStr = promptVars["ANALYZED_DATE"] ?: java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_DATE)
        
        val rawDeps = promptVars["DEPENDENCIES"]
        val dependenciesStr = if (rawDeps.isNullOrBlank()) {
            "[]"
        } else {
            "[\"$rawDeps\"]"
        }

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

        val bannerTitle = "### 🎯 분석 대상: `$fileName` (전체)\n\n"
        
        return yamlFrontmatter + bannerTitle
    }
}
