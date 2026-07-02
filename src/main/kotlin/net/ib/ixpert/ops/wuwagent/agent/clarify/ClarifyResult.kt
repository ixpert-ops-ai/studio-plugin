package net.ib.ixpert.ops.wuwagent.agent.clarify

data class ClarifyResult(
    val enhancedRequirements: List<String>,
    val outOfScopeNotices: List<String>
)

data class ClarifyUserResponse(
    val requirements: List<String>
)

data class FinalRequirement(
    val originalInput: String,
    val confirmedItems: List<String>,
    val skippedItems: List<String>,
    val fullText: String
)
