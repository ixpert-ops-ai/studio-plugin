package net.ib.ixpert.ops.wuwagent.agent.clarify

/**
 * 후보 선정 근거 문구(스캐너가 쓰는 내부 표현)를 사용자에게 보이는 말로 바꾼다. 작업 요약 프롬프트에 근거를 넣기 전에만 쓰며,
 * 후보 항목에 저장된 근거(`anchorRationale`)와 후보 선정 규칙은 바꾸지 않는다. 등록되지 않은 문구는 원문 그대로 둔다.
 *
 * - `구조 식별자(a, b) 공유`(Stage0GraphScanner) → `같은 화면 변수(a, b) 사용`
 * - `View-Script 페어` → `같은 화면의 스크립트`
 * - `CALLS 연결` → `호출 관계`
 * - `요구사항 토큰(x)과 일치` → `이름이 요구사항 단어(x)와 비슷함`
 */
object RationaleWording {
    private val RULES: List<Pair<Regex, String>> = listOf(
        Regex("""구조 식별자\(([^)]*)\) 공유""") to "같은 화면 변수(\$1) 사용",
        Regex("""View-Script 페어""") to "같은 화면의 스크립트",
        Regex("""CALLS 연결""") to "호출 관계",
        // "클래스명/엔드포인트가 요구사항 토큰(x)과 일치"처럼 앞에 붙은 주어는 새 문구가 이름을 말하므로 함께 바꾼다
        Regex("""(?:클래스명/엔드포인트가 )?요구사항 토큰\(([^)]*)\)(?:과|와)? ?일치""") to "이름이 요구사항 단어(\$1)와 비슷함"
    )

    fun toUserWording(raw: String): String = RULES.fold(raw) { acc, (re, to) -> re.replace(acc, to) }

    /**
     * 모델이 쓴 요약 문장에서 내부 용어를 사용자 문구로 바꾼다(B-79). 프롬프트의 근거는 [toUserWording]으로 바꿔 넣어도 모델이
     * "근거가 토큰 일치 수준에 불과하므로"처럼 내부 말을 다시 만들어 쓴다.
     * - `토큰 일치`, `토큰이 일치` → `이름 유사`, `이름이 비슷`
     * - 남은 `토큰` → `단어`
     * 근거 문구용 규칙([toUserWording])도 함께 적용한다. 등록되지 않은 표현은 그대로 둔다.
     */
    private val SUMMARY_RULES: List<Pair<Regex, String>> = listOf(
        Regex("""토큰\s*(?:이|과|와)?\s*일치""") to "이름 유사",
        Regex("""토큰(?:과|와)""") to "단어와", // "단어"는 받침이 없어 조사는 "와"
        Regex("""토큰""") to "단어"
    )

    fun cleanSummary(text: String): String = SUMMARY_RULES.fold(toUserWording(text)) { acc, (re, to) -> re.replace(acc, to) }
}
