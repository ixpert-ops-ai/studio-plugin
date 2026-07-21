package net.ib.ixpert.ops.wuwagent.agent.completeness

import java.io.File
import kotlin.system.exitProcess

/**
 * A standalone helper script to periodically generate a human-readable report 
 * from the accumulated shadow_logs.jsonl file.
 */
object ShadowReportGenerator {
    @JvmStatic
    fun main(args: Array<String>) {
        val projectRoot = if (args.isNotEmpty()) args[0] else System.getProperty("user.dir")
        val logFile = File(File(projectRoot, ".wuwagent"), "shadow_logs.jsonl")

        if (!logFile.exists()) {
            println("No shadow logs found at \${logFile.absolutePath}")
            exitProcess(0)
        }

        println("Parsing shadow logs from \${logFile.absolutePath} ...\n")
        
        val parseResult = ShadowLogParser.parse(projectRoot)
        val report = ShadowLogAggregator.aggregate(parseResult)

        println("==================================================")
        println("       COMPLETENESS GUARD: SHADOW REPORT")
        println("==================================================")
        println("1. Overview")
        println("  - Total SRs Evaluated: \${report.totalEvaluatedEvents}")
        println("  - Total Blocked SRs:   \${report.totalBlockedEvents}")
        println("  - Parser Skipped Lines (Data Loss): \${report.totalSkippedLines}")
        println()
        
        println("2. Event-Level Breakdown (SRs)")
        println("  - Fully Known Blocked SRs (Noise): \${report.fullyKnownBlockEvents}")
        println("  - Unknown Blocked SRs (Investigation Target): \${report.unknownBlockEvents}")
        println("    -> This is the denominator for your FP rate calculation.")
        println()
        
        println("3. Violation-Level Details")
        println("  [Root Causes]")
        report.blockedViolationsByRootCause.forEach { (cause, count) ->
            println("    - \$cause: \$count")
        }
        
        println("\n  [Known Debt Reasons]")
        if (report.knownDebtViolationCounts.isEmpty()) {
            println("    - None")
        } else {
            report.knownDebtViolationCounts.forEach { (reason, count) ->
                println("    - \$reason: \$count")
            }
        }
        
        println("\n  [Unknown Violations (Must be manually labeled TP/FP)]")
        if (report.unknownViolationItems.isEmpty()) {
            println("    - None")
        } else {
            report.unknownViolationItems.forEachIndexed { index, item ->
                println("    \${index + 1}. SR: \${item.srKey} | Target: \${item.missingTargetKind} | Anchor: \${item.anchorFile}")
            }
        }
        println("==================================================")
    }
}
