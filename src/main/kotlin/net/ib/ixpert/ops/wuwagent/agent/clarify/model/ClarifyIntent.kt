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

    // 사용자 추가 발화 원문 목록 (Analyze는 refinedRequirement를 소비하고, userStatements는 무손실 원본 기록 및 후속 Implement 단계용)
    val userStatements: List<String> = emptyList(),

    // 대화 중 검증/확정된 핵심 앵커 토큰 (Analyze Seed 우선 부여용)
    val anchorTokens: List<String> = emptyList(),

    // 대화에서 추출된 구조화 제약 조건 (오탐 사전 차단용)
    val constraints: List<IntentConstraint> = emptyList(),

    // 대화 중 명시적으로 배제된 파일 경로 목록 (1급 결정 이양용)
    val excludedFiles: List<String> = emptyList(),

    // 결정론 앵커 (그래프 정합성 검증)
    val graphHash: String,

    // 식별자 보존 검사 및 안전판 보정 감사 기록 (지표 왜곡 방지 및 품질 모니터링용)
    val retentionAudit: RetentionAudit? = null,

    // 계약 버전
    val contractVersion: String = "1.1"
)

/**
 * 식별자 보존 검사 감사 기록
 */
data class RetentionAudit(
    val rawRefinedRequirement: String,
    val expectedIdentifiers: List<String> = emptyList(),
    val missingBeforeFix: List<String> = emptyList(),
    val auxiliaryIdentifiers: List<String> = emptyList(), // 그래프 클래스명 구성단어와 일치하는 보조 식별자 (정제문 강제병기 없이 기록/모니터링)
    val wasRetainedWithoutModification: Boolean = true,
    val scopeModifierDropped: Boolean = false // '만' 등 범위 한정 조사가 LLM 정제문에서 누락되었는지 감사 기록
)

/**
 * 대화에서 포착된 의도 제약 단위
 */
data class IntentConstraint(
    val kind: ConstraintKind = ConstraintKind.OTHER,
    val value: String,
    val rawStatement: String? = null, // 원본 발화 보존용 (디버깅/추적 안전판)
    val evidence: String? = null,     // 발화 원문 내 직접적 근거 구문 (환각 검증 및 100% 사실 기반성 보장용)
    val rawKind: ConstraintKind? = null // 가드 개입 전 LLM의 원본 분류 (가드 개입 여부 및 품질 감사용)
)

/**
 * 제약 조건 분류 유형
 */
enum class ConstraintKind {
    INCLUDE_CHANNEL,    // 특정 채널/경로 포함 (예: 알림톡 채널)
    EXCLUDE_COMPONENT,  // 특정 컴포넌트/배치/기능 배제 (예: 알림톡 배치 수정 제외)
    EXCLUDE_EXTERNAL,   // 외부 연동 배제 (예: 외부 API 미사용)
    NEW_MODULE,         // 신규 모듈 생성 필요 (사용자가 신규 개발/생성을 명시한 경우만)
    SCOPE_LIMIT,        // 범위 한정 (예: 특정 파일/클래스만 수정)
    OTHER               // 분류 불능 자유 제약 (안전판 폴백)
}
