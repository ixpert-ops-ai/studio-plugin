package net.ib.ixpert.ops.wuwagent.agent.completeness

import net.ib.ixpert.ops.wuwagent.agent.completeness.model.*
import net.ib.ixpert.ops.wuwagent.agent.completeness.rulesets.*
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType

object FrameworkRulesetRegistry {
    private val rulesets = mapOf(
        FrameworkType.SPRING_MVC_MYBATIS  to SpringMvcMybatisRuleset.value,
        FrameworkType.SPRING_BOOT_JPA     to SpringBootJpaRuleset.value,
        FrameworkType.SPRING_BOOT_MYBATIS to SpringBootMybatisRuleset.value,
        FrameworkType.SPRING_BOOT_JDBC    to SpringBootJdbcRuleset.value,
        FrameworkType.ANYFRAME_AP         to AnyframeApRuleset.value,
        FrameworkType.CUSTOM              to CustomRuleset.value
    )

    fun getRuleset(type: FrameworkType): FrameworkRuleset =
        rulesets[type] ?: CustomRuleset.value

    fun resolve(type: FrameworkType, ctx: GraphMatchContext): RulesetResolution {
        val ruleset = getRuleset(type)
        val degraded = ruleset.roleRealizations
            .flatMap { rr -> rr.companions.map { rr to it } }
            .filterNot { (_, c) -> c.matchBy.isPrecisionAvailable(ctx) }
            .map { (rr, c) ->
                DegradeNote(
                    role = rr.role, 
                    companionKind = c.kind, 
                    strategy = c.matchBy::class.simpleName ?: "?",
                    missing = c.matchBy.requires - ctx.capabilities
                )
            }
        return RulesetResolution(ruleset, degraded)
    }
}
