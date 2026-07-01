package net.ib.ixpert.ops.wuwagent.agent.completeness.rulesets

import net.ib.ixpert.ops.wuwagent.agent.completeness.model.*
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType

object SpringBootJdbcRuleset {
    val value = FrameworkRuleset(
        frameworkType = FrameworkType.SPRING_BOOT_JDBC,
        roleRealizations = listOf(
            RoleRealization(
                role = ArchRole.PERSISTENCE,
                anchorKind = FileKind.DAO_IMPL,        // ★ JdbcTemplate 쓰는 클래스 자체
                companions = emptyList(),
                preferModifyExisting = true
            ),
            RoleRealization(ArchRole.BUSINESS, FileKind.SERVICE_IMPL, emptyList(), true),
            RoleRealization(ArchRole.ENTRYPOINT, FileKind.CONTROLLER, emptyList(), false)
        ),
        entrypointRule = EntrypointRule(
            FileKind.CONTROLLER, EntrypointTrigger.USER_ACTION_PRESENT, CreationPolicy.ALLOW_NEW
        )
    )
}
