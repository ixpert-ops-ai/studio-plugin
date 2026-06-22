package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraphQueryable

/**
 * Phase 1: Seed Selection을 담당하는 인터페이스
 */
interface SeedSelector {
    /**
     * @param srText 사용자 요구사항(SR)
     * @param graph 프로젝트 구조 그래프
     * @return LLM이 선정한 Seed Selection 결과
     */
    fun selectSeeds(srText: String, graph: ProjectGraphQueryable): SeedSelectionResult
}
