package net.ib.ixpert.ops.wuwagent.agent.clarify

import net.ib.ixpert.ops.wuwagent.agent.clarify.model.*
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph

/**
 * Stage 0 대화 제어 엔진 및 개방형 질문 트리거.
 * 설계서 (v1.0) 3절, 5절, 6절 준수.
 */
class Stage0ClarificationEngine(
    private val scanner: Stage0GraphScanner,
    private val graph: ProjectGraph
) {
    /**
     * 사용자 입력 데이터 구조
     */
    data class UserInput(
        val verdictUpdates: Map<String, Verdict> = emptyMap(), // id -> CONFIRMED / REJECTED
        val userStatement: String? = null,                    // 추가 발화 또는 개방형 질문 답변
        val isCompletionDeclared: Boolean = false             // 사용자의 완결 선언 여부
    )

    /**
     * 한 턴의 처리 결과
     */
    data class Stage0TurnResult(
        val state: Stage0State,
        val openQuestion: String? = null,
        val isExhausted: Boolean = false,
        val isReadyForStage1: Boolean = false
    )

    /**
     * 6절: Stage 1 전이 계약 산출물
     */
    data class Stage0TransitionContract(
        val confirmedItems: List<RequirementItem>,
        val trustedExistingRefs: List<LinkHint.ExistingRef>,
        val newCreations: List<RequirementItem>,
        val enrichedRequirementText: String
    )

    /**
     * 초기 세션 생성 (Turn 0)
     */
    fun initSession(originalRequirement: String): Stage0TurnResult {
        val initialTokens = scanner.extractTokens(originalRequirement)
        val initialCandidates = scanner.rescanUnverified(initialTokens, emptyList())
        val openQ = checkOpenQuestionTrigger(initialTokens, initialCandidates)

        val state = Stage0State(
            originalRequirement = originalRequirement,
            items = initialCandidates,
            seedSet = initialTokens
        )

        return Stage0TurnResult(
            state = state,
            openQuestion = openQ,
            isExhausted = initialCandidates.isEmpty(),
            isReadyForStage1 = false
        )
    }

    /**
     * 3절: 대화 턴 처리 (증분 seed 확장 + 미판정 영역 재탐색 + 확정분 동결)
     */
    fun processTurn(state: Stage0State, userInput: UserInput): Stage0TurnResult {
        // 1. 기존 items에 사용자 판정(CONFIRMED/REJECTED) 반영
        val updatedItems = state.items.map { item ->
            val update = userInput.verdictUpdates[item.id]
            if (update != null && update != Verdict.PENDING) {
                item.copy(
                    verdict = update,
                    source = if (update == Verdict.CONFIRMED) HintSource.USER_CONFIRMED else item.source
                )
            } else {
                item
            }
        }.toMutableList()

        // 2. 사용자 추가 발화 처리 (개방형 질문 답변 또는 신규 요구)
        var newSeedTokens = state.seedSet
        if (!userInput.userStatement.isNullOrBlank()) {
            val stmt = userInput.userStatement.trim()
            val utteredTokens = scanner.extractTokens(stmt, emptySet())
            newSeedTokens = state.seedSet + utteredTokens

            // Rule-3: 토큰 기반 그래프 조회 및 분기 판정
            // 그래프 실재 여부 확인 (발화에서 추출된 토큰 중 클래스명, 파일 경로, 리소스 노드 매칭)
            val structuralTokens = utteredTokens.filter { it.kind == TokenKind.STRUCTURAL }.map { it.value.lowercase() }
            val matchedExistingNodes = mutableListOf<Pair<String, String>>() // (filePath, matchedSymbol)

            for (st in structuralTokens) {
                val matchedFile = graph.files.values.find { 
                    it.className.equals(st, ignoreCase = true) || 
                    it.path.substringAfterLast("/").substringBeforeLast(".").equals(st, ignoreCase = true) ||
                    it.path.substringAfterLast("/").equals(st, ignoreCase = true)
                }
                if (matchedFile != null) {
                    matchedExistingNodes.add(matchedFile.path to matchedFile.className)
                }
                val matchedResource = graph.resourceNodes.find { 
                    val fileName = it.path.substringAfterLast("/")
                    val fileNameWithoutExt = fileName.substringBeforeLast(".")
                    fileName.equals(st, ignoreCase = true) || fileNameWithoutExt.equals(st, ignoreCase = true)
                }
                if (matchedResource != null) {
                    // 리소스 노드(JSP/JS/XML)는 별도 클래스명이 없으므로 완전 일치한 파일명 식별자(st)를 심볼로 보존
                    matchedExistingNodes.add(matchedResource.path to st)
                }
            }

            if (matchedExistingNodes.isNotEmpty()) {
                // 분기 A: 그래프 실재 노드 (엣지 보유 / 실존 파일) -> ExistingRef + USER_UTTERANCE 출처
                for ((filePath, symbol) in matchedExistingNodes.distinctBy { it.first }) {
                    val fileName = filePath.substringAfterLast("/")
                    val statement = "$stmt (관련 파일: $fileName)"
                    val hint = LinkHint.ExistingRef(filePath, listOf(symbol))
                    val newItem = RequirementItem(
                        id = RequirementItem.deriveId(hint, statement),
                        statement = statement,
                        source = HintSource.USER_UTTERED,
                        hint = hint,
                        anchorRationale = "사용자 발화 기반 그래프 실재 노드 식별 ($stmt)",
                        verdict = Verdict.CONFIRMED, // 사용자 발화는 즉시 동결
                        confidence = ConfidenceBucket.HIGH_CONFIDENCE,
                        provenanceSignals = setOf(ProvenanceSignal.USER_UTTERANCE)
                    )
                    if (updatedItems.none { it.id == newItem.id }) {
                        updatedItems.add(newItem)
                    }
                }
            } else {
                // 분기 B: 그래프 부존재 / 0-degree (신규 생성 요구) -> NewCreation
                val hint = LinkHint.NewCreation
                val newItem = RequirementItem(
                    id = RequirementItem.deriveId(hint, stmt),
                    statement = stmt,
                    source = HintSource.USER_UTTERED,
                    hint = hint,
                    anchorRationale = "사용자 직접 발화 신규 컴포넌트 생성 요구사항",
                    verdict = Verdict.CONFIRMED, // 사용자 발화는 즉시 동결
                    confidence = ConfidenceBucket.HIGH_CONFIDENCE,
                    provenanceSignals = setOf(ProvenanceSignal.USER_UTTERANCE)
                )
                if (updatedItems.none { it.id == newItem.id }) {
                    updatedItems.add(newItem)
                }
            }
        }

        // 사용자가 완결을 선언한 경우
        if (userInput.isCompletionDeclared) {
            return Stage0TurnResult(
                state = state.copy(items = updatedItems, seedSet = newSeedTokens),
                isExhausted = true,
                isReadyForStage1 = true
            )
        }

        // 3. 확정 단위(CONFIRMED / REJECTED) 동결 보호 분리
        val frozenItems = updatedItems.filter { it.verdict != Verdict.PENDING }

        // 4. 미판정 영역 재탐색
        val newCandidates = scanner.rescanUnverified(newSeedTokens, frozenItems)

        // 5. 병합 (3.1절 mergeCandidates 규칙)
        val mergedItems = mergeCandidates(updatedItems, newCandidates)

        // 6. 소진 신호 및 5절 개방형 질문 트리거 평가
        val isExhausted = newCandidates.isEmpty()
        val openQ = if (!isExhausted) null else checkOpenQuestionTrigger(newSeedTokens, mergedItems)

        val nextState = state.copy(
            items = mergedItems,
            seedSet = newSeedTokens
        )

        return Stage0TurnResult(
            state = nextState,
            openQuestion = openQ,
            isExhausted = isExhausted,
            isReadyForStage1 = false
        )
    }

    /**
     * 3.1절: 동일성 유지와 동결 보호 병합 (mergeCandidates)
     * - id로 식별하여 CONFIRMED / REJECTED는 재탐색 결과로 절대 덮어쓰지 않음
     * - 기존 PENDING은 최신 재탐색 결과로 갱신 가능
     */
    private fun mergeCandidates(
        currentItems: List<RequirementItem>,
        newCandidates: List<RequirementItem>
    ): List<RequirementItem> {
        val resultMap = mutableMapOf<String, RequirementItem>()

        // 1) 기존 동결된 아이템 우선 보존
        for (item in currentItems) {
            if (item.verdict != Verdict.PENDING) {
                resultMap[item.id] = item
            }
        }

        // 2) 신규 재탐색 결과 추가 (동결된 ID는 건드리지 않음)
        for (cand in newCandidates) {
            if (!resultMap.containsKey(cand.id)) {
                resultMap[cand.id] = cand
            }
        }

        return resultMap.values.toList()
    }

    /**
     * 5절: 개방형 질문 트리거 (shouldAskOpenQuestion)
     * 도메인 개념 토큰은 감지되었으나, 이를 담당할 구조가 그래프에서 미발견일 때 발동
     */
    private fun checkOpenQuestionTrigger(
        seedTokens: Set<SeedToken>,
        items: List<RequirementItem>
    ): String? {
        val conceptualTokens = seedTokens.filter { it.kind == TokenKind.CONCEPTUAL }
        val coveredPaths = items.mapNotNull { (it.hint as? LinkHint.ExistingRef)?.filePath }

        for (ct in conceptualTokens) {
            val tokenVal = ct.value.lowercase()
            // 외부 API, 브랜드메시지 등 그래프에서 담당 컴포넌트가 부족한 개념 감지
            val isCoveredInGraph = coveredPaths.any { it.contains(tokenVal, ignoreCase = true) } ||
                                   graph.files.values.any { it.className.contains(tokenVal, ignoreCase = true) }

            if (!isCoveredInGraph && (tokenVal.contains("브랜드") || tokenVal.contains("brand") || tokenVal.contains("api") || tokenVal.contains("연동"))) {
                return "요구사항의 '${ct.value}' 관련 기능은 외부 연동 API(예: Bizgo 등) 또는 별도 신규 모듈을 통해 처리됩니까? 그렇다면 세부 연동 방식을 알려주세요."
            }
        }
        return null
    }

    /**
     * 6절: Stage 1로의 전이 산출물 생성
     */
    fun transitionToStage1(state: Stage0State): Stage0TransitionContract {
        val confirmed = state.items.filter { it.verdict == Verdict.CONFIRMED }
        val trustedExistingRefs = confirmed.mapNotNull { it.hint as? LinkHint.ExistingRef }
        val newCreations = confirmed.filter { it.hint is LinkHint.NewCreation }

        val statementsSummary = confirmed.joinToString("\n") { "- ${it.statement}" }
        val enrichedText = buildString {
            appendLine(state.originalRequirement)
            if (statementsSummary.isNotBlank()) {
                appendLine()
                appendLine("[확정된 세부 구현 범위]")
                appendLine(statementsSummary)
            }
        }.trim()

        return Stage0TransitionContract(
            confirmedItems = confirmed,
            trustedExistingRefs = trustedExistingRefs,
            newCreations = newCreations,
            enrichedRequirementText = enrichedText
        )
    }
}
