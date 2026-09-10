package net.ib.ixpert.ops.wuwagent.agent.clarify.model

/**
 * Stage 0 Clarification 완료 후 Stage 1 영향도 분석으로 전이되는 영속 계약 아티팩트 모델.
 * - 스키마 버전 헤더, 타임스탬프, 그래프 해시 무결성 검증
 * - 확정된 기존 파일(trustedExistingRefs) 및 신규 생성(newCreations)
 * - 신규 슬롯의 원본 참조 앵커(anchorSiblingRefs)
 * - 거부된 항목 분리 보존 (rejectedExistingRefs for Stage 1 filter, rejectedNewCreations for Clarify re-session suppression)
 */
data class Stage0TransitionContract(
    val contractVersion: String = "1.0",
    val createdAt: String, // ISO-8601
    val graphHash: String,
    val sessionId: String = "default",
    val srId: String = "",
    val confirmedItems: List<RequirementItem> = emptyList(),
    val trustedExistingRefs: List<LinkHint.ExistingRef> = emptyList(),
    val newCreations: List<RequirementItem> = emptyList(),
    val anchorSiblingRefs: List<LinkHint.ExistingRef> = emptyList(),
    val rejectedExistingRefs: List<LinkHint.ExistingRef> = emptyList(),
    val rejectedNewCreations: List<RequirementItem> = emptyList(),
    val enrichedRequirementText: String = ""
)
