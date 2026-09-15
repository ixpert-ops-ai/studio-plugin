package net.ib.ixpert.ops.wuwagent.agent.clarify

import net.ib.ixpert.ops.wuwagent.agent.clarify.model.*
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FileNode
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.RelationshipType
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.SpringFileType

/**
 * 형제 유추(Brother Analogy) 스캐너.
 * v1.1 스펙 준수:
 * 1단계: 공유 타입 브릿지(Shared DTO Bridge) 위상 식별 (허브 필터링)
 * 2단계: 위상 응집 서브그래프 회수 (Category B, 저신뢰 버킷)
 * 3단계: 구조 슬롯 제안 (Category A, 1층 구조 제안)
 */
class BrotherAnalogyScanner(
    private val graph: ProjectGraph,
    private val maxBridgeDegree: Int = 15,
    private val maxExternalShared: Int = 3,
    private val maxClusterHops: Int = 2
) {

    private val LAYER_NAMES = setOf(
        // Standard Spring MVC / Spring Boot layers
        "service", "dao", "repository", "dto", "vo", "controller", "impl", "entity", "model", "rest", "api", "mapper",
        "request", "response",
        // Period chain AP layers (Anyframe AP / 기간계 AP 표준)
        "svc", "svo", "biz", "bvo", "dem", "dqm", "dvo", "util", "bizutil"
    )

    /**
     * 시드 노드들로부터 형제 클러스터 및 구조 슬롯을 유추 탐색한다.
     */
    fun scanSiblings(
        seedNodes: List<FileNode>,
        frozenIds: Set<String> = emptySet()
    ): List<RequirementItem> {
        if (seedNodes.isEmpty()) return emptyList()

        val seedPaths = seedNodes.map { it.path }.toSet()

        // 1단계: 공유 타입 브릿지 DTO 식별
        val bridgeDtos = identifySharedBridges(seedPaths)
        if (bridgeDtos.isEmpty()) return emptyList()

        val items = mutableListOf<RequirementItem>()
        val recoveredCategoryBNodes = mutableSetOf<FileNode>()

        val coreCohesiveNodes = mutableSetOf<FileNode>()

        // 2단계: 위상 응집 서브그래프 회수 (Category B)
        for (bridge in bridgeDtos) {
            val externalCallers = getExternalCallers(bridge.path, seedPaths)
            for (callerPath in externalCallers) {
                val callerNode = graph.files[callerPath] ?: continue
                val clusterResult = expandCohesiveCluster(callerNode)
                coreCohesiveNodes.addAll(clusterResult.coreNodes)
                val allClusterNodes = clusterResult.coreNodes + clusterResult.leafNodes

                for (node in allClusterNodes) {
                    val hint = LinkHint.ExistingRef(node.path)
                    val statement = "형제 클러스터 [${node.path.substringAfterLast("/")}] 파일에서 관련 처리를 수행해야 한다."
                    val id = RequirementItem.deriveId(hint, statement)

                    if (frozenIds.contains(id)) continue

                    items.add(
                        RequirementItem(
                            id = id,
                            statement = statement,
                            source = HintSource.SYSTEM_UNCONFIRMED,
                            hint = hint,
                            anchorRationale = "공유 타입 브릿지(${bridge.path.substringAfterLast("/")})를 통한 형제 클러스터 위상 회수",
                            verdict = Verdict.PENDING,
                            confidence = ConfidenceBucket.LOW_CONFIDENCE,
                            provenanceSignals = setOf(ProvenanceSignal.SIBLING_ANALOGY)
                        )
                    )
                }
            }
        }

        // 3단계: 구조 슬롯 제안 (Category A - 1층 구조 제안)
        // 외부 리프 노드(IbCenterApiService 등)를 배제하고, 동일 서브시스템 코어 응집 노드(비-DTO 실행 컴포넌트)로만 템플릿 슬롯 구성
        val executableCoreNodes = coreCohesiveNodes.filter { 
            it.fileType != SpringFileType.DTO && it.fileType != SpringFileType.ENTITY 
        }
        if (executableCoreNodes.size >= 2) {
            val templateComponentNames = executableCoreNodes.map { it.path.substringAfterLast("/") }
            val statement = "기존 형제 클러스터(${templateComponentNames.take(3).joinToString(", ")} 등)와 동일한 신규 컴포넌트 생성이 필요할 수 있습니다."
            val hint = LinkHint.NewCreation
            val id = RequirementItem.deriveId(hint, statement)

            if (!frozenIds.contains(id)) {
                val slotProposal = StructuralSlotProposal(
                    slotType = "COHESIVE_SIBLING_CLUSTER",
                    templateComponents = templateComponentNames,
                    description = "기존 형제 클러스터의 위상 구조를 복제한 신규 컴포넌트 생성 제안"
                )

                items.add(
                    RequirementItem(
                        id = id,
                        statement = statement,
                        source = HintSource.SYSTEM_UNCONFIRMED,
                        hint = hint,
                        anchorRationale = "형제 클러스터(${templateComponentNames.take(3).joinToString()})의 구조적 템플릿 기반 제안",
                        verdict = Verdict.PENDING,
                        confidence = ConfidenceBucket.LOW_CONFIDENCE,
                        provenanceSignals = setOf(ProvenanceSignal.SIBLING_ANALOGY),
                        structuralSlotProposal = slotProposal
                    )
                )
            }
        }

        // 중복 id 제거
        return items.distinctBy { it.id }
    }

    /**
     * 1단계: 시드 노드가 참조하는 DTO 중 외부 도메인에서 공유되고 허브 필터를 통과하는 브릿지 식별
     */
    private fun identifySharedBridges(seedPaths: Set<String>): List<FileNode> {
        val candidateDtoPaths = mutableSetOf<String>()

        for (rel in graph.relationships) {
            if (seedPaths.contains(rel.source) && rel.type == RelationshipType.USES_TYPE) {
                val targetNode = graph.files[rel.target]
                if (targetNode != null) {
                    candidateDtoPaths.add(targetNode.path)
                }
            }
        }

        val confirmedBridges = mutableListOf<FileNode>()

        for (dtoPath in candidateDtoPaths) {
            val allIncoming = graph.relationships.filter { it.target == dtoPath }
            val inDegree = allIncoming.size
            val distinctCallers = allIncoming.map { it.source }.distinct()

            val seedCallersForDto = distinctCallers.filter { seedPaths.contains(it) }
            val internalDomainPackages = seedCallersForDto.map { extractDomainPackage(it) }.distinct()

            val externalCallers = distinctCallers.filter { caller ->
                val callerDomain = extractDomainPackage(caller)
                !internalDomainPackages.contains(callerDomain)
            }
            val externalPackages = externalCallers.map { extractPackage(it) }.distinct()

            // 허브 필터링: inDegree <= maxBridgeDegree && 1 <= externalPackages.size <= maxExternalShared
            if (inDegree <= maxBridgeDegree && externalPackages.isNotEmpty() && externalPackages.size <= maxExternalShared) {
                graph.files[dtoPath]?.let { confirmedBridges.add(it) }
            }
        }

        return confirmedBridges
    }

    /**
     * 특정 DTO 브릿지를 참조하는 외부 도메인 호출자 목록
     */
    private fun getExternalCallers(dtoPath: String, seedPaths: Set<String>): List<String> {
        val allIncoming = graph.relationships.filter { it.target == dtoPath }
        val distinctCallers = allIncoming.map { it.source }.distinct()
        val seedCallersForDto = distinctCallers.filter { seedPaths.contains(it) }
        val internalDomainPackages = seedCallersForDto.map { extractDomainPackage(it) }.distinct()

        return distinctCallers.filter { caller ->
            val callerDomain = extractDomainPackage(caller)
            !internalDomainPackages.contains(callerDomain)
        }
    }

    private fun extractDomainPackage(path: String): String {
        val pkg = path.substringBeforeLast("/")
        val segments = pkg.split("/").toMutableList()
        while (segments.isNotEmpty() && LAYER_NAMES.contains(segments.last().lowercase())) {
            segments.removeAt(segments.size - 1)
        }
        return segments.joinToString("/")
    }

    /**
     * 2단계: 앵커 노드로부터 동일 서브시스템/패키지 내에서 허용된 엣지(INJECTS, CALLS, IMPLEMENTS)로 직결된 응집 클러스터 확장
     */
    private fun expandCohesiveCluster(anchor: FileNode): ClusterExpansionResult {
        val coreNodes = mutableSetOf<FileNode>()
        val leafNodes = mutableSetOf<FileNode>()
        val anchorPkg = extractPackage(anchor.path)
        coreNodes.add(anchor)

        // 1~2 hop 확장: 동일 패키지 내 양방향 확장 + 앵커 패키지 직결 1-hop 리프 확장
        val visited = mutableSetOf(anchor.path)
        var currentFrontier = setOf(anchor.path)

        for (hop in 1..maxClusterHops) {
            val nextFrontier = mutableSetOf<String>()
            for (curr in currentFrontier) {
                val currPkg = extractPackage(curr)

                // Outgoing 엣지
                val outRels = graph.relationships.filter { 
                    it.source == curr && (it.type == RelationshipType.INJECTS || it.type == RelationshipType.CALLS || it.type == RelationshipType.IMPLEMENTS)
                }
                for (rel in outRels) {
                    val targetNode = graph.files[rel.target]
                    if (targetNode != null && !visited.contains(targetNode.path)) {
                        val targetPkg = extractPackage(targetNode.path)
                        if (targetPkg == anchorPkg) {
                            visited.add(targetNode.path)
                            coreNodes.add(targetNode)
                            nextFrontier.add(targetNode.path)
                        } else if (currPkg == anchorPkg && (rel.type == RelationshipType.INJECTS || rel.type == RelationshipType.IMPLEMENTS || rel.type == RelationshipType.CALLS)) {
                            // 앵커 패키지에서 나가는 1-hop 외부 의존성은 리프로만 포함하고 외곽으로 더 전파하지 않음
                            visited.add(targetNode.path)
                            leafNodes.add(targetNode)
                        }
                    }
                }

                // Incoming 엣지 (동일 패키지 내부 역방향 INJECTS/CALLS만 확장)
                val inRels = graph.relationships.filter { 
                    it.target == curr && (it.type == RelationshipType.INJECTS || it.type == RelationshipType.CALLS)
                }
                for (rel in inRels) {
                    val sourceNode = graph.files[rel.source]
                    if (sourceNode != null && !visited.contains(sourceNode.path)) {
                        val sourcePkg = extractPackage(sourceNode.path)
                        if (sourcePkg == anchorPkg) {
                            visited.add(sourceNode.path)
                            coreNodes.add(sourceNode)
                            nextFrontier.add(sourceNode.path)
                        }
                    }
                }
            }
            currentFrontier = nextFrontier
        }

        return ClusterExpansionResult(coreNodes, leafNodes)
    }

    private fun extractPackage(path: String): String {
        return path.substringBeforeLast("/")
    }

    private data class ClusterExpansionResult(
        val coreNodes: Set<FileNode>,
        val leafNodes: Set<FileNode>
    )
}
