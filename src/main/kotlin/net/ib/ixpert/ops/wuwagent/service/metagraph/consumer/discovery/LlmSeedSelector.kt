package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraphQueryable
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FileNode

class LlmSeedSelector(private val llmClient: LLMClient) : SeedSelector {

    private val gson = Gson()

    override fun selectSeeds(srText: String, graph: ProjectGraphQueryable): SeedSelectionResult {
        // 클래스와 패키지 정보를 함께 제공하여 LLM이 도메인을 유추하기 쉽게 함
        val backendCandidates = graph.files.values
            .map { "${it.className} (${it.packageName})" }
            .distinct()
        
        val frontendCandidates = graph.resourceNodes
            .filter { it.path.endsWith(".jsp") || it.path.endsWith(".html") || it.path.endsWith(".js") || it.path.endsWith(".vue") || it.path.endsWith(".tsx") }
            .map { "${it.path.substringAfterLast('/')} (${it.path.substringBeforeLast('/', "")})" }
            .distinct()

        val candidates = (backendCandidates + frontendCandidates).joinToString("\n")

        val frameworkName = graph.frameworkDisplayName
        val additionalContext = if (graph.resolvedFrameworkType == net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType.ANYFRAME_AP) {
            """
            - Anyframe Enterprise 프레임워크 특징:
              - BIZ: 핵심 업무 로직 (보통 *BIZ 클래스)
              - SVC: 서비스 인터페이스(*SVC) 및 구현체(*SVCImpl)
              - DATA_ACCESS: DB 접근 객체 (보통 *DEM 또는 *DQM)
              - VO: Value Object (BVO, SVO, DVO 등으로 계층화됨)
              - BIZ_UTIL: 공통 로직 (보통 *Util)
            """.trimIndent()
        } else ""

        val systemPrompt = """
            당신은 $frameworkName 프로젝트의 코드 변경 분석가입니다.
            아래 SR(요구사항)을 읽고, 변경이 시작되어야 할 핵심 진입점(Seed) 클래스를 선정하세요.
            반드시 제공된 `submit_seeds` 도구를 호출하여 결과를 제출하세요.
            
            ## 지침
            - `seedClasses`에는 최대 3~4개의 핵심 클래스명(패키지 제외)을 지정하세요.
            - `changeIntent`는 MODIFY, CREATE, DELETE 중 하나여야 합니다.
            - `layerHint`는 변경이 걸치는 계층(ENTITY, SERVICE, PRESENTATION 등)을 배열로 제공하세요.
            - `frontendRelevant`는 화면 변경 포함 여부(true/false)입니다.
            - `frontendRelevant`가 true이면, 관련될 가능성이 높은 Vue/React 파일명 키워드를 `frontendFileHints`에 포함하세요. SR 텍스트의 화면명을 영문 파일명으로 변환하세요. (예: '마이페이지' → 'MyPage', '장바구니' → 'Cart')
            - `reasoning`은 전체 요구사항의 요약과 함께, 각 대상 파일별 선정 사유를 반드시 "1. [파일명] - [사유]", "2. [파일명] - [사유]" 형식으로 번호를 매겨 상세히 작성하세요.
            - 중요: JSON 응답 생성 시, reasoning 필드 값 내부에 실제 줄바꿈 문자(\n)를 사용하지 마세요. 줄바꿈 대신 띄어쓰기나 마침표를 사용하세요.
            $additionalContext
        """.trimIndent()

        val userPrompt = """
            ## SR
            $srText

            ## 프로젝트 클래스 목록 (클래스명 (패키지명))
            $candidates
        """.trimIndent()

        val tool = net.ib.ixpert.ops.wuwagent.model.ToolDefinition(
            type = "function",
            function = net.ib.ixpert.ops.wuwagent.model.FunctionDefinition(
                name = "submit_seeds",
                description = "요구사항 분석 결과(Seed 클래스 및 인텐트)를 제출합니다.",
                parameters = net.ib.ixpert.ops.wuwagent.model.FunctionParameters(
                    type = "object",
                    properties = mapOf(
                        "seedClasses" to net.ib.ixpert.ops.wuwagent.model.PropertyDefinition(
                            type = "array",
                            description = "변경의 진입점이 되는 핵심 클래스 목록",
                            items = net.ib.ixpert.ops.wuwagent.model.PropertyDefinition(type = "string")
                        ),
                        "changeIntent" to net.ib.ixpert.ops.wuwagent.model.PropertyDefinition(
                            type = "string",
                            description = "작업 의도",
                            enum = listOf("MODIFY", "CREATE", "DELETE")
                        ),
                        "layerHint" to net.ib.ixpert.ops.wuwagent.model.PropertyDefinition(
                            type = "array",
                            description = "영향을 받는 계층 목록",
                            items = net.ib.ixpert.ops.wuwagent.model.PropertyDefinition(type = "string")
                        ),
                        "frontendRelevant" to net.ib.ixpert.ops.wuwagent.model.PropertyDefinition(
                            type = "boolean",
                            description = "프론트엔드 연관 여부"
                        ),
                        "reasoning" to net.ib.ixpert.ops.wuwagent.model.PropertyDefinition(
                            type = "string",
                            description = "선정 근거"
                        ),
                        "frontendFileHints" to net.ib.ixpert.ops.wuwagent.model.PropertyDefinition(
                            type = "array",
                            description = "frontendRelevant가 true일 때, 관련될 가능성이 높은 프론트엔드 파일명 키워드 (예: MyPage, ProductDetail, Cart)",
                            items = net.ib.ixpert.ops.wuwagent.model.PropertyDefinition(type = "string")
                        )
                    ),
                    required = listOf("seedClasses", "changeIntent", "layerHint", "frontendRelevant", "reasoning")
                )
            )
        )

        val messages = listOf(
            net.ib.ixpert.ops.wuwagent.model.ChatMessage(role = "user", content = userPrompt)
        )

        try {
            val response = llmClient.chatWithTools(
                systemPrompt = systemPrompt,
                messages = messages,
                maxTokens = 1500,
                tools = listOf(tool),
                toolChoice = mapOf("type" to "function", "function" to mapOf("name" to "submit_seeds"))
            )
            
            val toolCall = response?.toolCalls?.firstOrNull { it.function.name == "submit_seeds" }
            if (toolCall != null) {
                return gson.fromJson(toolCall.function.arguments, SeedSelectionResult::class.java)
            } else if (!response?.content.isNullOrBlank()) {
                // Some models return the tool arguments directly in the text content
                var cleanJson = response!!.content!!
                cleanJson = cleanJson.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "").trim()
                val jsonMatch = Regex("\\{.*\\}", RegexOption.DOT_MATCHES_ALL).find(cleanJson)
                val cleanJsonForParse = jsonMatch?.value ?: cleanJson.replace("```json", "").replace("```", "").trim()
                try {
                    return gson.fromJson(cleanJsonForParse, SeedSelectionResult::class.java)
                } catch (e: Exception) {
                    val arrayMatch = Regex("\\[.*\\]", RegexOption.DOT_MATCHES_ALL).find(cleanJson)
                    val arrayJson = arrayMatch?.value ?: cleanJson.replace("```json", "").replace("```", "").trim()
                    val type = object : com.google.gson.reflect.TypeToken<List<String>>() {}.type
                    val list: List<String> = gson.fromJson(arrayJson, type)
                    if (list.isNotEmpty()) {
                        return SeedSelectionResult(
                            seedClasses = list,
                            changeIntent = ChangeIntent.MODIFY,
                            layerHint = listOf("SERVICE", "PRESENTATION"),
                            frontendRelevant = true,
                            reasoning = "Parsed from JSON array fallback"
                        )
                    }
                    throw e
                }
            }
        } catch (e: Exception) {
            println("Failed to get ToolCall from LLM: ${e.message}")
        }

