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
class ExplainAction : AnAction() {
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        
        // ★ 우클릭 시점에 선택 텍스트 및 라인 번호를 즉시 캡처 (메뉴 열리면서 사라질 수 있으므로)
        val extracted = net.ib.ixpert.ops.wuwagent.service.EditorContextService.extractCodeWithScope(editor, project)
        val selectedText = if (extracted.isSelection) extracted.code else ""
        
        // UI 통신망(브릿지) 인스턴스 획득
        val bridge = JcefBridge.getInstance(project)
        
        // 다형성을 지원하는 공통 컨텍스트 생성
        // selectedText가 있으면 textBody에 전달하여 Agent가 활용할 수 있도록 함
        val context = AgentContext(project, editor, selectedText, extracted.startLine, extracted.endLine)

        // Agent 실행 (에이전트로부터 받는 청크를 브릿지로 즉시 전달)
        val agent = ExplainAgent()
        val messageId = "msg_${System.currentTimeMillis()}"

        // 🛎 즉시 자리 만들기 (로딩 표시 유도)
        ApplicationManager.getApplication().invokeLater {
            bridge.sendMessage("explain_start", "코드 구조를 분석하고 있습니다...", messageId)
        }

        agent.execute(
            context, 
            onSuccess = { resultText ->
                ApplicationManager.getApplication().invokeLater {
                    // 최종 결과 전송 (messageId 명시)
                    bridge.sendMessage("explain", resultText, messageId)
                }
            },
            onChunk = { chunk ->
                ApplicationManager.getApplication().invokeLater {
                    // 수신된 청크를 즉시 웹뷰로 전송
                    bridge.sendMessageChunk(messageId, chunk)
                }
            },
            onError = { errorMsg ->
                ApplicationManager.getApplication().invokeLater {
                    // 에러 발생 시 명시적 신호 전송 (messageId 명시)
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
