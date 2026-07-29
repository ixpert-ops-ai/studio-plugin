package net.ib.ixpert.ops.wuwagent.agent.completeness.model

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.InputStreamReader

enum class DebtReason {
    ACTUAL_DEBT,         // Genuine architectural defect (e.g., missing XML), accepted by project team
    HEURISTIC_LIMITATION // FP due to matching limitations (e.g., N suffix), suppressed by guard developers
}

data class SuppressionRecord(
    val anchorFilePath: String,        // File path as it appears in the graph or violation
    val missingCompanionKind: FileKind,
    val expectedTargetName: String? = null, // Optional exact name of the missed target if needed
    val reason: DebtReason,
    val evidence: String               // Reason for this debt/suppression
)

interface KnownDebtRegistry {
    fun isSuppressed(violation: CompanionFinding): Boolean
    fun getAllRecords(): List<SuppressionRecord>
}

class JsonKnownDebtRegistry(
    private val records: List<SuppressionRecord>
) : KnownDebtRegistry {
    
    override fun isSuppressed(violation: CompanionFinding): Boolean {
        return records.any { record ->
            // Path must match (using endsWith to be resilient to path prefixes)
            val pathMatches = violation.anchorPath.replace("\\", "/").endsWith(record.anchorFilePath.replace("\\", "/"))
            val kindMatches = violation.companionKind == record.missingCompanionKind
            
            // To check expected target name, we'd need it in the violation. 
            // In MatchResult, note might contain it, but for now we rely strictly on anchor + kind.
            val targetMatches = if (record.expectedTargetName != null) {
                // If expectedTargetName is specified, we check if the match result note or matched path contains it.
                // It's a heuristic fallback to refine suppression safely.
                val matchedPath = violation.result.matchedPath ?: ""
                val note = violation.result.note
                matchedPath.contains(record.expectedTargetName) || note.contains(record.expectedTargetName)
            } else true

            pathMatches && kindMatches && targetMatches
        }
    }
    
    override fun getAllRecords(): List<SuppressionRecord> = records

    companion object {
        // Caching for concurrency and I/O optimization
        private val classPathCache = java.util.concurrent.ConcurrentHashMap<String, JsonKnownDebtRegistry>()
        internal var classpathReadCount = java.util.concurrent.atomic.AtomicInteger(0)

        fun loadFromClasspath(vararg paths: String): JsonKnownDebtRegistry {
            val cacheKey = paths.joinToString(",")
            return classPathCache.computeIfAbsent(cacheKey) {
                classpathReadCount.incrementAndGet()
                val gson = Gson()
                val allRecords = mutableListOf<SuppressionRecord>()
                for (path in paths) {
                    val url = this::class.java.classLoader.getResource(path)
                    if (url != null) {
                        val listType = object : TypeToken<List<SuppressionRecord>>() {}.type
                        val records: List<SuppressionRecord> = gson.fromJson(InputStreamReader(url.openStream()), listType)
                        allRecords.addAll(records)
                    }
                }
                JsonKnownDebtRegistry(allRecords)
            }
        }
        
        // For tests that want to force a fresh load
        internal fun clearCache() {
            classPathCache.clear()
            classpathReadCount.set(0)
        }
        
        fun loadFromFiles(vararg files: File): JsonKnownDebtRegistry {
            val gson = Gson()
            val allRecords = mutableListOf<SuppressionRecord>()
            for (file in files) {
                if (file.exists()) {
                    val listType = object : TypeToken<List<SuppressionRecord>>() {}.type
                    val records: List<SuppressionRecord> = gson.fromJson(file.readText(), listType)
                    allRecords.addAll(records)
                }
            }
            return JsonKnownDebtRegistry(allRecords)
        }
        
        fun empty(): JsonKnownDebtRegistry = JsonKnownDebtRegistry(emptyList())
    }
}
