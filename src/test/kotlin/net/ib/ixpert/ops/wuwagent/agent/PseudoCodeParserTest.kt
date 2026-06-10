package net.ib.ixpert.ops.wuwagent.agent

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Assert.assertEquals
import org.junit.Test

class PseudoCodeParserTest {

    @Test
    fun testParseTargetElements() {
        val graph = ProjectGraph(
            generatedAt = "2026",
            projectRoot = "test",
            files = emptyMap(),
            relationships = emptyList(),
            statistics = net.ib.ixpert.ops.wuwagent.service.metagraph.model.GraphStatistics()
        )
        
        val response = """
            #### [수정] 메서드 수정
            - 대상 메서드: `myMethod`
            ```java
            void myMethod() {}
            ```
            
            #### [수정] 요소 수정
            - 대상 요소: `myDiv`
            ```html
            <div id="myDiv"></div>
            ```
            
            #### [수정] 쿼리 수정
            - 타겟 쿼리 : `myQuery`
            ```xml
            <select id="myQuery"></select>
            ```
        """.trimIndent()
        
        val result = PseudoCodeParser.parse("test.java", response, graph)
        
        assertEquals(3, result.blocks.size)
        assertEquals(BlockType.MODIFY, result.blocks[0].type)
        assertEquals("myMethod", result.blocks[0].targetMethod)
        
        assertEquals(BlockType.MODIFY, result.blocks[1].type)
        assertEquals("myDiv", result.blocks[1].targetMethod)
        
        assertEquals(BlockType.MODIFY, result.blocks[2].type)
        assertEquals("myQuery", result.blocks[2].targetMethod)
    }
}
