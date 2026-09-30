package net.ib.ixpert.ops.wuwagent.agent.clarify

import net.ib.ixpert.ops.wuwagent.agent.clarify.model.*
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DomainDictionary
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FileNode
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

    data class UserUtteranceResolution(
        val confirmedItems: List<RequirementItem>,
        val newCreationItem: RequirementItem?,
        val unconfirmedQuestion: String?,
        val unmatchedIdentifiers: List<String>
    )

    /**
     * [B-12] 사용자 발화 내 식별자 3분기 분석 및 자동 확정 / 확인 질의 결정
     * 1) 사용자 발화 O + 그래프 실재 O: 즉시 USER_UTTERED / CONFIRMED 확정 (재질의 0건)
     * 2) 사용자 발화 O + 그래프 실재 X: 신규 파일 생성 여부 1회 확인 질의 생성 및 NewCreation 보존
     * 3) 사용자 발화 X 인접 식별자: PENDING 유지 (rescanUnverified에서 처리)
     */
    fun resolveUserUtteredIdentifiers(
        text: String,
        existingConstraints: List<IntentConstraint>? = null
    ): UserUtteranceResolution {
        if (text.isBlank()) {
            return UserUtteranceResolution(emptyList(), null, null, emptyList())
        }

        // EXCLUDE_COMPONENT 제약의 evidence 범위에 포함된 식별자는 분기 1(CONFIRMED)에서 제외
        // evidence가 null인 경우 근거 없음으로 처리하여 배제 문맥에 무분별하게 포함시키지 않음
        val constraints = existingConstraints ?: extractConstraints(listOf(text))
        val excludeEvidences = constraints
            .filter { it.kind == ConstraintKind.EXCLUDE_COMPONENT }
            .mapNotNull { it.evidence }

        val newModuleEvidences = constraints
            .filter { it.kind == ConstraintKind.NEW_MODULE }
            .mapNotNull { it.evidence }

        val extractedIds = IdentifierRetentionChecker.extractIdentifiers(listOf(text))
        val targetIds = extractedIds.filter { idToken ->
            excludeEvidences.none { ev -> 
                IdentifierRetentionChecker.containsIdentifier(ev, idToken)
            }
        }

        val isLlmAvailable = llmClient != null
        val matchedVerdict = if (isLlmAvailable) Verdict.CONFIRMED else Verdict.PENDING

        val confirmedList = mutableListOf<RequirementItem>()
        val matchedTokens = mutableSetOf<String>()

        for (idToken in targetIds) {
            val baseName = idToken.substringBeforeLast(".")

            // 1. files 검색 (클래스명 및 파일명 완전/경계 일치)
            val matchedFile = graph.files.values.find { fileNode ->
                val fName = fileNode.path.substringAfterLast("/")
                val fBase = fName.substringBeforeLast(".")
                fileNode.className.equals(idToken, ignoreCase = true) ||
                fileNode.className.equals(baseName, ignoreCase = true) ||
                fName.equals(idToken, ignoreCase = true) ||
                fBase.equals(idToken, ignoreCase = true) ||
                fBase.equals(baseName, ignoreCase = true)
            }

            if (matchedFile != null) {
                val fileName = matchedFile.path.substringAfterLast("/")
                val statement = "사용자 명시 파일: $fileName ($idToken)"
                val hint = LinkHint.ExistingRef(matchedFile.path, listOf(matchedFile.className))
                val item = RequirementItem(
                    id = RequirementItem.deriveId(hint, statement),
                    statement = statement,
                    source = HintSource.USER_UTTERED,
                    hint = hint,
                    anchorRationale = "사용자 발화 명시 식별자 ($idToken)",
                    verdict = matchedVerdict,
                    confidence = ConfidenceBucket.HIGH_CONFIDENCE,
                    provenanceSignals = setOf(ProvenanceSignal.USER_UTTERANCE),
                    domainPackage = scanner.extractDomainPackage(matchedFile.path)
                )
                if (confirmedList.none { (it.hint as? LinkHint.ExistingRef)?.filePath == matchedFile.path }) {
                    confirmedList.add(item)
                }
                matchedTokens.add(idToken)
                continue
            }

            // 2. resourceNodes 검색 (JSP, JS, XML 등 리소스 파일명 완전/경계 일치)
            val matchedResource = graph.resourceNodes.find { rNode ->
                val rName = rNode.path.substringAfterLast("/")
                val rBase = rName.substringBeforeLast(".")
                rName.equals(idToken, ignoreCase = true) ||
                rBase.equals(idToken, ignoreCase = true) ||
                rBase.equals(baseName, ignoreCase = true)
            }

            if (matchedResource != null) {
                val rFileName = matchedResource.path.substringAfterLast("/")
                val statement = "사용자 명시 리소스: $rFileName ($idToken)"
                val hint = LinkHint.ExistingRef(matchedResource.path, listOf(idToken))
                val item = RequirementItem(
                    id = RequirementItem.deriveId(hint, statement),
                    statement = statement,
                    source = HintSource.USER_UTTERED,
                    hint = hint,
                    anchorRationale = "사용자 발화 명시 리소스 ($idToken)",
                    verdict = matchedVerdict,
                    confidence = ConfidenceBucket.HIGH_CONFIDENCE,
                    provenanceSignals = setOf(ProvenanceSignal.USER_UTTERANCE)
                )
                if (confirmedList.none { (it.hint as? LinkHint.ExistingRef)?.filePath == matchedResource.path }) {
                    confirmedList.add(item)
                }
                matchedTokens.add(idToken)
                continue
            }
        }

        // Branch 2: Graph에 실재하지 않는 식별자 (UpperCamelCase 또는 확장자 포함 파일명 등 독립 컴포넌트 식별자)
        val unmatched = targetIds.filter { it !in matchedTokens && (it.matches(Regex("^[A-Z][a-zA-Z0-9]+$")) || it.contains(".")) }

        // NEW_MODULE 제약의 evidence에 포함된 식별자는 명시적 신규 생성(CONFIRMED)으로 분류
        val confirmedNewModuleIds = if (isLlmAvailable) {
            unmatched.filter { idToken ->
                newModuleEvidences.any { ev ->
                    IdentifierRetentionChecker.containsIdentifier(ev, idToken)
                }
            }
        } else {
            emptyList()
        }
        val unconfirmedUnmatched = unmatched.filter { idToken ->
            idToken !in confirmedNewModuleIds
        }

        // 확인 질문은 NEW_MODULE로 확정되지 않은 미확인 식별자에 대해서만 발생
        val question = if (unconfirmedUnmatched.isNotEmpty()) {
            val names = unconfirmedUnmatched.joinToString(", ")
            "요구사항에 명시된 '$names'은(는) 프로젝트에 존재하지 않습니다. 새로 생성할 파일(신규 컴포넌트)인가요?"
        } else {
            null
        }

        // confirmedNewModuleIds가 있으면 confirmedList에 NewCreation item 추가
        for (newId in confirmedNewModuleIds) {
            val hint = LinkHint.NewCreation
            val item = RequirementItem(
                id = RequirementItem.deriveId(hint, newId),
                statement = "신규 컴포넌트 생성: $newId",
                source = HintSource.USER_UTTERED,
                hint = hint,
                anchorRationale = "사용자 직접 발화 신규 컴포넌트 생성 요구사항 ($newId)",
                verdict = Verdict.CONFIRMED,
                confidence = ConfidenceBucket.HIGH_CONFIDENCE,
                provenanceSignals = setOf(ProvenanceSignal.USER_UTTERANCE)
            )
            if (confirmedList.none { it.id == item.id }) {
                confirmedList.add(item)
            }
        }

        // unconfirmedUnmatched가 있는 경우 PENDING NewCreation item 생성 (source는 USER_UTTERED)
        val newCreationItem = when {
            unconfirmedUnmatched.isNotEmpty() -> {
                val names = unconfirmedUnmatched.joinToString(", ")
                val hint = LinkHint.NewCreation
                RequirementItem(
                    id = RequirementItem.deriveId(hint, names),
                    statement = "미확인 부존재 식별자: $names",
                    source = HintSource.USER_UTTERED,
                    hint = hint,
                    anchorRationale = "사용자 발화 미확인 부존재 식별자 ($names)",
                    verdict = Verdict.PENDING, // 사용자 확인 전까지는 미해결(PENDING) 유지
                    confidence = ConfidenceBucket.HIGH_CONFIDENCE,
                    provenanceSignals = setOf(ProvenanceSignal.USER_UTTERANCE)
                )
            }
            unmatched.isEmpty() && confirmedNewModuleIds.isEmpty() && matchedTokens.isEmpty() && text.isNotBlank() -> {
                // 식별자는 없지만 사용자 직접 발화 요구사항인 경우
                val hint = LinkHint.NewCreation
                RequirementItem(
                    id = RequirementItem.deriveId(hint, text),
                    statement = text,
                    source = HintSource.USER_UTTERED,
                    hint = hint,
                    anchorRationale = "사용자 직접 발화 신규 컴포넌트/개념 요구사항",
                    verdict = Verdict.CONFIRMED,
                    confidence = ConfidenceBucket.HIGH_CONFIDENCE,
                    provenanceSignals = setOf(ProvenanceSignal.USER_UTTERANCE)
                )
            }
            else -> null
        }

        return UserUtteranceResolution(
            confirmedItems = confirmedList,
            newCreationItem = newCreationItem,
            unconfirmedQuestion = question,
            unmatchedIdentifiers = unmatched
        )
    }

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

        // B-12: 사용자 초기 발화 식별자 3분기 분해
        val userResolution = resolveUserUtteredIdentifiers(originalRequirement)
        val userConfirmedItems = userResolution.confirmedItems.filter { it.verdict == Verdict.CONFIRMED }
        val userPendingItems = userResolution.confirmedItems.filter { it.verdict == Verdict.PENDING } + 
            (userResolution.newCreationItem?.takeIf { it.verdict == Verdict.PENDING }?.let { listOf(it) } ?: emptyList())
        val userConfirmedPaths = userConfirmedItems.mapNotNull { (it.hint as? LinkHint.ExistingRef)?.filePath }.toSet()
        val userPendingPaths = userPendingItems.mapNotNull { (it.hint as? LinkHint.ExistingRef)?.filePath }.toSet()

        val initialCandidates = scanner.rescanUnverified(initialTokens, frozenRejectedItems)
            .filter { cand ->
                val p = (cand.hint as? LinkHint.ExistingRef)?.filePath
                p == null || (p !in userConfirmedPaths && p !in userPendingPaths)
            }
        val openQ = userResolution.unconfirmedQuestion ?: checkOpenQuestionTrigger(initialTokens, initialCandidates)

        // 초기 items = 사용자 확정 항목 + 사용자 미확인(PENDING) 항목 + 새로 발견된 후보군 + 이전 세션 거부 동결 항목
        val combinedItems = userConfirmedItems + userPendingItems + initialCandidates + frozenRejectedItems

        val state = Stage0State(
            originalRequirement = originalRequirement,
            items = combinedItems,
            seedSet = initialTokens
        )

        val taskSummary = generateTaskSummary(originalRequirement, combinedItems)

        return Stage0TurnResult(
            state = state,
            openQuestion = openQ,
            isExhausted = initialCandidates.isEmpty() && userConfirmedItems.isEmpty(),
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
        var exclusionQuestion: String? = null
        var unmatchedQuestion: String? = null
        var echoMsg: String? = null

        if (!userInput.userStatement.isNullOrBlank()) {
            val stmt = userInput.userStatement.trim()
            val lowerStmt = stmt.lowercase()

            // 2-0. 이전 턴에서 제안된 배제 후보(PROPOSED_EXCLUSION)에 대한 긍정/부정 답변 확인
            val pendingExclusions = updatedItems.filter { it.source == HintSource.PROPOSED_EXCLUSION && it.verdict == Verdict.PENDING }
            if (pendingExclusions.isNotEmpty()) {
                val isAffirmative = listOf("응", "네", "맞아", "제외해", "제외해줘", "빼줘", "yes", "y", "확인", "그래", "오케이", "ok", "제외").any { lowerStmt.contains(it) }
                val isNegative = listOf("아니", "아니요", "포함해", "건드려", "no", "n", "취소", "유지").any { lowerStmt.contains(it) }

                if (isAffirmative) {
                    // 모든 토큰이 정확히 번역/매칭된 고신뢰(HIGH_CONFIDENCE) 후보만 "응"으로 일괄 제외 확정
                    val highConfidenceExclusions = pendingExclusions.filter { it.confidence == ConfidenceBucket.HIGH_CONFIDENCE }
                    if (highConfidenceExclusions.isNotEmpty()) {
                        for (i in updatedItems.indices) {
                            if (updatedItems[i].source == HintSource.PROPOSED_EXCLUSION && 
                                updatedItems[i].verdict == Verdict.PENDING &&
                                updatedItems[i].confidence == ConfidenceBucket.HIGH_CONFIDENCE) {
                                updatedItems[i] = updatedItems[i].copy(
                                    verdict = Verdict.REJECTED,
                                    source = HintSource.USER_CONFIRMED,
                                    rejectionReason = RejectionReason.FILE_MISMATCH
                                )
                            }
                        }
                        echoMsg = "배제 요청하신 파일 목록을 분석 대상에서 제외(REJECTED) 확정했습니다."
                    } else {
                        // 부분 번역(LOW_CONFIDENCE)인 경우 일반 "응"으로 일괄 제외하지 않고 세부 선택 요구
                        echoMsg = "모호한 배제 후보가 포함되어 있어 일괄 제외되지 않았습니다. 특정 파일을 지정하거나 체크박스로 선택해 주세요."
                    }
                } else if (isNegative) {
                    // "아니요"/취소 시: 제안된 배제 후보 항목을 state.items에서 완전히 제거하여 계약(CONFIRMED/REJECTED)에 0 흔적 보장
                    for (i in updatedItems.indices.reversed()) {
                        if (updatedItems[i].source == HintSource.PROPOSED_EXCLUSION && updatedItems[i].verdict == Verdict.PENDING) {
                            updatedItems.removeAt(i)
                        }
                    }
                    echoMsg = "배제 요청을 취소하고 분석 대상을 그대로 유지합니다."
                }
            }

            // 2-1. 발화로부터 EXCLUDE_COMPONENT 제약 추출 및 배제 후보 탐색
            val currentConstraints = extractConstraints(listOf(stmt))
            val excludeConstraints = currentConstraints.filter { it.kind == ConstraintKind.EXCLUDE_COMPONENT }
            for (ec in excludeConstraints) {
                val evidence = ec.evidence ?: stmt
                val resolution = resolveExclusionCandidates(evidence)
                val confidence = if (resolution.isAllTokensTranslated) ConfidenceBucket.HIGH_CONFIDENCE else ConfidenceBucket.LOW_CONFIDENCE

                for (file in resolution.candidates) {
                    val fileName = file.path.substringAfterLast("/")
                    val hint = LinkHint.ExistingRef(file.path, listOf(file.className))
                    val item = RequirementItem(
                        id = RequirementItem.deriveId(hint, "exclude:${file.path}"),
                        statement = "배제 후보: $fileName (${ec.value})",
                        source = HintSource.PROPOSED_EXCLUSION,
                        hint = hint,
                        anchorRationale = "사용자 배제 발화('${evidence}') 매칭 제외 후보",
                        verdict = Verdict.PENDING,
                        confidence = confidence,
                        provenanceSignals = setOf(ProvenanceSignal.USER_UTTERANCE)
                    )
                    val existingIndex = updatedItems.indexOfFirst { 
                        it.id == item.id || (it.hint as? LinkHint.ExistingRef)?.filePath == file.path 
                    }
                    if (existingIndex >= 0) {
                        if (updatedItems[existingIndex].verdict == Verdict.PENDING) {
                            updatedItems[existingIndex] = item
                        }
                    } else {
                        updatedItems.add(item)
                    }
                }
                if (resolution.question != null) {
                    exclusionQuestion = resolution.question
                }
            }

            val utteredTokens = scanner.extractTokens(stmt, emptySet())
            newSeedTokens = state.seedSet + utteredTokens

            // B-12: 사용자 추가 발화 식별자 3분기 분해 (추출된 constraints 재사용으로 중복 LLM 호출 차단)
            val userResolution = resolveUserUtteredIdentifiers(stmt, currentConstraints)
            for (item in userResolution.confirmedItems) {
                val itemPath = (item.hint as? LinkHint.ExistingRef)?.filePath
                val existingIndex = if (itemPath != null) {
                    updatedItems.indexOfFirst { (it.hint as? LinkHint.ExistingRef)?.filePath == itemPath }
                } else {
                    updatedItems.indexOfFirst { it.id == item.id }
                }
                if (existingIndex >= 0) {
                    updatedItems[existingIndex] = item
                } else {
                    updatedItems.add(item)
                }
            }
            userResolution.newCreationItem?.let { newItem ->
                if (updatedItems.none { it.id == newItem.id }) {
                    updatedItems.add(newItem)
                }
            }
            if (userResolution.unconfirmedQuestion != null) {
                unmatchedQuestion = userResolution.unconfirmedQuestion
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
                isReadyForStage1 = true,
                echoBackMessage = echoMsg
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
        val openQ = exclusionQuestion ?: unmatchedQuestion ?: if (!isExhausted) null else checkOpenQuestionTrigger(newSeedTokens, mergedItems)

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
            taskSummary = taskSummary,
            echoBackMessage = echoMsg
        )
    }

    /**
     * 배제 후보 탐색 및 대화 질문 생성 결과
     */
    data class ExclusionCandidateResolution(
        val evidence: String,
        val candidates: List<FileNode>,
        val isAllTokensTranslated: Boolean,
        val question: String?
    )

    /**
     * 한글 evidence로부터 도메인 사전 번역 및 메타데이터 매칭을 시도하고 3가지 상황(완전 매칭, 부분 매칭, 매칭 실패)에 맞추어 배제 후보 및 질문 생성.
     * - Case A (모든 토큰 매칭 성공): All-Token Matching 후보 도출 및 일괄 확인 질문 생성
     * - Case B (일부 토큰만 매칭 성공): 매칭된 토큰 기반 광범위 후보 도출 및 항목별 선택 질문 생성 (일반 '응'으로 일괄 제외 방지)
     * - Case C (매칭 실패): 추측하지 않고 클래스/패키지명 직접 질의 질문 생성
     */
    fun resolveExclusionCandidates(evidence: String): ExclusionCandidateResolution {
        val dict = DomainDictionary.load(graph)
        val stopwords = setOf(
            "는", "은", "이", "가", "을", "를", "에", "도", "말고", "만", "건드리지", "마라", "마",
            "제외", "제외해", "제외해줘", "수정", "하지", "않", "제외하고", "말아줘", "말고는", "빼고",
            "안", "함", "수정할", "거야", "할거야", "위주로", "파일", "클래스", "관련", "쪽"
        )
        val rawTokens = Regex("[가-힣A-Za-z0-9_]+").findAll(evidence).map { it.value }.toList()
        val particles = listOf("에서", "으로", "까지", "부터", "하고", "에는", "는", "은", "이", "가", "을", "를", "에", "도", "만", "의", "로")
        val words = rawTokens.map { raw ->
            var cleaned = raw
            for (p in particles) {
                if (cleaned.endsWith(p) && cleaned.length > p.length + 1) {
                    cleaned = cleaned.removeSuffix(p)
                    break
                }
            }
            cleaned
        }.filter { it.length >= 2 && it !in stopwords }.distinct()

        if (words.isEmpty()) {
            return ExclusionCandidateResolution(evidence, emptyList(), false, null)
        }

        // 각 단어별로 매칭되는 그래프 파일 집합 탐색 (메타데이터 localName/주석 또는 도메인 사전 번역 토큰 매칭)
        fun findFilesForWord(word: String): Set<FileNode> {
            val translations = dict.translate(word)
            return graph.files.values.filter { fileNode ->
                val matchesKorean = (fileNode.localName?.contains(word, ignoreCase = true) == true) ||
                        fileNode.koreanComments.any { it.contains(word, ignoreCase = true) }
                val fileTokens = (DomainDictionary.tokenizeCamelCase(fileNode.className) +
                        fileNode.path.split('/', '.', '_').map { it.lowercase() }
                ).toSet()
                val matchesTranslation = translations.isNotEmpty() && translations.any { trans ->
                    fileTokens.contains(trans.lowercase())
                }
                matchesKorean || matchesTranslation
            }.toSet()
        }

        val wordToMatchedFiles = words.associateWith { findFilesForWord(it) }
        val resolvedWords = wordToMatchedFiles.filter { it.value.isNotEmpty() }
        val unresolvedWords = wordToMatchedFiles.filter { it.value.isEmpty() }.keys.toList()

        // Case C: 어떤 단어도 그래프 파일/사전과 매칭되지 않은 경우
        if (resolvedWords.isEmpty()) {
            val question = "배제 요청하신 '${evidence}'에 해당하는 클래스나 패키지명을 알려주세요."
            return ExclusionCandidateResolution(evidence, emptyList(), false, question)
        }

        // Case A: 모든 단어가 매칭되고, 교집합(All-Token Match)이 존재하는 경우
        if (unresolvedWords.isEmpty()) {
            var intersection = resolvedWords.values.first()
            for (fileSet in resolvedWords.values.drop(1)) {
                intersection = intersection.intersect(fileSet)
            }
            val candidates = intersection.toList()

            val question = if (candidates.isNotEmpty()) {
                "배제 요청하신 '${evidence}' 관련 파일(${candidates.joinToString { it.className + ".java" }})을 분석 대상에서 제외할까요?"
            } else {
                "배제 요청하신 '${evidence}' 관련 파일을 그래프에서 찾지 못했습니다. 관련 클래스나 패키지명을 알려주세요."
            }
            return ExclusionCandidateResolution(evidence, candidates, true, question)
        }

        // Case B: 일부 단어만 매칭된 경우 (Partial Translation/Resolution)
        // 매칭된 단어들의 파일 합집합을 후보로 제시하고, 미매칭 단어에 대해 세부 선택 요구
        val unionCandidates = resolvedWords.values.flatten().distinct()
        val transNames = resolvedWords.keys.joinToString(", ")
        val untransNames = unresolvedWords.joinToString(", ")
        val candidateNames = unionCandidates.take(5).joinToString(", ") { it.className + ".java" } + (if (unionCandidates.size > 5) " 등" else "")

        val question = "배제 요청하신 '${evidence}' 중 '${untransNames}'에 해당하는 정확한 클래스를 특정하지 못했습니다. 다음 '${transNames}' 관련 후보($candidateNames) 중 제외할 대상을 선택하시거나, 정확한 클래스/패키지명을 알려주세요."

        return ExclusionCandidateResolution(evidence, unionCandidates, false, question)
    }

    fun findExclusionCandidates(evidence: String): List<FileNode> {
        return resolveExclusionCandidates(evidence).candidates
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
        val frozenPaths = mutableSetOf<String>()

        // 1) 기존 동결된 아이템 우선 보존
        for (item in currentItems) {
            if (item.verdict != Verdict.PENDING) {
                resultMap[item.id] = item
                (item.hint as? LinkHint.ExistingRef)?.filePath?.let { frozenPaths.add(it) }
            }
        }

        // 2) 신규 재탐색 결과 추가 (동결된 ID 및 동결된 filePath는 건드리지 않음)
        for (cand in newCandidates) {
            val candPath = (cand.hint as? LinkHint.ExistingRef)?.filePath
            if (!resultMap.containsKey(cand.id) && (candPath == null || !frozenPaths.contains(candPath))) {
                resultMap[cand.id] = cand
            }
        }

        // 3) 기존 미판정 아이템(배제 제안 등) 중 아직 resultMap에 없는 항목 보존
        for (item in currentItems) {
            val itemPath = (item.hint as? LinkHint.ExistingRef)?.filePath
            if (!resultMap.containsKey(item.id) && (itemPath == null || !frozenPaths.contains(itemPath))) {
                resultMap[item.id] = item
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
        val rawRefined = refineRequirement(state.originalRequirement, state.userStatements, constraints)
        val retentionResult = IdentifierRetentionChecker.checkRetention(
            refinedRequirement = rawRefined,
            originalRequirement = state.originalRequirement,
            userStatements = state.userStatements
        )
        val refinedRequirement = retentionResult.effectiveRefinedRequirement
        val excludedFiles = state.items
            .filter { it.verdict == Verdict.REJECTED }
            .mapNotNull { (it.hint as? LinkHint.ExistingRef)?.filePath }
            .distinct()

        val allStatements = listOf(state.originalRequirement) + state.userStatements
        val classNames = graph.files.values.map { it.className }
        val auxiliaryIdentifiers = IdentifierRetentionChecker.extractAuxiliaryIdentifiers(
            texts = allStatements,
            classNames = classNames,
            knownExpected = retentionResult.expectedIdentifiers
        )

        return ClarifyIntent(
            originalRequirement = state.originalRequirement,
            refinedRequirement = refinedRequirement,
            userStatements = state.userStatements,
            anchorTokens = anchorTokens,
            constraints = constraints,
            excludedFiles = excludedFiles,
            graphHash = graphHash,
            retentionAudit = RetentionAudit(
                rawRefinedRequirement = retentionResult.rawRefinedRequirement,
                expectedIdentifiers = retentionResult.expectedIdentifiers.toList().sorted(),
                missingBeforeFix = retentionResult.missingIdentifiers,
                auxiliaryIdentifiers = auxiliaryIdentifiers,
                wasRetainedWithoutModification = retentionResult.wasRetainedWithoutModification,
                scopeModifierDropped = retentionResult.scopeModifierDropped
            ),
            contractVersion = "1.1"
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
                2. 사용자가 언급한 특정 클래스/파일명(예: OrderServiceImpl, SAPACMM0802S01, selTrcdIsInf 등) 및 식별자는 누락 없이 온전히 포함하세요.
                3. 사용자가 배타적 한정 표현('~만 수정', '~단독 수정')을 사용한 경우 이를 흐리거나 일반화('~내에서', '~를 수정')하지 말고 'OrderServiceImpl만 수정'과 같이 '만'을 분명하게 유지하세요. (단, '~위주로'와 같은 우선순위 표현은 배타적 한정으로 왜곡하지 말고 원문 맥락을 유지하세요.)
                4. 불필요한 서론/결론/인사말 없이 오직 정제된 요구사항 본문만 출력하세요.
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
            IntentConstraint(
                kind = ConstraintKind.OTHER,
                value = stmt,
                rawStatement = stmt,
                evidence = null
            )
        }

        val client = llmClient ?: return defaultConstraints

        return try {
            val systemPrompt = """
                당신은 요구사항 분석 전문가입니다. 사용자의 발화 목록에서 시스템 분석 및 파일 필터링에 사용할 제약 조건(Constraints)을 분류하세요.

                [ConstraintKind 분류 기준]
                - INCLUDE_CHANNEL: 특정 발송 채널/경로 포함 요구 (예: 모바일 푸시 알림 활용, SMS 발송 등)
                - EXCLUDE_COMPONENT: 특정 컴포넌트/배치/기능 수정 제외 요구 (예: 정산 배치는 건드리지 마라 등)
                - EXCLUDE_EXTERNAL: 외부 연동/외부 API 배제 요구 (예: 외부 결제 연동 미사용, 내부 DB만 사용 등)
                - NEW_MODULE: 신규 모듈/컴포넌트 생성 필요 (사용자가 신규 개발/생성을 명시적으로 요구한 경우만)
                - SCOPE_LIMIT: 특정 파일/클래스에 대한 배타적 수정 범위 한정 (예: OrderServiceImpl만 수정 등)
                - OTHER: 위 항목으로 명확히 분류되지 않는 일반 제약/발화

                [분류 원칙]
                1. 모든 분류는 사용자의 발화에 명시적으로 표현된 내용에만 근거해야 하며, 언급되지 않은 의도를 임의로 추측하거나 과도하게 분류하지 마세요.
                2. 하나의 발화에 배제와 한정이 공존하는 경우(예: '주문 배치는 두고 OrderController만 고쳐 줘')에는 반드시 각각 EXCLUDE_COMPONENT와 SCOPE_LIMIT 2개의 객체로 분리하여 추출하세요.
                3. 각 제약의 근거가 되는 사용자 발화 속 정확한 단어/구문(evidence)을 반드시 기재하세요.

                [출력 형식]
                반드시 아래 JSON 배열 형식으로만 응답하세요:
                [
                  {
                    "kind": "INCLUDE_CHANNEL | EXCLUDE_COMPONENT | EXCLUDE_EXTERNAL | NEW_MODULE | SCOPE_LIMIT | OTHER",
                    "value": "제약의 핵심 내용 요약",
                    "rawStatement": "원본 발화 문장",
                    "evidence": "해당 제약의 근거가 되는 사용자 발화 속 정확한 단어/구문"
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

            val combinedUserText = userStatements.joinToString(" ")

            parsedList.mapIndexed { index, map ->
                val rawKindStr = map["kind"]?.trim()?.uppercase()
                var kind = try {
                    ConstraintKind.valueOf(rawKindStr ?: "OTHER")
                } catch (e: Exception) {
                    ConstraintKind.OTHER
                }
                val rawStmt = map["rawStatement"]?.takeIf { it.isNotBlank() }
                    ?: userStatements.getOrNull(index)
                    ?: map["value"]
                    ?: ""
                val value = map["value"]?.takeIf { it.isNotBlank() } ?: rawStmt
                val evidence = map["evidence"]?.trim()?.takeIf { it.isNotBlank() }

                // 사후 결정론적 환각 가드 (Hallucination Guard)
                // evidence가 누락되었거나 사용자 발화에 전혀 존재하지 않는 가공 텍스트인 경우 OTHER로 안전 강등
                val isEvidenceGrounded = !evidence.isNullOrBlank() && combinedUserText.contains(evidence, ignoreCase = true)
                val effectiveKind = if (isEvidenceGrounded) {
                    kind
                } else {
                    ConstraintKind.OTHER
                }

                IntentConstraint(
                    kind = effectiveKind,
                    value = value,
                    rawStatement = rawStmt,
                    evidence = evidence ?: rawStmt,
                    rawKind = kind
                )
            }
        } catch (e: Exception) {
            defaultConstraints
        }
    }
}

