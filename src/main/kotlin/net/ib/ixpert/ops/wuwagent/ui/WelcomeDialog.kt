package net.ib.ixpert.ops.wuwagent.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.SwingConstants
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JPanel

/**
 * 플러그인 최초 설치 후 1회 표시되는 웰컴 다이얼로그.
 *
 * - JCEF 지원 환경: 내부 JBCefBrowser로 [WELCOME_URL] 렌더링
 * - JCEF 미지원 환경: 안내 메시지 표시 + 외부 브라우저로 fallback
 */
class WelcomeDialog(project: Project) : DialogWrapper(project, true) {

    companion object {
        /** 웰컴 페이지 URL — 변경이 필요하면 이 상수만 수정 */
        const val WELCOME_URL = "https://ixpertops.cloud"

        private const val DIALOG_WIDTH  = 900
        private const val DIALOG_HEIGHT = 600
    }

    private val logger = Logger.getInstance(WelcomeDialog::class.java)

    init {
        title = "iXpert AI Assistant에 오신 것을 환영합니다"
        isResizable = true
        init()
    }

    override fun createCenterPanel(): JComponent {
        if (!JBCefApp.isSupported()) {
            logger.warn("WelcomeDialog: JCEF 미지원 환경 — 외부 브라우저로 fallback")
            BrowserUtil.browse(WELCOME_URL)
            return buildFallbackPanel()
        }

        return try {
            val browser = JBCefBrowser()
            browser.loadURL(WELCOME_URL)
            logger.info("WelcomeDialog: JCEF 브라우저 로드 완료 ($WELCOME_URL)")

            val panel = JPanel(BorderLayout())
            panel.preferredSize = Dimension(DIALOG_WIDTH, DIALOG_HEIGHT)
            panel.add(browser.component, BorderLayout.CENTER)
            panel
        } catch (e: Exception) {
            logger.error("WelcomeDialog: 브라우저 초기화 실패", e)
            buildFallbackPanel()
        }
    }

    /** JCEF 미지원 / 초기화 실패 시 안내 패널 */
    private fun buildFallbackPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(DIALOG_WIDTH, 120)

        val label = JLabel(
            "<html><div style='text-align:center;'>" +
            "브라우저를 지원하지 않는 환경입니다.<br><br>" +
            "외부 브라우저에서 <b>$WELCOME_URL</b> 를 확인해 주세요." +
            "</div></html>",
            SwingConstants.CENTER
        )
        panel.add(label, BorderLayout.CENTER)
        return panel
    }

    /** 하단 버튼: "닫기" 하나만 */
    override fun createActions(): Array<Action> = arrayOf(okAction)

    override fun getOKAction(): Action {
        val action = super.getOKAction()
        action.putValue(Action.NAME, "닫기")
        return action
    }
}
