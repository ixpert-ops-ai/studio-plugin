package net.ib.ixpert.ops.wuwagent.agent.clarify

import net.ib.ixpert.ops.wuwagent.agent.clarify.model.ClarifyIntent
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.UnresolvedItem

/**
 * Analyze 파이프라인 입력.
 * - effectiveRequirement: 결정론적 단계(`AdaptiveFileDiscovery.filter`의 primaryReq)에 넘길 텍스트.
 *   누락 식별자가 없으면 rawRefined와 같고, 있으면 `"$rawRefined\n" + missingIdentifiers.joinToString("") { "\n$it" }`.
 * - rawRefined: 태그가 붙기 전 정제문. 정제문이 공백이면 originalRequirement(그것도 공백이면 rawInput)로 복원한 값.
 * - missingIdentifiers: `retentionAudit.missingBeforeFix`에서 `subtractedIdentifiers`를 뺀 값(사전순 유지).
 *   `retentionAudit` 자체는 바꾸지 않는다.
 * - unresolvedItems, excludedFiles: 인텐트 값 그대로(`unresolvedItems`는 차감하지 않음).
 * - subtractedIdentifiers: `missingBeforeFix` 중 `excludedFiles`에 해당해 차감된 식별자(`missingBeforeFix` 순서 유지).
 *   로그와 테스트용이며 프롬프트에 넣지 않는다.
 */
data class ResolvedAnalyzeInput(
    val effectiveRequirement: String,
    val effectiveIntent: ClarifyIntent?,
    val rawRefined: String = effectiveRequirement,
    val missingIdentifiers: List<String> = emptyList(),
    val unresolvedItems: List<UnresolvedItem> = emptyList(),
    val excludedFiles: List<String> = emptyList(),
    val subtractedIdentifiers: List<String> = emptyList()
)

object AnalyzeInputResolver {
    /** 그래프에서 클래스명(`FileNode.className`)이 만들어지는 소스 확장자 (`ProjectGraphBuilder.collectJavaAndKotlinFiles`). */
    private val CLASS_SOURCE_EXTENSIONS = setOf("java", "kt")

    /**
     * 식별자가 사용자가 제외한 파일을 가리키는지 판정한다(순수 문자열 비교, 그래프 조회 없음).
     * `excludedFiles`는 그래프 경로 전체(확장자 포함)다.
     * 1) 확장자 포함 파일명 일치(모든 확장자), 2) 제외 파일이 클래스 소스(java/kt)일 때만 기본 이름 일치. 둘 다 대소문자 무시.
     */
    private fun isExcludedIdentifier(id: String, excludedFiles: List<String>): Boolean = excludedFiles.any { path ->
        val file = path.substringAfterLast('/')
        val ext = file.substringAfterLast('.', "").lowercase()
        val base = file.substringBeforeLast('.')
        id.equals(file, ignoreCase = true) ||
            (ext in CLASS_SOURCE_EXTENSIONS && id.equals(base, ignoreCase = true))
    }

    /**
     * Analyze 파이프라인 입력 및 계약 인계 결정:
     * - inMemoryIntent가 전달된 경우(Clarify 세션 직후 전이): Clarify 정제 의도 및 제약/배제 계약을 사용.
     *   `retentionAudit`이 있으면 태그 없는 정제문(`rawRefinedRequirement`)과 `missingBeforeFix`로 입력을 조립하고,
     *   `retentionAudit`이 null이면 기존처럼 `refinedRequirement`를 그대로 쓴다.
     * - inMemoryIntent가 null인 경우(직접 /analyze 실행): 사용자 입력을 100% 최우선 보장하며,
     *   과거 디스크 잔존 인텐트나 stale 계약을 일절 소비하지 않음 (유령 오염 원천 차단).
     */
    fun resolve(
        rawInput: String,
        inMemoryIntent: ClarifyIntent?
    ): ResolvedAnalyzeInput {
        return if (inMemoryIntent != null) {
            val audit = inMemoryIntent.retentionAudit
            val sourceRefined = audit?.rawRefinedRequirement ?: inMemoryIntent.refinedRequirement
            val rawRefined = sourceRefined.ifBlank {
                inMemoryIntent.originalRequirement.ifBlank { rawInput }
            }
            val (subtracted, missing) = (audit?.missingBeforeFix ?: emptyList())
                .partition { isExcludedIdentifier(it, inMemoryIntent.excludedFiles) }
            val req = if (missing.isEmpty()) {
                rawRefined
            } else {
                "$rawRefined\n" + missing.joinToString("") { "\n$it" }
            }
            ResolvedAnalyzeInput(
                effectiveRequirement = req,
                effectiveIntent = inMemoryIntent,
                rawRefined = rawRefined,
                missingIdentifiers = missing,
                unresolvedItems = inMemoryIntent.unresolvedItems,
                excludedFiles = inMemoryIntent.excludedFiles,
                subtractedIdentifiers = subtracted
            )
        } else {
            ResolvedAnalyzeInput(
                effectiveRequirement = rawInput,
                effectiveIntent = null,
                rawRefined = rawInput
            )
        }
    }
}
