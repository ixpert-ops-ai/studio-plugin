package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*

import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 요구사항 기반 파일 관련성 필터.
 *
 * 70MB+ 규모의 MetaGraph에서 요구사항과 관련된 파일만 추려내어
 * LLM 토큰 사용량을 대폭 절감합니다.
 *
 * 흐름:
 * 1. 한글 요구사항 → LLM 호출로 영문 번역 + 키워드 추출 (1회 호출)
 * 2. 키워드로 files 맵의 path/className/packageName/apiEndpoints 매칭
 * 3. 매칭된 파일의 dependsOn/dependedBy + IMPLEMENTS 관계 1-depth 확장
 * 4. 상한(MAX_FILES) 적용 후 필터링된 ProjectGraph 반환
 */
object RelevanceFilter {

    private val logger = Logger.getInstance(RelevanceFilter::class.java)

    /** 필터링 결과 최대 파일 수 */
    private const val MAX_FILES = 200

    /** 매칭 결과 최소 보장 수 (이하이면 패키지 확장) */
    private const val MIN_FILES = 10

    /** 공통 유틸 확장 제한 임계값 (dependedBy가 이 수 초과이면 역방향 확장 스킵) */
    private const val MAX_DEPENDED_BY_FOR_EXPANSION = 8

    /** LLM 호출 타임아웃 (초) */
    private const val LLM_TIMEOUT_SECONDS = 30L

    /** 키워드 추출 시 LLM max_tokens */
    private const val KEYWORD_MAX_TOKENS = 150

    // ═══════════════════════════════════════════════════════════════
    // Public API
    // ═══════════════════════════════════════════════════════════════

