package net.ib.ixpert.ops.wuwagent.agent.clarify

import com.fasterxml.jackson.databind.ObjectMapper
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType
import org.slf4j.LoggerFactory

class RequirementClarifier(
    private val llmClient: LLMClient,
    private val promptBuilder: ClarifyPromptBuilder
) {
    private val logger = LoggerFactory.getLogger(RequirementClarifier::class.java)
    private val objectMapper = ObjectMapper()

    fun clarify(userRequirement: String, fwType: FrameworkType, scopeSummary: String = ""): ClarifyResult {
        val systemPrompt = promptBuilder.buildSystemPrompt()
        val userPrompt = promptBuilder.buildUserPrompt(userRequirement, fwType, scopeSummary)
        
        var rawResponse = ""
        try {
            val response = llmClient.chat(systemPrompt, userPrompt)
            rawResponse = response?.message?.content ?: throw IllegalStateException("Empty LLM response")
            return parseResponse(rawResponse)
        } catch (e: Exception) {
            logger.warn("JSON parsing failed, attempting fallback...", e)
            try {
                // 재시도
                val fallbackResponse = llmClient.chat(systemPrompt, userPrompt + "\n반드시 JSON 형식으로만 반환하세요.")
                rawResponse = fallbackResponse?.message?.content ?: throw IllegalStateException("Empty LLM response")
                return parseResponse(rawResponse)
            } catch (retryEx: Exception) {
                logger.error("Stage 0 fallback failed", retryEx)
                // Fallback: 빈 원본을 확정 항목에 포함
                return ClarifyResult(
                    enhancedRequirements = listOf(userRequirement),
                    outOfScopeNotices = emptyList()
                )
            }
        }
    }

    internal fun parseResponse(rawResponse: String): ClarifyResult {
        val cleanJson = rawResponse
            .replace(Regex("```json\\s*", RegexOption.IGNORE_CASE), "")
            .replace("```", "")
            .trim()
            
        val rootNode = objectMapper.readTree(cleanJson)
        
        val enhancedReqs = mutableListOf<String>()
        rootNode.path("enhancedRequirements").forEach { enhancedReqs.add(it.asText()) }
        
        val outOfScopeNotices = mutableListOf<String>()
        rootNode.path("outOfScopeNotices").forEach { outOfScopeNotices.add(it.asText()) }
        
        return ClarifyResult(enhancedReqs, outOfScopeNotices)
    }

    fun finalize(
        clarifyResult: ClarifyResult,
        userResponse: ClarifyUserResponse,
        originalInput: String
    ): FinalRequirement {
        
        // 1. 사용자가 확정한 요구사항 (UI에서 수정/추가/삭제된 최종본)
        val confirmedItems = userResponse.requirements
        
        // 2. 최종 텍스트 조립
        val fullText = if (confirmedItems.isNotEmpty()) {
            confirmedItems.joinToString(". ") + "."
        } else {
            originalInput
        }
        
        return FinalRequirement(
            originalInput = originalInput,
            confirmedItems = confirmedItems,
            skippedItems = emptyList(),
            fullText = fullText
        )
    }
}
