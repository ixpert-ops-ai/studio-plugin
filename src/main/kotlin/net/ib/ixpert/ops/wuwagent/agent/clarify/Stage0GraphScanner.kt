package net.ib.ixpert.ops.wuwagent.agent.clarify

import net.ib.ixpert.ops.wuwagent.agent.analyze.search.KeywordDecomposer
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.*
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DomainDictionary
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*

/**
 * Stage 0 미판정 영역 재탐색 3단 파이프라인.
 * 설계서 (v1.0) 4절 준수.
 * 
 * 1단: 토큰 추출 (1차 구조 식별자 + 2차 개념 토큰 폴백)
 * 2단: 3대 화이트리스트 엣지 기반 그래프 확장 (구조 식별자 공유, View-Script URL 페어링, Java 관계)
 * 3단: 후보 필터 (저특이성 컷, 제안 예산 제한, Anchor 근거 생성)
 */
class Stage0GraphScanner(
    private val graph: ProjectGraph,
    private val minSpecificityScore: Double = 1.0,
    private val proposalBudget: Int = 8,
    private val localDomainOverrides: Map<String, Set<String>> = emptyMap()
) {
    private val domainDictionary = DomainDictionary.load(graph)
    private val keywordDecomposer = KeywordDecomposer(graph, domainDictionary)

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

        // A-0. 로컬 도메인 오버라이드 매칭 (전역 사전 오염 방지)
        val localTokens = mutableSetOf<String>()
        val koreanPattern = Regex("[가-힣]+")
        koreanPattern.findAll(input).map { it.value }.filter { it.length >= 2 }.forEach { kToken ->
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

        // A-1. 1차: 사용자 입력 쿼리로부터 식별된 핵심 클래스들(matchedClassNames)에서 DTO 필드명 추출
        for (className in decomposed.matchedClassNames) {
            val matchedNode = allFileNodes.find { it.className.equals(className, ignoreCase = true) }
            if (matchedNode != null) {
                result[matchedNode.className.lowercase()] = SeedToken(matchedNode.className, TokenKind.STRUCTURAL)
                extractFieldsFromMethods(matchedNode).forEach { fieldName ->
                    result[fieldName.lowercase()] = SeedToken(fieldName, TokenKind.STRUCTURAL)
                }
            }
        }

        // A. 1차: 입력에서 언급된 클래스/파일로부터 DTO 필드명 및 구조 식별자 추출
        for (token in decomposed.tokens) {
            val tokenLower = token.lowercase()
            // 1) 클래스명 매칭 확인
            val matchedNode = allFileNodes.find { 
                it.className.equals(token, ignoreCase = true) || 
                it.path.substringAfterLast("/").substringBeforeLast(".").equals(token, ignoreCase = true) 
            }
            if (matchedNode != null) {
                result[matchedNode.className.lowercase()] = SeedToken(matchedNode.className, TokenKind.STRUCTURAL)
                
                // DTO/Entity인 경우 getter/setter로부터 필드명 파생
                extractFieldsFromMethods(matchedNode).forEach { fieldName ->
                    result[fieldName.lowercase()] = SeedToken(fieldName, TokenKind.STRUCTURAL)
                }
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
     * 4.2~4.3 2단계 & 3단계: 미판정 영역 재탐색 및 후보 필터링
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
        // 엣지 1: 구조 식별자 공유 엣지 (DTO/클래스 필드 ↔ ResourceNode metadata)
        // ─────────────────────────────────────────────────────────────
        for (rNode in graph.resourceNodes) {
            val inputFields = (rNode.metadata["input_field"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
            val jsFields = (rNode.metadata["field"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
            val methods = (rNode.metadata["methods"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
            val sqlIds = (rNode.metadata["sql_id"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
            val paramTypes = (rNode.metadata["parameter_type"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()

            val matchedSymbols = mutableListOf<String>()
            var score = 0.0

            // A. DTO Parameter Type 직접 바인딩 확인 (MyBatis Mapper)
            for (st in structuralTokens) {
                if (paramTypes.any { it.contains(st, ignoreCase = true) }) {
                    matchedSymbols.add("parameterType:$st")
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
                        val isDirectRequirementMatch = st.contains("send") || st.contains("channel") || st.contains("template") || st.contains("message") || st.contains("type")
                        score += if (isDirectRequirementMatch) 5.0 else 2.0
                    }
                }
                for (ct in conceptualTokens) {
                    if (isGenericOrAuditField(ct)) continue
                    if (isTokenBoundaryMatch(fieldLower, ct)) {
                        matchedSymbols.add(field)
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
                        val isDirectRequirementMatch = st.contains("send") || st.contains("channel") || st.contains("template") || st.contains("message") || st.contains("type")
                        score += if (isDirectRequirementMatch) 4.0 else 1.5
                    }
                }
                for (ct in conceptualTokens) {
                    if (isGenericOrAuditField(ct)) continue
                    if (isTokenBoundaryMatch(fLower, ct)) {
                        matchedSymbols.add(f)
                        val isDirectRequirementMatch = ct.contains("send") || ct.contains("channel") || ct.contains("message") || ct.contains("발송") || ct.contains("채널")
                        score += if (isDirectRequirementMatch) 2.5 else 1.0
                    }
                }
            }

            if (score > 0) {
                val acc = candidateMap.getOrPut(rNode.path) {
                    CandidateAcc(rNode.path, rNode.type.name, mutableListOf(), 0.0, "")
                }
                acc.score += score
                acc.symbols.addAll(matchedSymbols)
                acc.rationale = "구조 식별자(${matchedSymbols.distinct().take(3).joinToString(", ")}) 공유"
            }
        }

        // ─────────────────────────────────────────────────────────────
        // 엣지 2: View-Script URL 페어 엣지 (JSP ↔ JS 페어링)
        // ─────────────────────────────────────────────────────────────
        val matchedResourcePaths = candidateMap.keys.toList()
        for (path in matchedResourcePaths) {
            val sourceResource = graph.resourceNodes.find { it.path == path } ?: continue
            val sourceUrls = sourceResource.dynamicBindings.map { it.matchedUrl }.filter { it.isNotBlank() }

            if (sourceUrls.isNotEmpty()) {
                for (targetResource in graph.resourceNodes) {
                    if (targetResource.path == path) continue
                    val targetUrls = targetResource.dynamicBindings.map { it.matchedUrl }
                    val sharedUrl = sourceUrls.firstOrNull { targetUrls.contains(it) }

                    if (sharedUrl != null) {
                        val acc = candidateMap.getOrPut(targetResource.path) {
                            CandidateAcc(targetResource.path, targetResource.type.name, mutableListOf(), 0.0, "")
                        }
                        // 페어 보너스 점수 부여 (소스 점수의 90%)
                        val inheritedScore = (candidateMap[path]?.score ?: 2.0) * 0.9
                        acc.score = maxOf(acc.score, inheritedScore)
                        acc.symbols.add("pairUrl:$sharedUrl")
                        val sourceFileName = path.substringAfterLast("/")
                        acc.rationale = if (acc.rationale.isBlank()) {
                            "[$sourceFileName]와 동일 URL($sharedUrl) 공유하는 View-Script 페어"
                        } else {
                            "${acc.rationale} + [$sourceFileName]와 동일 URL($sharedUrl) 페어"
                        }
                    }
                }
            }
        }

        // ─────────────────────────────────────────────────────────────
        // 엣지 3: Java 클래스 관계 엣지 (INJECTS, IMPLEMENTS, CALLS)
        // ─────────────────────────────────────────────────────────────
        for (rel in graph.relationships) {
            val srcMatch = candidateMap[rel.source]
            val tgtMatch = candidateMap[rel.target]

            if (srcMatch != null && !candidateMap.containsKey(rel.target)) {
                val tgtNode = graph.files[rel.target]
                if (tgtNode != null) {
                    val acc = candidateMap.getOrPut(rel.target) {
                        CandidateAcc(rel.target, tgtNode.fileType.name, mutableListOf(), 0.0, "")
                    }
                    acc.score = maxOf(acc.score, srcMatch.score * 0.7)
                    acc.symbols.add("relation:${rel.type}")
                    acc.rationale = "[${rel.source.substringAfterLast("/")}]로부터 ${rel.type} 연결"
                }
            } else if (tgtMatch != null && !candidateMap.containsKey(rel.source)) {
                val srcNode = graph.files[rel.source]
                if (srcNode != null) {
                    val acc = candidateMap.getOrPut(rel.source) {
                        CandidateAcc(rel.source, srcNode.fileType.name, mutableListOf(), 0.0, "")
                    }
                    acc.score = maxOf(acc.score, tgtMatch.score * 0.7)
                    acc.symbols.add("relation:${rel.type}")
                    acc.rationale = "[${rel.target.substringAfterLast("/")}]와 ${rel.type} 연결"
                }
            }
        }

        // ─────────────────────────────────────────────────────────────
        // 3단계: 후보 필터링 및 RequirementItem 생성
        // ─────────────────────────────────────────────────────────────
        val items = mutableListOf<RequirementItem>()

        val sortedCandidates = candidateMap.values
            .filter { it.score >= minSpecificityScore }
            .sortedByDescending { it.score }
            .take(proposalBudget)

        for (cand in sortedCandidates) {
            val symbols = cand.symbols.distinct()
            val hint = LinkHint.ExistingRef(cand.path, symbols)
            val statement = generateStatement(cand.path, cand.type, symbols)
            val id = RequirementItem.deriveId(hint, statement)

            // 이미 동결된 id는 재탐색 결과에서 제외 (동결 보호)
            if (frozenIds.contains(id)) continue

            items.add(
                RequirementItem(
                    id = id,
                    statement = statement,
                    source = HintSource.SYSTEM_UNCONFIRMED,
                    hint = hint,
                    anchorRationale = cand.rationale,
                    verdict = Verdict.PENDING
                )
            )
        }

        return items
    }

    private fun isTokenBoundaryMatch(target: String, token: String): Boolean {
        if (target.isBlank() || token.isBlank()) return false
        if (target.equals(token, ignoreCase = true)) return true
        
        // 스네이크 케이스 분해 매칭: "test_send_type".split('_') -> ["test", "send", "type"] contains "send_type"
        if (target.contains(token, ignoreCase = true)) {
            val pattern = Regex("(?:^|_|\\b)${Regex.escape(token)}(?:_|$|\\b)", RegexOption.IGNORE_CASE)
            return pattern.containsMatchIn(target)
        }
        return false
    }

    private fun extractFieldsFromMethods(node: FileNode): List<String> {
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

    private fun generateStatement(path: String, type: String, symbols: List<String>): String {
        val fileName = path.substringAfterLast("/")
        val cleanSymbols = symbols.filter { !it.contains(":") }.take(2).joinToString(", ")
        val symbolClause = if (cleanSymbols.isNotBlank()) " (관련 필드/기능: $cleanSymbols)" else ""
        return "[$fileName] 파일에서 요구사항 관련 처리$symbolClause 를 수행해야 한다."
    }

    private data class CandidateAcc(
        val path: String,
        val type: String,
        val symbols: MutableList<String>,
        var score: Double,
        var rationale: String
    )
}
