package net.ib.ixpert.ops.wuwagent.agent.completeness.rulesets

import net.ib.ixpert.ops.wuwagent.agent.completeness.model.*
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType

object SpringMvcMybatisRuleset {
    val value = FrameworkRuleset(
        frameworkType = FrameworkType.SPRING_MVC_MYBATIS,
        roleRealizations = listOf(
            RoleRealization(
                role = ArchRole.PERSISTENCE,
                anchorKind = FileKind.DAO_INTERFACE,
                companions = listOf(
                    CompanionRule(FileKind.DAO_IMPL, PairingStrength.MANDATORY,
                                  MatchStrategy.SameBasenameImpl, CompanionTrigger.ON_NEW_METHOD),
                    CompanionRule(FileKind.MYBATIS_XML, PairingStrength.MANDATORY,
                                  MatchStrategy.SameNamespaceXml, CompanionTrigger.ON_ANY_CHANGE)
                ),
                preferModifyExisting = true
            ),
            RoleRealization(
                role = ArchRole.BUSINESS,
                anchorKind = FileKind.SERVICE_INTERFACE,
                companions = listOf(
                    CompanionRule(FileKind.SERVICE_IMPL, PairingStrength.MANDATORY,
                                  MatchStrategy.SameBasenameImpl, CompanionTrigger.ON_NEW_METHOD)
                ),
                preferModifyExisting = true
            ),
            RoleRealization(
                role = ArchRole.ENTRYPOINT,
                anchorKind = FileKind.CONTROLLER,
                companions = emptyList(),
                preferModifyExisting = true
            ),
            RoleRealization(
                role = ArchRole.VIEW,
                anchorKind = FileKind.JSP_VIEW,
                companions = listOf(
                    CompanionRule(FileKind.JS_SCRIPT, PairingStrength.RECOMMENDED,
                                  MatchStrategy.SameFeatureJs, CompanionTrigger.ON_ANY_CHANGE)
                ),
                preferModifyExisting = true
            )
        ),
        entrypointRule = EntrypointRule(
            realizationKind = FileKind.CONTROLLER,
            requiredWhen = EntrypointTrigger.USER_ACTION_PRESENT,
            creationPolicy = CreationPolicy.PREFER_MODIFY_EXISTING
        )
    )
}
