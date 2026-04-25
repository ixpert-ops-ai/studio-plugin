package net.ib.ixpert.ops.wuwagent.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import net.ib.ixpert.ops.wuwagent.agent.AgentContext
import net.ib.ixpert.ops.wuwagent.agent.DocGenerateAgent
import net.ib.ixpert.ops.wuwagent.ui.bridge.JcefBridge

/**
 * 에디터에서 "Generate Analysis Doc" 이벤트를 받아
 * DocGenerateAgent에게 위임하고, 결과를 UI(JCEF)로 전달하는 Action.
 *
 * 기존 ExplainAction과 동일한 패턴을 따릅니다.
 */
class DocGenerateAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return

        val bridge = JcefBridge.getInstance(project)
        val context = AgentContext(project, editor, "")
        val messageId = "msg_${System.currentTimeMillis()}"

        // 즉시 로딩 표시
        ApplicationManager.getApplication().invokeLater {
            bridge.sendMessage("explain_start", "📄 분석 문서를 생성하고 있습니다...", messageId)
        }

        val agent = DocGenerateAgent()
        agent.execute(
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
                    if (errorMsg != "__cancelled__") {
                        bridge.sendMessage("error", errorMsg, messageId)
                    }
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
