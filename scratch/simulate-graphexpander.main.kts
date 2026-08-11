import java.io.File
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.*

fun main() {
    val file = File("C:/Workspace/project-graph_b/project-graph_b.json")
    val mapper = jacksonObjectMapper()
    val graph = mapper.readValue<ProjectGraph>(file)
    
    // GraphLoader normalization logic
    val projectRoot = "project-graph_b" // mock relativeRoot
    val normalizedFiles = mutableMapOf<String, FileNode>()
    graph.files.forEach { (path, node) ->
        val normalizedPath = if (path.startsWith(projectRoot)) path else "$projectRoot/$path"
        if (normalizedPath != path) {
            val safeNode = node.copy(
                path = normalizedPath,
                dependsOn = node.dependsOn.map { if (it.startsWith(projectRoot)) it else "$projectRoot/$it" }.toMutableList(),
                dependedBy = node.dependedBy.map { if (it.startsWith(projectRoot)) it else "$projectRoot/$it" }.toMutableList(),
                usesTypes = node.usesTypes.map { if (it.startsWith(projectRoot)) it else "$projectRoot/$it" }.toMutableList(),
                usedByTypes = node.usedByTypes.map { if (it.startsWith(projectRoot)) it else "$projectRoot/$it" }.toMutableList()
            )
            normalizedFiles[normalizedPath] = safeNode
        } else {
            normalizedFiles[normalizedPath] = node
        }
    }
    val normalizedGraph = ProjectGraph(files = normalizedFiles)
    
    val queryable = ProjectGraphQueryable(normalizedGraph)
    val expander = GraphExpander(queryable, DiscoveryConfig(maxHop = 4, infraThreshold = 20))
    
    val seedController = normalizedGraph.files.values.find { it.className == "PdInfoController" }
    if (seedController == null) {
        println("Seed not found")
        return
    }
    
    val expanded = expander.expand(seedController.path)
    println("Expanded nodes: ${expanded.size}")
}

main()
