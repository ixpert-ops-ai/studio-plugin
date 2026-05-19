package net.ib.ixpert.ops.wuwagent.action

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import net.ib.ixpert.ops.wuwagent.agent.AgentContext
import net.ib.ixpert.ops.wuwagent.agent.ChatAgent
import net.ib.ixpert.ops.wuwagent.agent.DocGenerateAgent
import net.ib.ixpert.ops.wuwagent.agent.ExplainAgent
import net.ib.ixpert.ops.wuwagent.agent.ImpactAgent
import net.ib.ixpert.ops.wuwagent.agent.IntentAnalyzer
import net.ib.ixpert.ops.wuwagent.agent.QueryValidationAgent
import net.ib.ixpert.ops.wuwagent.agent.TaskAgent
import net.ib.ixpert.ops.wuwagent.agent.TaskCancellationToken
import net.ib.ixpert.ops.wuwagent.agent.TaskPipeline
import net.ib.ixpert.ops.wuwagent.agent.UnitTestReportAgent
import net.ib.ixpert.ops.wuwagent.service.EditorApplyService
import net.ib.ixpert.ops.wuwagent.service.WuwLlmService
import net.ib.ixpert.ops.wuwagent.service.EditorDiffService
import net.ib.ixpert.ops.wuwagent.ui.bridge.JcefBridge

/**
 * Webview(React)에서 넘어오는 JSQuery 명령을 수신하여
 * 알맞은 Agent로 라우팅합니다.
 *
 * 명령어 구조 (혼합 방식):
 * - /explain  → ExplainAgent 직접 실행
 * - /chat     → ChatAgent 직접 실행
 * - /task     → TaskAgent (IntentAnalyzer → Pipeline)
 * - /apply    → EditorApplyService로 코드 에디터 적용
 * - (기타)    → 알 수 없는 명령어 에러 반환
 */
class WebviewActionRouter(private val project: Project) {
    private val logger = Logger.getInstance(WebviewActionRouter::class.java)

    private fun buildAttachedFileContext(filesJson: String): String {
        if (filesJson.isBlank()) return ""
        return try {
            val entries = Regex("""\{"name":"([^"]+)","path":"([^"]+)"\}""").findAll(filesJson)
            val blocks = entries.mapNotNull { match ->
                val name = match.groupValues[1]
                val path = match.groupValues[2].replace("\\\\", "\\")
                val vFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(path)
                    ?: return@mapNotNull null
                val content = ApplicationManager.getApplication().runReadAction(
                    com.intellij.openapi.util.Computable {
                        com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
                            .getDocument(vFile)?.text
                    }
                ) ?: return@mapNotNull null
                "// 파일: $name\n```\n$content\n```"
            }.toList()
            if (blocks.isEmpty()) "" else "[첨부 파일]\n${blocks.joinToString("\n\n")}"
        } catch (e: Exception) {
            logger.warn("Router: 첨부 파일 읽기 실패", e)
            ""
        }
    }

