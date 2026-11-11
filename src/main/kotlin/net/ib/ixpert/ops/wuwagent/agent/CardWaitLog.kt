package net.ib.ixpert.ops.wuwagent.agent

/**
 * 사용자 응답을 기다린 확인 카드의 결과 로그(B-85 7). 모든 카드가 끝날 때 같은 형식으로 한 줄씩 남겨, 로그만으로 "어느 카드가 몇 번째 턴에서 무엇으로 끝났고 얼마나 기다렸는지"를 판정한다.
 * 형식: `[CardWait] card=<refine|package|hint|stage3> turn=<N|-> result=<submit|edit|skip|cancel|timeout|stale|superseded|none> waitedMs=<실측|->`
 * - card: refine = 정제문 확인, package = 읽을 범위(패키지) 선택, hint = 탐색 힌트, stage3 = 최종 검증 대상 선택.
 * - turn: 탐색 힌트 카드의 턴 번호(화면 기록 줄의 "Turn N"과 같은 값). 턴이 없는 카드는 "-".
 * - result: submit = 제출, edit = 수정해서 제출(정제문), skip = 건너뛰기, cancel = 취소(정제문), timeout = 5분 자동 진행, stale = 기다리는 요청과 달라 무시한 오래된 응답(정제문),
 *   superseded = 새 /clarify가 대신함(정제문), none = 결정 없이 끝남(전송 실패 등).
 * - waitedMs: 카드를 보낸 뒤 결과가 정해질 때까지의 실측 시간. 모르면 "-".
 * 기존 [CardTimeout]·[RefineGate] 줄은 그대로 남고 이 줄이 더해진다. 형식은 시험이 고정한다(순수 함수 [line]).
 */
object CardWaitLog {
    enum class Card(val label: String) { REFINE("refine"), PACKAGE("package"), HINT("hint"), STAGE3("stage3") }

    enum class Result(val label: String) {
        SUBMIT("submit"), EDIT("edit"), SKIP("skip"), CANCEL("cancel"), TIMEOUT("timeout"), STALE("stale"), SUPERSEDED("superseded"), NONE("none")
    }

    fun line(card: Card, turn: Int?, result: Result, waitedMs: Long?): String =
        "[CardWait] card=${card.label} turn=${turn?.takeIf { it > 0 }?.toString() ?: "-"} result=${result.label} waitedMs=${waitedMs?.takeIf { it >= 0 }?.toString() ?: "-"}"

    fun log(card: Card, turn: Int?, result: Result, waitedMs: Long?) = println(line(card, turn, result, waitedMs))

    /** 정제문 확인의 결정을 로그 결과값으로: 이대로 = submit, 수정 = edit, 취소 = cancel, 5분 = timeout, 새 /clarify가 대신함 = superseded, 결정 없음 = none. */
    fun refineResult(decision: net.ib.ixpert.ops.wuwagent.agent.clarify.RefinedConfirmationDecision?): Result = when (decision) {
        net.ib.ixpert.ops.wuwagent.agent.clarify.RefinedConfirmationDecision.Proceed -> Result.SUBMIT
        is net.ib.ixpert.ops.wuwagent.agent.clarify.RefinedConfirmationDecision.ProceedEdited -> Result.EDIT
        net.ib.ixpert.ops.wuwagent.agent.clarify.RefinedConfirmationDecision.Cancel -> Result.CANCEL
        net.ib.ixpert.ops.wuwagent.agent.clarify.RefinedConfirmationDecision.TimedOut -> Result.TIMEOUT
        net.ib.ixpert.ops.wuwagent.agent.clarify.RefinedConfirmationDecision.Superseded -> Result.SUPERSEDED
        null -> Result.NONE
    }
}
