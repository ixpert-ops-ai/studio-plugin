package net.ib.ixpert.ops.wuwagent.service.metagraph.model

enum class DaoPattern {
    JPA_REPOSITORY, MAPPER_INTERFACE, JDBC_TEMPLATE, SQL_SESSION, ANYFRAME_DEM_DQM, UNKNOWN
}

enum class VoStrategy {
    ENTITY_RESPONSE_DTO, PLAIN_POJO_DTO, LAYERED_SVO_BVO_DVO, NONE
}

enum class FrameworkType(
    val displayName: String,
    val hasInterfaceImplPair: Boolean,
    val controllerCanSkip: Boolean,
    val usesLombok: Boolean,
    val requiresJpaAnnotation: Boolean,
    val daoPattern: DaoPattern,
    val voStrategy: VoStrategy
) {
    SPRING_BOOT_JPA(
        "Spring Boot + JPA", false, true, true, true, 
        DaoPattern.JPA_REPOSITORY, VoStrategy.ENTITY_RESPONSE_DTO
    ),
    SPRING_BOOT_MYBATIS(
        "Spring Boot + MyBatis", false, true, true, false, 
        DaoPattern.MAPPER_INTERFACE, VoStrategy.PLAIN_POJO_DTO
    ),
    SPRING_BOOT_JDBC(
        "Spring Boot + JDBC", false, true, true, false, 
        DaoPattern.JDBC_TEMPLATE, VoStrategy.PLAIN_POJO_DTO
    ),
    SPRING_MVC_MYBATIS(
        "Spring MVC + MyBatis", true, true, true, false, 
        DaoPattern.SQL_SESSION, VoStrategy.PLAIN_POJO_DTO
    ),
    ANYFRAME_AP(
        "Anyframe Enterprise", true, false, false, false,
        DaoPattern.ANYFRAME_DEM_DQM, VoStrategy.LAYERED_SVO_BVO_DVO
    ),
    ANDROID(
        "Android", false, true, false, false,
        DaoPattern.UNKNOWN, VoStrategy.NONE
    ),
    CUSTOM(
        "기타 (Custom)", false, true, true, false, 
        DaoPattern.UNKNOWN, VoStrategy.NONE
    ),
    @Deprecated("Legacy type. Will be auto-migrated.")
    SPRING_BOOT(
        "Spring Boot (Legacy)", false, true, true, true, 
        DaoPattern.UNKNOWN, VoStrategy.NONE
    ),
    @Deprecated("Legacy type. Will be auto-migrated.")
    ANYFRAME(
        "Anyframe (Legacy)", true, false, false, false, 
        DaoPattern.ANYFRAME_DEM_DQM, VoStrategy.LAYERED_SVO_BVO_DVO
    ),
    @Deprecated("Unknown extractor output. Will be aliased to ANYFRAME_AP.")
    ANYFRAME_JAP(
        "Anyframe JAP (Alias)", true, false, false, false, 
        DaoPattern.ANYFRAME_DEM_DQM, VoStrategy.LAYERED_SVO_BVO_DVO
    );

    companion object {
        // 기존값 마이그레이션
        fun migrateOldFrameworkType(old: String?): FrameworkType? = when(old) {
            "SPRING_BOOT" -> null  // 자동감지로 재판정
            "ANYFRAME", "ANYFRAME_JAP" -> ANYFRAME_AP
            else -> null
        }
    }
}
