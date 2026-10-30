package net.ib.ixpert.ops.wuwagent.agent.clarify

/**
 * Stage 0 질문에 대한 사용자의 답(자유 문장)을 LLM 없이 항목별로 나눈다(B-79): 출처 / 대상 화면 / 마스킹 규칙.
 * Stage 0의 "기록함" 표시와 결과 경고의 "사용자 답변" 인용이 같은 해석을 쓴다(전에는 결과 경고가 답을 앞 60자에서 잘라 인용했다).
 *
 * 문장을 절(마침표·쉼표 단위)로 나누고 절마다 한 항목에 넣는다. 우선순위는 마스킹 규칙 > 대상 화면 > 출처이며, 어디에도 안 맞는 절은 기타로 둔다.
 * 해석은 표시용이다: 제약으로 기록되는 값은 사용자의 원문 그대로다.
 */
object AnswerInterpreter {
    data class Result(val source: List<String>, val screen: List<String>, val mask: List<String>, val other: List<String>) {
        val isEmpty: Boolean get() = source.isEmpty() && screen.isEmpty() && mask.isEmpty()
    }

    private val CLAUSE_SPLIT = Regex("(?<=[.!?])\\s+|,\\s*")
    private val MASK = Regex("마스킹|가리|가려|숨기|숨겨|자리|도메인|\\*")
    private val SCREEN = Regex("화면|페이지|관리자|목록|상세")
    private val SOURCE = Regex("수집|저장|컬럼|조인|테이블|새로|추가|가져|연동|출처|모르|몰라|모릅")

    // 출처 절을 정리한 문장으로 바꾸는 규칙(B-80 4). 규칙에 정확히 맞는 절만 바꾸고, 맞지 않거나 부정·제외 표현이 있어 뜻이 바뀔 수 있으면 원문 절을 그대로 쓴다.
    private val NEGATION = Regex("않|말아|말고|안\\s|못|빼|제외|아니")
    private val NO_DATA = Regex("^(?:.+?(?:은|는)\\s+)?(?:아직\\s+)?(?:수집(?:을\\s?)?(?:하지\\s?않|\\s?안\\s?하|\\s?안\\s?해|하고\\s?있지\\s?않)\\S*|수집\\s?안\\s?\\S+)$")
    private val SAVE_NEW = Regex("^(.+?\\s?테이블)에\\s+(.+?)\\s?(?:컬럼|칼럼)을?\\s+(?:새로\\s+)?(?:추가|신설|생성)(?:해서|하여|하고)\\s+저장(?:하는\\s*방향으로|하도록|하게)?$")
    private val JOIN = Regex("^(.+?)에서\\s*조인(?:해서|하여)\\s*(?:가져|조회|사용)\\S*$")
    private val UNKNOWN = Regex("^(?:잘\\s*)?(?:모르\\S*|몰라\\S*|모릅\\S*)$")
    private val TRAILING = Regex("(?:\\s*(?:이고|해\\s?줘|해\\s?주세요|줘|해요))+[.!?]?$")

    private fun clean(clause: String): String = clause.trim().replace(TRAILING, "").trim().trimEnd('.', ',', '!', '?')

    fun interpret(answer: String): Result {
        val source = mutableListOf<String>()
        val screen = mutableListOf<String>()
        val mask = mutableListOf<String>()
        val other = mutableListOf<String>()
        answer.split(CLAUSE_SPLIT).map { clean(it) }.filter { it.isNotBlank() }.forEach { c ->
            when {
                MASK.containsMatchIn(c) -> mask.add(c)
                SCREEN.containsMatchIn(c) -> screen.add(c)
                SOURCE.containsMatchIn(c) -> source.add(c)
                else -> other.add(c)
            }
        }
        return Result(source, screen, mask, other)
    }

    /** 출처 절 하나를 정리한 문장으로. 알려진 모양에 정확히 맞을 때만 바꾸고 아니면 null(호출한 쪽이 원문 절을 쓴다). */
    internal fun refineSourceClause(clause: String): String? {
        val c = clause.trim()
        UNKNOWN.matchEntire(c)?.let { return "출처 미정(사용자도 모름)" }
        if (NO_DATA.matches(c)) return "기존 데이터 없음"
        if (NEGATION.containsMatchIn(c)) return null
        SAVE_NEW.matchEntire(c)?.let { return "${it.groupValues[1]}에 ${it.groupValues[2]} 컬럼 신설·저장" }
        JOIN.matchEntire(c)?.let { return "${it.groupValues[1]} 조인으로 조회" }
        return null
    }

    /** Stage 0 "기록함"의 출처 줄: 절을 모두 정리한 문장으로 바꿀 수 있으면 " → "로 잇고, 하나라도 못 바꾸면 원문 절을 " / "로 잇는다(뜻이 바뀔 수 있는 정리는 하지 않는다). */
    internal fun sourceLine(source: List<String>): String {
        val refined = source.map { refineSourceClause(it) }
        return if (refined.all { it != null }) refined.joinToString(" → ") else source.joinToString(" / ")
    }

    /** 결과 경고의 "사용자 답변" 자리에 인용할 출처 부분. 출처로 나눠진 절이 없으면 답 앞부분을 그대로 쓴다. */
    fun sourceQuote(answer: String, limit: Int = 80): String {
        val r = interpret(answer)
        return (if (r.source.isNotEmpty()) r.source.joinToString(" / ") else answer.trim()).take(limit)
    }

    /** Stage 0 대화에 보일 "기록함" 메시지: 발화 통째 인용 대신 해석한 항목을 줄마다 보인다. 하나도 못 나눴으면 원문 앞부분을 인용한다. */
    fun echo(label: String, answer: String): String {
        val r = interpret(answer)
        // B-80: 머리줄은 출처만이 아니라 실제로 기록된 항목 전체를 말한다(출처, 대상 화면, 마스킹 규칙, 그 밖)
        if (r.isEmpty) return "기록함: ${label} 관련 답변 — \"${answer.trim().take(80)}\" (제약으로 정제문에 전달됩니다)"
        val recorded = listOfNotNull(
            "출처".takeIf { r.source.isNotEmpty() }, "대상 화면".takeIf { r.screen.isNotEmpty() },
            "마스킹 규칙".takeIf { r.mask.isNotEmpty() }, "그 밖".takeIf { r.other.isNotEmpty() }
        )
        return buildString {
            append("기록함: ${label} 관련 답변 — ${recorded.joinToString(", ")} (제약으로 정제문에 전달됩니다)")
            if (r.source.isNotEmpty()) append("\n- 출처: ").append(sourceLine(r.source))
            if (r.screen.isNotEmpty()) append("\n- 대상 화면: ").append(r.screen.joinToString(" / "))
            if (r.mask.isNotEmpty()) append("\n- 마스킹 규칙: ").append(r.mask.joinToString(" / "))
            if (r.other.isNotEmpty()) append("\n- 그 밖: ").append(r.other.joinToString(" / "))
        }
    }
}
