package net.ib.ixpert.ops.wuwagent.agent.completeness

import net.ib.ixpert.ops.wuwagent.agent.completeness.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KnownDebtClassifierTest {

    @Test
    fun `test classify returns correct debtReason for ISM 16 Mapper XMLs when NOT_IN_GRAPH`() {
        val finding = CompanionFinding(
            role = ArchRole.PERSISTENCE,
            anchorPath = "src/main/java/sc/chn/aps/ism/common/dao/SomeDAO.java",
            companionKind = FileKind.MYBATIS_XML,
            pairing = PairingStrength.MANDATORY,
            trigger = CompanionTrigger.ON_NEW_METHOD,
            result = MatchResult(null, false, MatchMode.PRECISION, 1.0, ""),
            existsInRequiredSet = false
        )

        val debtReason = KnownDebtClassifier.classify(finding, RootCause.NOT_IN_GRAPH)
        assertEquals("ISM_16_XML", debtReason)
    }

    @Test
    fun `test classify returns null for ISM Mapper XMLs if rootCause is NO_EDGE`() {
        val finding = CompanionFinding(
            role = ArchRole.PERSISTENCE,
            anchorPath = "src/main/java/sc/chn/aps/ism/common/dao/SomeDAO.java",
            companionKind = FileKind.MYBATIS_XML,
            pairing = PairingStrength.MANDATORY,
            trigger = CompanionTrigger.ON_NEW_METHOD,
            result = MatchResult(null, false, MatchMode.PRECISION, 1.0, ""),
            existsInRequiredSet = false
        )

        // If the XML is in the graph but the edge is missing, it's NOT a known extraction debt (which is a NOT_IN_GRAPH limit).
        val debtReason = KnownDebtClassifier.classify(finding, RootCause.NO_EDGE)
        assertNull("Should return null because rootCause is NO_EDGE", debtReason)
    }

    @Test
    fun `test classify returns null for DVO even if NOT_IN_GRAPH because VO limits are handled manually`() {
        val finding = CompanionFinding(
            role = ArchRole.PERSISTENCE,
            anchorPath = "src/main/java/sc/chn/aps/apc/ad/ad03/biz/APCADSbodrMngtBIZ.java",
            companionKind = FileKind.DVO,
            pairing = PairingStrength.MANDATORY,
            trigger = CompanionTrigger.ON_NEW_METHOD,
            result = MatchResult(null, false, MatchMode.PRECISION, 1.0, ""),
            existsInRequiredSet = false
        )

        val debtReason = KnownDebtClassifier.classify(finding, RootCause.NOT_IN_GRAPH)
        assertNull("Should return null for DVO because VO limits are no longer automatically tagged", debtReason)
    }

    @Test
    fun `test classify returns null for unknown debt`() {
        val finding = CompanionFinding(
            role = ArchRole.BUSINESS,
            anchorPath = "src/main/java/sc/chn/aps/apc/some/service/MySVC.java",
            companionKind = FileKind.SERVICE_IMPL,
            pairing = PairingStrength.MANDATORY,
            trigger = CompanionTrigger.ON_NEW_METHOD,
            result = MatchResult(null, false, MatchMode.PRECISION, 1.0, ""),
            existsInRequiredSet = false
        )

        val debtReason = KnownDebtClassifier.classify(finding, RootCause.NOT_IN_GRAPH)
        assertNull("Should return null for unknown debt", debtReason)
    }
}
