package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

/**
 * 사용자 입력을 분석하여 [AnalyzedQuery]를 생성하는 경량 분석기.
 *
 * 외부 형태소 분석 라이브러리 없이 regex 기반으로 동작합니다:
 * - 한글 토큰: 한글 2자 이상 추출 + 조사 제거
 * - 영어 토큰: CamelCase 분해 + 사전 변환 포함
 * - 정확 식별자: CamelCase, ALL_CAPS, 패키지 경로
 * - URL 패턴: /로 시작하는 문자열
 * - ServiceId: 대문자+숫자 6자 이상 조합
 */
class QueryAnalyzer(private val dictionary: DomainDictionary) {

    fun analyze(query: String): AnalyzedQuery {
        val koreanNouns = extractKoreanNouns(query)

        // 정확한 식별자 추출: CamelCase, ALL_CAPS, 패키지 경로
        val exactIdentifiers = IDENTIFIER_PATTERN
            .findAll(query)
            .map { it.value }
            .toList()

        // URL 패턴 추출
        val urlPatterns = URL_PATTERN
            .findAll(query)
            .map { it.value }
            .toList()

        // ServiceId/DEM클래스 패턴: 대문자+숫자 조합 6자 이상 (예: SAPCMM0204S01, ACAMTBAPC001DEM)
        val serviceIds = SERVICE_ID_PATTERN
            .findAll(query)
            .map { it.value }
            .filter { it !in EXCLUDED_SERVICE_IDS }
            .toList()

        // 영어 토큰: 식별자를 CamelCase 분해 + 나머지 영단어
        val engFromIdentifiers = exactIdentifiers.flatMap { DomainDictionary.tokenizeCamelCase(it) }
        val engFromFreeText = ENGLISH_WORD_PATTERN
            .findAll(query)
            .map { it.value.lowercase() }
            .filter { it !in STOP_WORDS }
            .toList()
        val englishTokens = (engFromIdentifiers + engFromFreeText).toMutableSet()

        // 한글 → 사전 변환 결과도 englishTokens에 합산
        val dictTranslated = koreanNouns.flatMap { dictionary.translate(it) }
        englishTokens.addAll(dictTranslated)

        return AnalyzedQuery(
            original = query,
            koreanNouns = koreanNouns,
            englishTokens = englishTokens,
            exactIdentifiers = exactIdentifiers,
            urlPatterns = urlPatterns,
            serviceIds = serviceIds
        )
    }

    /**
     * 한글 텍스트에서 명사를 추출합니다.
     * 형태소 분석 라이브러리 없이 regex 기반으로 동작합니다:
     * 1. 한글 2자 이상 연속 문자열 추출
     * 2. 조사/어미 제거
     * 3. 2자 이상 결과만 반환
     */
    private fun extractKoreanNouns(text: String): List<String> {
        return KOREAN_PATTERN
            .findAll(text)
            .map { it.value.replace(PARTICLE_PATTERN, "") }
            .filter { it.length >= 2 }
            .distinct()
            .toList()
    }

    companion object {
        /** 한글 2자 이상 연속 */
        private val KOREAN_PATTERN = Regex("[가-힣]{2,}")

        /** 한글 조사/어미 패턴 (기존 TargetExtractor + PsiMethodExtractor 통합) */
        private val PARTICLE_PATTERN = Regex(
            "(을|를|의|에서|에|이|가|은|는|와|과|로|으로|하는|하여|하고|위한|통한|대한|된|할|함|시|때)$"
        )

        /** CamelCase, ALL_CAPS, 패키지 경로 식별자 */
        private val IDENTIFIER_PATTERN = Regex(
            """[A-Z][a-zA-Z0-9]{2,}|[A-Z][A-Z0-9_]{2,}|[a-z]+\.[a-z]+\.[a-z.]+"""
        )

        /** URL 패턴 */
        private val URL_PATTERN = Regex("""/[a-zA-Z0-9/_\-{}]+""")

        /** ServiceId/DEM 클래스 패턴: 대문자+숫자 조합 6자 이상 */
        private val SERVICE_ID_PATTERN = Regex("""[A-Z][A-Z0-9]{5,}""")

        /** 영어 단어 (2자 이상) */
        private val ENGLISH_WORD_PATTERN = Regex("""[a-zA-Z]{2,}""")

        /** ServiceId 패턴에서 제외할 일반 영어 단어 */
        private val EXCLUDED_SERVICE_IDS = setOf(
            "SELECT", "INSERT", "UPDATE", "DELETE", "CREATE", "ALTER", "DROP",
            "STRING", "INTEGER", "BOOLEAN", "DOUBLE", "FLOAT", "OBJECT",
            "SPRING", "SERVICE", "CONTROLLER", "REPOSITORY", "ENTITY",
            "CONFIG", "ABSTRACT", "IMPLEMENT", "OVERRIDE", "RETURN",
            "PUBLIC", "PRIVATE", "PROTECTED", "STATIC", "IMPORT"
        )

        /** 의미 없는 영어 불용어 */
        private val STOP_WORDS = setOf(
            "the", "is", "at", "in", "on", "for", "to", "of", "and", "or",
            "this", "that", "with", "from", "when", "issue", "error", "fix",
            "bug", "add", "modify", "delete", "check", "please", "want",
            "need", "like", "know", "think", "get", "make", "not", "but",
            "can", "will", "would", "should", "could", "have", "has", "had",
            "do", "does", "did", "been", "being", "are", "was", "were"
        )
    }
}
