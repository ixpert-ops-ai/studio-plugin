package net.ib.ixpert.ops.wuwagent.ui.bridge

import com.google.gson.Gson
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import net.ib.ixpert.ops.wuwagent.action.WebviewActionRouter

/**
 * JS -> IDE 메세지 전송을 처리하는 Query 바인딩 매니저.
 */
class JcefMessageHandler(project: Project, browser: JBCefBrowser) {
    private val logger = Logger.getInstance(JcefMessageHandler::class.java)
    private val jsQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase)
    private val gson = Gson()
    private val router = WebviewActionRouter(project)

    init {
        jsQuery.addHandler { requestStr ->
            logger.info("JS → IDE: Received message payload - $requestStr")
            try {
                @Suppress("UNCHECKED_CAST")
                val map = gson.fromJson(requestStr, Map::class.java) as Map<String, String>
                val command = map["command"] ?: ""
                
                // 역방향 라우팅을 통해 WebviewActionRouter로 분기 위임
                router.handleCommand(command, map)
                
                JBCefJSQuery.Response("success")
            } catch (e: Exception) {
                logger.error("Failed to parse incoming JS request", e)
                JBCefJSQuery.Response(null, 500, "Error parsing JSON: \${e.message}")
            }
        }
    }

    /**
     * Webview HTML의 <head> 태그 안에 주입할 런타임 자바스크립트 스니펫을 반환합니다.
     */
    fun getInjectScript(): String {
        // window.sendToIde(data) 호출 시 data 변수를 그대로 cefQuery에 전달
        // jsQuery.inject("data") 는 "data" 변수를 payload로 cefQuery를 부르는 JS 코드를 생성
        val queryCall = jsQuery.inject("data")
        return "window.sendToIde = function(data) { $queryCall };"
    }
}
