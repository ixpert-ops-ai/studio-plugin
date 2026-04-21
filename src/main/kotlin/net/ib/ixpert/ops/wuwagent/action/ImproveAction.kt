package net.ib.ixpert.ops.wuwagent.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import net.ib.ixpert.ops.wuwagent.agent.AgentContext
import net.ib.ixpert.ops.wuwagent.agent.TaskAgent
import net.ib.ixpert.ops.wuwagent.agent.TaskPipeline
import net.ib.ixpert.ops.wuwagent.ui.bridge.JcefBridge
import com.intellij.openapi.diagnostic.Logger

/**
 * 에디터 우클릭 → "Improve Code" 액션.
 * TaskAgent 파이프라인을 통해 코드 개선(Analyze → Improve)을 실행합니다.
 */
class ImproveAction : AnAction() {
    private val logger = Logger.getInstance(ImproveAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return

        val bridge = JcefBridge.getInstance(project)
        val messageId = "task_${System.currentTimeMillis()}"
        val fileName = editor.virtualFile?.name ?: ""
        val hasSelection = editor.selectionModel.hasSelection()

        // 개선 요청 텍스트 (라우터가 intent 분석 후 ImprovePipeline으로 실행)
        val improveRequest = "/improve 선택된 코드를 분석하고 최선의 방법으로 개선해주세요."
        val context = AgentContext(project, editor, improveRequest)

        ApplicationManager.getApplication().invokeLater {
            bridge.sendMessage("task_start", "✨ 코드 개선을 시작합니다...", messageId)
        }

        val stepNotiIdx = intArrayOf(0)
        var analysisHeaderSent = false
        val onStepStart = { stepLabel: String, stepMsgId: String, isApplyable: Boolean ->
            logger.info("ImproveAction: Step 시작 → $stepLabel (stepMsgId=$stepMsgId, isApplyable=$isApplyable)")
            val notiId = "${messageId}_noti_${stepNotiIdx[0]}"
            stepNotiIdx[0]++
            ApplicationManager.getApplication().invokeLater {
                bridge.sendMessage("step_noti", stepLabel, notiId, mapOf("status" to "started"))
                if (stepMsgId == messageId) {
                    // Step 1: 기존 말풍선에 진행 상태 업데이트 + 파일명/범위 헤더 즉시 전송
                    bridge.sendMessage("task_progress", "⚙️ $stepLabel LLM 응답 대기 중...", stepMsgId)
                    if (!analysisHeaderSent) {
                        analysisHeaderSent = true
                        val scopeText = if (hasSelection) "선택 영역" else "전체 파일"
                        bridge.sendMessageChunk(messageId, "### 🎯 분석 대상: `$fileName` ($scopeText)\n\n")
                    }
                } else if (!isApplyable) {
                    // Step 2+, 텍스트 스트리밍 step: 새 말풍선 생성
                    bridge.sendMessage("task_start", "⚙️ $stepLabel LLM 응답 대기 중...", stepMsgId)
                } else {
                    // Step 2+, 코드 카드 step: 진행 상태만 기존 말풍선에 업데이트
                    bridge.sendMessage("task_progress", "⚙️ $stepLabel LLM 응답 대기 중...", messageId)
                }
            }
        }

        val stepNotiDoneIdx = intArrayOf(0)
        val onStep = { stepLabel: String, result: TaskPipeline.StepResult, _: Boolean, stepMsgId: String ->
            logger.info("ImproveAction: Step 완료 → $stepLabel (stepMsgId=$stepMsgId)")
            val notiId = "${messageId}_noti_${stepNotiDoneIdx[0]}"
            stepNotiDoneIdx[0]++
            ApplicationManager.getApplication().invokeLater {
                bridge.sendMessage(
                    "step_noti", stepLabel, notiId,
                    mapOf("status" to if (result.isSuccess) "completed" else "failed")
                )
                bridge.sendMessage(
                    subType = "task_step",
                    content = result.llmResponse,
                    messageId = stepMsgId,
                    meta = mapOf(
                        "stepLabel" to stepLabel,
                        "applyable" to "false",
                        "isSuccess" to result.isSuccess.toString()
                    )
                )
            }
        }

        val agent = TaskAgent(messageId, onStep, onStepStart)
        agent.execute(
            context,
            onSuccess = { _ ->
                ApplicationManager.getApplication().invokeLater {
                    bridge.sendMessage("task_success", "코드 개선이 완료되었습니다.", messageId)
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

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = project != null && editor != null
    }
}
