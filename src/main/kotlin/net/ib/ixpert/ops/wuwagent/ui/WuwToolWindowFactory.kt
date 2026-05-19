package net.ib.ixpert.ops.wuwagent.ui

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import net.ib.ixpert.ops.wuwagent.ui.bridge.JcefBridge
import net.ib.ixpert.ops.wuwagent.ui.bridge.JcefMessageHandler
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import java.awt.Font

class WuwToolWindowFactory : ToolWindowFactory {

    private val logger = Logger.getInstance(WuwToolWindowFactory::class.java)

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = JPanel(BorderLayout())

        if (!JBCefApp.isSupported()) {
            logger.warn("WuwToolWindow: JBCefApp.isSupported() = false. JCEF를 사용할 수 없습니다.")
            val msg = buildJcefUnsupportedPanel()
            panel.add(msg, BorderLayout.CENTER)
            addContent(toolWindow, panel)
            return
        }

        try {
            val url = WuwToolWindowFactory::class.java.getResource("/webview/index.html")

            if (url == null) {
                panel.add(
                    JLabel("Error: webview/index.html not found in resources.", SwingConstants.CENTER),
                    BorderLayout.CENTER
                )
                addContent(toolWindow, panel)
                return
            }

            val browser = JBCefBrowser()

            // 브릿지 등록
            val bridge = JcefBridge.getInstance(project)
            bridge.registerBrowser(browser)

            // JBCefJSQuery 핸들러 생성 (이 객체를 JcefBridge에 생명주기 고정)
            val messageHandler = JcefMessageHandler(project, browser)
            bridge.registerMessageHandler(messageHandler)

            // ✅ 핵심 수정: 페이지 로드 완료 후 executeJavaScript로 브릿지 주입
            // (HTMLに직접 <script> 삽입 시 CEF 소켓 바인딩 타이밍 문제 발생)
            browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(cefBrowser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                    if (frame?.isMain == true) {
                        val injectScript = messageHandler.getInjectScript()
                        logger.info("WuwToolWindow: 페이지 로드 완료. JS 브릿지 주입 시작...")
                        println("[WuwAgent] onLoadEnd inject script: $injectScript")
                        cefBrowser?.executeJavaScript(injectScript, cefBrowser.url, 0)
                        logger.info("WuwToolWindow: JS 브릿지 주입 완료.")

                        // 초기 모델 정보 전송
                        val settings = net.ib.ixpert.ops.wuwagent.setting.SettingsState.getInstance().state
                        bridge.sendMessage("selected_model", settings.model)
                        logger.info("WuwToolWindow: 초기 모델 정보 전송 완료 (${settings.model})")
                    }
                }
            }, browser.cefBrowser)

            // HTML 로드 (스크립트 주입 없이 순수 HTML만)
            val htmlContent = url.readText(Charsets.UTF_8)
            browser.loadHTML(htmlContent, "http://wuwagent/index.html")

            panel.add(browser.component, BorderLayout.CENTER)

        } catch (e: Exception) {
            logger.error("WuwToolWindow: UI 로딩 실패", e)
            panel.add(
                JLabel("Failed to load iXpert AI Assistant UI: ${e.message}", SwingConstants.CENTER),
                BorderLayout.CENTER
            )
        }

        addContent(toolWindow, panel)
    }

    private fun addContent(toolWindow: ToolWindow, panel: JPanel) {
        val contentManager = toolWindow.contentManager
        contentManager.addContent(contentManager.factory.createContent(panel, "", false))
    }

    private fun buildJcefUnsupportedPanel(): JPanel {
        val container = JPanel()
        container.layout = java.awt.GridBagLayout()

        val lines = listOf(
            "⚠️  JCEF(Chromium)를 사용할 수 없습니다." to Font.BOLD,
            "" to Font.PLAIN,
            "iXpert AI Assistant는 JCEF 기반 웹뷰가 필요합니다." to Font.PLAIN,
            "아래 방법으로 활성화 후 IDE를 재시작해 주세요." to Font.PLAIN,
            "" to Font.PLAIN,
            "1. Help → Find Action 클릭" to Font.PLAIN,
            "2. \"Choose Boot Java Runtime for the IDE\" 검색" to Font.PLAIN,
            "3. 목록에서 JCEF가 포함된 버전 선택" to Font.PLAIN,
            "4. OK 클릭 후 IDE 재시작" to Font.PLAIN,
            "" to Font.PLAIN,
            "※ 이 설정은 최초 1회만 필요합니다." to Font.PLAIN,
            "문제가 지속되면 IDE를 최신 버전으로 업데이트해 주세요." to Font.PLAIN,
        )

        val gbc = java.awt.GridBagConstraints().apply {
            gridx = 0; gridy = 0
            anchor = java.awt.GridBagConstraints.WEST
            insets = java.awt.Insets(2, 16, 2, 16)
        }

        for ((text, style) in lines) {
            val label = JLabel(text)
            label.font = label.font.deriveFont(style, 12f)
            container.add(label, gbc)
            gbc.gridy++
        }

        return container
    }
}
