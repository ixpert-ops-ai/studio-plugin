package net.ib.ixpert.ops.wuwagent.agent.completeness

import net.ib.ixpert.ops.wuwagent.agent.completeness.model.ShadowLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ShadowLoggerConcurrencyTest {

    private val testProjectRoot = "build/tmp/test_concurrency_logger"
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
    fun `test concurrent appends do not lose or corrupt lines`() {
        val numThreads = 50
        val writesPerThread = 20
        val totalExpectedWrites = numThreads * writesPerThread

        val executor = Executors.newFixedThreadPool(numThreads)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(numThreads)

        val logTemplate = ShadowLog(
            timestamp = "2026-06-30T10:00:00Z",
            srKey = "SR-TEST",
            runId = "RUN-1",
            rulesetVersion = "1.0",
            guardMode = "SHADOW",
            frameworkType = "ANYFRAME",
            verdict = "WOULD_BLOCK",
            requiredFiles = listOf("A.java", "B.java", "C.java"),
            violations = listOf(
                net.ib.ixpert.ops.wuwagent.agent.completeness.model.ViolationDetail(
                    type = "CompanionMissing",
                    anchorFile = "src/Test.java",
                    missingTargetKind = "DVO",
                    rootCause = net.ib.ixpert.ops.wuwagent.agent.completeness.model.RootCause.NOT_IN_GRAPH,
                    isKnownDebt = false,
                    debtReason = null
                )
            ) // Valid violation so it's not a ghost line
        )

        for (i in 0 until numThreads) {
            executor.submit {
                try {
                    startLatch.await() // Wait until all threads are ready to start simultaneously
                    for (j in 0 until writesPerThread) {
                        // Make a slightly varied log per write to simulate real workload
                        val log = logTemplate.copy(srKey = "SR-TEST-$i-$j")
                        ShadowLogger.log(testProjectRoot, log)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        // Unleash the threads!
        startLatch.countDown()

        // Wait for all to finish
        val completed = doneLatch.await(30, TimeUnit.SECONDS)
        executor.shutdown()

        assertEquals("All threads should finish writing within timeout", true, completed)
        assertEquals("Log file must exist after writes", true, logFile.exists())

        // Now parse the logs to verify no corruption (skippedCount = 0) and no loss (size = totalExpectedWrites)
        val parseResult = ShadowLogParser.parse(testProjectRoot)

        assertEquals("There should be zero corrupted/broken lines (no interleaved writes)", 0, parseResult.skippedCount)
        assertEquals("There should be zero ghost anomalies", 0, parseResult.dataQualityAnomalyCount)
        assertEquals("Valid logs should be exactly \$totalExpectedWrites to prove no writes were lost", totalExpectedWrites, parseResult.logs.size)
    }
}
