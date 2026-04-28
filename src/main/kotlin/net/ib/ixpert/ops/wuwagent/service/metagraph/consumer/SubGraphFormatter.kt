package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FileNode
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.TypeResolver
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ChangeRisk

/**
 * 추출된 서브그래프를 LLM이 이해하기 쉬운 마크다운 텍스트로 변환합니다.
 */
object SubGraphFormatter {

    fun format(graph: ProjectGraph, subGraph: SubGraph): String {
        val sb = StringBuilder()

        // 1. O(N)으로 인터페이스-구현체 매핑 테이블 사전 구축 (성능 최적화)
        // key: 인터페이스 FQN 또는 Simple Name, value: 구현체(FileNode) 리스트
        val implMap = mutableMapOf<String, MutableList<FileNode>>()
        for (node in graph.files.values) {
            if (!node.isInterface) {
                for (iface in node.implementedInterfaces) {
                    val simpleName = TypeResolver.toSimpleName(iface)
                    implMap.computeIfAbsent(iface) { mutableListOf() }.add(node)
                    implMap.computeIfAbsent(simpleName) { mutableListOf() }.add(node)
                }
            }
        }

        // 1. 프로젝트 요약
        sb.append("## 프로젝트 구조 컨텍스트\n")
        sb.append("- 프레임워크: ${graph.framework} / 총 ${graph.statistics.totalFiles}개 파일\n\n")

        // 2. 분석 대상 (Targets)
        sb.append("### 분석 대상\n")
        for (target in subGraph.targets) {
            sb.append("- ${formatNode(target, graph, implMap)}${formatEnrichments(target)}\n")
            // 대상의 직접 주입 정보 간략 표시
            val injections = target.injections.map { TypeResolver.unwrapGenericType(TypeResolver.toSimpleName(it.targetType)) }.distinct()
            if (injections.isNotEmpty()) {
                sb.append("  - 주입: ${injections.joinToString(", ")}\n")
            }
        }
        sb.append("\n")

        // 3. 상향 영향도 (dependedBy)
        for (depth in 1..subGraph.upwardDependencies.size) {
            val nodes = subGraph.upwardDependencies[depth] ?: continue
            if (nodes.isEmpty()) continue
            
            val depthTitle = if (depth == 1) "1차 영향 (이 클래스에 의존하는 파일)" else "${depth}차 영향"
            sb.append("### $depthTitle\n")
            for (node in nodes) {
                // 이 노드가 주입받는 대상 중 SubGraph 타겟과 연관된 것 찾기
                // 2차 이상의 노드는 타겟을 직접 주입받지 않으므로 해당 설명이 표시되지 않는 것이 정상 동작입니다. (의도됨)
                val injectedTargets = node.injections.filter { inj -> 
                    val injName = TypeResolver.unwrapGenericType(TypeResolver.toSimpleName(inj.targetType))
                    subGraph.targets.any { 
                        it.className == injName || 
                        it.implementedInterfaces.any { iface -> TypeResolver.toSimpleName(iface) == injName }
                    }
                }
                
                val detail = if (injectedTargets.isNotEmpty()) {
                    val names = injectedTargets.map { TypeResolver.unwrapGenericType(TypeResolver.toSimpleName(it.targetType)) }.distinct()
                    " -> ${names.joinToString(", ")} 주입"
                } else ""
                
                // 1차 영향도 노드 중 ChangeRisk가 HIGH 이상이면 경고 태그 추가
                val riskTag = if (depth == 1 && (node.changeRisk == ChangeRisk.HIGH || node.changeRisk == ChangeRisk.CRITICAL)) {
                    " ⚠️ HIGH RISK"
                } else ""
                
                sb.append("- ${formatNode(node, graph, implMap)}$detail$riskTag\n")
            }
            sb.append("\n")
        }

        // 4. 하향 의존성 (dependsOn)
        var hasDownward = false
        for (depth in 1..subGraph.downwardDependencies.size) {
            val nodes = subGraph.downwardDependencies[depth] ?: continue
            if (nodes.isEmpty()) continue
            
            if (!hasDownward) {
                sb.append("### 분석 대상의 의존성\n")
                hasDownward = true
            }
            for (node in nodes) {
                sb.append("- ${formatNode(node, graph, implMap)}\n")
            }
        }
        if (hasDownward) sb.append("\n")

        // 5. Truncated 경고
        if (subGraph.isTruncated) {
            sb.append("> ⚠️ 이 서브그래프는 전체 영향 범위의 일부입니다 (maxNodes 제한 적용됨)\n")
        }

        return sb.toString().trimEnd()
    }

    /**
     * 인터페이스-구현체 병합 표기를 처리하여 노드 문자열을 생성합니다.
     * 예: UserService (impl: UserServiceImpl) (SERVICE / BUSINESS)
     */
    private fun formatNode(node: FileNode, graph: ProjectGraph, implMap: Map<String, List<FileNode>>): String {
        var displayName = node.className
        
        if (node.isInterface) {
            // 이 인터페이스를 구현한 구체 클래스가 그래프 내에 있는지 확인
            val impls = implMap[node.className] ?: emptyList()
            if (impls.size == 1) {
                displayName = "${node.className} (impl: ${impls[0].className})"
            }
        } else {
            // 구체 클래스인 경우, 구현하고 있는 첫 번째 인터페이스를 기준으로 묶어줌
            if (node.implementedInterfaces.isNotEmpty()) {
                val primaryIfaceName = TypeResolver.toSimpleName(node.implementedInterfaces.first())
                // 그래프 내에 해당 인터페이스가 존재하고 구현체가 1개일 때만 병합
                if (graph.files.values.any { it.className == primaryIfaceName }) {
                    val impls = implMap[primaryIfaceName] ?: emptyList()
                    if (impls.size == 1) {
                        displayName = "$primaryIfaceName (impl: ${node.className})"
                    }
                }
            }
        }

        return "$displayName (${node.fileType.name} / ${node.layer.name})"
    }

    /**
     * 타겟 노드용 보강 정보를 생성합니다.
     */
    private fun formatEnrichments(node: FileNode): String {
        var result = ""
        if (node.apiEndpoints.isNotEmpty()) {
            val endpointList = node.apiEndpoints.joinToString(", ") { "[${it.httpMethod}] ${it.path}" }
            result += " ✨ Endpoints: $endpointList"
        }
        if (node.beanDefinitions.isNotEmpty()) {
            val beanList = node.beanDefinitions.joinToString(", ") { "${it.beanName}: ${it.returnType}" }
            result += " 📦 Beans: $beanList"
        }
        if (node.entityRelations.isNotEmpty()) {
            val relationList = node.entityRelations.joinToString(", ") { "${it.type} -> ${it.targetEntity}" }
            result += " 🔗 Relations: $relationList"
        }
        return result
    }
}
