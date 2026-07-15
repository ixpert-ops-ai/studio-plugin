package net.ib.ixpert.ops.wuwagent.agent.completeness.analytics

object DashboardGenerator {
    fun generateMarkdown(metrics: ShadowMetrics): String {
        val sb = StringBuilder()
        sb.appendLine("# Completeness Guard: SHADOW Mode Analytics Dashboard")
        sb.appendLine()
        sb.appendLine("## High-Level Summary")
        sb.appendLine("- **Total Raw Logs Collected**: ${metrics.totalRawLogs}")
        sb.appendLine("- **Total Deduped Events (Unique Runs)**: ${metrics.totalDedupedEvents}")
        sb.appendLine()
        
        if (metrics.totalDedupedEvents == 0) {
            sb.appendLine("> No valid events to report.")
            return sb.toString()
        }
        
        val passPct = (metrics.totalPassEvents.toDouble() / metrics.totalDedupedEvents * 100).format(1)
        val blockPct = (metrics.totalWouldBlockEvents.toDouble() / metrics.totalDedupedEvents * 100).format(1)
        val warnPct = (metrics.totalWarnEvents.toDouble() / metrics.totalDedupedEvents * 100).format(1)

        sb.appendLine("## Verdict Distribution")
        sb.appendLine("```mermaid")
        sb.appendLine("pie title Verdicts")
        sb.appendLine("    \"PASS\" : ${metrics.totalPassEvents}")
        sb.appendLine("    \"WOULD_BLOCK\" : ${metrics.totalWouldBlockEvents}")
        if (metrics.totalWarnEvents > 0) sb.appendLine("    \"WARN\" : ${metrics.totalWarnEvents}")
        sb.appendLine("```")
        sb.appendLine()
        sb.appendLine("- **PASS**: ${metrics.totalPassEvents} ($passPct%)")
        sb.appendLine("- **WOULD_BLOCK**: ${metrics.totalWouldBlockEvents} ($blockPct%)")
        if (metrics.totalWarnEvents > 0) {
            sb.appendLine("- **WARN**: ${metrics.totalWarnEvents} ($warnPct%)")
        }
        sb.appendLine()

        sb.appendLine("## Missing Companion Frequencies (True Missing)")
        if (metrics.companionViolationFrequencies.isEmpty()) {
            sb.appendLine("*No missing companions detected!*")
        } else {
            sb.appendLine("| Companion Kind | Frequency |")
            sb.appendLine("|----------------|-----------|")
            metrics.companionViolationFrequencies.entries.sortedByDescending { it.value }.forEach { (kind, count) ->
                sb.appendLine("| $kind | $count |")
            }
        }
        sb.appendLine()

        sb.appendLine("## Accepted Debts Frequencies (Known Debt)")
        if (metrics.acceptedDebtFrequencies.isEmpty()) {
            sb.appendLine("*No known debts triggered.*")
        } else {
            sb.appendLine("| Companion Kind | Frequency |")
            sb.appendLine("|----------------|-----------|")
            metrics.acceptedDebtFrequencies.entries.sortedByDescending { it.value }.forEach { (kind, count) ->
                sb.appendLine("| $kind | $count |")
            }
        }
        sb.appendLine()

        sb.appendLine("## Two-Tier Precision Metrics")
        sb.appendLine("> **Note**: These metrics evaluate the safety of transitioning to `ENFORCING` mode.")
        sb.appendLine("- **MANDATORY FP Rate (차단 FP율)**: ${(metrics.mandatoryFPRate * 100).format(1)}%")
        sb.appendLine("  - *Goal: ≤ 5.0%*")
        sb.appendLine("- **RECOMMENDED Noise Rate (참고용 노이즈율)**: ${(metrics.recommendedNoiseRate * 100).format(1)}%")
        sb.appendLine()
        
        return sb.toString()
    }
    
    private fun Double.format(digits: Int) = "%.${digits}f".format(this)
}
