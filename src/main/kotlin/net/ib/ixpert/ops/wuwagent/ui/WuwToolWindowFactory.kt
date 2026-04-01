package net.ib.ixpert.ops.wuwagent.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import net.ib.ixpert.ops.wuwagent.ui.bridge.JcefBridge
import java.awt.BorderLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

class WuwToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = JPanel(BorderLayout())

        if (!JBCefApp.isSupported()) {
            panel.add(JLabel("JCEF (Chromium) is not supported in this IDE version.", SwingConstants.CENTER), BorderLayout.CENTER)
            val contentManager = toolWindow.contentManager
            contentManager.addContent(contentManager.factory.createContent(panel, "", false))
            return
        }

        try {
            val url = WuwToolWindowFactory::class.java.getResource("/webview/index.html")
            
            if (url != null) {
                val browser = JBCefBrowser()
                
                // 생성된 JBCefBrowser 인스턴스를 JcefBridge에 등록 (UI 통신망 세팅)
                val bridge = JcefBridge.getInstance(project)
                bridge.registerBrowser(browser)

                // JSQuery 바인딩 핸들러 생성 및 인젝션 런타임 셋업
                val messageHandler = net.ib.ixpert.ops.wuwagent.ui.bridge.JcefMessageHandler(project, browser)
                bridge.registerMessageHandler(messageHandler)
                val injectScript = messageHandler.getInjectScript()

                val originalHtml = url.readText(Charsets.UTF_8)
                val htmlContent = originalHtml.replace("</head>", "<script>\n$injectScript\n</script>\n</head>")
                browser.loadHTML(htmlContent, "http://wuwagent/index.html")
                
                panel.add(browser.component, BorderLayout.CENTER)
            } else {
                panel.add(JLabel("Error: webview/index.html not found in resources.", SwingConstants.CENTER), BorderLayout.CENTER)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            panel.add(JLabel("Failed to load WuwAgent UI: \${e.message}", SwingConstants.CENTER), BorderLayout.CENTER)
        }

        val contentManager = toolWindow.contentManager
        contentManager.addContent(contentManager.factory.createContent(panel, "", false))
    }
}
