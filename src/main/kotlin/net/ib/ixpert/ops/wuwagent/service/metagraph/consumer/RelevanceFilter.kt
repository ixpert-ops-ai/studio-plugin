package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer

import com.intellij.openapi.diagnostic.Logger
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*
import java.util.concurrent.CompletableFuture
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

        // Step 2: 키워드로 파일 매칭
        onProgress?.invoke("> 🔍 ${graph.files.size}개 파일에서 관련 파일을 검색합니다...\n")
        val directMatches = matchFilesByKeywords(keywords, graph)
        logger.info("RelevanceFilter: 직접 매칭 ${directMatches.size}개 파일")

        // Step 3: 1-depth 확장 (dependsOn/dependedBy + IMPLEMENTS)
        val expanded = expandOneDepth(directMatches, graph)
        logger.info("RelevanceFilter: 1-depth 확장 후 ${expanded.size}개 파일")

        // Step 3.5: 매칭 결과가 너무 적으면 패키지 확장
        val finalMatches = if (expanded.size < MIN_FILES && directMatches.isNotEmpty()) {
            val packageExpanded = expandByPackage(directMatches, graph)
            logger.info("RelevanceFilter: 패키지 확장 후 ${packageExpanded.size}개 파일")
            (expanded + packageExpanded).distinctBy { it.first }
        } else {
            expanded
        }

        // Step 4: 상한 적용 (직접 매칭은 무조건 포함, 나머지는 riskScore 순)
        val capped = capResults(finalMatches, directMatches)

        // Step 4.5: 최종 fallback (매칭 결과 0건이면 riskScore 상위 파일)
        val finalCapped = if (capped.isEmpty()) {
            logger.warn("RelevanceFilter: 매칭 결과 0건, riskScore 상위 파일로 fallback")
            onProgress?.invoke("> ⚠️ 키워드 매칭 결과가 없습니다. 주요 파일을 기준으로 분석합니다.\n")
            graph.files.entries
                .sortedByDescending { it.value.riskAssessment.riskScore }
                .take(MAX_FILES)
                .map { (path, node) -> path to node }
        } else {
            capped
        }

        // 필터링된 ProjectGraph 생성
        val filteredFiles = finalCapped.associate { (path, node) -> path to node }
        val filteredRelationships = graph.relationships.filter { rel ->
            filteredFiles.containsKey(rel.source) || filteredFiles.containsKey(rel.target)
        }
        val filteredGraph = graph.copy(
            files = filteredFiles,
            relationships = filteredRelationships
        )

        onProgress?.invoke("> ✅ ${graph.files.size}개 → **${filteredFiles.size}개** 파일로 필터링 완료\n\n")
        logger.info("RelevanceFilter: 최종 ${filteredFiles.size}개 파일 (원본 ${graph.files.size}개)")

        return FilterResult(filteredGraph, keywords)
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
    // Step 2: 키워드 기반 파일 매칭
    // ═══════════════════════════════════════════════════════════════

    /**
     * 키워드로 파일을 매칭합니다.
     * 매칭 대상: path, className, packageName, apiEndpoints[].path, handlerMethod
     */
    private fun matchFilesByKeywords(
        keywords: List<String>,
        graph: ProjectGraph
    ): List<Pair<String, FileNode>> {
        if (keywords.isEmpty()) return emptyList()

        return graph.files.entries.filter { (path, node) ->
            val searchableText = buildSearchableText(path, node)
            keywords.any { keyword ->
                searchableText.contains(keyword, ignoreCase = true)
            }
        }.map { (path, node) -> path to node }
    }

    /**
     * 파일의 검색 가능 텍스트를 구성합니다.
     * camelCase를 분리하여 개별 단어도 매칭 가능하게 합니다.
     */
    private fun buildSearchableText(path: String, node: FileNode): String {
        return buildString {
            append(path.lowercase())
            append(" ")
            // className을 camelCase 분리하여 추가
            append(node.className.lowercase())
            append(" ")
            append(splitCamelCase(node.className))
            append(" ")
            append(node.packageName?.lowercase() ?: "")
            append(" ")
            // API 엔드포인트 URL 경로도 검색 대상
            for (endpoint in node.apiEndpoints) {
                append(endpoint.path.lowercase())
                append(" ")
                append(endpoint.handlerMethod.lowercase())
                append(" ")
                append(splitCamelCase(endpoint.handlerMethod))
                append(" ")
            }
            // 어노테이션
            for (annotation in node.annotations) {
                append(annotation.lowercase())
                append(" ")
            }
        }
    }

    /**
     * camelCase 문자열을 공백으로 분리합니다.
     * 예: "SurveyServiceImpl" → "survey service impl"
     */
    private fun splitCamelCase(text: String): String {
        return text
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1 $2")
            .lowercase()
    }

    // ═══════════════════════════════════════════════════════════════
    // Step 3: 1-depth 의존성 확장
    // ═══════════════════════════════════════════════════════════════

    /**
     * 직접 매칭된 파일의 dependsOn/dependedBy와 IMPLEMENTS 관계를 1-depth 확장합니다.
     * Interface ↔ Impl 쌍이 반드시 함께 포함되도록 보장합니다.
     * 공통 유틸(dependedBy > 임계값)의 역방향 확장은 제한합니다.
     */
    private fun expandOneDepth(
        directMatches: List<Pair<String, FileNode>>,
        graph: ProjectGraph
    ): List<Pair<String, FileNode>> {
        val matchedPaths = directMatches.map { it.first }.toMutableSet()
        val result = directMatches.toMutableList()

        for ((_, node) in directMatches) {
            // dependsOn 확장
            for (dep in node.dependsOn) {
                if (dep !in matchedPaths && graph.files.containsKey(dep)) {
                    matchedPaths.add(dep)
                    result.add(dep to graph.files[dep]!!)
                }
            }
            // dependedBy 확장 (공통 유틸 폭발 방지)
            if (node.dependedBy.size <= MAX_DEPENDED_BY_FOR_EXPANSION) {
                for (dep in node.dependedBy) {
                    if (dep !in matchedPaths && graph.files.containsKey(dep)) {
                        matchedPaths.add(dep)
                        result.add(dep to graph.files[dep]!!)
                    }
                }
            }
        }

        // IMPLEMENTS 관계 확장: Interface의 Impl이 누락되지 않도록
        val implementsRelations = graph.relationships.filter {
            it.type == RelationshipType.IMPLEMENTS
        }
        for (rel in implementsRelations) {
            val sourceInSet = rel.source in matchedPaths
            val targetInSet = rel.target in matchedPaths
            if (sourceInSet && !targetInSet && graph.files.containsKey(rel.target)) {
                matchedPaths.add(rel.target)
                result.add(rel.target to graph.files[rel.target]!!)
            }
            if (targetInSet && !sourceInSet && graph.files.containsKey(rel.source)) {
                matchedPaths.add(rel.source)
                result.add(rel.source to graph.files[rel.source]!!)
            }
        }

        return result
    }

    // ═══════════════════════════════════════════════════════════════
    // Step 3.5: 패키지 확장 (매칭 결과 부족 시)
    // ═══════════════════════════════════════════════════════════════

    /**
     * 매칭 결과가 MIN_FILES 미만일 때, 매칭된 파일의 패키지 내 다른 파일을 포함합니다.
     * 예: survey 패키지의 한 파일만 매칭되면 → 같은 패키지의 모든 파일 포함
     */
    private fun expandByPackage(
        directMatches: List<Pair<String, FileNode>>,
        graph: ProjectGraph
    ): List<Pair<String, FileNode>> {
        val matchedPaths = directMatches.map { it.first }.toSet()

        // 매칭된 파일들의 패키지 추출
        val packages = directMatches.mapNotNull { it.second.packageName }.toSet()

        return graph.files.entries
            .filter { (path, node) ->
                path !in matchedPaths && node.packageName in packages
            }
            .map { (path, node) -> path to node }
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
        val keywords: List<String>
    )
}
