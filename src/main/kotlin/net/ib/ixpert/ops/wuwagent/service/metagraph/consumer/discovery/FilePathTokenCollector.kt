package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph

/**
 * 파일 경로 기반 토큰 매칭 서브 수집기.
 *
 * 파일 경로를 `/`, `.` 구분자로 분리하고, 공통 세그먼트(src, main, java 등)를
 * 제거한 뒤 [AnalyzedQuery.englishTokens]와의 교집합 비율로 점수를 산출합니다.
 *
 * **점수 산출 방식:** `50 * (교집합 토큰 수 / 전체 경로 토큰 수)`
 *
 * **최소 임계값:** 교집합 비율 >= 0.3
 */
class FilePathTokenCollector(
    private val graph: ProjectGraph
) : SubCollector {

    override fun search(query: AnalyzedQuery): List<ScoredCandidate> {
        if (query.englishTokens.isEmpty()) return emptyList()

        val results = mutableListOf<ScoredCandidate>()

        for ((path, _) in graph.files.entries) {
            val pathTokens = tokenizePath(path)
            if (pathTokens.isEmpty()) continue

            val intersection = pathTokens.intersect(query.englishTokens)
            if (intersection.isEmpty()) continue

            val ratio = intersection.size.toDouble() / pathTokens.size
            if (ratio < 0.3) continue

            val score = 50.0 * ratio

            results.add(
                ScoredCandidate(
                    filePath = path,
                    score = score,
                    matchedBy = listOf(
                        "파일 경로 토큰 매칭: ${intersection.joinToString(", ")} (비율: ${"%.2f".format(ratio)})"
                    )
                )
            )
        }

        return results
    }

    companion object {
        /** 경로 토큰화 시 제외할 공통 세그먼트 */
        private val COMMON_SEGMENTS = setOf(
            "src", "main", "java", "kotlin", "com", "net", "org",
            "resources", "test", "webapp", "web", "inf"
        )

        /**
         * 파일 경로를 `/`, `.` 구분자로 분리하고 공통 세그먼트를 제거합니다.
         * 각 세그먼트는 소문자로 변환됩니다.
         */
        fun tokenizePath(path: String): Set<String> {
            return path.split('/', '\\', '.')
                .filter { it.length >= 2 }
                .map { it.lowercase() }
                .filter { it !in COMMON_SEGMENTS }
                .toSet()
        }
    }
}
