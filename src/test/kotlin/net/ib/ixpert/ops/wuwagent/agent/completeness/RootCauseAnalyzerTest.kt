package net.ib.ixpert.ops.wuwagent.agent.completeness

import net.ib.ixpert.ops.wuwagent.agent.completeness.model.*
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*
import org.junit.Assert.assertEquals
import org.junit.Test

class RootCauseAnalyzerTest {

    private val graph = AnyframeGraphFixture.graph()
    private val ctx = ProjectGraphAdapter(graph)

    @Test
    fun `test NOT_IN_GRAPH when node does not exist at all`() {
        // DVO for a completely unknown BIZ (which does not exist in graph)
        val finding = CompanionFinding(
            role = ArchRole.PERSISTENCE,
            anchorPath = "src/main/java/sc/chn/aps/apc/ad/ad03/biz/UnknownBIZ.java",
            companionKind = FileKind.DVO,
            pairing = PairingStrength.MANDATORY,
            trigger = CompanionTrigger.ON_NEW_METHOD,
            result = MatchResult(null, false, MatchMode.PRECISION, 1.0, ""),
            existsInRequiredSet = false
        )
        
        val cause = RootCauseAnalyzer.analyze(finding, ctx)
        assertEquals(RootCause.NOT_IN_GRAPH, cause)
    }

    @Test
    fun `test NO_EDGE when node exists but edge is missing`() {
        // BIZ is APCADSbodrMngtBIZ, DVO is ACMBTBAPC026DVO
        // In AnyframeGraphFixture, ACMBTBAPC026DVO DOES exist! 
        // So the RootCauseAnalyzer will find it and return NO_EDGE (meaning it exists, but the MatchStrategy failed to find a valid edge).
        val finding = CompanionFinding(
            role = ArchRole.PERSISTENCE,
            anchorPath = "src/main/java/sc/chn/aps/apc/ad/ad03/biz/APCADSbodrMngtBIZ.java",
            companionKind = FileKind.DVO,
            pairing = PairingStrength.MANDATORY,
            trigger = CompanionTrigger.ON_NEW_METHOD,
            result = MatchResult(null, true, MatchMode.PRECISION, 1.0, ""),
            existsInRequiredSet = false
        )
        
        val cause = RootCauseAnalyzer.analyze(finding, ctx)
        assertEquals(RootCause.NO_EDGE, cause)
    }
}
