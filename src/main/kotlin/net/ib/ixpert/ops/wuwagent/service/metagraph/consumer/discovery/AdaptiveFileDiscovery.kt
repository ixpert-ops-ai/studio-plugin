package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.GraphLoader
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.RelevanceFilter
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Adaptive File Discovery — 기존 [RelevanceFilter]를 대체하는 파일 탐색 시스템.
 *
 * 기존 RelevanceFilter와 동일한 [RelevanceFilter.FilterResult]를 반환하여
 * 호출부([RequirementAnalysisPipeline]) 변경을 최소화합니다.
 *
 * ## 처리 흐름
 * 1. **Stage 0**: 멀티모듈 선택 (기존 RelevanceFilter 로직 재활용)
 * 2. **Stage 1**: QueryAnalyzer → CandidateCollector (정적 매칭)
 * 3. **Stage 2**: LLM Fallback (Stage 1 결과가 빈약할 때만)
 * 4. **결과 조립**: FilterResult 반환
 */
object AdaptiveFileDiscovery {

    private val logger = Logger.getInstance(AdaptiveFileDiscovery::class.java)

    /** LLM fallback 트리거 임계값: 후보가 이 수 이하이면 LLM 호출 */
    private const val LLM_FALLBACK_THRESHOLD = 3

    /** 최종 결과 파일 수 상한 */
    private const val MAX_FILES = 200

    /** LLM 호출 타임아웃 (초) */
    private const val LLM_TIMEOUT_SECONDS = 30L

    /** LLM 키워드 추출 max_tokens */
    private const val KEYWORD_MAX_TOKENS = 150

    // ═══════════════════════════════════════════════════════════════
    // Public API
    // ═══════════════════════════════════════════════════════════════

    /**
     * 요구사항과 관련된 파일만 포함하는 축소된 ProjectGraph를 반환합니다.
     *
     * @param requirement 사용자의 원본 요구사항 (한글/영어/혼합 가능)
     * @param graph 전체 ProjectGraph
     * @param client LLM 클라이언트 (fallback 키워드 추출용)
     * @param project IntelliJ Project (멀티모듈 동적 로딩용, nullable)
     * @param onProgress 진행 상태 콜백
     * @return 필터링된 ProjectGraph + 추출된 키워드
     */
    fun filter(
        primaryReq: String,
        secondaryReq: String,
        graph: ProjectGraph,
        client: LLMClient,
        project: Project?,
        onProgress: ((String) -> Unit)? = null
    ): RelevanceFilter.FilterResult {

        val fullReq = if (secondaryReq.isNotBlank()) "$primaryReq\n$secondaryReq" else primaryReq

        // ── Stage 0: Multi-module Selection ──────────────────
        val workingGraph = resolveWorkingGraph(fullReq, graph, project, onProgress)

        // ── Stage 1: QueryAnalyzer → CandidateCollector ──────
        onProgress?.invoke("> 🔍 [Stage 1] 입력 분석 및 후보 파일 탐색 중...\n")
        val candidateCollector = CandidateCollector(workingGraph)
        val candidates = candidateCollector.collect(primaryReq, secondaryReq)
        val analyzedQuery = candidateCollector.queryAnalyzer.analyze(fullReq)

        logger.info("AdaptiveFileDiscovery: Stage 1 → ${candidates.size}건 후보")

        // 키워드 목록 조립 (디버그 및 결과 표시용)
        val keywords = buildList {
            addAll(analyzedQuery.exactIdentifiers)
            addAll(analyzedQuery.serviceIds)
            addAll(analyzedQuery.urlPatterns)
            addAll(analyzedQuery.englishTokens.take(10))
        }.distinct()

        onProgress?.invoke("> 📋 분석 키워드: ${keywords.take(15).joinToString(", ")}\n")
        onProgress?.invoke("> 🎯 Stage 1 결과: ${candidates.size}건 후보 파일 탐색\n")

        // ── Stage 2: LLM Fallback (후보 부족 시) ─────────────
        val finalCandidatePaths = if (candidates.size <= LLM_FALLBACK_THRESHOLD) {
            logger.info("AdaptiveFileDiscovery: Stage 1 결과 ${candidates.size}건 ≤ ${LLM_FALLBACK_THRESHOLD}, LLM fallback 실행")
            onProgress?.invoke("> ⚠️ 매칭 결과가 부족합니다. LLM 키워드 추출로 보완합니다...\n")

            val llmKeywords = extractKeywordsViaLlm(fullReq, client, onProgress)
            if (llmKeywords.isNotEmpty()) {
                onProgress?.invoke("> 🔑 LLM 키워드: ${llmKeywords.joinToString(", ")}\n")
                val llmMatches = matchByKeywords(llmKeywords, workingGraph)
                logger.info("AdaptiveFileDiscovery: LLM fallback → ${llmMatches.size}건 추가 매칭")

                // 기존 후보 + LLM 매칭 합산
                val merged = candidates.map { it.filePath }.toMutableSet()
                merged.addAll(llmMatches)
                merged
            } else {
                candidates.map { it.filePath }.toSet()
            }
        } else {
            candidates.map { it.filePath }.toSet()
        }

        // ── 결과 조립 ────────────────────────────────────────
        val filteredPaths = if (finalCandidatePaths.isEmpty()) {
            // 완전 실패 시: riskScore 상위 파일로 fallback
            logger.warn("AdaptiveFileDiscovery: 후보 0건, riskScore fallback")
            onProgress?.invoke("> ⚠️ 매칭 결과가 없습니다. 주요 파일을 기준으로 분석합니다.\n")
            workingGraph.files.entries
                .sortedByDescending { it.value.riskAssessment.riskScore }
                .take(MAX_FILES)
                .map { it.key }
                .toSet()
        } else {
            finalCandidatePaths
        }

        val filteredFiles = workingGraph.files.filterKeys { it in filteredPaths }
        val filteredRelationships = workingGraph.relationships.filter { rel ->
            filteredFiles.containsKey(rel.source) || filteredFiles.containsKey(rel.target)
        }
        val filteredGraph = workingGraph.copy(
            files = filteredFiles,
            relationships = filteredRelationships
        )

        onProgress?.invoke("> ✅ ${workingGraph.files.size}개 → **${filteredFiles.size}개** 파일로 필터링 완료\n\n")
        logger.info("AdaptiveFileDiscovery: 최종 ${filteredFiles.size}개 파일 (원본 ${workingGraph.files.size}개)")

        return RelevanceFilter.FilterResult(filteredGraph, keywords, candidates)
    }

