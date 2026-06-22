package net.ib.ixpert.ops.wuwagent.service.metagraph.model

enum class GraphType {
    SINGLE,
    MULTI_LEVEL_1,
    MULTI_LEVEL_2
}

/**
 * 프레임워크 자동 감지 결과.
 */
data class FrameworkDetectionResult(
    val detected: FrameworkType,
    val confidence: Int,          // 0~100
    val reasons: List<String>,    // 판단 근거
    val alternativeCandidates: List<Pair<FrameworkType, Int>> = emptyList(),
    var userOverride: FrameworkType? = null
)

interface ProjectGraphQueryable {
    val projectRoot: String?
    val files: Map<String, FileNode>
    val resourceNodes: List<ResourceNode>
    
    val totalFileCount: Int
        get() = files.size
        
    fun getAllClassNames(): List<String> = files.values.map { it.className }
}

/**
 * 프로젝트 전체 구조 그래프.
 * 메타파일(.meta/project-graph.json)의 최상위 구조입니다.
 */
data class ProjectGraph(
    val version: String = "1.0",
    val graphType: GraphType = GraphType.SINGLE,
    val generatedAt: String,
    override val projectRoot: String,
    val framework: String = "spring-boot",
    val frameworkType: FrameworkType = FrameworkType.SPRING_BOOT_JPA,
    val frameworkDetection: FrameworkDetectionResult? = null,
    val modules: List<ModuleInfo>? = null,
    override val files: Map<String, FileNode>,
    override val resourceNodes: List<ResourceNode> = emptyList(),
    val relationships: List<Relationship>,
    val statistics: GraphStatistics
) : ProjectGraphQueryable {
    fun resolveFrameworkType(): FrameworkType {
        return this.frameworkDetection?.userOverride ?: this.frameworkType
    }

    @Transient
    private var _documentFrequency: Map<String, Int>? = null

    val documentFrequency: Map<String, Int>
        get() {
            if (_documentFrequency != null) return _documentFrequency!!

            val dfMap = mutableMapOf<String, Int>()
            
            fun tokenize(identifier: String): List<String> {
                if (identifier.isBlank()) return emptyList()
                if (identifier.contains('_')) {
                    return identifier.split('_').filter { it.length >= 2 }.map { it.lowercase() }
                }
                val regex = Regex("(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")
                return regex.split(identifier).filter { it.length >= 2 }.map { it.lowercase() }
            }

            for (rNode in resourceNodes) {
                val tokens = mutableSetOf<String>()
                tokens.addAll(rNode.path.split(Regex("[^a-zA-Z0-9]+")).flatMap { tokenize(it) })
                rNode.metadata.values.forEach { value ->
                    if (value is List<*>) {
                        value.forEach { v -> tokens.addAll(v.toString().split(Regex("[^a-zA-Z0-9]+")).flatMap { tokenize(it) }) }
                    } else {
                        tokens.addAll(value.toString().split(Regex("[^a-zA-Z0-9]+")).flatMap { tokenize(it) })
                    }
                }
                tokens.forEach { dfMap[it] = dfMap.getOrDefault(it, 0) + 1 }
            }
            
            for (fNode in files.values) {
                val tokens = mutableSetOf<String>()
                tokens.addAll(tokenize(fNode.className))
                fNode.packageName?.let { tokens.addAll(it.split(".")) }
                fNode.methodNames.forEach { tokens.addAll(tokenize(it)) }
                tokens.forEach { dfMap[it] = dfMap.getOrDefault(it, 0) + 1 }
            }
            
            _documentFrequency = dfMap
            return dfMap
        }
}

/**
 * 개별 파일(클래스) 노드.
 */
