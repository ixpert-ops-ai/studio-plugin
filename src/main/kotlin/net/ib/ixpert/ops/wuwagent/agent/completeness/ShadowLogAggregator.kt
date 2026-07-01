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
        val totalEvaluatedEvents = logs.size
        
        val blockedLogs = logs.filter { it.verdict == "WOULD_BLOCK" }
        val totalBlockedEvents = blockedLogs.size

        var fullyKnownBlockEvents = 0
        var unknownBlockEvents = 0

        val blockedViolationsByRootCause = mutableMapOf<RootCause?, Int>()
        val knownDebtViolationCounts = mutableMapOf<String, Int>()
        val unknownViolationItems = mutableListOf<UnknownBlockItem>()

        for (log in blockedLogs) {
            var hasUnknown = false
            
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

            if (hasUnknown) {
                unknownBlockEvents++
            } else if (log.violations.isNotEmpty()) {
                // If there are violations but none are unknown, it's fully known debt
                fullyKnownBlockEvents++
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
