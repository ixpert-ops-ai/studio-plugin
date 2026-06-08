package net.ib.ixpert.ops.wuwagent.service.metagraph.analyzer

import com.intellij.openapi.diagnostic.Logger

/**
 * P6: Vue/React 등 프론트엔드 파일 심층 파싱기.
 * 정규식의 한계를 고려하여 제한된 깊이(1-depth)와 개수(최대 N개)로 
 * 상태 변수(State), 메서드(Methods), API 호출 패턴을 추출합니다.
 */
object FrontendDeepParser {
    private val logger = Logger.getInstance(FrontendDeepParser::class.java)

    const val MAX_STATE_FIELDS = 30
    const val MAX_METHODS = 20
    const val MAX_API_CALLS = 10

    /**
     * 프론트엔드 코드 스크립트에서 구조적 메타데이터를 추출합니다.
     * @param content 스크립트 파일 내용
     * @return map of categories (state, methods, api_calls) to list of extracted strings
     */
    fun parse(content: String): Map<String, List<String>> {
        val result = mutableMapOf<String, MutableList<String>>()

        try {
            // 1. State 추출 (ref, reactive, data)
            val stateList = mutableListOf<String>()
            stateList.addAll(extractRefs(content))
            stateList.addAll(extractReactives(content))
            stateList.addAll(extractDataReturn(content))
            
            if (stateList.isNotEmpty()) {
                result["state"] = stateList.distinct().take(MAX_STATE_FIELDS).toMutableList()
            }

            // 2. Methods 추출
            val methods = extractMethods(content)
            if (methods.isNotEmpty()) {
                result["methods"] = methods.distinct().take(MAX_METHODS).toMutableList()
            }

            // 3. API Calls 추출
            val apiCalls = extractApiCalls(content)
            if (apiCalls.isNotEmpty()) {
                result["api_calls"] = apiCalls.distinct().take(MAX_API_CALLS).toMutableList()
            }
        } catch (e: Exception) {
            logger.warn("FrontendDeepParser 실패 (graceful fallback 처리됨): ${e.message}", e)
        }

        return result
    }

    /**
     * ref(...) 형태의 상태 변수 추출
     * 예: const count = ref(0) -> "count (ref)"
     */
    private fun extractRefs(content: String): List<String> {
        val refs = mutableListOf<String>()
        // const, let, var 뒤 변수명 = ref(...) 형태 매칭
        val refRegex = Regex("""(?:const|let|var)\s+([a-zA-Z0-9_]+)\s*=\s*ref\s*\(""")
        refRegex.findAll(content).forEach { match ->
            refs.add("${match.groupValues[1]} (ref)")
        }
        return refs
    }

    /**
     * reactive({ ... }) 형태의 상태 변수 추출 (1-depth 제한)
     * 예: const form = reactive({ name: '', address: {} }) 
     * -> "form (reactive) - fields: [name, address: Object]"
     */
    private fun extractReactives(content: String): List<String> {
        val reactives = mutableListOf<String>()
        val reactiveRegex = Regex("""(?:const|let|var)\s+([a-zA-Z0-9_]+)\s*=\s*reactive\s*\(\s*\{([^}]*)\}\s*\)""", RegexOption.DOT_MATCHES_ALL)
        
        reactiveRegex.findAll(content).forEach { match ->
            val varName = match.groupValues[1]
            val innerContent = match.groupValues[2]
            
            val fields = extractFields1Depth(innerContent)
            val fieldSummary = if (fields.isNotEmpty()) " - fields: [${fields.joinToString(", ")}]" else ""
            reactives.add("$varName (reactive)$fieldSummary")
        }
        return reactives
    }

    /**
     * Options API data() { return { ... } } 형태의 상태 변수 추출 (1-depth 제한)
     */
    private fun extractDataReturn(content: String): List<String> {
        val dataReturns = mutableListOf<String>()
        val dataRegex = Regex("""data\s*\(\s*\)\s*\{\s*return\s*\{([^}]*)\}\s*\}""", RegexOption.DOT_MATCHES_ALL)
        
        dataRegex.findAll(content).forEach { match ->
            val innerContent = match.groupValues[1]
            val fields = extractFields1Depth(innerContent)
            if (fields.isNotEmpty()) {
                fields.forEach { dataReturns.add("$it (data)") }
            }
        }
        return dataReturns
    }

