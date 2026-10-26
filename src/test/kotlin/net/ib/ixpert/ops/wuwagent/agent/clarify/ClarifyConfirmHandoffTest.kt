package net.ib.ixpert.ops.wuwagent.agent.clarify

import net.ib.ixpert.ops.wuwagent.agent.JcefRefinedRequirementConfirmationBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * `/clarify-confirm`의 정제문 생성(LLM 호출)을 EDT에서 빼고 백그라운드로 옮겼다. 모의 LLM(지연)과 가짜 실행기로 검사한다(IDE·서버 없음).
 * e1 모의 LLM 지연 3000ms에도 핸들러가 EDT를 200ms 안에 반환
 * e2 단계 순서: 가드 → getSession → processTurn → invalidate → (확인 진행 표지, 반환) → 정제문 생성 → 세션 닫기 → 계속
 * e3 정제문 생성 실패 시 표지를 풀고 같은 형식의 오류 알림(세션은 닫지 않는다)
 * e4 정제문 생성 중 두 번째 확인은 무시되고 분석은 1회
 * e5 /clarify-utterance 세션 TTL 갱신은 불변(AnalyzeSessionTtlTest s1~s3와 라우터 소스의 touch 2곳)
 */
class ClarifyConfirmHandoffTest {

    private val router: String by lazy {
        File("src/main/kotlin/net/ib/ixpert/ops/wuwagent/action/WebviewActionRouter.kt").readText(Charsets.UTF_8)
    }

    private class Recorder {
        val steps: MutableList<String> = Collections.synchronizedList(ArrayList())
        val failures: MutableList<Exception> = Collections.synchronizedList(ArrayList())
    }

    private fun steps(r: Recorder, build: () -> String, onContinue: (String) -> Unit = {}, done: CountDownLatch? = null) =
        ClarifyConfirmHandoff.Steps(
            invalidateSummary = { r.steps.add("invalidate") },
            beginHandoff = { r.steps.add("beginHandoff") },
            buildIntent = { r.steps.add("buildIntent"); build() },
            removeSession = { r.steps.add("removeSession") },
            continueAfterClarify = { i -> r.steps.add("continue:$i"); onContinue(i); done?.countDown() },
            onFailure = { e -> r.steps.add("onFailure"); r.failures.add(e); done?.countDown() },
            endHandoff = { r.steps.add("endHandoff") }
        )

    // ── e1 ──

