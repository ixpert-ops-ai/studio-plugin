package net.ib.ixpert.ops.wuwagent.agent.clarify

/**
 * Stage 0 작업 요약이 프롬프트의 지시문이나 구역 이름을 그대로 옮겨 쓴 문장을 걸러낸다(B-78, B-79에서 표지를 좁힘).
 * 사실 줄을 평서문으로 바꾸고 금지 지시를 시스템 프롬프트로만 두어도 모델이 가끔 "~어긋나지 않도록 ~로만 취급해야" 같은 문장을 쓰므로,
 * 지시문에서만 나오는 표현이 든 문장은 요약에서 뺀다. 걸러낸 뒤 문장이 하나도 남지 않으면 null을 돌려준다(화면은 "요약을 만들지 못했습니다"로 처리).
 *
 * 정상 문장을 지우지 않도록 표지는 지시문 전용 표현으로 한정한다: "사용자 제약과 어긋나는 후보" 같은 일반 표현의 "어긋나"만으로는 지우지 않고,
 * "사실과 어긋나"이거나 "어긋나"와 "취급해야"가 한 문장에 함께 있을 때만 지시문으로 본다.
 */
object SummaryPromptEcho {
    /** 지시문·구역 이름에서만 나오는 표현. */
    private val STRONG = Regex("사실과 어긋나|쓰지 마십시오|쓰지 말[아것]|옮기지 말|\\[코드 확인|코드 확인 목록|소스 확인 결과|확인된 사실")

    private val SENTENCE_END = Regex("(?<=[.다요음함])\\s+|\\n+")

    private fun isEcho(sentence: String): Boolean =
        STRONG.containsMatchIn(sentence) || (sentence.contains("어긋나") && sentence.contains("취급해야"))

    /** 지시문 문장이 하나라도 있었는지(측정용). */
    fun leaks(text: String): Boolean = text.split(SENTENCE_END).any { isEcho(it) }

    fun strip(text: String): String? {
        if (!leaks(text)) return text
        val kept = text.split(SENTENCE_END).filter { it.isNotBlank() && !isEcho(it) }
        return kept.joinToString("\n").trim().ifBlank { null }
    }
}
