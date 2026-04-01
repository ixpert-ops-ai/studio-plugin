package net.ib.ixpert.ops.wuwagent.action

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import net.ib.ixpert.ops.wuwagent.agent.AgentContext
import net.ib.ixpert.ops.wuwagent.agent.ChatAgent
import net.ib.ixpert.ops.wuwagent.agent.ExplainAgent
import net.ib.ixpert.ops.wuwagent.ui.bridge.JcefBridge

/**
 * Webview (프론트엔드)에서 넘어오는 JSQuery 명령을 수신하여 
 * 알맞은 Agent(다형성 적용)로 라우팅합니다.
 */
class WebviewActionRouter(private val project: Project) {
    private val logger = Logger.getInstance(WebviewActionRouter::class.java)

    fun handleCommand(command: String, payload: Map<String, String>) {
        ApplicationManager.getApplication().invokeLater {
            val bridge = JcefBridge.getInstance(project)
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
            val textBody = payload["text"] ?: ""

            when (command) {
                "/explain" -> {
                    logger.info("Router: /explain 명령어 분기")
                    if (editor == null) {
                        bridge.sendMessage("explain", "활성화된 에디터가 없어 Explain을 실행할 수 없습니다.")
                        return@invokeLater
                    }
                    val context = AgentContext(project, editor, textBody)
                    ExplainAgent().execute(context) { res ->
                        ApplicationManager.getApplication().invokeLater {
                            bridge.sendMessage("explain", res)
                        }
                    }
                }
                "/chat" -> {
                    logger.info("Router: /chat (일반 채팅) 분기")
                    val context = AgentContext(project, editor, textBody)
                    ChatAgent().execute(context) { res ->
                        ApplicationManager.getApplication().invokeLater {
                            bridge.sendMessage("chat", res)
                        }
                    }
                }
                else -> {
                    logger.warn("Router: 원격 정의되지 않은 JS 명령 수신 - $command")
                    bridge.sendMessage("error", "알 수 없는 명령어: $command")
                }
            }
        }
    }
}
