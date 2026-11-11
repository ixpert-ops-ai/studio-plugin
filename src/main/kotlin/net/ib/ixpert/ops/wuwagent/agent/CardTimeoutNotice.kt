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

    /**
     * 화면으로 보내는 안내의 부가 필드(B-85 2): 탐색 힌트 카드는 몇 번째 턴의 카드였는지(turn)를 싣는다. 웹뷰는 이 값으로 기록 줄에 "Turn N"을 붙이고,
     * 없으면(범위·Stage 3, 번호를 모르는 경우) 아무 필드도 싣지 않는다. 화면의 카드 payload `turn`과 같은 값이다.
     */
    fun metaFor(turn: Int?): Map<String, String> = if (turn != null && turn > 0) mapOf("turn" to turn.toString()) else emptyMap()

    fun send(project: Project?, text: String, turn: Int? = null) {
        // B-82 1-2: 시간 초과 처리를 백엔드 로그에도 한 줄 남긴다(전에는 화면으로 보내는 Bridge 줄만 있어 로그만으로는 5분 초과를 알 수 없었다)
        println("[CardTimeout] 5분 응답 없음 → 자동 진행: $text")
        if (project == null) return
        ApplicationManager.getApplication().invokeLater {
            try {
                net.ib.ixpert.ops.wuwagent.ui.bridge.JcefBridge.getInstance(project)
                    .sendMessage("analyze_notice", text, "analyze_notice_${System.currentTimeMillis()}", metaFor(turn))
            } catch (_: Exception) {
                // 안내 실패는 분석 진행에 영향을 주지 않는다
            }
        }
    }
}
