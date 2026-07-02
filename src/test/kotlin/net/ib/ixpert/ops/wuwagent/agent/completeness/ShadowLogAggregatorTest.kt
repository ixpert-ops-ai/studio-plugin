package net.ib.ixpert.ops.wuwagent.agent.completeness

import net.ib.ixpert.ops.wuwagent.agent.completeness.model.*
import org.junit.Assert.assertEquals
import org.junit.Test

class ShadowLogAggregatorTest {

    private fun createLog(srKey: String, verdict: String, violations: List<ViolationDetail>): ShadowLog {
        return ShadowLog(
            timestamp = "2026-06-30T10:00:00Z",
            srKey = srKey,
            runId = "RUN-TEST",
            rulesetVersion = "1.0",
            guardMode = "SHADOW",
            frameworkType = "ANYFRAME",
            verdict = verdict,
            requiredFiles = emptyList(),
            violations = violations
        )
    }

    private fun createViolation(rootCause: RootCause, isKnownDebt: Boolean, debtReason: String? = null, targetKind: String = "DVO"): ViolationDetail {
        return ViolationDetail(
            type = "CompanionMissing",
            anchorFile = "src/Test.java",
            missingTargetKind = targetKind,
            rootCause = rootCause,
            isKnownDebt = isKnownDebt,
            debtReason = debtReason
        )
    }

    @Test
    fun `test aggregator separates event level block counts from violation level counts`() {
        val logs = mutableListOf<ShadowLog>()

        // 1. 5 PROCEED logs (no blocks)
        for (i in 1..5) {
            logs.add(createLog("SR-PROCEED-$i", "PASS", emptyList()))
        }

        // 2. 3 WOULD_BLOCK logs with only known_debt (NOT_IN_GRAPH)
        for (i in 1..3) {
            logs.add(createLog("SR-FULLY-KNOWN-$i", "WOULD_BLOCK", listOf(
                createViolation(RootCause.NOT_IN_GRAPH, true, "ISM_16_XML", "MYBATIS_XML")
            )))
        }

        // 3. 2 WOULD_BLOCK logs with only unknown debt (NOT_IN_GRAPH, isKnownDebt = false)
        for (i in 1..2) {
            logs.add(createLog("SR-FULLY-UNKNOWN-$i", "WOULD_BLOCK", listOf(
                createViolation(RootCause.NOT_IN_GRAPH, false, null, "DVO")
            )))
        }

        // 4. 1 SR with multiple WOULD_BLOCK logs (Mixed: 1 known + 1 unknown)
        // This should count as ONE UNKNOWN block event, but register both violations
        logs.add(createLog("SR-MIXED-1", "WOULD_BLOCK", listOf(
            createViolation(RootCause.NOT_IN_GRAPH, true, "ISM_16_XML", "MYBATIS_XML")
        )))
        logs.add(createLog("SR-MIXED-1", "WOULD_BLOCK", listOf(
            createViolation(RootCause.NO_EDGE, false, null, "SVO")
        )))

        // 5. 1 SR with a PROCEED and then a WOULD_BLOCK (Retry simulation)
        // Should count as ONE FULLY KNOWN block event
        logs.add(createLog("SR-RETRY-1", "PASS", emptyList()))
        logs.add(createLog("SR-RETRY-1", "WOULD_BLOCK", listOf(
            createViolation(RootCause.NOT_IN_GRAPH, true, "SOME_DEBT", "DVO")
        )))

        val parseResult = ShadowLogParseResult(logs, skippedCount = 42, dataQualityAnomalyCount = 1)
        val report = ShadowLogAggregator.aggregate(parseResult)

        // Event Level Asserts
        assertEquals("Total evaluated events (Unique SRs) should be 12 (5 proceed + 3 fully known + 2 fully unknown + 1 mixed + 1 retry)", 12, report.totalEvaluatedEvents)
        assertEquals("Total blocked events should be 7 (3 fully known + 2 fully unknown + 1 mixed + 1 retry)", 7, report.totalBlockedEvents)
        
        // Fully Known = 3 + 1 (retry) = 4, Unknown = 2 + 1 (Mixed) = 3
        assertEquals("Fully known block events should be 4", 4, report.fullyKnownBlockEvents)
        assertEquals("Unknown block events should be 3", 3, report.unknownBlockEvents)
        assertEquals("Total blocked events should equal sum of fully known and unknown", report.totalBlockedEvents, report.fullyKnownBlockEvents + report.unknownBlockEvents)

        assertEquals("Skipped count should be preserved", 42, report.totalSkippedLines)

        // Violation Level Asserts
        assertEquals("Unknown violation items should have 3 items (2 from fully unknown, 1 from mixed)", 3, report.unknownViolationItems.size)
        assertEquals("Known debt counts should reflect 3 from fully known + 1 from mixed", 4, report.knownDebtViolationCounts["ISM_16_XML"])
        assertEquals("Known debt counts should reflect 1 from retry", 1, report.knownDebtViolationCounts["SOME_DEBT"])
        
        // Root Cause Asserts
        // NOT_IN_GRAPH: 3 (from fully known) + 2 (from fully unknown) + 1 (from mixed) + 1 (retry) = 7
        // NO_EDGE: 1 (from mixed) = 1
        assertEquals("NOT_IN_GRAPH should have 7 total violations", 7, report.blockedViolationsByRootCause[RootCause.NOT_IN_GRAPH])
        assertEquals("NO_EDGE should have 1 total violation", 1, report.blockedViolationsByRootCause[RootCause.NO_EDGE])
    }

    @Test
    fun `test aggregator with zero blocks`() {
        val logs = listOf(
            createLog("SR-1", "PASS", emptyList()),
            createLog("SR-2", "PASS", emptyList()),
            createLog("SR-2", "PASS", emptyList()) // duplicate SR should count as 1 event
        )
        
        val parseResult = ShadowLogParseResult(logs, skippedCount = 0, dataQualityAnomalyCount = 0)
        val report = ShadowLogAggregator.aggregate(parseResult)

        assertEquals("Should group SR-2 so total is 2", 2, report.totalEvaluatedEvents)
        assertEquals(0, report.totalBlockedEvents)
        assertEquals(0, report.fullyKnownBlockEvents)
        assertEquals(0, report.unknownBlockEvents)
        assertEquals(0, report.unknownViolationItems.size)
        assertEquals(0, report.knownDebtViolationCounts.size)
        assertEquals(0, report.blockedViolationsByRootCause.size)
    }
}
