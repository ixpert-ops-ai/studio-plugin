package net.ib.ixpert.ops.wuwagent.agent.completeness

import net.ib.ixpert.ops.wuwagent.agent.completeness.model.RootCause
import net.ib.ixpert.ops.wuwagent.agent.completeness.model.ShadowLog
import net.ib.ixpert.ops.wuwagent.agent.completeness.model.ViolationDetail
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption

class ShadowLogRoundtripTest {

    private val testProjectRoot = "build/tmp/test_shadow_logger"
    private val logDir = File(testProjectRoot, ".wuwagent")
    private val logFile = File(logDir, "shadow_logs.jsonl")

    @Before
    fun setUp() {
        logDir.mkdirs()
        if (logFile.exists()) {
            logFile.delete()
        }
    }

    @After
    fun tearDown() {
        if (logFile.exists()) {
            logFile.delete()
        }
        logDir.delete()
        File(testProjectRoot).delete()
    }

    @Test
    fun `test shadow log write and read roundtrip with skipped broken lines`() {
        // 1. Write 2 valid logs using ShadowLogger
        val log1 = ShadowLog(
            timestamp = "2026-06-30T10:00:00Z",
            srKey = "SR-001",
            runId = "RUN-1",
            rulesetVersion = "1.0",
            guardMode = "SHADOW",
            frameworkType = "ANYFRAME",
            verdict = "WOULD_BLOCK",
            requiredFiles = listOf("A.java"),
            violations = listOf(
                ViolationDetail("CompanionMissing", "src/SomeBiz.java", "DVO", RootCause.NOT_IN_GRAPH, false, null)
            )
        )
        val log2 = ShadowLog(
            timestamp = "2026-06-30T10:01:00Z",
            srKey = "SR-002",
            runId = "RUN-1",
            rulesetVersion = "1.0",
            guardMode = "SHADOW",
            frameworkType = "ANYFRAME",
            verdict = "WOULD_BLOCK",
            requiredFiles = listOf("B.java"),
            violations = listOf(
                ViolationDetail("CompanionMissing", "src/SomeDAO.java", "MYBATIS_XML", RootCause.NOT_IN_GRAPH, true, "ISM_16_XML")
            )
        )
        
        ShadowLogger.log(testProjectRoot, log1)
        ShadowLogger.log(testProjectRoot, log2)

        // 2. Write 1 broken line (malformed JSON) manually
        Files.write(
            logFile.toPath(),
            "{ \"srKey\": \"SR-BROKEN\", \"runId\": \"RUN-1\", \"violations\": [ { \"type\": \"CompanionMissing\" \n".toByteArray(StandardCharsets.UTF_8),
            StandardOpenOption.APPEND
        )

        // 3. Write 1 valid log using ShadowLogger
        val log3 = ShadowLog(
            timestamp = "2026-06-30T10:02:00Z",
            srKey = "SR-003",
            runId = "RUN-1",
            rulesetVersion = "1.0",
            guardMode = "SHADOW",
            frameworkType = "ANYFRAME",
            verdict = "WOULD_BLOCK",
            requiredFiles = listOf("C.java"),
            violations = listOf(
                ViolationDetail("CompanionMissing", "src/AnotherBiz.java", "SVO", RootCause.NO_EDGE, false, null)
            )
        )
        ShadowLogger.log(testProjectRoot, log3)

        // 4. Write 1 incomplete/truncated line manually (simulating IDE crash)
        Files.write(
            logFile.toPath(),
            "{\"srKey\":\"SR-TRUNCATED\"\n".toByteArray(StandardCharsets.UTF_8),
            StandardOpenOption.APPEND
        )

        // 4-b. Write 1 Ghost line (WOULD_BLOCK but empty violations) using ShadowLogger
        val ghostLog = ShadowLog(
            timestamp = "2026-06-30T10:03:00Z",
            srKey = "SR-GHOST",
            runId = "RUN-1",
            rulesetVersion = "1.0",
            guardMode = "SHADOW",
            frameworkType = "ANYFRAME",
            verdict = "WOULD_BLOCK",
            requiredFiles = listOf("D.java"),
            violations = emptyList() // The ghost anomaly!
        )
        ShadowLogger.log(testProjectRoot, ghostLog)

        // 4-c. Write 1 PASS but violations not empty using ShadowLogger
        val inconsistentPassLog = ShadowLog(
            timestamp = "2026-06-30T10:04:00Z",
            srKey = "SR-INCONSISTENT-PASS",
            runId = "RUN-1",
            rulesetVersion = "1.0",
            guardMode = "SHADOW",
            frameworkType = "ANYFRAME",
            verdict = "PASS",
            requiredFiles = listOf("D.java"),
            violations = listOf(ViolationDetail("RoleMissing", "A", null, null, false, null))
        )
        ShadowLogger.log(testProjectRoot, inconsistentPassLog)
        
        // 4-d. Write 1 Null-Ghost line (WOULD_BLOCK but violations field is completely omitted) manually
        // This tests that Gson injects null into the non-nullable list, and our parser handles it
        val nullGhostJson = """{"timestamp":"2026-06-30T10:05:00Z","srKey":"SR-NULL-GHOST","runId":"RUN-1","rulesetVersion":"1.0","guardMode":"SHADOW","frameworkType":"ANYFRAME","verdict":"WOULD_BLOCK","requiredFiles":["D.java"]}"""
        Files.write(
            logFile.toPath(),
            ("\n" + nullGhostJson + "\n").toByteArray(StandardCharsets.UTF_8),
            StandardOpenOption.APPEND
        )

        // 5. Parse the log file
        val result = ShadowLogParser.parse(testProjectRoot)

        // 6. Assertions
        assertEquals("Should parse 6 valid JSON logs (including anomalies)", 6, result.logs.size)
        assertEquals("Should skip 2 broken logs", 2, result.skippedCount)
        assertEquals("Should detect 3 anomalies", 3, result.dataQualityAnomalyCount)
        
        // Ensure ghost anomalies are properly marked
        val parsedGhost = result.logs.find { it.srKey == "SR-GHOST" }
        assertEquals("GHOST_VERDICT_BLOCK_WITHOUT_VIOLATIONS", parsedGhost?.dataQualityAnomaly)
        
        val parsedInconsistent = result.logs.find { it.srKey == "SR-INCONSISTENT-PASS" }
        assertEquals("INCONSISTENT_PASS_WITH_VIOLATIONS", parsedInconsistent?.dataQualityAnomaly)

        // Verify fields of the valid logs survived roundtrip
        val parsedLog1 = result.logs.find { it.srKey == "SR-001" }!!
        assertEquals("SR-001", parsedLog1.srKey)
        assertEquals(RootCause.NOT_IN_GRAPH, parsedLog1.violations!![0].rootCause)
        assertEquals(false, parsedLog1.violations!![0].isKnownDebt)

        val parsedLog2 = result.logs.find { it.srKey == "SR-002" }!!
        assertEquals("SR-002", parsedLog2.srKey)
        assertEquals("MYBATIS_XML", parsedLog2.violations!![0].missingTargetKind)
        assertEquals(true, parsedLog2.violations!![0].isKnownDebt)
        
        val parsedLog3 = result.logs.find { it.srKey == "SR-003" }!!
        assertEquals("SR-003", parsedLog3.srKey)
        assertEquals(RootCause.NO_EDGE, parsedLog3.violations!![0].rootCause)
    }

