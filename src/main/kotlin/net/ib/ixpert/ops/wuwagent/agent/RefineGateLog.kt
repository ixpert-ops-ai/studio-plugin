package net.ib.ixpert.ops.wuwagent.agent

import net.ib.ixpert.ops.wuwagent.agent.clarify.RefinedConfirmationDecision

/**
 * 정제문 확인 카드의 "요청 전송"·"응답 결과" 로그 줄(B-85 후속). 두 줄 모두 끝에 `requestId=<rid:messageId>`를 붙여,
 * 같은 카드의 요청·응답·[CardWaitLog] 줄과 웹뷰의 "웹뷰 응답 수신"·"stale response ignored" 줄을 requestId 하나로 묶는다.
 * 식별자가 없으면(시험용 이전 형식) "-". 형식은 시험이 고정한다(순수 함수).
 */
object RefineGateLog {
    /** 모르는 식별자는 "-". */
    fun idText(requestId: String?): String = requestId?.takeIf { it.isNotBlank() } ?: "-"

    /** `[RefineGate] rid=<hex> 카드 요청 전송 projectId=<id> 정제문 <N>자 (<경과>) requestId=<rid:messageId|->` */
    fun requestSentLine(rid: String, projectId: String, refinedChars: Int, sinceText: String, requestId: String?): String =
        "[RefineGate] rid=$rid 카드 요청 전송 projectId=$projectId 정제문 ${refinedChars}자 ($sinceText) requestId=${idText(requestId)}"

    /** 결정의 로그 문구(기존 "응답 결과" 줄의 값과 같다). */
    fun decisionText(decision: RefinedConfirmationDecision?): String = when (decision) {
        null -> "결정 없음(정제문 그대로 진행)"
        RefinedConfirmationDecision.Proceed -> "이대로 분석"
        is RefinedConfirmationDecision.ProceedEdited -> "수정(" + decision.text.trim().length + "자)"
        RefinedConfirmationDecision.Cancel -> "취소"
        RefinedConfirmationDecision.Superseded -> "새 /clarify가 대신함(분석 안 함)"
        RefinedConfirmationDecision.TimedOut -> "시간 초과(정제문 그대로 진행)"
    }

    /** `[RefineGate] rid=<hex> 응답 결과: <결정> requestId=<rid:messageId|->` */
    fun responseLine(rid: String, decision: RefinedConfirmationDecision?, requestId: String?): String =
        "[RefineGate] rid=$rid 응답 결과: ${decisionText(decision)} requestId=${idText(requestId)}"
}
