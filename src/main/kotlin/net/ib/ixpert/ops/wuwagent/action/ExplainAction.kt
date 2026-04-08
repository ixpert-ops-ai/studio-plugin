package net.ib.ixpert.ops.wuwagent.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import net.ib.ixpert.ops.wuwagent.agent.AgentContext
import net.ib.ixpert.ops.wuwagent.agent.ExplainAgent
import net.ib.ixpert.ops.wuwagent.ui.bridge.JcefBridge

/**
 * 에디터에서 "Explain This Code" 이벤트를 받아 Agent에게 위임하고,
 * 결과를 받아 UI 계층(JCEF)으로 연결해주는 라우터 성격을 띱니다.
 */
class ExplainAction : AnAction("WhatUWant: Explain This Code", "Explain the selected code using WhatUWant Agent", com.intellij.icons.AllIcons.Actions.IntentionBulb) {
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        
        // UI 통신망(브릿지) 인스턴스 획득
        val bridge = JcefBridge.getInstance(project)
        
        // 다형성을 지원하는 공통 컨텍스트 생성
        val context = AgentContext(project, editor, "")

        // Agent 실행 (에이전트로부터 받는 청크를 브릿지로 즉시 전달)
        val agent = ExplainAgent()
        val messageId = "msg_${System.currentTimeMillis()}"

        agent.execute(
            context, 
            onSuccess = { resultText ->
                ApplicationManager.getApplication().invokeLater {
                    // 최종 결과 전송 (필요한 경우 meta 정보와 함께)
                    bridge.sendMessage("explain", resultText, mapOf("messageId" to messageId))
                }
            },
            onChunk = { chunk ->
                ApplicationManager.getApplication().invokeLater {
                    // 수신된 청크를 즉시 웹뷰로 전송
                    bridge.sendMessageChunk(messageId, chunk)
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
