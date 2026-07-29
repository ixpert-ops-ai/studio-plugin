package net.ib.ixpert.ops.wuwagent.agent.completeness.rulesets

import net.ib.ixpert.ops.wuwagent.agent.completeness.model.*
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType

object SpringBootMybatisRuleset {
    val value = FrameworkRuleset(
        frameworkType = FrameworkType.SPRING_BOOT_MYBATIS,
        roleRealizations = listOf(
            RoleRealization(
                role = ArchRole.PERSISTENCE,
                anchorKind = FileKind.DAO_INTERFACE,    // @Mapper
                companions = listOf(
                    // ★★ DAO_IMPL 절대 넣지 말 것. XML만 MANDATORY
                    // 참고: @Select 어노테이션 쿼리 전용 프로젝트라면 XML이 과잉 차단될 수 있음. 차후 설정 플래그로 분기 필요.
                    CompanionRule(FileKind.MYBATIS_XML, PairingStrength.MANDATORY,
                                  MatchStrategy.SameNamespaceXml, CompanionTrigger.ON_ANY_CHANGE)
                ),
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
