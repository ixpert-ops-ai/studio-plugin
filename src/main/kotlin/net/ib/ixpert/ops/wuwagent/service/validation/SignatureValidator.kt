package net.ib.ixpert.ops.wuwagent.service.validation

import com.intellij.openapi.diagnostic.Logger

/**
 * 생성된 코드 스니펫에서 이전 파일에서 정의된 시그니처가 올바르게 호출되는지 검증합니다.
 */
object SignatureValidator {
    private val logger = Logger.getInstance(SignatureValidator::class.java)

    // 제외할 Java 키워드 목록
    private val EXCLUDED_KEYWORDS = setOf(
        "if", "for", "while", "switch", "catch", "return", "new", 
        "throw", "throws", "class", "interface", "enum", "synchronized"
    )

    /**
     * contextChain에서 시그니처들을 추출합니다.
     * contextChain 항목 형식: "### `파일경로` 변경사항\n시그니처1\n시그니처2"
     * (ImplementationPipeline에서 [MODIFIED_SIGNATURES] 태그는 이미 제거된 상태)
     */
    fun extractSignaturesFromContext(contextChain: List<String>): Map<String, List<String>> {
        val signatureMap = mutableMapOf<String, MutableList<String>>()
        
        contextChain.forEach context@ { context ->
            val lines = context.lines()
            lines.forEach lines@ { line ->
                val cleanLine = line.trim()
                    .removePrefix("+").trim()
                    .removePrefix("-").trim()
                
                // 헤더 줄(###), 빈 줄, 주석 줄 건너뛰기
                if (cleanLine.startsWith("#") || cleanLine.isBlank() || 
                    cleanLine.startsWith("//") || cleanLine.startsWith("*")) {
                    return@lines
                }
                
                val methodNameMatch = Regex("""\b(\w+)\s*\(""").find(cleanLine)
                if (methodNameMatch != null) {
                    val methodName = methodNameMatch.groupValues[1]
                    if (methodName !in EXCLUDED_KEYWORDS) {
                        signatureMap.getOrPut(methodName) { mutableListOf() }.add(cleanLine)
                        logger.info("시그니처 추출: $methodName -> $cleanLine")
                    }
                }
            }
        }
        
        logger.info("추출된 시그니처 맵: ${signatureMap.keys} (총 ${signatureMap.values.sumOf { it.size }}개)")
        return signatureMap
    }

    /**
     * 응답 텍스트 내의 메서드 호출이 추출된 시그니처와 일치하는지 검증합니다.
     * 메서드 정의부는 제외하고 호출부만 검사하며, 중괄호를 고려하여 파라미터 수를 카운트합니다.
     */
    fun validateConsistency(responseText: String, signatureMap: Map<String, List<String>>): List<String> {
        val warnings = mutableListOf<String>()
        
        if (signatureMap.isEmpty()) {
            logger.info("시그니처 맵이 비어있어 일관성 검증을 건너뜁니다.")
            return warnings
        }
        
        val responseLines = responseText.lines()
        
        signatureMap.forEach methods@ { (methodName, signatures) ->
            val callPattern = Regex("""\b$methodName\s*\(([^)]*)\)""")
            
            callPattern.findAll(responseText).forEach calls@ { match ->
                // 매칭된 위치의 전체 줄을 찾기
                val matchStart = match.range.first
                val lineIndex = responseText.substring(0, matchStart).count { it == '\n' }
                val fullLine = if (lineIndex < responseLines.size) responseLines[lineIndex] else ""
                val trimmedLine = fullLine.trim()
                
                // 메서드 정의부 제외
                if (isMethodDefinition(trimmedLine, methodName)) return@calls
                
                // import, package, 주석, 어노테이션 줄 제외
                if (trimmedLine.startsWith("import ") || 
                    trimmedLine.startsWith("package ") ||
                    trimmedLine.startsWith("//") || 
                    trimmedLine.startsWith("*") ||
                    trimmedLine.startsWith("/*") ||
                    trimmedLine.startsWith("@")) {
                    return@calls
                }
                
                val argsStr = match.groupValues[1]
                val argCount = countTopLevelArgs(argsStr)
                
                signatures.forEach { sig ->
                    val paramsMatch = Regex("""\(([^)]*)\)""").find(sig)
                    if (paramsMatch != null) {
                        val paramsStr = paramsMatch.groupValues[1]
                        val expectedCount = countTopLevelArgs(paramsStr)
                        
                        if (argCount != expectedCount) {
                            val warning = "시그니처 불일치: `$methodName()` — " +
                                "호출부 ${argCount}개 인자, 정의부 ${expectedCount}개 파라미터\n" +
                                "  호출 코드: `${trimmedLine.take(120)}`\n" +
                                "  정의 시그니처: `$sig`"
                            warnings.add(warning)
                            logger.warn(warning)
                        }
                    }
                }
            }
        }
        return warnings.distinct()
    }
    
    /**
     * 해당 줄이 메서드 정의부인지 판별합니다.
     * 접근제어자, 반환타입, 또는 abstract/default 키워드가 메서드명 앞에 있으면 정의부로 간주합니다.
     */
    private fun isMethodDefinition(line: String, methodName: String): Boolean {
        val definitionPattern = Regex(
            """(?:public|private|protected|static|abstract|default|final|synchronized)\s+.*\b$methodName\s*\("""
        )
        if (definitionPattern.containsMatchIn(line)) return true
        
        // 반환타입 + 메서드명 패턴 (접근제어자 없는 경우)
        val returnTypePattern = Regex(
            """(?:void|int|long|double|float|boolean|char|String|List|Map|Set|JSONObject|ModelAndView|[\w]+(?:<[^>]+>)?)\s+$methodName\s*\("""
        )
        return returnTypePattern.containsMatchIn(line)
    }
    
    /**
     * 중첩 괄호/제네릭을 고려하여 최상위 레벨의 인자(파라미터) 수를 카운트합니다.
     * 예: "a, foo(b, c), d" → 3
     * 예: "Map<String, Object> claims, String key" → 2
     * 예: "" → 0
     */
    private fun countTopLevelArgs(argsStr: String): Int {
        val trimmed = argsStr.trim()
        if (trimmed.isEmpty()) return 0
        
        var depth = 0
        var count = 1
        for (ch in trimmed) {
            when (ch) {
                '(', '<', '[' -> depth++
                ')', '>', ']' -> depth--
                ',' -> if (depth == 0) count++
            }
        }
        return count
    }
}
