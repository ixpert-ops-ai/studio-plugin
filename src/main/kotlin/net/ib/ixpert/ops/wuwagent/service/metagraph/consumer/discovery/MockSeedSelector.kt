package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraphQueryable

class MockSeedSelector(private val fixtures: Map<String, SeedSelectionResult>) : SeedSelector {
    override fun selectSeeds(srText: String, graph: ProjectGraphQueryable): SeedSelectionResult {
        // srText에 포함된 식별자(TC-01 등)로 fixture 매칭, 없으면 첫 번째 값이나 기본값 반환
        val tcId = Regex("TC-\\d+").find(srText)?.value
        
        if (tcId != null && fixtures.containsKey(tcId)) {
            return fixtures[tcId]!!
        }
        
        // 매칭되지 않을 경우 기본 Fallback 동작 (테스트가 깨지지 않게 방어)
        return fixtures.values.firstOrNull() ?: SeedSelectionResult(
            seedClasses = emptyList(),
            changeIntent = ChangeIntent.MODIFY,
            layerHint = emptyList(),
            frontendRelevant = false,
            reasoning = "Mock Default"
        )
    }
}
