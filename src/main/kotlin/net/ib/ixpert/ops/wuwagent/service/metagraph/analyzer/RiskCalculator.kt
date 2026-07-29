package net.ib.ixpert.ops.wuwagent.service.metagraph.analyzer

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*

object RiskCalculator {
    fun calculate(node: FileNode, inboundCountOverride: Int? = null, outboundCountOverride: Int? = null): RiskAssessment {
        var score = 0
        val reasons = mutableListOf<String>()

        // 1. Base Layer (기본 계층 위험도)
        val baseScore = when (node.fileType) {
            SpringFileType.CONTROLLER, SpringFileType.REST_CONTROLLER, SpringFileType.CONFIG, SpringFileType.FILTER, SpringFileType.INTERCEPTOR, SpringFileType.BIZ -> 3
            SpringFileType.REPOSITORY, SpringFileType.MAPPER, SpringFileType.SERVICE, SpringFileType.COMPONENT, SpringFileType.DATA_ACCESS, SpringFileType.BIZ_UTIL -> 2
            SpringFileType.UTIL, SpringFileType.DTO, SpringFileType.VO, SpringFileType.ENTITY, SpringFileType.VIEW, SpringFileType.UNKNOWN,
            SpringFileType.INTERFACE, SpringFileType.ABSTRACT_CLASS, SpringFileType.ENUM, SpringFileType.EXCEPTION_HANDLER, SpringFileType.EXCEPTION, SpringFileType.TEST, SpringFileType.SERVICE_INTERFACE -> 1
        }
        score += baseScore
        reasons.add("기본 계층 위험도 [${node.fileType}]: +$baseScore")

        // 2. Inbound Dependencies (피의존성)
        val inboundCount = inboundCountOverride ?: (node.dependedBy.size + node.usedByTypes.size)
        if (inboundCount > 0) {
            score += inboundCount
            reasons.add("${inboundCount}개의 파일에서 이 파일을 의존/호출함: +$inboundCount")
        }

        // 3. Outbound API (엔드포인트 노출)
        val apiCount = node.apiEndpoints.size
        if (apiCount > 0) {
            score += apiCount
            reasons.add("${apiCount}개의 API 엔드포인트 노출: +$apiCount")
        }

        // 4. Complexity (복잡도: 외부 호출)
        val outboundCalls = outboundCountOverride ?: (node.dependsOn.size + node.usesTypes.size)
        if (outboundCalls > 0) {
            val callScore = (outboundCalls * 0.5).toInt()
            if (callScore > 0) {
                score += callScore
                reasons.add("${outboundCalls}개의 외부 클래스 호출 (복잡도): +$callScore")
            }
        }

        // 5. ChangeRisk 분류
        val risk = when {
            score >= 8 -> ChangeRisk.CRITICAL
            score >= 5 -> ChangeRisk.HIGH
            score >= 3 -> ChangeRisk.MEDIUM
            else -> ChangeRisk.LOW
        }

        return RiskAssessment(riskScore = score, changeRisk = risk, riskReasons = reasons)
    }
}
