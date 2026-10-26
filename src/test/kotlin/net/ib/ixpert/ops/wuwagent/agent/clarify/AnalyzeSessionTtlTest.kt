package net.ib.ixpert.ops.wuwagent.agent.clarify

import com.intellij.openapi.project.Project
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.GraphStatistics
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.TimeUnit

/**
 * clarify 세션 유효 시간은 마지막 활동부터 30분이다(생성 시각 기준 5분이 아니다). 시각은 주입한 clock으로 다루고 실제로 기다리지 않는다.
 * s1 생성 4분 뒤 발화 → 그 4분 뒤(생성 기준 8분) 확정 성공
 * s2 마지막 활동 후 29분 확정 성공, 31분이면 null
 * s3 만료된 세션은 발화·요약 전송으로 되살아나지 않는다(만료 후 발화 → 같은 null, 곧 같은 오류)
 */
class AnalyzeSessionTtlTest {

    private var now = 1_000_000_000_000L
    private fun minutes(n: Long) = TimeUnit.MINUTES.toMillis(n)
    private fun advance(min: Long) { now += minutes(min) }

    private fun project(basePath: String): Project = Proxy.newProxyInstance(
        Project::class.java.classLoader, arrayOf(Project::class.java)
    ) { _, method, _ ->
        when (method.name) {
            "getBasePath" -> basePath
            "getName" -> "TtlTest"
            "getLocationHash" -> "hash_$basePath"
            else -> null
        }
    } as Project

    private fun save(p: Project) {
        val graph = ProjectGraph(
            generatedAt = java.time.Instant.now().toString(),
            projectRoot = "/ttl", files = emptyMap(), relationships = emptyList(), statistics = GraphStatistics()
        )
        val engine = Stage0ClarificationEngine(Stage0GraphScanner(graph), graph)
        val turn = engine.initSession("요구사항")
        AnalyzeSessionManager.saveSession(p, "요구사항", engine, turn.state, turn)
    }

    @Before
    fun setUp() {
        AnalyzeSessionManager.clearAll()
        now = 1_000_000_000_000L
        AnalyzeSessionManager.clock = { now }
    }

    @After
    fun tearDown() {
        AnalyzeSessionManager.clearAll()
        AnalyzeSessionManager.clock = { System.currentTimeMillis() }
    }

    @Test
    fun ttl는_30분이다() {
        assertEquals(30L, AnalyzeSessionManager.TTL_MINUTES)
    }

    @Test
    fun s1_생성_4분_뒤_발화하면_생성_기준_8분에도_확정이_성공한다() {
        val p = project("/ttl/s1")
        save(p)
        advance(4)
        assertNotNull("4분 뒤 발화(세션 조회)", AnalyzeSessionManager.getSession(p)) // 조회 성공이 활동이다
        advance(4)
        assertNotNull("생성 기준 8분 뒤 확정", AnalyzeSessionManager.getSession(p))
    }

    @Test
    fun s1b_옛_기준이었다면_생성_5분_뒤에는_실패했을_구간도_성공한다() {
        val p = project("/ttl/s1b")
        save(p)
        advance(5)
        advance(1) // 생성 6분(옛 TTL 5분 초과)
        assertNotNull(AnalyzeSessionManager.getSession(p))
    }

    @Test
    fun s2_마지막_활동_29분_뒤_확정은_성공하고_31분이면_null이다() {
        val ok = project("/ttl/s2a")
        save(ok)
        advance(1)
        assertNotNull(AnalyzeSessionManager.getSession(ok)) // 마지막 활동 = 생성 1분 뒤
        advance(29)
        assertNotNull("마지막 활동 29분 뒤", AnalyzeSessionManager.getSession(ok))

        val late = project("/ttl/s2b")
        save(late)
        advance(1)
        assertNotNull(AnalyzeSessionManager.getSession(late)) // 마지막 활동 = 생성 1분 뒤
        advance(31)
        assertNull("마지막 활동 31분 뒤", AnalyzeSessionManager.getSession(late))
    }

    @Test
    fun s2c_경계는_30분_초과부터_만료다() {
        val p = project("/ttl/s2c")
        save(p)
        now += minutes(30)
        assertNotNull("정확히 30분", AnalyzeSessionManager.getSession(p))
        now += minutes(30) + 1
        assertNull("30분에서 1ms 지남", AnalyzeSessionManager.getSession(p))
    }

    @Test
    fun s3_만료된_세션은_발화나_요약_전송으로_되살아나지_않는다() {
        val p = project("/ttl/s3")
        save(p)
        advance(31)
        // 요약 전송이 만료 뒤에 도착해도(touch) 되살리지 않는다. 아직 지워지기 전 상태에서 확인한다
        AnalyzeSessionManager.touch(p)
        assertNull("만료 후 발화: 같은 null(= 같은 오류)", AnalyzeSessionManager.getSession(p))
        advance(1)
        assertNull("지워진 뒤에도 null", AnalyzeSessionManager.getSession(p))
        AnalyzeSessionManager.touch(p) // 없는 세션은 무시
        assertNull(AnalyzeSessionManager.getSession(p))
    }

    @Test
    fun touch는_살아_있는_세션의_유효_시간을_늘린다() {
        val p = project("/ttl/touch")
        save(p)
        advance(20)
        AnalyzeSessionManager.touch(p) // 요약 전송
        advance(20) // 생성 기준 40분이지만 마지막 활동은 20분 전
        assertNotNull(AnalyzeSessionManager.getSession(p))
    }

    @Test
    fun 세션_객체의_isExpired는_마지막_활동_기준이다() {
        val p = project("/ttl/obj")
        save(p)
        val s = AnalyzeSessionManager.getSession(p)!!
        assertFalse(s.isExpired(30, now + minutes(30)))
        assertTrue(s.isExpired(30, now + minutes(30) + 1))
        s.touch(now + minutes(10))
        assertFalse(s.isExpired(30, now + minutes(40)))
    }

    @Test
    fun r1_라우터는_발화_처리_완료와_요약_전송에서_활동을_갱신한다() {
        val router = File("src/main/kotlin/net/ib/ixpert/ops/wuwagent/action/WebviewActionRouter.kt").readText(Charsets.UTF_8)
        assertEquals(2, Regex("""AnalyzeSessionManager\.touch\(""").findAll(router).count())
        val utterance = router.substringAfter("\"/clarify-utterance\" -> {").substringBefore("// ── Scope Selection Events")
        assertTrue(utterance.contains("AnalyzeSessionManager.touch(project)"))
        val summary = router.substringAfter("private fun scheduleTaskSummary(").substringBefore("private fun continueAfterClarify(")
        assertTrue(summary.contains("AnalyzeSessionManager.touch(it)"))
        assertEquals(3, Regex("""scheduleTaskSummary\(bridge, [^)]*messageId, project\)""").findAll(router).count())
    }
}
