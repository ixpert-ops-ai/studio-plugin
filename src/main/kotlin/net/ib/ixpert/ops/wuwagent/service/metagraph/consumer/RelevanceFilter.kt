package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer

import com.intellij.openapi.diagnostic.Logger
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*

/**
 * 요구사항 기반 파일 관련성 필터.
 *
 * 70MB+ 규모의 MetaGraph에서 요구사항과 관련된 파일만 추려내어
 * LLM 토큰 사용량을 대폭 절감합니다.
 *
 * 흐름:
 * 1. 한글 요구사항 → LLM 경량 호출로 영문 키워드 추출
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
        val keywords = extractKeywords(requirement, client)
        logger.info("RelevanceFilter: 추출된 키워드 → $keywords")
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

        // Step 4: 상한 적용 (riskScore 순 정렬로 중요한 파일 우선)
        val capped = if (finalMatches.size > MAX_FILES) {
            finalMatches.sortedByDescending { (_, node) ->
                node.riskAssessment.riskScore
            }.take(MAX_FILES)
        } else {
            finalMatches
        }

        // 필터링된 ProjectGraph 생성
        val filteredFiles = capped.associate { (path, node) -> path to node }
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
     * 한글 요구사항을 LLM 경량 호출로 영문 키워드 리스트로 변환합니다.
     * ~50토큰 이내의 매우 짧은 응답을 기대합니다.
     */
    private fun extractKeywords(requirement: String, client: LLMClient): List<String> {
        val systemPrompt = """
            You are a keyword extractor for a Spring Boot Java project.
            Extract 5-15 English keywords from the user's requirement.
            Include synonyms and related technical terms.
            
            Rules:
            1. Output ONLY comma-separated lowercase keywords, nothing else.
            2. Include both domain terms (survey, result) and technical terms (csv, download, export).
            3. Include synonyms (e.g., survey → questionnaire, download → export).
            4. Include potential class name fragments (e.g., SurveyService → survey, service).
            5. Do NOT include generic Java keywords (class, public, void, etc.).
            
            Example input: "설문 결과 CSV 다운로드 기능 구현"
            Example output: survey,result,csv,download,export,excel,questionnaire,list,data
        """.trimIndent()

        return try {
            val response = client.chat(systemPrompt, requirement, null)
            val rawKeywords = response?.message?.content?.trim() ?: ""

            rawKeywords
                .replace("\n", ",")
                .split(",")
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() && it.length >= 2 }
                .distinct()
                .also { logger.info("RelevanceFilter: LLM 키워드 추출 성공 → ${it.size}개") }
        } catch (e: Exception) {
            logger.warn("RelevanceFilter: LLM 키워드 추출 실패, fallback 사용: ${e.message}")
            // Fallback: 요구사항에서 영문 단어만 추출 + 한글을 기본 키워드로 분해
            extractFallbackKeywords(requirement)
        }
    }

    /**
     * LLM 호출 실패 시 폴백: 요구사항에서 기본 키워드를 추출합니다.
     * 영문 단어 + 한글 명사(조사 제거) 기반.
     */
    private fun extractFallbackKeywords(requirement: String): List<String> {
        val keywords = mutableListOf<String>()

        // 영문 단어 추출
        val englishWords = Regex("[a-zA-Z]{2,}").findAll(requirement)
        keywords.addAll(englishWords.map { it.value.lowercase() })

        // 한글에서 일반적인 기술 키워드 매핑
        val koreanToEnglish = mapOf(
            "설문" to listOf("survey", "questionnaire"),
            "결과" to listOf("result", "data"),
            "다운로드" to listOf("download", "export"),
            "업로드" to listOf("upload", "import"),
            "엑셀" to listOf("excel", "xls", "xlsx"),
            "목록" to listOf("list", "select"),
            "등록" to listOf("insert", "create", "register"),
            "수정" to listOf("update", "modify", "edit"),
            "삭제" to listOf("delete", "remove"),
            "조회" to listOf("select", "find", "search", "query"),
            "로그인" to listOf("login", "auth", "sign"),
            "권한" to listOf("auth", "permission", "role"),
            "관리" to listOf("manage", "admin"),
            "통계" to listOf("statistics", "stat", "chart"),
            "게시판" to listOf("board", "post", "article"),
            "파일" to listOf("file", "attachment"),
            "이미지" to listOf("image", "photo", "picture"),
            "메일" to listOf("mail", "email", "smtp"),
            "알림" to listOf("notification", "alert", "alarm"),
            "배치" to listOf("batch", "schedule", "job")
        )

        for ((korean, english) in koreanToEnglish) {
            if (requirement.contains(korean)) {
                keywords.addAll(english)
            }
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
     * 매칭 대상: path, className, packageName, apiEndpoints[].path
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
     * path, className, packageName, apiEndpoints 경로를 결합합니다.
     */
    private fun buildSearchableText(path: String, node: FileNode): String {
        return buildString {
            append(path.lowercase())
            append(" ")
            append(node.className.lowercase())
            append(" ")
            append(node.packageName?.lowercase() ?: "")
            append(" ")
            // API 엔드포인트 URL 경로도 검색 대상
            for (endpoint in node.apiEndpoints) {
                append(endpoint.path.lowercase())
                append(" ")
                append(endpoint.handlerMethod.lowercase())
                append(" ")
            }
            // 어노테이션 (@RequestMapping 등의 URL 포함)
            for (annotation in node.annotations) {
                append(annotation.lowercase())
                append(" ")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Step 3: 1-depth 의존성 확장
    // ═══════════════════════════════════════════════════════════════

    /**
     * 직접 매칭된 파일의 dependsOn/dependedBy와 IMPLEMENTS 관계를 1-depth 확장합니다.
     * Interface ↔ Impl 쌍이 반드시 함께 포함되도록 보장합니다.
     */
    private fun expandOneDepth(
        directMatches: List<Pair<String, FileNode>>,
        graph: ProjectGraph
    ): List<Pair<String, FileNode>> {
        val matchedPaths = directMatches.map { it.first }.toMutableSet()
        val result = directMatches.toMutableList()

        for ((path, node) in directMatches) {
            // dependsOn 확장
            for (dep in node.dependsOn) {
                if (dep !in matchedPaths && graph.files.containsKey(dep)) {
                    matchedPaths.add(dep)
                    result.add(dep to graph.files[dep]!!)
                }
            }
            // dependedBy 확장
            for (dep in node.dependedBy) {
                if (dep !in matchedPaths && graph.files.containsKey(dep)) {
                    matchedPaths.add(dep)
                    result.add(dep to graph.files[dep]!!)
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
    // Data class
    // ═══════════════════════════════════════════════════════════════

    data class FilterResult(
        val filteredGraph: ProjectGraph,
        val keywords: List<String>
    )
}