    fun handleCommand(command: String, payload: Map<String, String>) {
        ApplicationManager.getApplication().invokeLater {
            val bridge = JcefBridge.getInstance(project)
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
            val textBody = payload["text"] ?: ""

            logger.info("Router: 수신 명령어='$command'")

            when (command) {
                // ── 직접 라우팅 ──────────────────────────────
                "/explain" -> {
                    logger.info("Router: /explain 분기")
                    if (editor == null) {
                        bridge.sendMessage("explain", "활성화된 에디터가 없어 Explain을 실행할 수 없습니다.")
                        return@invokeLater
                    }
                    val messageId = "msg_${System.currentTimeMillis()}"
                    // 🛎 즉시 자리 만들기 (로딩 표시 유도)
                    bridge.sendMessage("explain_start", "🔍 코드 구조를 분석하고 있습니다...", messageId)

                    val context = AgentContext(project, editor, textBody, command = "/explain")
                    ExplainAgent().execute(
                        context, 
                        onSuccess = { res ->
                            ApplicationManager.getApplication().invokeLater {
                                bridge.sendMessage("explain", res, messageId)
                            }
                        },
                        onChunk = { chunk ->
                            ApplicationManager.getApplication().invokeLater {
                                bridge.sendMessageChunk(messageId, chunk)
                            }
                        },
                        onError = { errorMsg ->
                            ApplicationManager.getApplication().invokeLater {
                                // "__cancelled__" 는 /cancel 핸들러에서 이미 task_cancelled 전송 완료 → 중복 방지
                                if (errorMsg != "__cancelled__") {
                                    bridge.sendMessage("error", errorMsg, messageId)
                                }
                            }
                        }
                    )
                }

                // ── 요구사항 기반 파일 추출 (Phase 2a) ────────
                "/analyze" -> {
                    logger.info("Router: /analyze 분기")
                    val messageId = "analyze_${System.currentTimeMillis()}"
                    bridge.sendMessage("analyze_start", "🔍 프로젝트 메타그래프를 분석하여 요구사항 대상 파일을 추출하고 있습니다...", messageId)
                    
                    ApplicationManager.getApplication().executeOnPooledThread {
                        try {
                            val graphLoader = project.getService(net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.GraphLoader::class.java)
                            val projectGraph = graphLoader.loadGraph() ?: throw IllegalStateException("메타그래프를 찾을 수 없습니다. 먼저 /metagraph 명령어로 그래프를 생성해주세요.")
                            
                            val client = WuwLlmService.getClient()
                            val pipeline = net.ib.ixpert.ops.wuwagent.agent.RequirementAnalysisPipeline(client)
                            
                            val result = pipeline.analyze(textBody, projectGraph) { chunk ->
                                ApplicationManager.getApplication().invokeLater {
                                    bridge.sendMessageChunk(messageId, chunk)
                                }
                            }
                            
                            ApplicationManager.getApplication().invokeLater {
                                if (result.targetFiles.isNotEmpty()) {
                                    val extraText = "\n\n---\n**💡 위 파일들의 구체적인 코드 수정을 원하시면 `/implement`를 입력하세요.**"
                                    bridge.sendMessageChunk(messageId, extraText)
                                }
                                bridge.sendMessage("chat", "", messageId) // 스트리밍 종료 신호
                            }
                        } catch (e: Exception) {
                            logger.error("RequirementAnalysisPipeline Error", e)
                            ApplicationManager.getApplication().invokeLater {
                                bridge.sendMessage("error", "요구사항 분석 중 오류가 발생했습니다: ${e.message}", messageId)
                            }
                        }
                    }
                }

                // ── 자동 코드 작성 스텁 (Phase 2b) ────────
                "/implement" -> {
                    logger.info("Router: /implement 분기")
                    val messageId = "implement_${System.currentTimeMillis()}"
                    
                    val cachedResult = net.ib.ixpert.ops.wuwagent.agent.RequirementAnalysisPipeline.lastResult
                    if (cachedResult == null || cachedResult.targetFiles.isEmpty()) {
                        bridge.sendMessage("chat_start", "", messageId)
                        bridge.sendMessage("error", "분석된 타겟 파일이 없습니다. 먼저 `/analyze 요구사항`을 실행해주세요.", messageId)
                        return@invokeLater
                    }
                    
                    bridge.sendMessage("chat_start", "🚀 **Phase 2b 코드 수정 파이프라인**\n타겟 파일 소스 코드를 분석하여 수정을 시작합니다...", messageId)
                    
                    ApplicationManager.getApplication().executeOnPooledThread {
                        try {
                            val client = WuwLlmService.getClient()
                            val pipeline = net.ib.ixpert.ops.wuwagent.agent.ImplementationPipeline(client, project)
                            
                            pipeline.execute(cachedResult) { chunk ->
                                ApplicationManager.getApplication().invokeLater {
                                    bridge.sendMessageChunk(messageId, chunk)
                                }
                            }
                            
                            // [Phase 2c] 실행 완료 후 컨텍스트 캐시 저장 (파이프라인 내부에서 이미 저장하지만, 완료 메시지는 여기서 처리)
                            val extraText = "\n\n💡 수정된 파일들의 테스트 코드를 일괄 생성하려면 `/test-all`을 입력하세요."
                            ApplicationManager.getApplication().invokeLater {
                                bridge.sendMessageChunk(messageId, extraText)
                                bridge.sendMessage("chat", "", messageId)
                            }
                        } catch (e: Exception) {
                            logger.error("ImplementationPipeline Error", e)
                            ApplicationManager.getApplication().invokeLater {
                                bridge.sendMessage("error", "코드 생성 중 오류가 발생했습니다: ${e.message}", messageId)
                            }
                        }
                    }
                }

                // ── 단위 테스트 코드 일괄 생성 (Phase 2c) ────────
                "/test-all" -> {
                    logger.info("Router: /test-all 분기")
                    val messageId = "test_${System.currentTimeMillis()}"
                    val trimmedQuery = textBody.removePrefix("/test-all").trim()

                    ApplicationManager.getApplication().executeOnPooledThread {
                        try {
                            if (trimmedQuery.isBlank() && net.ib.ixpert.ops.wuwagent.agent.TestGenerationPipeline.lastImplementContext == null) {
                                ApplicationManager.getApplication().invokeLater {
                                    bridge.sendMessage("chat_start", "", messageId)
                                    bridge.sendMessage("error", "⚠️ 테스트를 생성할 컨텍스트가 없습니다.\n먼저 `/implement`를 실행하거나, `/test-all 클래스명`으로 특정 클래스를 지정하세요.", messageId)
                                }
                                return@executeOnPooledThread
                            }

                            ApplicationManager.getApplication().invokeLater {
                                bridge.sendMessage("chat_start", "🧪 **단위 테스트 자동 생성**을 시작합니다...", messageId)
                            }

                            val client = WuwLlmService.getClient()
                            val pipeline = net.ib.ixpert.ops.wuwagent.agent.TestGenerationPipeline(project, client)

                            val target = if (trimmedQuery.isBlank()) null else trimmedQuery
                            pipeline.execute(explicitTarget = target) { chunk ->
                                ApplicationManager.getApplication().invokeLater {
                                    bridge.sendMessageChunk(messageId, chunk)
                                }
                            }

                            ApplicationManager.getApplication().invokeLater {
                                bridge.sendMessage("chat", "", messageId)
                            }
                        } catch (e: Exception) {
                            logger.error("TestGenerationPipeline Error", e)
                            ApplicationManager.getApplication().invokeLater {
                                bridge.sendMessage("error", "테스트 생성 중 오류가 발생했습니다: ${e.message}", messageId)
                            }
                        }
                    }
                }

                // ── 분석 문서 MD 생성 (디렉토리 선택 → 일괄 분석) ────────
                "/doc" -> {
                    logger.info("Router: /doc 분기 → 디렉토리 선택 다이얼로그")
                    val messageId = "doc_${System.currentTimeMillis()}"

                    // 네이티브 디렉토리 선택 다이얼로그
                    val descriptor = com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
                        .createSingleFolderDescriptor()
                    descriptor.title = "분석 대상 디렉토리 선택"
                    descriptor.description = "하위 폴더의 모든 소스 파일을 분석하여 Markdown 문서를 생성합니다."

                    val projectBase = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                        .findFileByPath(project.basePath ?: "")

                    com.intellij.openapi.fileChooser.FileChooser.chooseFile(
                        descriptor, project, projectBase
                    ) { selectedDir ->
                        // 소스 파일 수집
                        val files = net.ib.ixpert.ops.wuwagent.service.MarkdownFileService
                            .collectSourceFiles(selectedDir)

                        if (files.isEmpty()) {
                            bridge.sendMessage("error", "선택한 디렉토리에 분석 가능한 소스 파일이 없습니다.")
                            return@chooseFile
                        }

                        // 시작 알림
                        bridge.sendMessage("explain_start",
                            "📄 ${files.size}개 파일의 분석 문서를 생성합니다...", messageId)

                        // step_noti 인덱스
                        var notiIdx = 0

                        DocGenerateAgent().executeBatch(
                            project = project,
                            files = files,
                            onStepProgress = { fileName, current, total, status ->
                                val notiId = "${messageId}_noti_${notiIdx++}"
                                ApplicationManager.getApplication().invokeLater {
                                    bridge.sendMessage(
                                        "step_noti",
                                        "$current/$total $fileName",
                                        notiId,
                                        mapOf("status" to status)
                                    )
                                }
                            },
                            onComplete = { summary ->
                                ApplicationManager.getApplication().invokeLater {
                                    bridge.sendMessage("explain", summary, messageId)
                                }
                            },
                            onError = { errorMsg ->
                                ApplicationManager.getApplication().invokeLater {
                                    bridge.sendMessage("error", errorMsg, messageId)
                                }
                            }
                        )
                    }
                }

                // ── RAG 특화 분석 문서 생성 (FAQ 포함) ────────
                "/ragdoc" -> {
                    logger.info("Router: /ragdoc 분기 → 디렉토리 선택 다이얼로그")
                    val messageId = "rag_${System.currentTimeMillis()}"

                    val descriptor = com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
                        .createSingleFolderDescriptor()
                    descriptor.title = "RAG 전용 분석 대상 디렉토리 선택"
                    descriptor.description = "하위 폴더의 모든 소스 파일을 분석하여 FAQ가 포함된 RAG용 Markdown 문서를 생성합니다."

                    val projectBase = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                        .findFileByPath(project.basePath ?: "")

                    com.intellij.openapi.fileChooser.FileChooser.chooseFile(
                        descriptor, project, projectBase
                    ) { selectedDir ->
                        val files = net.ib.ixpert.ops.wuwagent.service.MarkdownFileService
                            .collectSourceFiles(selectedDir)

                        if (files.isEmpty()) {
                            bridge.sendMessage("error", "선택한 디렉토리에 분석 가능한 소스 파일이 없습니다.")
                            return@chooseFile
                        }

                        bridge.sendMessage("explain_start",
                            "📄 ${files.size}개 파일의 RAG 분석 문서를 생성합니다...", messageId)

                        var notiIdx = 0
                        DocGenerateAgent().executeBatch(
                            project = project,
                            files = files,
                            command = "/ragdoc",
                            onStepProgress = { fileName, current, total, status ->
                                val notiId = "${messageId}_noti_${notiIdx++}"
                                ApplicationManager.getApplication().invokeLater {
                                    bridge.sendMessage(
                                        "step_noti",
                                        "$current/$total $fileName",
                                        notiId,
                                        mapOf("status" to status)
                                    )
                                }
                            },
                            onComplete = { summary ->
                                ApplicationManager.getApplication().invokeLater {
                                    bridge.sendMessage("explain", summary, messageId)
                                }
                            },
                            onError = { errorMsg ->
                                ApplicationManager.getApplication().invokeLater {
                                    bridge.sendMessage("error", errorMsg, messageId)
                                }
                            }
                        )
                    }
                }

                // ── MetaGraph 기반 영향도 분석 (Phase 2d) ───────
                "/impact" -> {
                    logger.info("Router: /impact 분기")
                    val messageId = "impact_${System.currentTimeMillis()}"
                    
                    // 1. 타겟 파일 경로 결정 (인자 우선 -> 현재 에디터 차선)
                    var targetPath = textBody.removePrefix("/impact").trim()
                    if (targetPath.isBlank()) {
                        targetPath = editor?.virtualFile?.path ?: ""
                    }
                    
                    if (targetPath.isBlank()) {
                        bridge.sendMessage("error", "분석 대상 파일을 지정하거나 에디터에서 파일을 열어주세요.")
                        return@invokeLater
                    }

                    // 2. 프로젝트 상대 경로로 변환
                    val projectBasePath = project.basePath ?: ""
                    val relativePath = if (targetPath.startsWith(projectBasePath)) {
                        targetPath.removePrefix(projectBasePath).removePrefix("/").removePrefix("\\")
                    } else {
                        targetPath
                    }.replace("\\", "/")

                    bridge.sendMessage("explain_start", "🔍 `${relativePath}`의 변경 파급 효과를 분석하고 있습니다...", messageId)
                    
                    ApplicationManager.getApplication().executeOnPooledThread {
                        try {
                            val graphLoader = project.getService(net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.GraphLoader::class.java)
                            val projectGraph = graphLoader.loadGraph() ?: throw IllegalStateException("메타그래프를 찾을 수 없습니다. 먼저 `/metagraph` 명령어로 그래프를 생성해주세요.")
                            
                            val impactResult = net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.MetaImpactAnalyzer.analyze(projectGraph, relativePath)
                            val formattedReport = net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.ImpactGraphFormatter.format(impactResult)
                            
                            ApplicationManager.getApplication().invokeLater {
                                bridge.sendMessage("explain", formattedReport, messageId)
                            }
                        } catch (e: Exception) {
                            logger.error("MetaImpact Error", e)
                            ApplicationManager.getApplication().invokeLater {
                                bridge.sendMessage("error", "영향 분석 중 오류가 발생했습니다: ${e.message}", messageId)
                            }
                        }
                    }
                }

                // ── 프로젝트 메타 그래프 생성 ────────────────
                "/metagraph" -> {
                    logger.info("Router: /metagraph 분기 → 프로젝트 메타 그래프 생성")
                    val messageId = "metagraph_${System.currentTimeMillis()}"
                    val progressId = "${messageId}_progress"
                    bridge.sendMessage("explain_start", "🗺️ 프로젝트 구조를 분석하고 있습니다...", messageId)

                    val builder = net.ib.ixpert.ops.wuwagent.service.metagraph.ProjectGraphBuilder(project)
                    builder.buildGraphAsync(
                        onProgress = { statusMsg ->
                            ApplicationManager.getApplication().invokeLater {
                                bridge.sendMessage("step_noti", statusMsg, progressId)
                            }
                        },
                        onComplete = { graph ->
                            val stats = graph.statistics
                            val summary = buildString {
                                appendLine("# 📊 프로젝트 메타 그래프 생성 완료")
                                appendLine()
                                appendLine("## 분석 결과 요약")
                                appendLine("| 항목 | 수량 |")
                                appendLine("| :--- | ---: |")
                                appendLine("| 전체 파일 | ${stats.totalFiles}개 |")
                                appendLine("| Controller | ${stats.controllers}개 |")
                                appendLine("| Service | ${stats.services}개 |")
                                appendLine("| Repository/Mapper | ${stats.repositories}개 |")
                                appendLine("| Entity | ${stats.entities}개 |")
                                appendLine("| Configuration | ${stats.configs}개 |")
                                appendLine("| DTO/VO | ${stats.dtos}개 |")
                                appendLine("| View | ${stats.views}개 |")
                                appendLine("| Component/Filter | ${stats.components}개 |")
                                appendLine("| Utils | ${stats.utils}개 |")
                                appendLine("| 기타 | ${stats.others}개 |")
                                appendLine("| 관계 (Relationships) | ${stats.totalRelationships}개 |")
                                appendLine()
                                appendLine("📁 저장 위치: `.meta/project-graph.json`")
                            }
                            ApplicationManager.getApplication().invokeLater {
                                bridge.sendMessage("step_noti", "완료되었습니다.", progressId, mapOf("status" to "completed"))
                                bridge.sendMessage("explain", summary, messageId)
                            }
                        },
                        onError = { errorMsg ->
                            ApplicationManager.getApplication().invokeLater {
                                bridge.sendMessage("step_noti", "오류 발생: $errorMsg", progressId, mapOf("status" to "failed"))
                                bridge.sendMessage("error", errorMsg, messageId)
                            }
                        }
                    )
                }

                "/openTabs" -> {
                    logger.info("Router: /openTabs 분기")
                    val openFiles = FileEditorManager.getInstance(project).openFiles
                    val jsonArray = openFiles.joinToString(separator = ",", prefix = "[", postfix = "]") { vFile ->
                        val name = vFile.name.replace("\\", "\\\\").replace("\"", "\\\"")
                        val path = vFile.path.replace("\\", "\\\\").replace("\"", "\\\"")
                        """{"name":"$name","path":"$path"}"""
                    }
                    bridge.sendMessage("openTabs", jsonArray)
                }

                "/chat" -> {
                    logger.info("Router: /chat 분기")
                    val messageId = "msg_${System.currentTimeMillis()}"
                    // 🛎 즉시 자리 만들기 (로딩 표시 유도)
                    bridge.sendMessage("chat_start", "💬 답변을 준비 중입니다...", messageId)

                    val context = AgentContext(project, editor, textBody, command = "/chat")
                    ChatAgent().execute(
                        context, 
                        onSuccess = { res ->
                            ApplicationManager.getApplication().invokeLater {
                                bridge.sendMessage("chat", res, messageId)
                            }
                        },
                        onChunk = { chunk ->
                            ApplicationManager.getApplication().invokeLater {
                                bridge.sendMessageChunk(messageId, chunk)
                            }
                        },
                        onError = { errorMsg ->
                            ApplicationManager.getApplication().invokeLater {
                                // "__cancelled__" 는 /cancel 핸들러에서 이미 task_cancelled 전송 완료 → 중복 방지
                                if (errorMsg != "__cancelled__") {
                                    bridge.sendMessage("error", errorMsg, messageId)
                                }
                            }
                        }
                    )
                }

                // ── TaskAgent (오케스트레이터) ────────────────
                "/task" -> {
                    logger.info("Router: /task 분기 → TaskAgent 시작")
                    if (editor == null) {
                        bridge.sendMessage("error", "활성화된 에디터가 없어 작업을 실행할 수 없습니다.")
                        return@invokeLater
                    }
                    val messageId = "task_${System.currentTimeMillis()}"
                    val fileContext = buildAttachedFileContext(payload["files"] ?: "")
                    val enhancedText = if (fileContext.isNotBlank()) "$textBody\n\n$fileContext" else textBody
                    val context = AgentContext(project, editor, enhancedText, command = "/task")

                    // @ 첨부 파일이 있으면 첫 번째 파일 경로, 없으면 에디터 파일 경로 (Improve Diff 버튼용)
                    val firstAttachedFilePath: String = run {
                        val filesJson = payload["files"] ?: ""
                        if (filesJson.isBlank()) ""
                        else Regex(""""path":"([^"]+)"""").find(filesJson)
                            ?.groupValues?.get(1)?.replace("\\\\", "\\") ?: ""
                    }
                    val improveFilePath = firstAttachedFilePath.ifBlank { editor.virtualFile?.path ?: "" }
                    // 선택 범위 Diff 수정용: 백그라운드 실행 전 EDT에서 선택 상태 선제 캡처
                    // (ImproveAction 방식과 동일 — 이후 스레드에서 selection이 해제될 수 있음)
                    val selectedEditorText = if (editor.selectionModel.hasSelection())
                        editor.selectionModel.selectedText ?: "" else ""

                    // 🛎 즉시 시작 알림 (UI 스레드 큐로 보냄)
                    ApplicationManager.getApplication().invokeLater {
                        bridge.sendMessage("task_start", "✅ 의도를 분석하고 있습니다...", messageId)
                    }

                    // Step 시작 시 즉각 UI 피드백
                    val stepNotiIdx = intArrayOf(0)
                    val onStepStart = { stepLabel: String, stepMsgId: String, isApplyable: Boolean ->
                        logger.info("Router: Step 시작 알림 → $stepLabel (stepMsgId=$stepMsgId, isApplyable=$isApplyable)")
                        val notiId = "${messageId}_noti_${stepNotiIdx[0]}"
                        stepNotiIdx[0]++
                        ApplicationManager.getApplication().invokeLater {
                            bridge.sendMessage("step_noti", stepLabel, notiId, mapOf("status" to "started"))
                            when {
                                stepMsgId == messageId ->
                                    bridge.sendMessage("task_progress", "⚙️ $stepLabel LLM 응답 대기 중...", stepMsgId)
                                !isApplyable && !stepMsgId.endsWith("_s3") ->
                                    // Step2: Diff 버튼용 filePath 포함
                                    bridge.sendMessage(
                                        "task_start", "⚙️ $stepLabel LLM 응답 대기 중...", stepMsgId,
                                        mapOf(
                                            "filePath"     to improveFilePath,
                                            "hasSelection" to "false"
                                        )
                                    )
                                !isApplyable ->
                                    // Step3(안정성 평가) 등: filePath 없이 새 말풍선만 생성
                                    bridge.sendMessage("task_start", "⚙️ $stepLabel LLM 응답 대기 중...", stepMsgId)
                                else ->
                                    bridge.sendMessage("task_progress", "⚙️ $stepLabel LLM 응답 대기 중...", messageId)
                            }
                        }
                    }

                    // Step 완료 시 결과 전송 (stepMsgId: Step별 독립 말풍선 ID)
                    val stepNotiDoneIdx = intArrayOf(0)
                    val onStep = { stepLabel: String, result: TaskPipeline.StepResult, isApplyable: Boolean, stepMsgId: String ->
                        logger.info("Router: Task Step 완료 → $stepLabel (applyable=$isApplyable, success=${result.isSuccess}, scope=${result.applyScope})")
                        val notiId = "${messageId}_noti_${stepNotiDoneIdx[0]}"
                        stepNotiDoneIdx[0]++
                        ApplicationManager.getApplication().invokeLater {
                            bridge.sendMessage(
                                "step_noti", stepLabel, notiId,
                                mapOf("status" to if (result.isSuccess) "completed" else "failed")
                            )
                        }

                        when {
                            isApplyable && result.isSuccess -> {
                                // ── 코드 말풍선 (Step 2 성공) ──────────────────────────
                                // 별도 messageId로 새 말풍선 생성 → 텍스트 말풍선과 완전 분리
                                val codeMessageId = "${messageId}_code"
                                ApplicationManager.getApplication().invokeLater {
                                    bridge.sendMessage(
                                        subType = "task_code",
                                        content = "",
                                        messageId = codeMessageId,
                                        meta = mapOf(
                                            "stepLabel" to stepLabel,
                                            "applyable" to "true",
                                            "originalCode" to result.originalCode.orEmpty(),
                                            "modifiedCode" to result.modifiedCode.orEmpty(),
                                            "extractedCode" to result.extractedCode,
                                            "applyScope" to result.applyScope,
                                            "isSuccess" to "true"
                                        )
                                    )
                                }
                            }
                            isApplyable && !result.isSuccess -> {
                                // ── Step2 에러: Step1 말풍선 유지 + 별도 에러 카드 ──────
                                // Step1 말풍선의 로딩 상태만 종료하고 내용은 건드리지 않는다.
                                // 에러는 새 messageId(_err)로 별도 카드 생성.
                                val errMessageId = "${messageId}_err"
                                ApplicationManager.getApplication().invokeLater {
                                    bridge.sendMessage("task_success", "완료되었습니다.", messageId)
                                    bridge.sendMessage("task_start", "", errMessageId)
                                    bridge.sendMessage("error", result.llmResponse, errMessageId)
                                }
                            }
                            else -> {
                                // Step2 선택 범위 케이스: 원본 풀코드에서 선택 범위를 개선 코드로 교체
                                // → 3-way Diff용 modifiedFullCode 생성 (ImproveAction과 동일 방식)
                                // Step3(안정성 평가) 및 @파일 케이스(applyScope ≠ "선택 영역") 제외
                                val modifiedFullCode = if (
                                    result.applyScope == "선택 영역" &&
                                    stepMsgId != messageId &&
                                    !stepMsgId.endsWith("_s3") &&
                                    result.isSuccess &&
                                    selectedEditorText.isNotBlank()
                                ) {
                                    val fullCode = ApplicationManager.getApplication().runReadAction(
                                        com.intellij.openapi.util.Computable { editor.document.text }
                                    )
                                    val improvedCode = EditorApplyService.extractCodeBlock(result.llmResponse)
                                        .takeIf { it.isNotBlank() } ?: result.llmResponse
                                    fullCode.replaceFirst(selectedEditorText, improvedCode)
                                } else ""

                                ApplicationManager.getApplication().invokeLater {
                                    val meta = mutableMapOf(
                                        "stepLabel" to stepLabel,
                                        "applyable" to "false",
                                        "isSuccess" to result.isSuccess.toString()
                                    )
                                    if (modifiedFullCode.isNotBlank()) meta["modifiedFullCode"] = modifiedFullCode
                                    bridge.sendMessage(
                                        subType = "task_step",
                                        content = result.llmResponse,
                                        messageId = stepMsgId,
                                        meta = meta
                                    )
                                }
                            }
                        }
                    }

                    // IntentAnalyzer 키워드 매핑 (LLM 호출 없이 즉시 반환)
                    val client = WuwLlmService.getClient()
                    when (IntentAnalyzer.analyze(enhancedText, client)) {

                        TaskPipeline.ExplainTask -> {
                            ExplainAgent().execute(context,
                                onSuccess = { _ ->
                                    ApplicationManager.getApplication().invokeLater {
                                        bridge.sendMessage("task_success", "완료되었습니다.", messageId)
                                    }
                                },
                                onChunk = { chunk ->
                                    ApplicationManager.getApplication().invokeLater {
                                        bridge.sendMessageChunk(messageId, chunk)
                                    }
                                },
                                onError = { errorMsg ->
                                    ApplicationManager.getApplication().invokeLater {
                                        if (errorMsg != "__cancelled__") bridge.sendMessage("error", errorMsg, messageId)
                                    }
                                }
                            )
                        }

                        TaskPipeline.Impact -> {
                            ImpactAgent().execute(context,
                                onSuccess = { _ ->
                                    ApplicationManager.getApplication().invokeLater {
                                        bridge.sendMessage("task_success", "완료되었습니다.", messageId)
                                    }
                                },
                                onChunk = { chunk ->
                                    ApplicationManager.getApplication().invokeLater {
                                        bridge.sendMessageChunk(messageId, chunk)
                                    }
                                },
                                onError = { errorMsg ->
                                    ApplicationManager.getApplication().invokeLater {
                                        bridge.sendMessage("error", errorMsg, messageId)
                                    }
                                }
                            )
                        }

                        TaskPipeline.QueryValidation -> {
                            QueryValidationAgent().execute(context,
                                onSuccess = { _ ->
                                    ApplicationManager.getApplication().invokeLater {
                                        bridge.sendMessage("task_success", "완료되었습니다.", messageId)
                                    }
                                },
                                onChunk = { chunk ->
                                    ApplicationManager.getApplication().invokeLater {
                                        bridge.sendMessageChunk(messageId, chunk)
                                    }
                                },
                                onError = { errorMsg ->
                                    ApplicationManager.getApplication().invokeLater {
                                        bridge.sendMessage("error", errorMsg, messageId)
                                    }
                                }
                            )
                        }

                        TaskPipeline.UnitTestReport -> {
                            UnitTestReportAgent().execute(context,
                                onSuccess = { res ->
                                    ApplicationManager.getApplication().invokeLater {
                                        bridge.sendMessage("explain", res, messageId)
                                    }
                                },
                                onChunk = { chunk ->
                                    ApplicationManager.getApplication().invokeLater {
                                        bridge.sendMessageChunk(messageId, chunk)
                                    }
                                },
                                onError = { errorMsg ->
                                    ApplicationManager.getApplication().invokeLater {
                                        if (errorMsg != "__cancelled__") {
                                            bridge.sendMessage("error", errorMsg, messageId)
                                        }
                                    }
                                }
                            )
                        }

                        TaskPipeline.DocGenerate -> {
                            DocGenerateAgent().execute(context,
                                onSuccess = { res ->
                                    ApplicationManager.getApplication().invokeLater {
                                        bridge.sendMessage("explain", res, messageId)
                                    }
                                },
                                onChunk = { chunk ->
                                    ApplicationManager.getApplication().invokeLater {
                                        bridge.sendMessageChunk(messageId, chunk)
                                    }
                                },
                                onError = { errorMsg ->
                                    ApplicationManager.getApplication().invokeLater {
                                        if (errorMsg != "__cancelled__") {
                                            bridge.sendMessage("error", errorMsg, messageId)
                                        }
                                    }
                                }
                            )
                        }

                        else -> {
                            val agent = TaskAgent(messageId, onStep, onStepStart)
                            agent.execute(
                                context,
                                onSuccess = { _ ->
                                    ApplicationManager.getApplication().invokeLater {
                                        bridge.sendMessage("task_success", "완료되었습니다.", messageId)
                                    }
                                },
                                onChunk = null,
                                onError = { errorMsg ->
                                    ApplicationManager.getApplication().invokeLater {
                                        bridge.sendMessage("error", errorMsg, messageId)
                                    }
                                }
                            )
                        }
                    }
                }

                // ── Apply: 에디터에 코드 쓰기 ────────────────
                "/apply" -> {
                    logger.info("Router: /apply 분기 → EditorApplyService")
                    val messageId = payload["id"] ?: ""
                    val scope = payload["scope"] ?: ""
                    val original = payload["original"] ?: ""
                    val result = EditorApplyService.apply(project, textBody, scope, original)
                    
                    if (result.startsWith("[오류]")) {
                        bridge.sendMessage("task_progress", result, messageId)
                    } else {
                        bridge.sendMessage("apply_success", result, messageId)
                    }
                }

                // ── Diff: IDE 내장 Diff 창 띄우기 (블록 단위 적용 << 지원) ─────────────
                "/viewDiff" -> {
                    val filePath = payload["filePath"] ?: ""

                    if (filePath.isNotBlank()) {
                        // ── filePath 기반 경로: Improve Step2 Diff 버튼 ──────────────────
                        // Step2 LLM 응답(SEARCH/REPLACE 또는 풀코드)을 원본 파일에 적용하여 Diff 표시
                        logger.info("Router: /viewDiff (filePath 기반) → $filePath")
                        val localFs = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                        val vFile = localFs.findFileByPath(filePath)
                        if (vFile == null) {
                            bridge.sendMessage("error", "파일을 찾을 수 없습니다: $filePath")
                            return@invokeLater
                        }
                        // 닫혀 있으면 다시 열기
                        FileEditorManager.getInstance(project).openFile(vFile, false)
                        val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vFile)
                        if (document == null) {
                            bridge.sendMessage("error", "파일 내용을 읽을 수 없습니다.")
                            return@invokeLater
                        }
                        val originalText = document.text
                        // SEARCH/REPLACE 적용 → 실패 시 코드 블록 추출 → 최후엔 원문 그대로
                        val rightFullText = EditorApplyService.applySearchReplace(originalText, textBody)
                            ?: EditorApplyService.extractCodeBlock(textBody).takeIf { it.isNotBlank() }
                            ?: textBody
                        EditorDiffService.showDiff(project, vFile, rightFullText, "AI 코드 개선 제안 (${vFile.name})")
                        return@invokeLater
                    }

                    // ── 기존 scope 기반 경로 ───────────────────────────────────────────
                    val original = payload["original"] ?: ""
                    val modified = payload["modified"] ?: ""
                    val scope    = payload["scope"] ?: "File"

                    if (original == modified && original.isNotBlank()) {
                        logger.warn("Router: /viewDiff 무시됨 → original과 modified가 완벽히 동일합니다.")
                        bridge.sendMessage("task_progress", "⚠️ 원본과 개선된 코드가 동일하여 Diff를 열 수 없습니다.")
                        return@invokeLater
                    }

                    val fileEditorManager = FileEditorManager.getInstance(project)
                    val targetFile = fileEditorManager.openFiles.firstOrNull { it.name == scope }
                        ?: fileEditorManager.selectedTextEditor?.virtualFile

                    if (targetFile == null) {
                        logger.warn("Router: /viewDiff 대상을 찾을 수 없음 (scope=$scope)")
                        bridge.sendMessage("error", "적용 대상 파일($scope)을 찾을 수 없어 Diff 뷰어를 열 수 없습니다.")
                        return@invokeLater
                    }

                    val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(targetFile)
                    if (document == null) {
                        bridge.sendMessage("error", "해당 파일의 내용을 읽을 수 없습니다.")
                        return@invokeLater
                    }

                    val fullOriginalText = document.text
                    val rightFullText = if (original.isNotBlank() && original != modified) {
                        fullOriginalText.replaceFirst(original, modified)
                    } else {
                        modified
                    }

                    EditorDiffService.showDiff(project, targetFile, rightFullText, "AI 코드 개선 제안 ($scope)")
                }

                // [이전 코드 백업 - 선택 영역 SimpleDiff 2-way 비교, 드래그 케이스도 3-way Diff로 대체됨]
                // "/viewDiffSimple" -> {
                //     logger.info("Router: /viewDiffSimple 분기 → SimpleDiffRequest")
                //     val originalCode = payload["originalCode"] ?: ""
                //     // SEARCH/REPLACE 적용 시도 → 코드 블록 추출 → 원문 순으로 fallback
                //     val rightText = EditorApplyService.applySearchReplace(originalCode, textBody)
                //         ?: EditorApplyService.extractCodeBlock(textBody).takeIf { it.isNotBlank() }
                //         ?: textBody
                //     val factory = com.intellij.diff.DiffContentFactory.getInstance()
                //     val request = com.intellij.diff.requests.SimpleDiffRequest(
                //         "AI 코드 개선 제안 (선택 영역)",
                //         factory.create(originalCode),
                //         factory.create(rightText),
                //         "원본 선택 코드",
                //         "개선된 코드"
                //     )
                //     com.intellij.diff.DiffManager.getInstance().showDiff(project, request)
                // }

                // ── Undo: IDE 기본 Undo 실행 (단축키 Ctrl+Z와 동일) ──────────────
                "/undo" -> {
                    logger.info("Router: /undo 분기 → IDE 기본 Undo (UndoManager) 실행")
                    val messageId = payload["id"] ?: ""
                    
                    val fileEditorManager = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
                    val selectedEditor = fileEditorManager.selectedEditor
                    
                    if (selectedEditor != null) {
                        val undoManager = com.intellij.openapi.command.undo.UndoManager.getInstance(project)
                        if (undoManager.isUndoAvailable(selectedEditor)) {
                            // Undo는 EDT에서 실행되어야 함
                            ApplicationManager.getApplication().invokeLater {
                                undoManager.undo(selectedEditor)
                                logger.info("Router: Undo 실행 성공")
                                bridge.sendMessage("undo_success", "에디터 적용 사항이 취소되었습니다. (Undo)", messageId)
                            }
                        } else {
                            logger.warn("Router: Undo 불가능한 상태")
                            bridge.sendMessage("error", "취소(Undo)할 내역이 없습니다.", messageId)
                        }
                    } else {
                        bridge.sendMessage("error", "활성화된 에디터를 찾을 수 없습니다.")
                    }
                }

                // ── SaveMarkdown: 분석 텍스트를 Markdown 파일로 저장 ──────────
                "/saveMarkdown" -> {
                    logger.info("Router: /saveMarkdown → 파일 저장 다이얼로그 실행")
                    val content = payload["content"] ?: textBody
                    if (content.isBlank()) {
                        bridge.sendMessage("error", "저장할 내용이 없습니다.")
                        return@invokeLater
                    }
                    ApplicationManager.getApplication().invokeLater {
                        val descriptor = com.intellij.openapi.fileChooser.FileSaverDescriptor(
                            "분석 결과 저장",
                            "Markdown 파일로 저장합니다.",
                            "md"
                        )
                        val dialog = com.intellij.openapi.fileChooser.FileChooserFactory.getInstance()
                            .createSaveFileDialog(descriptor, project)
                        val projectBase = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                            .findFileByPath(project.basePath ?: "")
                        val result = dialog.save(projectBase, "analysis_result.md")
                        if (result != null) {
                            try {
                                // 사용자가 선택한 외부 파일이므로 VFS 대신 java.io.File 로 직접 쓴다.
                                // (VfsUtil.saveText 는 write-action 요구 → EDT 에서 예외 → 빈 파일 남음)
                                val file = result.file
                                file.parentFile?.mkdirs()
                                file.writeText(content, Charsets.UTF_8)
                                com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
                                logger.info("Router: Markdown 저장 완료 → ${file.absolutePath} (${content.length}자)")
                            } catch (e: Exception) {
                                logger.error("Router: Markdown 저장 실패", e)
                                bridge.sendMessage("error", "파일 저장 중 오류가 발생했습니다: ${e.message}")
                            }
                        }
                    }
                }

                // ── Cancel: 실행 중인 요청 취소 ──────────────
                "/cancel" -> {
                    // activeMessageId를 먼저 읽어둔 뒤 cancel — 취소 후에는 null로 초기화되므로 순서 중요
                    val activeId = TaskCancellationToken.activeMessageId
                    logger.info("Router: /cancel → TaskCancellationToken.cancel() (activeMessageId=$activeId)")
                    TaskCancellationToken.cancel()
                    bridge.sendMessage("task_cancelled", "⛔ 작업이 취소되었습니다.", activeId)
                }

                // ── Settings: 네이티브 설정 창 열기 ──────────────
                "/openSettings" -> {
                    logger.info("Router: /openSettings → ShowSettingsUtil 실행")
                    com.intellij.openapi.options.ShowSettingsUtil.getInstance().showSettingsDialog(project, "iXpert AI Assistant")
                }

                // ── Alert: WebView에서 네이티브 메시지 박스 띄우기 ──────────────
                "/alert" -> {
                    val title = payload["title"] ?: "iXpert AI Assistant"
                    val msg = payload["message"] ?: textBody
                    val type = payload["type"] ?: "info"
                    
                    logger.info("Router: /alert 수신 (type=$type, title=$title)")
                    if (type == "error") {
                        com.intellij.openapi.ui.Messages.showErrorDialog(project, msg, title)
                    } else {
                        com.intellij.openapi.ui.Messages.showInfoMessage(project, msg, title)
                    }
                }

                // ── Test Connection: Ollama 서버 연결 테스트 ──────────────
                "/testConnection" -> {
                    val settings = net.ib.ixpert.ops.wuwagent.setting.SettingsState.getInstance().state
                    val defaultUrl = when (settings.apiType) {
                        net.ib.ixpert.ops.wuwagent.setting.SettingsState.ApiType.OLLAMA -> settings.ollamaServerUrl
                        net.ib.ixpert.ops.wuwagent.setting.SettingsState.ApiType.AIPRO -> settings.aiproServerUrl
                        else -> settings.openaiServerUrl
                    }
                    val baseUrl = payload["baseUrl"] ?: defaultUrl
                    val apiKey = payload["apiKey"] ?: settings.apiKey
                    logger.info("Router: /testConnection 실행 (baseUrl=$baseUrl)")
                    net.ib.ixpert.ops.wuwagent.service.WuwLlmService.testConnection(null, baseUrl, apiKey)
                }

                // ── Fetch Models: 모델 리스트 조회 ──────────────
                "/fetchModels" -> {
                    val settings = net.ib.ixpert.ops.wuwagent.setting.SettingsState.getInstance().state
                    val defaultUrl = when (settings.apiType) {
                        net.ib.ixpert.ops.wuwagent.setting.SettingsState.ApiType.OLLAMA -> settings.ollamaServerUrl
                        net.ib.ixpert.ops.wuwagent.setting.SettingsState.ApiType.AIPRO -> settings.aiproServerUrl
                        else -> settings.openaiServerUrl
                    }
                    val baseUrl = payload["baseUrl"] ?: defaultUrl
                    val apiKey = payload["apiKey"] ?: settings.apiKey
                    logger.info("Router: /fetchModels 실행 (baseUrl=$baseUrl)")
                    
                    ApplicationManager.getApplication().executeOnPooledThread {
                        val models = net.ib.ixpert.ops.wuwagent.agent.SettingsAgent.fetchModelsSilent(baseUrl, apiKey)
                        ApplicationManager.getApplication().invokeLater {
                            if (models != null) {
                                bridge.sendMessage("fetched_models", models.joinToString(","))
                            } else {
                                bridge.sendMessage("fetched_models_error", "모델 조회 실패")
                            }
                        }
                    }
                }

                // ── Change Model: 즉시 모델명 변경 ──────────────
                "/changeModel" -> {
                    val targetModel = payload["model"]
                    if (targetModel.isNullOrBlank()) {
                        bridge.sendMessage("fetched_models_error", "변경할 모델명이 없습니다.")
                        return@invokeLater
                    }
                    val settings = net.ib.ixpert.ops.wuwagent.setting.SettingsState.getInstance().state
                    settings.model = targetModel
                    logger.info("Router: 모델 변경 성공 → $targetModel")

                    com.intellij.openapi.project.ProjectManager.getInstance().openProjects.forEach { p ->
                        net.ib.ixpert.ops.wuwagent.ui.bridge.JcefBridge.getInstance(p).sendMessage("selected_model", targetModel)
                    }
                }

                // ── Chat History: 대화 저장 ──────────────
                "/saveChat" -> {
                    val id = payload["id"] ?: return@invokeLater
                    val title = payload["title"] ?: ""
                    val messages = payload["messages"] ?: "[]"
                    println("saveChat 수신: id=$id, title=$title")
                    ApplicationManager.getApplication().executeOnPooledThread {
                        net.ib.ixpert.ops.wuwagent.service.ChatHistoryService.saveChat(id, title, messages)
                    }
                }

                // ── Chat History: 마지막 채팅 자동 복원 ──────────────
                "/loadLastChat" -> {
                    println("loadLastChat 수신됨")
                    ApplicationManager.getApplication().executeOnPooledThread {
                        val json = net.ib.ixpert.ops.wuwagent.service.ChatHistoryService.loadLastChat()
                        println("loadLastChat 결과: ${if (json != null) "성공 (${json.length}bytes)" else "없음"}")
                        if (json != null) {
                            ApplicationManager.getApplication().invokeLater {
                                bridge.sendMessage("chat_loaded", json)
                            }
                        }
                    }
                }

                // ── Chat History: 대화 불러오기 ──────────────
                "/loadChat" -> {
                    val id = payload["id"] ?: return@invokeLater
                    println("loadChat 수신: id=$id")
                    ApplicationManager.getApplication().executeOnPooledThread {
                        val json = net.ib.ixpert.ops.wuwagent.service.ChatHistoryService.loadChat(id)
                        println("loadChat 파일 읽기 결과: ${if (json != null) "성공 (${json.length}bytes)" else "파일 없음"}")
                        ApplicationManager.getApplication().invokeLater {
                            if (json != null) {
                                println("loadChat bridge 전송: chat_loaded")
                                bridge.sendMessage("chat_loaded", json)
                            } else {
                                bridge.sendMessage("error", "채팅 기록을 불러올 수 없습니다.")
                            }
                        }
                    }
                }

                // ── Chat History: 대화 목록 조회 ──────────────
                "/listChats" -> {
                    ApplicationManager.getApplication().executeOnPooledThread {
                        val json = net.ib.ixpert.ops.wuwagent.service.ChatHistoryService.listChats()
                        ApplicationManager.getApplication().invokeLater {
                            bridge.sendMessage("chat_list", json)
                        }
                    }
                }

                // ── Chat History: 대화 삭제 ──────────────
                "/deleteChat" -> {
                    val id = payload["id"] ?: return@invokeLater
                    ApplicationManager.getApplication().executeOnPooledThread {
                        net.ib.ixpert.ops.wuwagent.service.ChatHistoryService.deleteChat(id)
                    }
                }

                // ── Open In Editor: 파일 경로로 IDE 에디터 열기 ──────────────
                "/openInEditor" -> {
                    val filePath = payload["filePath"] ?: textBody
                    logger.info("Router: /openInEditor → $filePath")
                    if (filePath.isBlank()) {
                        bridge.sendMessage("error", "파일 경로가 없습니다.")
                        return@invokeLater
                    }
                    val vFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                        .findFileByPath(filePath)
                    if (vFile == null) {
                        com.intellij.openapi.ui.Messages.showInfoMessage(
                            project,
                            "파일을 찾을 수 없습니다:\n$filePath",
                            "iXpert AI Assistant"
                        )
                        return@invokeLater
                    }
                    FileEditorManager.getInstance(project).openFile(vFile, true)
                }

                else -> {
                    logger.warn("Router: 정의되지 않은 명령어 수신 → $command")
                    bridge.sendMessage("error", "알 수 없는 명령어: $command")
                }
            }
        }
    }
}
