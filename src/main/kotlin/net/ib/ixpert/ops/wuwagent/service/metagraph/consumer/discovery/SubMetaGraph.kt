package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FileNode
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraphQueryable
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.Relationship
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ResourceNode

data class SubMetaGraph(
    override val projectRoot: String? = null,
    override val files: Map<String, FileNode>,
    override val resourceNodes: List<ResourceNode>,
    val internalEdges: List<Relationship>,
    val externalDependencies: List<ExternalDependency>,
    val sourceSelections: List<String>
) : ProjectGraphQueryable

data class ExternalDependency(
    val sourceFile: FileNode,
    val targetFile: FileNode,
    val relationTypes: Set<String>,
    val count: Int
)