    @Test
    fun e1_모의_LLM이_3초_걸려도_핸들러는_200ms_안에_반환한다() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            val r = Recorder()
            val done = CountDownLatch(1)
            val started = System.nanoTime()
            ClarifyConfirmHandoff.start(steps(r, build = { Thread.sleep(3000); "intent" }, done = done)) { task -> pool.execute { task() } }
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue("EDT 반환까지 ${elapsedMs}ms", elapsedMs < 200)
            // 정제문 생성은 그 뒤에 백그라운드에서 끝난다
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertEquals(listOf("invalidate", "beginHandoff", "buildIntent", "removeSession", "continue:intent"), r.steps.toList())
        } finally {
            pool.shutdownNow()
        }
    }

    // ── e2 ──

    @Test
    fun e2_단계_순서는_표지를_세운_뒤_반환하고_그_뒤에_정제문_생성_세션_닫기_계속_순이다() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            val r = Recorder()
            val gate = CountDownLatch(1)
            val done = CountDownLatch(1)
            ClarifyConfirmHandoff.start(steps(r, build = { "i" }, done = done)) { task -> pool.execute { gate.await(); task() } }
            r.steps.add("returned") // start가 돌아온 시점(EDT가 풀린 시점)
            gate.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("invalidate", "beginHandoff", "returned", "buildIntent", "removeSession", "continue:i"), r.steps.toList())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun e2b_라우터_확정_분기의_단계_순서와_정제문_생성의_위치() {
        val confirm = router.substringAfter("\"/clarify-confirm\", \"/analyze-confirm\" -> {").substringBefore("// ── 요구사항 대화형 발화 처리")
        val order = listOf(
            "isHandoffActive(project.locationHash)",   // 가드
            "AnalyzeSessionManager.getSession(project)", // 세션 조회
            "session.engine.processTurn(",             // processTurn(EDT에 남는다)
            "ClarifyConfirmHandoff.start(",            // 요약 폐기, 표지, 백그라운드 전환
            "buildIntent = {",
            "removeSession = {",
            "continueAfterClarify = {"
        ).map { confirm.indexOf(it) }
        order.forEach { assertTrue("단계가 있어야 한다: $order", it >= 0) }
        assertEquals("순서가 바뀌면 안 된다: $order", order.sorted(), order)
        // 정제문 생성(LLM 호출)은 백그라운드로 넘기는 람다 안에만 있고 EDT 쪽에는 없다
        assertEquals(1, Regex("""buildClarifyIntentChecked\(""").findAll(confirm).count())
        assertTrue(confirm.indexOf("buildClarifyIntentChecked(") > confirm.indexOf("buildIntent = {"))
        assertTrue(confirm.contains("executeOnPooledThread { task() }"))
        // 표지는 start 안에서 요약 폐기 다음, 백그라운드 전환 앞에서 세운다(Steps의 beginHandoff를 넘긴다)
        assertTrue(confirm.contains("invalidateSummary = { session.engine.summaryScheduler.invalidate() }"))
        assertTrue(confirm.contains("beginHandoff = {"))
    }

    // ── e3 ──

    @Test
    fun e3_정제문_생성이_실패하면_표지를_풀고_오류를_알리며_세션은_닫지_않는다() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            val r = Recorder()
            val done = CountDownLatch(1)
            ClarifyConfirmHandoff.start(steps(r, build = { throw IllegalStateException("boom") }, done = done)) { task -> pool.execute { task() } }
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("invalidate", "beginHandoff", "buildIntent", "endHandoff", "onFailure"), r.steps.toList())
            assertEquals("boom", r.failures.single().message)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun e3b_실행기가_일을_받지_못해도_표지를_풀고_같은_오류_경로로_알린다() {
        val r = Recorder()
        ClarifyConfirmHandoff.start(steps(r, build = { "i" })) { throw java.util.concurrent.RejectedExecutionException("꽉 참") }
        assertEquals(listOf("invalidate", "beginHandoff", "endHandoff", "onFailure"), r.steps.toList())
    }

    @Test
    fun e3c_오류_알림은_기존_파이프라인_오류와_같은_error_형식이다() {
        val confirm = router.substringAfter("\"/clarify-confirm\", \"/analyze-confirm\" -> {").substringBefore("// ── 요구사항 대화형 발화 처리")
        assertTrue(confirm.contains("bridge.sendMessage(\"error\", \"분석을 시작하지 못했습니다: \${e.message}\", messageId)"))
        assertTrue(confirm.contains("logger.error(\"Clarify confirm error\", e)"))
        assertTrue(confirm.contains("endHandoff = {"))
    }

    // ── e4 ──

    @Test
    fun e4_정제문_생성_중_들어온_두_번째_확인은_무시되고_분석은_한_번이다() {
        val id = "handoff-test-project"
        JcefRefinedRequirementConfirmationBridge.endHandoff(id)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val r = Recorder()
            var ignored = 0
            val analyses = Collections.synchronizedList(ArrayList<String>())
            val buildStarted = CountDownLatch(1)
            val release = CountDownLatch(1)
            val done = CountDownLatch(1)
            // 라우터의 확정 분기와 같은 모양: 가드 → (통과하면) 표지를 세우고 백그라운드로
            fun confirm() {
                if (JcefRefinedRequirementConfirmationBridge.isHandoffActive(id)) { ignored++; return }
                ClarifyConfirmHandoff.start(
                    ClarifyConfirmHandoff.Steps(
                        invalidateSummary = {},
                        beginHandoff = { JcefRefinedRequirementConfirmationBridge.beginHandoff(id) },
                        buildIntent = { buildStarted.countDown(); release.await(5, TimeUnit.SECONDS); "intent" },
                        removeSession = {},
                        continueAfterClarify = { i -> analyses.add(i); JcefRefinedRequirementConfirmationBridge.endHandoff(id); done.countDown() },
                        onFailure = { r.failures.add(it); done.countDown() },
                        endHandoff = { JcefRefinedRequirementConfirmationBridge.endHandoff(id) }
                    )
                ) { task -> pool.execute { task() } }
            }
            confirm()
            assertTrue(buildStarted.await(5, TimeUnit.SECONDS))
            assertTrue("정제문을 만드는 중에 표지가 서 있다", JcefRefinedRequirementConfirmationBridge.isHandoffActive(id))
            confirm() // 두 번째 확인
            confirm() // 세 번째 확인
            assertEquals(2, ignored)
            release.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("intent"), analyses.toList())
            assertFalse(JcefRefinedRequirementConfirmationBridge.isHandoffActive(id))
            assertTrue(r.failures.isEmpty())
        } finally {
            JcefRefinedRequirementConfirmationBridge.endHandoff(id)
            pool.shutdownNow()
        }
    }

    @Test
    fun e4b_무시_로그는_가드에_한_줄이다() {
        val confirm = router.substringAfter("\"/clarify-confirm\", \"/analyze-confirm\" -> {").substringBefore("// ── 요구사항 대화형 발화 처리")
        assertEquals(1, Regex("""두 번째 확인을 무시했습니다""").findAll(confirm).count())
        assertEquals(1, Regex("""isHandoffActive\(project\.locationHash\)""").findAll(confirm).count())
    }

    // ── x5 (B-72) ──

    @Test
    fun x5_정제문_생성_실패_뒤_다시_확정하면_분석은_한_번이고_무시_로그는_없다() {
        val id = "handoff-retry-project"
        JcefRefinedRequirementConfirmationBridge.endHandoff(id)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val r = Recorder()
            var ignored = 0
            var sessionRemoved = 0
            var attempt = 0
            val analyses = Collections.synchronizedList(ArrayList<String>())
            val done = CountDownLatch(2)
            fun confirm() {
                if (JcefRefinedRequirementConfirmationBridge.isHandoffActive(id)) { ignored++; return }
                ClarifyConfirmHandoff.start(
                    ClarifyConfirmHandoff.Steps(
                        invalidateSummary = {},
                        beginHandoff = { JcefRefinedRequirementConfirmationBridge.beginHandoff(id) },
                        buildIntent = { if (++attempt == 1) throw IllegalStateException("첫 시도 실패") else "intent" },
                        removeSession = { sessionRemoved++ },
                        continueAfterClarify = { i -> analyses.add(i); JcefRefinedRequirementConfirmationBridge.endHandoff(id); done.countDown() },
                        onFailure = { r.failures.add(it); done.countDown() },
                        endHandoff = { JcefRefinedRequirementConfirmationBridge.endHandoff(id) }
                    )
                ) { task -> pool.execute { task() } }
            }
            confirm()
            // 첫 시도가 실패해 오류가 한 번 나가고 표지가 풀릴 때까지 기다린다
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (r.failures.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals("오류 1회", 1, r.failures.size)
            assertEquals("실패한 시도는 세션을 닫지 않는다", 0, sessionRemoved)
            assertFalse("표지가 풀려 있어야 한다", JcefRefinedRequirementConfirmationBridge.isHandoffActive(id))
            confirm() // 사용자가 다시 확정
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals("분석은 한 번", listOf("intent"), analyses.toList())
            assertEquals("무시 로그 없음", 0, ignored)
            assertEquals(1, sessionRemoved)
        } finally {
            JcefRefinedRequirementConfirmationBridge.endHandoff(id)
            pool.shutdownNow()
        }
    }

    // ── B-73 step 2: 분석 시작(continueAfterClarify) 실패 ──

    @Test
    fun x7_분석_시작이_실패하면_세션을_되돌리고_표지를_풀며_다시_확정하면_분석이_한_번이다() {
        val id = "handoff-continue-fail-project"
        JcefRefinedRequirementConfirmationBridge.endHandoff(id)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val r = Recorder()
            var ignored = 0
            var attempt = 0
            val analyses = Collections.synchronizedList(ArrayList<String>())
            val done = CountDownLatch(1)
            fun confirm() {
                if (JcefRefinedRequirementConfirmationBridge.isHandoffActive(id)) { ignored++; return }
                ClarifyConfirmHandoff.start(
                    ClarifyConfirmHandoff.Steps(
                        invalidateSummary = {},
                        beginHandoff = { JcefRefinedRequirementConfirmationBridge.beginHandoff(id) },
                        buildIntent = { "intent" },
                        removeSession = { r.steps.add("removeSession") },
                        restoreSession = { r.steps.add("restoreSession") },
                        continueAfterClarify = { i ->
                            if (++attempt == 1) throw IllegalStateException("분석 시작 실패")
                            analyses.add(i); JcefRefinedRequirementConfirmationBridge.endHandoff(id); done.countDown()
                        },
                        onFailure = { r.steps.add("onFailure"); r.failures.add(it) },
                        endHandoff = { r.steps.add("endHandoff"); JcefRefinedRequirementConfirmationBridge.endHandoff(id) }
                    )
                ) { task -> pool.execute { task() } }
            }
            confirm()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (r.failures.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals("세션을 지운 뒤 실패했으니 세션을 되돌리고, 그 다음에 표지를 풀고, 오류를 알린다",
                listOf("removeSession", "restoreSession", "endHandoff", "onFailure"), r.steps.toList())
            assertFalse(JcefRefinedRequirementConfirmationBridge.isHandoffActive(id))
            confirm() // 같은 정제문으로 다시 확정
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("intent"), analyses.toList())
            assertEquals("무시 로그 없음", 0, ignored)
        } finally {
            JcefRefinedRequirementConfirmationBridge.endHandoff(id)
            pool.shutdownNow()
        }
    }

    @Test
    fun x7b_정제문_생성이_실패하면_세션을_지우지_않았으므로_복원도_하지_않는다() {
        val r = Recorder()
        ClarifyConfirmHandoff.start(
            ClarifyConfirmHandoff.Steps(
                invalidateSummary = {}, beginHandoff = {}, buildIntent = { throw IllegalStateException("x") },
                removeSession = { r.steps.add("removeSession") }, restoreSession = { r.steps.add("restoreSession") },
                continueAfterClarify = { r.steps.add("continue") }, onFailure = { r.steps.add("onFailure") }, endHandoff = { r.steps.add("endHandoff") }
            )
        ) { task -> task() }
        assertEquals(listOf("endHandoff", "onFailure"), r.steps.toList())
    }

    @Test
    fun x7c_라우터_확정_분기는_분석_시작_실패_때_세션을_되돌린다() {
        val confirm = router.substringAfter("\"/clarify-confirm\", \"/analyze-confirm\" -> {").substringBefore("// ── 요구사항 대화형 발화 처리")
        assertTrue(confirm.contains("restoreSession = {"))
        assertTrue(confirm.contains("AnalyzeSessionManager.restoreSession(project, session)"))
    }

    // ── e5 ──

    @Test
    fun e5_발화_경로의_세션_갱신은_그대로다() {
        val utterance = router.substringAfter("\"/clarify-utterance\" -> {").substringBefore("// ── Scope Selection Events")
        assertTrue(utterance.contains("AnalyzeSessionManager.touch(project)"))
        assertEquals(2, Regex("""AnalyzeSessionManager\.touch\(""").findAll(router).count())
        // 발화 완료 경로는 원래 백그라운드(executeOnPooledThread) 안에서 정제문을 만든다
        assertTrue(utterance.indexOf("executeOnPooledThread") in 0 until utterance.indexOf("buildClarifyIntentChecked("))
        assertSame(Unit, Unit)
    }
}
