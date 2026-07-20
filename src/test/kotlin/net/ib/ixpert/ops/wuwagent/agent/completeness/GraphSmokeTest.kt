package net.ib.ixpert.ops.wuwagent.agent.completeness

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Test
import org.junit.Assert.assertNotNull
import java.io.File
import java.nio.file.Files

class GraphSmokeTest {
    @Test
    fun testLoadIsmGraph() {
        val file = File("C:\\Workspace\\project-graph_b\\project-graph_b.json")
        if (!file.exists()) {
            println("ISM graph file not found.")
            return
        }
        val json = Files.readString(file.toPath())
        val graph = Gson().fromJson(json, ProjectGraph::class.java)
        
        assertNotNull(graph)
        println("Loaded ISM Graph: ${graph.resourceNodes.size} resource nodes, ${graph.files.size} source nodes")
        
        val mappers = graph.files.values.filter { it.dependsOn.isNotEmpty() }
        println("Found ${mappers.size} nodes with dependencies.")
        if (mappers.isNotEmpty()) {
            val sample = mappers.first()
            println("Sample Node: ${sample.className}, layer: ${sample.layer}")
            println("Dependencies: ${sample.dependsOn}")
            println("Injections: ${sample.injections}")
        }
    }

    @Test
    fun testLoadApcGraph() {
        val file = File("C:\\Users\\dffrp\\Downloads\\project-graph_a\\project-graph.json")
        if (!file.exists()) {
            println("APC graph file not found.")
            return
        }
        val json = Files.readString(file.toPath())
        val graph = Gson().fromJson(json, ProjectGraph::class.java)
        
        assertNotNull(graph)
        println("Loaded APC Graph: ${graph.resourceNodes.size} resource nodes, ${graph.files.size} source nodes")
        
        val svcs = graph.files.values.filter { it.dependsOn.isNotEmpty() }
        println("Found ${svcs.size} nodes with dependencies.")
        if (svcs.isNotEmpty()) {
            val sample = svcs.first()
            println("Sample Node: ${sample.className}, layer: ${sample.layer}")
            println("Dependencies: ${sample.dependsOn}")
            println("Injections: ${sample.injections}")
        }
    }
}