    /**
     * 1-depth 필드 추출 및 타입 단순화
     * 예: "city: '', address: { zip: '' }, count: 0" -> ["city", "address: Object", "count"]
     */
    private fun extractFields1Depth(block: String): List<String> {
        val fields = mutableListOf<String>()
        // 대략적인 key: value 매칭 (1-depth만 근사적으로 추출)
        // 콤마로 분리하여 각 줄을 분석
        val lines = block.split("\n", ",").map { it.trim() }.filter { it.isNotEmpty() && it.contains(":") }
        
        for (line in lines) {
            val parts = line.split(":", limit = 2)
            if (parts.size == 2) {
                val key = parts[0].trim().replace("\"", "").replace("'", "")
                val valueStr = parts[1].trim()
                
                // 간단한 타입 추론
                val typeInfo = when {
                    valueStr.startsWith("{") -> "Object"
                    valueStr.startsWith("[") -> "Array"
                    valueStr == "true" || valueStr == "false" -> "Boolean"
                    valueStr.matches(Regex("""^-?\d+(\.\d+)?$""")) -> "Number"
                    valueStr.startsWith("'") || valueStr.startsWith("\"") || valueStr.startsWith("`") -> "String"
                    else -> "Unknown"
                }
                
                if (typeInfo == "Unknown") {
                    fields.add(key)
                } else {
                    fields.add("$key: $typeInfo")
                }
            }
        }
        return fields
    }

    /**
     * 컴포넌트 메서드 목록 추출 (최대 20개)
     * function, const = () =>, async 구문 대응
     */
    private fun extractMethods(content: String): List<String> {
        val methods = mutableListOf<String>()
        
        // 1. function 선언 (async 포함)
        val funcRegex = Regex("""(?:export\s+)?(?:async\s+)?function\s+([a-zA-Z0-9_]+)\s*\(""")
        funcRegex.findAll(content).forEach { methods.add(it.groupValues[1]) }
        
        // 2. 화살표 함수 할당
        val arrowRegex = Regex("""(?:const|let|var)\s+([a-zA-Z0-9_]+)\s*=\s*(?:async\s+)?\([^)]*\)\s*=>""")
        arrowRegex.findAll(content).forEach { methods.add(it.groupValues[1]) }
        
        // 3. Options API methods: { methodName() { ... } } (근사치 추출)
        val optionsMethodRegex = Regex("""([a-zA-Z0-9_]+)\s*\([^)]*\)\s*\{""")
        
        // 전체 파일에서 단어(메서드명) + () + { 구조를 찾아, if/for/while/catch 제외하고 추가
        val excludeWords = setOf("if", "for", "while", "switch", "catch", "function", "return")
        optionsMethodRegex.findAll(content).forEach { match ->
            val name = match.groupValues[1]
            if (!excludeWords.contains(name) && !methods.contains(name)) {
                // 정확도를 위해 Options API 구조 내부인지 판별하기 어렵지만, 최대한 담고 후처리.
                methods.add(name)
            }
        }
        
        return methods
    }

    /**
     * API 호출 패턴 추출 (Phase 1: 인라인 리터럴 또는 변수명까지만 분석)
     * 예: api.post('/endpoint', data) -> "POST /endpoint (payload: data)"
     */
    private fun extractApiCalls(content: String): List<String> {
        val calls = mutableListOf<String>()
        
        // axios.post('/url', payload) 또는 api.put('/url', { ... })
        // 그룹 1: http method, 그룹 2: url, 그룹 3: payload
        val apiRegex = Regex("""(?:api|axios|http)\s*\.?\s*(get|post|put|delete|patch)\s*\(\s*['"`]([^'"`]+)['"`](?:\s*,\s*([^)]+))?\s*\)""")
        
        apiRegex.findAll(content).forEach { match ->
            val httpMethod = match.groupValues[1].uppercase()
            val url = match.groupValues[2]
            var payload = match.groupValues.getOrNull(3)?.trim() ?: ""
            
            // 콜백이나 복잡한 설정 객체가 따라오면 정리 (대략적)
            if (payload.isNotEmpty()) {
                val configCommaIdx = payload.indexOf(',')
                if (configCommaIdx > 0 && !payload.startsWith("{")) {
                    payload = payload.substring(0, configCommaIdx).trim()
                }
                
                // 인라인 객체면 간소화
                if (payload.startsWith("{")) {
                    // 필드명만 추출 시도
                    val fields = extractFields1Depth(payload.substringBeforeLast("}") + "}")
                    payload = "Inline { " + fields.map { it.split(":")[0] }.joinToString(", ") + " }"
                }
            }
            
            val payloadInfo = if (payload.isNotEmpty()) " (payload: $payload)" else ""
            calls.add("$httpMethod $url$payloadInfo")
        }
        
        return calls
    }
}
