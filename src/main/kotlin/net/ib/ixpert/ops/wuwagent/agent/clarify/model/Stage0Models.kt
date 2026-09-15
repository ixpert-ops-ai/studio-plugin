package net.ib.ixpert.ops.wuwagent.agent.clarify.model

import java.security.MessageDigest

/**
 * Stage 0 요구사항 구체화 대화 기능의 핵심 데이터 모델.
 * 설계서 (v1.0) 및 형제 유추 스펙 (v1.1) 준수.
 */

/**
 * 2.2 힌트 출처 태그 (신뢰 수준)
 */
enum class HintSource {
    USER_CONFIRMED,        // 시스템 제안을 사용자가 확인
    USER_UTTERED,          // 사용자가 직접 발화한 요구
    SYSTEM_UNCONFIRMED     // 시스템 제안, 미확인 상태
}

/**
 * 2.3 연결 힌트 (기존 그래프 요소 참조 vs 신규 생성)
 */
sealed class LinkHint {
    /**
     * 그래프에 실재하는 파일 및 심볼 참조
     */
    data class ExistingRef(
        val filePath: String,            // ResourceNode.path 또는 클래스 경로
        val symbols: List<String> = emptyList() // 관련 필드/메소드명
    ) : LinkHint()

    /**
     * 그래프에 실재하지 않는 신규 생성 지시
     */
    object NewCreation : LinkHint()
}

/**
 * 2.4 판정 상태 (동결 여부)
 */
enum class Verdict {
    PENDING,      // 미판정 — 재탐색 대상
    CONFIRMED,    // 사용자 확인 — 동결 (재탐색으로 덮어쓰지 않음)
    REJECTED      // 사용자 거부 — 동결 (재탐색으로 덮어쓰지 않음)
}

/**
 * 신뢰도 버킷 (v1.1 스펙 2단계 & 3단계)
 * - HIGH_CONFIDENCE: 개별 확인 대상 (구조 식별자, Mapper 체인, 다중 신호 교차)
 * - LOW_CONFIDENCE: 접힌 묶음 대상 (형제 유추 단독 신호)
 */
enum class ConfidenceBucket {
    HIGH_CONFIDENCE,
    LOW_CONFIDENCE
}

/**
 * 신호 출처 채널 (v1.1 스펙 4대 병렬 신호)
 */
enum class ProvenanceSignal {
    STRUCTURAL_ID,     // 구조 식별자 공유 엣지
    MAPPER_CHAIN,      // MyBatis Mapper 네임스페이스/테이블 바인딩
    VIEW_SCRIPT_PAIR,  // JSP/JS URL 페어링
    SIBLING_ANALOGY,   // 위상 기반 형제 유추
    USER_UTTERANCE,    // 사용자 직접 발화
    LOCAL_NAME_MATCH   // 메타그래프 자연어명(localName) 매칭
}

/**
 * 3단계 1층: 구조적 슬롯 제안 (Structural Slot Proposal)
 */
data class StructuralSlotProposal(
    val slotType: String,                 // 예: "COHESIVE_SUBGRAPH_CLUSTER", "SERVICE_PAIR"
    val templateComponents: List<String>, // 형제 컴포넌트 템플릿 파일명 목록
    val description: String               // 사용자 설명 문구
)

/**
 * 2.7 거부 사유 (RejectionReason) - 2지선다
 * - FILE_MISMATCH: 이 특정 파일이 아님 (대체 후보 필요, 새 엣지/신호로 재등장 가능)
 * - CONCEPT_IRRELEVANT: 이 업무/개념 자체가 무관함 (완전 억제, 재제안 불가)
 */
enum class RejectionReason {
    FILE_MISMATCH,
    CONCEPT_IRRELEVANT
}

/**
 * 2.1 요구사항 적립 단위 (RequirementItem)
 */
data class RequirementItem(
    val id: String,                    // 안정적 식별자 (재탐색 간 동일성 유지)
    val statement: String,             // 검증 가능한 "~해야 한다" 문장
    val source: HintSource,            // 출처 태그 (2.2)
    val hint: LinkHint,                // 연결 힌트 (2.3)
    val anchorRationale: String,       // 원 요구사항과의 연결 근거
    val verdict: Verdict = Verdict.PENDING, // 판정 상태 (2.4)
    val confidence: ConfidenceBucket = ConfidenceBucket.HIGH_CONFIDENCE, // 신뢰도 버킷 (v1.1)
    val provenanceSignals: Set<ProvenanceSignal> = emptySet(),           // 출처 신호 집합 (v1.1)
    val structuralSlotProposal: StructuralSlotProposal? = null,          // 구조 슬롯 제안 (v1.1)
    val rejectionReason: RejectionReason? = null,                        // 거부 사유 (v1.1 P1)
    val isReEmergence: Boolean = false,                                  // 새 엣지/신호로 재등장 여부 (v1.1 P1)
    val domainPackage: String? = null                                    // 도메인 패키지 클러스터 (v1.1 P2)
) {
    companion object {
        /**
         * 3.1절: 연결 힌트와 요구사항 문장으로부터 결정론적 id 파생
         * - ExistingRef: "ref:<filePath>:<sortedSymbols>"
         * - NewCreation: "new:<sha256(statement)>"
         */
        fun deriveId(hint: LinkHint, statement: String = ""): String {
            return when (hint) {
                is LinkHint.ExistingRef -> {
                    val normalizedPath = hint.filePath.trim().replace("\\", "/")
                    val sortedSymbols = hint.symbols.map { it.trim() }.filter { it.isNotEmpty() }.sorted().joinToString(",")
                    if (sortedSymbols.isEmpty()) {
                        "ref:$normalizedPath"
                    } else {
                        "ref:$normalizedPath:$sortedSymbols"
                    }
                }
                is LinkHint.NewCreation -> {
                    val normalizedStmt = statement.trim()
                    val hash = sha256(normalizedStmt).take(12)
                    "new:$hash"
                }
            }
        }

        private fun sha256(input: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(input.toByteArray(Charsets.UTF_8))
            return digest.fold("") { str, it -> str + "%02x".format(it) }
        }
    }
}

/**
 * 토큰 종류 (1차 구조 식별자 vs 2차 개념 토큰 폴백)
 */
enum class TokenKind {
    STRUCTURAL,   // 1차: 클래스명, DTO 필드명, input 필드명 등 그래프 실재 식별자
    CONCEPTUAL    // 2차 폴백: 도메인 어휘 (단어 경계 \b, _ 강제)
}

/**
 * 2.6 SeedToken (누적 seed 및 개념 토큰)
 */
data class SeedToken(
    val value: String,
    val kind: TokenKind
)

/**
 * 2.5 Stage0State (압축된 대화 상태)
 */
data class Stage0State(
    val originalRequirement: String,          // 원 요구 (앵커)
    val items: List<RequirementItem> = emptyList(), // 확정 + 미판정 단위 전체
    val seedSet: Set<SeedToken> = emptySet()        // 누적 seed·개념 토큰 (2.6)
)
