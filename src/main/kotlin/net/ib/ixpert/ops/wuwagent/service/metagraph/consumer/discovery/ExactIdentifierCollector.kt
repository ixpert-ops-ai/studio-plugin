package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph

/**
 * 정확 식별자 기반 수집기 — 가장 높은 우선순위.
 *
 * [AnalyzedQuery]에서 추출된 식별자(클래스명, ServiceId, URL, 메서드명)를
 * [ProjectGraph]의 각 [FileNode][net.ib.ixpert.ops.wuwagent.service.metagraph.model.FileNode]와
 * 직접 대조하여 매칭되는 파일을 수집합니다.
 *
 * 점수 기준:
 * - 클래스명 완전 일치: **200**
 * - ServiceId 완전 일치: **200**
 * - URL 경로 포함 매칭: **180**
 * - 메서드명 완전 일치: **160**
 * - 클래스명 부분 포함: **150**
 *
 * @param graph 검색 대상 프로젝트 그래프
 */
class ExactIdentifierCollector(
    private val graph: ProjectGraph
) : SubCollector {

    override fun search(query: AnalyzedQuery): List<ScoredCandidate> {
        val results = mutableMapOf<String, MutableList<Pair<Double, String>>>()

        for ((path, node) in graph.files.entries) {

            // ── 1. 클래스명 완전 일치 (score 200) ──
            for (id in query.exactIdentifiers) {
                if (node.className.equals(id, ignoreCase = true)) {
                    results.getOrPut(path) { mutableListOf() }
                        .add(200.0 to "클래스명 완전일치: $id")
                }
            }

            // ── 2. ServiceId 완전 일치 (score 200) ──
            for (sid in query.serviceIds) {
                if (node.serviceEndpoints?.any { it.serviceId == sid } == true) {
                    results.getOrPut(path) { mutableListOf() }
                        .add(200.0 to "ServiceId 일치: $sid")
                }
            }

            // ── 3. URL 경로 포함 매칭 (score 180) ──
            for (url in query.urlPatterns) {
                if (node.apiEndpoints.any { it.path.contains(url) || url.contains(it.path) }) {
                    results.getOrPut(path) { mutableListOf() }
                        .add(180.0 to "URL 경로 매칭: $url")
                }
            }

            // ── 4. 메서드명 완전 일치 (score 160) ──
            for (id in query.exactIdentifiers) {
                val matchInMethods = node.methodNames.any { it.equals(id, ignoreCase = true) }
                val matchInDem = node.demMethods?.any { it.methodName.equals(id, ignoreCase = true) } == true
                if (matchInMethods || matchInDem) {
                    results.getOrPut(path) { mutableListOf() }
                        .add(160.0 to "메서드명 일치: $id")
                }
            }

            // ── 5. 클래스명 부분 포함 (score 150) ──
            for (id in query.exactIdentifiers) {
                // 완전 일치는 위에서 이미 처리하므로 부분 포함만 체크
                if (!node.className.equals(id, ignoreCase = true) &&
                    node.className.contains(id, ignoreCase = true)
                ) {
                    results.getOrPut(path) { mutableListOf() }
                        .add(150.0 to "클래스명 부분포함: $id")
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
}
