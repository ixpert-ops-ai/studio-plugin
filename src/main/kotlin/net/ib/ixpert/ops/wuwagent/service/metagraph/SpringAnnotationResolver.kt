package net.ib.ixpert.ops.wuwagent.service.metagraph

import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*

/**
 * Spring 어노테이션 기반 분석기.
 * PsiClass를 입력받아 FileNode를 직접 생성합니다.
 *
 * 핵심 설계 원칙:
 * - FQN(Fully Qualified Name) 기반 어노테이션 비교
 * - DI 주입 3패턴 (생성자/필드/Setter) + static final 제외
 * - 제네릭 래핑 해제 (TypeResolver 활용)
 */
class SpringAnnotationResolver {

    companion object {
        // ── FQN 기반 어노테이션 → SpringFileType 매핑 ──
        private val ANNOTATION_TYPE_MAP: Map<String, SpringFileType> = mapOf(
            "org.springframework.web.bind.annotation.RestController" to SpringFileType.REST_CONTROLLER,
            "org.springframework.stereotype.Controller" to SpringFileType.CONTROLLER,
            "org.springframework.stereotype.Service" to SpringFileType.SERVICE,
            "org.springframework.stereotype.Repository" to SpringFileType.REPOSITORY,
            "org.springframework.stereotype.Component" to SpringFileType.COMPONENT,
            "org.springframework.context.annotation.Configuration" to SpringFileType.CONFIG,
            "javax.persistence.Entity" to SpringFileType.ENTITY,
            "jakarta.persistence.Entity" to SpringFileType.ENTITY,
            "org.springframework.web.bind.annotation.ControllerAdvice" to SpringFileType.EXCEPTION_HANDLER,
            "org.springframework.web.bind.annotation.RestControllerAdvice" to SpringFileType.EXCEPTION_HANDLER
        )

        // ── 어노테이션 → 레이어 매핑 ──
        private val LAYER_MAP: Map<SpringFileType, ArchitectureLayer> = mapOf(
            SpringFileType.REST_CONTROLLER to ArchitectureLayer.PRESENTATION,
            SpringFileType.CONTROLLER to ArchitectureLayer.PRESENTATION,
            SpringFileType.EXCEPTION_HANDLER to ArchitectureLayer.PRESENTATION,
            SpringFileType.DTO to ArchitectureLayer.PRESENTATION,
            SpringFileType.VO to ArchitectureLayer.PRESENTATION,
            SpringFileType.SERVICE to ArchitectureLayer.BUSINESS,
            SpringFileType.REPOSITORY to ArchitectureLayer.PERSISTENCE,
            SpringFileType.ENTITY to ArchitectureLayer.PERSISTENCE,
            SpringFileType.MAPPER to ArchitectureLayer.PERSISTENCE,
            SpringFileType.CONFIG to ArchitectureLayer.COMMON,
            SpringFileType.COMPONENT to ArchitectureLayer.COMMON,
            SpringFileType.FILTER to ArchitectureLayer.COMMON,
            SpringFileType.INTERCEPTOR to ArchitectureLayer.COMMON,
            SpringFileType.UTIL to ArchitectureLayer.COMMON,
            SpringFileType.VIEW to ArchitectureLayer.PRESENTATION,
            SpringFileType.EXCEPTION to ArchitectureLayer.COMMON
        )

        // ── DI 관련 어노테이션 FQN ──
        private val AUTOWIRED_FQNS = setOf(
            "org.springframework.beans.factory.annotation.Autowired",
            "javax.inject.Inject",
            "jakarta.inject.Inject",
            "javax.annotation.Resource",
            "jakarta.annotation.Resource"
        )

        private val REQUIRED_ARGS_CONSTRUCTOR_FQNS = setOf(
            "lombok.RequiredArgsConstructor",
            "lombok.AllArgsConstructor"
        )
    }

