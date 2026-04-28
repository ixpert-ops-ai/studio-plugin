package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FileNode
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph

/**
 * 추출된 서브그래프 정보.
 * 포매터(SubGraphFormatter)가 읽기 쉬운 계층 구조로 제공합니다.
 */
data class SubGraph(
    val targets: List<FileNode>,
    val upwardDependencies: Map<Int, List<FileNode>>,   // key: depth (상향)
    val downwardDependencies: Map<Int, List<FileNode>>, // key: depth (하향)
    val isTruncated: Boolean,                           // 허브 노드로 인해 maxNodes 제한에 걸려 잘렸는지 여부
    val totalExtractedNodes: Int
)

/**
 * 타겟 파일(들)을 중심으로 연관된 서브그래프를 BFS로 추출합니다.
 */
object SubGraphExtractor {
    
    /**
     * 타겟 파일들을 중심으로 서브그래프를 추출합니다.
     *
     * @param graph 원본 프로젝트 그래프
     * @param targetClassNames 분석 대상 클래스명 리스트 (Simple Name)
     * @param maxUpwardDepth 상향(dependedBy) 탐색 깊이 (기본 2)
     * @param maxDownwardDepth 하향(dependsOn) 탐색 깊이 (기본 1)
     * @param maxNodes 최대 추출 노드 수 (허브 노드 방어용, 기본 20)
     */
    fun extract(
        graph: ProjectGraph,
        targetClassNames: List<String>,
        maxUpwardDepth: Int = 2,
        maxDownwardDepth: Int = 1,
        maxNodes: Int = 20
    ): SubGraph {
        // 타겟 노드 찾기 (클래스명 기준 매칭)
        val targetNodes = targetClassNames.mapNotNull { name ->
            graph.files.values.find { it.className == name }
        }.distinctBy { it.path }

        if (targetNodes.isEmpty()) {
            return SubGraph(emptyList(), emptyMap(), emptyMap(), false, 0)
        }

        val extractedPaths = mutableSetOf<String>()
        targetNodes.forEach { extractedPaths.add(it.path) }
        
        var nodeCount = targetNodes.size
        var isTruncated = false

        // 1. 상향 탐색 (dependedBy) - 누가 나를 호출하는가
        val upwardMap = mutableMapOf<Int, MutableList<FileNode>>()
        var currentUpwardQueue: List<FileNode> = targetNodes

        for (depth in 1..maxUpwardDepth) {
            if (nodeCount >= maxNodes) {
                isTruncated = true
                break
            }
            val nextQueue = mutableSetOf<FileNode>()
            
            for (node in currentUpwardQueue) {
                for (callerPath in node.dependedBy) {
                    val callerNode = graph.files[callerPath]
                    if (callerNode != null && !extractedPaths.contains(callerNode.path)) {
                        nextQueue.add(callerNode)
                        extractedPaths.add(callerNode.path)
                        nodeCount++
                        if (nodeCount >= maxNodes) {
                            isTruncated = true
                            break
                        }
                    }
                }
                if (isTruncated) break
            }
            
            if (nextQueue.isNotEmpty()) {
                upwardMap[depth] = nextQueue.toMutableList()
                currentUpwardQueue = nextQueue.toList()
            } else {
                break
            }
        }

        // 2. 하향 탐색 (dependsOn) - 내가 누구를 호출하는가
        val downwardMap = mutableMapOf<Int, MutableList<FileNode>>()
        var currentDownwardQueue: List<FileNode> = targetNodes
        
        for (depth in 1..maxDownwardDepth) {
            if (nodeCount >= maxNodes) {
                isTruncated = true
                break
            }
            val nextQueue = mutableSetOf<FileNode>()
            
            for (node in currentDownwardQueue) {
                for (calleePath in node.dependsOn) {
                    val calleeNode = graph.files[calleePath]
                    if (calleeNode != null && !extractedPaths.contains(calleeNode.path)) {
                        nextQueue.add(calleeNode)
                        extractedPaths.add(calleeNode.path)
                        nodeCount++
                        if (nodeCount >= maxNodes) {
                            isTruncated = true
                            break
                        }
                    }
                }
                if (isTruncated) break
            }
            
            if (nextQueue.isNotEmpty()) {
                downwardMap[depth] = nextQueue.toMutableList()
                currentDownwardQueue = nextQueue.toList()
            } else {
                break
            }
        }

        return SubGraph(
            targets = targetNodes,
            upwardDependencies = upwardMap,
            downwardDependencies = downwardMap,
            isTruncated = isTruncated,
            totalExtractedNodes = nodeCount
        )
    }
}
