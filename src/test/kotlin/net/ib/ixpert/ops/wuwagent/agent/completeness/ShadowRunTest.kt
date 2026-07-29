package net.ib.ixpert.ops.wuwagent.agent.completeness

import com.google.gson.GsonBuilder
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*
import org.junit.Test
import java.io.File

class ShadowRunTest {

    private fun getKind(className: String): String {
        return when {
            className.endsWith("SVC") -> "SVC"
            className.endsWith("SVCImpl") -> "SVCImpl"
            className.endsWith("BIZ") -> "BIZ"
            className.endsWith("DEM") -> "DEM"
            className.endsWith("DQM") -> "DQM"
            className.endsWith("SVO") -> "SVO"
            className.endsWith("BVO") -> "BVO"
            className.endsWith("DVO") -> "DVO"
            else -> "UNKNOWN"
        }
    }

    @Test
    fun runShadowRun() {
        val gson = GsonBuilder().disableHtmlEscaping().create()
        val graphFile = File("C:/Users/dffrp/Downloads/project-graph_a/project-graph.json")
        val jsonContent = graphFile.readText(Charsets.UTF_8)
        val projectGraph = gson.fromJson(jsonContent, ProjectGraph::class.java)

        println("=== Track 1: Shadow Run Results ===")
        println("Header frameworkType: ${projectGraph.frameworkType.name}")
        
        val rolesCount = mutableMapOf<String, Int>()
        var bizCount = 0
        var bizWithDemDqmCount = 0
        var bizWithDemDqmAndDvoCount = 0
        var svcImplCount = 0
        val wouldBlockCases = mutableListOf<String>()
        val svcImplMissingInterface = mutableListOf<String>()
        val passedCases = mutableListOf<String>()

        projectGraph.files.values.forEach { node ->
            val kind = getKind(node.className)
            rolesCount[kind] = rolesCount.getOrDefault(kind, 0) + 1
            
            if (kind == "BIZ") {
                bizCount++
                val dependsOnKinds = node.dependsOn.mapNotNull { depPath ->
                    projectGraph.files[depPath]?.className?.let { getKind(it) }
                }
                
                val hasDem = dependsOnKinds.contains("DEM")
                val hasDqm = dependsOnKinds.contains("DQM")
                
                if (hasDem || hasDqm) {
                    bizWithDemDqmCount++
                    
                    val demDqmDeps = dependsOnKinds.indices
                        .filter { dependsOnKinds[it] == "DEM" || dependsOnKinds[it] == "DQM" }
                        .map { node.dependsOn[it] }
                        
                    // Check if any of these DEM/DQM nodes explicitly depends on a DVO
                    val hasDvoInDemDqm = demDqmDeps.any { depPath ->
                        val depNode = projectGraph.files[depPath]
                        depNode?.dependsOn?.any { projectGraph.files[it]?.className?.let { getKind(it) } == "DVO" } == true
                    }
                    
                    if (hasDvoInDemDqm) {
                        bizWithDemDqmAndDvoCount++
                        if (passedCases.size < 5) {
                            passedCases.add(node.className)
                        }
                    } else {
                        wouldBlockCases.add("BIZ: ${node.className} depends on DEM/DQM but WOULD_BLOCK: None of its DEM/DQM dependencies depend on a DVO")
                    }
                }
            }
            
            if (kind == "SVCImpl") {
                svcImplCount++
                val hasMatch = node.implementedInterfaces.any { iface ->
                    val kindIf = getKind(iface.substringAfterLast('.'))
                    kindIf == "SVC"
                }
                if (!hasMatch) {
                    svcImplMissingInterface.add(node.className)
                }
            }
        }

        println("1. anyframeRole Counts: $rolesCount")
        println("2. BIZ nodes: $bizCount")
        println("   BIZ calling DEM/DQM: $bizWithDemDqmCount")
        println("   BIZ calling DEM/DQM AND has DVO: $bizWithDemDqmAndDvoCount")
        val percentage = if (bizWithDemDqmCount > 0) (bizWithDemDqmAndDvoCount.toDouble() / bizWithDemDqmCount * 100) else 0.0
        println("   Percentage of DVO accompaniment: $percentage %")
        
        println("3. WOULD_BLOCK cases (Missing DVO in BIZ->DEM chain): ${wouldBlockCases.size} cases")
        wouldBlockCases.forEach { println("   - $it") }
        
        println("4. SVC_IMPL missing matching SVC interface in implementedInterfaces: ${svcImplMissingInterface.size} cases")
        svcImplMissingInterface.forEach { println("   - $it") }
        
        println("5. Header frameworkType is ${projectGraph.frameworkType.name}. (If ANYFRAME_JAP, warning would be logged by GraphLoader, but it is ${projectGraph.frameworkType.name})")

        println("\n=== DETAILED ANALYSIS OF THE 5 BIZ CASES AND 1 SVC_IMPL CASE ===")
        val targets = listOf("APCADSbodrMngtBIZ", "APCADSpacdBnnrMngtBIZ", "APCMMAprIzBIZ", "APCCOMOacdFdsLkBIZ", "APCCOMCmnCBIZ", "APCMstcdFrnStlmSVCImpl")
        
        println("\n=== DETAILED ANALYSIS OF 5 PASSING BIZ CASES ===")
        val combinedTargets = targets + passedCases
        
        combinedTargets.forEach { targetName ->
            val node = projectGraph.files.values.find { it.className == targetName }
            if (node != null) {
                println("\n--- Target: $targetName ---")
                println("Class Name: ${node.className}")
                println("Implemented Interfaces: ${node.implementedInterfaces}")
                println("Depends On: ${node.dependsOn.map { projectGraph.files[it]?.className ?: it }}")
                
                // Show methods with their signatures
                println("Methods:")
                (node.methods ?: emptyList()).take(5).forEach { m -> 
                    println("  - ${m.name}(${m.parameters.joinToString()}) -> ${m.returnType}")
                }
                if ((node.methods ?: emptyList()).size > 5) println("  - ... and ${(node.methods ?: emptyList()).size - 5} more methods")

                // If BIZ, check its DEM/DQM dependencies deeply
                if (targetName.endsWith("BIZ")) {
                    val demDqmDeps = node.dependsOn.mapNotNull { projectGraph.files[it] }
                        .filter { getKind(it.className) == "DEM" || getKind(it.className) == "DQM" }
                    println("  Deep dive into DEM/DQM dependencies:")
                    demDqmDeps.forEach { depNode ->
                        println("    - ${depNode.className} depends on: ${depNode.dependsOn.map { projectGraph.files[it]?.className ?: it }}")
                        println("      Methods (first 3):")
                        (depNode.methods ?: emptyList()).take(3).forEach { m ->
                            println("        * ${m.name}(${m.parameters.joinToString()}) -> ${m.returnType}")
                        }
                    }
                }
            } else {
                println("\n--- Target: $targetName NOT FOUND ---")
            }
        }
    }
}
