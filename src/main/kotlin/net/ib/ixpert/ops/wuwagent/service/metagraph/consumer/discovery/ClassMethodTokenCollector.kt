package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph

/**
 * 클래스명 및 메서드명 기반 토큰 매칭 서브 수집기.
 *
 * CamelCase 분해를 통해 클래스명과 메서드명에서 토큰을 추출한 뒤,
 * [AnalyzedQuery.englishTokens]와의 교집합 비율로 점수를 산출합니다.
 *
 * **점수 산출 방식:**
 * - 기본 점수: `60 * (교집합 토큰 수 / 전체 후보 토큰 수)`
 * - [AnalyzedQuery.exactIdentifiers]가 className에 부분 포함되면 추가 가중치
 *
 * **최소 임계값:** 교집합 토큰 2개 이상 또는 교집합 비율 >= 0.3
 */
class ClassMethodTokenCollector(
    private val graph: ProjectGraph
) : SubCollector {

    override fun search(query: AnalyzedQuery): List<ScoredCandidate> {
        if (query.englishTokens.isEmpty()) return emptyList()

        val results = mutableListOf<ScoredCandidate>()

        for ((path, node) in graph.files.entries) {
            val classTokens = DomainDictionary.tokenizeCamelCase(node.className)
            val methodTokens = node.methodNames.flatMap { DomainDictionary.tokenizeCamelCase(it) }
            val allTokens = (classTokens + methodTokens).toSet()

            if (allTokens.isEmpty()) continue

            val intersection = allTokens.intersect(query.englishTokens)
            if (intersection.isEmpty()) continue

            val ratio = intersection.size.toDouble() / allTokens.size
            if (intersection.size < 2 && ratio < 0.3) continue

            var score = 60.0 * ratio

            // exactIdentifiers가 className에 부분 포함되면 보너스
            val hasIdentifierMatch = query.exactIdentifiers.any { identifier ->
                node.className.contains(identifier, ignoreCase = true)
            }
            if (hasIdentifierMatch) {
                score += 15.0
            }

            val matchReasons = mutableListOf<String>()
            matchReasons.add("클래스/메서드 토큰 매칭: ${intersection.joinToString(", ")} (비율: ${"%.2f".format(ratio)})")
            if (hasIdentifierMatch) {
                matchReasons.add("정확 식별자가 클래스명에 포함됨")
            }

            results.add(
                ScoredCandidate(
                    filePath = path,
                    score = score,
                    matchedBy = matchReasons
                )
            )
        }

        return results
    }
}
