package net.ib.ixpert.ops.wuwagent.agent.clarify

import net.ib.ixpert.ops.wuwagent.agent.clarify.model.*
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph

/**
 * Stage 0 대화 제어 엔진 및 개방형 질문 트리거.
 * 설계서 (v1.0) 3절, 5절, 6절 준수.
 */
class Stage0ClarificationEngine(
    private val scanner: Stage0GraphScanner,
    private val graph: ProjectGraph,
    private val llmClient: net.ib.ixpert.ops.wuwagent.client.LLMClient? = null
) {
    /**
     * 사용자 입력 데이터 구조
     */
    data class UserInput(
        val verdictUpdates: Map<String, Verdict> = emptyMap(), // id -> CONFIRMED / REJECTED
        val rejectionReasonUpdates: Map<String, RejectionReason> = emptyMap(), // id -> FILE_MISMATCH / CONCEPT_IRRELEVANT
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
        val isReadyForStage1: Boolean = false,
        val taskSummary: String? = null,
        val echoBackMessage: String? = null
    )

    /**
     * 초기 세션 생성 (Turn 0)
     * - previousContract가 전달된 경우, 이전 세션에서 거부된 항목(rejectedNewCreations, rejectedExistingRefs)을
     *   동결 항목으로 사전 주입하여 재제안/재질문되는 것을 원천 억제합니다.
     */
    fun initSession(
        originalRequirement: String,
        previousContract: Stage0TransitionContract? = null
    ): Stage0TurnResult {
        val initialTokens = scanner.extractTokens(originalRequirement)

        // 이전 계약의 거부 항목을 frozen 리스트로 구성
        val frozenRejectedItems = mutableListOf<RequirementItem>()
        if (previousContract != null) {
            val priorRejected = if (previousContract.rejectedItems.isNotEmpty()) {
                previousContract.rejectedItems
            } else {
                val list = mutableListOf<RequirementItem>()
                for (rejRef in previousContract.rejectedExistingRefs) {
                    list.add(RequirementItem(
                        id = RequirementItem.deriveId(rejRef),
                        statement = "이전 세션에서 거부된 기존 파일: ${rejRef.filePath}",
                        source = HintSource.SYSTEM_UNCONFIRMED,
                        hint = rejRef,
                        anchorRationale = "이전 세션 REJECTED 이력 보존",
                        verdict = Verdict.REJECTED,
                        confidence = ConfidenceBucket.HIGH_CONFIDENCE,
                        domainPackage = scanner.extractDomainPackage(rejRef.filePath)
                    ))
                }
                for (rejNew in previousContract.rejectedNewCreations) {
                    list.add(rejNew.copy(verdict = Verdict.REJECTED))
                }
                list
            }

            for (rejItem in priorRejected) {
                if (rejItem.rejectionReason == RejectionReason.CONCEPT_IRRELEVANT) {
                    // CONCEPT_IRRELEVANT: 완전 억제 (재등장 불가)
                    frozenRejectedItems.add(rejItem.copy(verdict = Verdict.REJECTED, isReEmergence = false))
                } else {
                    // FILE_MISMATCH 또는 미지정: 결정론적 입력 토큰 매칭 검사
                    val isMatchedByNewTokens = when (val h = rejItem.hint) {
                        is LinkHint.ExistingRef -> {
                            val pathLower = h.filePath.lowercase().replace('\\', '/')
                            val fileName = pathLower.substringAfterLast('/')
                            val className = fileName.substringBeforeLast('.')
                            initialTokens.any { tok -> 
                                val tv = tok.value.lowercase()
                                pathLower.contains(tv) || className.contains(tv) || h.symbols.any { it.lowercase().contains(tv) }
                            }
                        }
                        is LinkHint.NewCreation -> {
                            val stmtLower = rejItem.statement.lowercase()
                            initialTokens.any { tok -> stmtLower.contains(tok.value.lowercase()) }
                        }
                    }

                    if (isMatchedByNewTokens) {
                        // 결정론적 재등장: 새 토큰 매칭 시 isReEmergence = true 및 PENDING으로 재활성화
                        frozenRejectedItems.add(rejItem.copy(
                            verdict = Verdict.PENDING,
                            isReEmergence = true,
                            anchorRationale = "${rejItem.anchorRationale} [FILE_MISMATCH 거부 후 새 문맥에서 재등장]"
                        ))
                    } else {
                        frozenRejectedItems.add(rejItem.copy(verdict = Verdict.REJECTED, isReEmergence = false))
                    }
                }
            }
        }

        val initialCandidates = scanner.rescanUnverified(initialTokens, frozenRejectedItems)
        val openQ = checkOpenQuestionTrigger(initialTokens, initialCandidates)

        // 초기 items = 새로 발견된 후보군 + 이전 세션 거부 동결 항목
        val combinedItems = initialCandidates + frozenRejectedItems

        val state = Stage0State(
            originalRequirement = originalRequirement,
            items = combinedItems,
            seedSet = initialTokens
        )

        val taskSummary = generateTaskSummary(originalRequirement, combinedItems)

        return Stage0TurnResult(
            state = state,
            openQuestion = openQ,
            isExhausted = initialCandidates.isEmpty(),
            isReadyForStage1 = false,
            taskSummary = taskSummary
        )
    }

    /**
     * 3절: 대화 턴 처리 (증분 seed 확장 + 미판정 영역 재탐색 + 확정분 동결)
     */
    fun processTurn(state: Stage0State, userInput: UserInput): Stage0TurnResult {
        // 1. 기존 items에 사용자 판정(CONFIRMED/REJECTED) 및 거부 사유 반영
        val updatedItems = state.items.map { item ->
            val update = userInput.verdictUpdates[item.id]
            val reasonUpdate = userInput.rejectionReasonUpdates[item.id]
            if (update != null && update != Verdict.PENDING) {
                item.copy(
                    verdict = update,
                    source = if (update == Verdict.CONFIRMED) HintSource.USER_CONFIRMED else item.source,
                    rejectionReason = if (update == Verdict.REJECTED) (reasonUpdate ?: item.rejectionReason ?: RejectionReason.FILE_MISMATCH) else null
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

            // Rule-3: 발화 내 단어 식별자(클래스명, 파일명, 식별자) 직접 추출 및 그래프 조회 분기 판정
            val wordTokens = Regex("[a-zA-Z0-9_.]+").findAll(stmt).map { it.value }.toList()
            val matchedExistingNodes = mutableListOf<Pair<String, String>>() // (filePath, matchedSymbol)
            val matchedUtteredTokens = mutableSetOf<String>() // 실제 매칭에 성공한 원본 발화 토큰 집합

            for (w in wordTokens) {
                val matchedFile = graph.files.values.find { 
                    it.className.equals(w, ignoreCase = true) || 
                    it.path.substringAfterLast("/").substringBeforeLast(".").equals(w, ignoreCase = true) ||
                    it.path.substringAfterLast("/").equals(w, ignoreCase = true)
                }
                if (matchedFile != null) {
                    matchedExistingNodes.add(matchedFile.path to matchedFile.className)
                    matchedUtteredTokens.add(w.lowercase())
                }
                val matchedResource = graph.resourceNodes.find { 
                    val fileName = it.path.substringAfterLast("/")
                    val fileNameWithoutExt = fileName.substringBeforeLast(".")
                    fileName.equals(w, ignoreCase = true) || fileNameWithoutExt.equals(w, ignoreCase = true)
                }
                if (matchedResource != null) {
                    // 리소스 노드(JSP/JS/XML)는 별도 클래스명이 없으므로 완전 일치한 파일명 식별자(w)를 심볼로 보존
                    matchedExistingNodes.add(matchedResource.path to w)
                    matchedUtteredTokens.add(w.lowercase())
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
            }

            // 매칭된 실재 노드들에 속한 멤버(클래스명, 파일명, 필드명, 메서드명, 리소스 메타데이터) 집합 수집
            val coveredMembers = mutableSetOf<String>()
            coveredMembers.addAll(matchedUtteredTokens)

            for ((filePath, _) in matchedExistingNodes) {
                val fileNode = graph.files[filePath]
                if (fileNode != null) {
                    coveredMembers.add(fileNode.className.lowercase())
                    fileNode.methods.forEach { coveredMembers.add(it.name.lowercase()) }
                    scanner.extractFieldsFromMethods(fileNode).forEach { coveredMembers.add(it.lowercase()) }
                }
                val resourceNode = graph.resourceNodes.find { it.path == filePath }
                if (resourceNode != null) {
                    val rFileName = resourceNode.path.substringAfterLast("/")
                    coveredMembers.add(rFileName.lowercase())
                    coveredMembers.add(rFileName.substringBeforeLast(".").lowercase())
                    val inputs = (resourceNode.metadata["input_field"] as? List<*>)?.mapNotNull { it?.toString()?.lowercase() } ?: emptyList()
                    val methods = (resourceNode.metadata["methods"] as? List<*>)?.mapNotNull { it?.toString()?.lowercase() } ?: emptyList()
                    coveredMembers.addAll(inputs)
                    coveredMembers.addAll(methods)
                }
            }

            // 발화 내 명시된 클래스형(CamelCase) 또는 확장자 포함 파일명 식별자 토큰 추출
            val utteredClassLikeTokens = wordTokens.filter { 
                it.matches(Regex("^[A-Z][a-zA-Z0-9]+$")) || (it.contains(".") && it.length > 3)
            }

            // 분기 B 판정:
            // 1) 발화 내 실재 노드가 전혀 매칭되지 않은 경우 (신규 개념/요구사항)
            // 2) 발화 내 명시된 클래스형/파일명 토큰 중 실재 노드/멤버 어디에도 매칭되지 않은 독립 신규 컴포넌트가 존재하는 경우
            val hasUnmatchedNewCreations = (matchedExistingNodes.isEmpty() && stmt.isNotBlank()) ||
                    utteredClassLikeTokens.any { it.lowercase() !in coveredMembers }

            if (hasUnmatchedNewCreations) {
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

        val updatedStatements = if (!userInput.userStatement.isNullOrBlank()) {
            state.userStatements + userInput.userStatement.trim()
        } else {
            state.userStatements
        }

        // 사용자가 완결을 선언한 경우
        if (userInput.isCompletionDeclared) {
            return Stage0TurnResult(
                state = state.copy(
                    items = updatedItems,
                    seedSet = newSeedTokens,
                    userStatements = updatedStatements
                ),
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
            seedSet = newSeedTokens,
            userStatements = updatedStatements
        )

        val taskSummary = generateTaskSummary(state.originalRequirement, mergedItems, updatedStatements)

        return Stage0TurnResult(
            state = nextState,
            openQuestion = openQ,
            isExhausted = isExhausted,
            isReadyForStage1 = false,
            taskSummary = taskSummary
        )
    }

    /**
     * 후보 목록 확정 후 사용자 친화적인 한국어 자연어 작업 요약 문장 생성 (단방향 렌더링 전용)
     * - 실패/예외 발생 시 null 반환하여 탐색 결과 렌더링에 영향 주지 않도록 완벽 격리 (폴백 보장)
     * - 후보군을 재판단/평가/필터링하지 않고 오직 요약/설명만 하도록 규율 프롬프트 적용
     * - 사용자 추가 지시/제약사항(userStatements) 및 현재 유효(REJECTED 제외) 후보군을 반영하여 매 턴 갱신
     */
    private fun generateTaskSummary(
        originalRequirement: String,
        items: List<RequirementItem>,
        userStatements: List<String> = emptyList()
    ): String? {
        val client = llmClient ?: return null
        return try {
            val systemPrompt = """
                당신은 엔터프라이즈 시스템 분석 어시스턴트입니다.
                사용자의 원 요구사항(SR), 대화로 추가된 지시/제약사항, 그리고 현재 유효한 후보 파일 목록을 바탕으로, 전체 작업의 핵심 목표와 각 컴포넌트의 변경 맥락을 간결한 한국어 자연어 문장(2~4줄)으로 요약하여 설명하세요.

                [엄격한 규율]
                1. 주어진 후보 파일 목록을 절대로 재판단, 평가, 추가, 또는 삭제하지 마십시오.
                2. 탐색된 후보들이 왜 관련되어 있으며 어떤 역할을 하는지 사용자에게 친절하게 요약/설명하는 용도로만 작성하십시오.
                3. 불필요한 인사말(예: 안녕하세요 등) 없이 요약 본문만 작성하십시오.
            """.trimIndent()

            val activeItems = items.filter { it.verdict != Verdict.REJECTED }
            val candidateDescriptions = activeItems.joinToString("\n") { item ->
                val path = (item.hint as? LinkHint.ExistingRef)?.filePath ?: item.statement
                val rationale = item.anchorRationale ?: ""
                "- $path ($rationale)"
            }

            val statementsSection = if (userStatements.isNotEmpty()) {
                "\n\n[사용자 추가 지시 및 제약사항]\n" + userStatements.joinToString("\n") { "- $it" }
            } else ""

            val userPrompt = """
                [원 요구사항]
                $originalRequirement$statementsSection

                [현재 유효 분석 대상 컴포넌트 목록]
                $candidateDescriptions
            """.trimIndent()

            val response = client.chat(
                systemPrompt = systemPrompt,
                userCode = userPrompt,
                maxTokens = 500
            )
            val content = response?.message?.content?.trim()
            if (content.isNullOrBlank() || content.startsWith("[Error]")) {
                null
            } else {
                content
            }
        } catch (e: Exception) {
            // LLM 호출 실패/타임아웃 시 후보군 반환에 영향을 주지 않도록 안전하게 null 반환
            null
        }
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
    /**
     * 6절: Stage 1로의 전이 산출물 생성
     */
    fun transitionToStage1(state: Stage0State, sessionId: String = "default", srId: String = ""): Stage0TransitionContract {
        val confirmed = state.items.filter { it.verdict == Verdict.CONFIRMED }
        val trustedExistingRefs = confirmed.mapNotNull { it.hint as? LinkHint.ExistingRef }
        val newCreations = confirmed.filter { it.hint is LinkHint.NewCreation }

        val rejectedItems = state.items.filter { it.verdict == Verdict.REJECTED }
        val rejectedExistingRefs = rejectedItems.mapNotNull { it.hint as? LinkHint.ExistingRef }
        val rejectedNewCreations = rejectedItems.filter { it.hint is LinkHint.NewCreation }

        // 신규 슬롯 제안 중 수락(CONFIRMED)된 항목들의 원본 템플릿 컴포넌트(앵커 형제들) 추출
        val anchorSiblingPaths = mutableSetOf<String>()
        for (item in confirmed) {
            val proposal = item.structuralSlotProposal ?: continue
            for (tmpl in proposal.templateComponents) {
                val matchedFile = graph.files.values.find { 
                    it.path.endsWith(tmpl) || it.className == tmpl.substringBeforeLast(".") 
                }
                if (matchedFile != null) {
                    anchorSiblingPaths.add(matchedFile.path)
                }
            }
        }
        val anchorSiblingRefs = anchorSiblingPaths.map { LinkHint.ExistingRef(filePath = it) }

        val statementsSummary = confirmed.joinToString("\n") { "- ${it.statement}" }
        val enrichedText = buildString {
            appendLine(state.originalRequirement)
            if (statementsSummary.isNotBlank()) {
                appendLine()
                appendLine("[확정된 세부 구현 범위]")
                appendLine(statementsSummary)
            }
        }.trim()

        val graphHash = ClarificationContractStore.calculateGraphHash(graph)
        val createdAt = java.time.Instant.now().toString()

        return Stage0TransitionContract(
            contractVersion = ClarificationContractStore.CURRENT_CONTRACT_VERSION,
            createdAt = createdAt,
            graphHash = graphHash,
            sessionId = sessionId,
            srId = srId,
            confirmedItems = confirmed,
            trustedExistingRefs = trustedExistingRefs,
            newCreations = newCreations,
            anchorSiblingRefs = anchorSiblingRefs,
            rejectedExistingRefs = rejectedExistingRefs,
            rejectedNewCreations = rejectedNewCreations,
            rejectedItems = rejectedItems,
            enrichedRequirementText = enrichedText
        )
    }

    /**
     * 3-2단계: 사용자 자연어 발화 처리 (번역 + 되비추기 echo-back + 상태 반영).
     * - ClarifyUtteranceTranslator를 호출하여 6대 불변식(a~f)에 따라 상태 갱신
     * - (a) EXCLUDE: 해당 targetId / targetFilePath 아이템을 REJECTED로 전환
     * - (b) INCLUDE_TOKEN: seedSet에 토큰 누적 및 rescanUnverified 재탐색 병합
     * - (c) ADD_CONSTRAINT: userStatements에 누적
     * - (d) COMPLETE: isReadyForStage1 = true 및 isExhausted = true 전이
     * - (e) NOT_IN_CANDIDATES & UNKNOWN: items No-Op (0건 훼손) 및 안내 되비추기
     * - (f) userStatements 추적 보존
     */
    fun processUtterance(state: Stage0State, utterance: String): Stage0TurnResult {
        val trimmed = utterance.trim()
        val translated = ClarifyUtteranceTranslator.translate(trimmed, state.items, llmClient)
        val updatedStatements = if (trimmed.isNotBlank()) state.userStatements + trimmed else state.userStatements

        var updatedItems = state.items
        var updatedSeedSet = state.seedSet
        var isReady = false
        val echoMsg = translated.clarificationMessage

        when (translated.kind) {
            UserActionKind.EXCLUDE -> {
                val targetId = translated.targetId
                val targetPath = translated.targetFilePath
                updatedItems = state.items.map { item ->
                    val isMatch = (targetId != null && item.id == targetId) ||
                            (targetPath != null && (item.hint as? LinkHint.ExistingRef)?.filePath == targetPath)
                    if (isMatch) {
                        item.copy(
                            verdict = Verdict.REJECTED,
                            source = HintSource.USER_CONFIRMED,
                            rejectionReason = RejectionReason.FILE_MISMATCH
                        )
                    } else {
                        item
                    }
                }
            }
            UserActionKind.INCLUDE_TOKEN -> {
                val tokenVal = translated.tokenValue ?: trimmed
                if (tokenVal.isNotBlank()) {
                    val extracted = scanner.extractTokens(tokenVal, emptySet())
                    val tokenDirect = SeedToken(tokenVal, TokenKind.CONCEPTUAL)
                    val newSeedTokens = state.seedSet + extracted + tokenDirect
                    updatedSeedSet = newSeedTokens
                    val frozenItems = state.items.filter { it.verdict != Verdict.PENDING }
                    val newCandidates = scanner.rescanUnverified(updatedSeedSet, frozenItems)
                    updatedItems = mergeCandidates(state.items, newCandidates)
                }
            }
            UserActionKind.ADD_CONSTRAINT -> {
                // 제약 조건은 updatedStatements에 누적되어 buildClarifyIntent 시 IntentConstraint로 자동 수렴됨
            }
            UserActionKind.COMPLETE -> {
                isReady = true
            }
            UserActionKind.UNKNOWN -> {
                // Invariant (e): No-Op on items
            }
        }

        val newDialogueHistory = state.dialogueHistory.toMutableList()
        if (trimmed.isNotBlank()) {
            newDialogueHistory.add(DialogueHistoryItem(role = "user", content = trimmed))
        }
        if (!echoMsg.isNullOrBlank()) {
            newDialogueHistory.add(DialogueHistoryItem(role = "assistant", content = echoMsg))
        }

        val nextState = state.copy(
            items = updatedItems,
            seedSet = updatedSeedSet,
            userStatements = updatedStatements,
            dialogueHistory = newDialogueHistory
        )

        val isExhausted = isReady || (translated.kind == UserActionKind.INCLUDE_TOKEN && updatedItems.none { it.verdict == Verdict.PENDING })
        val openQ = if (isReady) null else checkOpenQuestionTrigger(updatedSeedSet, updatedItems)
        val taskSummary = generateTaskSummary(state.originalRequirement, updatedItems, updatedStatements)

        return Stage0TurnResult(
            state = nextState,
            openQuestion = openQ,
            isExhausted = isExhausted,
            isReadyForStage1 = isReady,
            taskSummary = taskSummary,
            echoBackMessage = echoMsg
        )
    }

    /**
     * Clarify 대화 상태로부터 구조화된 인텐트 계약(ClarifyIntent) 생성.
     * - 대화 표면(로그, 파일 목록, taskSummary)은 완전히 배제하고 오직 구조화된 의도만 전송.
     * - 대화 중 REJECTED된 파일 목록을 excludedFiles(1급 결정)로 추출하여 analyze에 원천 배제 인계.
     * - LLM 실패 시 originalRequirement 및 ConstraintKind.OTHER + rawStatement로 완전 격리 폴백 보장.
     */
    fun buildClarifyIntent(state: Stage0State): ClarifyIntent {
        val graphHash = ClarificationContractStore.calculateGraphHash(graph)
        val anchorTokens = state.seedSet.map { it.value }.distinct()
        val constraints = extractConstraints(state.userStatements)
        val refinedRequirement = refineRequirement(state.originalRequirement, state.userStatements, constraints)
        val excludedFiles = state.items
            .filter { it.verdict == Verdict.REJECTED }
            .mapNotNull { (it.hint as? LinkHint.ExistingRef)?.filePath }
            .distinct()

        return ClarifyIntent(
            originalRequirement = state.originalRequirement,
            refinedRequirement = refinedRequirement,
            anchorTokens = anchorTokens,
            constraints = constraints,
            excludedFiles = excludedFiles,
            graphHash = graphHash,
            contractVersion = "1.0"
        )
    }

    private fun refineRequirement(
        original: String,
        userStatements: List<String>,
        constraints: List<IntentConstraint>
    ): String {
        if (userStatements.isEmpty()) return original
        val client = llmClient ?: return original
        return try {
            val systemPrompt = """
                당신은 소프트웨어 요구사항 정제 전문가입니다.
                최초 요구사항과 대화 중 사용자가 제공한 추가 발화/답변을 종합하여, 명확하고 구체화된 단일 요구사항 정제문(1~2문장)을 작성하세요.

                [규율]
                1. 원 요구사항의 핵심 목표와 사용자의 세부 의도/제약사항을 자연스럽게 결합하세요.
                2. 불필요한 서론/결론/인사말 없이 오직 정제된 요구사항 본문만 출력하세요.
            """.trimIndent()

            val userPrompt = """
                [최초 요구사항]
                $original

                [사용자 추가 발화 및 답변]
                ${userStatements.joinToString("\n") { "- $it" }}
            """.trimIndent()

            val response = client.chat(
                systemPrompt = systemPrompt,
                userCode = userPrompt,
                maxTokens = 300
            )
            val content = response?.message?.content?.trim()
            if (content.isNullOrBlank() || content.startsWith("[Error]")) {
                original
            } else {
                content
            }
        } catch (e: Exception) {
            original
        }
    }

    private fun extractConstraints(userStatements: List<String>): List<IntentConstraint> {
        if (userStatements.isEmpty()) return emptyList()

        val defaultConstraints = userStatements.map { stmt ->
            IntentConstraint(kind = ConstraintKind.OTHER, value = stmt, rawStatement = stmt)
        }

        val client = llmClient ?: return defaultConstraints

        return try {
            val systemPrompt = """
                당신은 요구사항 분석 전문가입니다. 사용자의 발화 목록에서 시스템 분석 및 파일 필터링에 사용할 제약 조건(Constraints)을 분류하세요.

                [ConstraintKind 분류 기준]
                - INCLUDE_CHANNEL: 특정 발송 채널/경로 포함 (예: 알림톡 채널 활용, SMS 발송 등)
                - EXCLUDE_EXTERNAL: 외부 연동/외부 API 배제 (예: 외부 API 연동 안 함, 내부 DB만 사용 등)
                - NEW_MODULE: 신규 모듈/컴포넌트 개발 필요 (예: Bizgo 연동 모듈 신규 개발 등)
                - SCOPE_LIMIT: 변경 범위 한정 (예: 어드민 화면만 수정, 발송 로직만 수정 등)
                - OTHER: 위 항목으로 명확히 분류되지 않는 일반 제약/발화

                [출력 형식]
                반드시 아래 JSON 배열 형식으로만 응답하세요:
                [
                  {
                    "kind": "INCLUDE_CHANNEL | EXCLUDE_EXTERNAL | NEW_MODULE | SCOPE_LIMIT | OTHER",
                    "value": "제약의 핵심 내용 요약",
                    "rawStatement": "원본 발화 문장"
                  }
                ]
            """.trimIndent()

            val userPrompt = userStatements.joinToString("\n") { "- $it" }

            val response = client.chat(
                systemPrompt = systemPrompt,
                userCode = userPrompt,
                maxTokens = 500
            )
            val content = response?.message?.content?.trim()
            if (content.isNullOrBlank() || content.startsWith("[Error]")) {
                return defaultConstraints
            }

            // JSON 파싱 시도 (코드 블록 방어)
            val cleanJson = if (content.contains("```json")) {
                content.substringAfter("```json").substringBefore("```").trim()
            } else if (content.contains("```")) {
                content.substringAfter("```").substringBefore("```").trim()
            } else {
                content
            }

            val listType = object : com.google.gson.reflect.TypeToken<List<Map<String, String>>>() {}.type
            val parsedList: List<Map<String, String>>? = com.google.gson.Gson().fromJson(cleanJson, listType)
            if (parsedList == null || parsedList.isEmpty()) {
                return defaultConstraints
            }

            parsedList.mapIndexed { index, map ->
                val kindStr = map["kind"]?.trim()?.uppercase()
                val kind = try {
                    ConstraintKind.valueOf(kindStr ?: "OTHER")
                } catch (e: Exception) {
                    ConstraintKind.OTHER
                }
                val rawStmt = map["rawStatement"]?.takeIf { it.isNotBlank() }
                    ?: userStatements.getOrNull(index)
                    ?: map["value"]
                    ?: ""
                val value = map["value"]?.takeIf { it.isNotBlank() } ?: rawStmt

                IntentConstraint(
                    kind = kind,
                    value = value,
                    rawStatement = rawStmt
                )
            }
        } catch (e: Exception) {
            defaultConstraints
        }
    }
}

