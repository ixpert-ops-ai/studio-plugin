package net.ib.ixpert.ops.wuwagent.agent.completeness.analytics

import net.ib.ixpert.ops.wuwagent.agent.completeness.ShadowLogAggregator
import net.ib.ixpert.ops.wuwagent.agent.completeness.model.ShadowLog

data class ShadowMetrics(
    val totalRawLogs: Int,
    val totalDedupedEvents: Int,
    val totalPassEvents: Int,
    val totalWouldBlockEvents: Int,
    val totalWarnEvents: Int,
    val companionViolationFrequencies: Map<String, Int>,
    val acceptedDebtFrequencies: Map<String, Int>,
    val mandatoryFPRate: Double,
    val recommendedNoiseRate: Double
)

object ShadowMetricsCollector {
    fun collect(rawLogs: List<ShadowLog>): ShadowMetrics {
        val dedupedLogs = ShadowLogAggregator.deduplicate(rawLogs)
        
        var passCount = 0
        var blockCount = 0
        var warnCount = 0
        
        var totalMandatoryTPs = 0
        var totalMandatoryFPs = 0
        var totalRecommendedFPs = 0
        var totalRecommendedMissing = 0
        
        val companionFreq = mutableMapOf<String, Int>()
        val acceptedDebtFreq = mutableMapOf<String, Int>()
        
        for (rawLog in dedupedLogs) {
            val log = rawLog.normalized()
            when (log.verdict) {
                "PASS" -> passCount++
                "WOULD_BLOCK" -> blockCount++
                "WARN" -> warnCount++
            }
            
            log.violations?.filter { it.type == "CompanionMissing" }?.forEach { v ->
                val kind = v.missingTargetKind ?: "UNKNOWN"
                companionFreq[kind] = (companionFreq[kind] ?: 0) + 1
                if (v.pairing == "MANDATORY") {
                    totalMandatoryTPs++
                }
            }
            
            log.acceptedDebts?.forEach { v ->
                val kind = v.missingTargetKind ?: "UNKNOWN"
                acceptedDebtFreq[kind] = (acceptedDebtFreq[kind] ?: 0) + 1
                if (v.pairing == "MANDATORY") {
                    totalMandatoryFPs++
                } else if (v.pairing == "RECOMMENDED") {
                    totalRecommendedFPs++
                }
            }
            
            log.recommendations?.filter { it.pairingStrength == "RECOMMENDED" && !it.satisfied }?.forEach {
                totalRecommendedMissing++
            }
        }
        
        val totalMandatoryWouldBlocks = totalMandatoryTPs + totalMandatoryFPs
        val mandatoryFPRate = if (totalMandatoryWouldBlocks > 0) {
            totalMandatoryFPs.toDouble() / totalMandatoryWouldBlocks
        } else 0.0
        
        val recommendedNoiseRate = if (totalRecommendedMissing > 0) {
            totalRecommendedFPs.toDouble() / totalRecommendedMissing
        } else 0.0
        
        return ShadowMetrics(
            totalRawLogs = rawLogs.size,
            totalDedupedEvents = dedupedLogs.size,
            totalPassEvents = passCount,
            totalWouldBlockEvents = blockCount,
            totalWarnEvents = warnCount,
            companionViolationFrequencies = companionFreq,
            acceptedDebtFrequencies = acceptedDebtFreq,
            mandatoryFPRate = mandatoryFPRate,
            recommendedNoiseRate = recommendedNoiseRate
        )
    }
}
