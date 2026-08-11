package net.ib.ixpert.ops.wuwagent.service.metagraph

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import com.intellij.openapi.project.Project
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.GraphLoader

class SchemaCompatibilityTest {
    // ... (Keep testLegacyGraphDeserialization)
    @Test
    fun testLegacyGraphDeserialization() {
        val file = File("C:/Users/dffrp/Downloads/project-graph_a/project-graph.json")
        if (!file.exists()) {
            println("APC file not found at " + file.absolutePath)
            return
        }
        val json = file.readText()
        val graph = Gson().fromJson(json, ProjectGraph::class.java).normalizeLegacyCollections()
        
        val node = graph.files.values.firstOrNull()
        if (node != null) {
            println("Node: " + node.path)
            println("usesTypes is null? " + (node.usesTypes == null))
            println("usedByTypes is null? " + (node.usedByTypes == null))
            val safeUsesTypes = (node.usesTypes as List<String>?) ?: emptyList()
            println("safeUsesTypes size: " + safeUsesTypes.size)

            // Runtime verification of patched components
            println("Running RiskCalculator on legacy node...")
            val risk = net.ib.ixpert.ops.wuwagent.service.metagraph.analyzer.RiskCalculator.calculate(node)
            println("Risk score: " + risk.riskScore)
            
            println("Running DomainExtractor on legacy graph...")
            val domainExtractor = net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DomainExtractor(graph.files)
            val domain = domainExtractor.getDomain(node.path)
            println("DomainExtractor execution completed. Domain: " + domain)
            
            println("Running GraphExpander on legacy graph...")
            val expander = net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.GraphExpander(graph, domainExtractor)
            val seedResult = net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.SeedSelectionResult(
                seedClasses = listOf(node.className),
                changeIntent = net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.ChangeIntent.MODIFY,
                layerHint = emptyList(),
                frontendRelevant = false,
                reasoning = "Test",
                frontendFileHints = emptyList()
            )
            val expandedFiles = expander.expand(seedResult)
            println("GraphExpander execution completed. Expanded files: " + expandedFiles.size)
            
            println("All patched components successfully executed without NPE!")
        } else {
            println("Graph has no files.")
        }
    }

    @Test
    fun testGraphLoaderProductionRoute() {
        val originalFile = File("C:/Users/dffrp/Downloads/project-graph_a/project-graph.json")
        if (!originalFile.exists()) {
            println("APC file not found at " + originalFile.absolutePath)
            return
        }

        // Create temporary project structure for GraphLoader
        val tempProjectDir = File(System.getProperty("java.io.tmpdir"), "test-project-" + System.currentTimeMillis())
        val metaDir = File(tempProjectDir, ".meta")
        metaDir.mkdirs()
        val tempMetaFile = File(metaDir, "project-graph.json")
        originalFile.copyTo(tempMetaFile, overwrite = true)

        // Create a Proxy for Project instead of using Mockito
        val mockProject = Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java)
        ) { _, method, _ ->
            if (method.name == "getBasePath") {
                return@newProxyInstance tempProjectDir.absolutePath
            }
            // Return null or default values for other methods if called
            null
        } as Project

        val loader = GraphLoader(mockProject)
        val graph = loader.loadGraph() // loadGraph will find it in .meta/project-graph.json
        
        if (graph != null) {
            val node = graph.files.values.firstOrNull()
            if (node != null) {
                println("Testing GraphLoader production route...")
                println("Node via GraphLoader: " + node.path)
                println("usesTypes size: " + node.usesTypes.size)
                println("usedByTypes size: " + node.usedByTypes.size)
                
                // Verify NPE doesn't happen
                val risk = net.ib.ixpert.ops.wuwagent.service.metagraph.analyzer.RiskCalculator.calculate(node)
                println("Risk score via GraphLoader: " + risk.riskScore)
                println("GraphLoader production route successfully executed without NPE!")
            }
        }
        
        // Cleanup
        tempProjectDir.deleteRecursively()
    }
}