    /**
     * PsiClass로부터 FileNode를 생성합니다.
     * @param psiClass 분석 대상 PsiClass
     * @param relativePath 프로젝트 루트 기준 상대 경로
     */
    /**
     * PsiClass로부터 FileNode를 생성합니다.
     * @param psiClass 분석 대상 PsiClass
     * @param relativePath 프로젝트 루트 기준 상대 경로
     */
    fun resolve(psiClass: PsiClass, relativePath: String): FileNode {
        val settings = net.ib.ixpert.ops.wuwagent.setting.SettingsState.getInstance()
        val isAnyframe = settings.state.frameworkType == FrameworkType.ANYFRAME

        var anyframeRole: AnyframeRole? = null
        var fileType = resolveFileType(psiClass, relativePath)
        var layer = LAYER_MAP[fileType] ?: ArchitectureLayer.COMMON
        var localName: String? = null
        var datasource: String? = null

        if (isAnyframe) {
            anyframeRole = classifyAnyframeRole(psiClass, relativePath)
            if (anyframeRole != AnyframeRole.UNKNOWN) {
                fileType = mapAnyframeRoleToSpringFileType(anyframeRole)
                layer = mapAnyframeRoleToLayer(anyframeRole)
            }
            localName = getAnnotationStringValue(psiClass, "LocalName")
            datasource = getAnnotationStringValue(psiClass, "datasource") ?: getAnnotationStringValue(psiClass, "dataSource")
        }

        val annotations = extractAnnotationNames(psiClass)
        val injections = resolveDependencyInjections(psiClass, fileType)

        val superClass = psiClass.superClass?.let { sup ->
            if (sup.qualifiedName != "java.lang.Object") sup.qualifiedName else null
        }

        val interfaces = psiClass.interfaces.mapNotNull { it.qualifiedName }

        return FileNode(
            path = relativePath,
            packageName = (psiClass.containingFile as? PsiJavaFile)?.packageName,
            className = psiClass.name ?: "<anonymous>",
            fileType = fileType,
            layer = layer,
            isInterface = psiClass.isInterface,
            isAbstract = psiClass.hasModifierProperty(PsiModifier.ABSTRACT),
            annotations = annotations,
            superClass = superClass,
            implementedInterfaces = interfaces,
            injections = injections,
            anyframeRole = anyframeRole,
            localName = localName,
            datasource = datasource
        )
    }

    private fun classifyAnyframeRole(psiClass: PsiClass, relativePath: String): AnyframeRole {
        val className = psiClass.name ?: ""
        val normalizedPath = relativePath.replace("\\", "/").lowercase()
        
        // Check annotations first
        val stereotype = getAnnotationStringValue(psiClass, "stereotype") ?: getAnnotationStringValue(psiClass, "Stereotype")
        if (stereotype != null) {
            val cleanStereotype = stereotype.trim().uppercase()
            when (cleanStereotype) {
                "BIZ" -> return AnyframeRole.BIZ
                "BIZ_UTIL" -> return AnyframeRole.BIZ_UTIL
                "SVC" -> return AnyframeRole.SVC
                "SVC_IMPL" -> return AnyframeRole.SVC_IMPL
                "DEM" -> return AnyframeRole.DEM
                "DQM" -> return AnyframeRole.DQM
            }
        }
        
        val daoType = getAnnotationStringValue(psiClass, "daoType") ?: getAnnotationStringValue(psiClass, "DaoType")
        if (daoType != null) {
            val cleanDao = daoType.trim().uppercase()
            when (cleanDao) {
                "DEM" -> return AnyframeRole.DEM
                "DQM" -> return AnyframeRole.DQM
            }
        }

        // 1. 패키지 경로 기반 분류 (가장 견고함)
        if (normalizedPath.contains("/dem/dvo/") || normalizedPath.contains("/dqm/dvo/") || (normalizedPath.contains("/svc/svo/") && normalizedPath.contains("/dvo/"))) {
            return AnyframeRole.DVO
        }
        if (normalizedPath.contains("/biz/bvo/") || normalizedPath.contains("/bvo/")) {
            return AnyframeRole.BVO
        }
        if (normalizedPath.contains("/svc/svo/") || normalizedPath.contains("/svo/")) {
            return AnyframeRole.SVO
        }
        if (normalizedPath.contains("/svc/impl/") || normalizedPath.contains("/svcimpl/")) {
            return AnyframeRole.SVC_IMPL
        }
        
        // 2. 클래스 접미사 기반 분류
        return when {
            className.endsWith("DEM") -> AnyframeRole.DEM
            className.endsWith("NDEM") -> AnyframeRole.DEM  // 변형 (예: ACAMTBAPC001NDEM)
            className.endsWith("DQM") -> AnyframeRole.DQM
            className.endsWith("DVO") -> AnyframeRole.DVO
            className.endsWith("BVO") -> AnyframeRole.BVO
            className.endsWith("SVO") -> AnyframeRole.SVO
            className.endsWith("SVCImpl") -> AnyframeRole.SVC_IMPL
            className.endsWith("SVC") -> AnyframeRole.SVC
            className.endsWith("BIZ") -> AnyframeRole.BIZ
            else -> AnyframeRole.UNKNOWN
        }
    }