data class FileNode(
    val path: String,
    val packageName: String?,
    val className: String,
    val fileType: SpringFileType,
    val layer: ArchitectureLayer,
    val isInterface: Boolean = false,
    val isAbstract: Boolean = false,
    val annotations: List<String> = emptyList(),
    val superClass: String? = null,
    val implementedInterfaces: List<String> = emptyList(),
    val injections: List<DependencyInjection> = emptyList(),
    // Phase 1c 보강 분석기용 데이터
    val apiEndpoints: List<ApiEndpoint> = emptyList(),
    val beanDefinitions: List<BeanDefinition> = emptyList(),
    val entityRelations: List<EntityRelation> = emptyList(),
    val dependsOn: MutableList<String> = mutableListOf(),
    val dependedBy: MutableList<String> = mutableListOf(),
    var riskAssessment: RiskAssessment = RiskAssessment(0, ChangeRisk.NOT_CALCULATED, emptyList()),
    // Anyframe 지원 필드 추가
    val anyframeRole: AnyframeRole? = null,
    val demMethods: List<DemMethodInfo>? = null,
    val serviceEndpoints: List<ServiceEndpoint>? = null,
    val localName: String? = null,
    val datasource: String? = null,
    // Adaptive File Discovery 지원 필드
    val koreanComments: List<String> = emptyList(),
    val methodNames: List<String> = emptyList(),
    // P5-B: 동적 뷰 역추적 필드
    val isDynamicRouter: Boolean = false,
    val dynamicViewFolders: List<String> = emptyList()
)

/**
 * DI 주입 정보.
 */
data class DependencyInjection(
    val targetType: String,           // FQN (내부 처리용)
    val fieldName: String,
    val method: InjectionMethod,
    val resolvedImpl: String? = null  // FQN — DependencyResolver가 채움
)

/**
 * 파일 간 관계.
 */
data class Relationship(
    val source: String,
    val target: String,
    val type: RelationshipType,
    val strength: RelationshipStrength = RelationshipStrength.DIRECT,
    val detail: String? = null,
    val callType: String? = null, // STATIC or INSTANCE
    val metadata: Map<String, String>? = null
)

/**
 * P5-B: 동적 뷰 라우팅(Front Controller) 바인딩 정보.
 */
data class DynamicBinding(
    val resourcePath: String,
    val controllerPath: String,
    val matchedUrl: String,
    val confidence: Double,
    val bindingType: String = "DYNAMIC_VIEW_RESOLVE"
)

/**
 * 멀티모듈 정보.
 */
data class ModuleInfo(
    val name: String,
    val rootPath: String,
    val metadataPath: String,
    val dependsOnModules: List<String> = emptyList(),
    val lastIndexedAt: Long? = null,
    val fileCount: Int? = null,
    val publicApis: List<String>? = null
)

/**
 * 그래프 통계.
 */
data class GraphStatistics(
    val totalFiles: Int = 0,
    val controllers: Int = 0,
    val services: Int = 0,
    val repositories: Int = 0,
    val entities: Int = 0,
    val configs: Int = 0,
    val dtos: Int = 0,
    val utils: Int = 0,
    val views: Int = 0,
    val scripts: Int = 0,
    val components: Int = 0,
    val others: Int = 0,
    val totalRelationships: Int = 0
)

// ── Phase 1b 보강용 모델 ──────────────────────────

data class ApiEndpoint(
    val httpMethod: String,
    val path: String,
    val controllerClass: String = "",
    val handlerMethod: String,
    val params: List<String> = emptyList(),
    val returnType: String? = null,
    val relatedServiceMethod: String = "",
    val returnedViewNames: List<String> = emptyList()
)

data class BeanDefinition(
    val beanName: String,
    val returnType: String,
    val dependencies: List<String> = emptyList()
)

data class EntityRelation(
    val type: String,            // "OneToMany", "ManyToOne" 등
    val targetEntity: String,
    val fieldName: String
)

// ── Enum 정의 ─────────────────────────────────────

