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
                    bridge.sendMessage("explain_start", "🔍 코드를 분석하고 있습니다...", messageId)

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
                    val messageId = "task_${System.currentTimeMillis()}"
                    val context = AgentContext(project, editor, textBody)

                    // 🛎 즉시 시작 알림 (UI 스레드 큐로 보냄)
                    ApplicationManager.getApplication().invokeLater {
                        bridge.sendMessage("task_start", "✅ 의도를 분석하고 있습니다...", messageId)
                    }

                    // Step 시작 시 즉각 UI 피드백
                    val onStepStart = { stepLabel: String ->
                        logger.info("Router: Step 시작 알림 → $stepLabel")
                        ApplicationManager.getApplication().invokeLater {
                            bridge.sendMessage("task_progress", "⚙️ $stepLabel LLM 응답 대기 중...", messageId)
                        }
                    }

                    // Step 완료 시 결과 전송
                    val onStep = { stepLabel: String, result: TaskPipeline.StepResult, isApplyable: Boolean, stepMsgId: String ->
                        logger.info("Router: Task Step 완료 → $stepLabel (applyable=$isApplyable, success=${result.isSuccess}, scope=${result.applyScope})")

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
                                result.getVirtualFile(true)?.let { vFile ->
                                    com.intellij.openapi.vfs.VfsUtil.saveText(vFile, content)
                                    logger.info("Router: Markdown 저장 완료 → ${vFile.path}")
                                } ?: run {
                                    val file = result.file
                                    file.writeText(content)
                                    logger.info("Router: Markdown 저장 완료 (fallback) → ${file.absolutePath}")
                                }
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
                    net.ib.ixpert.ops.wuwagent.service.WuwLlmService.fetchModels(null, baseUrl, apiKey) { models ->
                        // 조회 성공 시 WebView로 다시 전달하거나 로그만 남김 (현재는 네이티브 팝업이 주 목적)
                        logger.info("Router: Models fetched successfully: $models")
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