    private fun mapAnyframeRoleToSpringFileType(role: AnyframeRole): SpringFileType = when (role) {
        AnyframeRole.SVC -> SpringFileType.INTERFACE
        AnyframeRole.SVC_IMPL -> SpringFileType.SERVICE
        AnyframeRole.BIZ -> SpringFileType.SERVICE
        AnyframeRole.BIZ_UTIL -> SpringFileType.UTIL
        AnyframeRole.DEM -> SpringFileType.REPOSITORY
        AnyframeRole.DQM -> SpringFileType.REPOSITORY
        AnyframeRole.SVO -> SpringFileType.VO
        AnyframeRole.BVO -> SpringFileType.VO
        AnyframeRole.DVO -> SpringFileType.VO
        AnyframeRole.UNKNOWN -> SpringFileType.UNKNOWN
    }

    private fun mapAnyframeRoleToLayer(role: AnyframeRole): ArchitectureLayer = when (role) {
        AnyframeRole.SVC -> ArchitectureLayer.BUSINESS
        AnyframeRole.SVC_IMPL -> ArchitectureLayer.BUSINESS
        AnyframeRole.BIZ -> ArchitectureLayer.BUSINESS
        AnyframeRole.BIZ_UTIL -> ArchitectureLayer.COMMON
        AnyframeRole.DEM -> ArchitectureLayer.PERSISTENCE
        AnyframeRole.DQM -> ArchitectureLayer.PERSISTENCE
        AnyframeRole.SVO -> ArchitectureLayer.PRESENTATION
        AnyframeRole.BVO -> ArchitectureLayer.BUSINESS
        AnyframeRole.DVO -> ArchitectureLayer.PERSISTENCE
        AnyframeRole.UNKNOWN -> ArchitectureLayer.COMMON
    }

    private fun getAnnotationStringValue(psiClass: PsiClass, annotationFqn: String): String? {
        val ann = psiClass.annotations.firstOrNull { 
            val name = it.qualifiedName ?: it.nameReferenceElement?.referenceName ?: ""
            name == annotationFqn || name.endsWith(".$annotationFqn")
        } ?: return null
        
        val valueExpr = ann.findAttributeValue("value") ?: ann.findAttributeValue(null)
        return when (valueExpr) {
            is PsiLiteralExpression -> valueExpr.value?.toString()
            else -> valueExpr?.text?.trim('"')
        }
    }

    // ── 타입 판정 ──────────────────────────────────

    private fun resolveFileType(psiClass: PsiClass, relativePath: String): SpringFileType {
        var annotationType = SpringFileType.UNKNOWN
        
        // 1. 어노테이션 비교 (FQN 및 Simple Name 폴백)
        for (annotation in psiClass.annotations) {
            val name = annotation.qualifiedName ?: annotation.nameReferenceElement?.referenceName ?: continue
            
            // FQN 직접 매칭
            var match = ANNOTATION_TYPE_MAP[name]
            
            // Simple Name 매칭 (FQN이 패키지 없는 단순 이름으로 반환되는 경우 폴백)
            if (match == null) {
                match = ANNOTATION_TYPE_MAP.entries.firstOrNull { 
                    it.key.endsWith(".$name") 
                }?.value
            }
            
            if (match != null) {
                annotationType = match
                break
            }
        }

        // @Component는 범용이므로, 클래스명/경로 패턴이 더 구체적이면 그걸 우선 (예: AuthDto에 @Component가 붙은 경우)
        if (annotationType == SpringFileType.COMPONENT) {
            val nameType = inferFromClassAndPath(psiClass, relativePath)
            if (nameType != SpringFileType.UNKNOWN) return nameType
        }

        if (annotationType != SpringFileType.UNKNOWN) return annotationType

        // 2. 클래스명 및 경로, 내용 기반 패턴 폴백
        return inferFromClassAndPath(psiClass, relativePath)
    }

