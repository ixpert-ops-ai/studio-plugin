package net.ib.ixpert.ops.wuwagent.agent.clarify.model

/**
 * clarify -> analyze 인수인계 계약 (단방향 불변 인텐트).
 * 대화 표면(로그, 요약문, 렌더링 카드)은 완전히 배제하고 오직 구조화된 의도만 전송.
 */
data class ClarifyIntent(
    // 최초 원문 (불변 기록/추적용)
    val originalRequirement: String,

    // 대화로 정제된 최종 요구사항 (Analyze 방향타)
    val refinedRequirement: String,

    // 대화 중 검증/확정된 핵심 앵커 토큰 (Analyze Seed 우선 부여용)
    val anchorTokens: List<String> = emptyList(),

    // 대화에서 추출된 구조화 제약 조건 (오탐 사전 차단용)
    val constraints: List<IntentConstraint> = emptyList(),

    // 결정론 앵커 (그래프 정합성 검증)
    val graphHash: String,

    // 계약 버전
    val contractVersion: String = "1.0"
)

/**
 * 대화에서 포착된 의도 제약 단위
 */
data class IntentConstraint(
    val kind: ConstraintKind = ConstraintKind.OTHER,
    val value: String,
    val rawStatement: String? = null // 원본 발화 보존용 (디버깅/추적 안전판)
)

/**
 * 제약 조건 분류 유형
 */
enum class ConstraintKind {
    INCLUDE_CHANNEL,    // 특정 채널/경로 포함 (예: 알림톡 채널)
    EXCLUDE_EXTERNAL,   // 외부 연동 배제 (예: 외부 API 미사용)
    NEW_MODULE,         // 신규 모듈 생성 필요 (예: 신규 연동 어댑터)
    SCOPE_LIMIT,        // 범위 한정 (예: 발송 화면만 수정)
    OTHER               // 분류 불능 자유 제약 (안전판 폴백)
}
