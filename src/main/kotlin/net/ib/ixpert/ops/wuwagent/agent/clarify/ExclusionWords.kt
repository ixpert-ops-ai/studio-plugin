package net.ib.ixpert.ops.wuwagent.agent.clarify

/**
 * "X는 건드리지 마" 계열 발화 판별에 쓰는 결정론적 단어 규칙(LLM 없음).
 * - 제외 단어가 있는 발화나 SR 원문에서만 제약 추출(LLM 호출)과 제외 제안 흐름을 시작한다.
 * - 번역기가 ADD_CONSTRAINT 안내문에서 실제와 다른 말을 하지 않도록 같은 규칙으로 안내 문구를 가른다.
 */
object ExclusionWords {
    private val PATTERN = Regex("건드|빼|제외|말고|그대로|배제|손대|수정하지|변경하지")

    fun containsExclusionWord(text: String?): Boolean = !text.isNullOrBlank() && PATTERN.containsMatchIn(text)
}
