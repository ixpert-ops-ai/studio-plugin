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

        // 개선 요청 텍스트 (라우터가 intent 분석 후 ImprovePipeline으로 실행)
        val improveRequest = "/improve 선택된 코드를 분석하고 최선의 방법으로 개선해주세요."
        val context = AgentContext(project, editor, improveRequest)

        ApplicationManager.getApplication().invokeLater {
            bridge.sendMessage("task_start", "✨ 코드 개선을 시작합니다...", messageId)
        }

        val stepNotiIdx = intArrayOf(0)
        val onStepStart = { stepLabel: String ->
            logger.info("ImproveAction: Step 시작 → $stepLabel")
            val notiId = "${messageId}_noti_${stepNotiIdx[0]}"
            stepNotiIdx[0]++
            ApplicationManager.getApplication().invokeLater {
                bridge.sendMessage("step_noti", stepLabel, notiId, mapOf("status" to "started"))
                bridge.sendMessage("task_progress", "⚙️ $stepLabel LLM 응답 대기 중...", messageId)
            }
        }

        val stepNotiDoneIdx = intArrayOf(0)
        val onStep = { stepLabel: String, result: TaskPipeline.StepResult, isApplyable: Boolean, _: String ->
            logger.info("ImproveAction: Step 완료 → $stepLabel (applyable=$isApplyable)")
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
                    val errMessageId = "${messageId}_err"
                    ApplicationManager.getApplication().invokeLater {
                        bridge.sendMessage("task_success", "완료되었습니다.", messageId)
                        bridge.sendMessage("task_start", "", errMessageId)
                        bridge.sendMessage("error", result.llmResponse, errMessageId)
                    }
                }
                else -> {
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
                    bridge.sendMessage("task_success", "코드 개선이 완료되었습니다.", messageId)
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

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = project != null && editor != null
    }
}
