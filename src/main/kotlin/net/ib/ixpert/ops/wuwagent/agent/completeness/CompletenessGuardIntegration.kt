package net.ib.ixpert.ops.wuwagent.agent.completeness

import com.intellij.openapi.diagnostic.Logger
import net.ib.ixpert.ops.wuwagent.agent.completeness.model.*
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType

enum class GuardMode { SHADOW, ENFORCING }

sealed class GuardDecision {
    data class Proceed(val requiredFiles: Set<String>) : GuardDecision()
    data class Retry(val feedback: String) : GuardDecision()
}

class CompletenessGuardIntegration(
    private val mode: GuardMode = GuardMode.SHADOW,
    private val logger: Logger = Logger.getInstance(CompletenessGuardIntegration::class.java)
) {
    fun evaluateAfterVerifier(
        frameworkType: FrameworkType,
        requiredFiles: Set<String>,
        srFacts: SrFacts,
        ctx: GraphMatchContext,
        projectRoot: String? = null,
        runId: String = java.util.UUID.randomUUID().toString(),
        srKey: String = "unknown",
        srFactsSource: String = "heuristic"
    ): GuardDecision {
        val dynamicMode = if (System.getProperty("wuwagent.guard.enforcing") == "true") GuardMode.ENFORCING else this.mode
        
        val debtRegistry = JsonKnownDebtRegistry.loadFromClasspath("ixpert/known_debts.json", "ixpert/heuristic_suppressions.json")
        val report = CompletenessEngine(ctx, debtRegistry).evaluate(frameworkType, requiredFiles, srFacts)
        val outcome = CompletenessGuard.check(report)

        logGuardResult(frameworkType, requiredFiles, report, outcome)

        // Write to structured SHADOW log
        val verdictStr = if (outcome is GuardOutcome.Block) "WOULD_BLOCK" else if (outcome is GuardOutcome.Warn) "WARN" else "PASS"
        
        val companionDetails = report.companionViolations.map { finding ->
            val rootCause = RootCauseAnalyzer.analyze(finding, ctx)
            val debtReason = KnownDebtClassifier.classify(finding, rootCause)
            
            ViolationDetail(
                type = "CompanionMissing",
                anchorFile = finding.anchorPath,
                missingTargetKind = finding.companionKind.name,
                rootCause = rootCause,
                isKnownDebt = debtReason != null,
                debtReason = debtReason,
                resolvedCategory = ResolvedCategory.UNRESOLVED
            )
        }

        val roleDetails = report.roleViolations.map { finding ->
            ViolationDetail(
                type = "RoleMissing",
                anchorFile = "PROJECT",
                missingTargetKind = finding.role.name,
                rootCause = null,
                isKnownDebt = false,
                debtReason = null,
                resolvedCategory = ResolvedCategory.UNRESOLVED
            )
        }
        
        val violations = companionDetails + roleDetails
        
        val acceptedDebtDetails = report.acceptedDebts.map { finding ->
            ViolationDetail(
                type = "CompanionMissing",
                anchorFile = finding.anchorPath,
                missingTargetKind = finding.companionKind.name,
                rootCause = RootCauseAnalyzer.analyze(finding, ctx),
                isKnownDebt = true,
                debtReason = "REGISTERED_KNOWN_DEBT",
                resolvedCategory = ResolvedCategory.UNRESOLVED
            )
        }

        val shadowLog = ShadowLog(
            timestamp = java.time.Instant.now().toString(),
            srKey = srKey,
            runId = runId,
            rulesetVersion = "1.0.0-shadow",
            guardMode = dynamicMode.name,
            frameworkType = frameworkType.name,
            verdict = verdictStr,
            requiredFiles = requiredFiles.toList(),
            violations = violations,
            acceptedDebts = acceptedDebtDetails,
            srFactsSource = srFactsSource
        )

        ShadowLogger.log(projectRoot, shadowLog)

        return when (dynamicMode) {
            GuardMode.SHADOW -> {
                if (outcome is GuardOutcome.Block) {
                    logger.warn("[GUARD-SHADOW] 차단 대상이었으나 shadow 모드라 통과시킴: " +
                            outcome.messages.joinToString("; "))
                }
                GuardDecision.Proceed(requiredFiles)
            }
            GuardMode.ENFORCING -> when (outcome) {
                is GuardOutcome.Pass -> GuardDecision.Proceed(requiredFiles)
                is GuardOutcome.Warn -> GuardDecision.Proceed(requiredFiles)
                is GuardOutcome.Block -> GuardDecision.Retry(
                    feedback = PromptRenderer.renderViolationFeedback(outcome)
                )
            }
        }
    }

    private fun logGuardResult(
        fw: FrameworkType, required: Set<String>,
        report: CompletenessReport, outcome: GuardOutcome
    ) {
        val verdict = if (outcome is GuardOutcome.Block) "WOULD_BLOCK" else "PASS"
        logger.info("[GUARD] fw=$fw verdict=$verdict required=${required.size}건 " +
                "roleViolations=${report.roleViolations.size} " +
                "companionViolations=${report.companionViolations.size} " +
                "unclassified=${report.unclassifiedFiles.size}")
        if (outcome is GuardOutcome.Block) {
            outcome.messages.forEach { logger.info("[GUARD]   └ $it") }
        }
    }
}
