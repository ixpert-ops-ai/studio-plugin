package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

/**
 * 3-샘플 실측(ScReturn, Chat, PDsbUse) 기반 초기 가설 파라미터.
 * 
 * [설계 철학 & 가설 명시]
 * 1. False Positive(불필요한 질의)가 False Negative(오답 자동 확정)보다 안전하다는 원칙 적용.
 * 2. 현재 3개 케이스는 최상위 점수 격차가 크거나(30~55pt) 완전 동점(0pt)이어서 SCORE_TOLERANCE_EPSILON에 무감함.
 *    향후 근소차(비완전 동점) 케이스 확보 시 정밀 보정 요망.
 * 3. 신규 Archetype 케이스가 확보되면 정상 케이스에서의 False Positive 발생 여부를 재검증해야 함.
 */
object SeedAmbiguityConfig {
    const val SCORE_TOLERANCE_EPSILON = 5.0   // 동점으로 간주할 최상위 점수 허용 오차 (가설치)
    const val MIN_AMBIGUOUS_CANDIDATES = 3     // 모호성 판정 최소 상위 후보 수 (가설치)
    const val MIN_DISTINCT_PACKAGES = 2       // 서로 다른 업무 패키지 분산 임계값 (가설치)
}

/**
 * 그래프 탐색 초기 검색 결과의 모호성(Ambiguity) 및 엔트로피를 판정하는 순수 함수 모듈.
 * 도메인 단어 하드코딩 없이, 점수 분포와 업무 패키지 분산도만을 기반으로 판정함.
 */
object SeedAmbiguityEvaluator {

    private val STRUCTURAL_STOPWORDS = setOf(
        "response", "request", "service", "impl", "vo", "dto", "controller", "repository",
        "mapper", "dao", "biz", "svo", "bvo", "dvo", "dem", "dqm", "util", "helper",
        "config", "entity", "model", "api", "app", "bo", "src", "main", "java", "com", "sc", "chn", "aps", "apc"
    )

    private val LAYER_KEYWORDS = setOf(
        "controller", "service", "repository", "dao", "mapper", "dto", "request", "response", "entity", "domain", "app", "bo"
    )

    /**
     * 검색된 후보 목록(점수 내림차순 정렬 가정)에서 모호성 여부를 평가.
     * @return true: 상위 점수 동점/근소차 다수 & 복수 업무 패키지에 분산 (askUser 발동 대상)
     *         false: 단독 1위 또는 단일 업무 패키지 군집 (자동 탐색 지속)
     */
    fun evaluate(candidates: List<Map<String, Any>>): Boolean {
        if (candidates.isEmpty()) return false

        val topScore = (candidates.first()["matchScore"] as? Number)?.toDouble() ?: 0.0
        if (topScore <= 0.0) return false

        // 1. 최상위 점수와 근소한(Epsilon 이내) 상위 후보군 추출
        val topTier = candidates.filter {
            val score = (it["matchScore"] as? Number)?.toDouble() ?: 0.0
            (topScore - score) <= SeedAmbiguityConfig.SCORE_TOLERANCE_EPSILON
        }

        if (topTier.size < SeedAmbiguityConfig.MIN_AMBIGUOUS_CANDIDATES) {
            return false // 단독 1위이거나 상위 후보 수가 부족하면 자동 진행
        }

        // 2. 계층 디렉토리 오염을 제거한 순수 업무 패키지 분산도 측정
        val distinctPackages = topTier.mapNotNull { it["path"]?.toString() }
            .map { extractBusinessPackageRobust(it) }
            .distinct()

        // 3. 복수 업무 도메인 패키지에 흩어져 있으면 모호(True) 판정
        return distinctPackages.size >= SeedAmbiguityConfig.MIN_DISTINCT_PACKAGES
    }

    /**
     * 파일 경로에서 Controller/Service/DAO 등의 계층 폴더 및 공통 불용어를 제거하고
     * 순수 비즈니스 업무 도메인 세그먼트를 추출함.
     */
    fun extractBusinessPackageRobust(path: String): String {
        val normalized = path.replace("\\", "/")
        val parts = normalized.split("/").filter { it.isNotBlank() }
        if (parts.isEmpty()) return "root"

        // 파일명 제거 (디렉토리 경로만 추출)
        val dirParts = if (parts.last().contains(".")) parts.dropLast(1) else parts

        // 계층 키워드 및 구조 불용어 제거
        val businessTokens = dirParts.filter { p ->
            val lower = p.lowercase()
            lower !in LAYER_KEYWORDS && lower !in STRUCTURAL_STOPWORDS
        }

        return when {
            businessTokens.size >= 2 -> businessTokens.takeLast(2).joinToString("/")
            businessTokens.size == 1 -> businessTokens.first()
            else -> dirParts.lastOrNull() ?: "root"
        }
    }
}
