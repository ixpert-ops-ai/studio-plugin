package net.ib.ixpert.ops.wuwagent.agent.completeness

import net.ib.ixpert.ops.wuwagent.agent.completeness.model.*
import org.junit.Assert.assertEquals
import org.junit.Test

class ShadowLogAggregatorTest {

    private fun createLog(
        srKey: String, 
        verdict: String, 
        violations: List<ViolationDetail>, 
        requiredFiles: List<String> = emptyList(), 
        timestamp: String = "2026-06-30T10:00:00Z", 
        dataQualityAnomaly: String? = null,
        recommendations: List<CompanionRecommendation>? = null
    ): ShadowLog {
        return ShadowLog(
            timestamp = timestamp,
            srKey = srKey,
            runId = "RUN-TEST",
            rulesetVersion = "1.0",
            guardMode = "SHADOW",
            frameworkType = "ANYFRAME",
            verdict = verdict,
            requiredFiles = requiredFiles,
            violations = violations,
            recommendations = recommendations,
            dataQualityAnomaly = dataQualityAnomaly
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
            logs.add(createLog("SR-PROCEED-$i", "PASS", emptyList(), listOf("F1.java")))
        }

        // 2. 3 WOULD_BLOCK logs with only known_debt (NOT_IN_GRAPH)
        for (i in 1..3) {
            logs.add(createLog("SR-FULLY-KNOWN-$i", "WOULD_BLOCK", listOf(
                createViolation(RootCause.NOT_IN_GRAPH, true, "ISM_16_XML", "MYBATIS_XML")
            ), listOf("F1.java")))
        }

        // 3. 2 WOULD_BLOCK logs with only unknown debt (NOT_IN_GRAPH, isKnownDebt = false)
        for (i in 1..2) {
            logs.add(createLog("SR-FULLY-UNKNOWN-$i", "WOULD_BLOCK", listOf(
                createViolation(RootCause.NOT_IN_GRAPH, false, null, "DVO")
            ), listOf("F1.java")))
        }

        // 4. 1 SR with multiple WOULD_BLOCK logs (Mixed: 1 known + 1 unknown)
        // This should count as ONE UNKNOWN block event, but register both violations
        // Give them different requiredFiles so they survive DedupKey deduplication
        logs.add(createLog("SR-MIXED-1", "WOULD_BLOCK", listOf(
            createViolation(RootCause.NOT_IN_GRAPH, true, "ISM_16_XML", "MYBATIS_XML")
        ), listOf("A.java")))
        logs.add(createLog("SR-MIXED-1", "WOULD_BLOCK", listOf(
            createViolation(RootCause.NO_EDGE, false, null, "SVO")
        ), listOf("A.java", "B.java")))

        // 5. 1 SR with a PROCEED and then a WOULD_BLOCK (Retry simulation)
        // Should count as ONE FULLY KNOWN block event
        logs.add(createLog("SR-RETRY-1", "PASS", emptyList(), listOf("C.java")))
        logs.add(createLog("SR-RETRY-1", "WOULD_BLOCK", listOf(
            createViolation(RootCause.NOT_IN_GRAPH, true, "SOME_DEBT", "DVO")
        ), listOf("C.java", "D.java")))

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
            createLog("SR-1", "PASS", emptyList(), listOf("A.java")),
            createLog("SR-2", "PASS", emptyList(), listOf("B.java")),
            createLog("SR-2", "PASS", emptyList(), listOf("B.java")) // duplicate SR should count as 1 event
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

    @Test
    fun `test deduplication policy (anomalies, excluded projects, last-wins)`() {
        val logs = mutableListOf<ShadowLog>()

        // 1. Ghost line (should be ignored by deduplicate)
        logs.add(createLog("SR-ANOMALY", "WOULD_BLOCK", emptyList(), listOf("A.java"), dataQualityAnomaly = "GHOST"))

        // 2. Project exclusions (should be ignored)
        logs.add(createLog("SR-EXCLUDED-1", "WOULD_BLOCK", listOf(createViolation(RootCause.NOT_IN_GRAPH, false)), listOf("survey_admin/src/App.java")))

        // 3. Last-Wins for exact same DedupKey (Same SR and requiredFiles)
        // Log 1: Older, WOULD_BLOCK
        logs.add(createLog("SR-DEDUP", "WOULD_BLOCK", listOf(createViolation(RootCause.NOT_IN_GRAPH, false)), listOf("X.java"), timestamp = "2026-06-30T10:00:00Z"))
        // Log 2: Newer, PASS
        logs.add(createLog("SR-DEDUP", "PASS", emptyList(), listOf("X.java"), timestamp = "2026-06-30T10:05:00Z"))
        
        // 4. Same SR, different requiredFiles (should BOTH survive deduplication, and SR counts as 1 event, but its blocks are combined)
        logs.add(createLog("SR-DIFF-FILES", "PASS", emptyList(), listOf("Y.java"), timestamp = "2026-06-30T10:00:00Z"))
        logs.add(createLog("SR-DIFF-FILES", "WOULD_BLOCK", listOf(createViolation(RootCause.NOT_IN_GRAPH, true)), listOf("Y.java", "Z.java"), timestamp = "2026-06-30T10:10:00Z"))

        val parseResult = ShadowLogParseResult(logs, skippedCount = 0, dataQualityAnomalyCount = 1)
        val report = ShadowLogAggregator.aggregate(parseResult)

        // Valid SRs are SR-DEDUP and SR-DIFF-FILES
        assertEquals("Should evaluate exactly 2 valid SRs", 2, report.totalEvaluatedEvents)

        // For SR-DEDUP, Last-Wins selected the PASS log. So it is NOT blocked.
        // For SR-DIFF-FILES, the WOULD_BLOCK log survived (because different key) and blocks the SR.
        assertEquals("Only SR-DIFF-FILES should be blocked", 1, report.totalBlockedEvents)
        assertEquals("The block should be fully known", 1, report.fullyKnownBlockEvents)
        assertEquals("Unknown blocks should be 0", 0, report.unknownBlockEvents)
    }

    @Test
    fun `test aggregator math for recommendations`() {
        val logs = listOf(
            createLog("SR-1", "PASS", emptyList(), recommendations = listOf(
                CompanionRecommendation("A.java", "RESPONSE_DTO", "RECOMMENDED", true, null),
                CompanionRecommendation("B.java", "SERVICE_IMPL", "RECOMMENDED", false, null)
            )),
            createLog("SR-2", "WOULD_BLOCK", listOf(createViolation(RootCause.NOT_IN_GRAPH, false)), recommendations = listOf(
                CompanionRecommendation("C.java", "ENTITY", "RECOMMENDED", true, null),
                CompanionRecommendation("D.java", "REPOSITORY", "RECOMMENDED", true, null),
                CompanionRecommendation("E.java", "CONTROLLER", "RECOMMENDED", false, null)
            ))
        )
        
        val parseResult = ShadowLogParseResult(logs, skippedCount = 0, dataQualityAnomalyCount = 0)
        val report = ShadowLogAggregator.aggregate(parseResult)

        // total should be 5, satisfied should be 3, unsatisfied should be 2
        assertEquals(5, report.totalRecommendations)
        assertEquals(3, report.satisfiedRecommendations)
        assertEquals(2, report.unsatisfiedRecommendations)
    }
}
