package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph

/**
 * 파일 경로 기반 토큰 매칭 서브 수집기.
 *
 * 파일 경로를 분리한 토큰이 질의의 한글 명사와 얼마나 교차하는지 명사 단위 비율로 계산합니다.
 *
 * **점수 산출 방식:** `min(매칭된 명사 수 * 25.0, 50.0)`
 *
 * **최소 임계값:** 점수 15점 이상
 */
class FilePathTokenCollector(
    private val graph: ProjectGraph
) : SubCollector {

    private val dictionary = DomainDictionary.load(graph)

    override fun search(query: AnalyzedQuery): List<ScoredCandidate> {
        if (query.koreanNouns.isEmpty()) return emptyList()

        val results = mutableListOf<ScoredCandidate>()

        for ((path, _) in graph.files.entries) {
            val pathTokens = tokenizePath(path)
            if (pathTokens.isEmpty()) continue

            val matchedNouns = query.koreanNouns.count { noun ->
                val translations = dictionary.translate(noun)
                translations.any { trans -> tokenMatchesAny(trans, pathTokens) }
            }
            
            if (matchedNouns == 0) continue

            val score = minOf(matchedNouns * 25.0, 50.0)

            if (score >= 15.0) {
                results.add(
                    ScoredCandidate(
                        filePath = path,
                        score = score,
                        matchedBy = listOf(
                            "파일 경로 명사 매칭: $matchedNouns개 (점수: $score)"
                        )
                    )
                )
            }
        }

        return results
    }

    private fun tokenMatchesAny(token: String, candidates: Collection<String>): Boolean {
        return candidates.any { candidate ->
            token == candidate ||
            token == candidate + "s" ||
            token.removeSuffix("s") == candidate
        }
    }

    companion object {
        private val COMMON_SEGMENTS = setOf(
            "src", "main", "java", "kotlin", "com", "net", "org",
            "resources", "test", "webapp", "web", "inf"
        )

        fun tokenizePath(path: String): Set<String> {
            return path.split('/', '\\', '.')
                .filter { it.length >= 2 }
                .map { it.lowercase() }
                .filter { it !in COMMON_SEGMENTS }
                .toSet()
        }
    }
}
