package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import com.intellij.openapi.project.Project
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.agent.JcefL1ClarificationBridge
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraphQueryable

object AdaptiveFileDiscovery {

    /**
     * 탐색·점수·신규 파일 탐지에 쓰는 SR 문장(B-84 2). 전에는 "$primaryReq\n$secondaryReq"라서 secondaryReq가 비면 끝에 줄바꿈이 남았고
     * 탐색기 메시지에 "\n\n\n"이 생겼다(그 한 줄 때문에 IDE와 하네스의 탐색 결과가 갈렸다, B-83). 각 부분을 다듬고 빈 부분은 뺀 뒤 한 줄바꿈으로 잇는다.
     */
    internal fun buildSrText(primaryReq: String, secondaryReq: String): String =
        listOf(primaryReq, secondaryReq).map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")

    fun filter(
        primaryReq: String,
        secondaryReq: String,
        graph: ProjectGraphQueryable,
        client: LLMClient,
        project: Project?,
        projectBasePath: String? = null,
        enhancedRequirements: List<String> = emptyList(),
        seedSelector: SeedSelector? = null,
        factsBlock: String? = null,
        onProgress: ((String) -> Unit)? = null
    ): DiscoveryResult {
        
        onProgress?.invoke("> **(Stage 1) Discovery 시작**\n요구사항 텍스트 기반 Seed 식별 시작...")
        val srText = buildSrText(primaryReq, secondaryReq)
        // Phase 1: Seed Selection
        // 테스트 환경 등에서는 MockSeedSelector가 주입될 수 있으나, 프로덕션에서는 AgenticSeedSelector 사용
        val defaultBridge = if (project != null) JcefL1ClarificationBridge(project) else null
        val selector: SeedSelector = seedSelector ?: AgenticSeedSelector(
            llmClient = client,
            projectBasePath = projectBasePath ?: project?.basePath,
            clarificationBridge = defaultBridge,
            factsBlock = factsBlock
        )
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
            reasoning = seedResult.reasoning,
            rawCandidates = seedResult.rawCandidates,
            judgePicks = seedResult.judgePicks,
            frontendFileHints = seedResult.frontendFileHints ?: emptyList(),
            usedFallback = seedResult.usedFallback
        )

        return DiscoveryResult(
            relevantFiles = relevantFiles,
            suggestedNewFiles = suggestedNewFiles,
            metadata = metadata
        )
    }
}