    /**
     * 요구사항과 관련된 파일만 포함하는 축소된 ProjectGraph를 반환합니다.
     *
     * @param requirement 사용자의 원본 요구사항 (한글 가능)
     * @param graph 전체 ProjectGraph
     * @param client LLM 클라이언트 (키워드 추출용)
     * @param onProgress 진행 상태 콜백
     * @return 필터링된 ProjectGraph + 추출된 키워드
     */
    fun filter(
        requirement: String,
        graph: ProjectGraph,
        client: LLMClient,
        project: Project?,
        onProgress: ((String) -> Unit)? = null
    ): FilterResult {
        // Step 1: 한글 요구사항 → 영문 키워드 추출
        onProgress?.invoke("> 🔑 요구사항에서 검색 키워드를 추출하고 있습니다...\n")
        val keywords = extractKeywords(requirement, client, onProgress)
        logger.info("RelevanceFilter: 추출된 키워드 → $keywords")

        if (keywords.isEmpty()) {
            onProgress?.invoke("> ⚠️ 키워드 추출에 실패했습니다. 전체 파일 목록으로 분석합니다.\n\n")
            return FilterResult(graph, emptyList())
        }

        onProgress?.invoke("> 📋 키워드: ${keywords.joinToString(", ")}\n")

        // [Stage 0: Module Selection & Dynamic Loading]
        // 멀티모듈 레벨 1(요약 그래프) 구조인 경우, 키워드 매칭을 통해 필요한 모듈만 동적으로 로딩하여 메모리 최소화
        val workingGraph = if (graph.graphType == GraphType.MULTI_LEVEL_1 && graph.modules != null) {
            onProgress?.invoke("> 📦 [Stage 0] 요구사항 기반으로 관련 서브 모듈을 식별하고 있습니다...\n")
            val targetModules = mutableListOf<String>()
            
            for (module in graph.modules) {
                val nameLower = module.name.lowercase()
                val rootPathLower = module.rootPath.lowercase()
                
                // 1. 모듈명/경로와 매칭
                val isNameMatched = keywords.any { nameLower.contains(it) || it.contains(nameLower) }
                val isPathMatched = keywords.any { rootPathLower.contains(it) || it.contains(rootPathLower) }
                
                // 2. 노출 API Endpoint와 매칭
                val isApiMatched = module.publicApis?.any { api ->
                    keywords.any { api.lowercase().contains(it) }
                } ?: false
                
                if (isNameMatched || isPathMatched || isApiMatched) {
                    targetModules.add(module.name)
                }
            }

            if (targetModules.isNotEmpty()) {
                onProgress?.invoke("> 🎯 연관 서브 모듈 선정: ${targetModules.joinToString(", ")} (총 ${targetModules.size}개/전체 ${graph.modules.size}개)\n")
                if (project != null) {
                    onProgress?.invoke("> 📥 해당 모듈의 상세 메타데이터(Level 2)만 동적으로 병합 로드합니다...\n")
                    val graphLoader = project.getService(GraphLoader::class.java)
                    val loaded = graphLoader.loadGraph(targetModules = targetModules)
                    if (loaded != null) {
                        loaded
                    } else {
                        onProgress?.invoke("> ⚠️ 동적 로딩 실패. 전체 모듈 데이터를 폴백 로딩합니다.\n")
                        graphLoader.loadGraph() ?: graph
                    }
                } else {
                    onProgress?.invoke("> ⚠️ Project 객체가 제공되지 않아 동적 모듈 로딩을 건너뛰고 전체 데이터를 기반으로 진행합니다.\n")
                    graph
                }
            } else {
                onProgress?.invoke("> ⚠️ 매칭된 서브 모듈이 없습니다. 전체 모듈 데이터를 로딩하여 분석합니다 (Fallback).\n")
                if (project != null) {
                    val graphLoader = project.getService(GraphLoader::class.java)
                    graphLoader.loadGraph() ?: graph
                } else {
                    graph
                }
            }
        } else {
            graph
        }

        // Step 2: 진입점 식별 (Entry Point Identification)
        onProgress?.invoke("> 🎯 진입점(Entry Point) 파일을 식별하고 있습니다...\n")
        val entryPoints = identifyEntryPoints(requirement, keywords, workingGraph, client, onProgress)
        logger.info("RelevanceFilter: 최종 진입점 ${entryPoints.size}개 파일 확정")

        val finalMatches = if (entryPoints.isEmpty()) {
            logger.warn("RelevanceFilter: 진입점 식별 결과 0건, riskScore 상위 파일로 fallback")
            onProgress?.invoke("> ⚠️ 매칭 결과가 없습니다. 주요 파일을 기준으로 분석합니다.\n")
            workingGraph.files.entries
                .sortedByDescending { it.value.riskAssessment.riskScore }
                .take(MAX_FILES)
                .map { (path, node) -> path to node }
        } else {
            // Step 3: BFS 기반 2-depth 탐색 (의존성 확장)
            onProgress?.invoke("> 🕸️ 진입점을 기준으로 의존성 그래프를 확장합니다 (depth=2)...\n")
            val expanded = expandFromEntryPoints(entryPoints, workingGraph, depth = 2)
            logger.info("RelevanceFilter: BFS 확장 후 ${expanded.size}개 파일")
            expanded
        }

        // Step 4: 상한 적용
        val capped = capResults(finalMatches, entryPoints)

        // 필터링된 ProjectGraph 생성
        val filteredFiles = capped.associate { (path, node) -> path to node }
        val filteredRelationships = workingGraph.relationships.filter { rel ->
            filteredFiles.containsKey(rel.source) || filteredFiles.containsKey(rel.target)
        }
        val filteredGraph = workingGraph.copy(
            files = filteredFiles,
            relationships = filteredRelationships
        )

        onProgress?.invoke("> ✅ ${workingGraph.files.size}개 → **${filteredFiles.size}개** 파일로 필터링 완료\n\n")
        logger.info("RelevanceFilter: 최종 ${filteredFiles.size}개 파일 (로드 대상 ${workingGraph.files.size}개)")

        return FilterResult(filteredGraph, keywords)
    }

