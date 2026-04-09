package net.ib.ixpert.ops.wuwagent.ui.bridge

import com.google.gson.Gson
import com.intellij.openapi.application.ApplicationManager
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
     * @param messageId 메시지 고유 ID (단일 말풍선 추적용)
     * @param meta      부가 메타데이터 (예: stepLabel, applyable, code)
     */
    fun sendMessage(subType: String, content: String, messageId: String? = null, meta: Map<String, String> = emptyMap()) {
        val payload = mutableMapOf(
            "type"    to "ai_message",
            "subType" to subType,
            "content" to content
        )
        if (messageId != null) {
            payload["messageId"] = messageId
        }
        payload.putAll(meta)

        val jsonString = gson.toJson(payload)
        val script = "window.postMessage($jsonString, '*');"

        logger.info("Bridge → Webview (subType=$subType, messageId=$messageId, meta=${meta.keys})")
        browser?.cefBrowser?.executeJavaScript(script, browser?.cefBrowser?.url, 0)
    }

    /**
     * 스트리밍 중인 메시지의 일부분(Chunk)을 전송합니다.
     */
    fun sendMessageChunk(messageId: String, content: String, isFirst: Boolean = false) {
        val payload = mapOf(
            "type" to "ai_message",
            "subType" to "task_chunk",
            "messageId" to messageId,
            "content" to content,
            "isFirst" to isFirst
        )
        val jsonString = gson.toJson(payload)
        val script = "window.postMessage($jsonString, '*');"
        // ✅ invokeLater로 EDT 큐에 삽입 → sendMessage와 동일한 큐 → 순서 보장
        ApplicationManager.getApplication().invokeLater {
            browser?.cefBrowser?.executeJavaScript(script, browser?.cefBrowser?.url, 0)
        }
    }

    companion object {
        fun getInstance(project: Project): JcefBridge = project.getService(JcefBridge::class.java)
    }
}
