package net.ib.ixpert.ops.wuwagent.agent.completeness

import net.ib.ixpert.ops.wuwagent.agent.completeness.model.*
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FileNode
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ResourceNode
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.SpringFileType
import java.io.File

class ProjectGraphAdapter(private val graph: ProjectGraph) : GraphMatchContext {
    
    override val frameworkType: FrameworkType = graph.frameworkType

    override val capabilities: Set<GraphCapability> = setOf(
        GraphCapability.FILE_PATH_ONLY,
        GraphCapability.BASENAME_INDEX,
        GraphCapability.XML_NAMESPACE_INDEX,
        GraphCapability.CONTROLLER_INDEX
    )

    override fun linkedByNamespace(daoFqcn: String): List<String> {
        val targetPath = graph.files.values.find { 
            (it.packageName + "." + it.className) == daoFqcn || it.className == daoFqcn 
        }?.path ?: return emptyList()

        return graph.resourceNodes
            .filter { it.linkedTo.contains(targetPath) }
            .map { it.path }
    }

    override fun existingControllers(): List<String> {
        return graph.files.values
            .filter { it.fileType == SpringFileType.CONTROLLER || it.fileType == SpringFileType.REST_CONTROLLER }
            .map { it.path }
    }

    override fun filesUnder(dir: String): List<String> {
        val normalizedDir = dir.replace("\\", "/")
        val filePaths = graph.files.keys.filter { it.replace("\\", "/").startsWith(normalizedDir) }
        val resourcePaths = graph.resourceNodes.map { it.path }.filter { it.replace("\\", "/").startsWith(normalizedDir) }
        return filePaths + resourcePaths
    }

    override fun allFiles(): List<String> {
        return graph.files.keys.toList() + graph.resourceNodes.map { it.path }
    }

    override fun allNodes(): Collection<FileNode> {
        return graph.files.values
    }

    override fun fqcnOf(filePath: String): String? {
        val node = graph.files[filePath] ?: return null
        return if (node.packageName.isNullOrEmpty()) node.className else "${node.packageName}.${node.className}"
    }

    override fun dirOf(filePath: String): String {
        val normalized = filePath.replace("\\", "/")
        return normalized.substringBeforeLast("/", "")
    }

    override fun baseName(filePath: String): String {
        val normalized = filePath.replace("\\", "/")
        val fileName = normalized.substringAfterLast("/")
        return fileName.substringBefore(".")
    }

    override fun getFileNode(path: String): FileNode? = graph.files[path]

    override fun getResourceNode(path: String): ResourceNode? =
        graph.resourceNodes.firstOrNull { it.path == path }
}
