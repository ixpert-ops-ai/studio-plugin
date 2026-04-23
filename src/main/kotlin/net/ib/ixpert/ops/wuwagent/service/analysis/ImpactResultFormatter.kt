package net.ib.ixpert.ops.wuwagent.service.analysis

import net.ib.ixpert.ops.wuwagent.service.analysis.ImpactAnalyzer.ImpactNode
import net.ib.ixpert.ops.wuwagent.service.analysis.ImpactAnalyzer.RelationType
import net.ib.ixpert.ops.wuwagent.service.analysis.ImpactAnalyzer.ArchitectureLayer
import net.ib.ixpert.ops.wuwagent.service.analysis.ImpactAnalyzer.AnalysisStrategy
import net.ib.ixpert.ops.wuwagent.service.analysis.ImpactAnalyzer.AnalysisStatistics
import net.ib.ixpert.ops.wuwagent.service.analysis.ImpactAnalyzer.ImpactAnalysisResult

/**
 * ImpactAnalyzer에서 수행된 영향도 분석 데이터(ImpactNode 트리)를
 * 텍스트 형식으로 변환하고 통계를 계산하는 포매터입니다.
 * (단일 책임 원칙에 따라 데이터 수집과 렌더링 로직을 분리)
 */
object ImpactResultFormatter {

    private const val MAX_DISPLAY_PER_NODE = 50

    private data class FlatNode(
        val signature: String,
        val location: String,
        val elementType: String,
        val depth: Int,
        val parentSignature: String?,
        val relationType: RelationType,
        val architectureLayer: ArchitectureLayer
    )

    fun buildResult(
        targetSignature: String,
        targetLocation: String,
        targetType: String,
        strategy: AnalysisStrategy,
        rootNode: ImpactNode?
    ): ImpactAnalysisResult {
        if (rootNode == null) {
            return ImpactAnalysisResult(
                targetSignature = targetSignature,
                targetLocation = targetLocation,
                targetType = targetType,
                callHierarchyTree = "(분석 결과 없음)",
                layerSummary = "",
                polymorphismInfo = "",
                dataFlowInfo = "",
                allAffectedSignatures = emptyList(),
                statistics = AnalysisStatistics(0, 0, 0, 0, 0, 0, strategy)
            )
        }

        val allNodes = mutableListOf<FlatNode>()
        flattenTree(rootNode, 0, null, allNodes)

        val treeString = renderTree(rootNode, 0)
        val layerSummary = buildLayerSummary(allNodes)
        val polymorphismInfo = buildPolymorphismInfo(allNodes)
        val dataFlowInfo = buildDataFlowInfo(allNodes)

        val directCount = rootNode.children.size
        val indirectCount = allNodes.count { it.depth > 1 }
        val polyCount = allNodes.count { it.relationType == RelationType.POLYMORPHIC }
        val dfCount = allNodes.count { it.relationType == RelationType.DATA_FLOW }
        val maxDepth = allNodes.maxOfOrNull { it.depth } ?: 0

        val allSignatures = allNodes
            .filter { it.depth > 0 }
            .map { it.signature }
            .distinct()

        return ImpactAnalysisResult(
            targetSignature = targetSignature,
            targetLocation = targetLocation,
            targetType = targetType,
            callHierarchyTree = treeString,
            layerSummary = layerSummary,
            polymorphismInfo = polymorphismInfo,
            dataFlowInfo = dataFlowInfo,
            allAffectedSignatures = allSignatures,
            statistics = AnalysisStatistics(
                directCallerCount = directCount,
                indirectCallerCount = indirectCount,
                polymorphicCount = polyCount,
                dataFlowCount = dfCount,
                totalAffected = allSignatures.size,
                maxDepthReached = maxDepth,
                strategy = strategy
            )
        )
    }

    private fun flattenTree(node: ImpactNode, depth: Int, parentSig: String?, result: MutableList<FlatNode>) {
        val layer = classifyArchitectureLayer(node.signature)
        result.add(FlatNode(
            signature = node.signature,
            location = node.location,
            elementType = node.elementType,
            depth = depth,
            parentSignature = parentSig,
            relationType = node.relationType,
            architectureLayer = layer
        ))
        for (child in node.children) {
            flattenTree(child, depth + 1, node.signature, result)
        }
    }

