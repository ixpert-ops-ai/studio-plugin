// analysis/extractor/RegexExtractor.kt
package net.ib.ixpert.ops.wuwagent.service.analysis.extractor

import net.ib.ixpert.ops.wuwagent.service.analysis.model.*

/**
 * 정규식 기반 경량 구조 추출기.
 * PSI와 Tree-sitter가 모두 사용 불가능할 때의 최종 fallback입니다.
 *
 * 정확도는 낮지만 "LLM에게 전부 맡기기"보다는 낫습니다.
 * 추출된 목록을 LLM의 참고 자료로 제공하면 누락을 줄일 수 있습니다.
 */
class RegexExtractor : StructureExtractor {

    companion object {
        private val SUPPORTED_LANGUAGES = setOf(
            "javascript", "typescript", "javascriptreact", "typescriptreact",
            "vue", "python", "swift", "java", "kotlin"
        )
    }

    override fun supports(languageId: String): Boolean {
        return SUPPORTED_LANGUAGES.contains(languageId.lowercase())
    }

    override fun extract(code: String, languageId: String): ExtractedStructure {
        val lang = languageId.lowercase()
        val symbols = when (lang) {
            "javascript", "javascriptreact" -> extractJavaScript(code)
            "typescript", "typescriptreact" -> extractTypeScript(code)
            "vue" -> extractVue(code)
            "python" -> extractPython(code)
            "java" -> extractJava(code)
            "kotlin" -> extractKotlin(code)
            "swift" -> extractSwift(code)
            else -> emptyList()
        }

        val imports = extractImports(code, lang)
        val classes = extractClasses(code, lang)

        return ExtractedStructure(
            symbols = symbols,
            imports = imports,
            classes = classes,
            rawCode = code,
            extractionMethod = ExtractionMethod.REGEX
        )
    }

    // ── JavaScript ──

    private fun extractJavaScript(code: String): List<SymbolInfo> {
        val symbols = mutableListOf<SymbolInfo>()
        val lines = code.lines()

        val patterns = listOf(
            // function declaration
            Triple(
                Regex("""(?:export\s+)?(?:async\s+)?function\s+(\w+)\s*\(([^)]*)\)"""),
                SymbolKind.FUNCTION,
                false
            ),
            // arrow function: const fn = (...) => ...
            Triple(
                Regex("""(?:export\s+)?(?:const|let|var)\s+(\w+)\s*=\s*(?:async\s*)?\(([^)]*)\)\s*=>"""),
                SymbolKind.ARROW_FUNCTION,
                false
            ),
            // class method: methodName(...) {
            Triple(
                Regex("""^\s+(?:async\s+)?(\w+)\s*\(([^)]*)\)\s*\{"""),
                SymbolKind.METHOD,
                false
            )
        )

        lines.forEachIndexed { index, line ->
            for ((pattern, kind, _) in patterns) {
                val match = pattern.find(line) ?: continue
                val name = match.groupValues[1]
                val params = parseParamString(match.groupValues[2])

                // 중복 방지
                if (symbols.any { it.name == name && it.startLine == index + 1 }) continue

                symbols.add(SymbolInfo(
                    name = name,
                    kind = kind,
                    params = params,
                    startLine = index + 1,
                    endLine = findBlockEnd(lines, index),
                    bodyText = extractBodyText(lines, index, 500),
                    isExported = line.trimStart().startsWith("export"),
                    isAsync = line.contains("async")
                ))
            }
        }

        return symbols
    }

    // ── TypeScript (JS 확장) ──

    private fun extractTypeScript(code: String): List<SymbolInfo> {
        val symbols = extractJavaScript(code).toMutableList()

        val lines = code.lines()
        val interfacePattern = Regex("""(?:export\s+)?interface\s+(\w+)""")

        lines.forEachIndexed { index, line ->
            interfacePattern.find(line)?.let { match ->
                symbols.add(SymbolInfo(
                    name = match.groupValues[1],
                    kind = SymbolKind.INTERFACE_METHOD,
                    startLine = index + 1,
                    endLine = findBlockEnd(lines, index),
                    bodyText = extractBodyText(lines, index, 300),
                    isExported = line.trimStart().startsWith("export")
                ))
            }
        }

        return symbols
    }

    // ── Vue SFC ──