    // ═══════════════════════════════════════════════════════════════
    // Step 2: 진입점 식별 (Entry Point Identification)
    // ═══════════════════════════════════════════════════════════════

    /**
     * 3단계 Cascade 전략으로 진입점을 식별합니다.
     */
    private fun identifyEntryPoints(
        requirement: String,
        keywords: List<String>,
        graph: ProjectGraph,
        client: LLMClient,
        onProgress: ((String) -> Unit)?
    ): List<Pair<String, FileNode>> {
        val settings = net.ib.ixpert.ops.wuwagent.setting.SettingsState.getInstance()
        val isAnyframe = settings.state.frameworkType == FrameworkType.ANYFRAME_AP

        if (isAnyframe) {
            val anyframeStage1Matches = mutableSetOf<String>()
            graph.files.forEach { (path, node) ->
                // Check class-level localName
                if (node.localName != null && (requirement.contains(node.localName) || node.localName.contains(requirement))) {
                    anyframeStage1Matches.add(path)
                }
                
                // Check serviceEndpoints
                node.serviceEndpoints?.forEach { endpoint ->
                    if (requirement.contains(endpoint.serviceId, ignoreCase = true)) {
                        anyframeStage1Matches.add(path)
                    }
                    if (endpoint.localName != null && (requirement.contains(endpoint.localName) || endpoint.localName.contains(requirement))) {
                        anyframeStage1Matches.add(path)
                    }
                }
            }
            if (anyframeStage1Matches.isNotEmpty()) {
                onProgress?.invoke("> 🎯 [Anyframe Stage 1] @LocalName 또는 ServiceId 매칭을 통해 진입점을 식별했습니다.\n")
                return anyframeStage1Matches.mapNotNull { path -> graph.files[path]?.let { path to it } }
            }
        }

        // [Stage 1] Deterministic Match (Regex / API Path)
        val stage1Matches = mutableSetOf<String>()
        
        // 1-1. API Path matching
        val httpMethods = listOf("GET", "POST", "PUT", "DELETE", "PATCH")
        val hasApiMethod = httpMethods.any { requirement.contains(it, ignoreCase = true) }
        val apiPathPattern = Regex("""/[a-zA-Z0-9_\-\/]+""")
        val paths = apiPathPattern.findAll(requirement).map { it.value }.toList()
        
        if (hasApiMethod || paths.isNotEmpty()) {
            graph.files.forEach { (path, node) ->
                node.apiEndpoints.forEach { endpoint ->
                    if (paths.any { endpoint.path.contains(it, ignoreCase = true) }) {
                        stage1Matches.add(path)
                    }
                }
            }
        }
        
        // 1-2. Explicit Class Name matching (Regex + Impl removal)
        val classPattern = Regex("""(?i)[a-z][a-z0-9]*(Controller|Service|Dao|Repository|Impl|Util)""")
        val classMatches = classPattern.findAll(requirement).map { it.value }.toList()
        
        // translation mapping for korean
        val translatedClasses = mutableListOf<String>()
        if (requirement.contains("서비스")) translatedClasses.add("Service")
        if (requirement.contains("컨트롤러")) translatedClasses.add("Controller")
        if (requirement.contains("레포지토리") || requirement.contains("저장소")) translatedClasses.add("Repository")
        
        val classCandidates = (classMatches + translatedClasses).distinct()
        
        graph.files.forEach { (path, node) ->
            for (candidate in classCandidates) {
                val extractedName = candidate.replace("Impl", "", ignoreCase = true)
                if (extractedName.length > 3) {
                    val baseName = node.className.replace("Impl", "", ignoreCase = true)
                    if (baseName.equals(extractedName, ignoreCase = true) || node.className.equals(extractedName, ignoreCase = true)) {
                        stage1Matches.add(path)
                    }
                }
            }
        }
        
        if (stage1Matches.isNotEmpty()) {
            onProgress?.invoke("> 🎯 [Stage 1] 명시적 단서로 진입점을 찾았습니다.\n")
            return stage1Matches.mapNotNull { path -> graph.files[path]?.let { path to it } }
        }
        
        // [Stage 2] Heuristic Layer-Weighted Scoring
        val candidateScores = mutableMapOf<String, Double>()
        
        val splitKeywords = keywords
            .flatMap { it.split(" ", "-", "_") }
            .map { it.trim().lowercase() }
            .filter { it.length >= 2 && it !in STOP_WORDS }
            .distinct()
        
        graph.files.forEach { (path, node) ->
            var matchScore = 0.0
            
            splitKeywords.forEach { keyword ->
                if (node.className.lowercase().contains(keyword)) matchScore += 3.0
                if (node.apiEndpoints.any { it.path.contains(keyword, ignoreCase = true) }) matchScore += 2.0
                if (node.packageName?.lowercase()?.contains(keyword) == true) matchScore += 1.0
            }
            
            if (matchScore > 0.0) {
                val layerWeight = when (node.layer?.toString()?.uppercase()) {
                    "CONTROLLER", "SERVICE" -> 1.2
                    "REPOSITORY", "DAO" -> 1.0
                    "UTIL", "COMMON" -> 0.5
                    "DTO", "ENTITY", "VO" -> 0.3
                    else -> 0.5
                }
                
                val finalWeight = layerWeight
                
                candidateScores[path] = matchScore * finalWeight
            }
        }
        
        val sortedCandidates = candidateScores.entries.sortedByDescending { it.value }
        if (sortedCandidates.isEmpty()) {
            return emptyList()
        }
        
        val top1 = sortedCandidates.getOrNull(0)
        val top2 = sortedCandidates.getOrNull(1)
        
        // Triggers for Stage 3 (LLM Fallback)
        var triggerLLM = false
        if (top1 != null && top2 != null) {
            val diff = (top1.value - top2.value) / top1.value
            if (diff < 0.20) triggerLLM = true
        }
        
        val significantThreshold = top1!!.value * 0.5
        val significantCandidates = sortedCandidates.count { it.value >= significantThreshold }
        if (significantCandidates > 5) {
            triggerLLM = true
        }
        
        logger.info("RelevanceFilter [Stage 2] 스코어 분포: " +
            sortedCandidates.take(10).joinToString { "${graph.files[it.key]?.className}: ${it.value}" }
        )
        logger.info("RelevanceFilter [Stage 2] significantCandidates=$significantCandidates, triggerLLM=$triggerLLM")
        
        val top3Stage2 = sortedCandidates.take(3).mapNotNull { pathScore -> graph.files[pathScore.key]?.let { pathScore.key to it } }
        
        if (!triggerLLM) {
            onProgress?.invoke("> 🎯 [Stage 2] 스코어링을 통해 진입점을 찾았습니다.\n")
            val threshold = top1!!.value * 0.6
            return sortedCandidates
                .takeWhile { it.value >= threshold }
                .take(3)
                .mapNotNull { graph.files[it.key]?.let { node -> it.key to node } }
        }
        
        // [Stage 3] LLM Fallback
        onProgress?.invoke("> 🤖 진입점이 모호하여 LLM을 통해 최적의 진입점을 판별합니다...\n")
        val candidatePaths = sortedCandidates.take(10).map { it.key }
        val prompt = """
            User requirement: "$requirement"
            
            From the following list of candidate files, select 1 to 2 core entry point files that are most relevant to resolving this requirement.
            - Select ONLY the most essential files.
            - Output format (only output the file paths, one per line):
            <path1>
            <path2>
            
            Candidate list:
            ${candidatePaths.joinToString("\n")}
        """.trimIndent()
        
        try {
            val future = com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService().submit(java.util.concurrent.Callable {
                client.chat("You are an expert system architect.", prompt, 150, null)
            })
            val response = future.get(LLM_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val content = response?.message?.content?.trim() ?: ""
            
            val llmSelected = content.lines().map { it.trim() }.filter { candidatePaths.contains(it) }
            
            if (llmSelected.isNotEmpty()) {
                onProgress?.invoke("> 🎯 [Stage 3] LLM이 진입점을 확정했습니다.\n")
                return llmSelected.mapNotNull { path -> graph.files[path]?.let { path to it } }
            }
        } catch (e: Exception) {
            logger.warn("RelevanceFilter: LLM Fallback 호출 실패", e)
        }
        
        // LLM Fallback 실패 또는 빈 응답시 Stage 2 Top 3 반환
        onProgress?.invoke("> ⚠️ LLM 판별에 실패하여 스코어 상위 3개 파일을 진입점으로 사용합니다.\n")
        return top3Stage2
    }

    // ═══════════════════════════════════════════════════════════════
    // Step 1: 키워드 추출
    // ═══════════════════════════════════════════════════════════════

    /**
     * 한글 요구사항을 LLM 1회 호출로 영문 번역 + 키워드 추출합니다.
     * 출력 형식을 2줄로 제한하여 반복 생성 문제를 방지합니다.
     * 30초 타임아웃을 적용하여 LLM 서버 무응답 시 자동 fallback합니다.
     */
    private fun extractKeywords(
        requirement: String,
        client: LLMClient,
        onProgress: ((String) -> Unit)? = null
    ): List<String> {

        val systemPrompt = """
            Translate the Korean text to English, then extract search keywords.
            
            Output format (2 lines only):
            TRANSLATION: <english sentence>
            KEYWORDS: <comma-separated keywords, 5-10 words>
            
            Keyword rules:
            - Extract ONLY words directly mentioned or closely implied in the sentence
            - Include class/method name fragments mentioned by the user
            - Include direct synonyms only (e.g., verification → validate, verify)
            - Do NOT add loosely related concepts (e.g., session → jwt, oauth, token)
            - Exclude stop words (the, is, are, a, an, to, for, etc.)
            - All lowercase
            - Maximum 10 keywords
            
            Output ONLY these 2 lines. Nothing else. Do NOT add explanations.
        """.trimIndent()

        return try {
            val future = com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService().submit(java.util.concurrent.Callable {
                client.chat(systemPrompt, requirement, KEYWORD_MAX_TOKENS, null)
            })
            val response = future.get(LLM_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val content = response?.message?.content?.trim() ?: ""

            if (content.isBlank()) {
                logger.warn("RelevanceFilter: LLM이 빈 응답을 반환함, fallback 사용")
                onProgress?.invoke("> ⚠️ LLM이 빈 응답을 반환했습니다. 내장 키워드 매핑을 사용합니다.\n")
                return extractFallbackKeywords(requirement)
            }

            logger.info("RelevanceFilter: LLM 응답 → $content")

            // KEYWORDS 라인 파싱
            val keywordsLine = content.lines()
                .firstOrNull { it.startsWith("KEYWORDS:", ignoreCase = true) }
                ?.substringAfter(":")?.trim()

            val keywords = if (!keywordsLine.isNullOrBlank()) {
                keywordsLine
                    .split(",")
                    .map { it.trim().lowercase() }
                    .filter { it.length in 2..30 }
                    .distinct()
                    .take(15)
            } else {
                // KEYWORDS 라인이 없으면 전체 응답에서 comma-split 시도
                parseRawResponse(content)
            }

            if (keywords.isEmpty()) {
                logger.warn("RelevanceFilter: LLM 응답에서 키워드 파싱 실패, fallback 사용")
                onProgress?.invoke("> ⚠️ LLM 응답 파싱에 실패했습니다. 내장 키워드 매핑을 사용합니다.\n")
                return extractFallbackKeywords(requirement)
            }

            logger.info("RelevanceFilter: 키워드 추출 성공 → ${keywords.size}개: $keywords")
            keywords
        } catch (e: TimeoutException) {
            logger.warn("RelevanceFilter: LLM ${LLM_TIMEOUT_SECONDS}초 타임아웃, fallback 사용")
            onProgress?.invoke("> ⚠️ LLM 서버 응답 시간 초과 (${LLM_TIMEOUT_SECONDS}초). 내장 키워드 매핑을 사용합니다.\n")
            extractFallbackKeywords(requirement)
        } catch (e: Exception) {
            val errorMsg = e.cause?.message ?: e.message ?: "알 수 없는 오류"
            logger.warn("RelevanceFilter: LLM 호출 실패, fallback 사용: $errorMsg")
            onProgress?.invoke("> ⚠️ LLM 호출 실패: $errorMsg — 내장 키워드 매핑을 사용합니다.\n")
            extractFallbackKeywords(requirement)
        }
    }

    /**
     * LLM 응답이 예상 포맷이 아닐 때, 전체 응답에서 키워드를 추출하는 시도.
     * comma-separated 응답이거나 문장 형태일 수 있음.
     */
    private fun parseRawResponse(content: String): List<String> {
        // comma가 포함되어 있으면 comma-split
        if (content.contains(",")) {
            return content
                .replace("\n", ",")
                .split(",")
                .map { it.trim().lowercase() }
                .filter { it.length in 2..30 && !it.contains(":") }
                .distinct()
                .take(15)
        }

        // 그 외: 영문 단어만 추출
        return Regex("[a-zA-Z]{3,}")
            .findAll(content)
            .map { it.value.lowercase() }
            .filter { it !in STOP_WORDS }
            .distinct()
            .take(15)
            .toList()
    }

    /**
     * LLM 호출 실패 시 폴백: 요구사항에서 기본 키워드를 추출합니다.
     * 도메인 무관한 범용 CRUD/공통 용어만 포함합니다.
     */
    private fun extractFallbackKeywords(requirement: String): List<String> {
        val keywords = mutableListOf<String>()

        // 1. 영문 단어 그대로 추출
        keywords.addAll(
            Regex("[a-zA-Z]{2,}").findAll(requirement).map { it.value.lowercase() }
        )

        // 2. 한글 → 영문: 범용적인 CRUD/공통 용어만 (도메인 무관)
        for ((ko, en) in COMMON_TERMS) {
            if (requirement.contains(ko)) keywords.add(en)
        }

        return keywords.distinct().also {
            logger.info("RelevanceFilter: Fallback 키워드 → $it")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Step 3: BFS 기반 의존성 확장
    // ═══════════════════════════════════════════════════════════════

    /**
     * 식별된 진입점(들)에서 시작하여 BFS로 의존성을 확장합니다.
     * depth 횟수만큼 dependsOn, dependedBy(유틸성 폭발 방지 조건부), IMPLEMENTS 관계를 탐색합니다.
     */
    private fun expandFromEntryPoints(
        entryPoints: List<Pair<String, FileNode>>,
        graph: ProjectGraph,
        depth: Int
    ): List<Pair<String, FileNode>> {
        val visited = mutableSetOf<String>()
        val result = mutableListOf<Pair<String, FileNode>>()
        var currentLevel = entryPoints.map { it.first }.toSet()
        
        // 인터페이스 관계 추출 (미리 캐싱하여 성능 최적화)
        val implementsRelations = graph.relationships.filter { it.type == RelationshipType.IMPLEMENTS }
        
        for (i in 0..depth) {
            val nextLevel = mutableSetOf<String>()
            
            for (path in currentLevel) {
                if (path !in visited && graph.files.containsKey(path)) {
                    visited.add(path)
                    val node = graph.files[path]!!
                    result.add(path to node)
                    
                    if (i < depth) {
                        // dependsOn 확장
                        node.dependsOn.forEach { nextLevel.add(it) }
                        
                        // dependedBy 확장 (공통 유틸 폭발 방지)
                        if (node.dependedBy.size <= MAX_DEPENDED_BY_FOR_EXPANSION) {
                            node.dependedBy.forEach { nextLevel.add(it) }
                        }
                    }
                }
            }
            
            // 인터페이스-구현체 관계는 깊이에 상관없이 현재 레벨 노드들과 연결시킵니다.
            for (rel in implementsRelations) {
                if (rel.source in visited && rel.target !in visited) {
                    nextLevel.add(rel.target)
                }
                if (rel.target in visited && rel.source !in visited) {
                    nextLevel.add(rel.source)
                }
            }
            
            if (nextLevel.isEmpty()) break
            currentLevel = nextLevel
        }
        
        return result
    }

    // ═══════════════════════════════════════════════════════════════
    // Step 4: 상한 적용
    // ═══════════════════════════════════════════════════════════════

    /**
     * 상한(MAX_FILES)을 적용합니다.
     * 직접 매칭된 파일은 무조건 포함하고, 확장 파일만 riskScore 순으로 자릅니다.
     */
    private fun capResults(
        finalMatches: List<Pair<String, FileNode>>,
        directMatches: List<Pair<String, FileNode>>
    ): List<Pair<String, FileNode>> {
        if (finalMatches.size <= MAX_FILES) return finalMatches

        val directMatchPaths = directMatches.map { it.first }.toSet()

        // 직접 매칭 파일은 무조건 포함
        val mustInclude = finalMatches.filter { it.first in directMatchPaths }

        // 나머지는 riskScore 순으로 상한까지 채움
        val remaining = MAX_FILES - mustInclude.size
        val extras = if (remaining > 0) {
            finalMatches
                .filter { it.first !in directMatchPaths }
                .sortedByDescending { (_, node) -> node.riskAssessment.riskScore }
                .take(remaining)
        } else {
            emptyList()
        }

        return mustInclude + extras
    }

    // ═══════════════════════════════════════════════════════════════
    // 상수 (도메인 무관)
    // ═══════════════════════════════════════════════════════════════

    /** 영어 stop words — 문법 요소로 프로젝트와 무관하게 고정 */
    private val STOP_WORDS = setOf(
        "a", "an", "the", "is", "are", "was", "were", "be", "been", "being",
        "have", "has", "had", "do", "does", "did", "will", "would", "could",
        "should", "may", "might", "shall", "can", "need", "dare", "ought",
        "when", "where", "how", "what", "which", "who", "whom", "whose",
        "if", "then", "else", "than", "that", "this", "these", "those",
        "and", "or", "but", "nor", "not", "so", "yet", "both", "either",
        "to", "for", "in", "on", "at", "by", "with", "from", "of", "about",
        "into", "through", "during", "before", "after", "above", "below",
        "up", "down", "out", "off", "over", "under", "again", "further",
        "also", "just", "only", "very", "too", "quite", "rather",
        "please", "want", "need", "like", "know", "think", "get", "make"
    )

    /**
     * 한글 → 영문 범용 매핑 (도메인 무관한 CRUD/공통 용어만).
     * LLM fallback 시에만 사용됩니다.
     * 도메인 특화 용어(설문, 카드, 주문 등)는 LLM 번역에 위임합니다.
     */
    private val COMMON_TERMS = mapOf(
        "등록" to "register",
        "수정" to "update",
        "삭제" to "delete",
        "조회" to "search",
        "목록" to "list",
        "상세" to "detail",
        "다운로드" to "download",
        "업로드" to "upload",
        "로그인" to "login",
        "로그아웃" to "logout",
        "오류" to "error",
        "실패" to "fail",
        "성공" to "success",
        "추가" to "add",
        "변경" to "change",
        "취소" to "cancel",
        "승인" to "approve",
        "반려" to "reject",
        "처리" to "process",
        "검색" to "search",
        "저장" to "save",
        "전송" to "send"
    )

    // ═══════════════════════════════════════════════════════════════
    // Data class
    // ═══════════════════════════════════════════════════════════════

    data class FilterResult(
        val filteredGraph: ProjectGraph,
        val keywords: List<String>,
        val candidates: List<net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.ScoredCandidate>? = null
    )
}
