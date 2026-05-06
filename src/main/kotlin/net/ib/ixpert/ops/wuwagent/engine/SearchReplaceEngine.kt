package net.ib.ixpert.ops.wuwagent.engine

import org.slf4j.LoggerFactory
import java.io.File

/**
 * LLM이 출력한 SEARCH/REPLACE 블록을 파싱하고
 * 원본 소스에 적용하는 엔진
 */
class SearchReplaceEngine {
    private val logger = LoggerFactory.getLogger(SearchReplaceEngine::class.java)

    companion object {
        // SEARCH/REPLACE 블록 감지 패턴
        private val BLOCK_PATTERN = Regex(
            """<<<<<<< SEARCH\n(.*?)\n=======\n(.*?)\n>>>>>>> REPLACE""",
            RegexOption.DOT_MATCHES_ALL
        )

        // 파일 지시자 패턴
        private val FILE_HEADER_PATTERN = Regex(
            """\[FILE:\s*(.+?)\s*]"""
        )

        // 매칭 허용 공백 정규화
        private val WHITESPACE_NORMALIZE = Regex("""\s+""")

        // 최대 fuzzy 매칭 시도 거리
        private const val MAX_FUZZY_DISTANCE = 3
    }

    // ═══════════════════════════════════════════════════════════════
    // 데이터 모델
    // ═══════════════════════════════════════════════════════════════

    data class EditBlock(
        val filePath: String,
        val searchContent: String,
        val replaceContent: String,
        val blockIndex: Int
    )

    data class ApplyResult(
        val filePath: String,
        val success: Boolean,
        val updatedContent: String?,
        val message: String,
        val appliedBlocks: Int,
        val failedBlocks: List<FailedBlock>
    )

    data class FailedBlock(
        val blockIndex: Int,
        val searchSnippet: String,
        val reason: String
    )

    // ═══════════════════════════════════════════════════════════════
    // 파싱: LLM 출력 → EditBlock 리스트
    // ═══════════════════════════════════════════════════════════════

    /**
     * LLM 응답에서 SEARCH/REPLACE 블록을 추출한다.
     * 파일 헤더 [FILE: path]가 있으면 해당 경로를 사용하고,
     * 없으면 defaultFilePath를 사용한다.
     */
    fun parse(llmResponse: String, defaultFilePath: String? = null): List<EditBlock> {
        val blocks = mutableListOf<EditBlock>()
        val lines = llmResponse.lines()

        var currentFilePath = defaultFilePath ?: ""
        var blockIndex = 0

        // 라인별로 스캔하며 FILE 헤더와 SEARCH/REPLACE 블록을 추출
        var i = 0
        while (i < lines.size) {
            val line = lines[i]

            // [FILE: ...] 헤더 감지
            val fileMatch = FILE_HEADER_PATTERN.find(line)
            if (fileMatch != null) {
                currentFilePath = fileMatch.groupValues[1].trim()
                i++
                continue
            }

            // <<<<<<< SEARCH 시작 감지
            if (line.trim() == "<<<<<<< SEARCH") {
                val searchLines = mutableListOf<String>()
                val replaceLines = mutableListOf<String>()
                var inReplace = false
                i++

                while (i < lines.size) {
                    val currentLine = lines[i]
                    when {
                        currentLine.trim() == "=======" && !inReplace -> {
                            inReplace = true
                        }
                        currentLine.trim() == ">>>>>>> REPLACE" -> {
                            break
                        }
                        inReplace -> {
                            replaceLines.add(currentLine)
                        }
                        else -> {
                            searchLines.add(currentLine)
                        }
                    }
                    i++
                }

                if (searchLines.isNotEmpty() || replaceLines.isNotEmpty()) {
                    blocks.add(
                        EditBlock(
                            filePath = currentFilePath,
                            searchContent = searchLines.joinToString("\n"),
                            replaceContent = replaceLines.joinToString("\n"),
                            blockIndex = blockIndex++
                        )
                    )
                }
            }

            i++
        }

        logger.info("[Parsing] " + blocks.size + " SEARCH/REPLACE blocks extracted")
        return blocks
    }

    // ═══════════════════════════════════════════════════════════════
    // 적용: EditBlock 리스트 → 원본에 merge
    // ═══════════════════════════════════════════════════════════════

