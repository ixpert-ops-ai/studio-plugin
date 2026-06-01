package net.ib.ixpert.ops.wuwagent.agent.clarify

import org.junit.Assert.*
import org.junit.Test
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FileNode

class ClarifyEngineTest {

    @Test
    fun `GapAnalyzerTest - 우선순위 및 4개 제한`() {
        // 모든 슬롯이 EMPTY
        val slots = mapOf(
            SlotId.ACTION to Slot(SlotId.ACTION, confidence = SlotConfidence.EMPTY),
            SlotId.TARGET to Slot(SlotId.TARGET, confidence = SlotConfidence.EMPTY),
            SlotId.SCOPE to Slot(SlotId.SCOPE, confidence = SlotConfidence.EMPTY),
            SlotId.PATTERN to Slot(SlotId.PATTERN, confidence = SlotConfidence.EMPTY),
            SlotId.CONSTRAINTS to Slot(SlotId.CONSTRAINTS, confidence = SlotConfidence.EMPTY) // EMPTY면 제외됨
        )
        
        val gap = GapAnalyzer.analyze(slots)
        assertFalse(gap.isZeroQuestion)
        // CONSTRAINTS는 EMPTY면 우선순위에서 제외하므로 최대 4개
        assertEquals(4, gap.targetSlots.size)
        assertEquals(SlotId.ACTION, gap.targetSlots[0].id)
        assertEquals(SlotId.PATTERN, gap.targetSlots[3].id)
    }

    @Test
    fun `ContextProbeTest - 패턴 클러스터링`() {
        val graph = ProjectGraph(
            files = mapOf(
                "A.kt" to FileNode("A.kt", "A", "A", net.ib.ixpert.ops.wuwagent.service.metagraph.model.SpringFileType.VIEW, net.ib.ixpert.ops.wuwagent.service.metagraph.model.ArchitectureLayer.PRESENTATION,
                    injections = listOf(net.ib.ixpert.ops.wuwagent.service.metagraph.model.DependencyInjection("com.UtilView", "util", net.ib.ixpert.ops.wuwagent.service.metagraph.model.InjectionMethod.FIELD, "com.UtilView"))
                ),
                "B.kt" to FileNode("B.kt", "B", "B", net.ib.ixpert.ops.wuwagent.service.metagraph.model.SpringFileType.VIEW, net.ib.ixpert.ops.wuwagent.service.metagraph.model.ArchitectureLayer.PRESENTATION,
                    injections = listOf(net.ib.ixpert.ops.wuwagent.service.metagraph.model.DependencyInjection("com.UtilView", "util", net.ib.ixpert.ops.wuwagent.service.metagraph.model.InjectionMethod.FIELD, "com.UtilView"))
                )
            ),
            generatedAt = "2023",
            projectRoot = "/",
            relationships = emptyList(),
            statistics = net.ib.ixpert.ops.wuwagent.service.metagraph.model.GraphStatistics()
        )
        val clusters = PatternClusterer.cluster(listOf("A.kt", "B.kt"), graph)
        assertEquals(1, clusters.size)
        assertEquals("UtilView 패턴", clusters[0].name)
        assertEquals(2, clusters[0].usages)
    }

    @Test
    fun `ResponseParserTest - 사용자 거부 처리`() {
        val slot = Slot(SlotId.ACTION, confidence = SlotConfidence.EMPTY, candidates = listOf("Create", "Update"))
        
        val r1 = ResponseParser.parse("알아서 해", slot)
        assertEquals(ResponseParser.ParseResultType.REFUSAL, r1.type)
        
        val r2 = ResponseParser.parse("a", slot)
        assertEquals(ResponseParser.ParseResultType.MAPPED, r2.type)
        assertEquals("Create", r2.updatedSlot?.value)
        
        val r3 = ResponseParser.parse("내 맘대로 작성함", slot)
        assertEquals(ResponseParser.ParseResultType.FREE_FORM, r3.type)
    }
}