    private fun inferFromClassAndPath(psiClass: PsiClass, relativePath: String): SpringFileType {
        val name = psiClass.name ?: return SpringFileType.UNKNOWN
        val lowerPath = relativePath.lowercase()

        val superClassName = psiClass.superClass?.name ?: ""
        val interfaceNames = psiClass.interfaces.map { it.name }

        // 1순위: 확실한 기본 타입 및 PSI 상속 관계
        if (psiClass.isEnum) return SpringFileType.ENUM
        
        if (psiClass.isInterface) {
            // Spring Data Repository 인터페이스 처리
            if (name.contains("Repository")) return SpringFileType.REPOSITORY
            if (interfaceNames.any { it?.contains("Repository") == true }) return SpringFileType.REPOSITORY
            return SpringFileType.INTERFACE
        }
        
        if (psiClass.hasModifierProperty(PsiModifier.ABSTRACT)) return SpringFileType.ABSTRACT_CLASS

        if (superClassName.contains("View")) return SpringFileType.VIEW
        if (superClassName.contains("Filter") || interfaceNames.any { it?.contains("Filter") == true }) return SpringFileType.FILTER
        if (superClassName.contains("Interceptor") || interfaceNames.any { it?.contains("Interceptor") == true }) return SpringFileType.INTERCEPTOR
        if (superClassName.contains("HttpServletRequestWrapper")) return SpringFileType.FILTER

        // 2순위: 클래스명 강제 규칙 (명확한 접미사/키워드)
        // DTO 판별 로직을 건너뛰도록 강제합니다.
        if (name.lowercase() == "temp") return SpringFileType.UNKNOWN
        if (name.endsWith("Util") || name.endsWith("Utils") || name.endsWith("Helper")) return SpringFileType.UTIL
        if (name.contains("Repository")) return SpringFileType.REPOSITORY
        if (name.contains("Filter")) return SpringFileType.FILTER
        if (name == "SessionVal") return SpringFileType.UTIL

        // 3순위: Getter/Setter 밀도 기반 DTO 판별
        if (isDtoByGetterSetterDensity(psiClass)) {
            return SpringFileType.DTO
        }

        // 3순위: 경로 패턴 (Path Fallback)
        when {
            "/dto/" in lowerPath -> return SpringFileType.DTO
            "/vo/" in lowerPath -> return SpringFileType.VO
            "/entity/" in lowerPath || "/entities/" in lowerPath -> return SpringFileType.ENTITY
            "/config/" in lowerPath || "/configuration/" in lowerPath -> return SpringFileType.CONFIG
            "/filter/" in lowerPath -> return SpringFileType.FILTER
            "/interceptor/" in lowerPath -> return SpringFileType.INTERCEPTOR
            "/util/" in lowerPath || "/utils/" in lowerPath || "/common/" in lowerPath -> return SpringFileType.UTIL
            "/view/" in lowerPath -> return SpringFileType.VIEW
            "/exception/" in lowerPath -> return SpringFileType.EXCEPTION
            "/controller/" in lowerPath -> return SpringFileType.CONTROLLER
            "/service/" in lowerPath -> return SpringFileType.SERVICE
            "/dao/" in lowerPath || "/repository/" in lowerPath || "/mapper/" in lowerPath -> return SpringFileType.REPOSITORY
        }

        // 5순위: 파일명 접미사 패턴 (기존 로직)
        return when {
            name.endsWith("DTO") || name.endsWith("Dto") -> SpringFileType.DTO
            name.endsWith("VO") || name.endsWith("Vo") -> SpringFileType.VO
            name.endsWith("Mapper") -> SpringFileType.MAPPER
            name.endsWith("Interceptor") -> SpringFileType.INTERCEPTOR
            name.endsWith("Test") || name.endsWith("Tests") -> SpringFileType.TEST
            name.endsWith("View") -> SpringFileType.VIEW
            name.endsWith("Exception") -> SpringFileType.EXCEPTION
            else -> SpringFileType.UNKNOWN
        }
    }

    /**
     * 전체 메서드 중 getter/setter의 비율이 60% 이상이면 DTO로 간주합니다.
     */
    private fun isDtoByGetterSetterDensity(psiClass: PsiClass): Boolean {
        val methods = psiClass.methods
        val totalMethods = methods.size
        
        if (totalMethods < 4) return false // 메서드가 너무 적으면 판별 불가

        val getterSetterCount = methods.count { method ->
            val mName = method.name
            (mName.startsWith("get") || mName.startsWith("set") || mName.startsWith("is"))
        }

        return (getterSetterCount.toDouble() / totalMethods) >= 0.6
    }

    // ── 어노테이션 추출 ───────────────────────────

    private fun extractAnnotationNames(psiClass: PsiClass): List<String> {
        return psiClass.annotations.mapNotNull { ann ->
            val name = ann.qualifiedName ?: ann.nameReferenceElement?.referenceName
            name?.substringAfterLast(".")
        }
    }