    /**
     * 주어진 EditBlock 리스트를 원본 소스에 순차 적용한다.
     * 같은 파일에 여러 블록이 있으면 순서대로 적용한다.
     */
    fun apply(originalSource: String, blocks: List<EditBlock>): ApplyResult {
        if (blocks.isEmpty()) {
            return ApplyResult(
                filePath = blocks.firstOrNull()?.filePath ?: "",
                success = true,
                updatedContent = originalSource,
                message = "적용할 블록 없음",
                appliedBlocks = 0,
                failedBlocks = emptyList()
            )
        }

        var current = originalSource
        var appliedCount = 0
        val failed = mutableListOf<FailedBlock>()

        for (block in blocks) {
            val result = applySingleBlock(current, block)
            if (result.first) {
                current = result.second
                appliedCount++
                logger.info("[적용 성공] 블록 #${block.blockIndex}: ${block.searchContent.lines().first().take(50)}...")
            } else {
                failed.add(
                    FailedBlock(
                        blockIndex = block.blockIndex,
                        searchSnippet = block.searchContent.lines().take(3).joinToString("\n"),
                        reason = result.second
                    )
                )
                logger.warn("[적용 실패] 블록 #${block.blockIndex}: ${result.second}")
            }
        }

        val success = failed.isEmpty()
        val message = if (success) {
            appliedCount.toString() + " blocks applied successfully"
        } else {
            appliedCount.toString() + " applied, " + failed.size + " failed"
        }

        return ApplyResult(
            filePath = blocks.first().filePath,
            success = success,
            updatedContent = current,
            message = message,
            appliedBlocks = appliedCount,
            failedBlocks = failed
        )
    }

    /**
     * 단일 SEARCH/REPLACE 블록 적용
     */
    private fun applySingleBlock(source: String, block: EditBlock): Pair<Boolean, String> {
        val search = block.searchContent
        val replace = block.replaceContent

        // === 전략 1: 정확 매칭 ===
        if (source.contains(search)) {
            val updated = source.replaceFirst(search, replace)
            return Pair(true, updated)
        }

        // === 전략 2: 줄끝 공백 제거 후 매칭 ===
        val searchTrimmed = search.lines().joinToString("\n") { it.trimEnd() }
        val sourceTrimmed = source.lines().joinToString("\n") { it.trimEnd() }

        val trimmedIdx = sourceTrimmed.indexOf(searchTrimmed)
        if (trimmedIdx != -1) {
            val sourceLines = source.lines()
            val searchLines = search.lines()
            val matchStart = findLineMatchStart(sourceLines, searchLines)
            if (matchStart != -1) {
                val updated = replaceLines(sourceLines, matchStart, searchLines.size, replace)
                return Pair(true, updated)
            }
        }

        // === 전략 3: 공백 완전 정규화 매칭 ===
        val sourceLinesList = source.lines()
        val fuzzyMatch = findFuzzyMatch(sourceLinesList, block.searchContent.lines())
        if (fuzzyMatch != null) {
            val updated = replaceLines(sourceLinesList, fuzzyMatch.first, fuzzyMatch.second, replace)
            return Pair(true, updated)
        }

        // === 전략 4: 첫/마지막 줄 앵커 매칭 ===
        val anchorMatch = findAnchorMatch(sourceLinesList, block.searchContent.lines())
        if (anchorMatch != null) {
            val updated = replaceLines(sourceLinesList, anchorMatch.first, anchorMatch.second, replace)
            return Pair(true, updated)
        }

        return Pair(false, "매칭 실패: SEARCH 블록을 원본에서 찾을 수 없음")
    }

    // ═══════════════════════════════════════════════════════════════
    // 매칭 헬퍼
    // ═══════════════════════════════════════════════════════════════

    private fun findLineMatchStart(sourceLines: List<String>, searchLines: List<String>): Int {
        if (searchLines.isEmpty()) return -1
        val searchTrimmed = searchLines.map { it.trimEnd() }

        outer@ for (i in 0..sourceLines.size - searchLines.size) {
            for (j in searchLines.indices) {
                if (sourceLines[i + j].trimEnd() != searchTrimmed[j]) {
                    continue@outer
                }
            }
            return i
        }
        return -1
    }

