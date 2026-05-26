package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph

/**
 * 서비스 엔드포인트 기반 수집기.
 *
 * 두 가지 경로로 매칭합니다:
 * 1. [AnalyzedQuery.serviceIds] → [ServiceEndpoint.serviceId][net.ib.ixpert.ops.wuwagent.service.metagraph.model.ServiceEndpoint.serviceId] 정확 일치
 * 2. [AnalyzedQuery.koreanNouns] → [ServiceEndpoint.localName][net.ib.ixpert.ops.wuwagent.service.metagraph.model.ServiceEndpoint.localName] 포함 매칭
 *
 * 점수 기준:
 * - ServiceId 정확 일치: **100**
 * - localName 한글 매칭: **100**
 *
 * @param graph 검색 대상 프로젝트 그래프
 */
class ServiceEndpointCollector(
    private val graph: ProjectGraph
) : SubCollector {

    override fun search(query: AnalyzedQuery): List<ScoredCandidate> {
        val results = mutableMapOf<String, MutableList<String>>()

        for ((path, node) in graph.files.entries) {
            val endpoints = node.serviceEndpoints ?: continue

            // ── 1. ServiceId 정확 일치 ──
            for (sid in query.serviceIds) {
                val matched = endpoints.filter { it.serviceId == sid }
                if (matched.isNotEmpty()) {
                    results.getOrPut(path) { mutableListOf() }
                        .add("ServiceId 일치: $sid")
                }
            }

            // ── 2. localName 한글 매칭 ──
            for (noun in query.koreanNouns) {
                val matched = endpoints.filter { ep ->
                    ep.localName?.let { ln ->
                        ln.contains(noun) || noun.contains(ln)
                    } == true
                }
                if (matched.isNotEmpty()) {
                    val names = matched.mapNotNull { it.localName }.distinct()
                    results.getOrPut(path) { mutableListOf() }
                        .add("ServiceEndpoint localName 매칭: $noun ↔ ${names.joinToString()}")
                }
            }
        }

        return results.map { (filePath, reasons) ->
            ScoredCandidate(
                filePath = filePath,
                score = 100.0,
                matchedBy = reasons
            )
        }
    }
}
