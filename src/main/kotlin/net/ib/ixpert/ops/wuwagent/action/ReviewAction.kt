package net.ib.ixpert.ops.wuwagent.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import net.ib.ixpert.ops.wuwagent.agent.AgentContext
import net.ib.ixpert.ops.wuwagent.agent.TaskAgent
import net.ib.ixpert.ops.wuwagent.agent.TaskCancellationToken
import net.ib.ixpert.ops.wuwagent.agent.IntentAnalyzer
import net.ib.ixpert.ops.wuwagent.agent.TaskPipeline
import net.ib.ixpert.ops.wuwagent.client.OllamaClient
import net.ib.ixpert.ops.wuwagent.ui.bridge.JcefBridge
import com.intellij.openapi.diagnostic.Logger

/**
 * 에디터 우클릭 → "Review Code" 액션.
 * WebviewActionRouter의 /task 분기를 재활용하여 리뷰 파이프라인을 실행합니다.
 * 사용자 입력을 "/task review" 형태로 포장하여 라우터에 위임합니다.
 */
class ReviewAction : AnAction(
    "Review Code",
    "선택한 코드를 AI가 리뷰합니다.",
    com.intellij.icons.AllIcons.Actions.Preview
) {
    private val logger = Logger.getInstance(ReviewAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return

        val bridge = JcefBridge.getInstance(project)
        val messageId = "task_${System.currentTimeMillis()}"

        // 리뷰 요청 텍스트 (라우터가 intent 분석 후 ReviewPipeline으로 실행)
        val reviewRequest = "/review 선택된 코드를 검토하고 개선 사항을 제안해주세요."
        val context = AgentContext(project, editor, reviewRequest)

        ApplicationManager.getApplication().invokeLater {
            bridge.sendMessage("task_start", "🔍 코드를 리뷰하고 있습니다...", messageId)
        }

        val onStepStart = { stepLabel: String ->
            logger.info("ReviewAction: Step 시작 → $stepLabel")
            ApplicationManager.getApplication().invokeLater {
                bridge.sendMessage("task_progress", "⚙️ $stepLabel 분석 중...", messageId)
            }
        }

        val onStep = { stepLabel: String, result: TaskPipeline.StepResult, isApplyable: Boolean, _: String ->
            logger.info("ReviewAction: Step 완료 → $stepLabel (applyable=$isApplyable)")
            if (isApplyable && result.isSuccess) {
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
            } else {
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

        val agent = TaskAgent(messageId, onStep, onStepStart)
        agent.execute(
            context,
            onSuccess = { _ ->
                ApplicationManager.getApplication().invokeLater {
                    bridge.sendMessage("task_success", "리뷰가 완료되었습니다.", messageId)
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
