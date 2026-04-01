package net.ib.ixpert.ops.wuwagent.ui.bridge

import com.google.gson.Gson
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.jcef.JBCefBrowser

/**
 * IDE와 Webview(JCEF) 간의 단방향(IDE→JS) 메시지 통신을 전담하는 브릿지 계층.
 */
@Service(Service.Level.PROJECT)
class JcefBridge(private val project: Project) {

    private val logger = Logger.getInstance(JcefBridge::class.java)
    private var browser: JBCefBrowser? = null
    private val gson = Gson()

    // JBCefJSQuery 객체가 GC로 수거되지 않도록 생명주기 고정
    private var messageHandler: Any? = null

    fun registerBrowser(cefBrowser: JBCefBrowser) {
        this.browser = cefBrowser
    }

    fun registerMessageHandler(handler: Any) {
        this.messageHandler = handler
    }

    /**
     * Webview (React) 창에 JSON 형태의 메시지를 전송합니다.
     *
     * @param subType   메시지 타입 ("explain", "chat", "task_step", "apply_result", "error" 등)
     * @param content   본문 텍스트
     * @param meta      부가 메타데이터 (예: stepLabel, applyable, code)
     */
    fun sendMessage(subType: String, content: String, meta: Map<String, String> = emptyMap()) {
        val payload = mutableMapOf(
            "type"    to "ai_message",
            "subType" to subType,
            "content" to content
        )
        payload.putAll(meta)

        val jsonString = gson.toJson(payload)
        val script = "window.postMessage($jsonString, '*');"

        logger.info("Bridge → Webview (subType=$subType, meta=${meta.keys})")
        browser?.cefBrowser?.executeJavaScript(script, browser?.cefBrowser?.url, 0)
    }

    companion object {
        fun getInstance(project: Project): JcefBridge = project.getService(JcefBridge::class.java)
    }
}
