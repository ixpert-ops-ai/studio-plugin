package net.ib.ixpert.ops.wuwagent.service.metagraph.analyzer

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*

/**
 * 프로젝트 그래프를 기반으로 사용 중인 프레임워크를 자동 감지하는 클래스입니다.
 */
object FrameworkDetector {

    /**
     * 메타그래프 노드들을 분석하여 프레임워크 타입을 추론합니다.
     */
    fun detectFramework(files: Map<String, FileNode>): FrameworkDetectionResult {
        // 규칙 1: Anyframe AP
        val hasSVCImpl = files.values.any { it.className.contains("SVCImpl") }
        val hasBIZ = files.values.any { it.className.contains("BIZ") && it.layer == ArchitectureLayer.BUSINESS }
        val hasDQM_DEM = files.values.any { it.className.contains("DQM") || it.className.contains("DEM") }

        if (hasSVCImpl && hasBIZ && hasDQM_DEM) {
            return FrameworkDetectionResult(
                detected = FrameworkType.ANYFRAME_AP,
                confidence = 95,
                reasons = listOf("SVCImpl, BIZ, DEM/DQM 패턴이 모두 발견되었습니다."),
                alternativeCandidates = listOf(Pair(FrameworkType.SPRING_BOOT_JPA, 5))
            )
        }

        // 규칙 2: Spring Boot + JPA
        val entityCount = files.values.count { it.annotations.any { ann -> ann == "Entity" || ann == "@Entity" } }
        val jpaRepoCount = files.values.count { it.superClass?.contains("JpaRepository") == true || it.implementedInterfaces.any { i -> i.contains("JpaRepository") } }
        
        if (entityCount > 0 || jpaRepoCount > 0) {
            return FrameworkDetectionResult(
                detected = FrameworkType.SPRING_BOOT_JPA,
                confidence = 95,
                reasons = listOf("@Entity 어노테이션이 ${entityCount}개 발견되었습니다.", "JpaRepository 상속이 ${jpaRepoCount}개 발견되었습니다."),
                alternativeCandidates = listOf(Pair(FrameworkType.SPRING_BOOT_MYBATIS, 5))
            )
        }

        // 규칙 3: Spring Boot + MyBatis
        val mapperCount = files.values.count { it.annotations.any { ann -> ann == "Mapper" || ann == "@Mapper" } }
        val hasSqlSession = files.values.any { it.superClass?.contains("SqlSessionDaoSupport") == true || it.injections.any { inj -> inj.targetType.contains("SqlSession") } }
        
        if (mapperCount > 0 && !hasSqlSession) {
            return FrameworkDetectionResult(
                detected = FrameworkType.SPRING_BOOT_MYBATIS,
                confidence = 85,
                reasons = listOf("@Mapper 어노테이션이 ${mapperCount}개 발견되었고 SqlSessionDaoSupport가 없습니다."),
                alternativeCandidates = listOf(Pair(FrameworkType.SPRING_MVC_MYBATIS, 15))
            )
        }

        // 규칙 4: Spring Boot + JDBC
        val hasJdbcTemplate = files.values.any { it.injections.any { inj -> inj.targetType.contains("JdbcTemplate") } }
        if (hasJdbcTemplate && entityCount == 0 && mapperCount == 0) {
            return FrameworkDetectionResult(
                detected = FrameworkType.SPRING_BOOT_JDBC,
                confidence = 85,
                reasons = listOf("JdbcTemplate 주입이 발견되었으나 JPA Entity나 MyBatis Mapper는 없습니다."),
                alternativeCandidates = listOf(Pair(FrameworkType.SPRING_BOOT_JPA, 10))
            )
        }

        // 규칙 5: Spring MVC + MyBatis
        if (hasSqlSession) {
            val hasInterfaceImplPairs = checkInterfaceImplPairs(files)
            if (hasInterfaceImplPairs) {
                return FrameworkDetectionResult(
                    detected = FrameworkType.SPRING_MVC_MYBATIS,
                    confidence = 90,
                    reasons = listOf("SqlSessionDaoSupport 상속이 발견되었으며, Service/ServiceImpl 패턴이 주를 이룹니다."),
                    alternativeCandidates = listOf(Pair(FrameworkType.SPRING_BOOT_MYBATIS, 10))
                )
            }
        }

        // Fallback
        return FrameworkDetectionResult(
            detected = FrameworkType.CUSTOM,
            confidence = 30,
            reasons = listOf("알려진 프레임워크 패턴(JPA, MyBatis, Anyframe 등)을 명확하게 찾지 못했습니다."),
            alternativeCandidates = emptyList()
        )
    }

    private fun checkInterfaceImplPairs(files: Map<String, FileNode>): Boolean {
        // 인터페이스를 상속받은 구현체(Impl) 패턴이 존재하는지 확인
        val interfaces = files.values.filter { it.isInterface }.map { it.className }
        val impls = files.values.filter { !it.isInterface && it.className.endsWith("Impl") }
        
        var matchCount = 0
        for (impl in impls) {
            val expectedInterface = impl.className.removeSuffix("Impl")
            if (interfaces.contains(expectedInterface)) {
                matchCount++
            }
        }
        
        return matchCount > 0
    }
}
