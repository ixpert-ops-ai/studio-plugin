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

    fun clarify(userRequirement: String, fwType: FrameworkType): ClarifyResult {
        val systemPrompt = promptBuilder.buildSystemPrompt(fwType)
        val userPrompt = promptBuilder.buildUserPrompt(userRequirement)
        
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
                // Fallback: 빈 질문 반환, 원본을 확정 항목에 포함
                return ClarifyResult(
                    enhancedRequirements = listOf(userRequirement),
                    questions = emptyList(),
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
        
        val questions = mutableListOf<ClarifyQuestion>()
        rootNode.path("questions").forEach {
            val confirmedStatementMap = mutableMapOf<String, String>()
            it.path("confirmedStatement").takeIf { !it.isMissingNode }?.fields()?.forEach { entry ->
                confirmedStatementMap[entry.key] = entry.value.asText()
            }
            questions.add(
                ClarifyQuestion(
                    id = it.path("id").asInt(),
                    questionText = it.path("questionText").asText(),
                    defaultValue = it.path("defaultValue").asText(),
                    confirmedStatement = if (confirmedStatementMap.isNotEmpty()) confirmedStatementMap else null
                )
            )
        }
        
        val outOfScopeNotices = mutableListOf<String>()
        rootNode.path("outOfScopeNotices").forEach { outOfScopeNotices.add(it.asText()) }
        
        return ClarifyResult(enhancedReqs, questions, outOfScopeNotices)
    }

    fun finalize(
        clarifyResult: ClarifyResult,
        userResponse: ClarifyUserResponse,
        originalInput: String
    ): FinalRequirement {
        
        // 1. 사용자가 제거한 항목 필터링
        val confirmedItems = clarifyResult.enhancedRequirements
            .filterNot { it in userResponse.removedRequirements }
        
        // 2. 질문 답변 반영
        val answeredItems = clarifyResult.questions.mapNotNull { q ->
            val answer = userResponse.answers[q.id] ?: q.defaultValue
            val upperAnswer = answer.uppercase()
            if (upperAnswer == "Y" || upperAnswer == "N") {
                q.confirmedStatement?.get(upperAnswer) ?: "${q.questionText}: $upperAnswer"
            } else {
                "${q.questionText}: $answer"
            }
        }
        
        // 3. 추가 노트 반영
        val additions = userResponse.additionalNotes?.let { listOf(it) } ?: emptyList()
        
        // 4. 최종 텍스트 조립
        val allItems = confirmedItems + answeredItems + additions
        val fullText = if (allItems.isNotEmpty()) {
            allItems.joinToString(". ") + "."
        } else {
            originalInput
        }
        
        return FinalRequirement(
            originalInput = originalInput,
            confirmedItems = confirmedItems + answeredItems,
            skippedItems = userResponse.removedRequirements,
            fullText = fullText
        )
    }
}
