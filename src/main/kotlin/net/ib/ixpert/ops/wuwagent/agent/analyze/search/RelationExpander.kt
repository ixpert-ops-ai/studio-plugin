package net.ib.ixpert.ops.wuwagent.agent.analyze.search

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ArchitectureLayer
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FileNode
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph

class RelationExpander(
    private val metaGraph: ProjectGraph,
    private val frameworkType: FrameworkType
) {
    data class FileCluster(
        val seed: FileNode,
        val related: List<RelatedFile>,
        val layerMap: Map<String, ArchitectureLayer>
    )

    data class RelatedFile(
        val fileNode: FileNode,
        val relation: Relation,
        val depth: Int
    )

    enum class Relation { INJECTS, IMPLEMENTS, EXTENDS, XML_MAPPED }

    fun expand(hits: List<MultiStrategySearch.SearchHit>, maxDepth: Int = 2): List<FileCluster> {
        return hits.take(5).map { hit ->
            val related = mutableListOf<RelatedFile>()
            val visited = mutableSetOf(hit.fileNode.path)
            expandFrom(hit.fileNode, related, visited, 0, maxDepth)

            FileCluster(
                seed = hit.fileNode,
                related = related,
                layerMap = assignLayers(listOf(hit.fileNode) + related.map { it.fileNode })
            )
        }
    }

    private fun expandFrom(
        fileNode: FileNode,
        result: MutableList<RelatedFile>,
        visited: MutableSet<String>,
        depth: Int,
        maxDepth: Int
    ) {
        if (depth >= maxDepth) return
        if (result.size >= 20) return // 폭발 방지: 최대 확장 수 제한

        // 공통 유틸 및 기반 클래스에 대한 과도한 확장 스킵
        val className = fileNode.className ?: ""
        if (className in setOf("BaseEntity", "AbstractService", "BaseController", "ApiResponse", "BusinessException")) return

        // 1. DI 주입 대상 추적 (프레임워크별)
        findDependencies(fileNode).forEach { depFile ->
            if (depFile.path !in visited) {
                visited += depFile.path
                result += RelatedFile(depFile, Relation.INJECTS, depth + 1)
                expandFrom(depFile, result, visited, depth + 1, maxDepth)
            }
        }

        // 2. Interface <-> Impl 쌍
        findInterfaceImplPair(fileNode)?.let { pairFile ->
            if (pairFile.path !in visited) {
                visited += pairFile.path
                result += RelatedFile(pairFile, Relation.IMPLEMENTS, depth + 1)
            }
        }

        // 3. 상속 관계
        findSuperClass(fileNode)?.let { parentFile ->
            if (parentFile.path !in visited) {
                visited += parentFile.path
                result += RelatedFile(parentFile, Relation.EXTENDS, depth + 1)
            }
        }

        // 4. XML 매핑 (MyBatis 계열)
        if (frameworkType in listOf(FrameworkType.SPRING_BOOT_MYBATIS, FrameworkType.SPRING_MVC_MYBATIS)) {
            findXmlMapper(fileNode)?.let { xmlFile ->
                if (xmlFile.path !in visited) {
                    visited += xmlFile.path
                    result += RelatedFile(xmlFile, Relation.XML_MAPPED, depth + 1)
                }
            }
        }

    }

    private fun findDependencies(fileNode: FileNode): List<FileNode> {
        return fileNode.injections.mapNotNull { injection ->
            injection.resolvedImpl ?: injection.targetType
        }.mapNotNull { typeName ->
            metaGraph.files.values.firstOrNull { it.className == typeName }
        }
    }

    private fun findInterfaceImplPair(fileNode: FileNode): FileNode? {
        val className = fileNode.className ?: return null

        return when (frameworkType) {
            FrameworkType.ANYFRAME_AP -> {
                when {
                    className.endsWith("SVCImpl") -> {
                        val ifaceName = className.removeSuffix("SVCImpl") + "Service"
                        metaGraph.files.values.firstOrNull { it.className == ifaceName }
                    }
                    className.endsWith("Service") && !className.endsWith("ServiceImpl") -> {
                        val implName = className.removeSuffix("Service") + "SVCImpl"
                        metaGraph.files.values.firstOrNull { it.className == implName }
                    }
                    else -> null
                }
            }
            FrameworkType.SPRING_MVC_MYBATIS -> {
                when {
                    className.endsWith("ServiceImpl") -> {
                        val ifaceName = className.removeSuffix("Impl")
                        metaGraph.files.values.firstOrNull { it.className == ifaceName }
                    }
                    className.endsWith("Service") && !className.endsWith("ServiceImpl") -> {
                        metaGraph.files.values.firstOrNull { it.className == "${className}Impl" }
                    }
                    else -> null
                }
            }
            FrameworkType.SPRING_BOOT_JPA, FrameworkType.SPRING_BOOT_MYBATIS, FrameworkType.SPRING_BOOT_JDBC -> {
                when {
                    className.endsWith("ServiceImpl") ->
                        metaGraph.files.values.firstOrNull { it.className == className.removeSuffix("Impl") }
                    className.endsWith("Service") ->
                        metaGraph.files.values.firstOrNull { it.className == "${className}Impl" }
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun findSuperClass(fileNode: FileNode): FileNode? {
        val parent = fileNode.superClass ?: return null
        return metaGraph.files.values.firstOrNull { it.className == parent }
    }

    private fun findXmlMapper(fileNode: FileNode): FileNode? {
        val className = fileNode.className ?: return null
        val baseName = className
            .removeSuffix("Dao").removeSuffix("DaoImpl")
            .removeSuffix("Mapper").removeSuffix("Repository")

        return metaGraph.files.values
            .filter { it.path.endsWith(".xml") }
            .firstOrNull { xmlNode ->
                val xmlName = xmlNode.path.substringAfterLast("/").removeSuffix(".xml")
                xmlName.contains(baseName, ignoreCase = true) ||
                    xmlName.replace("_SQL", "").equals(baseName, ignoreCase = true)
            }
    }

    private fun assignLayers(files: List<FileNode>): Map<String, ArchitectureLayer> {
        return files.associate { file ->
            val layer = classifyLayer(file)
            file.path to layer
        }
    }

    private fun classifyLayer(file: FileNode): ArchitectureLayer {
        val className = file.className ?: file.path.substringAfterLast("/")
        return when (frameworkType) {
            FrameworkType.ANYFRAME_AP -> when {
                className.endsWith("Controller") || className.endsWith("Action") -> ArchitectureLayer.PRESENTATION
                className.contains("BIZ") -> ArchitectureLayer.BUSINESS
                className.endsWith("SVCImpl") -> ArchitectureLayer.BUSINESS
                className.endsWith("Service") -> ArchitectureLayer.BUSINESS
                className.contains("DQM") || className.contains("DEM") -> ArchitectureLayer.PERSISTENCE
                className.endsWith("VO") || className.endsWith("SVO") ||
                    className.endsWith("BVO") || className.endsWith("DVO") -> ArchitectureLayer.MODEL
                else -> file.layer
            }
            FrameworkType.SPRING_BOOT_JPA -> when {
                className.endsWith("Controller") || className.endsWith("RestController") -> ArchitectureLayer.PRESENTATION
                className.endsWith("Service") || className.endsWith("ServiceImpl") -> ArchitectureLayer.BUSINESS
                className.endsWith("Repository") -> ArchitectureLayer.PERSISTENCE
                className.endsWith("Entity") || file.annotations.any { it == "Entity" || it == "@Entity" } -> ArchitectureLayer.MODEL
                className.endsWith("Dto") || className.endsWith("VO") -> ArchitectureLayer.MODEL
                else -> file.layer
            }
            FrameworkType.SPRING_BOOT_MYBATIS -> when {
                className.endsWith("Controller") -> ArchitectureLayer.PRESENTATION
                className.endsWith("Service") || className.endsWith("ServiceImpl") -> ArchitectureLayer.BUSINESS
                className.endsWith("Mapper") || file.annotations.any { it == "Mapper" || it == "@Mapper" } -> ArchitectureLayer.PERSISTENCE
                className.endsWith("Dto") || className.endsWith("VO") -> ArchitectureLayer.MODEL
                else -> file.layer
            }
            FrameworkType.SPRING_BOOT_JDBC -> when {
                className.endsWith("Controller") -> ArchitectureLayer.PRESENTATION
                className.endsWith("Service") || className.endsWith("ServiceImpl") -> ArchitectureLayer.BUSINESS
                className.endsWith("Dao") || className.endsWith("Repository") -> ArchitectureLayer.PERSISTENCE
                className.endsWith("Dto") || className.endsWith("VO") -> ArchitectureLayer.MODEL
                else -> file.layer
            }
            FrameworkType.SPRING_MVC_MYBATIS -> when {
                className.endsWith("Controller") -> ArchitectureLayer.PRESENTATION
                className.endsWith("Service") && !className.endsWith("ServiceImpl") -> ArchitectureLayer.BUSINESS
                className.endsWith("ServiceImpl") -> ArchitectureLayer.BUSINESS
                className.endsWith("Dao") || className.endsWith("DaoImpl") -> ArchitectureLayer.PERSISTENCE
                className.endsWith("VO") || className.endsWith("DTO") -> ArchitectureLayer.MODEL
                else -> file.layer
            }
            else -> file.layer
        }
    }
}
