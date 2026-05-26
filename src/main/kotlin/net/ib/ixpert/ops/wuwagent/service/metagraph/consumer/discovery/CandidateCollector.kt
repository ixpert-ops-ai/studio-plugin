package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import com.intellij.openapi.diagnostic.Logger
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph

/**
 * 통합 후보 수집 오케스트레이터.
 *
 * 그래프 메타데이터 존재 여부에 따라 서브 수집기를 동적으로 활성화하고,
 * 각 수집기의 결과를 `filePath` 기준으로 점수를 합산합니다.
 * 상위 진입점에서 BFS 확장을 수행하여 관련 파일을 추가로 발견합니다.
 */
class CandidateCollector(
    private val graph: ProjectGraph
) {
    private val logger = Logger.getInstance(CandidateCollector::class.java)
    private val dictionary = DomainDictionary.load(graph)
    val queryAnalyzer = QueryAnalyzer(dictionary)

    private val collectors: List<SubCollector> = buildList {
        // 항상 최우선 활성: 정확 식별자 매칭
        add(ExactIdentifierCollector(graph))

        // 항상 활성 (프레임워크 무관)
        add(ClassMethodTokenCollector(graph))
        add(FilePathTokenCollector(graph))

        // 메타데이터 존재 시 조건부 활성
        if (graph.files.values.any { !it.serviceEndpoints.isNullOrEmpty() }) {
            add(ServiceEndpointCollector(graph))
            logger.info("CandidateCollector: ServiceEndpointCollector 활성화")
        }
        if (graph.files.values.any { it.localName != null }) {
            add(LocalNameCollector(graph))
            logger.info("CandidateCollector: LocalNameCollector 활성화")
        }
        if (graph.files.values.any { !it.demMethods.isNullOrEmpty() }) {
            add(DemMethodCollector(graph))
            logger.info("CandidateCollector: DemMethodCollector 활성화")
        }
        if (graph.files.values.any { it.apiEndpoints.isNotEmpty() }) {
            add(ApiEndpointCollector(graph))
            logger.info("CandidateCollector: ApiEndpointCollector 활성화")
        }
        if (graph.files.values.any { it.koreanComments.isNotEmpty() }) {
            add(KoreanCommentCollector(graph))
            logger.info("CandidateCollector: KoreanCommentCollector 활성화")
        }
    }

    /**
     * 사용자 쿼리를 분석하고, 모든 활성 수집기를 실행하여 후보 파일 목록을 반환합니다.
     *
     * @param userQuery 사용자 원본 입력
     * @return 점수 내림차순 정렬된 상위 후보 리스트 (최대 [MAX_CANDIDATES]건)
     */
    fun collect(userQuery: String): List<ScoredCandidate> {
        val analyzed = queryAnalyzer.analyze(userQuery)
        logger.info("CandidateCollector: 분석 결과 → " +
            "koreanNouns=${analyzed.koreanNouns}, " +
            "englishTokens=${analyzed.englishTokens.take(10)}, " +
            "exactIdentifiers=${analyzed.exactIdentifiers}, " +
            "urlPatterns=${analyzed.urlPatterns}, " +
            "serviceIds=${analyzed.serviceIds}")

        val candidates = mutableMapOf<String, ScoredCandidate>()

        collectors.forEach { collector ->
            val results = collector.search(analyzed)
            if (results.isNotEmpty()) {
                logger.info("CandidateCollector: ${collector::class.simpleName} → ${results.size}건 매칭")
            }
            results.forEach { result ->
                candidates.merge(result.filePath, result) { existing, new ->
                    ScoredCandidate(
                        filePath = existing.filePath,
                        score = existing.score + new.score,
                        matchedBy = existing.matchedBy + new.matchedBy
                    )
                }
            }
        }

        // BFS 확장: 상위 진입점에서 관계 기반 파일 추가 탐색
        val entryPoints = candidates.values.sortedByDescending { it.score }.take(BFS_ENTRY_POINT_COUNT)
        if (entryPoints.isNotEmpty()) {
            val expanded = expandByRelationships(entryPoints, depth = BFS_DEPTH)
            expanded.forEach { result ->
                candidates.merge(result.filePath, result) { existing, new ->
                    ScoredCandidate(
                        filePath = existing.filePath,
                        score = existing.score + new.score,
                        matchedBy = existing.matchedBy + new.matchedBy
                    )
                }
            }
            logger.info("CandidateCollector: BFS 확장 → ${expanded.size}건 추가")
        }

        val sorted = candidates.values.map { candidate ->
            val node = graph.files[candidate.filePath]
            if (node != null) {
                val typeName = node.fileType.name
                val layerName = node.layer.name
                
                val shouldCap = (typeName in setOf("UTIL", "ABSTRACT_CLASS", "ENUM")) ||
                    (typeName == "DTO" && layerName == "COMMON") ||
                    (node.className in setOf("ApiResponse", "BaseEntity", "BusinessException"))

                if (shouldCap && candidate.score > 30.0) {
                    candidate.copy(score = 30.0, matchedBy = candidate.matchedBy + "Capped:UTIL")
                } else candidate
            } else candidate
        }.sortedByDescending { it.score }.take(MAX_CANDIDATES)
        
        logger.info("CandidateCollector: 최종 ${sorted.size}건 후보 (총 ${candidates.size}건 중)")
        return sorted
    }

    /**
     * BFS 기반 관계 확장.
     * 진입점 파일에서 dependsOn/dependedBy/IMPLEMENTS 관계를 따라
     * depth만큼 탐색하며, 거리에 비례해 점수를 감소시킵니다.
     */
    private fun expandByRelationships(
        entryPoints: List<ScoredCandidate>,
        depth: Int
    ): List<ScoredCandidate> {
        val visited = entryPoints.map { it.filePath }.toMutableSet()
        val results = mutableListOf<ScoredCandidate>()
        var currentLevel = entryPoints.map { it.filePath to it.score }

        for (d in 1..depth) {
            val decayFactor = Math.pow(BFS_DECAY_RATE, d.toDouble())
            val nextLevel = mutableListOf<Pair<String, Double>>()

            for ((filePath, parentScore) in currentLevel) {
                val node = graph.files[filePath] ?: continue

                // 순방향 의존 (dependsOn)
                for (dep in node.dependsOn) {
                    if (dep !in visited && graph.files.containsKey(dep)) {
                        visited.add(dep)
                        val score = parentScore * decayFactor
                        results.add(ScoredCandidate(dep, score, listOf("BFS:depth=$d")))
                        nextLevel.add(dep to score)
                    }
                }

                // 역방향 의존 (dependedBy) — 허브 노드 방어: MAX_DEPENDED_BY 초과 시 스킵
                if (node.dependedBy.size <= MAX_DEPENDED_BY_FOR_EXPANSION) {
                    for (dep in node.dependedBy) {
                        if (dep !in visited && graph.files.containsKey(dep)) {
                            visited.add(dep)
                            val score = parentScore * decayFactor
                            results.add(ScoredCandidate(dep, score, listOf("BFS:depth=$d,reverse")))
                            nextLevel.add(dep to score)
                        }
                    }
                }

                // IMPLEMENTS 관계
                val implRelations = graph.relationships.filter {
                    (it.source == filePath || it.target == filePath) &&
                    it.type.name == "IMPLEMENTS"
                }
                for (rel in implRelations) {
                    val other = if (rel.source == filePath) rel.target else rel.source
                    if (other !in visited && graph.files.containsKey(other)) {
                        visited.add(other)
                        val score = parentScore * decayFactor
                        results.add(ScoredCandidate(other, score, listOf("BFS:depth=$d,impl")))
                        nextLevel.add(other to score)
                    }
                }
            }

            currentLevel = nextLevel
            if (currentLevel.isEmpty()) break
        }

        return results
    }

    companion object {
        /** 최종 반환 후보 수 상한 */
        const val MAX_CANDIDATES = 30

        /** BFS 진입점 수 */
        private const val BFS_ENTRY_POINT_COUNT = 5

        /** BFS 탐색 깊이 */
        private const val BFS_DEPTH = 2

        /** BFS 점수 감쇠율 (depth별) */
        private const val BFS_DECAY_RATE = 0.6

        /** 역방향 의존 확장 제한 (허브 노드 방어) */
        private const val MAX_DEPENDED_BY_FOR_EXPANSION = 8
    }
}
