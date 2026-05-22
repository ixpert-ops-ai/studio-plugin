package net.ib.ixpert.ops.wuwagent.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import net.ib.ixpert.ops.wuwagent.setting.SettingsState
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
 * - JCEF 지원 환경: 내부 JBCefBrowser로 번들 HTML(/welcome.html) 렌더링
 * - JCEF 미지원 환경: 안내 메시지 표시 + [FALLBACK_URL] 외부 브라우저로 fallback
 */
class WelcomeDialog(project: Project) : DialogWrapper(project, true) {

    companion object {
        /** JCEF 미지원 시 외부 브라우저로 열 URL */
        const val FALLBACK_URL = "https://ixpertops.cloud"

        /** 플러그인 jar에 번들된 웰컴 HTML 리소스 경로 */
        private const val WELCOME_HTML_RESOURCE = "/welcome.html"

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
            BrowserUtil.browse(FALLBACK_URL)
            return buildFallbackPanel()
        }

        return try {
            val htmlUrl = javaClass.getResource(WELCOME_HTML_RESOURCE)
                ?: error("번들 리소스를 찾을 수 없습니다: $WELCOME_HTML_RESOURCE")

            // jar: URL 스킴은 JCEF가 지원하지 않으므로 HTML 내용을 직접 읽어 loadHTML() 사용
            val htmlContent = htmlUrl.readText(Charsets.UTF_8)
            val browser = JBCefBrowser()
            browser.loadHTML(htmlContent, "file:///welcome.html")
            logger.info("WelcomeDialog: 번들 HTML 로드 완료 ($htmlUrl)")

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
            "외부 브라우저에서 <b>$FALLBACK_URL</b> 를 확인해 주세요." +
            "</div></html>",
            SwingConstants.CENTER
        )
        panel.add(label, BorderLayout.CENTER)
        return panel
    }

    /**
     * 하단 버튼:
     * - 닫기 (cancelAction): 플래그 유지 → 다음 오픈 시 재표시
     * - 다시 보지 않기 (okAction): isFirstRun = false 저장 → 이후 미표시
     */
    override fun createActions(): Array<Action> = arrayOf(cancelAction, okAction)

    override fun getCancelAction(): Action {
        val action = super.getCancelAction()
        action.putValue(Action.NAME, "닫기")
        return action
    }

    override fun getOKAction(): Action {
        val action = super.getOKAction()
        action.putValue(Action.NAME, "다시 보지 않기")
        return action
    }

    /** "다시 보지 않기" 클릭 시 영구 숨김 플래그 저장 */
    override fun doOKAction() {
        SettingsState.getInstance().state.isFirstRun = false
        logger.info("WelcomeDialog: '다시 보지 않기' 선택 → isFirstRun=false 저장")
        super.doOKAction()
    }
}
