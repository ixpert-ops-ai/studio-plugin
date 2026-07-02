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
                        // Ghost Line Defense (Using isNullOrEmpty because Gson reflection can inject null into non-nullable List)
                        if (log.verdict == "WOULD_BLOCK" && log.violations.isNullOrEmpty()) {
                            val preview = if (line.length > 100) line.take(100) + "..." else line
                            logger.warn("[GUARD-SHADOW] Skipped ghost line (WOULD_BLOCK but no violations): \$preview")
                            anomalyCount++
                        } else {
                            logs.add(log)
                        }
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

        return ShadowLogParseResult(logs, skippedCount, anomalyCount)
    }
}
