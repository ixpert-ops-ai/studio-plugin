package net.ib.ixpert.ops.wuwagent.agent.analyze.search

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ArchitectureLayer
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FileNode
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType
import java.io.File

class PatternExtractor(private val frameworkType: FrameworkType) {
    data class ImplementationPattern(
        val name: String,
        val layerChain: List<ArchitectureLayer>,
        val files: Map<ArchitectureLayer, List<FileInfo>>,
        val utilities: List<String>,
        val keyMethodSignatures: List<String>,
        val codeSnippets: Map<ArchitectureLayer, String>
    )

    data class FileInfo(val className: String, val path: String, val layer: ArchitectureLayer)

    fun extract(clusters: List<RelationExpander.FileCluster>): List<ImplementationPattern> {
        return clusters
            .filter { it.related.isNotEmpty() }
            .map { buildPattern(it) }
            .distinctBy { it.layerChain.joinToString() + it.utilities.sorted().joinToString() }
    }

    private fun buildPattern(cluster: RelationExpander.FileCluster): ImplementationPattern {
        val allFiles = listOf(cluster.seed) + cluster.related.map { it.fileNode }

        val layerChain = cluster.layerMap.values.distinct()
            .filter { it != ArchitectureLayer.UNKNOWN }
            .sortedBy { layerOrder(it) }

        val filesByLayer = allFiles.groupBy { cluster.layerMap[it.path] ?: ArchitectureLayer.UNKNOWN }
            .mapValues { (_, nodes) ->
                nodes.map { FileInfo(it.className ?: it.path.substringAfterLast("/"), it.path, cluster.layerMap[it.path] ?: ArchitectureLayer.UNKNOWN) }
            }

        val utilities = allFiles.flatMap { extractUtilDependencies(it) }.distinct()
        val signatures = allFiles.flatMap { extractPublicMethodSignatures(it) }.take(8)
        
        // 지연 로딩을 통한 스니펫 추출 (파일 I/O 최소화)
        val snippets = layerChain.associateWith { layer ->
            allFiles.firstOrNull { cluster.layerMap[it.path] == layer }
                ?.let { extractSnippet(it, maxLines = 15) } ?: ""
        }

        return ImplementationPattern(
            name = derivePatternName(allFiles),
            layerChain = layerChain,
            files = filesByLayer,
            utilities = utilities,
            keyMethodSignatures = signatures,
            codeSnippets = snippets
        )
    }

    private fun derivePatternName(files: List<FileNode>): String {
        val classNames = files.mapNotNull { it.className }
        val suffixesToRemove = when (frameworkType) {
            FrameworkType.ANYFRAME_AP, FrameworkType.ANYFRAME_JAP -> listOf("Controller", "Action", "SVCImpl", "Service", "BIZ", "DQM", "DEM")
            FrameworkType.SPRING_BOOT_JPA -> listOf("Controller", "RestController", "ServiceImpl", "Service", "Repository")
            FrameworkType.SPRING_BOOT_MYBATIS -> listOf("Controller", "ServiceImpl", "Service", "Mapper")
            FrameworkType.SPRING_BOOT_JDBC -> listOf("Controller", "ServiceImpl", "Service", "Dao", "Repository")
            FrameworkType.SPRING_MVC_MYBATIS -> listOf("Controller", "ServiceImpl", "Service", "Dao", "DaoImpl")
            FrameworkType.CUSTOM, FrameworkType.SPRING_BOOT, FrameworkType.ANYFRAME -> listOf("Controller", "Service", "Dao", "Impl")
        }

        val stripped = classNames.map { name ->
            var result = name
            suffixesToRemove.forEach { suffix -> result = result.removeSuffix(suffix) }
            result
        }

        return stripped.groupBy { it }
            .maxByOrNull { it.value.size }?.key
            ?: stripped.firstOrNull() ?: "UnknownPattern"
    }

    private fun extractUtilDependencies(file: FileNode): List<String> {
        return file.injections.mapNotNull { it.targetType }
            .filter { it.contains("Util") || it.contains("Helper") || it.contains("Factory") }
    }

    private fun extractPublicMethodSignatures(file: FileNode): List<String> {
        return file.methodNames.map { "${file.className}.$it()" }
    }

    private fun extractSnippet(file: FileNode, maxLines: Int): String {
        val ioFile = File(file.path)
        if (!ioFile.exists() || !ioFile.isFile) return ""
        return try {
            ioFile.useLines { lines ->
                // import문 제외하고 클래스 선언부 주변부터 추출하도록 필터링
                val filteredLines = lines.dropWhile { 
                    it.trim().startsWith("package") || it.trim().startsWith("import") || it.trim().isEmpty() 
                }.take(maxLines).toList()
                filteredLines.joinToString("\n")
            }
        } catch (e: Exception) {
            ""
        }
    }

    private fun layerOrder(layer: ArchitectureLayer): Int = when (layer) {
        ArchitectureLayer.VIEW -> -1
        ArchitectureLayer.PRESENTATION -> 0
        ArchitectureLayer.BUSINESS -> 1
        ArchitectureLayer.PERSISTENCE -> 2
        ArchitectureLayer.COMMON -> 3
        ArchitectureLayer.MODEL -> 4
        ArchitectureLayer.TEST -> 5
        ArchitectureLayer.UNKNOWN -> 6
        else -> 7
    }
}
