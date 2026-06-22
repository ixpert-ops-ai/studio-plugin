package net.ib.ixpert.ops.wuwagent.agent.stage0

object Stage0Gate {

    fun evaluate(
        analysis: SrAnalysis,
        questions: List<Stage0Question>,
        answers: Map<Stage0Question, String>
    ): GateEvaluation {
        val blockingReasons = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val unresolvedQuestions = mutableListOf<Stage0Question>()

        // ─── Gate 1: BLOCKING 결함 해소 확인 ───
        questions
            .filter { it.priority == Priority.CRITICAL }
            .forEach { question ->
                val answer = answers[question]
                if (!isAnswerResolved(answer, question)) {
                    blockingReasons.add("[${question.relatedDeficiency?.type ?: "BLOCKING"}] " +
                        "${question.relatedDeficiency?.field ?: question.purpose}: 미해소")
                    unresolvedQuestions.add(question)
                }
            }

        // ─── Gate 2: 아키텍처 결정 확인 ───
        questions
            .filter { it.category == QuestionCategory.ARCHITECTURE_DECISION }
            .forEach { question ->
                val answer = answers[question]
                if (!isArchitectureDecisionResolved(answer, question)) {
                    blockingReasons.add("[AMBIGUITY] ${question.relatedDecision?.topic ?: question.purpose}: 구현 방식 미결정")
                    unresolvedQuestions.add(question)
                }
            }

        // ─── Gate 3: 최소 품질 기준 (이미 Phase 0-2에서 감지되지만 이중 확인) ───
        if (analysis.qualityIndicators.descriptionLength < 30) {
            blockingReasons.add("[OMISSION] SR 설명이 30자 미만으로 분석 불가")
        }

        // ─── WARNING 수집 ───
        analysis.deficiencies
            .filter { it.severity == DeficiencySeverity.WARNING }
            .forEach { warnings.add("[${it.type}] ${it.field}: ${it.description}") }

        // ─── 낮은 분류 confidence 경고 ───
        if (analysis.classification.confidence < 60) {
            warnings.add("[INFO] SR 유형 분류 신뢰도가 ${analysis.classification.confidence}%로 낮습니다. " +
                "분석 결과를 주의깊게 확인해 주세요.")
        }
        if (analysis.classification.classifiedBy == ClassificationMethod.FALLBACK) {
            warnings.add("[INFO] LLM 분류 실패로 폴백 분류가 적용되었습니다. 분석 깊이가 최대(DEEP)로 설정됩니다.")
        }

        // ─── 결과 판정 ───
        val canProceed = blockingReasons.isEmpty()

        return GateEvaluation(
            canProceed = canProceed,
            blockingReasons = blockingReasons,
            warnings = warnings,
            enrichedSr = if (canProceed) buildEnrichedSr(analysis, questions, answers) else null,
            unresolvedQuestions = unresolvedQuestions
        )
    }

    // ═══════════════════════════════════════════════
    // ★ 답변 품질 검증 (리뷰 #3 핵심 수정)
    // ═══════════════════════════════════════════════

    private val EVASIVE_PATTERNS = listOf(
        Regex("""(?:모르겠|나중에|일단|추후|TBD|미정|아직|잘\s*모르)"""),
        Regex("""(?:그냥|대충|알아서|상관없|아무거나)"""),
        Regex("""^(?:네|예|ㅇㅇ|ㅇ|ok|확인)$""", RegexOption.IGNORE_CASE)
    )

    private fun isAnswerResolved(answer: String?, question: Stage0Question): Boolean {
        if (answer.isNullOrBlank()) return false
        if (answer.trim().length < 5) return false

        // 회피성 답변 감지
        if (EVASIVE_PATTERNS.any { it.containsMatchIn(answer) }) return false

        // OMISSION 타입: 최소 길이 + 비회피성이면 해소로 판정
        if (question.relatedDeficiency?.type == DeficiencyType.OMISSION) {
            return answer.length >= 10
        }

        // AMBIGUITY 타입: 선택지 관련 키워드 포함 여부 추가 검증
        if (question.relatedDeficiency?.type == DeficiencyType.AMBIGUITY) {
            return isArchitectureDecisionResolved(answer, question)
        }

        return answer.length >= 10
    }

