package net.ib.ixpert.ops.wuwagent.startup

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import net.ib.ixpert.ops.wuwagent.setting.SettingsState
import net.ib.ixpert.ops.wuwagent.ui.WelcomeDialog

/**
 * 플러그인 최초 설치 후 1회만 웰컴 다이얼로그를 표시하는 StartupActivity.
 *
 * - [SettingsState.State.isFirstRun] = true 인 경우에만 실행
 * - 다이얼로그 표시 전에 isFirstRun = false 로 저장 (중복 표시 방지)
 * - 프로젝트를 열 때마다 트리거되지만, isFirstRun 플래그로 최초 1회만 실질 동작
 */
class FirstRunStartupActivity : ProjectActivity {

    private val logger = Logger.getInstance(FirstRunStartupActivity::class.java)

    override suspend fun execute(project: Project) {
        val settings = SettingsState.getInstance()

        if (!settings.state.isFirstRun) {
            return  // 이미 최초 실행 완료 → 아무것도 하지 않음
        }

        // 다이얼로그 표시 전에 플래그를 false 로 먼저 저장
        // (다이얼로그를 강제 종료해도 재표시되지 않도록)
        settings.state.isFirstRun = false
        logger.info("FirstRunStartupActivity: 최초 실행 감지 — 웰컴 다이얼로그 표시")

        // UI 조작은 반드시 EDT에서 수행
        ApplicationManager.getApplication().invokeLater {
            WelcomeDialog(project).show()
        }
    }
}
