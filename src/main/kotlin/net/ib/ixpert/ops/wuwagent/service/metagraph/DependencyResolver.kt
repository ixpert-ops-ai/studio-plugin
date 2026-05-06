package net.ib.ixpert.ops.wuwagent.service.metagraph

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiModifier
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*

/**
 * DI 의존성 해석기.
 * IntelliJ PSI의 ClassInheritorsSearch를 활용하여
 * 인터페이스 → 구현체 매핑을 배치로 처리합니다.
 *
 * 해석 우선순위: @Qualifier → @Primary → 유일 구현체
 */
class DependencyResolver(private val project: Project) {

    private val logger = Logger.getInstance(DependencyResolver::class.java)

    /**
     * 모든 FileNode의 DI 주입 대상을 배치로 해석합니다.
     *
     * Step 4: 전체 인터페이스 타입 수집 → 배치 ClassInheritorsSearch
     * Step 5: DependencyInjection.resolvedImpl 채우기
     *
     * @return resolvedImpl이 채워진 새 FileNode 맵
     */
    fun resolveAll(nodes: Map<String, FileNode>): Map<String, FileNode> {
        // Step 4: 모든 인터페이스 타입을 한 번에 수집
        val allTargetTypes = nodes.values
            .flatMap { it.injections }
            .map { it.targetType }
            .toSet()

        if (allTargetTypes.isEmpty()) return nodes

        // 배치 ClassInheritorsSearch
        val implementationMap = ReadAction.compute<Map<String, List<String>>, Throwable> {
            buildImplementationMap(allTargetTypes)
        }

        // Step 5: resolvedImpl 채우기
        val resolvedNodes = nodes.toMutableMap()
        for ((path, node) in resolvedNodes) {
            val resolvedInjections = node.injections.map { injection ->
                val impls = implementationMap[injection.targetType]
                val resolved = when {
                    impls == null || impls.isEmpty() -> {
                        // 구체 클래스이거나 외부 라이브러리 → null (에러 아님)
                        null
                    }
                    impls.size == 1 -> impls[0]
                    else -> {
                        // 여러 구현체 → Primary/Qualifier 기반 해석 (Phase 1a: 첫 번째 선택)
                        resolvePrimaryOrFirst(impls)
                    }
                }
                injection.copy(resolvedImpl = resolved)
            }
            resolvedNodes[path] = node.copy(injections = resolvedInjections)
        }

        return resolvedNodes
    }

    /**
     * 인터페이스 타입들의 구현체를 IntelliJ 인덱스에서 배치로 검색합니다.
     * 인터페이스가 아닌 타입은 빈 리스트로 매핑됩니다.
     */
    private fun buildImplementationMap(targetTypes: Set<String>): Map<String, List<String>> {
        val facade = JavaPsiFacade.getInstance(project)
        val scope = GlobalSearchScope.projectScope(project)

        return targetTypes.associateWith { typeName ->
            try {
                val psiClass = facade.findClass(typeName, scope) ?: return@associateWith emptyList()

                if (psiClass.isInterface || psiClass.hasModifierProperty(PsiModifier.ABSTRACT)) {
                    ClassInheritorsSearch.search(psiClass, scope, true)
                        .findAll()
                        .filter { !it.isInterface && !it.hasModifierProperty(PsiModifier.ABSTRACT) }
                        .mapNotNull { it.qualifiedName }
                } else {
                    // 구체 클래스 → 구현체 검색 불필요
                    emptyList()
                }
            } catch (e: Exception) {
                logger.warn("Failed to search implementations for: $typeName", e)
                emptyList()
            }
        }
    }

    /**
     * 여러 구현체 중 @Primary를 찾거나, 없으면 첫 번째를 반환합니다.
     * TODO Phase 1b: @Qualifier 기반 매칭 정교화
     */
    private fun resolvePrimaryOrFirst(implementations: List<String>): String {
        val facade = JavaPsiFacade.getInstance(project)
        val scope = GlobalSearchScope.projectScope(project)

        // @Primary가 있는 구현체 우선
        for (implFqn in implementations) {
            try {
                val implClass = facade.findClass(implFqn, scope) ?: continue
                val hasPrimary = implClass.annotations.any { ann ->
                    ann.qualifiedName == "org.springframework.context.annotation.Primary"
                }
                if (hasPrimary) return implFqn
            } catch (_: Exception) { /* skip */ }
        }

        return implementations.first()
    }

    /**
     * FileNode들 사이의 양방향 의존관계(dependsOn/dependedBy)를 채웁니다.
     * (Step 6)
     *
     * @param nodes resolvedImpl이 채워진 FileNode 맵
     * @return Relationship 리스트
     */
    fun buildRelationships(nodes: Map<String, FileNode>): List<Relationship> {
        val relationships = mutableListOf<Relationship>()

        // FQN → path 역 인덱스 구축
        val fqnToPath = mutableMapOf<String, String>()
        for ((path, node) in nodes) {
            val fqn = if (node.packageName != null) "${node.packageName}.${node.className}" else node.className
            fqnToPath[fqn] = path
        }

        for ((sourcePath, sourceNode) in nodes) {
            // DI 주입 관계
            for (injection in sourceNode.injections) {
                val targetFqn = injection.resolvedImpl ?: injection.targetType
                val targetPath = fqnToPath[targetFqn] ?: continue

                sourceNode.dependsOn.add(targetPath)
                nodes[targetPath]?.dependedBy?.add(sourcePath)

                relationships.add(Relationship(
                    source = sourcePath,
                    target = targetPath,
                    type = RelationshipType.INJECTS,
                    strength = if (injection.resolvedImpl != null) RelationshipStrength.INDIRECT else RelationshipStrength.DIRECT,
                    detail = "via ${injection.fieldName}"
                ))
            }

            // 상속 관계
            sourceNode.superClass?.let { superFqn ->
                val targetPath = fqnToPath[superFqn] ?: return@let
                sourceNode.dependsOn.add(targetPath)
                nodes[targetPath]?.dependedBy?.add(sourcePath)

                relationships.add(Relationship(
                    source = sourcePath,
                    target = targetPath,
                    type = RelationshipType.EXTENDS
                ))
            }

            // 인터페이스 구현 관계
            for (ifaceFqn in sourceNode.implementedInterfaces) {
                val targetPath = fqnToPath[ifaceFqn] ?: continue
                sourceNode.dependsOn.add(targetPath)
                nodes[targetPath]?.dependedBy?.add(sourcePath)

                relationships.add(Relationship(
                    source = sourcePath,
                    target = targetPath,
                    type = RelationshipType.IMPLEMENTS
                ))
            }
        }

        return relationships
    }
}