    private fun findFuzzyMatch(sourceLines: List<String>, searchLines: List<String>): Pair<Int, Int>? {
        if (searchLines.isEmpty()) return null

        val searchNormalized = searchLines.map { normalizeWhitespace(it) }.filter { it.isNotBlank() }
        if (searchNormalized.isEmpty()) return null

        val sourceNonBlank = sourceLines.mapIndexed { idx, line -> idx to normalizeWhitespace(line) }
            .filter { it.second.isNotBlank() }

        outer@ for (i in 0..sourceNonBlank.size - searchNormalized.size) {
            var mismatches = 0
            for (j in searchNormalized.indices) {
                if (sourceNonBlank[i + j].second != searchNormalized[j]) {
                    mismatches++
                    if (mismatches > MAX_FUZZY_DISTANCE) continue@outer
                }
            }
            val startLine = sourceNonBlank[i].first
            val endLine = sourceNonBlank[i + searchNormalized.size - 1].first
            return Pair(startLine, endLine - startLine + 1)
        }
        return null
    }

    private fun findAnchorMatch(sourceLines: List<String>, searchLines: List<String>): Pair<Int, Int>? {
        if (searchLines.size < 2) return null
        val firstLine = searchLines.first().trim()
        val lastLine = searchLines.last().trim()
        if (firstLine.isBlank() || lastLine.isBlank()) return null

        val candidates = sourceLines.indices.filter { sourceLines[it].trim() == firstLine }
        for (startIdx in candidates) {
            val maxEnd = minOf(startIdx + searchLines.size + MAX_FUZZY_DISTANCE, sourceLines.size)
            for (endIdx in (startIdx + searchLines.size - MAX_FUZZY_DISTANCE - 1) until maxEnd) {
                if (endIdx >= 0 && endIdx < sourceLines.size && sourceLines[endIdx].trim() == lastLine) {
                    val lineCount = endIdx - startIdx + 1
                    if (Math.abs(lineCount - searchLines.size) <= MAX_FUZZY_DISTANCE) {
                        return Pair(startIdx, lineCount)
                    }
                }
            }
        }
        return null
    }

    // ═══════════════════════════════════════════════════════════════
    // 유틸리티
    // ═══════════════════════════════════════════════════════════════

    private fun normalizeWhitespace(text: String): String {
        return text.trim().replace(WHITESPACE_NORMALIZE, " ")
    }

    private fun replaceLines(sourceLines: List<String>, startLine: Int, lineCount: Int, replacement: String): String {
        val before = sourceLines.take(startLine)
        val after = sourceLines.drop(startLine + lineCount)
        val replaceLines = replacement.lines()
        return (before + replaceLines + after).joinToString("\n")
    }

    // ═══════════════════════════════════════════════════════════════
    // 검증
    // ═══════════════════════════════════════════════════════════════

    fun validate(originalSource: String, updatedSource: String): ValidationResult {
        val warnings = mutableListOf<String>()
        val errors = mutableListOf<String>()

        // 1. Brace Balance
        val openBraces = updatedSource.count { it == '{' }
        val closeBraces = updatedSource.count { it == '}' }
        if (openBraces != closeBraces) {
            errors.add("Brace mismatch: { = " + openBraces + ", } = " + closeBraces)
        }

        // 2. Change Ratio
        val originalLines = originalSource.lines().size
        val updatedLines = updatedSource.lines().size
        if (originalLines > 0) {
            val ratio = (updatedLines.toDouble() - originalLines) / originalLines
            if (ratio < -0.5) {
                errors.add("Content reduced by more than 50%: " + originalLines + " -> " + updatedLines)
            }
        }

        // 3. Package Check
        val originalPackage = Regex("""^package\s+.+;""", RegexOption.MULTILINE).find(originalSource)?.value
        if (originalPackage != null && !updatedSource.contains(originalPackage)) {
            errors.add("Package declaration missing")
        }

        return ValidationResult(errors.isEmpty(), errors, warnings)
    }

    data class ValidationResult(val valid: Boolean, val errors: List<String>, val warnings: List<String>)

    // ═══════════════════════════════════════════════════════════════
    // 실패 복구: 재시도용 컨텍스트 생성
    // ═══════════════════════════════════════════════════════════════

    fun buildRetryContext(failedBlocks: List<FailedBlock>): String = buildString {
        append("Some SEARCH/REPLACE blocks failed to apply. Please try again with exact matches.\n\n")
        failedBlocks.forEach { failed ->
            append("### Block #" + failed.blockIndex + " failed\n")
            append("- Reason: " + failed.reason + "\n")
            append("- Tried SEARCH snippet:\n```java\n" + failed.searchSnippet + "\n```\n\n")
        }
    }
}
