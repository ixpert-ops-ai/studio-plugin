package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph

/**
 * Spring REST Controller의 ApiEndpoint 메타데이터를 기반으로 매칭합니다.
 * URL 패턴, HTTP 동사, Handler 메서드명을 통합 분석하여 최대 90점을 부여합니다.
 */
class ApiEndpointCollector(
    private val graph: ProjectGraph
) : SubCollector {

    private val dictionary = DomainDictionary.load(graph)

    override fun search(query: AnalyzedQuery): List<ScoredCandidate> {
        val candidates = mutableListOf<ScoredCandidate>()

        for ((filePath, node) in graph.files.entries) {
            val endpoints = node.apiEndpoints
            if (endpoints.isEmpty()) continue

            // 해당 파일 내에서 가장 높은 점수를 가진 엔드포인트를 찾습니다.
            val bestScore = endpoints.maxOfOrNull { ep ->
                var score = 0.0
                
                // 1. URL 토큰 매칭 (최대 40점)
                // URL에서 경로 변수 {id} 등을 제외하고 토큰화
                val urlTokens = ep.path.split("/", "-", "_", "{", "}").filter { it.length >= 2 }.map { it.lowercase() }
                if (urlTokens.isNotEmpty() && query.koreanNouns.isNotEmpty()) {
                    val urlMatch = query.koreanNouns.count { noun ->
                        val translations = dictionary.translate(noun)
                        translations.any { trans -> tokenMatchesAny(trans, urlTokens) }
                    }
                    score += urlMatch.toDouble() / query.koreanNouns.size * 40.0
                }

                // 2. HTTP Method 매칭 (최대 20점)
                val verbMatch = when (ep.httpMethod?.uppercase()) {
                    "POST" -> query.koreanNouns.any { it in listOf("등록", "생성", "추가", "전송") }
                    "GET" -> query.koreanNouns.any { it in listOf("조회", "검색", "목록", "상세") }
                    "PUT", "PATCH" -> query.koreanNouns.any { it in listOf("수정", "변경", "업데이트") }
                    "DELETE" -> query.koreanNouns.any { it in listOf("삭제", "제거") }
                    else -> false
                }
                if (verbMatch) score += 20.0

                // 3. Handler Method 토큰 매칭 (최대 30점)
                val methodTokens = DomainDictionary.tokenizeCamelCase(ep.handlerMethod).toSet()
                if (methodTokens.isNotEmpty() && query.koreanNouns.isNotEmpty()) {
                    val methodMatch = query.koreanNouns.count { noun ->
                        val translations = dictionary.translate(noun)
                        translations.any { trans -> tokenMatchesAny(trans, methodTokens) }
                    }
                    score += methodMatch.toDouble() / query.koreanNouns.size * 30.0
                }

                score
            } ?: 0.0

            if (bestScore >= 25.0) {
                candidates.add(ScoredCandidate(filePath, bestScore, listOf("ApiEndpoint:score=${bestScore.toInt()}")))
            }
        }

        return candidates
    }

    /**
     * REST API URL 패턴의 단복수 불일치(product vs products)를 허용하며 일치 여부를 검사합니다.
     */
    private fun tokenMatchesAny(token: String, candidates: Collection<String>): Boolean {
        return candidates.any { candidate ->
            token == candidate ||
            token == candidate + "s" ||
            token.removeSuffix("s") == candidate
        }
    }
}
