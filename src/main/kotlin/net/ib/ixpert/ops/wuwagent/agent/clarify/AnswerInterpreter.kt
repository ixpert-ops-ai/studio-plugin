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
    private val MASK = Regex("마스킹|자리|도메인|\\*")
    private val SCREEN = Regex("화면|페이지|관리자|목록|상세")
    private val SOURCE = Regex("수집|저장|컬럼|조인|테이블|새로|추가|가져|연동|출처")
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

    /** 결과 경고의 "사용자 답변" 자리에 인용할 출처 부분. 출처로 나눠진 절이 없으면 답 앞부분을 그대로 쓴다. */
    fun sourceQuote(answer: String, limit: Int = 80): String {
        val r = interpret(answer)
        return (if (r.source.isNotEmpty()) r.source.joinToString(" / ") else answer.trim()).take(limit)
    }

    /** Stage 0 대화에 보일 "기록함" 메시지: 발화 통째 인용 대신 해석한 항목을 줄마다 보인다. 하나도 못 나눴으면 원문 앞부분을 인용한다. */
    fun echo(label: String, answer: String): String {
        val r = interpret(answer)
        if (r.isEmpty) return "기록함: ${label}의 출처 — \"${answer.trim().take(80)}\" (제약으로 정제문에 전달됩니다)"
        return buildString {
            append("기록함: ${label}의 출처 (제약으로 정제문에 전달됩니다)")
            if (r.source.isNotEmpty()) append("\n- 출처: ").append(r.source.joinToString(" / "))
            if (r.screen.isNotEmpty()) append("\n- 대상 화면: ").append(r.screen.joinToString(" / "))
            if (r.mask.isNotEmpty()) append("\n- 마스킹 규칙: ").append(r.mask.joinToString(" / "))
            if (r.other.isNotEmpty()) append("\n- 그 밖: ").append(r.other.joinToString(" / "))
        }
    }
}
