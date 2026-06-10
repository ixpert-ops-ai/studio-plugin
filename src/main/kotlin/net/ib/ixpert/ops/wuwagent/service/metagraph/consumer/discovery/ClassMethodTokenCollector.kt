package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph

/**
 * 클래스명 및 메서드명 기반 토큰 매칭 서브 수집기.
 *
 * CamelCase 분해를 통해 클래스명과 메서드명에서 토큰을 추출한 뒤,
 * 질의의 한글 명사가 해당 토큰에 얼마나 매칭되는지를 명사 단위 비율로 산출합니다.
 *
 * **점수 산출 방식:**
 * - 기본 점수: `min(매칭된 명사 수 * 30.0, 60.0)`
 * - [AnalyzedQuery.exactIdentifiers]가 className에 부분 포함되면 추가 가중치 (+15점)
 *
 * **최소 임계값:** 점수가 20점 이상
 */
class ClassMethodTokenCollector(
    private val graph: ProjectGraph
) : SubCollector {

    private val dictionary = DomainDictionary.load(graph)

    override fun search(query: AnalyzedQuery): List<ScoredCandidate> {
        if (query.koreanNouns.isEmpty() && query.exactIdentifiers.isEmpty()) return emptyList()

        val results = mutableListOf<ScoredCandidate>()

        for ((path, node) in graph.files.entries) {
            val classTokens = DomainDictionary.tokenizeCamelCase(node.className).map { it.lowercase() }
            val methodTokens = node.methodNames.flatMap { DomainDictionary.tokenizeCamelCase(it).map { t -> t.lowercase() } }
            val allTokens = (classTokens + methodTokens).toSet()

            var score = 0.0
            val matchReasons = mutableListOf<String>()

            if (allTokens.isNotEmpty() && query.koreanNouns.isNotEmpty()) {
                var totalIdf = 0.0
                val totalDocs = graph.files.size + graph.resourceNodes.size
                val matchedNounsList = mutableListOf<String>()

                query.koreanNouns.forEach { noun ->
                    val translations = dictionary.translate(noun)
                    if (translations.any { trans -> tokenMatchesAny(trans, allTokens) }) {
                        val maxIdf = translations.maxOfOrNull { trans ->
                            val df = graph.documentFrequency[trans.lowercase()] ?: 1
                            Math.log(totalDocs.toDouble() / df.toDouble()).coerceAtLeast(0.1)
                        } ?: 1.0
                        totalIdf += maxIdf
                        matchedNounsList.add(noun)
                    }
                }
                
                if (matchedNounsList.isNotEmpty()) {
                    val nounScore = totalIdf * 30.0
                    score += nounScore
                    matchReasons.add("명사 매칭: ${matchedNounsList.joinToString(", ")} (IDF 점수: ${String.format("%.2f", nounScore)})")
                }
            }

            // exactIdentifiers가 className에 부분 포함되면 보너스
            val hasIdentifierMatch = query.exactIdentifiers.any { identifier ->
                node.className.contains(identifier, ignoreCase = true)
            }
            if (hasIdentifierMatch) {
                score += 15.0
                matchReasons.add("정확 식별자가 클래스명에 포함됨")
            }

            if (score >= 20.0) {
                results.add(
                    ScoredCandidate(
                        filePath = path,
                        score = score,
                        matchedBy = matchReasons
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
}
