package net.ib.ixpert.ops.wuwagent.agent.clarify

import net.ib.ixpert.ops.wuwagent.agent.analyze.search.KeywordDecomposer
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.*
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DomainDictionary
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*

/**
 * Stage 0 미판정 영역 재탐색 4단 병렬 파이프라인.
 * 설계서 (v1.0) 및 형제 유추 스펙 (v1.1) 준수.
 * 
 * 1단: 토큰 추출 (1차 구조 식별자 + 2차 개념 토큰 폴백)
 * 2단: 4대 화이트리스트 엣지 기반 그래프 확장 (구조 식별자 공유, View-Script URL 페어링, Java 관계, 형제 위상 유추)
 * 3단: 후보 필터 및 신뢰도 버킷팅 (고신뢰 개별 확인 vs 저신뢰 접힌 묶음)
 */
class Stage0GraphScanner(
    private val graph: ProjectGraph,
    private val minSpecificityScore: Double = 1.0,
    private val proposalBudget: Int = 8,
    private val localDomainOverrides: Map<String, Set<String>> = emptyMap(),
    private val maxBridgeDegree: Int = 15,
    private val maxExternalShared: Int = 3
) {
    private val domainDictionary = DomainDictionary.load(graph)
    private val keywordDecomposer = KeywordDecomposer(graph, domainDictionary)
    private val brotherAnalogyScanner = BrotherAnalogyScanner(graph, maxBridgeDegree, maxExternalShared)

    // 저특이성 범용 필드 블랙리스트 (단독 매칭 시 컷)
    private val GENERIC_FIELDS = setOf(
        "id", "s_idx", "idx", "seq", "resultcode", "result_code", "status", "s_status",
        "startdate", "start_date", "enddate", "end_date", "comp_code", "compcode",
        "page", "pagename", "jsp", "gradeidx", "listdata", "createdat", "updatedat",
        "total_count", "horizon_count", "vertical_count", "page_start", "page_end"
    )

    private fun isGenericOrAuditField(name: String): Boolean {
        val lower = name.lowercase()
        if (GENERIC_FIELDS.contains(lower)) return true
        if (lower.startsWith("login_user") || lower.startsWith("reg_user") || lower.startsWith("mod_user")) return true
        if (lower.startsWith("page_") || lower.startsWith("horizon_") || lower.startsWith("vertical_") || lower.startsWith("total_")) return true
        if (lower.endsWith("_date") || lower.endsWith("_time") || lower.endsWith("_yn")) return true
        return false
    }

    /**
     * 4.1 1단계: 토큰 추출 (extractTokens)
     * 구조 식별자(STRUCTURAL) 1차, 개념 토큰(CONCEPTUAL) 2차 폴백.
     */
    fun extractTokens(input: String, existingTokens: Set<SeedToken> = emptySet()): Set<SeedToken> {
        val result = mutableMapOf<String, SeedToken>()
        // 기존 토큰 복사
        existingTokens.forEach { result[it.value.lowercase()] = it }

        val decomposed = keywordDecomposer.decompose(input)
        val allFileNodes = graph.files.values
        val allResourceNodes = graph.resourceNodes

        // A-0. 로컬 도메인 오버라이드 매칭 (하위 호환성 유지)
        val localTokens = mutableSetOf<String>()
        val inputKoreanWords = extractKoreanStems(input)
        for (kToken in inputKoreanWords) {
            val matches = localDomainOverrides[kToken] 
                ?: localDomainOverrides.entries.find { kToken.contains(it.key) || it.key.contains(kToken) }?.value
            if (matches != null) {
                localTokens.addAll(matches)
            }
        }

        // 로컬 오버라이드 토큰으로부터 클래스 매칭 및 DTO 필드명 추출
        for (lt in localTokens) {
            val matchedNodes = allFileNodes.filter { 
                it.className.contains(lt, ignoreCase = true) || 
                it.path.substringAfterLast("/").contains(lt, ignoreCase = true)
            }
            for (node in matchedNodes) {
                result[node.className.lowercase()] = SeedToken(node.className, TokenKind.STRUCTURAL)
                extractFieldsFromMethods(node).forEach { fieldName ->
                    result[fieldName.lowercase()] = SeedToken(fieldName, TokenKind.STRUCTURAL)
                }
            }
        }

        // A. 1차: 입력에서 언급된 클래스/파일로부터 DTO 필드명 및 구조 식별자 추출
        for (token in decomposed.tokens) {
            val tokenLower = token.lowercase()
            // 1) 클래스명 또는 파일명 매칭 확인 (Java 파일 + Resource 파일)
            val matchedNode = allFileNodes.find { 
                it.className.equals(token, ignoreCase = true) || 
                it.path.substringAfterLast("/").substringBeforeLast(".").equals(token, ignoreCase = true) ||
                it.path.substringAfterLast("/").equals(token, ignoreCase = true)
            }
            val matchedResource = graph.resourceNodes.find {
                val rFileName = it.path.substringAfterLast("/")
                rFileName.equals(token, ignoreCase = true) || 
                rFileName.substringBeforeLast(".").equals(token, ignoreCase = true)
            }

            if (matchedNode != null) {
                result[tokenLower] = SeedToken(matchedNode.className, TokenKind.STRUCTURAL)
                
                // DTO/Entity인 경우 getter/setter로부터 필드명 파생
                extractFieldsFromMethods(matchedNode).forEach { fieldName ->
                    result[fieldName.lowercase()] = SeedToken(fieldName, TokenKind.STRUCTURAL)
                }
            } else if (matchedResource != null) {
                result[tokenLower] = SeedToken(token, TokenKind.STRUCTURAL)
            } else {
                // 그래프에 실재하는 구조 식별자인지 검증 (필드명/메서드명)
                val isGraphIdentifier = allFileNodes.any { node ->
                    extractFieldsFromMethods(node).any { it.equals(tokenLower, ignoreCase = true) } ||
                    node.methods.any { it.name.equals(tokenLower, ignoreCase = true) }
                } || graph.resourceNodes.any { rNode ->
                    val inputs = (rNode.metadata["input_field"] as? List<*>)?.mapNotNull { it?.toString()?.lowercase() } ?: emptyList()
                    inputs.contains(tokenLower)
                }

                if (isGraphIdentifier) {
                    result[tokenLower] = SeedToken(token, TokenKind.STRUCTURAL)
                } else {
                    // 그래프에 없는 어휘는 2차 개념 토큰으로 등록
                    result[tokenLower] = SeedToken(token, TokenKind.CONCEPTUAL)
                }
            }
        }

        // B. contentTokens (한글 도메인 명사 등) 처리
        for (cToken in decomposed.contentTokens) {
            val cLower = cToken.lowercase()
            if (cLower.length >= 2 && !result.containsKey(cLower)) {
                result[cLower] = SeedToken(cToken, TokenKind.CONCEPTUAL)
            }
        }

        // C. 정규화: 동일 value가 STRUCTURAL과 CONCEPTUAL 둘 다 등록된 경우 STRUCTURAL 우선
        return result.values.toSet()
    }

    /**
     * 4.2~4.3 2단계 & 3단계: 미판정 영역 재탐색 및 후보 필터링 (형제 유추 병렬 결합)
     */
    fun rescanUnverified(
        seedSet: Set<SeedToken>,
        frozenItems: List<RequirementItem> = emptyList()
    ): List<RequirementItem> {
        val frozenIds = frozenItems.map { it.id }.toSet()
        val structuralTokens = seedSet.filter { it.kind == TokenKind.STRUCTURAL }.map { it.value.lowercase() }
        val conceptualTokens = seedSet.filter { it.kind == TokenKind.CONCEPTUAL }.map { it.value.lowercase() }

        val candidateMap = mutableMapOf<String, CandidateAcc>()

        // ─────────────────────────────────────────────────────────────
        // 엣지 0: 메타그래프 자연어명(localName) 매칭 엣지
        // ─────────────────────────────────────────────────────────────
        val koreanStopwords = setOf(
            "검사", "등록", "유효성", "조회", "수정", "삭제", "목록", "상세", "추가", "변경",
            "저장", "처리", "검색", "전송", "취소", "오류", "실패", "성공", "에러", "여부",
            "결과", "코드", "상태", "일시", "시간", "번호", "내용", "설정", "권한", "화면",
            "이력", "기능", "관리", "요청", "응답", "개발", "팝업", "제공", "다운로드", "업로드"
        )
        val koreanConceptualTokens = conceptualTokens.filter { 
            Regex("[가-힣]+").containsMatchIn(it) && it !in koreanStopwords && it.length >= 2 
        }
        
        for (rNode in graph.resourceNodes) {
            val lName = rNode.localName ?: (rNode.metadata["localName"] as? String)
            if (lName != null) {
                val matches = koreanConceptualTokens.filter { lName.contains(it) || it.contains(lName) }
                if (matches.isNotEmpty()) {
                    val acc = candidateMap.getOrPut(rNode.path) {
                        CandidateAcc(rNode.path, rNode.type.name, mutableListOf(), 0.0, "", mutableSetOf())
                    }
                    acc.score += 2.0 * matches.size
                    acc.symbols.add("localName:$lName")
                    acc.signals.add(ProvenanceSignal.LOCAL_NAME_MATCH)
                    acc.rationale = "자연어명(\"$lName\")이 요구사항(${matches.joinToString()})과 일치"
                }
            }
        }

        for ((p, fNode) in graph.files) {
            val lName = fNode.localName
            val comments = fNode.koreanComments
            val matches = koreanConceptualTokens.filter { t ->
                (lName != null && (lName.contains(t) || t.contains(lName))) ||
                comments.any { it.contains(t) }
            }
            if (matches.isNotEmpty()) {
                val hasEdges = graph.relationships.any { it.source == p || it.target == p }
                if (hasEdges) {
                    val acc = candidateMap.getOrPut(p) {
                        CandidateAcc(p, fNode.fileType.name, mutableListOf(), 0.0, "", mutableSetOf())
                    }
                    acc.score += minOf(6.0, 2.0 * matches.size)
                    if (lName != null) acc.symbols.add("localName:$lName")
                    acc.signals.add(ProvenanceSignal.LOCAL_NAME_MATCH)
                    acc.rationale = "자연어명/주석이 요구사항(${matches.joinToString()})과 일치"
                }
            }
        }

        // ─────────────────────────────────────────────────────────────
        // 엣지 0-B: Java 소스코드 클래스명 및 API 엔드포인트 직접 앵커 매칭 엣지 (FileNode Direct Anchor Scorer)
        // ─────────────────────────────────────────────────────────────
        val genericTokenStopwords = setOf(
            "controller", "service", "impl", "dao", "mapper", "dto", "vo", "response", "request",
            "entity", "repository", "base", "util", "helper", "common", "config", "job", "batch",
            "list", "get", "set", "save", "update", "delete", "insert", "select", "find", "search", "read",
            "view", "page", "screen", "manage", "management", "mgr", "mgmt", "admin", "main", "detail", "info", "data",
            "handle", "process", "execute", "deal", "query"
        )
        
        val queryDomainTokens = (structuralTokens + conceptualTokens)
            .filter { it.length >= 2 && it !in genericTokenStopwords && !Regex("[가-힣]+").containsMatchIn(it) }
            .distinct()

        for ((p, fNode) in graph.files) {
            val classTokens = DomainDictionary.tokenizeCamelCase(fNode.className).map { it.lowercase() }
            val epTokens = fNode.apiEndpoints.flatMap { ep ->
                DomainDictionary.tokenizeCamelCase(ep.path.substringAfterLast("/")) + 
                DomainDictionary.tokenizeCamelCase(ep.handlerMethod)
            }.map { it.lowercase() }
            val methodTokens = (fNode.methods.map { it.name } + (fNode.demMethods?.map { it.methodName } ?: emptyList()))
                .flatMap { DomainDictionary.tokenizeCamelCase(it) }
                .map { it.lowercase() }

            val fileDistinctTokens = (classTokens + epTokens + methodTokens).filter { it !in genericTokenStopwords }.distinct()
            
            val matchedDomainTokens = queryDomainTokens.filter { qToken ->
                fileDistinctTokens.any { nToken ->
                    nToken == qToken || isTokenBoundaryMatch(nToken, qToken) || (qToken.length >= 4 && (nToken.contains(qToken) || qToken.contains(nToken)))
                }
            }

            val isExactClassMatch = structuralTokens.contains(fNode.className.lowercase())
            val matchCount = matchedDomainTokens.size

            // 다중 토큰 정밀도 판정:
            // 1) 2개 이상의 도메인 토큰이 일치하거나,
            // 2) 1개 이상의 도메인 토큰 + 정확한 클래스명 매칭이거나,
            // 3) 도메인 토큰이 1개만 존재하는 쿼리에서 해당 도메인 토큰이 클래스명에 포함된 경우
            if (matchCount >= 2 || (matchCount >= 1 && (isExactClassMatch || queryDomainTokens.size == 1))) {
                val acc = candidateMap.getOrPut(p) {
                    CandidateAcc(p, fNode.fileType.name, mutableListOf(), 0.0, "", mutableSetOf())
                }
                val directScore = (if (isExactClassMatch) 20.0 else 4.0) + (matchCount * 2.5)
                acc.score += directScore
                acc.symbols.addAll(matchedDomainTokens.map { "token:$it" })
                acc.signals.add(ProvenanceSignal.STRUCTURAL_ID)
                val summaryTokens = matchedDomainTokens.take(3).joinToString(", ")
                acc.rationale = if (acc.rationale.isBlank()) {
                    "클래스명/엔드포인트가 요구사항 토큰($summaryTokens)과 일치"
                } else {
                    "${acc.rationale} + 요구사항 토큰($summaryTokens) 일치"
                }
            }
        }

        // ─────────────────────────────────────────────────────────────
        // 엣지 1: 구조 식별자 공유 엣지 (DTO/클래스 필드 ↔ ResourceNode metadata)
        // ─────────────────────────────────────────────────────────────
        for (rNode in graph.resourceNodes) {
            val inputFields = (rNode.metadata["input_field"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
            val jsFields = (rNode.metadata["field"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
            val methods = (rNode.metadata["methods"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
            val paramTypes = (rNode.metadata["parameter_type"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()

            val matchedSymbols = mutableListOf<String>()
            val signals = mutableSetOf<ProvenanceSignal>()
            var score = 0.0

            // 0. Resource 파일명과 구조적 토큰 매칭
            val resFileName = rNode.path.substringAfterLast("/").substringBeforeLast(".")
            for (st in structuralTokens) {
                if (isTokenBoundaryMatch(resFileName, st)) {
                    matchedSymbols.add("fileName:$st")
                    signals.add(ProvenanceSignal.STRUCTURAL_ID)
                    score += 4.0
                }
            }

            // A. DTO Parameter Type 직접 바인딩 확인 (MyBatis Mapper)
            for (st in structuralTokens) {
                if (paramTypes.any { it.contains(st, ignoreCase = true) }) {
                    matchedSymbols.add("parameterType:$st")
                    signals.add(ProvenanceSignal.MAPPER_CHAIN)
                    score += 5.0
                }
            }

            // B. inputFields 매칭 (단어 경계/스네이크 케이스 매칭)
            for (field in inputFields) {
                val fieldLower = field.lowercase()
                if (isGenericOrAuditField(fieldLower)) continue
                
                for (st in structuralTokens) {
                    if (isGenericOrAuditField(st)) continue
                    if (isTokenBoundaryMatch(fieldLower, st)) {
                        matchedSymbols.add(field)
                        signals.add(ProvenanceSignal.STRUCTURAL_ID)
                        val isDirectRequirementMatch = st.contains("send") || st.contains("channel") || st.contains("template") || st.contains("message") || st.contains("type")
                        score += if (isDirectRequirementMatch) 5.0 else 2.0
                    }
                }
                for (ct in conceptualTokens) {
                    if (isGenericOrAuditField(ct)) continue
                    if (isTokenBoundaryMatch(fieldLower, ct)) {
                        matchedSymbols.add(field)
                        signals.add(ProvenanceSignal.STRUCTURAL_ID)
                        val isDirectRequirementMatch = ct.contains("send") || ct.contains("channel") || ct.contains("message") || ct.contains("발송") || ct.contains("채널")
                        score += if (isDirectRequirementMatch) 3.5 else 1.5
                    }
                }
            }

            // C. JS fields / methods 매칭
            for (f in jsFields + methods) {
                val fLower = f.lowercase()
                if (isGenericOrAuditField(fLower)) continue
                
                for (st in structuralTokens) {
                    if (isGenericOrAuditField(st)) continue
                    if (isTokenBoundaryMatch(fLower, st)) {
                        matchedSymbols.add(f)
                        signals.add(ProvenanceSignal.STRUCTURAL_ID)
                        val isDirectRequirementMatch = st.contains("send") || st.contains("channel") || st.contains("template") || st.contains("message") || st.contains("type")
                        score += if (isDirectRequirementMatch) 4.0 else 1.5
                    }
                }
                for (ct in conceptualTokens) {
                    if (isGenericOrAuditField(ct)) continue
                    if (isTokenBoundaryMatch(fLower, ct)) {
                        matchedSymbols.add(f)
                        signals.add(ProvenanceSignal.STRUCTURAL_ID)
                        val isDirectRequirementMatch = ct.contains("send") || ct.contains("channel") || ct.contains("message") || ct.contains("발송") || ct.contains("채널")
                        score += if (isDirectRequirementMatch) 2.5 else 1.0
                    }
                }
            }

            if (score > 0) {
                val acc = candidateMap.getOrPut(rNode.path) {
                    CandidateAcc(rNode.path, rNode.type.name, mutableListOf(), 0.0, "", mutableSetOf())
                }
                acc.score += score
                acc.symbols.addAll(matchedSymbols)
                acc.signals.addAll(signals)
                acc.rationale = "구조 식별자(${matchedSymbols.distinct().take(3).joinToString(", ")}) 공유"
            }
        }

        // ─────────────────────────────────────────────────────────────
        // 엣지 2: View-Script URL 페어 엣지 (JSP ↔ JS 페어링 및 Dynamic Binding)
        // ─────────────────────────────────────────────────────────────
        val matchedResourcePaths = candidateMap.keys.toList()
        for (path in matchedResourcePaths) {
            val sourceResource = graph.resourceNodes.find { it.path == path } ?: continue
            val sourceUrls = sourceResource.dynamicBindings.map { it.matchedUrl }.filter { it.isNotBlank() }

            if (sourceUrls.isNotEmpty()) {
                // 1) 동일 URL을 공유하는 View-Script 페어링 (상호 가산점 선반영)
                for (targetResource in graph.resourceNodes) {
                    if (targetResource.path == path) continue
                    val targetUrls = targetResource.dynamicBindings.map { it.matchedUrl }
                    val sharedUrl = sourceUrls.firstOrNull { targetUrls.contains(it) }

                    if (sharedUrl != null) {
                        val acc = candidateMap.getOrPut(targetResource.path) {
                            CandidateAcc(targetResource.path, targetResource.type.name, mutableListOf(), 0.0, "", mutableSetOf())
                        }
                        acc.score = maxOf(acc.score, 4.0)
                        acc.symbols.add("pairUrl:$sharedUrl")
                        acc.signals.add(ProvenanceSignal.VIEW_SCRIPT_PAIR)
                        val sourceFileName = path.substringAfterLast("/")
                        acc.rationale = if (acc.rationale.isBlank()) {
                            "[$sourceFileName]와 동일 URL($sharedUrl) 공유하는 View-Script 페어"
                        } else {
                            "${acc.rationale} + [$sourceFileName]와 동일 URL($sharedUrl) 페어"
                        }
                        candidateMap[path]?.let { srcAcc ->
                            srcAcc.signals.add(ProvenanceSignal.VIEW_SCRIPT_PAIR)
                            srcAcc.score = maxOf(srcAcc.score, 4.0)
                        }
                    }
                }

                // 2) Controller 바인딩 (페어링 점수 반영 후 전파)
                for (db in sourceResource.dynamicBindings) {
                    if (db.controllerPath.isNotBlank() && graph.files.containsKey(db.controllerPath)) {
                        val cNode = graph.files[db.controllerPath]!!
                        val acc = candidateMap.getOrPut(db.controllerPath) {
                            CandidateAcc(db.controllerPath, cNode.fileType.name, mutableListOf(), 0.0, "", mutableSetOf())
                        }
                        acc.score = maxOf(acc.score, (candidateMap[path]?.score ?: 2.0) * 0.95)
                        acc.symbols.add("url:${db.matchedUrl}")
                        acc.signals.add(ProvenanceSignal.STRUCTURAL_ID)
                        val sourceFileName = path.substringAfterLast("/")
                        acc.rationale = "[$sourceFileName]의 URL(${db.matchedUrl})을 처리하는 Controller"
                        candidateMap[path]?.signals?.add(ProvenanceSignal.STRUCTURAL_ID)
                    }
                }
            }
        }

        // ─────────────────────────────────────────────────────────────
        // 엣지 3: Java 클래스 관계 엣지 (INJECTS, IMPLEMENTS, CALLS 다중 홉 위상 전파 + 의미적 공명 감쇠)
        // ─────────────────────────────────────────────────────────────
        for (hop in 0 until 3) {
            for (rel in graph.relationships) {
                if (rel.type != RelationshipType.INJECTS && rel.type != RelationshipType.CALLS && rel.type != RelationshipType.IMPLEMENTS) continue
                
                val srcMatch = candidateMap[rel.source]
                val tgtMatch = candidateMap[rel.target]

                if (srcMatch != null) {
                    val tgtNode = graph.files[rel.target]
                    if (tgtNode != null && tgtNode.fileType != SpringFileType.DTO && tgtNode.fileType != SpringFileType.ENTITY) {
                        val isResonant = hasSemanticResonance(tgtNode, koreanConceptualTokens, structuralTokens)
                        val decay = if (isResonant) 0.90 else 0.40
                        val propScore = srcMatch.score * decay
                        if (propScore >= 1.0) {
                            val acc = candidateMap.getOrPut(rel.target) {
                                CandidateAcc(rel.target, tgtNode.fileType.name, mutableListOf(), 0.0, "", mutableSetOf(), srcMatch.hop + 1)
                            }
                            if (propScore > acc.score) {
                                acc.score = propScore
                                acc.hop = minOf(acc.hop, srcMatch.hop + 1)
                                acc.symbols.add("relation:${rel.type}")
                                acc.signals.add(ProvenanceSignal.STRUCTURAL_ID)
                                acc.rationale = "[${rel.source.substringAfterLast("/")}]로부터 ${rel.type} 연결"
                            }
                            srcMatch.signals.add(ProvenanceSignal.STRUCTURAL_ID)
                        }
                    }
                }
                if (tgtMatch != null) {
                    val srcNode = graph.files[rel.source]
                    if (srcNode != null && srcNode.fileType != SpringFileType.DTO && srcNode.fileType != SpringFileType.ENTITY) {
                        val isResonant = hasSemanticResonance(srcNode, koreanConceptualTokens, structuralTokens)
                        val decay = if (isResonant) 0.90 else 0.40
                        val propScore = tgtMatch.score * decay
                        if (propScore >= 1.0) {
                            val acc = candidateMap.getOrPut(rel.source) {
                                CandidateAcc(rel.source, srcNode.fileType.name, mutableListOf(), 0.0, "", mutableSetOf(), tgtMatch.hop + 1)
                            }
                            if (propScore > acc.score) {
                                acc.score = propScore
                                acc.hop = minOf(acc.hop, tgtMatch.hop + 1)
                                acc.symbols.add("relation:${rel.type}")
                                acc.signals.add(ProvenanceSignal.STRUCTURAL_ID)
                                acc.rationale = "[${rel.target.substringAfterLast("/")}]의 ${rel.type} 연결"
                            }
                            tgtMatch.signals.add(ProvenanceSignal.STRUCTURAL_ID)
                        }
                    }
                }
            }
        }

        // 3-B) DTO USES_TYPE 관계 확장 (Dead Node 고립 차단)
        for (rel in graph.relationships) {
            if (rel.type == RelationshipType.USES_TYPE) {
                val srcMatch = candidateMap[rel.source]
                if (srcMatch != null && srcMatch.score >= minSpecificityScore) {
                    val tgtNode = graph.files[rel.target]
                    if (tgtNode != null && (tgtNode.fileType == SpringFileType.DTO || tgtNode.fileType == SpringFileType.ENTITY)) {
                        val totalEdges = graph.relationships.count { it.source == rel.target || it.target == rel.target }
                        if (totalEdges > 0) {
                            val isResonant = hasSemanticResonance(tgtNode, koreanConceptualTokens, structuralTokens)
                            val decay = if (isResonant) 0.95 else 0.40
                            val propScore = srcMatch.score * decay
                            if (propScore >= minSpecificityScore) {
                                val acc = candidateMap.getOrPut(rel.target) {
                                    CandidateAcc(rel.target, tgtNode.fileType.name, mutableListOf(), 0.0, "", mutableSetOf(), srcMatch.hop + 1)
                                }
                                if (propScore > acc.score) {
                                    acc.score = propScore
                                    acc.hop = minOf(acc.hop, srcMatch.hop + 1)
                                    acc.symbols.add("dto:${rel.source.substringAfterLast("/")}")
                                    acc.signals.add(ProvenanceSignal.STRUCTURAL_ID)
                                    acc.rationale = "[${rel.source.substringAfterLast("/")}]에서 사용하는 DTO"
                                }
                            }
                        }
                    }
                }
            }
        }

        // ─────────────────────────────────────────────────────────────
        // 엣지 4: MyBatis Mapper 바인딩 엣지 (DAO ↔ XML Mapper 앵커 바인딩)
        // ─────────────────────────────────────────────────────────────
        for (p in candidateMap.keys.toList()) {
            val parentAcc = candidateMap[p] ?: continue
            if (parentAcc.score < minSpecificityScore) continue
            for (rNode in graph.resourceNodes) {
                if (rNode.type == ResourceType.MYBATIS_MAPPER && (rNode.linkedTo.contains(p) || rNode.linkedTo.any { p.contains(it) })) {
                    val mapperScore = parentAcc.score * 0.95
                    val acc = candidateMap.getOrPut(rNode.path) {
                        CandidateAcc(rNode.path, rNode.type.name, mutableListOf(), 0.0, "", mutableSetOf(), parentAcc.hop + 1)
                    }
                    acc.score = maxOf(acc.score, mapperScore)
                    acc.hop = minOf(acc.hop, parentAcc.hop + 1)
                    acc.symbols.add("mapper:${p.substringAfterLast("/")}")
                    acc.signals.add(ProvenanceSignal.MAPPER_CHAIN)
                    acc.rationale = "[${p.substringAfterLast("/")}]에 바인딩된 MyBatis 매퍼"
                    parentAcc.signals.add(ProvenanceSignal.MAPPER_CHAIN)
                }
            }
        }

        // ─────────────────────────────────────────────────────────────
        // 3단계: 기본 신호 후보 생성 및 다채널 교차검증 신뢰도 버킷팅
        // ─────────────────────────────────────────────────────────────
        val items = mutableListOf<RequirementItem>()

        fun isBatchOrAsync(path: String): Boolean {
            return path.contains("Batch") || path.contains("Runner") || path.contains("Job")
        }

        val validCandidates = candidateMap.values
            .filter { cand -> 
                cand.score >= minSpecificityScore && !isBatchOrAsync(cand.path) &&
                (cand.signals.size > 1 || !cand.signals.contains(ProvenanceSignal.LOCAL_NAME_MATCH) || cand.type == "VIEW" || cand.type == "SCRIPT")
            }

        fun isDataAccessType(type: String, path: String): Boolean {
            if (type == "DAO" || type == "REPOSITORY" || type == "MYBATIS_MAPPER" || type == "DATA_ACCESS") return true
            val lower = path.lowercase()
            if (lower.contains("/dao/") || lower.contains("/repository/") || lower.contains("/dem/") || lower.contains("/dqm/")) return true
            return false
        }

        // 계층별 균등 선발 (Stratified Round-Robin Selection: View -> Script -> Business -> DTO -> DataAccess)
        val viewQueue = ArrayDeque(
            validCandidates.filter { it.type == "VIEW" }
                .sortedWith(compareByDescending<CandidateAcc> { it.score + coreActionWeight(it.path) * 10.0 })
        )
        val scriptQueue = ArrayDeque(
            validCandidates.filter { it.type == "SCRIPT" }
                .sortedWith(compareByDescending<CandidateAcc> { it.score + coreActionWeight(it.path) * 10.0 })
        )
        val dataAccessQueue = ArrayDeque(
            validCandidates.filter { isDataAccessType(it.type, it.path) }
                .sortedWith(compareByDescending<CandidateAcc> { 
                    it.score + (if (it.type == "MYBATIS_MAPPER") 5.0 else 0.0)
                })
        )
        val dtoQueue = ArrayDeque(
            validCandidates.filter { it.type == "DTO" || it.type == "ENTITY" }
                .sortedByDescending { it.score }
        )
        val businessQueue = ArrayDeque(
            validCandidates.filter { !isDataAccessType(it.type, it.path) && it.type != "DTO" && it.type != "ENTITY" && it.type != "VIEW" && it.type != "SCRIPT" }
                .sortedByDescending { it.score }
        )

        val roundRobinList = mutableListOf<CandidateAcc>()
        while (roundRobinList.size < proposalBudget && (viewQueue.isNotEmpty() || scriptQueue.isNotEmpty() || businessQueue.isNotEmpty() || dtoQueue.isNotEmpty() || dataAccessQueue.isNotEmpty())) {
            viewQueue.removeFirstOrNull()?.let { roundRobinList.add(it) }
            if (roundRobinList.size >= proposalBudget) break
            scriptQueue.removeFirstOrNull()?.let { roundRobinList.add(it) }
            if (roundRobinList.size >= proposalBudget) break
            businessQueue.removeFirstOrNull()?.let { roundRobinList.add(it) }
            if (roundRobinList.size >= proposalBudget) break
            dtoQueue.removeFirstOrNull()?.let { roundRobinList.add(it) }
            if (roundRobinList.size >= proposalBudget) break
            dataAccessQueue.removeFirstOrNull()?.let { roundRobinList.add(it) }
        }

        val sortedCandidates = roundRobinList.distinctBy { it.path }

        for (cand in sortedCandidates) {
            val symbols = cand.symbols.distinct()
            val hint = LinkHint.ExistingRef(cand.path, symbols)
            val statement = generateStatement(cand.path, cand.type, symbols)
            val id = RequirementItem.deriveId(hint, statement)

            if (frozenIds.contains(id)) continue

            // 다채널 교차검증 판정:
            // 2개 이상의 독립 신호로 교차검증되었거나 구조 식별자/페어링을 보유한 경우 HIGH_CONFIDENCE,
            // 단독 localName 텍스트 매칭인 경우 LOW_CONFIDENCE로 격하.
            val isSoloLocalName = cand.signals.size == 1 && cand.signals.contains(ProvenanceSignal.LOCAL_NAME_MATCH)
            val confidence = if (isSoloLocalName) ConfidenceBucket.LOW_CONFIDENCE else ConfidenceBucket.HIGH_CONFIDENCE

            items.add(
                RequirementItem(
                    id = id,
                    statement = statement,
                    source = HintSource.SYSTEM_UNCONFIRMED,
                    hint = hint,
                    anchorRationale = cand.rationale,
                    verdict = Verdict.PENDING,
                    confidence = confidence,
                    provenanceSignals = cand.signals,
                    domainPackage = brotherAnalogyScanner.extractDomainPackage(cand.path)
                )
            )
        }

        // ─────────────────────────────────────────────────────────────
        // 엣지 5: 형제 유추(Brother Analogy) 병렬 결합 (LOW_CONFIDENCE 버킷 & 교차 승격)
        // ─────────────────────────────────────────────────────────────
        // 유효 점수(score >= 1.5)를 획득한 코어 노드들을 시드로 사용 (비-배치 노드로 엄격 한정)
        val internalSeedNodes = candidateMap.values
            .filter { it.score >= 1.5 && !isBatchOrAsync(it.path) }
            .mapNotNull { graph.files[it.path] }

        val tokenMatchedNodes = graph.files.values.filter { node ->
            !isBatchOrAsync(node.path) &&
            structuralTokens.any { st -> 
                node.className.equals(st, ignoreCase = true)
            }
        }
        val seedNodes = (tokenMatchedNodes + internalSeedNodes).distinctBy { it.path }

        val siblingItems = brotherAnalogyScanner.scanSiblings(seedNodes, frozenIds)

        for (sib in siblingItems) {
            val existingIdx = items.indexOfFirst { it.id == sib.id }
            if (existingIdx >= 0) {
                // 다중 채널 교차 확인 -> 신호 결합 및 고신뢰 유지
                val existing = items[existingIdx]
                items[existingIdx] = existing.copy(
                    provenanceSignals = existing.provenanceSignals + ProvenanceSignal.SIBLING_ANALOGY,
                    confidence = ConfidenceBucket.HIGH_CONFIDENCE
                )
            } else {
                // 형제 유추 단독 신호 -> LOW_CONFIDENCE 버킷으로 추가
                items.add(sib)
            }
        }

        return items
    }

    private fun extractKoreanStems(input: String): List<String> {
        val koreanPattern = Regex("[가-힣]+")
        val words = koreanPattern.findAll(input).map { it.value }.filter { it.length >= 2 }.toList()
        val particles = listOf("에서", "으로", "에는", "에", "을", "를", "의", "은", "는", "이", "가", "과", "와", "로", "도")
        val results = mutableSetOf<String>()
        for (w in words) {
            results.add(w)
            for (p in particles) {
                if (w.endsWith(p) && (w.length - p.length) >= 2) {
                    results.add(w.dropLast(p.length))
                }
            }
        }
        return results.toList()
    }

    private fun isTokenBoundaryMatch(target: String, token: String): Boolean {
        if (target.isBlank() || token.isBlank()) return false
        if (target.equals(token, ignoreCase = true)) return true
        
        if (target.contains(token, ignoreCase = true)) {
            val idx = target.indexOf(token, ignoreCase = true)
            val beforeOk = idx == 0 || !target[idx - 1].isLetterOrDigit() || target[idx - 1] == '_'
            val afterIdx = idx + token.length
            val afterOk = afterIdx == target.length || !target[afterIdx].isLetterOrDigit() || target[afterIdx] == '_'
            if (beforeOk && afterOk) return true
        }
        
        val segments = target.split(Regex("(?<=[a-z])(?=[A-Z])|_|-|\\.")).map { it.lowercase() }
        val tokenLower = token.lowercase()
        return segments.any { it == tokenLower }
    }

    internal fun extractFieldsFromMethods(node: FileNode): List<String> {
        val fields = mutableListOf<String>()
        for (m in node.methods) {
            val name = m.name
            val fieldName = when {
                name.startsWith("get") && name.length > 3 -> name.substring(3).replaceFirstChar { it.lowercase() }
                name.startsWith("set") && name.length > 3 -> name.substring(3).replaceFirstChar { it.lowercase() }
                name.startsWith("is") && name.length > 2 -> name.substring(2).replaceFirstChar { it.lowercase() }
                else -> null
            }
            if (fieldName != null && !isGenericOrAuditField(fieldName)) {
                fields.add(fieldName)
            }
        }
        return fields.distinct()
    }

    private fun hasSemanticResonance(node: FileNode, conceptualTokens: List<String>, structuralTokens: List<String>): Boolean {
        val lName = node.localName ?: ""
        val cName = node.className.lowercase()
        val comments = node.koreanComments.joinToString(" ")
        
        for (ct in conceptualTokens) {
            if (ct.length >= 2) {
                if (lName.contains(ct, ignoreCase = true) || cName.contains(ct, ignoreCase = true) || comments.contains(ct, ignoreCase = true)) return true
            }
        }
        for (st in structuralTokens) {
            if (st.length >= 3) {
                if (cName.contains(st, ignoreCase = true) || lName.contains(st, ignoreCase = true)) return true
                val segs = st.split(Regex("(?<=[a-z])(?=[A-Z])|_|-|\\.")).map { it.lowercase() }.filter { it.length >= 3 && !isGenericOrAuditField(it) }
                if (segs.any { cName.contains(it) || comments.contains(it) }) return true
            }
        }
        return false
    }

    private fun coreActionWeight(path: String): Int {
        val lower = path.lowercase()
        if (lower.contains("list") || lower.contains("write") || lower.contains("regist")) return 2
        if (lower.contains("result") || lower.contains("preview") || lower.contains("detail")) return 1
        return 0
    }

    private fun generateStatement(path: String, type: String, symbols: List<String>): String {
        val fileName = path.substringAfterLast("/")
        val cleanSymbols = symbols.filter { !it.contains(":") }.take(2).joinToString(", ")
        val symbolClause = if (cleanSymbols.isNotBlank()) " (관련 필드/기능: $cleanSymbols)" else ""
        return "[$fileName] 파일에서 요구사항 관련 처리$symbolClause 를 수행해야 한다."
    }

    fun extractDomainPackage(path: String?): String? {
        return brotherAnalogyScanner.extractDomainPackage(path)
    }

    private data class CandidateAcc(
        val path: String,
        val type: String,
        val symbols: MutableList<String>,
        var score: Double,
        var rationale: String,
        val signals: MutableSet<ProvenanceSignal>,
        var hop: Int = 0
    )
}
