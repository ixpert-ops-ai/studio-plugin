package net.ib.ixpert.ops.wuwagent.service.metagraph.analyzer

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*

/**
 * Anyframe VO 변환 체인 분석기.
 * SVO ↔ BVO ↔ DVO 간의 데이터 변환 관계를 분석하여 TRANSFORMS_VO 관계를 매핑합니다.
 * 1단계에서는 네이밍 규칙 기반 접두사 동일 여부 판단 및 연관 매핑(inferVoChainByNaming)을 수행하고
 * 상세 정보는 Relationship metadata에 기록합니다.
 */
class AnyframeVoChainAnalyzer {

    fun analyze(nodes: Map<String, FileNode>): List<Relationship> {
        val relationships = mutableListOf<Relationship>()

        val svos = nodes.filter { it.value.anyframeRole == AnyframeRole.SVO }
        val bvos = nodes.filter { it.value.anyframeRole == AnyframeRole.BVO }
        val dvos = nodes.filter { it.value.anyframeRole == AnyframeRole.DVO }

        // 1. SVO ↔ BVO 네이밍 접두사 매칭
        for ((sPath, sNode) in svos) {
            val sPrefix = sNode.className.removeSuffix("SVO").removeSuffix("Svo")
            if (sPrefix.isEmpty()) continue

            for ((bPath, bNode) in bvos) {
                val bPrefix = bNode.className.removeSuffix("BVO").removeSuffix("Bvo")
                if (sPrefix == bPrefix) {
                    relationships.add(
                        Relationship(
                            source = sPath,
                            target = bPath,
                            type = RelationshipType.TRANSFORMS_VO,
                            strength = RelationshipStrength.DIRECT,
                            detail = "SVO ↔ BVO Naming Match",
                            metadata = mapOf(
                                "sourceVo" to sNode.className,
                                "targetVo" to bNode.className,
                                "direction" to "INBOUND",
                                "transformType" to "NAMING_CONVENTION"
                            )
                        )
                    )
                }
            }
        }

        // 2. BVO ↔ DVO 네이밍 매칭 및 업무 연관성 추론 (접두사 교차 검증)
        for ((bPath, bNode) in bvos) {
            val bPrefix = bNode.className.removeSuffix("BVO").removeSuffix("Bvo")
            if (bPrefix.isEmpty()) continue

            for ((dPath, dNode) in dvos) {
                val dPrefix = dNode.className.removeSuffix("DVO").removeSuffix("Dvo")
                if (dPrefix.isEmpty()) continue

                // 접두사 교차 검증 (예: 앞부분 5자리 이상 일치하거나 포함 관계)
                val isOverlap = bPrefix.startsWith(dPrefix) || dPrefix.startsWith(bPrefix) ||
                        (bPrefix.length >= 5 && dPrefix.startsWith(bPrefix.substring(0, 5))) ||
                        (dPrefix.length >= 5 && bPrefix.startsWith(dPrefix.substring(0, 5)))

                if (isOverlap) {
                    relationships.add(
                        Relationship(
                            source = bPath,
                            target = dPath,
                            type = RelationshipType.TRANSFORMS_VO,
                            strength = RelationshipStrength.INDIRECT,
                            detail = "BVO ↔ DVO Naming Match",
                            metadata = mapOf(
                                "sourceVo" to bNode.className,
                                "targetVo" to dNode.className,
                                "direction" to "INBOUND",
                                "transformType" to "NAMING_CONVENTION"
                            )
                        )
                    )
                }
            }
        }

        return relationships
    }
}
