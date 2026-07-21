package net.ib.ixpert.ops.wuwagent.agent.completeness

import net.ib.ixpert.ops.wuwagent.agent.completeness.model.JsonKnownDebtRegistry
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class CompletenessConcurrencyTest {

    @Before
    fun setup() {
        JsonKnownDebtRegistry.clearCache()
    }

    @Test
    fun `test KnownDebtRegistry caching and concurrency safety`() {
        val threadCount = 50
        val latch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)
        val successfulRuns = AtomicInteger(0)

        // Submit 50 concurrent tasks to load the registry (simulating evaluateAfterVerifier calls)
        for (i in 0 until threadCount) {
            executor.submit {
                try {
                    latch.await() // Wait for the green light to start simultaneously
                    val registry = JsonKnownDebtRegistry.loadFromClasspath("ixpert/known_debts.json", "ixpert/heuristic_suppressions.json")
                    if (registry.getAllRecords().isNotEmpty()) {
                        successfulRuns.incrementAndGet()
                    }
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        // Release the hounds
        latch.countDown()
        
        // Wait for all threads to finish
        doneLatch.await()
        executor.shutdown()

        // Verify that all 50 threads successfully got the registry
        assertEquals("All 50 threads should successfully load the registry", threadCount, successfulRuns.get())

        // The absolute critical assertion: the file should have been parsed EXACTLY once
        assertEquals("JSON files should be parsed exactly once regardless of concurrent requests", 
            1, JsonKnownDebtRegistry.classpathReadCount.get())
    }
}
