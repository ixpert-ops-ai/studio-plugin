package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FileNode
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Test
import java.io.File

class GraphExpanderGateTest {

    @Test
    fun `test Gate 1 - ISM expansion simulates 503 nodes`() {
        val file = File("C:/Workspace/project-graph_b/project-graph_b.json")
        if (!file.exists()) {
            println("File not found")
            return
        }
        val mapper = jacksonObjectMapper()
        val graph = mapper.readValue<ProjectGraph>(file)
        
        // GraphLoader normalization logic
        val projectRoot = "project-graph_b"
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
        val normalizedGraph = ProjectGraph(
            frameworkType = graph.frameworkType,
            generatedAt = graph.generatedAt,
            projectRoot = projectRoot,
            relationships = graph.relationships,
            statistics = graph.statistics,
            files = normalizedFiles,
            resourceNodes = graph.resourceNodes
        )
        
        val domainExtractor = DomainExtractor(normalizedGraph.files)
        val expander = GraphExpander(normalizedGraph, domainExtractor, DiscoveryConfig(maxHop = 4))
        
        val seedController = normalizedGraph.files.values.find { it.className == "PdInfoController" }
        requireNotNull(seedController) { "Seed not found" }
        
        val seedResult = SeedSelectionResult(
            seedClasses = listOf("PdInfoController"),
            changeIntent = ChangeIntent.MODIFY,
            layerHint = listOf("CONTROLLER", "SERVICE", "MAPPER", "DATA_ACCESS", "DTO", "VO", "REPOSITORY"),
            frontendRelevant = false,
            reasoning = "Test"
        )
        val expanded = expander.expand(seedResult)
        println("===============================================")
        println("Gate 1 Result (ISM PdInfoController): ${expanded.size} nodes reached")
        println("===============================================")
    }
}
