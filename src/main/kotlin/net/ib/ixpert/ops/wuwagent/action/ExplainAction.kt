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

        // Agent 실행 (Agent 내부에 UI가 노출되지 않게 Callback을 뚫어줍니다)
        val agent = ExplainAgent()
        agent.execute(context) { resultText ->
            
            // JCEF 브릿지 호출은 AWT/UI 스레드와 동기화
            ApplicationManager.getApplication().invokeLater {
                // 서브 타입 "explain" 적용하여 JSON 렌더링 지시
                bridge.sendMessage("explain", resultText)
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = project != null && editor != null
    }
}
