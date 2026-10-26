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
}
