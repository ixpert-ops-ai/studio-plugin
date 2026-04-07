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
                    val context = AgentContext(project, editor, textBody)
                    ExplainAgent().execute(context) { res ->
                        bridge.sendMessage("explain", res)
                    }
                }

                "/chat" -> {
                    logger.info("Router: /chat 분기")
                    val context = AgentContext(project, editor, textBody)
                    ChatAgent().execute(context) { res ->
                        bridge.sendMessage("chat", res)
                    }
                }

                // ── TaskAgent (오케스트레이터) ────────────────
                "/task" -> {
                    logger.info("Router: /task 분기 → TaskAgent 시작")
                    val context = AgentContext(project, editor, textBody)

                    // 🛎 즉시 시작 알림 (LLM 호출 전, 즉각 전송)
                    bridge.sendMessage("task_start", "✅ 의도를 분석하고 있습니다...")

                    // Step 시작 시 즉각 UI 피드백 (LLM 응답 기다리는 동안 사용자에게 진행 표시)
                    val onStepStart = { stepLabel: String ->
                        logger.info("Router: Step 시작 알림 → $stepLabel")
                        bridge.sendMessage("task_progress", "⚙️ $stepLabel LLM 응답 대기 중... (모델 크기에 따라 수 분 소요)")
                    }

                    // Step 완료 시 결과 전송
                    val onStep = { stepLabel: String, result: TaskPipeline.StepResult, isApplyable: Boolean ->
                        logger.info("Router: Task Step 완료 → $stepLabel (applyable=$isApplyable, scope=${result.applyScope})")
                        bridge.sendMessage(
                            subType = "task_step",
                            content = result.llmResponse,
                            meta = mapOf(
                                "stepLabel" to stepLabel,
                                "applyable" to (if (result.isSuccess && isApplyable) "true" else "false"),
                                "originalCode" to result.originalCode.orEmpty(),
                                "modifiedCode" to result.modifiedCode.orEmpty(),
                                "extractedCode" to result.extractedCode,
                                "applyScope" to result.applyScope,
                                "isSuccess" to result.isSuccess.toString()
                            )
                        )
                    }

                    TaskAgent(onStep, onStepStart).execute(context) { _ -> /* task_done: no-op */ }
                }

                // ── Apply: 에디터에 코드 쓰기 ────────────────
                "/apply" -> {
                    logger.info("Router: /apply 분기 → EditorApplyService")
                    val messageId = payload["id"] ?: ""
                    val scope = payload["scope"] ?: ""
                    val original = payload["original"] ?: ""
                    val result = EditorApplyService.apply(project, textBody, scope, original)
                    
                    if (result.startsWith("[오류]")) {
                        bridge.sendMessage("task_progress", result)
                    } else {
                        bridge.sendMessage("apply_success", result, mapOf("id" to messageId))
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
                                bridge.sendMessage("undo_success", "에디터 적용 사항이 취소되었습니다. (Undo)", mapOf("id" to messageId))
                            }
                        } else {
                            logger.warn("Router: Undo 불가능한 상태")
                            bridge.sendMessage("error", "취소(Undo)할 내역이 없습니다.")
                        }
                    } else {
                        bridge.sendMessage("error", "활성화된 에디터를 찾을 수 없습니다.")
                    }
                }

                // ── Cancel: 실행 중인 Task 취소 ──────────────
                "/cancel" -> {
                    logger.info("Router: /cancel → TaskCancellationToken.cancel()")
                    TaskCancellationToken.cancel()
                    bridge.sendMessage("task_cancelled", "⛔ 작업이 취소되었습니다.")
                }

                else -> {
                    logger.warn("Router: 정의되지 않은 명령어 수신 → $command")
                    bridge.sendMessage("error", "알 수 없는 명령어: $command")
                }
            }
        }
    }
}
