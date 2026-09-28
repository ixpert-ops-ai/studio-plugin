package net.ib.ixpert.ops.wuwagent.agent.clarify

import com.intellij.openapi.diagnostic.Logger

/**
 * 사용자 입력 및 추가 발화에서 식별자(클래스명, 파일명, 메서드명, 전문명 등)를 추출하고
 * 정제문(refinedRequirement)에 100% 보존되었는지 검사 및 보정하는 결정론적 안전판.
 */
object IdentifierRetentionChecker {
    private val logger = Logger.getInstance(IdentifierRetentionChecker::class.java)

    // 일반 표준 단어 중 대문자 시작이지만 식별자가 아닌 불용어/일반약어
    internal val EXCLUDED_COMMON_WORDS = setOf(
        "Boolean", "String", "Integer", "Long", "Double", "Float", "List", "Map", "Set", "Class", "Object",
        "True", "False", "Null", "API", "DB", "DTO", "VO", "URL", "URI", "JSON", "XML", "HTTP", "HTTPS", "SQL", "SR"
    )

    data class RetentionResult(
        val effectiveRefinedRequirement: String,
        val rawRefinedRequirement: String,
        val expectedIdentifiers: Set<String>,
        val missingIdentifiers: List<String>,
        val wasRetainedWithoutModification: Boolean
    )

    /**
     * 원문 텍스트들로부터 고유 식별자(클래스명, 파일명, 메서드명, 전문명 등)를 결정론적으로 추출.
     * 한글 조사('에', '를', '과' 등) 결합 시에도 JDK 버전에 독립적으로 매칭되도록 (?<![A-Za-z0-9_]) 및 (?![A-Za-z0-9_]) 경계 사용.
     */
    fun extractIdentifiers(texts: List<String>): Set<String> {
        val identifiers = mutableSetOf<String>()
        val combined = texts.joinToString(" ")

        // 1. 백틱으로 감싸진 토큰 (`...`)
        val backtickRegex = Regex("""`([^`]+)`""")
        backtickRegex.findAll(combined).forEach { match ->
            val token = match.groupValues[1].trim()
            if (token.isNotBlank() && token.length >= 2) {
                identifiers.add(token)
            }
        }

        // 2. 확장자가 포함된 파일명 패턴 (예: SurveyServiceImpl.java, sql_survey.xml, order.jsp 등)
        val fileRegex = Regex("""(?<![A-Za-z0-9_])[a-zA-Z0-9_.-]+\.(java|kt|xml|jsp|js|sql|html|css|json)(?![A-Za-z0-9_])""", RegexOption.IGNORE_CASE)
        fileRegex.findAll(combined).forEach { match ->
            identifiers.add(match.value)
        }

        // 3. UpperCamelCase 클래스형 패턴 (대문자 시작 + 대문자 2개 이상 + 소문자 혼합, 예: SurveyServiceImpl, OrderDto, APCMMTrcdIsSVC)
        val upperCamelRegex = Regex("""(?<![A-Za-z0-9_])[A-Z][A-Za-z0-9_]*(?![A-Za-z0-9_])""")
        upperCamelRegex.findAll(combined).forEach { match ->
            val word = match.value
            val upperCount = word.count { it.isUpperCase() }
            val hasLower = word.any { it.isLowerCase() }
            if (word !in EXCLUDED_COMMON_WORDS && word.length >= 3 && upperCount >= 2 && hasLower) {
                identifiers.add(word)
            }
        }

        // 4. lowerCamelCase 메서드/필드명 패턴 (소문자+대문자 혼합, 예: selTrcdIsInf, getPayType, selectSurveyM)
        val lowerCamelRegex = Regex("""(?<![A-Za-z0-9_])[a-z][a-z0-9]+[A-Z][A-Za-z0-9_]*(?![A-Za-z0-9_])""")
        lowerCamelRegex.findAll(combined).forEach { match ->
            val word = match.value
            if (word !in EXCLUDED_COMMON_WORDS && word.length >= 3) {
                identifiers.add(word)
            }
        }

        // 5. 영숫자 혼합 식별자 패턴 (숫자와 영문자를 모두 포함하는 4글자 이상의 코드, 예: aCMBTBAPC024DEM, SAPACMM0802S01, ECMBTBISM006)
        val codeRegex = Regex("""(?<![A-Za-z0-9_])[a-zA-Z0-9_]{4,}(?![A-Za-z0-9_])""")
        codeRegex.findAll(combined).forEach { match ->
            val word = match.value
            if (word !in EXCLUDED_COMMON_WORDS && word.any { it.isDigit() } && word.any { it in 'a'..'z' || it in 'A'..'Z' }) {
                identifiers.add(word)
            }
        }

        // 중복 포섭 정리: 파일명(Foo.java)이 존재하는 경우 동일한 기본명 단독(Foo)은 파일명으로 통합
        val fileBaseNames = identifiers.filter { it.contains(".") }.map { it.substringBeforeLast(".") }.toSet()
        val deduplicated = identifiers.filter { id ->
            id.contains(".") || id !in fileBaseNames
        }.toSet()

        return deduplicated
    }

