package net.ib.ixpert.ops.wuwagent.agent.clarify

import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class AnalyzeSession(
    val initialRequirement: String,
    val clarifyResult: ClarifyResult,
    val createdAtMs: Long = System.currentTimeMillis()
) {
    fun isExpired(ttlMinutes: Long = 5): Boolean {
        return System.currentTimeMillis() - createdAtMs > TimeUnit.MINUTES.toMillis(ttlMinutes)
    }
}

object AnalyzeSessionManager {
    // Project base path -> Session
    private val sessions = ConcurrentHashMap<String, AnalyzeSession>()
    private const val TTL_MINUTES = 5L

    fun saveSession(project: Project, initialRequirement: String, clarifyResult: ClarifyResult) {
        val basePath = project.basePath ?: return
        sessions[basePath] = AnalyzeSession(initialRequirement, clarifyResult)
    }

    fun getSession(project: Project): AnalyzeSession? {
        val basePath = project.basePath ?: return null
        val session = sessions[basePath] ?: return null
        
        if (session.isExpired(TTL_MINUTES)) {
            sessions.remove(basePath)
            return null
        }
        return session
    }

    fun removeSession(project: Project) {
        val basePath = project.basePath ?: return
        sessions.remove(basePath)
    }
}