    private fun isArchitectureDecisionResolved(answer: String?, question: Stage0Question): Boolean {
        if (answer.isNullOrBlank() || answer.trim().length < 3) return false

        // 회피성 답변 감지
        if (EVASIVE_PATTERNS.any { it.containsMatchIn(answer) }) return false

        val decision = question.relatedDecision ?: return answer.length >= 10

        // 선택지 번호로 답변 (예: "1", "2번", "첫 번째")
        val numberPatterns = listOf(
            Regex("""^[1-${decision.options.size}]$"""),
            Regex("""[1-${decision.options.size}]\s*번"""),
            Regex("""(?:첫|두|세|네)\s*번째""")
        )
        if (numberPatterns.any { it.containsMatchIn(answer) }) return true

        // 선택지 키워드 포함 여부 (옵션의 핵심 단어 3자 이상 매칭)
        val optionKeywords = decision.options.flatMap { option ->
            option.split(Regex("""[\s/(),]"""))
                .filter { it.length >= 3 }
                .map { it.lowercase() }
        }
        val answerLower = answer.lowercase()
        val hasOptionKeyword = optionKeywords.any { answerLower.contains(it) }

        // 기존 패턴 참조 답변도 해소로 인정
        val referencePatterns = Regex("""(?:기존|현재|같은|동일|재사용).*(?:방식|패턴|구조)""")
        val hasReference = referencePatterns.containsMatchIn(answer)

        return hasOptionKeyword || hasReference || answer.length >= 20
    }

    // ═══════════════════════════════════════════════
    // EnrichedSr 조립
    // ═══════════════════════════════════════════════

    private fun buildEnrichedSr(
        analysis: SrAnalysis,
        questions: List<Stage0Question>,
        answers: Map<Stage0Question, String>
    ): EnrichedSr {
        // 아키텍처 결정 반영
        val resolvedDecisions = analysis.architectureDecisions.map { decision ->
            val relatedQuestion = questions.find { it.relatedDecision?.topic == decision.topic }
            val answer = relatedQuestion?.let { answers[it] }
            if (answer != null) {
                decision.copy(
                    resolved = true,
                    selectedOption = answer.trim()
                )
            } else {
                decision
            }
        }

        // 추가 맥락 조합
        val additionalContext = answers
            .filter { it.key.category in listOf(QuestionCategory.CONTEXTUAL, QuestionCategory.FALLBACK) }
            .filter { it.value.isNotBlank() }
            .map { "Q: ${it.key.question}\nA: ${it.value}" }
            .joinToString("\n\n")

        val classification = analysis.classification

        return EnrichedSr(
            originalSr = analysis.srInput,
            classification = classification,
            resolvedDecisions = resolvedDecisions,
            additionalContext = additionalContext,
            analysisDepth = determineDepth(classification),
            fileLimit = calculateFileLimit(classification),
            searchDirections = determineSearchDirections(classification),
            warnings = analysis.deficiencies
                .filter { it.severity == DeficiencySeverity.WARNING }
                .map { "${it.field}: ${it.description}" }
        )
    }

    private fun determineDepth(classification: SrClassification): AnalysisDepth {
        val allTypes = listOf(classification.primary) + classification.secondary
        return if (allTypes.any { it.analysisDepth == AnalysisDepth.DEEP }) {
            AnalysisDepth.DEEP
        } else {
            AnalysisDepth.SHALLOW
        }
    }

    // ★ 리뷰 #5 반영: DEEP secondary만 +2, SHALLOW +1, 상한 12
    private fun calculateFileLimit(classification: SrClassification): Int {
        val baseLimit = when (classification.primary) {
            SrType.FIELD_ADD -> 5
            SrType.CONDITION_CHANGE -> 4
            SrType.NEW_FEATURE -> 8
            SrType.INTERFACE_CHANGE -> 6
            SrType.BATCH_MODIFY -> 6
        }
        val bonus = classification.secondary.sumOf { type ->
            if (type.analysisDepth == AnalysisDepth.DEEP) 2.toInt() else 1.toInt()
        }
        return minOf(baseLimit + bonus, 12)
    }

    private fun determineSearchDirections(classification: SrClassification): List<SearchDirection> {
        val allTypes = listOf(classification.primary) + classification.secondary
        return allTypes.map { type ->
            when (type) {
                SrType.FIELD_ADD -> SearchDirection.VERTICAL
                SrType.CONDITION_CHANGE -> SearchDirection.HORIZONTAL
                SrType.NEW_FEATURE -> SearchDirection.PATTERN_REFERENCE
                SrType.INTERFACE_CHANGE -> SearchDirection.EXTERNAL_BOUNDARY
                SrType.BATCH_MODIFY -> SearchDirection.SCHEDULER_ENTRY
            }
        }.distinct()
    }
}