    /**
     * 사용자 발화의 영문 토큰 중 프로젝트 그래프의 클래스명 구성 단어(camelCase 분절)와 정확히 일치하는 보조 식별자 추출.
     * (정제문에 강제 병기하지 않고 audit 기록/모니터링용으로만 사용)
     * - 'Bizgo' -> 'BizgoApiServiceImpl' (일치: O)
     * - 'add' -> 'AddressDao' (일치: X, 단어 단위 일치이므로 부분 문자열 오탐 방지)
     * - 'API' -> 불용어 목록에 의해 사전 제외
     */
    fun extractAuxiliaryIdentifiers(
        texts: List<String>,
        classNames: Collection<String>,
        knownExpected: Set<String> = emptySet()
    ): List<String> {
        val combined = texts.joinToString(" ")
        val wordTokens = Regex("""(?<![A-Za-z0-9_])[A-Za-z0-9_]{3,}(?![A-Za-z0-9_])""").findAll(combined)
            .map { it.value }
            .filter { word ->
                word !in knownExpected &&
                word !in EXCLUDED_COMMON_WORDS &&
                word.any { it in 'a'..'z' || it in 'A'..'Z' }
            }
            .toList()

        if (wordTokens.isEmpty() || classNames.isEmpty()) return emptyList()

        val excludedLower = EXCLUDED_COMMON_WORDS.map { it.lowercase() }.toSet()
        val classWordSegments = classNames.flatMap { className ->
            className.split(Regex("(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|_|-|\\."))
                .map { it.lowercase() }
                .filter { it.length >= 3 && it !in excludedLower }
        }.toSet()

        return wordTokens.filter { word ->
            classWordSegments.contains(word.lowercase())
        }.distinct().sorted()
    }

    /**
     * 특정 식별자가 정제문에 온전히 존재하는지 경계(Boundary)를 인식하여 판정.
     * (부분 문자열 오판 방지: 'SurveyService' 검사 시 'SurveyServiceImpl'만 존재하는 경우는 미포함으로 판정)
     */
    fun containsIdentifier(text: String, identifier: String): Boolean {
        val baseName = identifier.substringBeforeLast(".")
        val exactRegex = Regex("""(?<![A-Za-z0-9_])""" + Regex.escape(identifier) + """(?![A-Za-z0-9_])""", RegexOption.IGNORE_CASE)
        val baseRegex = Regex("""(?<![A-Za-z0-9_])""" + Regex.escape(baseName) + """(?![A-Za-z0-9_])""", RegexOption.IGNORE_CASE)
        
        return exactRegex.containsMatchIn(text) || (identifier.contains(".") && baseRegex.containsMatchIn(text))
    }

    /**
     * 정제문(refinedRequirement)의 식별자 보존을 검사하고 상세 결과 객체를 반환합니다.
     */
    fun checkRetention(
        refinedRequirement: String,
        originalRequirement: String,
        userStatements: List<String>
    ): RetentionResult {
        val expectedIdentifiers = extractIdentifiers(listOf(originalRequirement) + userStatements)
        if (expectedIdentifiers.isEmpty()) {
            return RetentionResult(
                effectiveRefinedRequirement = refinedRequirement,
                rawRefinedRequirement = refinedRequirement,
                expectedIdentifiers = emptySet(),
                missingIdentifiers = emptyList(),
                wasRetainedWithoutModification = true
            )
        }

        val missing = expectedIdentifiers.filter { id ->
            !containsIdentifier(refinedRequirement, id)
        }.sorted()

        if (missing.isNotEmpty()) {
            logger.warn("IdentifierRetentionChecker: 식별자 누락 감지 (${missing.size}건: $missing). 정제문에 안전판 병기 수행.")
            val effective = buildString {
                append(refinedRequirement.trimEnd('.'))
                append(" (명시된 식별자: ")
                append(missing.joinToString(", "))
                append(")")
            }
            return RetentionResult(
                effectiveRefinedRequirement = effective,
                rawRefinedRequirement = refinedRequirement,
                expectedIdentifiers = expectedIdentifiers,
                missingIdentifiers = missing,
                wasRetainedWithoutModification = false
            )
        }

        return RetentionResult(
            effectiveRefinedRequirement = refinedRequirement,
            rawRefinedRequirement = refinedRequirement,
            expectedIdentifiers = expectedIdentifiers,
            missingIdentifiers = emptyList(),
            wasRetainedWithoutModification = true
        )
    }

    /**
     * 기존 호환 메서드
     */
    fun verifyAndRetain(
        refinedRequirement: String,
        originalRequirement: String,
        userStatements: List<String>
    ): String {
        return checkRetention(refinedRequirement, originalRequirement, userStatements).effectiveRefinedRequirement
    }
}

