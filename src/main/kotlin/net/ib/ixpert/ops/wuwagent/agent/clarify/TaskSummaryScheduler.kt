package net.ib.ixpert.ops.wuwagent.agent.clarify

import net.ib.ixpert.ops.wuwagent.agent.clarify.model.Stage0State
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 작업 요약(화면 표시 전용)을 턴 응답 밖에서 만든다.
 * - [schedule]은 바로 돌아오며 이번 턴의 번호(turn)를 돌려준다. 요약은 별도 스레드에서 만들어 [deliver]/[fail]로 그 번호와 함께 전달한다.
 * - 새 턴이 오거나 [invalidate]가 불리면 이전 요약은 폐기한다: 시작 전이면 LLM을 부르지 않고(호출 합침), 만드는 중이면 결과를 전달하지 않는다.
 *   그래서 늦게 도착한 요약이 더 최신 턴의 카드를 덮어쓰지 않는다. 받는 쪽도 turn이 카드의 번호와 같을 때만 반영한다.
 * - 요약은 분석 입력·판정에 쓰이지 않는다(Stage0ClarificationEngine.generateTaskSummary 주석, WebviewActionRouter는 UI로만 전달).
 */
class TaskSummaryScheduler(
    private val executor: Executor = defaultExecutor,
    private val build: (Stage0State) -> String?
) {
    /** 가장 최근 턴의 요약 상태. 확정 시점에 화면에 보이던 상태를 알려 준다. */
    enum class Status { NONE, PENDING, READY, FAILED }

    private val version = AtomicInteger()
    private val inFlight = AtomicInteger()
    private val skippedCount = AtomicInteger()
    @Volatile private var status: Status = Status.NONE

    /** 시작도 못 하고 폐기된 요약 수(= 줄어든 LLM 호출 수). */
    val skipped: Int get() = skippedCount.get()

    /** 진행 중이거나 대기 중인 요약 작업 수. */
    val pending: Int get() = inFlight.get()

    /** 가장 최근 턴의 요약 상태(확정 직전에 읽으면 화면에 보이던 상태). */
    val currentStatus: Status get() = status

    /** 새 턴 없이 요약을 버린다(확정 등). 상태는 그대로 두어 확정 시점의 표시 상태를 읽을 수 있게 한다. */
    fun invalidate() {
        version.incrementAndGet()
    }

    /** 이번 턴의 요약을 만들기 시작한다. 돌려주는 번호는 카드와 요약을 짝짓는 데 쓴다. */
    fun schedule(state: Stage0State, fail: (Int) -> Unit = {}, deliver: (Int, String) -> Unit): Int {
        val v = version.incrementAndGet()
        status = Status.PENDING
        inFlight.incrementAndGet()
        executor.execute {
            try {
                if (version.get() != v) {
                    skippedCount.incrementAndGet()
                    return@execute
                }
                val summary = try { build(state) } catch (_: Exception) { null }
                if (version.get() != v) return@execute
                if (summary != null) {
                    status = Status.READY
                    deliver(v, summary)
                } else {
                    status = Status.FAILED
                    fail(v)
                }
            } finally {
                inFlight.decrementAndGet()
            }
        }
        return v
    }

    /** 평가 도구용: 진행 중인 요약 작업이 끝나길 기다린다. 끝났으면 true. */
    fun awaitIdle(timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (inFlight.get() > 0) {
            if (System.currentTimeMillis() > end) return false
            Thread.sleep(50)
        }
        return true
    }

    companion object {
        private val defaultExecutor: Executor = Executors.newCachedThreadPool { r ->
            Thread(r, "task-summary").apply { isDaemon = true }
        }
    }
}
