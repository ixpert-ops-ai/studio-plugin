package net.ib.ixpert.ops.wuwagent.agent.completeness

import net.ib.ixpert.ops.wuwagent.agent.completeness.model.RootCause
import net.ib.ixpert.ops.wuwagent.agent.completeness.model.ShadowLog
import net.ib.ixpert.ops.wuwagent.agent.completeness.model.ViolationDetail
import org.junit.After
import org.junit.Assert.assertEquals
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

        // 4. Write 1 incomplete/truncated line manually (simulating IDE crash without newline)
        Files.write(
            logFile.toPath(),
            "{\"srKey\":\"SR-TRUNCATED\"".toByteArray(StandardCharsets.UTF_8),
            StandardOpenOption.APPEND
        )

        // 5. Parse the log file
        val result = ShadowLogParser.parse(testProjectRoot)

        // 6. Assertions
        assertEquals("Should parse 3 valid logs", 3, result.logs.size)
        assertEquals("Should skip 2 broken logs", 2, result.skippedCount)

        // Verify fields of the valid logs survived roundtrip
        val parsedLog1 = result.logs[0]
        assertEquals("SR-001", parsedLog1.srKey)
        assertEquals(RootCause.NOT_IN_GRAPH, parsedLog1.violations[0].rootCause)
        assertEquals(false, parsedLog1.violations[0].isKnownDebt)

        val parsedLog2 = result.logs[1]
        assertEquals("SR-002", parsedLog2.srKey)
        assertEquals("MYBATIS_XML", parsedLog2.violations[0].missingTargetKind)
        assertEquals(true, parsedLog2.violations[0].isKnownDebt)
        
        val parsedLog3 = result.logs[2]
        assertEquals("SR-003", parsedLog3.srKey)
        assertEquals(RootCause.NO_EDGE, parsedLog3.violations[0].rootCause)
    }
}
