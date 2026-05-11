package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*
import java.util.*

/**
 * MetaGraph 기반의 변경 영향 분석기.
 * BFS 알고리즘을 사용하여 특정 파일의 변경이 프로젝트 전체에 미치는 파급 효과를 분석합니다.
 */
object MetaImpactAnalyzer {

    /**
     * 특정 파일의 변경 영향 범위를 분석합니다.
     *
     * @param graph 프로젝트 전체 그래프
     * @param targetPath 분석 대상 파일의 상대 경로
     * @param maxDepth 탐색 최대 깊이 (기본 3)
     * @return ImpactResult 분석 결과 객체
     */
    fun analyze(graph: ProjectGraph, targetPath: String, maxDepth: Int = 3): ImpactResult {
        val targetNode = graph.files[targetPath] ?: throw IllegalArgumentException("그래프에서 대상 파일을 찾을 수 없습니다: $targetPath")

        val visited = mutableSetOf<String>()
        visited.add(targetPath)

        val directImpact = mutableListOf<ImpactNode>()
        val indirectImpact = mutableListOf<ImpactNode>()

        // 큐 구조: Triple(현재 파일 경로, 현재 depth, 관계 체인)
        val queue: Queue<Triple<String, Int, List<String>>> = LinkedList()

        // 1단계 호출처(나를 의존하는 파일들) 수집
        for (callerPath in targetNode.dependedBy) {
            val relationType = findRelationType(graph, callerPath, targetPath)
            queue.add(Triple(callerPath, 1, listOf(relationType)))
        }

        var maxRiskAssessment = targetNode.riskAssessment

        while (queue.isNotEmpty()) {
            val (currentPath, depth, chain) = queue.poll()
            
            // 이미 방문한 노드인 경우 스킵 (순환 참조 방지)
            if (visited.contains(currentPath)) continue
            visited.add(currentPath)

            val currentNode = graph.files[currentPath] ?: continue

            // 최대 위험도 갱신
            if (currentNode.riskAssessment.riskScore > maxRiskAssessment.riskScore) {
                maxRiskAssessment = currentNode.riskAssessment
            }

            val impactNode = ImpactNode(
                filePath = currentPath,
                className = currentNode.className,
                depth = depth,
                relationChain = chain,
                riskAssessment = currentNode.riskAssessment
            )

            if (depth == 1) {
                directImpact.add(impactNode)
            } else {
                indirectImpact.add(impactNode)
            }

            // 최대 깊이에 도달하지 않았다면 다음 레벨 탐색
            if (depth < maxDepth) {
                for (nextCallerPath in currentNode.dependedBy) {
                    if (!visited.contains(nextCallerPath)) {
                        val nextRelation = findRelationType(graph, nextCallerPath, currentPath)
                        queue.add(Triple(nextCallerPath, depth + 1, chain + nextRelation))
                    }
                }
            }
        }

        return ImpactResult(
            targetFile = targetPath,
            directImpact = directImpact,
            indirectImpact = indirectImpact,
            totalAffectedFiles = visited.size - 1, // 타겟 파일 본인 제외
            maxRiskInChain = maxRiskAssessment
        )
    }

    /**
     * 두 파일 간의 관계 유형을 찾습니다.
     */
    private fun findRelationType(graph: ProjectGraph, source: String, target: String): String {
        // 여러 관계가 있을 수 있으나 첫 번째를 우선 반환
        val rel = graph.relationships.find { it.source == source && it.target == target }
        return if (rel != null) {
            if (rel.type == RelationshipType.CALLS && rel.callType != null) {
                "CALLS(${rel.callType})"
            } else {
                rel.type.name
            }
        } else {
            "DEPENDS"
        }
    }
}
