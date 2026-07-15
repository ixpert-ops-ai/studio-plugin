package net.ib.ixpert.ops.wuwagent.agent.completeness

import com.google.gson.Gson
import com.intellij.openapi.diagnostic.Logger
import net.ib.ixpert.ops.wuwagent.agent.completeness.model.ShadowLog
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption

object ShadowLogger {
    private val logger = Logger.getInstance(ShadowLogger::class.java)
    private val gson = Gson()

    private fun detectAnomaly(verdict: String, violations: List<net.ib.ixpert.ops.wuwagent.agent.completeness.model.ViolationDetail>?): String? {
        val hasViolations = violations != null && violations.isNotEmpty()
        return when {
            verdict == "WOULD_BLOCK" && !hasViolations ->
                "GHOST_VERDICT_BLOCK_WITHOUT_VIOLATIONS"
            verdict == "PASS" && hasViolations ->
                "INCONSISTENT_PASS_WITH_VIOLATIONS"
            else -> null
        }
    }

    @Synchronized
    fun log(projectRoot: String?, shadowLog: ShadowLog) {
        if (projectRoot == null) {
            logger.warn("[GUARD-SHADOW] projectRoot is null, cannot write shadow_logs.jsonl")
            return
        }
        
        try {
            val dir = File(projectRoot, ".wuwagent")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            
            val anomaly = detectAnomaly(shadowLog.verdict, shadowLog.violations)
            val finalLog = shadowLog.copy(dataQualityAnomaly = anomaly)
            if (anomaly != null) {
                logger.warn("Shadow data anomaly detected: $anomaly (srKey=${finalLog.srKey}, runId=${finalLog.runId})")
            }
            
            val logFile = File(dir, "shadow_logs.jsonl")
            val jsonLine = gson.toJson(finalLog)
            
            Files.write(
                logFile.toPath(),
                (jsonLine + "\n").toByteArray(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            )
        } catch (e: Exception) {
            logger.error("[GUARD-SHADOW] Failed to write to shadow_logs.jsonl", e)
        }
    }
}
