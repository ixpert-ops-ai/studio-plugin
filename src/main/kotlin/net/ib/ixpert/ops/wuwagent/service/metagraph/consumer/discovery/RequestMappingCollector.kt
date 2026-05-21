package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph

/**
 * RequestMapping(URL 경로) 기반 수집기.
 *
 * [ApiEndpoint.path][net.ib.ixpert.ops.wuwagent.service.metagraph.model.ApiEndpoint.path]를 대상으로
 * 두 가지 경로로 매칭합니다:
 * 1. [AnalyzedQuery.urlPatterns] → URL 경로 직접 포함 비교 (score **70**)
 * 2. [AnalyzedQuery.englishTokens] → URL 경로를 토큰 분해 후 교집합 비율로 점수 산출 (score **70 × ratio**)
 *
 * @param graph 검색 대상 프로젝트 그래프
 */
class RequestMappingCollector(
    private val graph: ProjectGraph
) : SubCollector {

    override fun search(query: AnalyzedQuery): List<ScoredCandidate> {
        val results = mutableMapOf<String, MutableList<Pair<Double, String>>>()

        for ((path, node) in graph.files.entries) {
            if (node.apiEndpoints.isEmpty()) continue

            for (endpoint in node.apiEndpoints) {
                val endpointPath = endpoint.path

                // ── 1. URL 패턴 직접 포함 비교 (score 70) ──
                for (url in query.urlPatterns) {
                    if (endpointPath.contains(url) || url.contains(endpointPath)) {
                        results.getOrPut(path) { mutableListOf() }
                            .add(70.0 to "URL 경로 매칭: $url ↔ $endpointPath")
                    }
                }

                // ── 2. 영어 토큰 교집합 비율 (score 70 × ratio) ──
                if (query.englishTokens.isNotEmpty()) {
                    val pathTokens = tokenizeUrlPath(endpointPath)
                    if (pathTokens.isNotEmpty()) {
                        val intersection = query.englishTokens.intersect(pathTokens)
                        if (intersection.isNotEmpty()) {
                            val ratio = intersection.size.toDouble() / query.englishTokens.size
                            val score = 70.0 * ratio
                            results.getOrPut(path) { mutableListOf() }
                                .add(score to "URL 토큰 교집합: ${intersection.joinToString()} (ratio=${"%.2f".format(ratio)})")
                        }
                    }
                }
            }
        }

        return results.map { (filePath, matches) ->
            ScoredCandidate(
                filePath = filePath,
                score = matches.maxOf { it.first },
                matchedBy = matches.map { it.second }
            )
        }
    }

    /**
     * URL 경로를 소문자 토큰 집합으로 분해합니다.
     *
     * 예: "/api/member-card/limit" → {"api", "member", "card", "limit"}
     *
     * 처리 순서:
     * 1. `/`, `-`, `_` 로 분리
     * 2. CamelCase 분해 ([DomainDictionary.tokenizeCamelCase])
     * 3. 경로 변수(`{id}` 등) 및 1자 토큰 제거
     */
    private fun tokenizeUrlPath(urlPath: String): Set<String> {
        return urlPath
            .split("/", "-", "_")
            .filter { it.isNotBlank() }
            .flatMap { segment ->
                // 경로 변수 제거 (예: {id}, {orderId})
                if (segment.startsWith("{") && segment.endsWith("}")) {
                    emptyList()
                } else {
                    DomainDictionary.tokenizeCamelCase(segment)
                }
            }
            .filter { it.length >= 2 }
            .toSet()
    }
}
