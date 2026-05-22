package net.ib.ixpert.ops.wuwagent.startup

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import net.ib.ixpert.ops.wuwagent.setting.SettingsState
import net.ib.ixpert.ops.wuwagent.ui.WelcomeDialog

/**
 * isFirstRun = true 인 동안 매 프로젝트 오픈 시 웰컴 다이얼로그를 표시.
 *
 * - "닫기" 클릭 → 플래그 유지, 다음 오픈 시 재표시
 * - "다시 보지 않기" 클릭 → isFirstRun = false 저장, 이후 표시 안 함
 */
class FirstRunStartupActivity : ProjectActivity {

    private val logger = Logger.getInstance(FirstRunStartupActivity::class.java)

    override suspend fun execute(project: Project) {
        val settings = SettingsState.getInstance()

        if (!settings.state.isFirstRun) {
            return  // "다시 보지 않기" 를 눌렀던 경우 → 표시 안 함
        }

        logger.info("FirstRunStartupActivity: isFirstRun=true — 웰컴 다이얼로그 표시")

        // UI 조작은 반드시 EDT에서 수행
        ApplicationManager.getApplication().invokeLater {
            WelcomeDialog(project).show()
        }
    }
}
