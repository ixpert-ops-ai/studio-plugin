package net.ib.ixpert.ops.wuwagent.agent.clarify

import com.intellij.openapi.project.Project
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.Stage0State
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class Stage0Session(
    val initialRequirement: String,
    val engine: Stage0ClarificationEngine,
    var currentState: Stage0State,
    var lastTurnResult: Stage0ClarificationEngine.Stage0TurnResult,
    val createdAtMs: Long = System.currentTimeMillis()
) {
    fun isExpired(ttlMinutes: Long = 5): Boolean {
        return System.currentTimeMillis() - createdAtMs > TimeUnit.MINUTES.toMillis(ttlMinutes)
    }
}

object AnalyzeSessionManager {
    // Project base path -> Session
    private val sessions = ConcurrentHashMap<String, Stage0Session>()
    private const val TTL_MINUTES = 5L

    fun saveSession(
        project: Project,
        initialRequirement: String,
        engine: Stage0ClarificationEngine,
        currentState: Stage0State,
        lastTurnResult: Stage0ClarificationEngine.Stage0TurnResult
    ) {
        val basePath = project.basePath ?: return
        sessions[basePath] = Stage0Session(
            initialRequirement = initialRequirement,
            engine = engine,
            currentState = currentState,
            lastTurnResult = lastTurnResult
        )
    }

    fun getSession(project: Project): Stage0Session? {
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

    fun clearAll() {
        sessions.clear()
    }
}
