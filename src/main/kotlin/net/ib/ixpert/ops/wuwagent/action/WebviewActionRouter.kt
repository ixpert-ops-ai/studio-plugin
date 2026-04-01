package net.ib.ixpert.ops.wuwagent.action

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import net.ib.ixpert.ops.wuwagent.agent.ExplainAgent
import net.ib.ixpert.ops.wuwagent.ui.bridge.JcefBridge

/**
 * Webview (프론트엔드)에서 넘어오는 명령을 수신하여 
 * IDE의 Context나 다른 모듈로 전달하는 라우터입니다.
 */
class WebviewActionRouter(private val project: Project) {
    private val logger = Logger.getInstance(WebviewActionRouter::class.java)

    fun handleCommand(command: String, payload: Map<String, String>) {
        when (command) {
            "/explain" -> {
                logger.info("Router: /explain 명령어 라우팅 개시")
                
                ApplicationManager.getApplication().invokeLater {
                    // 사용자 에디터 획득 (활성 에디터)
                    val editor = FileEditorManager.getInstance(project).selectedTextEditor
                    val bridge = JcefBridge.getInstance(project)

                    // 활성 에디터 Null Fallback 예외 처리
                    if (editor == null) {
                        logger.warn("Router: 활성화된 에디터가 없어서 코드를 추출할 수 없습니다.")
                        bridge.sendMessage("explain", "현재 포커스된 파일/에디터 창이 없습니다. 코드가 있는 에디터를 열고 다시 실행해주세요.")
                        return@invokeLater
                    }

                    // Agent 오케스트레이션 호출 
                    logger.info("Router: ExplainAgent 호출 (execute 시작)")
                    val agent = ExplainAgent(project, editor)
                    agent.execute { resultText ->
                        logger.info("Router: ExplainAgent 작업 수신. 브릿지를 통해 결과 반환.")
                        bridge.sendMessage("explain", resultText)
                    }
                }
            }
            else -> {
                logger.warn("Router: 원격 정의되지 않은 JS 명령 수신 - $command")
            }
        }
    }
}
