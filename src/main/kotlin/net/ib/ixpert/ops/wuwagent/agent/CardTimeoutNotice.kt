package net.ib.ixpert.ops.wuwagent.agent

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project

/**
 * 사용자 응답을 기다리던 확인 카드가 타임아웃으로 넘어갈 때 대화창에 보이는 안내(오류가 아닌 analyze_notice).
 * 카드를 놓쳤거나 응답하지 않았을 때 분석이 말없이 진행되지 않게 한다.
 */
object CardTimeoutNotice {
    const val STAGE3 = "응답이 없어 전체 후보로 진행했습니다."
    const val SCOPE = "응답이 없어 전체 프로젝트로 진행했습니다."
    const val L1 = "응답이 없어 힌트 없이 진행했습니다."

    fun send(project: Project?, text: String) {
        if (project == null) return
        ApplicationManager.getApplication().invokeLater {
            try {
                net.ib.ixpert.ops.wuwagent.ui.bridge.JcefBridge.getInstance(project)
                    .sendMessage("analyze_notice", text, "analyze_notice_${System.currentTimeMillis()}")
            } catch (_: Exception) {
                // 안내 실패는 분석 진행에 영향을 주지 않는다
            }
        }
    }
}
