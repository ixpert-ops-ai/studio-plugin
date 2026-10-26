package net.ib.ixpert.ops.wuwagent.agent.clarify

/**
 * `/clarify-confirm`에서 대화를 마치고 분석으로 넘어가는 순서. EDT에서 부르며, 정제문 생성(LLM 호출)부터는 백그라운드에서 한다.
 * 라우터의 동작을 IDE 없이 테스트할 수 있게 단계를 인자로 받는다(운영 코드는 라우터가 실제 단계를 넘긴다).
 *
 * 순서: (EDT) 요약 폐기 → 확인 진행 표지 세움 → 곧바로 반환
 *       (백그라운드) 정제문 생성 → 세션 닫기 → 정제문 확인 카드·저장·분석 → 실패하면 표지를 풀고 오류 알림
 * 확인 진행 표지는 백그라운드로 넘기기 **전에** 세운다. 그래야 정제문을 만드는 동안 들어온 두 번째 확인이 무시된다.
 */
object ClarifyConfirmHandoff {
    class Steps<I>(
        val invalidateSummary: () -> Unit,
        val beginHandoff: () -> Unit,
        val buildIntent: () -> I,
        val removeSession: () -> Unit,
        val continueAfterClarify: (I) -> Unit,
        val onFailure: (Exception) -> Unit,
        val endHandoff: () -> Unit
    )

    fun <I> start(steps: Steps<I>, runInBackground: (() -> Unit) -> Unit) {
        steps.invalidateSummary()
        steps.beginHandoff()
        try {
            runInBackground {
                try {
                    val intent = steps.buildIntent()
                    steps.removeSession()
                    steps.continueAfterClarify(intent)
                } catch (e: Exception) {
                    steps.endHandoff()
                    steps.onFailure(e)
                }
            }
        } catch (e: Exception) {
            // 백그라운드 실행을 맡기지 못했다: 표지를 풀고 알린다
            steps.endHandoff()
            steps.onFailure(e)
        }
    }
}
