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
            SpringFileType.INTERCEPTOR to ArchitectureLayer.COMMON
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
    fun resolve(psiClass: PsiClass, relativePath: String): FileNode {
        val fileType = resolveFileType(psiClass)
        val layer = LAYER_MAP[fileType] ?: ArchitectureLayer.COMMON
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
            injections = injections
        )
    }

    // ── 타입 판정 ──────────────────────────────────

    private fun resolveFileType(psiClass: PsiClass): SpringFileType {
        // 1. 어노테이션 비교 (FQN 및 Simple Name 폴백)
        for (annotation in psiClass.annotations) {
            val name = annotation.qualifiedName ?: annotation.nameReferenceElement?.referenceName ?: continue
            
            // FQN 직접 매칭
            ANNOTATION_TYPE_MAP[name]?.let { return it }
            
            // Simple Name 매칭 (FQN이 패키지 없는 단순 이름으로 반환되는 경우 폴백)
            val simpleMatch = ANNOTATION_TYPE_MAP.entries.firstOrNull { 
                it.key.endsWith(".$name") 
            }?.value
            
            if (simpleMatch != null) return simpleMatch
        }

        // 2. 클래스명 패턴 폴백
        return inferFromClassName(psiClass)
    }

    private fun inferFromClassName(psiClass: PsiClass): SpringFileType {
        val name = psiClass.name ?: return SpringFileType.UNKNOWN

        return when {
            psiClass.isEnum -> SpringFileType.ENUM
            psiClass.isInterface -> SpringFileType.INTERFACE
            psiClass.hasModifierProperty(PsiModifier.ABSTRACT) -> SpringFileType.ABSTRACT_CLASS
            name.endsWith("DTO") || name.endsWith("Dto") -> SpringFileType.DTO
            name.endsWith("VO") || name.endsWith("Vo") -> SpringFileType.VO
            name.endsWith("Mapper") -> SpringFileType.MAPPER
            name.endsWith("Filter") -> SpringFileType.FILTER
            name.endsWith("Interceptor") -> SpringFileType.INTERCEPTOR
            name.endsWith("Test") || name.endsWith("Tests") -> SpringFileType.TEST
            else -> SpringFileType.UNKNOWN
        }
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
