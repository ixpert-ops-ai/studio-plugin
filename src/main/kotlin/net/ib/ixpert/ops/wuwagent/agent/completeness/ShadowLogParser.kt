package net.ib.ixpert.ops.wuwagent.agent.completeness

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.intellij.openapi.diagnostic.Logger
import net.ib.ixpert.ops.wuwagent.agent.completeness.model.ShadowLog
import java.io.File
import java.nio.file.Files

data class ShadowLogParseResult(
    val logs: List<ShadowLog>,
    val skippedCount: Int,
    val dataQualityAnomalyCount: Int
)

object ShadowLogParser {
    private val logger = Logger.getInstance(ShadowLogParser::class.java)
    private val gson = Gson()

    /**
     * Parses the shadow_logs.jsonl file line by line.
     * Skips broken or truncated lines and returns the count of skipped lines.
     */
    fun parse(projectRoot: String): ShadowLogParseResult {
        val logFile = File(File(projectRoot, ".wuwagent"), "shadow_logs.jsonl")
        
        if (!logFile.exists()) {
            return ShadowLogParseResult(emptyList(), 0, 0)
        }

        val logs = mutableListOf<ShadowLog>()
        var skippedCount = 0
        var anomalyCount = 0

        try {
            val lines = Files.readAllLines(logFile.toPath(), Charsets.UTF_8)
            for (line in lines) {
                if (line.isBlank()) continue
                
                try {
                    val log = gson.fromJson(line, ShadowLog::class.java)
                    if (log != null) {
                        // Data Quality Anomaly Defense
                        var currentAnomaly = log.dataQualityAnomaly
                        
                        // Fallback detection for legacy logs without dataQualityAnomaly
                        if (currentAnomaly == null) {
                            if (log.verdict == "WOULD_BLOCK" && log.violations.isNullOrEmpty()) {
                                currentAnomaly = "GHOST_VERDICT_BLOCK_WITHOUT_VIOLATIONS"
                            } else if (log.verdict == "PASS" && !log.violations.isNullOrEmpty()) {
                                currentAnomaly = "INCONSISTENT_PASS_WITH_VIOLATIONS"
                            }
                        }
                        
                        val finalLog = if (currentAnomaly != null && log.dataQualityAnomaly == null) {
                            val safeViolations = log.violations ?: emptyList()
                            val safeAcceptedDebts = log.acceptedDebts ?: emptyList()
                            val safeSrFactsSource = log.srFactsSource ?: "heuristic"
                            log.copy(dataQualityAnomaly = currentAnomaly, violations = safeViolations, acceptedDebts = safeAcceptedDebts, srFactsSource = safeSrFactsSource)
                        } else {
                            val safeViolations = log.violations ?: emptyList()
                            val safeAcceptedDebts = log.acceptedDebts ?: emptyList()
                            val safeSrFactsSource = log.srFactsSource ?: "heuristic"
                            if (log.violations == null || log.acceptedDebts == null || log.srFactsSource == null) log.copy(violations = safeViolations, acceptedDebts = safeAcceptedDebts, srFactsSource = safeSrFactsSource) else log
                        }

                        if (finalLog.dataQualityAnomaly != null) {
                            anomalyCount++
                        }
                        
                        logs.add(finalLog)
                    } else {
                        skippedCount++
                    }
                } catch (e: JsonSyntaxException) {
                    val preview = if (line.length > 100) line.take(100) + "..." else line
                    logger.warn("[GUARD-SHADOW] Skipped broken log line: \$preview")
                    skippedCount++
                } catch (e: Exception) {
                    val preview = if (line.length > 100) line.take(100) + "..." else line
                    logger.warn("[GUARD-SHADOW] Failed to parse log line: \$preview", e)
                    skippedCount++
                }
            }
        } catch (e: Exception) {
            logger.error("[GUARD-SHADOW] Failed to read shadow_logs.jsonl", e)
        }
        
        if (skippedCount > 0) {
            logger.warn("[GUARD-SHADOW] Skipped $skippedCount malformed lines due to physical corruption or parse errors.")
        }

        return ShadowLogParseResult(logs, skippedCount, anomalyCount)
    }
}
