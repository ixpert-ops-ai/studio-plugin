package net.ib.ixpert.ops.wuwagent.agent.analyze.search

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType

class SpecEnricher(private val frameworkType: FrameworkType) {

    data class EnrichedContext(
        val originalRequirement: String,
        val discoveredPatterns: String,
        val referenceFiles: List<String>,
        val llmDirectives: String,
        val confidence: Confidence
    )

    enum class Confidence { HIGH, MEDIUM, LOW, NONE }

    fun enrich(
        requirement: String,
        patterns: List<PatternExtractor.ImplementationPattern>,
        hits: List<MultiStrategySearch.SearchHit>
    ): EnrichedContext {
        val confidence = when {
            patterns.isNotEmpty() && hits.any { it.score >= 150.0 } -> Confidence.HIGH
            patterns.isNotEmpty() -> Confidence.MEDIUM
            hits.isNotEmpty() -> Confidence.LOW
            else -> Confidence.NONE
        }

        val patternDesc = if (patterns.isNotEmpty()) {
            buildPatternDescription(patterns)
        } else {
            "유사 구현을 찾지 못했습니다."
        }

        val directives = buildDirectives(patterns, confidence)
        val refFiles = patterns.flatMap { p -> p.files.values.flatten().map { it.path } }.distinct()

        return EnrichedContext(requirement, patternDesc, refFiles, directives, confidence)
    }

    private fun buildPatternDescription(patterns: List<PatternExtractor.ImplementationPattern>): String {
        return buildString {
            patterns.forEachIndexed { idx, p ->
                appendLine("### 참조 패턴 ${idx + 1}: ${p.name}")
                appendLine("레이어: ${p.layerChain.joinToString(" -> ")}")
                p.files.forEach { (layer, files) ->
                    appendLine("  $layer: ${files.joinToString { it.className }}")
                }
                if (p.utilities.isNotEmpty()) {
                    appendLine("유틸: ${p.utilities.joinToString(", ")}")
                }
                appendLine()
                p.codeSnippets.filter { it.value.isNotBlank() }.forEach { (layer, code) ->
                    appendLine("```java // $layer")
                    appendLine(code)
                    appendLine("```")
                }
                appendLine()
            }
        }
    }

    private fun buildDirectives(patterns: List<PatternExtractor.ImplementationPattern>, confidence: Confidence): String {
        return buildString {
            appendLine("## 구현 규칙")
            appendLine()

            // 프레임워크 기본 규칙
            appendLine(getFrameworkBaseRules())
            appendLine()

            // 패턴 기반 추가 규칙
            if (confidence in listOf(Confidence.HIGH, Confidence.MEDIUM) && patterns.isNotEmpty()) {
                val primary = patterns.first()
                appendLine("## 참조 패턴 기반 규칙 (기존 구현 컨벤션을 반드시 따르세요)")
                appendLine("- 레이어 구조: ${primary.layerChain.joinToString(" -> ")}")
                if (primary.utilities.isNotEmpty()) {
                    appendLine("- 기존 유틸 재사용: ${primary.utilities.joinToString(", ")}")
                }
                appendLine("- 네이밍은 기존 파일과 동일한 컨벤션 유지")
                appendLine("- 참조 메서드 시그니처:")
                primary.keyMethodSignatures.take(5).forEach { sig ->
                    appendLine("  $sig")
                }
            }
        }
    }

    private fun getFrameworkBaseRules(): String {
        return when (frameworkType) {
            FrameworkType.ANYFRAME_AP, FrameworkType.ANYFRAME_JAP -> """
                - Anyframe Enterprise 규칙 적용
                - Value Object 분리: SVO(서비스), BVO(비즈니스), DVO(데이터)
                - Service 인터페이스 + SVCImpl 구현체 분리 필수
                - BIZ 클래스에 비즈니스 로직 배치
                - DQM/DEM에 데이터 접근 로직 배치
                - @Resource로 의존성 주입
            """.trimIndent()

            FrameworkType.SPRING_BOOT_JPA -> """
                - Spring Boot + JPA 규칙 적용
                - @Entity로 도메인 모델 정의
                - JpaRepository 상속으로 Repository 생성
                - @Service, @RestController 어노테이션 사용
                - 생성자 주입 방식 권장
                - Entity를 직접 반환하지 말고 DTO로 변환하여 응답
            """.trimIndent()

            FrameworkType.SPRING_BOOT_MYBATIS -> """
                - Spring Boot + MyBatis 규칙 적용
                - @Mapper 인터페이스로 데이터 접근
                - XML 매퍼 파일에 SQL 작성
                - @Service, @Controller 어노테이션 사용
                - 생성자 주입 방식 권장
            """.trimIndent()

            FrameworkType.SPRING_BOOT_JDBC -> """
                - Spring Boot + JDBC 규칙 적용
                - JdbcTemplate을 사용하여 데이터 접근
                - RowMapper로 결과 매핑
                - @Service, @Controller, @Repository 사용
                - 생성자 주입 방식 권장
            """.trimIndent()

            FrameworkType.SPRING_MVC_MYBATIS -> """
                - Spring MVC + MyBatis 규칙 적용
                - Service 인터페이스 + ServiceImpl 구현체 분리 필수
                - Dao 클래스에서 SqlSession 또는 SqlSessionDaoSupport 사용
                - @Resource 또는 setter를 통한 의존성 주입
                - XML 매퍼 파일에 SQL 작성
                - ModelAndView 또는 String으로 뷰 반환
            """.trimIndent()

            FrameworkType.ANDROID -> """
                - Android 규칙 적용
                - Activity/Fragment로 UI 구성, ViewModel로 상태 관리
                - Composable 함수로 Jetpack Compose UI 작성
                - Repository 패턴으로 데이터 접근
                - Hilt/Koin 등으로 의존성 주입
            """.trimIndent()

            FrameworkType.CUSTOM, FrameworkType.SPRING_BOOT, FrameworkType.ANYFRAME -> """
                - 프레임워크 특정 규칙 없음
                - 프로젝트 기존 코드의 구조와 네이밍 컨벤션을 따를 것
            """.trimIndent()
        }
    }
}
