package net.ib.ixpert.ops.wuwagent.agent.completeness

import org.junit.Test
import java.io.File

class ShadowLogRealDataTest {
    @Test
    fun `analyze real data`() {
        val projectRoot = "C:\\Workspace\\HC_card_survey_admin"
        val logFile = File(projectRoot, ".wuwagent\\shadow_logs.jsonl")
        if (!logFile.exists()) {
            println("File not found")
            return
        }
        
        val result = ShadowLogParser.parse(projectRoot)
        val report = ShadowLogAggregator.aggregate(result)

        println("=== 1.5 Real Data Verification Results ===")
        println("Raw log lines parsed: ${result.logs.size}")
        println("Skipped lines (Malformed/Physical Corruption): ${result.skippedCount}")
        println("Data Quality Anomalies (Ghost lines): ${result.dataQualityAnomalyCount}")
        
        println("Total Evaluated Events (After Deduplication): ${report.totalEvaluatedEvents}")
        println("Total Blocked Events: ${report.totalBlockedEvents}")
        println("Fully Known Block Events: ${report.fullyKnownBlockEvents}")
        println("Unknown Block Events: ${report.unknownBlockEvents}")
        
        var excludedCount = 0
        var validLogsBeforeDedup = 0
        
        for (log in result.logs) {
            val isExcluded = log.requiredFiles.any { it.contains("survey_admin/") }
            if (isExcluded) {
                excludedCount++
            } else if (log.dataQualityAnomaly == null) {
                validLogsBeforeDedup++
            }
        }
        
        println("Excluded Project Logs (survey_admin): $excludedCount")
        println("Valid Logs before dedup (anomaly & excluded removed): $validLogsBeforeDedup")
        println("==========================================")
    }
}
