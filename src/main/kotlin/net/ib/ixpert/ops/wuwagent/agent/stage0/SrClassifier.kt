package net.ib.ixpert.ops.wuwagent.agent.stage0

import com.fasterxml.jackson.databind.ObjectMapper

interface LlmClassifierClient {
    fun classify(prompt: String): String?
}

// 1차 구현: NoOp 클라이언트
object NoOpLlmClient : LlmClassifierClient {
    override fun classify(prompt: String): String? = null  // 항상 null → 폴백 유도
}

object SrClassifier {
    
    var llmClient: LlmClassifierClient = NoOpLlmClient

    /**
     * 1차: 키워드 룰 기반 사전 분류
     * - 지배율(dominanceRatio) 50% 미만이면 LLM 위임
     * - 단어 경계를 고려한 매칭
     */
    fun preClassify(input: SrInput): SrClassification? {
        val text = "${input.title} ${input.description}".lowercase()
        
        val scores = SrType.values().associateWith { type ->
            type.keywords.count { keyword ->
                text.contains(keyword.lowercase())
            }
        }

        val maxEntry = scores.maxByOrNull { it.value } ?: return null
        if (maxEntry.value < 2) return null  // 최소 매칭 2회 필요

        val totalMatches = scores.values.sum().coerceAtLeast(1)
        val dominanceRatio = maxEntry.value.toFloat() / totalMatches

        // 지배율 50% 미만 → 복합 SR → LLM 위임
        if (dominanceRatio < 0.5f) return null

        val confidence = when {
            maxEntry.value >= 6 -> 95
            maxEntry.value >= 4 -> 88
            maxEntry.value >= 3 -> 82
            else -> 75
        }

        val secondary = scores
            .filter { it.key != maxEntry.key && it.value >= 2 }
            .keys.toList()

        return SrClassification(
            primary = maxEntry.key,
            secondary = secondary,
            confidence = confidence,
            reason = "키워드 매칭: ${maxEntry.key}(${maxEntry.value}회), 지배율 ${(dominanceRatio * 100).toInt()}%",
            classifiedBy = ClassificationMethod.RULE
        )
    }

    /**
     * 2차: LLM 기반 정밀 분류 (with 폴백)
     */
    fun classifyWithLlm(input: SrInput, metaGraphSummary: String): SrClassification {
        return try {
            val prompt = loadPrompt("classify_sr.txt")
                .replace("{{SR_TITLE}}", input.title)
                .replace("{{SR_DESCRIPTION}}", input.description)
                .replace("{{META_SUMMARY}}", metaGraphSummary)

            val response = llmClient.classify(prompt) ?: throw IllegalStateException("LLM 분류 실패")
            parseSrClassification(response).copy(classifiedBy = ClassificationMethod.LLM)
        } catch (e: Exception) {
            // ★ 폴백: 가장 안전한 기본 분류
            SrClassification(
                primary = SrType.NEW_FEATURE,  // 가장 깊은 분석으로 안전하게
                secondary = emptyList(),
                confidence = 40,
                reason = "LLM 장애로 인한 폴백 분류: ${e.message?.take(50)}",
                classifiedBy = ClassificationMethod.FALLBACK
            )
        }
    }

    /**
     * 통합 분류 (Rule 우선 → 실패 시 LLM → 실패 시 Fallback)
     */
    fun classify(input: SrInput, metaGraphSummary: String): SrClassification {
        return preClassify(input) ?: classifyWithLlm(input, metaGraphSummary)
    }

    private fun loadPrompt(filename: String): String {
        return try {
            this::class.java.classLoader
                .getResourceAsStream("prompts/$filename")
                ?.bufferedReader(Charsets.UTF_8)
                ?.readText()
                ?: "프롬프트를 찾을 수 없습니다."
        } catch (e: Exception) {
            "프롬프트를 로드할 수 없습니다."
        }
    }

    private fun parseSrClassification(response: String): SrClassification {
        val json = ObjectMapper().readTree(response)
        return SrClassification(
            primary = SrType.valueOf(json["primary"].asText()),
            secondary = json["secondary"]?.map { SrType.valueOf(it.asText()) } ?: emptyList(),
            confidence = json["confidence"].asInt(),
            reason = json["reason"].asText()
        )
    }
}
