package net.ib.ixpert.ops.wuwagent.agent.stage0

object AdaptiveQuestionGenerator {

    /**
     * SR 분석 결과를 기반으로 3~5개의 적응형 질문을 생성합니다.
     *
     * 질문 우선순위:
     * 1. BLOCKING 결함 해소 (CRITICAL)
     * 2. 아키텍처 결정 (HIGH)
     * 3. SR 유형별 맥락 (MEDIUM)
     * 4. 최소 수 보장용 Fallback (LOW)
     */
    fun generate(analysis: SrAnalysis, metaGraphContext: String): List<Stage0Question> {
        val questions = mutableListOf<Stage0Question>()

        // ─── 1단계: BLOCKING 결함 해소 질문 ───
        analysis.deficiencies
            .filter { it.severity == DeficiencySeverity.BLOCKING }
            .forEach { deficiency ->
                val relatedDecision = if (deficiency.type == DeficiencyType.AMBIGUITY) {
                    analysis.architectureDecisions.find { it.topic == deficiency.field }
                } else null

                questions.add(Stage0Question(
                    category = QuestionCategory.DEFICIENCY_RESOLUTION,
                    priority = Priority.CRITICAL,
                    question = generateDeficiencyQuestion(deficiency),
                    purpose = "BLOCKING 결함 해소: ${deficiency.field}",
                    relatedDeficiency = deficiency,
                    relatedDecision = relatedDecision
                ))
            }

        // ─── 2단계: 아키텍처 결정 질문 ───
        analysis.architectureDecisions
            .filter { !it.resolved }
            .forEach { decision ->
                // 이미 BLOCKING 결함으로 등록된 경우 중복 방지
                val alreadyCovered = questions.any {
                    it.relatedDeficiency?.field == decision.topic
                }
                if (!alreadyCovered) {
                    questions.add(Stage0Question(
                        category = QuestionCategory.ARCHITECTURE_DECISION,
                        priority = Priority.HIGH,
                        question = generateArchitectureQuestion(decision),
                        purpose = "아키텍처 결정: ${decision.topic}",
                        relatedDecision = decision
                    ))
                }
            }

        // ─── 3단계: SR 유형별 맥락 질문 ───
        val contextQuestions = generateContextualQuestions(analysis, metaGraphContext)
        questions.addAll(contextQuestions)

        // ─── 정렬 + 상한 적용 ───
        val sorted = questions
            .sortedBy { it.priority.ordinal }
            .take(5)
            .toMutableList()

        // ─── 최소 질문 수 보장 (★ 수정: 실제 리스트에 추가) ───
        val hasNoBlockings = analysis.deficiencies.none {
            it.severity == DeficiencySeverity.BLOCKING
        }
        if (sorted.size < 3 && hasNoBlockings) {
            val fallbacks = generateFallbackQuestions(analysis).take(3 - sorted.size)
            sorted.addAll(fallbacks)
        }

        return sorted
    }

    private fun generateDeficiencyQuestion(deficiency: Deficiency): String {
        return when (deficiency.type) {
            DeficiencyType.OMISSION ->
                "다음 정보가 필요합니다:\n${deficiency.description}\n\n💡 ${deficiency.suggestion}"
            DeficiencyType.AMBIGUITY ->
                "${deficiency.description}\n\n💡 ${deficiency.suggestion}"
            DeficiencyType.CONFLICT ->
                "요건에 모순이 감지되었습니다:\n${deficiency.description}\n\n💡 ${deficiency.suggestion}"
        }
    }

    private fun generateArchitectureQuestion(decision: ArchDecision): String {
        return buildString {
            appendLine("\"${decision.topic}\"에 대한 구현 방식을 결정해야 합니다.")
            appendLine()
            appendLine("선택지:")
            decision.options.forEachIndexed { i, option ->
                appendLine("  ${i + 1}) $option")
            }
            appendLine()
            append("프로젝트 기존 패턴이 있다면 해당 방식을, 없다면 선호하는 방식 번호를 알려주세요.")
        }
    }

    private fun generateContextualQuestions(
        analysis: SrAnalysis,
        metaGraphContext: String
    ): List<Stage0Question> {
        val questionPool = getQuestionPoolByType(analysis.classification.primary)
        
        // 부 유형(secondary) 질문도 추가
        val secondaryPool = analysis.classification.secondary
            .flatMap { getQuestionPoolByType(it) }
            .filter { q -> questionPool.none { it == q } }  // 중복 제거

        val combinedPool = questionPool + secondaryPool

        return combinedPool
            .filter { !isAnswerableFromMetaGraph(it, metaGraphContext) }
            .take(2)  // 맥락 질문은 최대 2개
            .map { q ->
                Stage0Question(
                    category = QuestionCategory.CONTEXTUAL,
                    priority = Priority.MEDIUM,
                    question = q,
                    purpose = "유형별 맥락 확인"
                )
            }
    }

    private fun getQuestionPoolByType(type: SrType): List<String> {
        return when (type) {
            SrType.FIELD_ADD -> listOf(
                "추가 필드의 필수/선택 여부와 기본값이 있나요?",
                "해당 필드가 검색/정렬 조건으로도 사용되나요?",
                "기존 데이터에 대한 마이그레이션이 필요한가요?"
            )
            SrType.CONDITION_CHANGE -> listOf(
                "변경 전/후의 조건을 구체적으로 명시해 주시겠습니까?",
                "해당 조건 변경이 다른 분기에도 영향을 주나요?",
                "기존 데이터 중 새 조건에 해당하는 건이 있나요?"
            )
            SrType.NEW_FEATURE -> listOf(
                "유사한 기존 기능이 있다면 참조할 파일/화면을 알려주세요.",
                "해당 기능의 주요 사용자(역할)는 누구인가요?",
                "예외/에러 발생 시 기대하는 처리 방식이 있나요?"
            )
            SrType.INTERFACE_CHANGE -> listOf(
                "연동 대상 시스템의 스펙 문서(전문 레이아웃 등)를 참조할 수 있나요?",
                "기존 연동 코드 위치를 알고 계신가요?",
                "전문 필드 추가 시 기존 전문 버전과의 호환이 필요한가요?"
            )
            SrType.BATCH_MODIFY -> listOf(
                "배치 실패 시 재처리 정책(재시도/스킵/알림)은 어떻게 하나요?",
                "대상 데이터 건수 규모는 어느 정도인가요?",
                "기존 배치와 실행 순서 의존관계가 있나요?"
            )
        }
    }

    private fun isAnswerableFromMetaGraph(question: String, metaGraphContext: String): Boolean {
        if (metaGraphContext.isBlank()) return false
        val keywords = question.split(Regex("""[\s,?·]"""))
            .filter { it.length > 3 }
            .map { it.lowercase() }
        if (keywords.isEmpty()) return false
        val contextLower = metaGraphContext.lowercase()
        val matchCount = keywords.count { contextLower.contains(it) }
        return matchCount.toFloat() / keywords.size > 0.6f
    }

    private fun generateFallbackQuestions(analysis: SrAnalysis): List<Stage0Question> {
        return listOf(
            Stage0Question(
                category = QuestionCategory.FALLBACK,
                priority = Priority.LOW,
                question = "이 변경과 관련하여 특별히 주의해야 할 업무 규칙이나 제약사항이 있나요?",
                purpose = "일반 맥락 보강"
            ),
            Stage0Question(
                category = QuestionCategory.FALLBACK,
                priority = Priority.LOW,
                question = "이 SR과 관련된 테스트 시나리오(정상/예외)가 있다면 알려주세요.",
                purpose = "테스트 커버리지 보강"
            )
        )
    }
}
