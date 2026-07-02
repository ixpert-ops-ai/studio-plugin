package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import com.intellij.openapi.project.Project
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraphQueryable

object AdaptiveFileDiscovery {

    fun filter(
        primaryReq: String,
        secondaryReq: String,
        graph: ProjectGraphQueryable,
        client: LLMClient,
        project: Project?,
        enhancedRequirements: List<String> = emptyList(),
        seedSelector: SeedSelector? = null,
        onProgress: ((String) -> Unit)? = null
    ): DiscoveryResult {
        
        onProgress?.invoke("> **(Stage 1) Discovery 시작**\n요구사항 텍스트 기반 Seed 식별 시작...")
        val srText = "$primaryReq\n$secondaryReq"
        // Phase 1: Seed Selection
        // 테스트 환경 등에서는 MockSeedSelector가 주입될 수 있으나, 프로덕션에서는 LlmSeedSelector 사용
        val selector: SeedSelector = seedSelector ?: LlmSeedSelector(client)
        val seedResult = selector.selectSeeds(srText, graph)

        onProgress?.invoke("식별된 Seed 바탕으로 그래프 탐색 중... (${seedResult.seedClasses.size}개 발견)")

        // Phase 2: Graph Expansion (2~3 Hops)
        val domainExtractor = DomainExtractor(graph.files)
        val config = DiscoveryConfig(maxHop = 3)
        val expander = GraphExpander(graph, domainExtractor, config)
        val expandedFiles = expander.expand(seedResult, srText)

        onProgress?.invoke("탐색된 파일 연관성 평가 중... (${expandedFiles.size}개 평가 대상)")

        // Phase 3: Relevance Scoring & Filtering
        val limit = 30
        val scorer = RelevanceScorer(graph, fileLimit = limit, minScore = 55)
        val relevantFiles = scorer.scoreAndFilter(srText, expandedFiles, seedResult).take(limit)

        // Phase 4: New File Detection
        val newFileDetector = NewFileDetector(client)
        val suggestedNewFiles = newFileDetector.detectNewFiles(srText, seedResult, relevantFiles, enhancedRequirements)

        onProgress?.invoke("최종 ${relevantFiles.size}개 파일 선정 완료.")

        // Metadata 기록
        val metadata = DiscoveryMetadata(
            seedClasses = seedResult.seedClasses,
            changeIntent = seedResult.changeIntent,
            layerHint = seedResult.layerHint,
            frontendRelevant = seedResult.frontendRelevant,
            totalCandidates = expandedFiles.size,
            filteredTo = relevantFiles.size,
            llmTokensUsed = 0, // Mock LLM Token Count (임시)
            expansionTrace = expandedFiles,
            reasoning = seedResult.reasoning
        )

        return DiscoveryResult(
            relevantFiles = relevantFiles,
            suggestedNewFiles = suggestedNewFiles,
            metadata = metadata
        )
    }
}