    private fun extractVue(code: String): List<SymbolInfo> {
        // <script> 블록 추출
        val scriptPattern = Regex("""<script[^>]*>([\s\S]*?)</script>""")
        val scriptMatch = scriptPattern.find(code) ?: return emptyList()
        val scriptContent = scriptMatch.groupValues[1]
        val scriptStartLine = code.substring(0, scriptMatch.range.first).count { it == '\n' }

        // script 내용을 JS/TS로 분석
        val isTS = code.contains("""lang="ts"""") || code.contains("lang='ts'")
        val symbols = if (isTS) extractTypeScript(scriptContent) else extractJavaScript(scriptContent)

        // 라인 번호 보정 (script 시작 위치 기준)
        return symbols.map { it.copy(startLine = it.startLine + scriptStartLine) }
    }

    // ── Python ──

    private fun extractPython(code: String): List<SymbolInfo> {
        val symbols = mutableListOf<SymbolInfo>()
        val lines = code.lines()

        val funcPattern = Regex("""^(\s*)(?:async\s+)?def\s+(\w+)\s*\(([^)]*)\)""")
        val classMethodPattern = Regex("""^\s{4,}(?:async\s+)?def\s+(\w+)\s*\(([^)]*)\)""")

        lines.forEachIndexed { index, line ->
            funcPattern.find(line)?.let { match ->
                val indent = match.groupValues[1].length
                val name = match.groupValues[2]
                val params = parseParamString(match.groupValues[3])
                val kind = if (indent >= 4) SymbolKind.METHOD else SymbolKind.FUNCTION

                symbols.add(SymbolInfo(
                    name = name,
                    kind = kind,
                    params = params,
                    startLine = index + 1,
                    endLine = findPythonBlockEnd(lines, index),
                    bodyText = extractBodyText(lines, index, 500),
                    isAsync = line.contains("async")
                ))
            }
        }

        return symbols
    }

    // ── Java ──

    private fun extractJava(code: String): List<SymbolInfo> {
        val symbols = mutableListOf<SymbolInfo>()
        val lines = code.lines()

        // public void methodName(Type param) {
        val methodPattern = Regex(
            """^\s*(?:(?:public|protected|private)\s+)?(?:static\s+)?(?:final\s+)?(?:synchronized\s+)?(\w+(?:<[^>]+>)?)\s+(\w+)\s*\(([^)]*)\)\s*(?:throws\s+[\w,\s]+)?\s*\{"""
        )

        lines.forEachIndexed { index, line ->
            methodPattern.find(line)?.let { match ->
                val returnType = match.groupValues[1]
                val name = match.groupValues[2]
                val params = parseJavaParamString(match.groupValues[3])

                symbols.add(SymbolInfo(
                    name = name,
                    kind = if (returnType == name) SymbolKind.CONSTRUCTOR else SymbolKind.METHOD,
                    params = params,
                    returnType = if (returnType != name) returnType else null,
                    startLine = index + 1,
                    endLine = findBlockEnd(lines, index),
                    bodyText = extractBodyText(lines, index, 500),
                    isExported = line.contains("public"),
                    isStatic = line.contains("static")
                ))
            }
        }

        return symbols
    }

    // ── Kotlin ──

    private fun extractKotlin(code: String): List<SymbolInfo> {
        val symbols = mutableListOf<SymbolInfo>()
        val lines = code.lines()

        // fun methodName(param: Type): ReturnType {
        val funcPattern = Regex(
            """^\s*(?:(?:public|private|internal|protected)\s+)?(?:suspend\s+)?(?:override\s+)?fun\s+(\w+)\s*\(([^)]*)\)(?:\s*:\s*(\w+(?:<[^>]+>)?))?"""
        )

        lines.forEachIndexed { index, line ->
            funcPattern.find(line)?.let { match ->
                val name = match.groupValues[1]
                val params = parseKotlinParamString(match.groupValues[2])
                val returnType = match.groupValues[3].ifBlank { null }

                symbols.add(SymbolInfo(
                    name = name,
                    kind = SymbolKind.FUNCTION,
                    params = params,
                    returnType = returnType,
                    startLine = index + 1,
                    endLine = findBlockEnd(lines, index),
                    bodyText = extractBodyText(lines, index, 500),
                    isAsync = line.contains("suspend")
                ))
            }
        }

        return symbols
    }

    // ── Swift ──

    private fun extractSwift(code: String): List<SymbolInfo> {
        val symbols = mutableListOf<SymbolInfo>()
        val lines = code.lines()

        val funcPattern = Regex(
            """^\s*(?:(?:public|private|internal|open)\s+)?(?:static\s+)?(?:override\s+)?func\s+(\w+)\s*\(([^)]*)\)(?:\s*->\s*(\w+))?"""
        )

        lines.forEachIndexed { index, line ->
            funcPattern.find(line)?.let { match ->
                symbols.add(SymbolInfo(
                    name = match.groupValues[1],
                    kind = SymbolKind.FUNCTION,
                    params = parseParamString(match.groupValues[2]),
                    returnType = match.groupValues[3].ifBlank { null },
                    startLine = index + 1,
                    endLine = findBlockEnd(lines, index),
                    bodyText = extractBodyText(lines, index, 500)
                ))
            }
        }

        return symbols
    }

    // ── Import 추출 ──

    private fun extractImports(code: String, languageId: String): List<String> {
        val pattern = when (languageId) {
            "javascript", "typescript", "javascriptreact", "typescriptreact", "vue" ->
                Regex("""^import\s+.+$""", RegexOption.MULTILINE)
            "java", "kotlin" ->
                Regex("""^import\s+.+$""", RegexOption.MULTILINE)
            "python" ->
                Regex("""^(?:from\s+\S+\s+)?import\s+.+$""", RegexOption.MULTILINE)
            "swift" ->
                Regex("""^import\s+\w+""", RegexOption.MULTILINE)
            else -> return emptyList()
        }

        return pattern.findAll(code).map { it.value.trim() }.toList()
    }

    // ── 클래스 추출 ──

    private fun extractClasses(code: String, languageId: String): List<ClassInfo> {
        val classes = mutableListOf<ClassInfo>()
        val lines = code.lines()

        val pattern = when (languageId) {
            "javascript", "typescript", "javascriptreact", "typescriptreact" ->
                Regex("""(?:export\s+)?class\s+(\w+)(?:\s+extends\s+(\w+))?(?:\s+implements\s+([\w,\s]+))?""")
            "java" ->
                Regex("""(?:public\s+)?(?:abstract\s+)?class\s+(\w+)(?:\s+extends\s+(\w+))?(?:\s+implements\s+([\w,\s]+))?""")
            "kotlin" ->
                Regex("""(?:data\s+)?(?:open\s+)?(?:abstract\s+)?class\s+(\w+)(?:\s*:\s*([\w,\s()]+))?""")
            "python" ->
                Regex("""class\s+(\w+)(?:\(([^)]+)\))?""")
            "swift" ->
                Regex("""class\s+(\w+)(?:\s*:\s*([\w,\s]+))?""")
            else -> return emptyList()
        }

        lines.forEachIndexed { index, line ->
            pattern.find(line)?.let { match ->
                classes.add(ClassInfo(
                    name = match.groupValues[1],
                    superClass = match.groupValues.getOrNull(2)?.trim()?.ifBlank { null },
                    line = index + 1
                ))
            }
        }

        return classes
    }

    // ── 파싱 유틸리티 ──

    private fun parseParamString(paramStr: String): List<ParamInfo> {
        if (paramStr.isBlank()) return emptyList()
        return paramStr.split(",").map { p ->
            val trimmed = p.trim()
            ParamInfo(name = trimmed)
        }
    }

    private fun parseJavaParamString(paramStr: String): List<ParamInfo> {
        if (paramStr.isBlank()) return emptyList()
        return paramStr.split(",").mapNotNull { p ->
            val parts = p.trim().split(Regex("""\s+"""))
            if (parts.size >= 2) {
                ParamInfo(name = parts.last(), type = parts.dropLast(1).joinToString(" "))
            } else if (parts.size == 1 && parts[0].isNotBlank()) {
                ParamInfo(name = parts[0])
            } else null
        }
    }

    private fun parseKotlinParamString(paramStr: String): List<ParamInfo> {
        if (paramStr.isBlank()) return emptyList()
        return paramStr.split(",").mapNotNull { p ->
            val parts = p.trim().split(":")
            if (parts.size >= 2) {
                ParamInfo(name = parts[0].trim(), type = parts[1].trim())
            } else if (parts[0].isNotBlank()) {
                ParamInfo(name = parts[0].trim())
            } else null
        }
    }

    /**
     * 중괄호 기반 블록 끝 찾기 (Java, JS, Kotlin, Swift 등)
     */
    private fun findBlockEnd(lines: List<String>, startIndex: Int): Int {
        var braceCount = 0
        var started = false

        for (i in startIndex until minOf(startIndex + 200, lines.size)) {
            for (char in lines[i]) {
                when (char) {
                    '{' -> { braceCount++; started = true }
                    '}' -> braceCount--
                }
                if (started && braceCount == 0) return i + 1
            }
        }

        return minOf(startIndex + 1, lines.size)
    }

    /**
     * Python 인덴트 기반 블록 끝 찾기
     */
    private fun findPythonBlockEnd(lines: List<String>, startIndex: Int): Int {
        val baseIndent = lines[startIndex].indexOfFirst { !it.isWhitespace() }

        for (i in (startIndex + 1) until minOf(startIndex + 200, lines.size)) {
            val line = lines[i]
            if (line.isBlank()) continue
            val indent = line.indexOfFirst { !it.isWhitespace() }
            if (indent <= baseIndent) return i
        }

        return lines.size
    }

    /**
     * 시작 라인부터 지정된 길이만큼 본문 텍스트 추출
     */
    private fun extractBodyText(lines: List<String>, startIndex: Int, maxLength: Int): String {
        val endIndex = findBlockEnd(lines, startIndex)
        val body = lines.subList(startIndex, minOf(endIndex, lines.size)).joinToString("\n")
        return if (body.length > maxLength) body.substring(0, maxLength) + "..." else body
    }
}