enum class SpringFileType(val displayName: String) {
    REST_CONTROLLER("RestController"),
    CONTROLLER("Controller"),
    SERVICE("Service"),
    REPOSITORY("Repository"),
    ENTITY("Entity"),
    DTO("DTO"),
    VO("VO"),
    CONFIG("Configuration"),
    COMPONENT("Component"),
    MAPPER("Mapper"),
    INTERFACE("Interface"),
    ABSTRACT_CLASS("AbstractClass"),
    ENUM("Enum"),
    FILTER("Filter"),
    INTERCEPTOR("Interceptor"),
    EXCEPTION_HANDLER("ExceptionHandler"),
    EXCEPTION("Exception"),
    UTIL("Util"),
    VIEW("View"),
    TEST("Test"),
    UNKNOWN("Unknown"),
    
    // Anyframe 지원 추가
    BIZ("Business"),
    SERVICE_INTERFACE("ServiceInterface"),
    DATA_ACCESS("DataAccess"),
    BIZ_UTIL("BizUtil")
}

enum class ArchitectureLayer(val displayName: String) {
    PRESENTATION("Presentation"),
    BUSINESS("Business"),
    PERSISTENCE("Persistence"),
    COMMON("Common"),
    TEST("Test"),
    MODEL("Model"),
    VIEW("View"),
    UNKNOWN("Unknown"),
    
    // 추가 레이어
    DATA("Data"),
    SERVICE("Service")
}

enum class InjectionMethod {
    CONSTRUCTOR, FIELD, SETTER
}

enum class RelationshipType {
    INJECTS,
    EXTENDS,
    IMPLEMENTS,
    CONFIGURES,
    ENTITY_RELATION,
    CALLS,
    
    // Anyframe 관계 타입 추가
    EXPOSES_SERVICE,
    CALLS_BIZ,
    CALLS_DEM_METHOD,
    MAPS_TO_TABLE,
    USES_DVO,
    TRANSFORMS_VO
}

enum class RelationshipStrength {
    DIRECT, INDIRECT, EVENT_DRIVEN
}

enum class ChangeRisk {
    NOT_CALCULATED, LOW, MEDIUM, HIGH, CRITICAL
}

data class RiskAssessment(
    val riskScore: Int,
    val changeRisk: ChangeRisk,
    val riskReasons: List<String>
)

// ── TypeResolver 유틸 ─────────────────────────────

/**
 * 제네릭 래핑 해제 및 타입명 변환 유틸리티.
 * SpringAnnotationResolver와 DependencyResolver 양쪽에서 공통 사용합니다.
 */
object TypeResolver {

    private val COLLECTION_TYPES = setOf(
        "List", "Set", "Collection", "Iterable",
        "java.util.List", "java.util.Set", "java.util.Collection", "java.util.Iterable"
    )

    private val WRAPPER_TYPES = setOf(
        "Optional", "java.util.Optional",
        "Provider", "javax.inject.Provider",
        "ObjectProvider", "org.springframework.beans.factory.ObjectProvider",
        "Lazy", "org.springframework.context.annotation.Lazy"
    )

    /**
     * 제네릭 래핑을 해제하여 실제 타입을 반환합니다.
     * 예: "List<OrderValidator>" → "OrderValidator"
     *     "Optional<CacheService>" → "CacheService"
     *     "Map<String, PaymentStrategy>" → "PaymentStrategy" (value 타입)
     *     "UserService" → "UserService" (변화 없음)
     */
    fun unwrapGenericType(type: String): String {
        val trimmed = type.trim()

        // 제네릭이 아닌 경우 그대로 반환
        val genericStart = trimmed.indexOf('<')
        if (genericStart < 0) return trimmed

        val outerType = trimmed.substring(0, genericStart).trim()
        val innerContent = trimmed.substring(genericStart + 1, trimmed.lastIndexOf('>')).trim()

        // Collection/Wrapper 타입이면 내부 타입 반환
        if (COLLECTION_TYPES.any { outerType.endsWith(it) } ||
            WRAPPER_TYPES.any { outerType.endsWith(it) }) {
            // Map<K, V>인 경우 Value 타입 반환
            if (outerType.endsWith("Map") || outerType.endsWith("java.util.Map")) {
                val parts = splitTopLevelComma(innerContent)
                return if (parts.size >= 2) unwrapGenericType(parts[1].trim()) else innerContent
            }
            return unwrapGenericType(innerContent)
        }

        // 그 외 제네릭 (예: ResponseEntity<UserDto>)은 outer 타입 반환
        return outerType
    }

