package net.ib.ixpert.ops.wuwagent.agent.completeness

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.diagnostic.Logger
import net.ib.ixpert.ops.wuwagent.agent.completeness.model.*
import java.io.InputStreamReader

data class KnownDebtRule(
    val debtReason: String,
    val missingTargetKind: String,
    val anchorPathPattern: String
)

object KnownDebtClassifier {
    private val logger = Logger.getInstance(KnownDebtClassifier::class.java)
    private val rules: List<KnownDebtRule> = loadRules()

    private fun loadRules(): List<KnownDebtRule> {
        return try {
            val stream = KnownDebtClassifier::class.java.getResourceAsStream("/wuwagent/known_debt.json")
            if (stream != null) {
                val reader = InputStreamReader(stream, Charsets.UTF_8)
                val type = object : TypeToken<List<KnownDebtRule>>() {}.type
                Gson().fromJson(reader, type)
            } else {
                logger.warn("[GUARD] known_debt.json not found in resources.")
                emptyList()
            }
        } catch (e: Exception) {
            logger.error("[GUARD] Failed to load known_debt.json", e)
            emptyList()
        }
    }

    /**
     * Checks if a given finding matches any known debt rule.
     * Only applies if the rootCause is NOT_IN_GRAPH, as known debts are extraction limits, not true omissions.
     * Returns the debtReason if matched, null otherwise.
     */
    fun classify(finding: CompanionFinding, rootCause: RootCause): String? {
        if (rootCause != RootCause.NOT_IN_GRAPH) return null

        val kindStr = finding.companionKind.name
        val path = finding.anchorPath.replace("\\", "/")

        for (rule in rules) {
            if (rule.missingTargetKind == kindStr) {
                val regex = Regex(rule.anchorPathPattern)
                if (regex.containsMatchIn(path)) {
                    return rule.debtReason
                }
            }
        }
        return null
    }
}
