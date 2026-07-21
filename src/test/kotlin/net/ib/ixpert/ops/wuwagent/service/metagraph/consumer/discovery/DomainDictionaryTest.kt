package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class DomainDictionaryTest {

    @Test
    fun testTranslation() {
        val graph = ProjectGraph(
            generatedAt = Instant.now().toString(),
            projectRoot = "/test",
            statistics = GraphStatistics(),
            relationships = emptyList(),
            files = mapOf(
                "CommonUtil.java" to FileNode(
                    path = "CommonUtil.java",
                    packageName = "com.example",
                    className = "CommonUtil",
                    fileType = SpringFileType.UTIL,
                    layer = ArchitectureLayer.COMMON,
                    methods = listOf(
                        net.ib.ixpert.ops.wuwagent.service.metagraph.model.MethodSignature("getMember", "void", emptyList()),
                        net.ib.ixpert.ops.wuwagent.service.metagraph.model.MethodSignature("getCard", "void", emptyList()),
                        net.ib.ixpert.ops.wuwagent.service.metagraph.model.MethodSignature("getLimit", "void", emptyList())
                    )
                )
            )
        )

        val dict = DomainDictionary.load(graph)
        val tokens = dict.translate("조회")
        
        // 내장 사전에 "조회" -> setOf("search")
        assertTrue(tokens.contains("search"))
    }

    @Test
    fun testPartialMatchLengthCheck() {
        val graph = ProjectGraph(
            generatedAt = Instant.now().toString(),
            projectRoot = "/test",
            statistics = GraphStatistics(),
            relationships = emptyList(),
            files = emptyMap()
        )
        val dict = DomainDictionary.load(graph)
        
        // 1글자는 매칭되지 않아야 함
        val tokens = dict.translate("록")
        assertTrue(tokens.isEmpty())
        
        // 2글자 이상은 매칭됨 (내장사전에 "목록" -> "list")
        val tokens2 = dict.translate("목록")
        assertTrue(tokens2.contains("list"))
    }
}