    // ── DI 주입 분석 (3패턴) ──────────────────────

    private fun isSpringManaged(fileType: SpringFileType): Boolean {
        return fileType !in listOf(
            SpringFileType.DTO, SpringFileType.VO,
            SpringFileType.ENUM, SpringFileType.INTERFACE,
            SpringFileType.ABSTRACT_CLASS, SpringFileType.UNKNOWN,
            SpringFileType.ENTITY, SpringFileType.TEST
        )
    }

    private fun isLikelySpringBeanType(typeFqn: String): Boolean {
        val excludePrefixes = listOf(
            "java.", "javax.", "jakarta.", "org.slf4j.",
            "org.apache.", "com.fasterxml.", "org.springframework.web."
        )
        return excludePrefixes.none { typeFqn.startsWith(it) }
    }

    private fun matchesAnyAnnotation(annotation: PsiAnnotation, fqnSet: Set<String>): Boolean {
        val name = annotation.qualifiedName ?: annotation.nameReferenceElement?.referenceName ?: return false
        if (fqnSet.contains(name)) return true
        return fqnSet.any { it.endsWith(".$name") }
    }

    /**
     * DI 주입을 분석합니다.
     * 1. @RequiredArgsConstructor + final 필드 (static final 제외)
     * 2. 명시적 @Autowired 생성자
     * 3. @Autowired / @Inject / @Resource 필드 주입
     */
    private fun resolveDependencyInjections(psiClass: PsiClass, fileType: SpringFileType): List<DependencyInjection> {
        // Spring 관리 대상 빈이 아니면 DI 분석을 건너뜀 (Bug 3 방지)
        if (!isSpringManaged(fileType)) return emptyList()

        val injections = mutableListOf<DependencyInjection>()
        val processedFields = mutableSetOf<String>()

        // 패턴 1: Lombok @RequiredArgsConstructor + final 필드
        val hasRequiredArgsConstructor = psiClass.annotations.any { ann ->
            matchesAnyAnnotation(ann, REQUIRED_ARGS_CONSTRUCTOR_FQNS)
        }

        if (hasRequiredArgsConstructor) {
            psiClass.fields
                .filter { it.hasModifierProperty(PsiModifier.FINAL) }
                .filter { !it.hasModifierProperty(PsiModifier.STATIC) }  // static final 제외
                .forEach { field ->
                    val fieldName = field.name
                    if (processedFields.add(fieldName)) {
                        val rawType = field.type.canonicalText
                        val targetType = TypeResolver.unwrapGenericType(rawType)
                        if (isLikelySpringBeanType(targetType)) {
                            injections.add(DependencyInjection(
                                targetType = targetType,
                                fieldName = fieldName,
                                method = InjectionMethod.CONSTRUCTOR
                            ))
                        }
                    }
                }
        }

        // 패턴 2: 명시적 @Autowired 생성자 주입
        psiClass.constructors
            .filter { constructor ->
                constructor.annotations.any { matchesAnyAnnotation(it, AUTOWIRED_FQNS) }
                        || (psiClass.constructors.size == 1 && !hasRequiredArgsConstructor)
            }
            .forEach { constructor ->
                constructor.parameterList.parameters.forEach { param ->
                    val fieldName = param.name
                    if (processedFields.add(fieldName)) {
                        val rawType = param.type.canonicalText
                        val targetType = TypeResolver.unwrapGenericType(rawType)
                        if (isLikelySpringBeanType(targetType)) {
                            injections.add(DependencyInjection(
                                targetType = targetType,
                                fieldName = fieldName,
                                method = InjectionMethod.CONSTRUCTOR
                            ))
                        }
                    }
                }
            }

        // 패턴 3: 필드 주입 (@Autowired / @Inject / @Resource)
        psiClass.fields
            .filter { field ->
                field.annotations.any { matchesAnyAnnotation(it, AUTOWIRED_FQNS) }
            }
            .forEach { field ->
                val fieldName = field.name
                if (processedFields.add(fieldName)) {
                    val rawType = field.type.canonicalText
                    val targetType = TypeResolver.unwrapGenericType(rawType)
                    if (isLikelySpringBeanType(targetType)) {
                        injections.add(DependencyInjection(
                            targetType = targetType,
                            fieldName = fieldName,
                            method = InjectionMethod.FIELD
                        ))
                    }
                }
            }

        return injections
    }
}
