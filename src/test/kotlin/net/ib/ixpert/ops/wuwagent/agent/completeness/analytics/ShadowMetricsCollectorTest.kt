package net.ib.ixpert.ops.wuwagent.agent.completeness.analytics

import net.ib.ixpert.ops.wuwagent.agent.completeness.model.ShadowLog
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class ShadowMetricsCollectorTest {

    private fun dummyLog(
        srKey: String = "test-sr-key",
        requiredFiles: List<String> = listOf("src/main/java/com/samsungcardmall/api/bo/app/dao/FakeDao.java"),
        verdict: String = "PASS"
    ) = ShadowLog(
        timestamp = java.time.Instant.now().toString(),
        srKey = srKey,
        runId = UUID.randomUUID().toString(),
        rulesetVersion = "1.0.0-shadow",
        guardMode = "SHADOW",
        frameworkType = "ANYFRAME",
        verdict = verdict,
        requiredFiles = requiredFiles,
        violations = emptyList(),
        acceptedDebts = emptyList()
    )

    @Test
    fun `test 5 duplicates count as 1 event`() {
        val sameSrKey = "sr-key-123"
        val sameFiles = listOf("src/main/java/test/Dao.java")
        
        // 5 identical logs (except timestamp/runId)
        val logs = (1..5).map { dummyLog(srKey = sameSrKey, requiredFiles = sameFiles, verdict = "PASS") }
        
        val metrics = ShadowMetricsCollector.collect(logs)
        
        assertEquals(5, metrics.totalRawLogs)
        assertEquals("5 duplicates should be deduplicated to 1 event", 1, metrics.totalDedupedEvents)
        assertEquals(1, metrics.totalPassEvents)
    }

    @Test
    fun `test survey_admin paths are completely excluded from denominator`() {
        val normalLog = dummyLog(srKey = "normal-key", verdict = "WOULD_BLOCK")
        val surveyAdminLog = dummyLog(
            srKey = "survey-key", 
            requiredFiles = listOf("src/main/java/survey_admin/Admin.java"), 
            verdict = "WOULD_BLOCK"
        )
        
        val logs = listOf(normalLog, surveyAdminLog)
        
        val metrics = ShadowMetricsCollector.collect(logs)
        
        assertEquals(2, metrics.totalRawLogs)
        assertEquals("survey_admin log should be excluded", 1, metrics.totalDedupedEvents)
        assertEquals("Only normal log is counted in WOULD_BLOCK", 1, metrics.totalWouldBlockEvents)
    }
}
