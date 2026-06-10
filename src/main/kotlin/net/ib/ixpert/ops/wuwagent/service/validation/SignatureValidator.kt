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
     * 파싱된 시그니처 정보를 담는 클래스
     */
    data class ParsedSignature(
        val className: String,  // 파일 경로에서 추출한 클래스명
        val methodName: String,
        val paramCount: Int,
        val rawSignature: String
    )

    /**
     * contextChain에서 시그니처들을 추출합니다.
     * contextChain 항목 형식: "### `파일경로` 변경사항\n시그니처1\n시그니처2"
     * (ImplementationPipeline에서 [MODIFIED_SIGNATURES] 태그는 이미 제거된 상태)
     */
    fun extractSignaturesFromContext(contextChain: List<String>): Map<String, List<ParsedSignature>> {
        val signatureMap = mutableMapOf<String, MutableList<ParsedSignature>>()
        
        contextChain.forEach context@ { context ->
            // 헤더에서 클래스명 추출: "### `.../ClassName.java` 변경사항"
            val classNameMatch = Regex("""###\s*`[^`]*?(\w+)\.java`""").find(context)
            val className = classNameMatch?.groupValues?.get(1) ?: "Unknown"

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
                        val paramsMatch = Regex("""\(([^)]*)\)""").find(cleanLine)
                        val paramsStr = paramsMatch?.groupValues?.get(1) ?: ""
                        val paramCount = countTopLevelArgs(paramsStr)

                        val parsed = ParsedSignature(className, methodName, paramCount, cleanLine)
                        signatureMap.getOrPut(methodName) { mutableListOf() }.add(parsed)
                        logger.info("시그니처 추출: [$className] $methodName (params: $paramCount) -> $cleanLine")
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
    fun validateConsistency(responseText: String, signatureMap: Map<String, List<ParsedSignature>>): List<String> {
        val warnings = mutableListOf<String>()
        
        if (signatureMap.isEmpty()) {
            logger.info("시그니처 맵이 비어있어 일관성 검증을 건너뜜.")
            return warnings
        }
        
        val responseLines = responseText.lines()
        
        signatureMap.forEach methods@ { (methodName, signatures) ->
            // methodName(...) 패턴 매칭
            val callPattern = Regex("""\b(\w+)?\.?$methodName\s*\(([^)]*)\)""")
            
            callPattern.findAll(responseText).forEach calls@ { match ->
                val callerObject = match.groupValues[1] // "filterChain", "AuthVal" 등 객체/클래스명
                val argsStr = match.groupValues[2]
                
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
                
                val argCount = countTopLevelArgs(argsStr)
                
                signatures.forEach { sig ->
                    // 클래스명이 일치하거나 (객체명 매칭은 불완전하므로) 
                    // 최소한 객체/클래스명이 호출부에 있을 때만 정밀 검증 시도하거나, 
                    // 아니면 모든 동일 명칭 메서드에 대해 파라미터 수가 하나라도 맞으면 통과시키는 식으로 허위 경고 방지
                    
                    // 정교한 검증: 호출된 객체명이 시그니처의 클래스명과 유사한지 확인
                    val isLikelyMatch = if (callerObject.isBlank()) {
                        true // 로컬 호출은 일단 타겟으로 간주
                    } else {
                        // "authVal.validateToken"에서 "authVal"과 "AuthVal" 클래스명 비교
                        callerObject.equals(sig.className, ignoreCase = true) ||
                        sig.className.contains(callerObject, ignoreCase = true)
                    }

                    if (isLikelyMatch && argCount != sig.paramCount) {
                        // 만약 다른 시그니처 중 이 파라미터 수와 맞는 것이 하나라도 있다면 경고하지 않음 (오버로딩 가능성)
                        val hasAnyMatchingSig = signatures.any { it.paramCount == argCount }
                        if (!hasAnyMatchingSig) {
                            val warning = "시그니처 불일치: `${sig.className}.$methodName()` — " +
                                "호출부 ${argCount}개 인자 ↔ 정의부 ${sig.paramCount}개 파라미터\n" +
                                "  호출 코드: `${trimmedLine.take(120)}`\n" +
                                "  정의 시그니처: `${sig.rawSignature}`"
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

    // ═══════════════════════════════════════════════════════════════
    // [Phase 3] Contract 기반 시그니처 검증
    // ═══════════════════════════════════════════════════════════════

    /**
     * 생성된 코드가 확정된 Contract와 일치하는지 검증합니다.
     * 반환 타입, 파라미터 타입, Caller 변수 타입을 검사합니다.
     *
     * 정규식에서 제네릭 중첩을 처리하기 위해 `[\w.<>,\s?]+` 패턴을 사용합니다.
     * (예: List<HashMap<String, Object>>)
     */
    fun validateAgainstContract(
        responseText: String,
        fileContract: net.ib.ixpert.ops.wuwagent.agent.FileContract
    ): List<String> {
        val warnings = mutableListOf<String>()

        for (method in fileContract.methods) {
            val escapedName = Regex.escape(method.methodName)

            // 1. 반환 타입 일치 검증 (메서드 정의부)
            // 제네릭 중첩을 지원하는 패턴: [\w.<>,\s?]+ 으로 List<HashMap<String, Object>> 매칭
            val returnTypePattern = Regex(
                """(?:public|protected)\s+([\w.<>,\s?]+)\s+$escapedName\s*\("""
            )
            val returnMatch = returnTypePattern.find(responseText)
            if (returnMatch != null) {
                val actualReturn = returnMatch.groupValues[1].trim()
                if (normalizeType(actualReturn) != normalizeType(method.returnType)) {
                    warnings.add(
                        "반환 타입 불일치: `${method.methodName}()` — " +
                        "생성: `$actualReturn`, 계약: `${method.returnType}`"
                    )
                }
            }

            // 2. Caller에서 변수 타입 검증 (CALLER 역할인 경우)
            if (fileContract.role == net.ib.ixpert.ops.wuwagent.agent.FileRole.CALLER) {
                val assignPattern = Regex(
                    """([\w.<>,\s?]+)\s+\w+\s*=\s*\w+\.$escapedName\s*\("""
                )
                val assignMatch = assignPattern.find(responseText)
                if (assignMatch != null) {
                    val varType = assignMatch.groupValues[1].trim()
                    if (normalizeType(varType) != normalizeType(method.returnType)) {
                        warnings.add(
                            "호출부 변수 타입 불일치: `${method.methodName}()` — " +
                            "변수: `$varType`, 계약: `${method.returnType}`"
                        )
                    }
                }
            }
        }

        return warnings.distinct()
    }

    /**
     * 타입 문자열을 정규화하여 비교합니다.
     * - import 차이 무시 (java.util.HashMap → HashMap)
     * - HashMap/Map 호환 허용
     * - 공백 차이 무시
     * - String vs Object 등은 strict 모드로 구분 유지
     */
    private fun normalizeType(type: String): String {
        return type
            .replace("static ", "")
            .replace("final ", "")
            .replace("abstract ", "")
            .replace(" ", "")
            .replace("java.util.", "")
            .replace("java.lang.", "")
            .replace("HashMap", "Map")  // HashMap과 Map은 호환으로 간주
            .trim()
    }
}
