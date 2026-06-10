package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

/**
 * 파일 후보의 점수 및 매칭 사유를 담는 데이터 클래스.
 */
data class ScoredCandidate(
    val filePath: String,
    val score: Double,
    val matchedBy: List<String>
)

/**
 * 모든 서브 수집기가 구현해야 하는 인터페이스.
 * 각 수집기는 [AnalyzedQuery]를 받아 [ScoredCandidate] 리스트를 반환합니다.
 */
interface SubCollector {
    fun search(query: AnalyzedQuery): List<ScoredCandidate>
}

/**
 * 사용자 입력을 분석한 결과물.
 * [QueryAnalyzer]가 생성하며, 각 [SubCollector]에 전달됩니다.
 */
data class AnalyzedQuery(
    /** 원본 사용자 입력 */
    val original: String,
    /** 한글 명사 추출 결과 (조사 제거 후) */
    val koreanNouns: List<String>,
    /** 영어 토큰 (CamelCase 분해 + 사전 변환 포함, 소문자) */
    val englishTokens: Set<String>,
    /** 정확한 식별자 (CamelCase, ALL_CAPS, 패키지 경로) */
    val exactIdentifiers: List<String>,
    /** URL 형태 문자열 (/로 시작) */
    val urlPatterns: List<String>,
    /** ServiceId/클래스 패턴 (대문자+숫자 6자 이상) */
    val serviceIds: List<String>
)
