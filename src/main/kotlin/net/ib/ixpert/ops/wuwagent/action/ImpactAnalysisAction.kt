package net.ib.ixpert.ops.wuwagent.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import net.ib.ixpert.ops.wuwagent.agent.AgentContext
import net.ib.ixpert.ops.wuwagent.agent.ImpactAgent
import net.ib.ixpert.ops.wuwagent.ui.bridge.JcefBridge

/** 에디터 우클릭 → "Impact Analysis" 액션 */
class ImpactAnalysisAction : AnAction(
    "Impact Analysis",
    "선택한 코드의 변경 영향 범위를 AI가 분석합니다.",
    com.intellij.icons.AllIcons.Actions.Find
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return

        val bridge = JcefBridge.getInstance(project)
        val context = AgentContext(project, editor, "")
        val messageId = "msg_${System.currentTimeMillis()}"

        ApplicationManager.getApplication().invokeLater {
            bridge.sendMessage("explain_start", "🔍 영향 범위를 분석하고 있습니다...", messageId)
        }

        ImpactAgent().execute(
            context,
            onSuccess = { resultText ->
                ApplicationManager.getApplication().invokeLater {
                    bridge.sendMessage("explain", resultText, messageId)
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
