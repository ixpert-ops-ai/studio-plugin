package net.ib.ixpert.ops.wuwagent.cache

import org.slf4j.LoggerFactory
import java.io.File

/**
 * /docs 폴더의 분석 문서를 캐싱하고 검색하는 클래스
 */
class DocCache(private val docsRoot: File) {
    private val logger = LoggerFactory.getLogger(DocCache::class.java)

    companion object {
        // 메서드명 추출 시 제외할 키워드
        private val JAVA_KEYWORDS_FOR_DOC = setOf(
            "extends", "implements", "return", "import", "package",
            "public", "private", "protected", "static", "final",
            "abstract", "class", "interface", "throws", "catch",
            "default", "string", "boolean", "integer", "object",
            "override", "throws", "synchronized", "volatile"
        )

        // 최대 문서 파일 크기 (50KB)
        const val MAX_DOC_FILE_SIZE = 50_000L
    }

    data class DocEntry(
        val path: String,       // docs 내 상대 경로
        val fileName: String,   // 원본 소스 파일명 (매핑용)
        val content: String     // 문서 내용
    )

    private val entries: List<DocEntry> by lazy { loadDocs() }

    private fun loadDocs(): List<DocEntry> {
        if (!docsRoot.exists() || !docsRoot.isDirectory) {
            logger.warn("Docs root not found or not a directory: ${docsRoot.absolutePath}")
            return emptyList()
        }

        logger.info("Loading docs from: ${docsRoot.absolutePath}")
        return docsRoot.walkTopDown()
            .filter { it.isFile && (it.extension == "md" || it.extension == "txt") }
            .filter { file ->
                if (file.length() > MAX_DOC_FILE_SIZE) {
                    logger.warn("[DocCache] 파일 크기 초과, 스킵: ${file.name} (${file.length()} bytes)")
                    false
                } else {
                    true
                }
            }
            .map { file ->
                val relativePath = file.relativeTo(docsRoot).path
                // 파일명에서 소스 파일명 추출 (예: SurveyService.java.md -> SurveyService)
                val sourceFileName = file.nameWithoutExtension.let { name ->
                    name.removeSuffix(".java")
                        .removeSuffix(".kt")
                        .let { n ->
                            when {
                                n.endsWith("_doc") -> n.removeSuffix("_doc")
                                n.endsWith("_analysis") -> n.removeSuffix("_analysis")
                                else -> n
                            }
                        }
                }
                DocEntry(
                    path = relativePath,
                    fileName = sourceFileName,
                    content = file.readText(Charsets.UTF_8)
                )
            }
            .toList()
            .also { logger.info("Loaded ${it.size} doc entries") }
    }

    /**
     * 소스 파일 경로로 정확히 매칭되는 문서 검색
     */
    fun findByPath(sourcePath: String): DocEntry? {
        val sourceFileName = File(sourcePath).nameWithoutExtension.lowercase()
        return entries.firstOrNull { entry ->
            entry.fileName.lowercase() == sourceFileName
        }
    }

    /**
     * 소스 파일명으로 퍼지 매칭
     */
    fun findByFileName(fileName: String): DocEntry? {
        val baseName = File(fileName).nameWithoutExtension.lowercase()

        // 1. 정확한 매칭
        val exact = entries.firstOrNull { it.fileName.lowercase() == baseName }
        if (exact != null) return exact

        // 2. 포함 관계 매칭 (fuzzy)
        return entries.firstOrNull { entry ->
            entry.fileName.lowercase().contains(baseName) ||
                baseName.contains(entry.fileName.lowercase())
        }
    }

    /**
     * ★ Phase 2a용 – 소스 파일에 대한 1줄 요약 생성
     * 역할 정보와 주요 메서드명을 조합하여 반환합니다.
     */
    fun getOneLinerFor(sourcePath: String): String {
        // ★ 수정 1: nameWithoutExtension으로 일관성 유지
        val doc = findByPath(sourcePath)
            ?: findByFileName(File(sourcePath).nameWithoutExtension)
            ?: return ""

        // [역할] 줄 추출
        val roleLine = doc.content.lines()
            .firstOrNull { line ->
                val l = line.trim()
                l.startsWith("[역할]") || l.startsWith("역할:") ||
                    l.startsWith("- 역할:") || l.startsWith("**역할**")
            }
            ?.replace(Regex("^[\\[\\-*]*\\s*역할[\\]*:：]?\\s*"), "")
            ?.replace("**", "")
            ?.trim()

        // 메서드명 추출 (최대 3개)
        val methods = extractMethodNamesFromDoc(doc.content).take(3)

        return when {
            !roleLine.isNullOrBlank() && methods.isNotEmpty() -> {
                "${roleLine.take(40)} (${methods.joinToString(", ")})"
            }
            !roleLine.isNullOrBlank() -> {
                roleLine.take(60)
            }
            methods.isNotEmpty() -> {
                "주요 메서드: ${methods.joinToString(", ")}"
            }
            else -> {
                // 첫 번째 의미 있는 줄 (헤더나 파일 정보 제외)
                doc.content.lines()
                    .firstOrNull {
                        it.isNotBlank() && !it.startsWith("#") &&
                            !it.startsWith("[파일]") && !it.startsWith("파일:")
                    }
                    ?.take(50)
                    ?: ""
            }
        }
    }

    /**
     * 문서 내용에서 메서드명 추출
     * ★ 수정 2: Java 키워드 제외 필터 추가
     */
    private fun extractMethodNamesFromDoc(content: String): List<String> {
        val patterns = listOf(
            Regex("[-*]\\s*`?([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(`?"),
            Regex("→\\s*`?([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(`?"),
            Regex("\\b((?:find|get|set|insert|update|delete|select|save|create|export|import|send|check|validate)\\w+)\\s*\\(")
        )

        val methodNames = mutableSetOf<String>()
        for (pattern in patterns) {
            pattern.findAll(content).forEach { match ->
                val name = match.groupValues[1]
                if (name.length >= 3 
                    && name[0].isLowerCase() 
                    && name.lowercase() !in JAVA_KEYWORDS_FOR_DOC) {
                    methodNames.add(name)
                }
            }
        }

        return methodNames.toList()
    }

    /**
     * 캐시된 문서 수 반환
     */
    fun size(): Int = entries.size

    /**
     * 전체 문서 목록 반환
     */
    fun allEntries(): List<DocEntry> = entries
}
