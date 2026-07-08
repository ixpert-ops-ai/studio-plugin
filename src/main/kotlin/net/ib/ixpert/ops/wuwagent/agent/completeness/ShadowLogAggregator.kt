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

data class DedupKey(val srKey: String, val requiredFilesKey: String)

object ShadowLogAggregator {

    private fun deduplicate(logs: List<net.ib.ixpert.ops.wuwagent.agent.completeness.model.ShadowLog>): List<net.ib.ixpert.ops.wuwagent.agent.completeness.model.ShadowLog> {
        // 1. dataQualityAnomaly != null 제외
        // 2. requiredFiles에 survey_admin/ 또는 member-market/ 포함 시 제외
        val validLogs = logs.filter { log ->
            log.dataQualityAnomaly == null &&
            !log.requiredFiles.any { it.contains("survey_admin/") || it.contains("member-market/") }
        }

        // 3. DedupKey 기준으로 그룹핑
        val groupedLogs = validLogs.groupBy {
            val key = it.requiredFiles.sorted().joinToString("\u0000")
            DedupKey(it.srKey, key)
        }

        // 4. 각 그룹 내에서 timestamp 최신 데이터만 선택 (Last-Wins)
        return groupedLogs.values.mapNotNull { group ->
            group.maxByOrNull { it.timestamp }
        }
    }

    fun aggregate(parseResult: ShadowLogParseResult): AggregationReport {
        val dedupedLogs = deduplicate(parseResult.logs)
        
        // After deduplication, each DedupKey has exactly 1 event (Last-Wins)
        // Group by srKey to calculate metrics. Each srKey might have multiple events (if they had different requiredFiles)
        val groupedLogs = dedupedLogs.groupBy { it.srKey }
        val totalEvaluatedEvents = groupedLogs.size
        
        var totalBlockedEvents = 0
        var fullyKnownBlockEvents = 0
        var unknownBlockEvents = 0

        val blockedViolationsByRootCause = mutableMapOf<RootCause?, Int>()
        val knownDebtViolationCounts = mutableMapOf<String, Int>()
        val unknownViolationItems = mutableListOf<UnknownBlockItem>()

        for ((srKey, srLogs) in groupedLogs) {
            val isBlocked = srLogs.any { it.verdict == "WOULD_BLOCK" }
            if (isBlocked) {
                totalBlockedEvents++
                
                var hasUnknown = false
                var hasViolations = false
                
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
