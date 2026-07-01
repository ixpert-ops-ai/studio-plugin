package net.ib.ixpert.ops.wuwagent.agent.completeness.model

enum class RootCause { 
    NOT_IN_GRAPH, 
    NO_EDGE 
}

enum class ResolvedCategory { 
    UNRESOLVED, 
    EXTRACTOR_DEBT, 
    TRUE_MISSING 
}

data class ViolationDetail(
    val type: String,           // CompanionMissing, RoleMissing, Unclassified
    val anchorFile: String?,
    val missingTargetKind: String?,
    val rootCause: RootCause?,
    val isKnownDebt: Boolean,
    val debtReason: String?,
    val resolvedCategory: ResolvedCategory = ResolvedCategory.UNRESOLVED
)

data class ShadowLog(
    val timestamp: String,
    val srKey: String,          // Hash of primaryReq
    val runId: String,          // UUID for this pipeline run
    val rulesetVersion: String,
    val guardMode: String,
    val frameworkType: String,
    val verdict: String,        // PASS, WARN, WOULD_BLOCK
    val requiredFiles: List<String>,
    val violations: List<ViolationDetail>
)
