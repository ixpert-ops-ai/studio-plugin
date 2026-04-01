package net.ib.ixpert.ops.wuwagent.action

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import net.ib.ixpert.ops.wuwagent.agent.AgentContext
import net.ib.ixpert.ops.wuwagent.agent.ChatAgent
import net.ib.ixpert.ops.wuwagent.agent.ExplainAgent
import net.ib.ixpert.ops.wuwagent.agent.TaskAgent
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

                    // Step별 중간 결과 콜백: bridge.sendMessage("task_step", ...)
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

                    TaskAgent(onStep).execute(context) { res ->
                        // "__task_done__" 신호는 UI에 직접 노출하지 않음
                        if (res != "__task_done__") bridge.sendMessage("task_done", res)
                    }
                }

                // ── Apply: 에디터에 코드 쓰기 ────────────────
                "/apply" -> {
                    logger.info("Router: /apply 분기 → EditorApplyService")
                    val codeToApply = EditorApplyService.extractCodeBlock(textBody)
                    val result = EditorApplyService.apply(project, codeToApply)
                    bridge.sendMessage("apply_result", result)
                }

                else -> {
                    logger.warn("Router: 정의되지 않은 명령어 수신 → $command")
                    bridge.sendMessage("error", "알 수 없는 명령어: $command")
                }
            }
        }
    }
}
