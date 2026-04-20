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
class ReviewAction : AnAction() {
    private val logger = Logger.getInstance(ReviewAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return

        val bridge = JcefBridge.getInstance(project)
        val messageId = "task_${System.currentTimeMillis()}"
        val fileName = editor.virtualFile?.name ?: ""
        val hasSelection = editor.selectionModel.hasSelection()

        // 리뷰 요청 텍스트 (라우터가 intent 분석 후 ReviewPipeline으로 실행)
        val reviewRequest = "/review 선택된 코드를 검토하고 개선 사항을 제안해주세요."
        val context = AgentContext(project, editor, reviewRequest)

        ApplicationManager.getApplication().invokeLater {
            bridge.sendMessage("task_start", "🔍 코드를 리뷰하고 있습니다...", messageId)
        }

        val stepNotiIdx = intArrayOf(0)
        var reviewHeaderSent = false
        val onStepStart = { stepLabel: String ->
            logger.info("ReviewAction: Step 시작 → $stepLabel")
            val notiId = "${messageId}_noti_${stepNotiIdx[0]}"
            stepNotiIdx[0]++
            ApplicationManager.getApplication().invokeLater {
                bridge.sendMessage("step_noti", stepLabel, notiId, mapOf("status" to "started"))
                bridge.sendMessage("task_progress", "⚙️ $stepLabel LLM 응답 대기 중...", messageId)
                if (!reviewHeaderSent) {
                    reviewHeaderSent = true
                    val scopeText = if (hasSelection) "선택 영역" else "전체 파일"
                    bridge.sendMessageChunk(messageId, "### 🔍 리뷰 대상: `$fileName` ($scopeText)\n\n")
                }
            }
        }

        val stepNotiDoneIdx = intArrayOf(0)
        val onStep = { stepLabel: String, result: TaskPipeline.StepResult, isApplyable: Boolean, _: String ->
            logger.info("ReviewAction: Step 완료 → $stepLabel (applyable=$isApplyable)")
            val notiId = "${messageId}_noti_${stepNotiDoneIdx[0]}"
            stepNotiDoneIdx[0]++
            ApplicationManager.getApplication().invokeLater {
                bridge.sendMessage(
                    "step_noti", stepLabel, notiId,
                    mapOf("status" to if (result.isSuccess) "completed" else "failed")
                )
            }

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
                val fileLabel = when {
                    result.applyScope == "선택 영역" -> "`$fileName` (선택 영역)"
                    result.applyScope == "전체 파일" -> "`$fileName` (전체 파일)"
                    result.applyScope.isNotBlank()   -> "`${result.applyScope}`"
                    fileName.isNotBlank()            -> "`$fileName`"
                    else -> ""
                }
                val header = if (fileLabel.isNotBlank()) "### 🔍 리뷰 대상: $fileLabel\n\n" else ""
                val displayContent = header + result.llmResponse
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
        // 숨김 처리 (기능 유지, 컨텍스트 메뉴에서만 미노출)
        e.presentation.isVisible = false
    }
}
