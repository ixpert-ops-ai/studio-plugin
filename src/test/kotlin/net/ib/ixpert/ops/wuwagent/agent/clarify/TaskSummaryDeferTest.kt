package net.ib.ixpert.ops.wuwagent.agent.clarify

import net.ib.ixpert.ops.wuwagent.agent.clarify.model.Stage0State
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.OllamaMessage
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.GraphStatistics
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/**
 * B-43 작업 요약(화면 표시 전용)을 턴 응답 밖에서 만든다: 지연 모드의 엔진 동작과 스케줄러의 호출 합침·폐기.
 */
class TaskSummaryDeferTest {

    private val graph = ProjectGraph(generatedAt = "now", projectRoot = "/", files = emptyMap(), relationships = emptyList(), statistics = GraphStatistics())
    private val summaryText = "요약 문장입니다."

    private class CountingClient(private val summaryText: String) : LLMClient {
        val summaryCalls = AtomicInteger()
        override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): OllamaChatResponse? {
            if (systemPrompt.contains("전체 작업의 핵심 목표와 각 컴포넌트의 변경 맥락")) summaryCalls.incrementAndGet()
            return OllamaChatResponse("m", null, OllamaMessage("assistant", summaryText), true)
        }
        override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
    }

    private fun engine(client: LLMClient, defer: Boolean) =
        Stage0ClarificationEngine(Stage0GraphScanner(graph), graph, client, deferTaskSummary = defer)

    // T1 지연 모드의 initSession은 요약을 만들지 않고(LLM 0회) null을 돌려준다. 기본 모드는 그대로 1회 만든다.
    @Test
    fun t1_initSessionInDeferModeMakesNoSummaryCall() {
        val sync = CountingClient(summaryText)
        val syncTurn = engine(sync, defer = false).initSession("주문 결제 방식 추가")
        assertEquals(summaryText, syncTurn.taskSummary)
        assertEquals(1, sync.summaryCalls.get())

        val deferred = CountingClient(summaryText)
        val e = engine(deferred, defer = true)
        val turn = e.initSession("주문 결제 방식 추가")
        assertNull(turn.taskSummary)
        assertEquals(0, deferred.summaryCalls.get())
        // 따로 만든 요약은 턴 안에서 만들던 것과 같은 입력·같은 결과다.
        assertEquals(summaryText, e.buildTaskSummary(turn.state))
        assertEquals(1, deferred.summaryCalls.get())
        assertEquals(syncTurn.state.items.size, turn.state.items.size)
    }

    // T2 지연 모드의 processTurn(재탐색 경로)도 요약을 만들지 않고, 판정·항목은 기본 모드와 같다.
    @Test
    fun t2_processTurnInDeferModeMakesNoSummaryCallAndSameItems() {
        val deferred = CountingClient(summaryText)
        val e = engine(deferred, defer = true)
        val s0 = e.initSession("주문 결제 방식 추가").state
        val turn = e.processTurn(s0, Stage0ClarificationEngine.UserInput())
        assertNull(turn.taskSummary)
        assertEquals(0, deferred.summaryCalls.get())

        val sync = CountingClient(summaryText)
        val se = engine(sync, defer = false)
        val sturn = se.processTurn(se.initSession("주문 결제 방식 추가").state, Stage0ClarificationEngine.UserInput())
        assertEquals(summaryText, sturn.taskSummary)
        assertEquals(sturn.state.items.map { it.id to it.verdict }, turn.state.items.map { it.id to it.verdict })
        assertEquals(sturn.openQuestion, turn.openQuestion)
    }


    private class ManualExecutor : Executor {
        val queue = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { queue.addLast(command) }
        fun runAll() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }

    private val state = Stage0State(originalRequirement = "r", items = emptyList(), seedSet = emptySet())

    // T3 새 턴이 오면 시작 전인 이전 요약은 LLM을 부르지 않고 폐기된다(호출 합침). 마지막 것만 만들어 그 턴 번호와 함께 전달한다.
    @Test
    fun t3_newerTurnDropsPendingSummary() {
        val ex = ManualExecutor()
        val built = ArrayList<String>()
        val s = TaskSummaryScheduler(ex) { st -> built.add(st.originalRequirement); "요약:${st.originalRequirement}" }
        val delivered = ArrayList<Pair<Int, String>>()
        val t1 = s.schedule(state.copy(originalRequirement = "턴1")) { turn, text -> delivered.add(turn to text) }
        val t2 = s.schedule(state.copy(originalRequirement = "턴2")) { turn, text -> delivered.add(turn to text) }
        assertTrue("턴 번호는 증가해야 함", t2 > t1)
        assertEquals(2, s.pending)
        ex.runAll()
        assertEquals(listOf("턴2"), built)
        assertEquals(listOf(t2 to "요약:턴2"), delivered)
        assertEquals(1, s.skipped)
        assertEquals(0, s.pending)
    }

    // T4 확정 등으로 invalidate되면 대기 중인 요약은 만들지도 전달하지도 않는다.
    @Test
    fun t4_invalidateDropsPendingSummary() {
        val ex = ManualExecutor()
        var buildCalls = 0
        val s = TaskSummaryScheduler(ex) { buildCalls++; "x" }
        val delivered = ArrayList<String>()
        s.schedule(state) { _, text -> delivered.add(text) }
        s.invalidate()
        ex.runAll()
        assertEquals(0, buildCalls)
        assertTrue(delivered.isEmpty())
        assertEquals(1, s.skipped)
    }

    // T5 만드는 중에 새 턴이 오면 결과도 실패 알림도 전달하지 않는다(낡은 요약이 새 카드를 덮어쓰지 않는다).
    @Test
    fun t5_staleSummaryIsNeitherDeliveredNorReportedAsFailure() {
        val ex = ManualExecutor()
        lateinit var s: TaskSummaryScheduler
        s = TaskSummaryScheduler(ex) { s.invalidate(); "낡은 요약" }
        val delivered = ArrayList<String>()
        val failed = ArrayList<Int>()
        s.schedule(state, fail = { failed.add(it) }) { _, text -> delivered.add(text) }
        ex.runAll()
        assertTrue(delivered.isEmpty())
        assertTrue(failed.isEmpty())

        val failing = TaskSummaryScheduler(ex) { null }
        failing.schedule(state, fail = { failed.add(it) }) { _, text -> delivered.add(text) }
        ex.runAll()
        assertTrue("실패(null)는 요약으로 전달하지 않음", delivered.isEmpty())
        assertEquals("최신 턴의 실패는 알린다", 1, failed.size)
    }

    // T6 기본 실행기에서도 비동기로 만들고 awaitIdle로 끝을 기다릴 수 있다.
    @Test
    fun t6_defaultExecutorDeliversAndAwaitIdleWaits() {
        val s = TaskSummaryScheduler { "비동기 요약" }
        val delivered = java.util.concurrent.CopyOnWriteArrayList<String>()
        s.schedule(state) { _, text -> delivered.add(text) }
        assertTrue(s.awaitIdle(5000))
        assertEquals(listOf("비동기 요약"), delivered)
    }

    // T7 늦게 끝난 이전 턴의 요약은 더 최신 턴의 요약을 덮어쓰지 못한다: 턴1을 만드는 중에 턴2가 끝나고, 그 뒤 턴1이 늦게 끝나도 전달은 최신 턴 번호 하나뿐이다.
    @Test
    fun t7_lateOlderSummaryCannotOverwriteNewerTurn() {
        val release = java.util.concurrent.CountDownLatch(1)
        val started = java.util.concurrent.CountDownLatch(1)
        val s = TaskSummaryScheduler { st ->
            if (st.originalRequirement == "턴1") {
                started.countDown()
                release.await(5, java.util.concurrent.TimeUnit.SECONDS)
            }
            "요약:${st.originalRequirement}"
        }
        val delivered = java.util.concurrent.CopyOnWriteArrayList<Pair<Int, String>>()
        s.schedule(state.copy(originalRequirement = "턴1")) { turn, text -> delivered.add(turn to text) }
        assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val t2 = s.schedule(state.copy(originalRequirement = "턴2")) { turn, text -> delivered.add(turn to text) }
        val end = System.currentTimeMillis() + 5000
        while (delivered.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(20)
        release.countDown()   // 턴1이 이제 늦게 끝난다
        assertTrue(s.awaitIdle(5000))
        assertEquals("전달은 최신 턴 하나뿐", listOf(t2 to "요약:턴2"), delivered.toList())
    }

    // T8 가장 최근 턴의 요약 상태를 확정 시점에 읽을 수 있다: 예약 전 NONE, 예약 후 PENDING, 전달 후 READY, 실패 후 FAILED, 확정(invalidate) 뒤에도 마지막 표시 상태 유지.
    @Test
    fun t8_statusTracksWhatTheCardShows() {
        val ex = ManualExecutor()
        var result: String? = "요약"
        val s = TaskSummaryScheduler(ex) { result }
        assertEquals(TaskSummaryScheduler.Status.NONE, s.currentStatus)
        s.schedule(state) { _, _ -> }
        assertEquals(TaskSummaryScheduler.Status.PENDING, s.currentStatus)
        ex.runAll()
        assertEquals(TaskSummaryScheduler.Status.READY, s.currentStatus)

        result = null
        s.schedule(state) { _, _ -> }
        ex.runAll()
        assertEquals(TaskSummaryScheduler.Status.FAILED, s.currentStatus)

        result = "요약"
        s.schedule(state) { _, _ -> }
        s.invalidate()   // 확정 직전에 요약이 아직 만들어지는 중
        assertEquals("확정 시점에 읽으면 갱신 중", TaskSummaryScheduler.Status.PENDING, s.currentStatus)
        ex.runAll()
        assertEquals("확정 뒤 늦게 끝나도 상태는 바뀌지 않는다", TaskSummaryScheduler.Status.PENDING, s.currentStatus)
    }
}
