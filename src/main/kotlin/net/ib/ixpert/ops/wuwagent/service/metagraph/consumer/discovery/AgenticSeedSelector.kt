package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraphQueryable
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FileNode

class AgenticSeedSelector(
    private val llmClient: LLMClient,
    private val projectBasePath: String? = null
) : SeedSelector {

    private val gson = Gson()

    override fun selectSeeds(srText: String, graph: ProjectGraphQueryable): SeedSelectionResult {
        val tokens = srText.split(Regex("[^A-Za-z0-9_]+"))
            .filter { it.length >= 4 }
            .map { it.lowercase() }

        val allBackend = graph.files.values
        val allFrontend = graph.resourceNodes.filter { 
            it.path.endsWith(".jsp") || it.path.endsWith(".html") || 
            it.path.endsWith(".js") || it.path.endsWith(".vue") || it.path.endsWith(".tsx") 
        }

        val matchedBackend = allBackend.filter { fileNode ->
            tokens.any { token -> fileNode.className.lowercase().startsWith(token) }
        }
        val matchedFrontend = allFrontend.filter { resourceNode ->
            val name = resourceNode.path.substringAfterLast('/').substringBeforeLast('.')
            tokens.any { token -> name.lowercase().startsWith(token) }
        }

        val candidatesListPair = run {
            val b = matchedBackend.map { "${it.className} (${it.packageName})" }
            val f = matchedFrontend.map { "${it.path.substringAfterLast('/')} (${it.path.substringBeforeLast('/', "")})" }
            val track1 = (b + f)
            
            fun tokenize(text: String): List<String> {
                val words = text.lowercase().replace(Regex("[^a-z0-9\\uAC00-\\uD7A3\\s]"), " ")
                    .split(Regex("\\s+"))
                    .filter { it.isNotBlank() }
                val t = mutableListOf<String>()
                for (w in words) {
                    if (w.length >= 2) t.addAll(w.windowed(2)) else t.add(w)
                }
                return t
            }
            
            val dict = DomainDictionary.load(graph)
            val qTokensBase = tokenize(srText).toMutableList()

            // SR 원문의 도메인 명사(최소 불용어 제외)에 대해 사전 번역 토큰 선별 주입
            val BM25_STOPWORDS = setOf("관리", "조회", "화면", "서비스", "컨트롤러", "추가", "수정", "삭제", "등록", "상세", "목록", "처리", "신규", "개발")
            val srDomainWords = srText.split(Regex("[^가-힣a-zA-Z0-9]")).filter { it.length >= 2 && it !in BM25_STOPWORDS }
            for (word in srDomainWords) {
                val translated = dict.translate(word)
                if (translated.isNotEmpty()) {
                    for (t in translated) {
                        if (t.length >= 2) qTokensBase.addAll(t.windowed(2)) else qTokensBase.add(t)
                    }
                }
            }
            var domainKeywords = listOf<String>()
            try {
                val domainSystemPrompt = """
                    당신은 엔터프라이즈 시스템의 도메인 분석 전문가입니다.
                    주어진 요구사항(SR)을 읽고, 이 요구사항이 시스템의 어느 핵심 도메인들에 속할 가능성이 있는지 연관된 핵심 도메인 명사(예: 상품, 주문, 클레임, 회원, 전시, 마케팅, 쿠폰, 장바구니 등)를 관련도 순으로 최대 3개 추출하세요.
                    반드시 명사 단어들만 쉼표(,)로 구분하여 정확하게 답변해야 하며, 마침표나 추가 설명은 절대 포함하지 마세요. (예: "클레임, 주문, 상품")
                """.trimIndent()
                val domainRes = llmClient.chat(domainSystemPrompt, srText, 50, null)
                val rawKeywords = domainRes?.message?.content?.trim() ?: ""
                println("[AgenticSeedSelector] Think - Raw LLM Output: $rawKeywords")
                domainKeywords = rawKeywords.split(",").map { it.replace(Regex("[^가-힣a-zA-Z]"), "").trim() }.filter { it.isNotEmpty() }.take(3)
                println("[AgenticSeedSelector] Think - Extracted Keywords: $domainKeywords")
            } catch (e: Exception) {
                println("[AgenticSeedSelector] Think failed: ${e.message}")
            }
            
            // Build document index once
            val documents = mutableMapOf<String, Pair<String, List<String>>>() // ID -> Pair(PromptString, Tokens)
            var totalLength = 0
            
            allBackend.forEach { fileObj ->
                val contentBuilder = StringBuilder()
                contentBuilder.append(fileObj.className ?: "").append(" ")
                contentBuilder.append(fileObj.path).append(" ")
                contentBuilder.append(fileObj.localName ?: "").append(" ")
                fileObj.koreanComments?.forEach { contentBuilder.append(it).append(" ") }
                fileObj.demMethods?.forEach { dm ->
                    contentBuilder.append(dm.methodName ?: "").append(" ")
                    contentBuilder.append(dm.localName ?: "").append(" ")
                }
                val docTokens = tokenize(contentBuilder.toString())
                val promptStr = "${fileObj.className} (${fileObj.packageName})"
                documents[fileObj.path] = promptStr to docTokens
                totalLength += docTokens.size
            }
            
            allFrontend.forEach { resourceNode ->
                val docTokens = tokenize(resourceNode.path)
                val promptStr = "${resourceNode.path.substringAfterLast('/')} (${resourceNode.path.substringBeforeLast('/', "")})"
                documents[resourceNode.path] = promptStr to docTokens
                totalLength += docTokens.size
            }
            
            val N = documents.size
            val avgdl = if (N > 0) totalLength.toDouble() / N else 1.0
            val k1 = 1.5
            val b_param = 0.75
            
            var bestTrack2 = emptyList<String>()
            var finalJudgePicks = emptyList<String>()
            
            // Loop (Act -> Observe)
            for (keyword in domainKeywords) {
                println("\n[AgenticSeedSelector] Loop - Act: Testing keyword '$keyword'")
                val qTokens = qTokensBase.toMutableList()
                for (i in 1..5) qTokens.add(keyword)
                
                val prefixes = dict.translate(keyword)
                if (prefixes.isNotEmpty()) {
                    prefixes.forEach { p ->
                        for (i in 1..5) qTokens.add(p)
                    }
                }
                println("[BM25-DIAG] Keyword: '$keyword' -> Translated Tokens: $prefixes | Final qTokens: ${qTokens.toSet()}")
                
                val df = mutableMapOf<String, Int>()
                for (q in qTokens) df[q] = documents.values.count { it.second.contains(q) }
                
                val scores = documents.map { (path, pair) ->
                    var score = 0.0
                    val docLen = pair.second.size
                    for (q in qTokens) {
                        val tf = pair.second.count { it == q }
                        if (tf > 0) {
                            val n = df[q] ?: 0
                            val idf = Math.log((N - n + 0.5) / (n + 0.5) + 1.0)
                            score += idf * (tf * (k1 + 1)) / (tf + k1 * (1 - b_param + b_param * (docLen / avgdl)))
                        }
                    }
                    path to score
                }.sortedByDescending { it.second }
                
                val top30Ids = scores.take(30).map { it.first }
                val top10Prompts = top30Ids.take(10).mapNotNull { documents[it]?.first }
                
                println("[BM25-DIAG] Top 5 Scores: ${scores.take(5).map { "${it.first.substringAfterLast('/')} (${String.format("%.2f", it.second)})" }}")
                println("[AgenticSeedSelector] Loop - Observe: Evaluating Top 10 nodes with LLM-as-a-Judge")
                println("[AgenticSeedSelector] Top 10 Nodes given to Judge: $top10Prompts")
                val judgeSystemPrompt = """
                    당신은 이커머스 시스템의 도메인 분석 심판입니다.
                    아래는 특정 키워드로 검색된 시스템 클래스 목록입니다.
                    이 클래스들이 다음 요구사항(SR)을 구현하는 데 핵심적으로 관련되어 있는지 판단하세요.
                    
                    반드시 아래 JSON 형식으로만 답변하세요. 마크다운 기호 없이 순수 JSON만 출력하세요.
                    {
                      "relevant": true,
                      "confidence": 85,
                      "matched_nodes": ["클래스명1", "클래스명2"]
                    }
                """.trimIndent()
                
                val judgeUserPrompt = """
                    요구사항(SR): $srText
                    
                    검색된 클래스 목록:
                    ${top10Prompts.joinToString("\n")}
                """.trimIndent()
                
                try {
                    val judgeRes = llmClient.chat(judgeSystemPrompt, judgeUserPrompt, 300, null)
                    var jsonStr = judgeRes?.message?.content?.trim() ?: ""
                    jsonStr = jsonStr.replace(Regex("```json|```"), "").trim()
                    if (jsonStr.contains("{")) {
                        jsonStr = jsonStr.substring(jsonStr.indexOf("{"), jsonStr.lastIndexOf("}") + 1)
                    }
                    
                    val judgeOutput = gson.fromJson(jsonStr, Map::class.java) as Map<String, Any>
                    val isRelevant = judgeOutput["relevant"] as? Boolean ?: false
                    val matchedNodesRaw = judgeOutput["matched_nodes"] as? List<*>
                    val matchedNodes = matchedNodesRaw?.map { it.toString() } ?: emptyList()
                    
                    println("[AgenticSeedSelector] Judge Output: relevant=$isRelevant, matched_nodes=$matchedNodes")
                    
                    if (isRelevant) {
                        val hasActualMatch = matchedNodes.any { nodeName -> top10Prompts.any { it.contains(nodeName) } }
                        if (hasActualMatch) {
                            println("[AgenticSeedSelector] Loop - Terminating! Keyword '$keyword' accepted by strict stopping rule.")
                            bestTrack2 = top30Ids
                            finalJudgePicks = matchedNodes
                            break
                        } else {
                            println("[AgenticSeedSelector] Loop - Rejected: Judge returned relevant=true, but matched_nodes were fabricated or missing.")
                        }
                    } else {
                        println("[AgenticSeedSelector] Loop - Rejected: Judge deemed results irrelevant.")
                    }
                } catch (e: Exception) {
                    println("[AgenticSeedSelector] Loop - Judge error: ${e.message}")
                }
            }
            
            if (bestTrack2.isEmpty()) {
                println("[AgenticSeedSelector] All hypotheses exhausted or failed. Returning base lexical BM25 results.")
                
                // Base lexical score
                val df = mutableMapOf<String, Int>()
                for (q in qTokensBase) df[q] = documents.values.count { it.second.contains(q) }
                val fallbackScores = documents.map { (_, pair) ->
                    var score = 0.0
                    val docLen = pair.second.size
                    for (q in qTokensBase) {
                        val tf = pair.second.count { it == q }
                        if (tf > 0) {
                            val n = df[q] ?: 0
                            val idf = Math.log((N - n + 0.5) / (n + 0.5) + 1.0)
                            score += idf * (tf * (k1 + 1)) / (tf + k1 * (1 - b_param + b_param * (docLen / avgdl)))
                        }
                    }
                    pair.first to score
                }.sortedByDescending { it.second }
                
                bestTrack2 = fallbackScores.take(30).map { it.first }
            }
            
            val track2 = bestTrack2
            println("[AgenticSeedSelector] Track 1 (Lexical) matched ${track1.size} candidates.")
            println("[AgenticSeedSelector] Track 2 (Agentic BM25) returned ${track2.size} candidates.")
            
            val union = (track1 + track2).distinct()
            println("[AgenticSeedSelector] Final Candidates (Union, count=${union.size}): $union")
            Pair(union, finalJudgePicks)
        }

        val candidatesList = candidatesListPair.first
        val finalJudgePicks = candidatesListPair.second
        
        val candidates = candidatesList.joinToString("\n")

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
            - [구체성 우선 및 추측성 선정 금지] SR이 요구하는 기능에 가장 직접적이고 구체적으로 대응하는 클래스를 Seed로 선정하세요. 후보 중에 이미 해당 기능에 특화된 전용 클래스가 존재하는 경우, "그 전용 클래스가 실제 수정 대상이 아닐 수도 있으니" 또는 "혹시 다른 곳에 로직이 있을 수도 있으니" 같은 가정에 근거해 더 포괄적이거나 일반적인 클래스를 보험용으로 추가 선정하지 마세요. 다만 SR이 명시적으로 공통·공유 로직의 수정을 요구하는 경우에는 해당 공통 클래스를 선정할 수 있습니다.
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
            
            val allRaw = candidatesListPair.first + candidatesListPair.second
            val isFrontendByRule = srText.contains("어드민") || srText.contains("화면") || 
                                  srText.contains("UI") || srText.contains("목록") || 
                                  srText.contains("등록") || srText.contains("조회") ||
                                  srText.contains("프론트") || srText.contains("vue") || 
                                  srText.contains("jsp") || srText.contains("js")

            val toolCall = response?.toolCalls?.firstOrNull { it.function.name == "submit_seeds" }
            if (toolCall != null) {
                val res = gson.fromJson(toolCall.function.arguments, SeedSelectionResult::class.java)
                val finalFrontend = res.frontendRelevant || isFrontendByRule
                return res.copy(
                    judgePicks = finalJudgePicks,
                    rawCandidates = allRaw,
                    frontendRelevant = finalFrontend
                )
            } else if (!response?.content.isNullOrBlank()) {
                // Some models return the tool arguments directly in the text content
                var cleanJson = response!!.content!!
                cleanJson = cleanJson.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "").trim()
                val jsonMatch = Regex("\\{.*\\}", RegexOption.DOT_MATCHES_ALL).find(cleanJson)
                val cleanJsonForParse = jsonMatch?.value ?: cleanJson.replace("```json", "").replace("```", "").trim()
                try {
                    val parsed = gson.fromJson(cleanJsonForParse, SeedSelectionResult::class.java)
                    val finalFrontend = parsed.frontendRelevant || isFrontendByRule
                    return parsed.copy(
                        judgePicks = finalJudgePicks,
                        rawCandidates = allRaw,
                        frontendRelevant = finalFrontend
                    )
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
                            frontendRelevant = isFrontendByRule,
                            reasoning = "Parsed from JSON array fallback",
                            judgePicks = finalJudgePicks,
                            rawCandidates = allRaw
                        )
                    }
                    throw e
                }
            }
        } catch (e: Exception) {
            println("Failed to get ToolCall from LLM: ${e.message}")
        }

        val fallbackRes = fallbackSelection(srText, graph)
        return fallbackRes.copy(judgePicks = finalJudgePicks)
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
