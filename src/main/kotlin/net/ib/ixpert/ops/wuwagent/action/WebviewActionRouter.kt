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
import net.ib.ixpert.ops.wuwagent.service.EditorApplyService
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
                    val onStep = { stepLabel: String, content: String, isApplyable: Boolean ->
                        logger.info("Router: Task Step 완료 → $stepLabel (applyable=$isApplyable)")
                        bridge.sendMessage(
                            subType = "task_step",
                            content = content,
                            meta = mapOf(
                                "stepLabel"  to stepLabel,
                                "applyable"  to isApplyable.toString()
                            )
                        )
                    }

                    TaskAgent(onStep, onStepStart).execute(context) { _ -> /* task_done: no-op */ }
                }

                // ── Apply: 에디터에 코드 쓰기 ────────────────
                "/apply" -> {
                    logger.info("Router: /apply 분기 → EditorApplyService")
                    val codeToApply = EditorApplyService.extractCodeBlock(textBody)
                    val result = EditorApplyService.apply(project, codeToApply)
                    bridge.sendMessage("apply_result", result)
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
