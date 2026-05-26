package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph

/**
 * 한글 로컬명(localName) 기반 수집기.
 *
 * [AnalyzedQuery.koreanNouns]에서 추출된 한글 명사를 각 파일 노드의
 * [localName][net.ib.ixpert.ops.wuwagent.service.metagraph.model.FileNode.localName]과 비교합니다.
 *
 * 점수 기준:
 * - localName 정확 일치 또는 포함: **100**
 *
 * @param graph 검색 대상 프로젝트 그래프
 */
class LocalNameCollector(
    private val graph: ProjectGraph
) : SubCollector {

    override fun search(query: AnalyzedQuery): List<ScoredCandidate> {
        if (query.koreanNouns.isEmpty()) return emptyList()

        val results = mutableListOf<ScoredCandidate>()

        for ((path, node) in graph.files.entries) {
            val localName = node.localName ?: continue
            if (localName.isBlank()) continue

            val matched = query.koreanNouns.filter { noun ->
                localName.contains(noun) || noun.contains(localName)
            }

            if (matched.isNotEmpty()) {
                results.add(
                    ScoredCandidate(
                        filePath = path,
                        score = 100.0,
                        matchedBy = matched.map { "localName 매칭: $it ↔ $localName" }
                    )
                )
            }
        }

        return results
    }
}
