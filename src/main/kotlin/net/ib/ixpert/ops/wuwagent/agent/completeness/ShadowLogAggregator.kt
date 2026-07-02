package net.ib.ixpert.ops.wuwagent.agent.completeness

import net.ib.ixpert.ops.wuwagent.agent.completeness.model.RootCause

data class UnknownBlockItem(
    val srKey: String,
    val anchorFile: String?,
    val missingTargetKind: String?
)

data class AggregationReport(
    val totalEvaluatedEvents: Int,
    val totalBlockedEvents: Int,
    val fullyKnownBlockEvents: Int,
    val unknownBlockEvents: Int,
    
    val totalSkippedLines: Int,
    
    val blockedViolationsByRootCause: Map<RootCause?, Int>,
    val knownDebtViolationCounts: Map<String, Int>,
    val unknownViolationItems: List<UnknownBlockItem>
)

object ShadowLogAggregator {

    fun aggregate(parseResult: ShadowLogParseResult): AggregationReport {
        val logs = parseResult.logs
        
        // Group by srKey to enforce "1 SR = 1 Event" deduplication policy (ii)
        val groupedLogs = logs.groupBy { it.srKey }
        val totalEvaluatedEvents = groupedLogs.size
        
        var totalBlockedEvents = 0
        var fullyKnownBlockEvents = 0
        var unknownBlockEvents = 0

        val blockedViolationsByRootCause = mutableMapOf<RootCause?, Int>()
        val knownDebtViolationCounts = mutableMapOf<String, Int>()
        val unknownViolationItems = mutableListOf<UnknownBlockItem>()

        for ((srKey, srLogs) in groupedLogs) {
            // Deduplication Policy (ii): If at least one log in the group has WOULD_BLOCK, the SR is blocked
            val isBlocked = srLogs.any { it.verdict == "WOULD_BLOCK" }
            if (isBlocked) {
                totalBlockedEvents++
                
                var hasUnknown = false
                var hasViolations = false
                
                // Collect violations across all WOULD_BLOCK logs in this SR
                for (log in srLogs.filter { it.verdict == "WOULD_BLOCK" }) {
                    if (log.violations.isNotEmpty()) {
                        hasViolations = true
                    }
                    for (v in log.violations) {
                        blockedViolationsByRootCause[v.rootCause] = (blockedViolationsByRootCause[v.rootCause] ?: 0) + 1
                        
                        if (v.isKnownDebt) {
                            val reason = v.debtReason ?: "UNKNOWN_REASON"
                            knownDebtViolationCounts[reason] = (knownDebtViolationCounts[reason] ?: 0) + 1
                        } else {
                            hasUnknown = true
                            unknownViolationItems.add(
                                UnknownBlockItem(
                                    srKey = log.srKey,
                                    anchorFile = v.anchorFile,
                                    missingTargetKind = v.missingTargetKind
                                )
                            )
                        }
                    }
                }

                if (hasUnknown) {
                    unknownBlockEvents++
                } else if (hasViolations) {
                    // If there are violations but none are unknown, it's fully known debt
                    fullyKnownBlockEvents++
                }
            }
        }

        return AggregationReport(
            totalEvaluatedEvents = totalEvaluatedEvents,
            totalBlockedEvents = totalBlockedEvents,
            fullyKnownBlockEvents = fullyKnownBlockEvents,
            unknownBlockEvents = unknownBlockEvents,
            totalSkippedLines = parseResult.skippedCount,
            blockedViolationsByRootCause = blockedViolationsByRootCause,
            knownDebtViolationCounts = knownDebtViolationCounts,
            unknownViolationItems = unknownViolationItems
        )
    }
}
