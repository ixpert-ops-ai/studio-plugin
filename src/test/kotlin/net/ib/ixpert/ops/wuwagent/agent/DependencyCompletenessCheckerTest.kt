package net.ib.ixpert.ops.wuwagent.agent

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class DependencyCompletenessCheckerTest {

    @Test
    fun testCompletenessCheck_AddsInjectedDependency() {
        val graph = ProjectGraph(
            generatedAt = Instant.now().toString(),
            projectRoot = "/test",
            statistics = GraphStatistics(),
            relationships = emptyList(),
            files = mapOf(
                "com/example/Controller.java" to FileNode(
                    path = "com/example/Controller.java",
                    packageName = "com.example",
                    className = "Controller",
                    fileType = SpringFileType.CONTROLLER,
                    layer = ArchitectureLayer.PRESENTATION,
                    methods = listOf(net.ib.ixpert.ops.wuwagent.service.metagraph.model.MethodSignature("doSomething", "void", emptyList())),
                    injections = listOf(DependencyInjection("com.example.Service", "Service", InjectionMethod.FIELD, null))
                ),
                "com/example/Service.java" to FileNode(
                    path = "com/example/Service.java",
                    packageName = "com.example",
                    className = "Service",
                    fileType = SpringFileType.SERVICE,
                    layer = ArchitectureLayer.BUSINESS,
                    methods = listOf(net.ib.ixpert.ops.wuwagent.service.metagraph.model.MethodSignature("processKeyword", "void", emptyList())),
                    dependedBy = mutableListOf("com/example/Controller.java")
                )
            )
        )

        val targetFiles = listOf(
            TargetFileSpec(1, "com/example/Controller.java", "수정", "Controller 수정")
        )

        val result = DependencyCompletenessChecker.check(targetFiles, graph, listOf("keyword"))
        
        assertEquals(2, result.size)
        assertTrue(result.any { it.path == "com/example/Service.java" })
    }

    @Test
    fun testCompletenessCheck_IgnoresCommonUtils() {
        val graph = ProjectGraph(
            generatedAt = Instant.now().toString(),
            projectRoot = "/test",
            statistics = GraphStatistics(),
            relationships = emptyList(),
            files = mapOf(
                "com/example/Controller.java" to FileNode(
                    path = "com/example/Controller.java",
                    packageName = "com.example",
                    className = "Controller",
                    fileType = SpringFileType.CONTROLLER,
                    layer = ArchitectureLayer.PRESENTATION,
                    methods = listOf(net.ib.ixpert.ops.wuwagent.service.metagraph.model.MethodSignature("doSomething", "void", emptyList())),
                    injections = listOf(DependencyInjection("com.example.StringUtil", "StringUtil", InjectionMethod.FIELD, null))
                ),
                "com/example/StringUtil.java" to FileNode(
                    path = "com/example/StringUtil.java",
                    packageName = "com.example",
                    className = "StringUtil",
                    fileType = SpringFileType.UTIL,
                    layer = ArchitectureLayer.COMMON,
                    methods = listOf(net.ib.ixpert.ops.wuwagent.service.metagraph.model.MethodSignature("parseKeyword", "void", emptyList())),
                    // dependedBy size >= 10
                    dependedBy = MutableList(10) { "com/example/Class$it.java" }
                )
            )
        )

        val targetFiles = listOf(
            TargetFileSpec(1, "com/example/Controller.java", "수정", "Controller 수정")
        )

        // StringUtil has method "parseKeyword" which matches "keyword", but dependedBy >= 10
        val result = DependencyCompletenessChecker.check(targetFiles, graph, listOf("keyword"))
        
        assertEquals(1, result.size) // Shouldn't add StringUtil
        assertFalse(result.any { it.path == "com/example/StringUtil.java" })
    }
}
