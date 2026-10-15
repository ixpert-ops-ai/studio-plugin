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

    // 사용자 추가 발화 원문 목록 (무손실 원본 기록 및 후속 Implement 단계용. Analyze는 이 값을 직접 읽지 않고 AnalyzeInputResolver를 거친다)
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

    // 사용자가 직접 발화/지정한 식별자 중 대화 종료 시점까지 미확정(PENDING)으로 남은 항목 목록
    val unresolvedItems: List<UnresolvedItem> = emptyList(),

    // 제외 요청 문장(EXCLUDE_COMPONENT)이 제외 확정으로 이어지지 않은 목록. 표시 전용이며 판정(excludedFiles, 후보, 최종 목록)에는 쓰지 않는다.
    val unconfirmedExclusions: List<UnconfirmedExclusion> = emptyList(),

    // 계약 버전
    val contractVersion: String = CURRENT_INTENT_VERSION
) {
    companion object {
        const val CURRENT_INTENT_VERSION = "1.2"
    }
}

/**
 * 미확정 사용자 발화 항목 종류
 */
enum class UnresolvedKind {
    EXISTING_REF,
    NEW_CREATION
}

/**
 * 미확정 사용자 발화 항목 구조체
 */
data class UnresolvedItem(
    val identifier: String,        // 필수: 사용자가 발화한 토큰 원문
    val kind: UnresolvedKind,      // EXISTING_REF 또는 NEW_CREATION
    val filePath: String?,         // EXISTING_REF일 때의 파일 경로 (NEW_CREATION은 null)
    val source: HintSource,        // 발화 출처 (USER_UTTERED 또는 PROPOSED_EXCLUSION)
    val utteredTurn: Int,          // 필수: 최초 발화 턴 번호
    val lastQuestion: String?,     // 실제 제시된 확인 질문 원문 (미질의 시 null)
    val lastAskedTurn: Int?        // 질문이 실제 제시된 턴 번호 (미질의 시 null)
)

/**
 * 확정되지 않은 제외 요청. 사용자가 "~는 건드리지 말고"처럼 말했지만 해당 파일이 제외 확정(REJECTED)되지 않은 경우의 기록.
 * candidateFiles가 비어 있으면 증거 구문으로 대상 파일을 특정하지 못한 경우다.
 */
data class UnconfirmedExclusion(
    val evidence: String,
    val candidateFiles: List<String> = emptyList(),
    val statement: String? = null   // 증거 구문이 들어 있는 사용자 발화 전체(표시용). 없으면 evidence를 쓴다.
)

/**
 * 분석 결과 요약에 보일 제외 확정·미확정 줄(표시 전용). 둘 다 없으면 빈 목록이라 아무 줄도 보이지 않는다.
 */
fun ClarifyIntent.exclusionSummaryLines(): List<String> {
    val lines = mutableListOf<String>()
    if (excludedFiles.isNotEmpty()) {
        lines.add("- ✅ 제외 확정: ${excludedFiles.joinToString { it.substringAfterLast('/') }}")
    }
    unconfirmedExclusions.forEach { u ->
        val quoted = u.statement ?: u.evidence
        if (u.candidateFiles.isEmpty()) {
            // 후보 목록에 대상이 없으면 체크를 해제할 수 없다. 결과 화면에는 파일을 제외하는 기능이 없으므로 확인을 안내한다.
            lines.add("- ⚠️ 제외 요청이 확정되지 않았습니다: '${quoted}'. 대상 파일을 찾지 못해 제외하지 못했습니다. 아래 결과 목록에 해당 파일이 포함됐는지 확인하세요.")
        } else {
            lines.add("- ⚠️ 제외 요청이 확정되지 않았습니다: '${quoted}' (후보: ${u.candidateFiles.joinToString { it.substringAfterLast('/') }}). 분석 결과에 포함될 수 있습니다. 제외하려면 /clarify에서 해당 파일의 체크를 해제한 뒤 확정하세요.")
        }
    }
    return lines
}

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
