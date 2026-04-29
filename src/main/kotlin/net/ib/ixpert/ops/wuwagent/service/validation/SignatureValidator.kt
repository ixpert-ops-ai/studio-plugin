package net.ib.ixpert.ops.wuwagent.service.validation

import com.intellij.openapi.diagnostic.Logger

/**
 * 생성된 코드 스니펫에서 이전 파일에서 정의된 시그니처가 올바르게 호출되는지 검증합니다.
 */
object SignatureValidator {
    private val logger = Logger.getInstance(SignatureValidator::class.java)

    /**
     * contextChain에서 [MODIFIED_SIGNATURES] 태그 뒤의 시그니처들을 추출합니다.
     */
    fun extractSignaturesFromContext(contextChain: List<String>): Map<String, List<String>> {
        val signatureMap = mutableMapOf<String, MutableList<String>>()
        contextChain.forEach { context ->
            if (context.contains("[MODIFIED_SIGNATURES]")) {
                val lines = context.substringAfter("[MODIFIED_SIGNATURES]").trim().lines()
                lines.forEach { line ->
                    // 예: "+ public String validateToken(String token, String secretKey)"
                    val cleanLine = line.trim().removePrefix("+").trim()
                    val methodNameMatch = Regex("""\b(\w+)\s*\(""").find(cleanLine)
                    if (methodNameMatch != null) {
                        val methodName = methodNameMatch.groupValues[1]
                        signatureMap.getOrPut(methodName) { mutableListOf() }.add(cleanLine)
                    }
                }
            }
        }
        return signatureMap
    }

    /**
     * 응답 텍스트 내의 메서드 호출이 추출된 시그니처와 일치하는지 검증합니다.
     * 파라미터 개수를 우선적으로 체크합니다.
     */
    fun validateConsistency(responseText: String, signatureMap: Map<String, List<String>>): List<String> {
        val warnings = mutableListOf<String>()
        
        signatureMap.forEach { (methodName, signatures) ->
            // 응답 내에서 해당 메서드 호출 찾기 (정의 부분 제외를 위해 세미콜론이나 중괄호 없는 것 위주)
            // 간단하게 methodName(...) 패턴 매칭
            val callPattern = Regex("""\b$methodName\s*\(([^)]*)\)""")
            callPattern.findAll(responseText).forEach { match ->
                val argsStr = match.groupValues[1]
                val argCount = if (argsStr.trim().isEmpty()) 0 else argsStr.split(",").size
                
                // 시그니처에서 파라미터 개수 추출
                signatures.forEach { sig ->
                    val paramsMatch = Regex("""\(([^)]*)\)""").find(sig)
                    if (paramsMatch != null) {
                        val paramsStr = paramsMatch.groupValues[1]
                        val expectedCount = if (paramsStr.trim().isEmpty()) 0 else paramsStr.split(",").size
                        
                        if (argCount != expectedCount) {
                            warnings.add("시그니처 불일치 주의: $methodName 호출 시 파라미터 개수가 다릅니다. (호출: $argCount, 정의: $expectedCount)\n정의: $sig")
                        }
                    }
                }
            }
        }
        return warnings.distinct()
    }
}