    /**
     * 컬렉션 타입인지 확인합니다.
     */
    fun isCollectionType(type: String): Boolean {
        val outerType = type.substringBefore('<').trim()
        return COLLECTION_TYPES.any { outerType.endsWith(it) }
    }

    /**
     * FQN을 simple name으로 변환합니다.
     */
    fun toSimpleName(fqn: String): String = fqn.substringAfterLast(".")

    /**
     * 제네릭의 최상위 콤마로 분리 (중첩 제네릭 고려).
     */
    private fun splitTopLevelComma(content: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var start = 0
        for (i in content.indices) {
            when (content[i]) {
                '<' -> depth++
                '>' -> depth--
                ',' -> if (depth == 0) {
                    parts.add(content.substring(start, i))
                    start = i + 1
                }
            }
        }
        parts.add(content.substring(start))
        return parts
    }
}

// ── Impact Graph 모델 ─────────────────────────────

/**
 * 변경 영향 분석 결과.
 */
data class ImpactResult(
    val targetFile: String,
    val directImpact: List<ImpactNode>,      // depth 1
    val indirectImpact: List<ImpactNode>,    // depth 2-3
    val totalAffectedFiles: Int,
    val maxRiskInChain: RiskAssessment
)

/**
 * 영향 전파 노드.
 */
data class ImpactNode(
    val filePath: String,
    val className: String,
    val depth: Int,
    val relationChain: List<String>,         // 예: ["CALLS", "INJECTS"]
    val riskAssessment: RiskAssessment
)

// ── Anyframe 전용 모델 ──────────────────────────────

enum class AnyframeRole {
    SVC, SVC_IMPL, BIZ, BIZ_UTIL, DEM, DQM, SVO, BVO, DVO, UNKNOWN
}

data class ServiceEndpoint(
    val serviceId: String,             // 예: "SAPCMM0204S01"
    val methodName: String,            // 예: "selPsnzInf"
    val localName: String?,            // 예: "개인화정보조회"
    val inputSvo: String?,             // 예: "APCMMPsnzInfSVO"
    val outputSvo: String?             // 예: "APCMMPsnzInfSVO"
)

data class DemMethodInfo(
    val methodName: String,            // 예: "selSpacdMbCt"
    val sqlId: String?,                // 주석 내 /*SQL_ID: ... */ 추출값
    val inputDvoClass: String?,        // 파라미터 DVO 클래스명
    val returnDvoClass: String?,       // 리턴 DVO 클래스명
    val tables: List<String>,          // DEM: 1개, DQM: 복수 조인 테이블 목록
    val operationType: SqlOpType,      // SELECT, INSERT, UPDATE, DELETE
    val localName: String?             // 메서드 상단 @LocalName의 한글 설명
)

enum class SqlOpType { SELECT, INSERT, UPDATE, DELETE, UNKNOWN }

data class VoTransformation(
    val sourceVo: String,           // 예: "APCMMPsnzInfSVO"
    val targetVo: String,           // 예: "APCMMPsnzInfBVO"
    val direction: VoDirection,     // INBOUND (SVO->BVO->DVO) or OUTBOUND (DVO->BVO->SVO)
    val transformMethod: String?,   // 변환이 일어난 메서드명
    val transformType: VoTransformType
)

enum class VoDirection { INBOUND, OUTBOUND }
enum class VoTransformType { BEAN_UTILS_COPY, MANUAL_SETTER, CONSTRUCTOR, NAMING_CONVENTION }

