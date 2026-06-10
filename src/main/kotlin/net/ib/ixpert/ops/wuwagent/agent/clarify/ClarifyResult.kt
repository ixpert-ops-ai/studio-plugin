package net.ib.ixpert.ops.wuwagent.agent.clarify

data class ClarifyResult(
    val enhancedRequirements: List<String>,
    val questions: List<ClarifyQuestion>,
    val outOfScopeNotices: List<String>
)

data class ClarifyQuestion(
    val id: Int,
    val questionText: String,
    val defaultValue: String,
    val confirmedStatement: Map<String, String>? = null
)

data class ClarifyUserResponse(
    val answers: Map<Int, String>,
    val removedRequirements: List<String>,
    val additionalNotes: String?
)

data class FinalRequirement(
    val originalInput: String,
    val confirmedItems: List<String>,
    val skippedItems: List<String>,
    val fullText: String
)