        return fallbackSelection(srText, graph)
    }

    private fun fallbackSelection(srText: String, graph: ProjectGraphQueryable): SeedSelectionResult {
        // 영단어 추출 후 className 부분 일치 검사
        val englishTokens = Regex("[a-zA-Z]{3,}").findAll(srText).map { it.value }.toList()
        // 한글 명사 추출 (간단히 2글자 이상) 및 불용어 제거
        val stopWords = setOf("추가", "생성", "신규", "삭제", "제거", "수정", "변경", "기능", "항목", "목록", "조회", "화면", "출력", "관련", "처리", "동작", "적용", "로직", "기반", "부분")
        val koreanTokens = Regex("[가-힣]{2,}").findAll(srText)
            .map { it.value }
            .filter { it !in stopWords }
            .toList()
        
        val seeds = mutableSetOf<String>()
        
        for (node in graph.files.values) {
            val className = node.className
            
            // 1. 영어 매칭 (클래스명)
            if (englishTokens.isNotEmpty() && englishTokens.any { className.contains(it, ignoreCase = true) }) {
                seeds.add(className)
            }
            
            // 2. 한글 매칭 (localName, koreanComments)
            if (koreanTokens.isNotEmpty()) {
                val matchLocalName = node.localName?.let { ln -> koreanTokens.any { ln.contains(it) } } == true
                val matchComments = node.koreanComments.any { c -> koreanTokens.any { c.contains(it) } }
                if (matchLocalName || matchComments) {
                    seeds.add(className)
                }
            }
            
            if (seeds.size >= 5) break
        }

        // 한국어 키워드 기반 유추
        val isCreate = srText.contains("추가") || srText.contains("생성") || srText.contains("신규")
        val isDelete = srText.contains("삭제") || srText.contains("제거")
        val isFrontend = srText.contains("화면") || srText.contains("UI") || srText.contains("표시")

        return SeedSelectionResult(
            seedClasses = seeds.toList(),
            changeIntent = if (isCreate) ChangeIntent.CREATE else if (isDelete) ChangeIntent.DELETE else ChangeIntent.MODIFY,
            layerHint = listOf("SERVICE", "PRESENTATION"), // 임의 기본값
            frontendRelevant = isFrontend,
            reasoning = "Fallback: Keyword matching due to LLM failure or timeout"
        )
    }
}
