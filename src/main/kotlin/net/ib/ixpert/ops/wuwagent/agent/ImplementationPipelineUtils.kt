package net.ib.ixpert.ops.wuwagent.agent

import net.ib.ixpert.ops.wuwagent.agent.ImplementationPipeline
import net.ib.ixpert.ops.wuwagent.agent.TargetFileSpec
import java.io.File

/**
 * ImplementationPipeline's pure logic extracted for testability.
 * No dependency on Project, OllamaClient, or DocCache.
 */
object ImplementationPipelineUtils {

    // ═══════════════════════════════════════════════════════════════
    // Constants (same as ImplementationPipeline)
    // ═══════════════════════════════════════════════════════════════

    const val SMALL_FILE_THRESHOLD = 150
    const val LARGE_FILE_THRESHOLD = 500
    const val TRUNCATION_LINE_LIMIT = 600
    const val TRUNCATION_CONTEXT_LINES = 25
    const val BASE_MAX_LENGTH = 15000
    const val SEARCH_REPLACE_MAX_LENGTH = 8000
    const val DTO_MAX_LENGTH = 3000

    // ═══════════════════════════════════════════════════════════════
    // Strategy Decision
    // ═══════════════════════════════════════════════════════════════

    fun decideStrategy(
        targetFile: TargetFileSpec,
        lineCount: Int,
        originalSourceContainsInterface: Boolean = false
    ): ImplementationPipeline.EditStrategy {
        val path = (targetFile.path as String).lowercase()

        if (isDtoFile(path)) {
            return ImplementationPipeline.EditStrategy.DTO_ONLY
        }

        if (targetFile.type == "create") {
            return ImplementationPipeline.EditStrategy.WHOLE
        }

        if (path.contains("interface") || originalSourceContainsInterface) {
            return ImplementationPipeline.EditStrategy.WHOLE
        }

        if (lineCount <= SMALL_FILE_THRESHOLD) {
            return ImplementationPipeline.EditStrategy.WHOLE
        }

        return ImplementationPipeline.EditStrategy.ACTION_BASED
    }

    // ═══════════════════════════════════════════════════════════════
    // Layer Weight
    // ═══════════════════════════════════════════════════════════════

