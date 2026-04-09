package net.ib.ixpert.ops.wuwagent.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import net.ib.ixpert.ops.wuwagent.agent.AgentContext
import net.ib.ixpert.ops.wuwagent.agent.QueryValidationAgent
import net.ib.ixpert.ops.wuwagent.ui.bridge.JcefBridge

/** 에디터 우클릭 → "Query Validation" 액션 */
class QueryValidationAction : AnAction(
    "Query Validation",
    "선택한 쿼리를 AI가 유효성 검증합니다.",
    com.intellij.icons.AllIcons.Actions.CheckMulticaret
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return

        val bridge = JcefBridge.getInstance(project)
        val context = AgentContext(project, editor, "")
        val messageId = "msg_${System.currentTimeMillis()}"

        ApplicationManager.getApplication().invokeLater {
            bridge.sendMessage("explain_start", "🧪 쿼리를 검증하고 있습니다...", messageId)
        }

        QueryValidationAgent().execute(
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
