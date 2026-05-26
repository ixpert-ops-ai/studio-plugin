package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph

/**
 * 한글 주석 기반 매칭 서브 수집기.
 *
 * [FileNode.koreanComments]에서 [AnalyzedQuery.koreanNouns]와
 * 매칭되는 명사를 찾아 점수를 산출합니다.
 *
 * **점수 산출 방식:** `80 * (매칭된 명사 수 / 전체 쿼리 명사 수)`
 *
 * **최소 임계값:** 매칭된 명사 1개 이상
 */
class KoreanCommentCollector(
    private val graph: ProjectGraph
) : SubCollector {

    override fun search(query: AnalyzedQuery): List<ScoredCandidate> {
        if (query.koreanNouns.isEmpty()) return emptyList()

        val results = mutableListOf<ScoredCandidate>()

        for ((path, node) in graph.files.entries) {
            if (node.koreanComments.isEmpty()) continue

            // 모든 주석을 하나의 텍스트로 합쳐서 검색
            val commentText = node.koreanComments.joinToString(" ")

            val matchedNouns = query.koreanNouns.filter { noun ->
                commentText.contains(noun)
            }

            if (matchedNouns.isEmpty()) continue

            val ratio = matchedNouns.size.toDouble() / query.koreanNouns.size
            val score = 80.0 * ratio

            results.add(
                ScoredCandidate(
                    filePath = path,
                    score = score,
                    matchedBy = listOf(
                        "한글 주석 매칭: ${matchedNouns.joinToString(", ")} (${matchedNouns.size}/${query.koreanNouns.size})"
                    )
                )
            )
        }

        return results
    }
}