    private fun classifyArchitectureLayer(signature: String): ArchitectureLayer {
        val lower = signature.lowercase()
        return when {
            lower.contains("controller") || lower.contains("resource") || lower.contains("endpoint") -> ArchitectureLayer.CONTROLLER
            lower.contains("service") || lower.contains("usecase") || lower.contains("facade") -> ArchitectureLayer.SERVICE
            lower.contains("repository") || lower.contains("dao") || lower.contains("mapper") -> ArchitectureLayer.REPOSITORY
            lower.contains("entity") || lower.contains("model") || lower.contains("dto") || lower.contains("vo") -> ArchitectureLayer.ENTITY
            lower.contains("config") || lower.contains("configuration") -> ArchitectureLayer.CONFIGURATION
            lower.contains("util") || lower.contains("helper") || lower.contains("common") -> ArchitectureLayer.UTILITY
            lower.contains("test") || lower.contains("spec") || lower.contains("mock") -> ArchitectureLayer.TEST
            else -> ArchitectureLayer.UNKNOWN
        }
    }

    private fun buildLayerSummary(allNodes: List<FlatNode>): String {
        val sb = StringBuilder()
        val grouped = allNodes
            .filter { it.depth > 0 }
            .groupBy { it.architectureLayer }
            .toSortedMap(compareBy { it.ordinal })

        if (grouped.isEmpty()) return ""

        for ((layer, nodes) in grouped) {
            val uniqueSignatures = nodes.map { it.signature }.distinct()
            sb.appendLine("  ${layer.label} (${uniqueSignatures.size}개):")
            for (sig in uniqueSignatures) {
                val loc = nodes.first { it.signature == sig }.location
                sb.appendLine("    - $sig  ($loc)")
            }
        }
        return sb.toString()
    }

    private fun buildPolymorphismInfo(allNodes: List<FlatNode>): String {
        val polyNodes = allNodes.filter {
            it.relationType == RelationType.POLYMORPHIC || it.relationType == RelationType.SUPER_METHOD
        }
        if (polyNodes.isEmpty()) return ""

        val sb = StringBuilder()
        sb.appendLine("  다형성/상속을 통해 영향받는 요소:")
        for (node in polyNodes.distinctBy { it.signature }) {
            sb.appendLine("    - [${node.relationType.label}] ${node.signature}  (${node.location})")
        }
        return sb.toString()
    }

    private fun buildDataFlowInfo(allNodes: List<FlatNode>): String {
        val dfNodes = allNodes.filter { it.relationType == RelationType.DATA_FLOW }
        if (dfNodes.isEmpty()) return ""

        val sb = StringBuilder()
        sb.appendLine("  필드 값 변경으로 간접 영향받는 요소:")
        for (node in dfNodes.distinctBy { it.signature }) {
            sb.appendLine("    - ${node.signature}  (${node.location})")
        }
        return sb.toString()
    }

    private fun renderTree(node: ImpactNode?, depth: Int): String {
        if (node == null) return ""

        val sb = StringBuilder()
        val indent = "  ".repeat(depth)
        val prefix = if (depth == 0) "▶ " else "├─ "

        sb.append(indent).append(prefix)
        if (node.relationType != RelationType.DIRECT_CALL) {
            sb.append("[${node.relationType.label}] ")
        }
        sb.append("[${node.elementType}] ")
        sb.append(node.signature)
        if (node.location.isNotBlank()) {
            sb.append("  (${node.location})")
        }
        if (node.totalChildCount > 0) {
            sb.append("  → ${node.totalChildCount}개 호출처")
        }
        sb.append("\n")

        var displayedCount = 0
        for (child in node.children) {
            displayedCount++
            if (displayedCount > MAX_DISPLAY_PER_NODE) {
                val remaining = node.children.size - MAX_DISPLAY_PER_NODE
                sb.append("  ".repeat(depth + 1))
                    .append("├─ ...외 ${remaining}개 (총 ${node.totalChildCount}개)\n")
                break
            }
            sb.append(renderTree(child, depth + 1))
        }

        return sb.toString()
    }
}
