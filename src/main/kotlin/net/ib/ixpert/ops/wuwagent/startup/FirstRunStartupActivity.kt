package net.ib.ixpert.ops.wuwagent.startup

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import net.ib.ixpert.ops.wuwagent.setting.SettingsState
import net.ib.ixpert.ops.wuwagent.ui.WelcomeDialog

/**
 * 시작 시 자동 웰컴 팝업 제거.
 * 웰컴 다이얼로그는 채팅 UI 상단 ⓘ 버튼으로만 수동 호출.
 */
class FirstRunStartupActivity : ProjectActivity {

    private val logger = Logger.getInstance(FirstRunStartupActivity::class.java)

    override suspend fun execute(project: Project) {
        logger.info("FirstRunStartupActivity: 자동 팝업 비활성화 — ⓘ 버튼으로 수동 호출")
    }
}
