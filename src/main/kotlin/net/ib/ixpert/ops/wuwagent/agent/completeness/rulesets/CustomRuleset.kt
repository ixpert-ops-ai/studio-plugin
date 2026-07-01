package net.ib.ixpert.ops.wuwagent.agent.completeness.rulesets

import net.ib.ixpert.ops.wuwagent.agent.completeness.model.*
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType

object CustomRuleset {
    val value = FrameworkRuleset(
        frameworkType = FrameworkType.CUSTOM,
        roleRealizations = listOf(
            RoleRealization(ArchRole.PERSISTENCE, FileKind.DAO_IMPL, emptyList(), true),
            RoleRealization(ArchRole.BUSINESS,    FileKind.SERVICE_IMPL, emptyList(), true),
            RoleRealization(ArchRole.ENTRYPOINT,  FileKind.CONTROLLER, emptyList(), true)
        ),
        entrypointRule = EntrypointRule(
            FileKind.CONTROLLER, EntrypointTrigger.USER_ACTION_PRESENT, CreationPolicy.PREFER_MODIFY_EXISTING
        )
    )
}