    @Test
    fun `test recommendations serialization roundtrip`() {
        val logWithRecs = ShadowLog(
            timestamp = "2026-07-10T10:00:00Z",
            srKey = "SR-REC-01",
            runId = "RUN-REC",
            rulesetVersion = "1.0",
            guardMode = "SHADOW",
            frameworkType = "SPRING_BOOT_JPA",
            verdict = "PASS",
            requiredFiles = listOf("A.java"),
            recommendations = listOf(
                net.ib.ixpert.ops.wuwagent.agent.completeness.model.CompanionRecommendation(
                    anchorFile = "A.java",
                    recommendedTargetKind = "RESPONSE_DTO",
                    pairingStrength = "RECOMMENDED",
                    satisfied = true,
                    note = "Found exact match"
                )
            )
        )
        
        ShadowLogger.log(testProjectRoot, logWithRecs)
        
        val result = ShadowLogParser.parse(testProjectRoot)
        assertEquals(1, result.logs.size)
        
        val parsedLog = result.logs.first().normalized()
        assertEquals("SR-REC-01", parsedLog.srKey)
        assertEquals(1, parsedLog.recommendations?.size)
        
        val rec = parsedLog.recommendations!![0]
        assertEquals("A.java", rec.anchorFile)
        assertEquals("RESPONSE_DTO", rec.recommendedTargetKind)
        assertEquals("RECOMMENDED", rec.pairingStrength)
        assertEquals(true, rec.satisfied)
        assertEquals("Found exact match", rec.note)
    }

    @Test
    fun `test legacy JSON parsing without NPE`() {
        val legacyJson = """{"timestamp":"2026-06-30T10:05:00Z","srKey":"SR-LEGACY","runId":"RUN-LEGACY","rulesetVersion":"1.0","guardMode":"SHADOW","frameworkType":"ANYFRAME","verdict":"PASS","requiredFiles":["A.java"]}"""
        Files.write(
            logFile.toPath(),
            (legacyJson + "\n").toByteArray(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
        )
        
        val result = ShadowLogParser.parse(testProjectRoot)
        assertEquals(1, result.logs.size)
        
        val parsedLog = result.logs.first()
        // Prior to normalized(), the field is null for legacy JSON.
        // Wait, gson might leave it null. Let's call normalized()
        val normalizedLog = parsedLog.normalized()
        
        assertEquals("SR-LEGACY", normalizedLog.srKey)
        // Check that NPE does not happen and lists are empty
        assertEquals(0, normalizedLog.recommendations?.size)
        assertEquals(0, normalizedLog.violations?.size)
        assertEquals(0, normalizedLog.acceptedDebts?.size)
    }
}
