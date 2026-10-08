package net.ib.ixpert.ops.wuwagent.agent.clarify

/**
 * Stage 3(`FileRelevanceVerifier.verify`)에 넘기는 요구사항 텍스트 조립 (순수 함수).
 *
 * 본문은 태그와 식별자 줄이 붙지 않은 `rawRefined`이고, 식별자는 두 섹션으로만 전달한다.
 * - 미확정 섹션: `unresolvedItems` 전체를 인텐트가 준 순서 그대로 `- <identifier> (<UnresolvedKind>)`.
 *   걸러내거나 중복 제거하지 않으며 본문 포함 여부를 보지 않는다.
 * - 빠진 식별자 섹션: `missingIdentifiers − unresolvedItems`(대소문자 무시)를 주어진 순서(사전순) 그대로 `- <identifier>`.
 *   이 섹션 안에서 대소문자 무시 중복이면 먼저 나온 철자만 남긴다.
 * 빈 섹션은 헤더를 생략하고, 두 섹션이 모두 비면 본문만 반환한다(`ResolvedAnalyzeInput`이 `resolve()` 출력이면 `effectiveRequirement`와 같다).
 * 줄바꿈은 LF이며 끝 줄바꿈은 붙이지 않는다(검증기가 `appendLine`으로 붙임).
 */
object AnalyzeSections {
    const val UNRESOLVED_HEADER = "[미확정(확정 아님) 식별자]"
    const val MISSING_HEADER = "[원문에 있었으나 정제문에서 빠진 식별자]"

    /**
     * @param input null이면 [fallbackBase]를 그대로 돌려준다(`analyze()`의 기존 기준 텍스트).
     * @param enrichedRequirementText `stage0Contract?.enrichedRequirementText`. null이 아니면(빈 문자열 포함) 본문으로 쓴다.
     */
    fun buildStage3Base(
        input: ResolvedAnalyzeInput?,
        secondaryReq: String,
        enrichedRequirementText: String?,
        fallbackBase: String
    ): String {
        if (input == null) return fallbackBase

        val body = enrichedRequirementText
            ?: if (secondaryReq.isNotBlank()) "${input.rawRefined}\n$secondaryReq" else input.rawRefined

        val unresolvedLines = input.unresolvedItems.map { "- ${it.identifier} (${it.kind.name})" }

        val unresolvedKeys = input.unresolvedItems.map { it.identifier.lowercase() }.toSet()
        val missingLines = input.missingIdentifiers
            .filter { it.lowercase() !in unresolvedKeys }
            .distinctBy { it.lowercase() }
            .map { "- $it" }

        return buildString {
            append(body)
            if (unresolvedLines.isNotEmpty()) {
                append("\n\n").append(UNRESOLVED_HEADER)
                unresolvedLines.forEach { append('\n').append(it) }
            }
            if (missingLines.isNotEmpty()) {
                append("\n\n").append(MISSING_HEADER)
                missingLines.forEach { append('\n').append(it) }
            }
        }
    }
}
