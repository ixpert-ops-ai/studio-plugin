package net.ib.ixpert.ops.wuwagent.agent

/**
 * 분석 결과 화면에 내부 진단 문구가 그대로 보이지 않게 하는 표시용 변환(저장 값은 바꾸지 않는다).
 * - "Score: N, Via: ..." (근거 없이 점수와 발견 경로만 남은 설명), " [Bypass: ...]" (구제 경로 표시), "Fallback: Keyword matching ..." (대체 경로 사유)
 */
object AnalysisResultWording {
    private val BYPASS = Regex("""\s*\[Bypass:[^\]]*]""")
    const val NO_REASON = "근거를 확인하지 못해 후보로만 남았습니다"

    /** 파일 표의 수정 사유. 내부 점수 문구는 사용자 말로 바꾸고, 구제 경로 표시는 지운다. */
    fun description(text: String): String {
        val t = text.trim()
        if (t.startsWith("Score:") && t.contains("Via:")) return NO_REASON
        return BYPASS.replace(text, "")
    }

    /** 결과 메타데이터의 "파일 필터링 결과" 한 줄(B-80): 단계마다 몇 개였는지 "평가 30개 → 검증 후보 13개 → 최종 4개"로 보인다. 신규 제안은 따로 센다. */
    fun stageCounts(evaluated: Int, verifyCandidates: Int, finalCount: Int, suggestedNew: Int): String =
        "평가 ${evaluated}개 → 검증 후보 ${verifyCandidates}개 → 최종 ${finalCount}개" + (if (suggestedNew > 0) " (신규 제안 ${suggestedNew}개는 별도)" else "")

    /** 탐색 요약 문장. 대체 경로 사유 문구는 보이지 않게 비운다. */
    fun reasoning(text: String): String {
        if (text.trim().startsWith("Fallback:")) return ""
        return BYPASS.replace(text, "")
    }
}

/** 파일 분석이 대체 경로(탐색기·검증기 실패)로 끝났을 때 결과 대신 보이는 안내. 결과 표와 /implement 안내는 내보내지 않는다. */
object IncompleteAnalysisNotice {
    const val TITLE = "### 파일 분석을 끝까지 하지 못했습니다"
    const val BODY = "서버 응답이 중간에 끊겨 대상 파일을 확정하지 못했습니다. 잘못된 결과를 보여 드리지 않기 위해 결과를 내지 않았습니다. 아래 \"다시 시도\"를 누르면 같은 요구사항으로 다시 분석합니다."

    fun text(): String = "$TITLE\n$BODY"
}
