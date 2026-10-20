package net.ib.ixpert.ops.wuwagent.agent.clarify

/** 제외 제안("다음 파일을 제외할까요?")에 대한 사용자 답의 종류. */
enum class ExclusionAnswer {
    AFFIRM,  // 긍정어가 있고 부정어가 없음: 제안한 파일을 제외로 확정
    NEGATE,  // 부정어만 있음: 제안 취소
    MIXED,   // 긍정어와 부정어가 섞임("네 근데 Runner는 빼지 마세요"): 확정하지 않고 다시 묻지도 않으며 미확정으로 남김
    OTHER    // 답이 아닌 다른 발화
}

/**
 * "X는 건드리지 마" 계열 발화 판별에 쓰는 결정론적 단어 규칙(LLM 없음).
 * - 제외 단어가 있는 발화나 SR 원문에서만 제약 추출(LLM 호출)과 제외 제안 흐름을 시작한다.
 * - 번역기가 ADD_CONSTRAINT 안내문에서 실제와 다른 말을 하지 않도록 같은 규칙으로 안내 문구를 가른다.
 * - 제외 제안에 대한 답은 단어 단위로 판정한다(정확 일치가 아니라 "긍정어 포함 + 부정어 없음").
 */
object ExclusionWords {
    private val PATTERN = Regex("건드|빼|제외|말고|그대로|배제|손대|수정하지|변경하지")

    fun containsExclusionWord(text: String?): Boolean = !text.isNullOrBlank() && PATTERN.containsMatchIn(text)

    private val SPLITTER = Regex("[\\s,.!?~·]+")

    // 긍정: 짧은 긍정어는 토큰 전체가 일치할 때만(예: "네트워크"의 "네"는 긍정이 아니다), 제외 동사는 어간으로 시작할 때
    private val AFFIRM_WORD = Regex("^(네+|넵|넹|예|응+|그래요?|맞아요?|맞습니다|좋아요?|오케이|ok|okay|yes|y|확인|확인함|확인했어요|확인합니다|제외|제외요)$")
    private val AFFIRM_STEMS = listOf("제외해", "제외하세요", "제외하겠", "빼줘", "빼주세요", "빼세요", "빼도")

    // 부정: 취소·유지·금지 표현. "빼지 마세요", "제외하지 마세요", "그대로 두세요"는 제외를 하지 말라는 뜻이다.
    private val NEGATE_WORD = Regex("^(아니(요|오)?|아뇨|no|n|취소|유지|포함해|포함해줘|건드려|그대로|말고|말아요|마세요|마라|마요|하지마|하지마세요|않아요?|안)$")
    private val NEGATE_STEMS = listOf("아니", "취소", "빼지", "제외하지", "제외말", "하지마", "건드리지", "포함해")

    // 제안에 대한 답은 짧다. 긴 문장("알림톡 배치는 건드리지 말고 SurveyServiceImpl만 수정할 거야")은 새 요청이므로 답으로 읽지 않는다.
    private const val MAX_ANSWER_TOKENS = 6
    private const val MAX_ANSWER_LENGTH = 30

    fun judgeAnswer(text: String): ExclusionAnswer {
        val tokens = text.lowercase().split(SPLITTER).filter { it.isNotBlank() }
        if (tokens.isEmpty() || tokens.size > MAX_ANSWER_TOKENS || text.trim().length > MAX_ANSWER_LENGTH) return ExclusionAnswer.OTHER
        val affirm = tokens.any { t -> AFFIRM_WORD.matches(t) || AFFIRM_STEMS.any { t.startsWith(it) } }
        val negate = tokens.any { t -> NEGATE_WORD.matches(t) || NEGATE_STEMS.any { t.startsWith(it) } }
        return when {
            affirm && negate -> ExclusionAnswer.MIXED
            affirm -> ExclusionAnswer.AFFIRM
            negate -> ExclusionAnswer.NEGATE
            else -> ExclusionAnswer.OTHER
        }
    }
}
