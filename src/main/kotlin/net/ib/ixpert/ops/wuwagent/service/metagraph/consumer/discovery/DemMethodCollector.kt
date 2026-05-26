package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph

/**
 * DEM 메서드 정보 기반 매칭 서브 수집기.
 *
 * [FileNode.demMethods]가 존재하는 노드를 대상으로 두 가지 경로로 매칭합니다:
 *
 * 1. **한글 경로**: [AnalyzedQuery.koreanNouns]와 [DemMethodInfo.localName]을 비교
 *    - 정확 일치: 점수 90
 *    - 부분 겹침: 40~80 (겹치는 비율에 비례)
 *
 * 2. **영어 경로**: [AnalyzedQuery.englishTokens]와 [DemMethodInfo.methodName]을
 *    CamelCase 분해 후 교집합 비율로 점수 산출
 *    - 점수: `60 * (교집합 비율)`
 *
 * 두 경로 중 높은 점수를 해당 파일의 최종 점수로 채택합니다.
 */
class DemMethodCollector(
    private val graph: ProjectGraph
) : SubCollector {

    override fun search(query: AnalyzedQuery): List<ScoredCandidate> {
        if (query.koreanNouns.isEmpty() && query.englishTokens.isEmpty()) return emptyList()

        val results = mutableListOf<ScoredCandidate>()

        for ((path, node) in graph.files.entries) {
            val demMethods = node.demMethods
            if (demMethods.isNullOrEmpty()) continue

            var bestScore = 0.0
            val matchReasons = mutableListOf<String>()

            for (dem in demMethods) {
                // ── 한글 경로: koreanNouns vs localName ──
                dem.localName?.let { localName ->
                    if (localName.isNotBlank() && query.koreanNouns.isNotEmpty()) {
                        val koreanScore = computeKoreanScore(query.koreanNouns, localName)
                        if (koreanScore > bestScore) {
                            bestScore = koreanScore
                            matchReasons.clear()
                            matchReasons.add(
                                "DEM 한글명 매칭: '${dem.methodName}' (localName='$localName', 점수=${"%.1f".format(koreanScore)})"
                            )
                        }
                    }
                }

                // ── 영어 경로: englishTokens vs methodName 토큰 ──
                if (query.englishTokens.isNotEmpty()) {
                    val methodTokens = DomainDictionary.tokenizeCamelCase(dem.methodName).toSet()
                    if (methodTokens.isNotEmpty()) {
                        val intersection = methodTokens.intersect(query.englishTokens)
                        if (intersection.isNotEmpty()) {
                            val ratio = intersection.size.toDouble() / methodTokens.size
                            val engScore = 60.0 * ratio
                            if (engScore > bestScore) {
                                bestScore = engScore
                                matchReasons.clear()
                                matchReasons.add(
                                    "DEM 메서드 토큰 매칭: '${dem.methodName}' → ${intersection.joinToString(", ")} (비율: ${"%.2f".format(ratio)})"
                                )
                            }
                        }
                    }
                }
            }

            if (bestScore > 0.0) {
                results.add(
                    ScoredCandidate(
                        filePath = path,
                        score = bestScore,
                        matchedBy = matchReasons
                    )
                )
            }
        }

        return results
    }

    companion object {
        /**
         * 한글 명사 목록과 localName 간의 유사도를 점수로 환산합니다.
         *
         * - 정확 일치: 90점
         * - 부분 겹침: 40 + 40 * (겹치는 명사 수 / 전체 명사 수)
         */
        private fun computeKoreanScore(koreanNouns: List<String>, localName: String): Double {
            // 정확 일치 검사
            if (koreanNouns.any { it == localName }) return 90.0

            // 부분 겹침: localName에 포함되는 명사 수
            val matchCount = koreanNouns.count { noun ->
                localName.contains(noun) || noun.contains(localName)
            }

            return if (matchCount > 0) {
                40.0 + 40.0 * (matchCount.toDouble() / koreanNouns.size)
            } else {
                0.0
            }
        }
    }
}
