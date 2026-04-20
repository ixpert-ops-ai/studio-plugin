package net.ib.ixpert.ops.wuwagent.action

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import net.ib.ixpert.ops.wuwagent.agent.AgentContext
import net.ib.ixpert.ops.wuwagent.agent.ChatAgent
import net.ib.ixpert.ops.wuwagent.agent.ExplainAgent
import net.ib.ixpert.ops.wuwagent.agent.TaskAgent
import net.ib.ixpert.ops.wuwagent.agent.TaskCancellationToken
import net.ib.ixpert.ops.wuwagent.agent.TaskPipeline
import net.ib.ixpert.ops.wuwagent.service.EditorApplyService
import net.ib.ixpert.ops.wuwagent.service.EditorDiffService
import net.ib.ixpert.ops.wuwagent.service.TestFileService
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

    /**
     * 소스 파일 경로로부터 테스트 파일 경로를 추론합니다.
     * - src/main/kotlin/... → src/test/kotlin/...Test.kt
     * - src/main/java/...   → src/test/java/...Test.java
     * - *.tsx / *.ts / *.js → *.test.tsx / *.test.ts / *.test.js
     */
    private fun resolveTestFilePath(basePath: String, sourceFileName: String): String {
        val ext = sourceFileName.substringAfterLast('.', "")
        val nameWithoutExt = sourceFileName.substringBeforeLast('.')

        // JS/TS 계열: 같은 디렉터리에 .test.ext 패턴
        if (ext in listOf("ts", "tsx", "js", "jsx")) {
            return "$basePath/src/__tests__/${nameWithoutExt}.test.$ext"
        }

        // JVM 계열: src/main → src/test 치환 + Test 접미사
        // 현재 에디터에서 열린 파일의 프로젝트 내 상대 경로를 찾아야 하지만,
        // sourceFileName만 있으므로 관례적 경로를 사용
        val testSuffix = when (ext) {
            "kt", "kts" -> "Test.kt"
            "java"      -> "Test.java"
            "py"        -> "_test.py"
            "go"        -> "_test.go"
            else        -> "Test.$ext"
        }

        val testDir = when (ext) {
            "kt", "kts" -> "$basePath/src/test/kotlin"
            "java"      -> "$basePath/src/test/java"
            "py"        -> "$basePath/tests"
            "go"        -> "$basePath"
            else        -> "$basePath/src/test"
        }

        return "$testDir/${nameWithoutExt}$testSuffix"
    }

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

                    val context = AgentContext(project, editor, textBody)
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

                    val context = AgentContext(project, editor, textBody)
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
                    val context = AgentContext(project, editor, enhancedText)

                    // 🛎 즉시 시작 알림 (UI 스레드 큐로 보냄)
                    ApplicationManager.getApplication().invokeLater {
                        bridge.sendMessage("task_start", "✅ 의도를 분석하고 있습니다...", messageId)
                    }

                    // Step 시작 시 즉각 UI 피드백
                    val stepNotiIdx = intArrayOf(0)
                    val onStepStart = { stepLabel: String ->
                        logger.info("Router: Step 시작 알림 → $stepLabel")
                        val notiId = "${messageId}_noti_${stepNotiIdx[0]}"
                        stepNotiIdx[0]++
                        ApplicationManager.getApplication().invokeLater {
                            bridge.sendMessage("step_noti", stepLabel, notiId, mapOf("status" to "started"))
                            bridge.sendMessage("task_progress", "⚙️ $stepLabel LLM 응답 대기 중...", messageId)
                        }
                    }

                    // Step 완료 시 결과 전송
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
                                // ── 텍스트 말풍선 (Step 1: Analyze / Explain) ───────────
                                // 코드 블록 제거 후 설명 텍스트만 기존 말풍선에 누적
                                val displayContent = result.llmResponse
                                    .replace(Regex("```[\\w]*\\n?[\\s\\S]*?```"), "")
                                    .trim()
                                ApplicationManager.getApplication().invokeLater {
                                    bridge.sendMessage(
                                        subType = "task_step",
                                        content = displayContent,
                                        messageId = messageId,
                                        meta = mapOf(
                                            "stepLabel" to stepLabel,
                                            "applyable" to "false",
                                            "isSuccess" to result.isSuccess.toString()
                                        )
                                    )
                                }
                            }
                        }
                    }

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

                    // 선택 영역 단위의 Diff일 경우 전체 문서 내에서 해당 영역만 교체하여 Full Text를 생성합니다.
                    val fullOriginalText = document.text
                    val rightFullText = if (original.isNotBlank() && original != modified) {
                        // 문서 전체에서 original을 modified로 치환 (첫 번째 일치 항목 안전 교체)
                        fullOriginalText.replaceFirst(original, modified)
                    } else {
                        modified
                    }
                    
                    EditorDiffService.showDiff(project, targetFile, rightFullText, "AI 코드 개선 제안 ($scope)")
                }

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

                // ── CreateTestFile: 테스트 코드를 파일로 생성 ──────────────
                "/createTestFile" -> {
                    logger.info("Router: /createTestFile 분기")
                    val code = payload["code"] ?: ""
                    val sourceFile = payload["sourceFile"] ?: ""
                    val messageId = payload["id"] ?: ""

                    if (code.isBlank()) {
                        bridge.sendMessage("error", "생성할 테스트 코드가 없습니다.", messageId)
                        return@invokeLater
                    }

                    val basePath = project.basePath
                    if (basePath == null) {
                        bridge.sendMessage("error", "프로젝트 경로를 찾을 수 없습니다.", messageId)
                        return@invokeLater
                    }

                    val testFilePath = TestFileService.resolveTestFilePath(basePath, sourceFile, code)

                    ApplicationManager.getApplication().invokeLater {
                        val testFile = java.io.File(testFilePath)

                        // 이미 존재하면 확인 다이얼로그
                        if (testFile.exists()) {
                            val result = com.intellij.openapi.ui.Messages.showYesNoDialog(
                                project,
                                "테스트 파일이 이미 존재합니다:\n${testFile.name}\n\n덮어쓰시겠습니까?",
                                "테스트 파일 생성",
                                com.intellij.openapi.ui.Messages.getQuestionIcon()
                            )
                            if (result != com.intellij.openapi.ui.Messages.YES) return@invokeLater
                        }

                        try {
                            val vFile = TestFileService.saveTestFile(testFilePath, code)
                            vFile?.let {
                                com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
                                    .openFile(it, true)
                            }

                            val relativePath = testFilePath.removePrefix("$basePath/")
                            bridge.sendMessage("test_file_created",
                                "테스트 파일이 생성되었습니다: $relativePath", messageId)
                            logger.info("Router: 테스트 파일 생성 완료 → $testFilePath")
                        } catch (e: Exception) {
                            logger.error("Router: 테스트 파일 생성 실패", e)
                            bridge.sendMessage("error", "테스트 파일 생성 중 오류: ${e.message}", messageId)
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
                    val baseUrl = payload["baseUrl"] ?: settings.baseUrl
                    val apiKey = payload["apiKey"] ?: settings.apiKey
                    logger.info("Router: /testConnection 실행 (baseUrl=$baseUrl)")
                    net.ib.ixpert.ops.wuwagent.service.WuwLlmService.testConnection(null, baseUrl, apiKey)
                }

                // ── Fetch Models: 모델 리스트 조회 ──────────────
                "/fetchModels" -> {
                    val settings = net.ib.ixpert.ops.wuwagent.setting.SettingsState.getInstance().state
                    val baseUrl = payload["baseUrl"] ?: settings.baseUrl
                    val apiKey = payload["apiKey"] ?: settings.apiKey
                    logger.info("Router: /fetchModels 실행 (baseUrl=$baseUrl)")
                    
                    ApplicationManager.getApplication().executeOnPooledThread {
                        val models = net.ib.ixpert.ops.wuwagent.agent.SettingsAgent.fetchModelsSilent(baseUrl, apiKey)
                        ApplicationManager.getApplication().invokeLater {
                            val bridge = JcefBridge.getInstance(project)
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

                else -> {
                    logger.warn("Router: 정의되지 않은 명령어 수신 → $command")
                    bridge.sendMessage("error", "알 수 없는 명령어: $command")
                }
            }
        }
    }
}
