package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ArchitectureLayer
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.RelationshipType

object DeterministicChainExpander {

    /**
     * Stage 1.5: 후보군에서 진입점을 찾아 수직 호출 체인(Call Chain)을 결정론적으로 확장합니다.
     *
     * @param candidates Stage 1에서 넘어온 상위 후보군
     * @param graph 프로젝트 전체 메타그래프
     * @param maxExtraCandidates 체인 확장을 통해 추가될 수 있는 최대 파일 수 (기본값 10)
     * @param maxDepth 재귀 탐색 최대 깊이 (기본값 4)
     * @return (확장된 후보 리스트, 포맷팅된 마크다운 문자열)
     */
    fun buildCallChains(
        candidates: List<ScoredCandidate>,
        graph: ProjectGraph,
        maxExtraCandidates: Int = 10,
        maxDepth: Int = 4
    ): Pair<List<ScoredCandidate>, String> {
        val expandedCandidates = mutableMapOf<String, ScoredCandidate>()
        val chainOutputs = mutableListOf<String>()
        val processedRoots = mutableSetOf<String>()

        var extraAddedCount = 0

        for (candidate in candidates) {
            val rootNode = graph.files[candidate.filePath] ?: continue
            if (processedRoots.contains(rootNode.path)) continue

            // PRESENTATION(Controller 등) 또는 BUSINESS(Service 등) 레이어에서만 하위 탐색 시작
            if (rootNode.layer != ArchitectureLayer.PRESENTATION && rootNode.layer != ArchitectureLayer.BUSINESS) {
                if (!expandedCandidates.containsKey(candidate.filePath)) {
                    expandedCandidates[candidate.filePath] = candidate
                }
                continue
            }

            val chainNodes = mutableSetOf<String>()
            val lines = mutableListOf<String>()

            trace(rootNode.path, graph, chainNodes, lines, 0, maxDepth)

            if (chainNodes.size > 1) {
                chainOutputs.add("### Chain starting from: ${rootNode.className}\n" + lines.joinToString("\n"))
                processedRoots.addAll(chainNodes)

                for (path in chainNodes) {
                    if (!expandedCandidates.containsKey(path)) {
                        // 원래 후보에 없던 파일인 경우 한도 체크
                        val isOriginal = candidates.any { it.filePath == path }
                        if (!isOriginal) {
                            if (extraAddedCount >= maxExtraCandidates) continue
                            extraAddedCount++
                        }
                        
                        // 기존 후보면 기존 스코어 유지, 추가된 파일이면 약간 낮은 스코어(0.9) 부여
                        val originalCandidate = candidates.find { it.filePath == path }
                        expandedCandidates[path] = originalCandidate ?: ScoredCandidate(path, candidate.score * 0.9, emptyList())
                    }
                }
            } else {
                if (!expandedCandidates.containsKey(candidate.filePath)) {
                    expandedCandidates[candidate.filePath] = candidate
                }
            }
        }

        // 체인에 속하지 않은 나머지 원본 후보들도 순서대로 채워 넣음
        for (candidate in candidates) {
            if (!expandedCandidates.containsKey(candidate.filePath)) {
                expandedCandidates[candidate.filePath] = candidate
            }
        }

        val markdown = if (chainOutputs.isNotEmpty()) {
            "## Deterministic Call Chains\n" +
            "The following call chains were deterministically extracted from the project graph based on dependency injections and implementations. These files are highly likely to be relevant. Prioritize them in your analysis, but only select them if they are actually required to fulfill the user requirement.\n\n" +
            chainOutputs.joinToString("\n\n") + "\n\n"
        } else {
            ""
        }

        return Pair(expandedCandidates.values.sortedByDescending { it.score }, markdown)
    }

    private fun trace(
        path: String,
        graph: ProjectGraph,
        visited: MutableSet<String>,
        lines: MutableList<String>,
        depth: Int,
        maxDepth: Int
    ) {
        if (visited.contains(path) || depth > maxDepth) return
        visited.add(path)

        val node = graph.files[path] ?: return
        val indent = "  ".repeat(depth)
        val typeLabel = if (node.isInterface) "(Interface)" else "(Class)"
        lines.add("$indent- ${node.className} $typeLabel [${node.layer.displayName}]")

        if (node.apiEndpoints.isNotEmpty()) {
            val endpointsStr = node.apiEndpoints.joinToString(", ") { "${it.httpMethod} ${it.path}" }
            lines.add("$indent    - Endpoints: $endpointsStr")
        }

        val children = mutableSetOf<String>()

        // 1. Find injected dependencies (하위 의존성)
        for (dep in node.dependsOn) {
            val depNode = graph.files[dep] ?: continue
            // 트리 폭발을 막기 위해 비즈니스, 영속성 레이어 또는 인터페이스만 추적
            if (depNode.layer == ArchitectureLayer.BUSINESS || depNode.layer == ArchitectureLayer.PERSISTENCE || depNode.isInterface) {
                children.add(dep)
            }
        }

        // 2. 만약 인터페이스라면, 해당 인터페이스를 구현(IMPLEMENTS)하는 실제 구현체(Impl) 추가
        if (node.isInterface) {
            val impls = graph.relationships
                .filter { it.target == path && it.type == RelationshipType.IMPLEMENTS }
                .map { it.source }
            children.addAll(impls)
        }

        // 3. Find connected ResourceNodes (XML, JSP 등)
        val linkedResources = graph.resourceNodes.filter { it.linkedTo.contains(path) }
        for (rNode in linkedResources) {
            val rNodePath = rNode.path
            if (!visited.contains(rNodePath)) {
                visited.add(rNodePath)
                val rIndent = "  ".repeat(depth + 1)
                lines.add("$rIndent- ${rNodePath.substringAfterLast('/')} (Resource) [${rNode.type.name}]")
            }
        }

        // 의존성 순서대로 재귀 탐색
        for (childPath in children) {
            trace(childPath, graph, visited, lines, depth + 1, maxDepth)
        }
    }
}
