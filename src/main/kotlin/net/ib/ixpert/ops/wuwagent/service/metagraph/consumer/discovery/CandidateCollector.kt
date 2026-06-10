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

    private val controllerToResources: Map<String, List<net.ib.ixpert.ops.wuwagent.service.metagraph.model.ResourceNode>> by lazy {
        val map = mutableMapOf<String, MutableList<net.ib.ixpert.ops.wuwagent.service.metagraph.model.ResourceNode>>()
        for (rNode in graph.resourceNodes) {
            for (path in rNode.linkedTo) {
                map.getOrPut(path) { mutableListOf() }.add(rNode)
            }
        }
        map
    }

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
     * @param primaryQuery 사용자 원본 입력
     * @param secondaryQuery 보강된 요구사항 (가중치 0.3)
     * @return 점수 내림차순 정렬된 상위 후보 리스트 (최대 [MAX_CANDIDATES]건)
     */
    fun collect(primaryQuery: String, secondaryQuery: String = ""): List<ScoredCandidate> {
        val analyzed = queryAnalyzer.analyze(primaryQuery)
        val candidates = mutableMapOf<String, ScoredCandidate>()

        // 1. Primary 수집
        collectors.forEach { collector ->
            val results = collector.search(analyzed)
            if (results.isNotEmpty()) {
                logger.warn("CandidateCollector(Primary): ${collector::class.simpleName} → ${results.size}건 매칭")
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

        // 2. Secondary 수집 (가중치 0.3)
        if (secondaryQuery.isNotBlank()) {
            val secondaryAnalyzed = queryAnalyzer.analyze(secondaryQuery)
            // Primary에 없는 키워드만 추출
            val secondaryOnly = AnalyzedQuery(
                original = secondaryQuery,
                koreanNouns = secondaryAnalyzed.koreanNouns.filter { it !in analyzed.koreanNouns },
                englishTokens = secondaryAnalyzed.englishTokens.filter { it !in analyzed.englishTokens }.toSet(),
                exactIdentifiers = secondaryAnalyzed.exactIdentifiers.filter { it !in analyzed.exactIdentifiers },
                urlPatterns = secondaryAnalyzed.urlPatterns.filter { it !in analyzed.urlPatterns },
                serviceIds = secondaryAnalyzed.serviceIds.filter { it !in analyzed.serviceIds }
            )
            
            collectors.forEach { collector ->
                val results = collector.search(secondaryOnly)
                if (results.isNotEmpty()) {
                    logger.warn("CandidateCollector(Secondary): ${collector::class.simpleName} → ${results.size}건 매칭")
                }
                results.forEach { result ->
                    val discountedScore = result.score * 0.3
                    val discountedResult = ScoredCandidate(
                        filePath = result.filePath,
                        score = discountedScore,
                        matchedBy = result.matchedBy.map { "$it (secondary 0.3x)" }
                    )
                    candidates.merge(result.filePath, discountedResult) { existing, new ->
                        ScoredCandidate(
                            filePath = existing.filePath,
                            score = existing.score + new.score,
                            matchedBy = existing.matchedBy + new.matchedBy
                        )
                    }
                }
            }
        }

        // 3. ResourceNode 수집 (Phase 1)
        if (graph.resourceNodes.isNotEmpty()) {
            val searchTokens = analyzed.englishTokens + analyzed.koreanNouns + analyzed.exactIdentifiers
            val totalDocs = graph.files.size + graph.resourceNodes.size

            graph.resourceNodes.forEach { rNode ->
                var score = 0.0
                val matchedBy = mutableListOf<String>()
                
                val pathLower = rNode.path.lowercase()
                
                searchTokens.forEach { token ->
                    val tokenLower = token.lowercase()
                    val df = graph.documentFrequency[tokenLower] ?: 1
                    val idfWeight = Math.log(totalDocs.toDouble() / df.toDouble()).coerceAtLeast(0.1)

                    if (pathLower.contains(tokenLower)) {
                        // 프론트엔드 파일(JSP/JS 등)이 충분한 점수를 받도록 상향
                        val baseScore = 80.0
                        score += baseScore * idfWeight
                        matchedBy.add("ResourcePathMatch($token*${String.format("%.2f", idfWeight)})")
                    }
                    
                    val metaMatch = rNode.metadata.values.any { value -> 
                        if (value is List<*>) {
                            value.any { it.toString().lowercase().contains(tokenLower) }
                        } else {
                            value.toString().lowercase().contains(tokenLower)
                        }
                    }
                    if (metaMatch) {
                        val baseScore = 60.0
                        score += baseScore * idfWeight
                        matchedBy.add("ResourceMetadataMatch($token*${String.format("%.2f", idfWeight)})")
                    }
                }
                
                if (score > 0) {
                    // P5-B Phase 1.5: linkedTo 가산점 부여
                    if (rNode.linkedTo.isNotEmpty()) {
                        score += 60.0
                        matchedBy.add("HasLinkedTo")
                    }
                    if (rNode.dynamicBindings.isNotEmpty()) {
                        score += 40.0
                        matchedBy.add("IsDynamicBinding")
                    }

                    val candidate = ScoredCandidate(rNode.path, score, matchedBy.distinct())
                    candidates.merge(rNode.path, candidate) { existing, new ->
                        ScoredCandidate(existing.filePath, existing.score + new.score, (existing.matchedBy + new.matchedBy).distinct())
                    }
                }
            }
        }

        // (이하 BFS 확장은 동일하게 유지)

        // BFS 확장: 상위 진입점에서 관계 기반 파일 추가 탐색
        val entryPoints = candidates.values.sortedByDescending { it.score }.take(BFS_ENTRY_POINT_COUNT)
        if (entryPoints.isNotEmpty()) {
            val expanded = expandByRelationships(entryPoints, analyzed, depth = BFS_DEPTH)
            expanded.forEach { result ->
                candidates.merge(result.filePath, result) { existing, new ->
                    ScoredCandidate(
                        filePath = existing.filePath,
                        score = existing.score + new.score,
                        matchedBy = existing.matchedBy + new.matchedBy
                    )
                }
            }
            logger.warn("CandidateCollector: BFS 확장 → ${expanded.size}건 추가")
        }

        val mappedCandidates = candidates.values.map { candidate ->
            val node = graph.files[candidate.filePath]
            if (node != null) {
                val typeName = node.fileType.name
                
                // [P5-C Fix 2] UTIL 클래스의 30점 강제 삭감(Cap) 로직 제거
                val shouldCap = (typeName in setOf("ABSTRACT_CLASS", "ENUM")) ||
                    (node.className in setOf("ApiResponse", "BaseEntity", "BusinessException"))

                if (shouldCap && candidate.score > 30.0) {
                    candidate.copy(score = 30.0, matchedBy = candidate.matchedBy + "Capped:UTIL")
                } else if (typeName == "DTO" && candidate.score > 80.0) {
                    // DTO는 너무 높은 점수만 받지 않도록 80점으로 캡
                    candidate.copy(score = 80.0, matchedBy = candidate.matchedBy + "Capped:DTO")
                } else candidate
            } else candidate
        }

        val maxTotal = MAX_CANDIDATES // 40
        val targetFilesQuota = 25
        val targetResourcesQuota = 15

        val fileCandidates = mappedCandidates.filter { graph.files.containsKey(it.filePath) }.sortedByDescending { it.score }
        val resourceCandidates = mappedCandidates.filter { !graph.files.containsKey(it.filePath) }.sortedByDescending { it.score }

        val actualFilesQuota = minOf(fileCandidates.size, targetFilesQuota + maxOf(0, targetResourcesQuota - resourceCandidates.size))
        val actualResourcesQuota = minOf(resourceCandidates.size, targetResourcesQuota + maxOf(0, targetFilesQuota - fileCandidates.size))

        val finalFiles = fileCandidates.take(actualFilesQuota)
        val finalResources = resourceCandidates.take(actualResourcesQuota)

        val sorted = (finalFiles + finalResources).sortedByDescending { it.score }
        
        logger.warn("CandidateCollector: 최종 ${sorted.size}건 후보 (총 ${candidates.size}건 중, File:${finalFiles.size}, Resource:${finalResources.size})")
        return sorted
    }

    /**
     * BFS 기반 관계 확장.
     * 진입점 파일에서 dependsOn/dependedBy/IMPLEMENTS 관계를 따라
     * depth만큼 탐색하며, 거리에 비례해 점수를 감소시킵니다.
     */
    private fun expandByRelationships(
        entryPoints: List<ScoredCandidate>,
        analyzedQuery: AnalyzedQuery,
        depth: Int
    ): List<ScoredCandidate> {
        val visited = entryPoints.map { it.filePath }.toMutableSet()
        val results = mutableListOf<ScoredCandidate>()
        var currentLevel = entryPoints.map { it.filePath to it.score }

        for (d in 1..depth) {
            val decayFactor = Math.pow(BFS_DECAY_RATE, d.toDouble())
            val nextLevel = mutableListOf<Pair<String, Double>>()

            for ((filePath, parentScore) in currentLevel) {
                val node = graph.files[filePath]
                if (node == null) {
                    val rNode = graph.resourceNodes.find { it.path == filePath }
                    if (rNode != null) {
                        for (dep in rNode.linkedTo) {
                            if (dep !in visited && graph.files.containsKey(dep)) {
                                visited.add(dep)
                                val score = parentScore * decayFactor
                                results.add(ScoredCandidate(dep, score, listOf("BFS:depth=$d,resource_link")))
                                nextLevel.add(dep to score)
                            }
                        }
                    }
                    continue
                }

                // ResourceNode 역방향 의존 (FileNode -> ResourceNode)
                val linkedResources = controllerToResources[filePath]
                if (linkedResources != null) {
                    val searchTokens = analyzedQuery.englishTokens + analyzedQuery.koreanNouns + analyzedQuery.exactIdentifiers
                    
                    for (rNode in linkedResources) {
                        if (rNode.path !in visited) {
                            visited.add(rNode.path)
                            var score = parentScore * decayFactor
                            
                            // [P5-C Fix 3] 검색 키워드와 매칭되는 ResourceNode는 점수를 대폭 상향하여 확실한 동반 진입 보장
                            val rNodePathLower = rNode.path.lowercase()
                            val isRelevant = searchTokens.any { token -> rNodePathLower.contains(token.lowercase()) }
                            if (isRelevant) {
                                score += 200.0 // 상위 40위 내 진입을 위한 강력한 가점
                            }
                            
                            results.add(ScoredCandidate(rNode.path, score, listOf("BFS:depth=$d,view_binding${if(isRelevant) ",RelevantKeyword" else ""}")))
                            nextLevel.add(rNode.path to score)
                        }
                    }
                }

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
        const val MAX_CANDIDATES = 40

        /** BFS 진입점 수 */
        private const val BFS_ENTRY_POINT_COUNT = 10

        /** BFS 탐색 깊이 */
        private const val BFS_DEPTH = 3

        /** BFS 점수 감쇠율 (depth별) */
        private const val BFS_DECAY_RATE = 0.65

        /** 역방향 의존 확장 제한 (허브 노드 방어) */
        private const val MAX_DEPENDED_BY_FOR_EXPANSION = 30
    }
}
