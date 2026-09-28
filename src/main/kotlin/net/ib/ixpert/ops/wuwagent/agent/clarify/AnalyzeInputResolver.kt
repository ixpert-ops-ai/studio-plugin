package net.ib.ixpert.ops.wuwagent.agent.clarify

import net.ib.ixpert.ops.wuwagent.agent.clarify.model.ClarifyIntent

data class ResolvedAnalyzeInput(
    val effectiveRequirement: String,
    val effectiveIntent: ClarifyIntent?
)

object AnalyzeInputResolver {
    /**
     * Analyze 파이프라인 입력 및 계약 인계 결정:
     * - inMemoryIntent가 전달된 경우(Clarify 세션 직후 전이): Clarify 정제 의도 및 제약/배제 계약을 사용.
     * - inMemoryIntent가 null인 경우(직접 /analyze 실행): 사용자 입력을 100% 최우선 보장하며,
     *   과거 디스크 잔존 인텐트나 stale 계약을 일절 소비하지 않음 (유령 오염 원천 차단).
     */
    fun resolve(
        rawInput: String,
        inMemoryIntent: ClarifyIntent?
    ): ResolvedAnalyzeInput {
        return if (inMemoryIntent != null) {
            val req = inMemoryIntent.refinedRequirement.ifBlank { 
                inMemoryIntent.originalRequirement.ifBlank { rawInput } 
            }
            ResolvedAnalyzeInput(
                effectiveRequirement = req,
                effectiveIntent = inMemoryIntent
            )
        } else {
            ResolvedAnalyzeInput(
                effectiveRequirement = rawInput,
                effectiveIntent = null
            )
        }
    }
}
