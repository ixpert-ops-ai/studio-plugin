package net.ib.ixpert.ops.wuwagent.service.metagraph.model

enum class ResourceType {
    MYBATIS_MAPPER,   // .xml (MyBatis namespace 보유)
    SCRIPT,           // .js, .ts (API 호출 포함)
    VIEW,             // .jsp, .html, .vue, .tsx
    CONFIG,           // .properties, .yml, .yaml
    SQL_MIGRATION,    // .sql (Flyway/Liquibase)
    SPRING_XML,       // applicationContext.xml 등
    MYBATIS_CONFIG,   // MyBatis 설정 XML
    UNKNOWN           // 미분류 (무시 대상)
}

data class ResourceNode(
    val path: String,                    // 상대 경로
    val type: ResourceType,              // MYBATIS_MAPPER, SCRIPT, VIEW, CONFIG 등
    val layer: String,                   // DATA_ACCESS, PRESENTATION, INFRASTRUCTURE 등
    val linkedTo: List<String>,          // 연결된 Java 노드 경로들
    val linkType: String,                // namespace_binding, url_binding, script_include 등
    val metadata: Map<String, Any>,      // 유형별 추가 정보 (유연한 구조)
    // P5-B: 동적 뷰 역추적 결과
    val dynamicBindings: List<DynamicBinding> = emptyList()
)
