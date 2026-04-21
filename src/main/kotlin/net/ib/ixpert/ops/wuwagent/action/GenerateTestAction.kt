package net.ib.ixpert.ops.wuwagent.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import net.ib.ixpert.ops.wuwagent.agent.AgentContext
import net.ib.ixpert.ops.wuwagent.agent.GenerateTestAgent
import net.ib.ixpert.ops.wuwagent.service.EditorContextService
import net.ib.ixpert.ops.wuwagent.ui.bridge.JcefBridge

/** 에디터 우클릭 → "Generate Test" 액션 */
class GenerateTestAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return

        val bridge = JcefBridge.getInstance(project)
        val context = AgentContext(project, editor, "")
        val messageId = "msg_${System.currentTimeMillis()}"
        val sourceFile = EditorContextService.extractFileName(editor)

        ApplicationManager.getApplication().invokeLater {
            bridge.sendMessage("test_start", "테스트 코드를 생성하고 있습니다...", messageId,
                meta = mapOf("sourceFile" to sourceFile))
        }

        GenerateTestAgent().execute(
            context,
            onSuccess = { resultText ->
                ApplicationManager.getApplication().invokeLater {
                    bridge.sendMessage("test", resultText, messageId,
                        meta = mapOf("sourceFile" to sourceFile))
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
