package net.ib.ixpert.ops.wuwagent.ui.bridge

import com.google.gson.Gson
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.ui.jcef.JBCefBrowser

/**
 * IDE와 Webview(JCEF) 간의 양방향/단방향 메시지 통신을 전담하는 브릿지 계층.
 */
@Service(Service.Level.PROJECT)
class JcefBridge(private val project: Project) {
    private var browser: JBCefBrowser? = null
    // JBCefJSQuery 객체가 GC 프로세스에 의해 수거되어 브릿지가 단절되는 것을 방지합니다.
    private var messageHandler: Any? = null
    private val gson = Gson()

    fun registerBrowser(cefBrowser: JBCefBrowser) {
        this.browser = cefBrowser
    }

    fun registerMessageHandler(handler: Any) {
        this.messageHandler = handler
    }

    /**
     * Webview (React) 창에 JSON 형태의 메시지를 전송합니다.
     * 규격: { "type": "ai_message", "subType": "...", "content": "..." }
     */
    fun sendMessage(subType: String, content: String) {
        val payload = mapOf(
            "type" to "ai_message",
            "subType" to subType,
            "content" to content
        )
        val jsonString = gson.toJson(payload)
        
        // JS 내부 window.addEventListener('message', ...) 에서 수신 가능한 구조
        val script = "window.postMessage($jsonString, '*');"
        
        val logger = com.intellij.openapi.diagnostic.Logger.getInstance(JcefBridge::class.java)
        logger.info("Bridge: Webview로 응답 전송 중... (subType: $subType)")
        
        browser?.cefBrowser?.executeJavaScript(script, browser?.cefBrowser?.url, 0)
    }

    companion object {
        fun getInstance(project: Project): JcefBridge = project.getService(JcefBridge::class.java)
    }
}
