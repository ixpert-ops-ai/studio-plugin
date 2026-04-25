package net.ib.ixpert.ops.wuwagent.service

import com.intellij.openapi.diagnostic.Logger
import java.io.File

/**
 * 소스 코드의 `import` 선언을 기반으로 **프로젝트 내부** 타입의 파일을 찾아
 * 메서드 시그니처/필드 선언만 요약해 LLM 컨텍스트로 만든다.
 * (메서드 본문은 제거 → 프롬프트 비대화 방지)
 */
object TypeContextService {
    private val logger = Logger.getInstance(TypeContextService::class.java)

    private const val MAX_TYPES = 20       // 주입할 최대 타입 개수
    private const val MAX_LINES_PER_TYPE = 120  // 타입당 최대 라인 수

    /** 소스 코드에서 프로젝트 타입 시그니처 블록을 생성. 결과가 없으면 빈 문자열. */
    fun buildTypeContext(basePath: String, sourceCode: String): String {
        val imports = extractImports(sourceCode)
        val projectFiles = imports
            .mapNotNull { fqn -> findSourceFile(basePath, fqn)?.let { fqn to it } }
            .take(MAX_TYPES)

        if (projectFiles.isEmpty()) return ""

        val sb = StringBuilder()
        sb.append("\n=========================================================\n")
        sb.append("[Referenced Project Types — AUTHORITATIVE SIGNATURES]\n")
        sb.append("=========================================================\n")
        sb.append("⚠️ CRITICAL: 아래 블록에 표시된 생성자/빌더/필드 시그니처가 유일한 진실(source of truth)입니다.\n")
        sb.append("- 아래 타입의 인스턴스를 만들 때는 반드시 표시된 시그니처를 그대로 사용하세요.\n")
        sb.append("- 기본 생성자 `new Xxx()`는 아래 블록에서 해당 생성자가 명시적으로 보일 때만 사용하세요.\n")
        sb.append("- 시그니처가 불확실하면 Mockito `mock(Xxx.class)`를 사용해 우회하세요.\n")
        sb.append("- 아래 블록에 없는 프로젝트 타입을 추측으로 인스턴스화하지 마세요.\n\n")
        for ((fqn, file) in projectFiles) {
            val summary = summarizeFile(file) ?: continue
            sb.append("\n// ━━━ $fqn (${file.name}) ━━━\n")
            sb.append(summary)
            sb.append("\n")
        }
        sb.append("\n=========================================================\n")
        logger.info("TypeContextService: ${projectFiles.size}개 타입 주입 → ${projectFiles.joinToString { it.first }}")
        return sb.toString()
    }

    private fun extractImports(code: String): List<String> {
        val regex = Regex("""^\s*import\s+(?:static\s+)?([\w.]+)(?:\.\*)?\s*;""", RegexOption.MULTILINE)
        return regex.findAll(code)
            .map { it.groupValues[1] }
            .filter { '.' in it }
            .toList()
            .distinct()
    }

    private fun findSourceFile(basePath: String, fqn: String): File? {
        // `import com.foo.Bar.Inner` 같은 중첩 참조는 `com.foo.Bar` 까지만 내려가며 탐색
        var current = fqn
        repeat(3) {
            val rel = current.replace(".", "/")
            val candidates = listOf(
                "$basePath/src/main/java/$rel.java",
                "$basePath/src/main/kotlin/$rel.kt",
                "$basePath/src/main/java/$rel.kt"
            )
            candidates.map { File(it) }.firstOrNull { it.exists() }?.let { return it }
            val lastDot = current.lastIndexOf('.')
            if (lastDot < 0) return null
            current = current.substring(0, lastDot)
        }
        return null
    }

    /** 메서드 본문을 중괄호 깊이 기반으로 제거하고 시그니처만 남긴다. */
    private fun summarizeFile(file: File): String? {
        return try {
            val raw = file.readText()
            val stripped = stripMethodBodies(raw)
            val lines = stripped.lines()
            if (lines.size > MAX_LINES_PER_TYPE) {
                lines.take(MAX_LINES_PER_TYPE).joinToString("\n") + "\n// ... (truncated)"
            } else {
                stripped
            }
        } catch (e: Exception) {
            logger.warn("TypeContextService: 파일 요약 실패 → ${file.absolutePath}", e)
            null
        }
    }

    /**
     * 최상위(클래스 레벨)를 넘어선 블록(메서드 본문 등)의 내용을 빈 `{ ... }` 로 축약.
     * 주석과 문자열 리터럴은 보존(그 안의 중괄호는 무시).
     */
    private fun stripMethodBodies(code: String): String {
        val out = StringBuilder(code.length)
        var depth = 0        // 중괄호 깊이
        var i = 0
        val n = code.length
        while (i < n) {
            val c = code[i]
            // 라인 주석
            if (c == '/' && i + 1 < n && code[i + 1] == '/') {
                val end = code.indexOf('\n', i).let { if (it < 0) n else it }
                if (depth <= 1) out.append(code, i, end)
                i = end; continue
            }
            // 블록 주석
            if (c == '/' && i + 1 < n && code[i + 1] == '*') {
                val end = code.indexOf("*/", i + 2).let { if (it < 0) n else it + 2 }
                if (depth <= 1) out.append(code, i, end)
                i = end; continue
            }
            // 문자열 / 문자 리터럴
            if (c == '"' || c == '\'') {
                val quote = c
                val start = i
                i++
                while (i < n) {
                    val ch = code[i]
                    if (ch == '\\') { i += 2; continue }
                    if (ch == quote) { i++; break }
                    i++
                }
                if (depth <= 1) out.append(code, start, i)
                continue
            }
            if (c == '{') {
                if (depth == 0) out.append('{')        // 클래스 본문 진입
                else if (depth == 1) out.append("{ ... }") // 메서드 본문 축약
                depth++; i++; continue
            }
            if (c == '}') {
                depth--
                if (depth == 0) out.append('}')        // 클래스 본문 종료
                // depth >= 1 이면 메서드 본문 내부 → 축약에 이미 포함됐으므로 무시
                i++; continue
            }
            if (depth <= 1) out.append(c)
            i++
        }
        // 빈 줄 압축 + 들여쓰기 그대로
        return out.toString().lines()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
    }
}