    // ═══════════════════════════════════════════════════════════════
    // Stage 0: Multi-module Selection
    // ═══════════════════════════════════════════════════════════════

    /**
     * 멀티모듈 프로젝트의 경우 요구사항 관련 모듈만 동적 로딩합니다.
     * 기존 RelevanceFilter의 Stage 0 로직을 재활용합니다.
     */
    private fun resolveWorkingGraph(
        requirement: String,
        graph: ProjectGraph,
        project: Project?,
        onProgress: ((String) -> Unit)?
    ): ProjectGraph {
        if (graph.graphType != GraphType.MULTI_LEVEL_1 || graph.modules == null) {
            return graph
        }

        onProgress?.invoke("> 📦 [Stage 0] 요구사항 기반으로 관련 서브 모듈을 식별하고 있습니다...\n")
        val reqLower = requirement.lowercase()
        val targetModules = mutableListOf<String>()

        for (module in graph.modules) {
            val nameLower = module.name.lowercase()
            val rootPathLower = module.rootPath.lowercase()

            val isNameMatched = nameLower.split("-", "_", ".").any { reqLower.contains(it) } ||
                                reqLower.split(" ", ",").any { nameLower.contains(it) }
            val isPathMatched = rootPathLower.split("/", "\\").any { token ->
                token.length >= 3 && reqLower.contains(token)
            }
            val isApiMatched = module.publicApis?.any { api ->
                reqLower.contains(api.lowercase())
            } ?: false

            if (isNameMatched || isPathMatched || isApiMatched) {
                targetModules.add(module.name)
            }
        }

        if (targetModules.isNotEmpty() && project != null) {
            onProgress?.invoke("> 🎯 연관 서브 모듈 선정: ${targetModules.joinToString(", ")} (${targetModules.size}/${graph.modules.size}개)\n")
            onProgress?.invoke("> 📥 해당 모듈의 상세 메타데이터(Level 2)를 동적 로딩합니다...\n")
            val graphLoader = project.getService(GraphLoader::class.java)
            val loaded = graphLoader.loadGraph(targetModules = targetModules)
            if (loaded != null) return loaded

            onProgress?.invoke("> ⚠️ 동적 로딩 실패. 전체 모듈 데이터를 폴백 로딩합니다.\n")
            return graphLoader.loadGraph() ?: graph
        }

        if (targetModules.isEmpty()) {
            onProgress?.invoke("> ⚠️ 매칭된 서브 모듈이 없습니다. 전체 데이터를 기반으로 분석합니다.\n")
            if (project != null) {
                val graphLoader = project.getService(GraphLoader::class.java)
                return graphLoader.loadGraph() ?: graph
            }
        }

        return graph
    }