    fun getLayerWeight(path: String): Int {
        val lower = path.lowercase()
        return when {
            lower.contains("/dao/") || lower.contains("/repository/") || lower.contains("/entity/") -> 1
            lower.contains("/service/") || lower.contains("/biz/") -> 2
            lower.contains("/controller/") || lower.contains("/view/") || lower.contains("/api/") -> 3
            lower.contains("/dto/") || lower.contains("/vo/") || lower.endsWith("dto.java") || lower.endsWith("vo.java") -> 4
            lower.contains("/util/") || lower.contains("/config/") || lower.contains("/common/") -> 5
            else -> 6
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Max Length Calculation
    // ═══════════════════════════════════════════════════════════════

    fun calculateWholeMaxLength(path: String, lineCount: Int): Int {
        val layerWeight = getLayerWeight(path)
        return when {
            layerWeight <= 2 -> (BASE_MAX_LENGTH * 1.3).toInt()
            lineCount > 500 -> (BASE_MAX_LENGTH * 1.2).toInt()
            else -> BASE_MAX_LENGTH
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Field Extraction
    // ═══════════════════════════════════════════════════════════════

    fun extractFieldNames(source: String): List<String> {
        val pattern = Regex("""private\s+\S+\s+(\w+)\s*;""")
        return pattern.findAll(source).map { it.groupValues[1] }.toList()
    }

    // ═══════════════════════════════════════════════════════════════
    // Target Method Name Extraction
    // ═══════════════════════════════════════════════════════════════

    fun extractTargetMethodNames(requirement: String, description: String): List<String> {
        val combined = "$requirement $description"
        val methodPattern = Regex("""([a-z][a-zA-Z0-9]*(?:Csv|Download|Export|Result|Survey|List|All|ForCsv))\b""")
        val found = methodPattern.findAll(combined).map { it.groupValues[1] }.distinct().toList()
        val verbPattern = Regex("""(select|insert|update|delete|find|get|create|download|export)\w*""", RegexOption.IGNORE_CASE)
        val verbs = verbPattern.findAll(combined).map { it.value }.distinct().toList()
        return (found + verbs).distinct()
    }

    // ═══════════════════════════════════════════════════════════════
    // Truncation
    // ═══════════════════════════════════════════════════════════════

    fun truncateForLargeFile(
        original: String,
        targetMethodNames: List<String>,
        contextLines: Int = TRUNCATION_CONTEXT_LINES
    ): String {
        val lines = original.lines()
        if (lines.size <= TRUNCATION_LINE_LIMIT) return original

        val keepRanges = mutableListOf<IntRange>()

        val classStartIdx = lines.indexOfFirst {
            it.trimStart().let { l ->
                l.startsWith("public class") || l.startsWith("public abstract class") ||
                        l.startsWith("@Component") || l.startsWith("@Service") ||
                        l.startsWith("@Repository") || l.startsWith("@Controller")
            }
        }
        val headerEnd = minOf((classStartIdx + 5).coerceAtLeast(30), lines.lastIndex)
        keepRanges.add(0..headerEnd)

        for ((i, line) in lines.withIndex()) {
            if (targetMethodNames.any { line.contains(it, ignoreCase = true) }) {
                val start = maxOf(0, i - contextLines)
                val end = minOf(lines.lastIndex, i + contextLines)
                keepRanges.add(start..end)
            }
        }

        keepRanges.add(maxOf(0, lines.lastIndex - 5)..lines.lastIndex)
        return mergeAndFormat(lines, keepRanges)
    }

    fun truncateForContext(
        original: String,
        targetMethodNames: List<String>
    ): String {
        val lines = original.lines()
        if (lines.size <= LARGE_FILE_THRESHOLD) return original

        val keepRanges = mutableListOf<IntRange>()

        val classStartIdx = lines.indexOfFirst {
            it.trimStart().let { l ->
                l.startsWith("public class") || l.startsWith("public abstract class") ||
                        l.startsWith("@Component") || l.startsWith("@Service") ||
                        l.startsWith("@Repository") || l.startsWith("@Controller")
            }
        }
        val headerEnd = minOf((classStartIdx + 3).coerceAtLeast(20), lines.lastIndex)
        keepRanges.add(0..headerEnd)

        for ((i, line) in lines.withIndex()) {
            if (targetMethodNames.any { line.contains(it, ignoreCase = true) }) {
                val start = maxOf(0, i - 5)
                val end = minOf(lines.lastIndex, i + 30)
                keepRanges.add(start..end)
            }
        }

        val methodSigPattern = Regex("""^\s*(public|protected|private)\s+\S+\s+\w+\s*\(""")
        for ((i, line) in lines.withIndex()) {
            if (methodSigPattern.containsMatchIn(line)) {
                keepRanges.add(maxOf(0, i - 1)..minOf(lines.lastIndex, i + 2))
            }
        }

        keepRanges.add(maxOf(0, lines.lastIndex - 3)..lines.lastIndex)
        return mergeAndFormat(lines, keepRanges)
    }

    private fun mergeAndFormat(lines: List<String>, ranges: List<IntRange>): String {
        if (ranges.isEmpty()) return lines.take(50).joinToString("\n")

        val sorted = ranges.sortedBy { it.first }
        val merged = mutableListOf<IntRange>()
        var current = sorted[0]

        for (i in 1 until sorted.size) {
            val next = sorted[i]
            current = if (next.first <= current.last + 1) {
                current.first..maxOf(current.last, next.last)
            } else {
                merged.add(current)
                next
            }
        }
        merged.add(current)

        return buildString {
            for ((idx, range) in merged.withIndex()) {
                for (i in range) {
                    if (i <= lines.lastIndex) appendLine(lines[i])
                }
                if (idx < merged.lastIndex) {
                    val gap = merged[idx + 1].first - range.last - 1
                    appendLine("\t// ... ($gap lines omitted) ...")
                }
            }
        }.trimEnd()
    }

    // ═══════════════════════════════════════════════════════════════
    // DTO Merge
    // ═══════════════════════════════════════════════════════════════

    fun mergeDtoSnippet(originalSource: String, snippet: String): String {
        val trimmed = snippet.trim()
            .removePrefix("```java").removePrefix("```")
            .removeSuffix("```").trim()

        if (trimmed.contains("No additional fields needed") || trimmed.contains("no additional fields")) {
            return originalSource
        }

        val codeOnly = trimmed.replace(
            Regex("""\[MODIFIED_SIGNATURES].*?(?=\n\s*(private|public|//\s*===|$))""", RegexOption.DOT_MATCHES_ALL),
            ""
        ).trim()

        if (codeOnly.isBlank()) return originalSource

        val lines = codeOnly.lines()
        val fieldDeclarations = mutableListOf<String>()
        val methodBlocks = mutableListOf<String>()
        var inMethod = false
        val methodBuffer = StringBuilder()
        var braceCount = 0

        for (line in lines) {
            val trimmedLine = line.trim()
            when {
                !inMethod && trimmedLine.startsWith("private ") && trimmedLine.contains(";") -> {
                    fieldDeclarations.add(line)
                }
                !inMethod && (trimmedLine.startsWith("public ") || trimmedLine.startsWith("protected ")) &&
                        trimmedLine.contains("(") -> {
                    inMethod = true
                    braceCount = line.count { it == '{' } - line.count { it == '}' }
                    methodBuffer.append(line).append("\n")
                    if (braceCount <= 0 && line.contains("{") && line.contains("}")) {
                        methodBlocks.add(methodBuffer.toString().trimEnd())
                        methodBuffer.clear()
                        inMethod = false
                    }
                }
                inMethod -> {
                    braceCount += line.count { it == '{' } - line.count { it == '}' }
                    methodBuffer.append(line).append("\n")
                    if (braceCount <= 0) {
                        methodBlocks.add(methodBuffer.toString().trimEnd())
                        methodBuffer.clear()
                        inMethod = false
                    }
                }
            }
        }

        val existingFields = extractFieldNames(originalSource).toSet()
        val newFields = fieldDeclarations.filter { decl ->
            val fieldName = Regex("""private\s+\S+\s+(\w+)\s*;""").find(decl)?.groupValues?.get(1)
            fieldName != null && fieldName !in existingFields
        }
        val newMethods = methodBlocks.filter { block ->
            val methodName = Regex("""(get|set)(\w+)\s*\(""").find(block)?.groupValues?.get(0)
            methodName == null || !originalSource.contains(methodName)
        }

        if (newFields.isEmpty() && newMethods.isEmpty()) return originalSource

        val insertionPoint = originalSource.lastIndexOf("}")
        if (insertionPoint == -1) return originalSource

        val insertion = buildString {
            if (newFields.isNotEmpty()) {
                append("\n\t// === New fields ===\n")
                newFields.forEach { append("\t${it.trim()}\n") }
            }
            if (newMethods.isNotEmpty()) {
                append("\n")
                newMethods.forEach { append("\t${it.trim()}\n\n") }
            }
        }

        return originalSource.substring(0, insertionPoint) + insertion + "}\n"
    }

    // ═══════════════════════════════════════════════════════════════
    // Post-Processing
    // ═══════════════════════════════════════════════════════════════

    fun postProcessBypassLogic(response: String): String {
        val pattern = Regex("""\}\s*else\s*\{[^}]*bypass[^}]*\}""", RegexOption.IGNORE_CASE)
        val matches = pattern.findAll(response).toList()
        if (matches.isEmpty()) return response
        var res = response
        for (match in matches.reversed()) {
            res = res.removeRange(match.range)
        }
        return res
    }

    fun postProcessStaticInstanceCheck(
        response: String,
        targetPath: String,
        originalSource: String?
    ): String {
        if (!targetPath.lowercase().let { it.contains("/util/") || it.contains("/common/") }) return response
        if (originalSource == null) return response

        val instanceMethodPattern = Regex("""public\s+(?!static\b)\w+\s+\w+\s*\(""", RegexOption.MULTILINE)
        if (instanceMethodPattern.containsMatchIn(originalSource)) return response

        var res = response
        val generated = instanceMethodPattern.findAll(response).toList()
        for (match in generated.reversed()) {
            res = res.replaceRange(match.range, match.value.replace("public ", "public static "))
        }
        return res
    }

    fun findMatchingBrace(source: String, startIdx: Int): Int {
        val braceStart = source.indexOf('{', startIdx)
        if (braceStart == -1) return startIdx
        var depth = 0
        for (i in braceStart..source.lastIndex) {
            when (source[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return i }
            }
        }
        return source.lastIndex
    }
    
    fun isDtoFile(path: String): Boolean = path.lowercase().let { 
        it.contains("/dto/") || it.contains("/vo/") || it.endsWith("dto.java") || it.endsWith("vo.java") 
    }
}
