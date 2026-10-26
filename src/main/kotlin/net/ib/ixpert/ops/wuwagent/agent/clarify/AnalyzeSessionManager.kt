package net.ib.ixpert.ops.wuwagent.agent.clarify

import com.intellij.openapi.project.Project
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.Stage0State
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * clarify 대화 세션. 유효 시간은 **마지막 활동 시각**부터 센다(생성 시각이 아니다).
 * 활동 = 세션 조회 성공([AnalyzeSessionManager.getSession]), 발화 처리 완료, 작업 요약 전송.
 */
data class Stage0Session(
    val initialRequirement: String,
    val engine: Stage0ClarificationEngine,
    var currentState: Stage0State,
    var lastTurnResult: Stage0ClarificationEngine.Stage0TurnResult,
    val createdAtMs: Long = System.currentTimeMillis(),
    @Volatile var lastActivityMs: Long = createdAtMs
) {
    /** 마지막 활동 뒤 [ttlMinutes]분을 넘겼는가. [nowMs]는 테스트에서 주입한다. */
    fun isExpired(ttlMinutes: Long = AnalyzeSessionManager.TTL_MINUTES, nowMs: Long = System.currentTimeMillis()): Boolean {
        return nowMs - lastActivityMs > TimeUnit.MINUTES.toMillis(ttlMinutes)
    }

    fun touch(nowMs: Long) {
        lastActivityMs = nowMs
    }
}

object AnalyzeSessionManager {
    // Project base path -> Session
    private val sessions = ConcurrentHashMap<String, Stage0Session>()

    /** 마지막 활동 뒤 이 시간(분)이 지나면 세션이 만료된다. */
    const val TTL_MINUTES = 30L

    /** 현재 시각(ms). 테스트에서 바꿔 쓴다(실제 대기 없이 시간 경과를 흉내 낸다). */
    @Volatile
    var clock: () -> Long = { System.currentTimeMillis() }

    fun saveSession(
        project: Project,
        initialRequirement: String,
        engine: Stage0ClarificationEngine,
        currentState: Stage0State,
        lastTurnResult: Stage0ClarificationEngine.Stage0TurnResult
    ) {
        val basePath = project.basePath ?: return
        val now = clock()
        sessions[basePath] = Stage0Session(
            initialRequirement = initialRequirement,
            engine = engine,
            currentState = currentState,
            lastTurnResult = lastTurnResult,
            createdAtMs = now,
            lastActivityMs = now
        )
    }

    /** 만료되었으면 지우고 null. 성공하면 마지막 활동 시각을 갱신한다. 만료된 세션은 다시 살아나지 않는다. */
    fun getSession(project: Project): Stage0Session? {
        val basePath = project.basePath ?: return null
        val session = sessions[basePath] ?: return null

        val now = clock()
        if (session.isExpired(TTL_MINUTES, now)) {
            sessions.remove(basePath)
            return null
        }
        session.touch(now)
        return session
    }

    /** 발화 처리 완료, 작업 요약 전송 같은 활동 때 마지막 활동 시각을 갱신한다(없거나 이미 지워진 세션은 무시). */
    fun touch(project: Project) {
        val basePath = project.basePath ?: return
        val session = sessions[basePath] ?: return
        val now = clock()
        // 이미 만료된 세션은 활동으로 되살리지 않는다(다음 getSession이 지운다)
        if (!session.isExpired(TTL_MINUTES, now)) session.touch(now)
    }

    /**
     * 분석 시작에 실패해 지웠던 세션을 되돌린다(같은 객체). 그 사이 새 세션이 만들어졌거나 이미 만료됐으면 되돌리지 않는다(만료된 세션은 되살리지 않는다).
     * 되돌리면 마지막 활동 시각을 갱신한다. 돌려주는 값: 되돌렸는가.
     */
    fun restoreSession(project: Project, session: Stage0Session): Boolean {
        val basePath = project.basePath ?: return false
        val now = clock()
        if (session.isExpired(TTL_MINUTES, now)) return false
        val restored = sessions.putIfAbsent(basePath, session) == null
        if (restored) session.touch(now)
        return restored
    }

    fun removeSession(project: Project) {
        val basePath = project.basePath ?: return
        sessions.remove(basePath)
    }

    fun clearAll() {
        sessions.clear()
    }
}
