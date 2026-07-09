package net.ib.ixpert.ops.wuwagent.agent.completeness.rulesets

import net.ib.ixpert.ops.wuwagent.agent.completeness.model.*
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType

object AnyframeApRuleset {
    val value = FrameworkRuleset(
        frameworkType = FrameworkType.ANYFRAME_AP,
        roleRealizations = listOf(
            RoleRealization(
                role = ArchRole.ENTRYPOINT,
                anchorKind = FileKind.SERVICE_INTERFACE, // *SVC
                companions = listOf(
                    CompanionRule(FileKind.SERVICE_IMPL, PairingStrength.MANDATORY,
                                  MatchStrategy.ImplementedInterfacesMatch, CompanionTrigger.ON_NEW_METHOD)
                ),
                preferModifyExisting = true
            ),
            RoleRealization(
                role = ArchRole.BUSINESS,
                anchorKind = FileKind.SERVICE_IMPL, // SVCImpl (The implementation of Entrypoint)
                companions = listOf(
                    // SVCImpl always needs SVO
                    CompanionRule(FileKind.SVO, PairingStrength.MANDATORY,
                                  MatchStrategy.DomainPrefixVO("SVO"), CompanionTrigger.ON_NEW_METHOD),
                    // If SVCImpl calls BIZ, BIZ must be accompanied by BVO.
                    CompanionRule(FileKind.BVO, PairingStrength.RECOMMENDED,
                                  MatchStrategy.CallChainDelegatingMatch(FileKind.BIZ, MatchStrategy.DomainPrefixVO("BVO")), CompanionTrigger.ON_NEW_METHOD)
                ),
                preferModifyExisting = true
            ),
            RoleRealization(
                role = ArchRole.PERSISTENCE,
                anchorKind = FileKind.BIZ, // BIZ (The business logic)
                companions = listOf(
                    // If BIZ calls DEM/DQM, it must be accompanied by DVO.
                    // We check both DEM and DQM as persistence links.
                    CompanionRule(FileKind.DVO, PairingStrength.MANDATORY,
                                  MatchStrategy.CallChainDelegatingMatch(FileKind.DEM, MatchStrategy.DomainPrefixVO("DVO")), CompanionTrigger.ON_NEW_METHOD),
                    CompanionRule(FileKind.DVO, PairingStrength.MANDATORY,
                                  MatchStrategy.CallChainDelegatingMatch(FileKind.DQM, MatchStrategy.DomainPrefixVO("DVO")), CompanionTrigger.ON_NEW_METHOD)
                ),
                preferModifyExisting = true
            )
            // Note: LoginController discovery fallback is handled outside the ruleset structure.
        ),
        entrypointRule = EntrypointRule(
            realizationKind = FileKind.SERVICE_INTERFACE,
            requiredWhen = EntrypointTrigger.ALWAYS, // Entrypoint is always required if the task adds logic
            creationPolicy = CreationPolicy.PREFER_MODIFY_EXISTING
        )
    )
}
