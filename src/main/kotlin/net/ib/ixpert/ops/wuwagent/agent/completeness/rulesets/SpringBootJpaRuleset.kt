package net.ib.ixpert.ops.wuwagent.agent.completeness.rulesets

import net.ib.ixpert.ops.wuwagent.agent.completeness.model.*
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType

object SpringBootJpaRuleset {
    val value = FrameworkRuleset(
        frameworkType = FrameworkType.SPRING_BOOT_JPA,
        roleRealizations = listOf(
            RoleRealization(
                role = ArchRole.PERSISTENCE,
                anchorKind = FileKind.JPA_REPOSITORY,
                companions = emptyList(),          // ★ Impl 없음
                preferModifyExisting = true
            ),
            RoleRealization(ArchRole.BUSINESS, FileKind.SERVICE_IMPL, emptyList(), true),
            RoleRealization(ArchRole.ENTRYPOINT, FileKind.CONTROLLER, emptyList(), false),
            RoleRealization(
                role = ArchRole.DATA,
                anchorKind = FileKind.ENTITY,
                companions = listOf(
                    CompanionRule(FileKind.RESPONSE_DTO, PairingStrength.RECOMMENDED,
                                  MatchStrategy.SameBasenameImpl, CompanionTrigger.ON_ANY_CHANGE)
                ),
                preferModifyExisting = true
            )
        ),
        entrypointRule = EntrypointRule(
            FileKind.CONTROLLER, EntrypointTrigger.USER_ACTION_PRESENT, CreationPolicy.ALLOW_NEW
        )
    )
}