    // ═══════════════════════════════════════════════════════════════
    // Stage 2: LLM Fallback
    // ═══════════════════════════════════════════════════════════════

    /**
     * LLM을 사용하여 한글 요구사항에서 영문 키워드를 추출합니다.
     * 기존 RelevanceFilter의 키워드 추출 로직을 재활용합니다.
     */
    private fun extractKeywordsViaLlm(
        requirement: String,
        client: LLMClient,
        onProgress: ((String) -> Unit)?
    ): List<String> {
        val prompt = """
            You are a keyword extractor for a Java/Spring project.
            
            Extract English technical keywords from the following requirement.
            Return ONLY a comma-separated list of lowercase English keywords.
            Focus on: class names, method names, API paths, domain terms, layer names.
            Do NOT include generic words like "error", "fix", "issue", "problem".
            
            Requirement: $requirement
            
            Keywords:
        """.trimIndent()

        return try {
            val future = java.util.concurrent.CompletableFuture.supplyAsync {
                client.chat(
                    systemPrompt = "You are a concise keyword extractor. Respond ONLY with comma-separated keywords.",
                    userCode = prompt,
                    maxTokens = KEYWORD_MAX_TOKENS
                )
            }

            val response = future.get(LLM_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val content = response?.message?.content?.trim() ?: ""

            if (content.isBlank()) {
                logger.warn("AdaptiveFileDiscovery: LLM이 빈 응답을 반환함, fallback 사용")
                return extractFallbackKeywords(requirement)
            }

            val keywords = content
                .replace(Regex("[\\[\\]\"']"), "")
                .split(",", "\n")
                .map { it.trim().lowercase() }
                .filter { it.length >= 2 && it.all { c -> c.isLetterOrDigit() || c == '_' || c == '-' } }
                .distinct()

            if (keywords.isEmpty()) {
                logger.warn("AdaptiveFileDiscovery: LLM 응답에서 키워드 파싱 실패, fallback 사용")
                extractFallbackKeywords(requirement)
            } else {
                logger.info("AdaptiveFileDiscovery: LLM 키워드 추출 성공 → ${keywords.size}개: $keywords")
                keywords
            }
        } catch (e: TimeoutException) {
            logger.warn("AdaptiveFileDiscovery: LLM ${LLM_TIMEOUT_SECONDS}초 타임아웃, fallback 사용")
            extractFallbackKeywords(requirement)
        } catch (e: Exception) {
            logger.warn("AdaptiveFileDiscovery: LLM 호출 실패, fallback 사용: ${e.message}")
            extractFallbackKeywords(requirement)
        }
    }

    /**
     * LLM 실패 시 정적 한글→영어 매핑 + 영어 토큰 추출로 fallback합니다.
     */
    private fun extractFallbackKeywords(requirement: String): List<String> {
        val keywords = mutableListOf<String>()

        // 영어 단어 직접 추출
        Regex("[a-zA-Z]{3,}").findAll(requirement).forEach {
            keywords.add(it.value.lowercase())
        }

        // 한글 → 영어 공통 매핑
        COMMON_TERMS.forEach { (korean, english) ->
            if (requirement.contains(korean)) {
                keywords.add(english)
            }
        }

        return keywords.distinct().also {
            logger.info("AdaptiveFileDiscovery: Fallback 키워드 → $it")
        }
    }

    /**
     * 키워드 기반으로 그래프에서 파일 경로를 매칭합니다.
     */
    private fun matchByKeywords(keywords: List<String>, graph: ProjectGraph): Set<String> {
        val matched = mutableSetOf<String>()

        for ((path, node) in graph.files) {
            val classNameLower = node.className.lowercase()
            val packageLower = node.packageName?.lowercase() ?: ""

            for (kw in keywords) {
                if (classNameLower.contains(kw) || packageLower.contains(kw)) {
                    matched.add(path)
                    break
                }
                if (node.apiEndpoints.any { it.path.lowercase().contains(kw) }) {
                    matched.add(path)
                    break
                }
            }
        }

        return matched
    }

    /**
     * 한글 → 영문 범용 매핑 (LLM fallback 시에만 사용).
     */
    private val COMMON_TERMS = mapOf(
        "등록" to "register", "수정" to "update", "삭제" to "delete",
        "조회" to "search", "목록" to "list", "상세" to "detail",
        "다운로드" to "download", "업로드" to "upload",
        "로그인" to "login", "로그아웃" to "logout",
        "오류" to "error", "실패" to "fail", "성공" to "success",
        "추가" to "add", "변경" to "change", "취소" to "cancel",
        "승인" to "approve", "반려" to "reject", "처리" to "process",
        "검색" to "search", "저장" to "save", "전송" to "send"
    )
}
