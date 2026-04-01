package net.ib.ixpert.ops.wuwagent.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

class WuwToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = JPanel(BorderLayout())

        if (!JBCefApp.isSupported()) {
            val label = JLabel("JCEF (Chromium) is not supported in this IDE version.", SwingConstants.CENTER)
            panel.add(label, BorderLayout.CENTER)
            val contentManager = toolWindow.contentManager
            val content = contentManager.factory.createContent(panel, "", false)
            contentManager.addContent(content)
            return
        }

        try {
            // 플러그인 빌드 후 리소스(webview/index.html) 읽기
            val url = WuwToolWindowFactory::class.java.getResource("/webview/index.html")
            
            if (url != null) {
                val browser = JBCefBrowser()
                // single-file 플러그인 덕분에 모든 리소스가 인라인된 HTML이므로 
                // loadHTML() 호출 한 번으로 React 앱 전체가 렌더링됩니다.
                val htmlContent = url.readText(Charsets.UTF_8)
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
        val content = contentManager.factory.createContent(panel, "", false)
        contentManager.addContent(content)
    }
}
