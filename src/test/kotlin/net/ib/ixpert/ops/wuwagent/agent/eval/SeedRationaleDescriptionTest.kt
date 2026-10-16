package net.ib.ixpert.ops.wuwagent.agent.eval

import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.AgenticGraphTools
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * B-43 시드 확정 도구의 rationale 길이 지시: 재생으로 검증한 설명과 같은 문구가 운영 정의에 들어 있고,
 * seed_classes 설명·인자 순서·도구 순서는 그대로다.
 */
class SeedRationaleDescriptionTest {

    @Test
    fun confirmToolOnlyChangesRationaleLengthInstruction() {
        val names = AgenticGraphTools.DEFINITIONS.map { it.function.name }
        assertEquals(listOf("search_graph_nodes", "inspect_node_detail", "expand_connected_nodes", "confirm_final_seeds"), names)
        val confirm = AgenticGraphTools.DEFINITIONS.first { it.function.name == "confirm_final_seeds" }.function
        assertEquals(listOf("seed_classes", "rationale"), confirm.parameters.properties.keys.toList())
        assertEquals(listOf("seed_classes", "rationale"), confirm.parameters.required)
        assertEquals("최종 확정된 Seed 클래스명 목록", confirm.parameters.properties.getValue("seed_classes").description)
        assertEquals(SeedRationaleReplayTest.AFTER_DESCRIPTION, confirm.parameters.properties.getValue("rationale").description)
    }
}
