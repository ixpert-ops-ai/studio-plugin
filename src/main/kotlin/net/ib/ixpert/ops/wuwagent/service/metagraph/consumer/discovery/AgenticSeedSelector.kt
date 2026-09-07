package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.model.ChatMessage
import net.ib.ixpert.ops.wuwagent.model.ToolDefinition
import net.ib.ixpert.ops.wuwagent.model.FunctionDefinition
import net.ib.ixpert.ops.wuwagent.model.FunctionParameters
import net.ib.ixpert.ops.wuwagent.model.PropertyDefinition
import net.ib.ixpert.ops.wuwagent.agent.L1ClarificationBridge
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraphQueryable
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FileNode

class AgenticSeedSelector(
    private val llmClient: LLMClient,
    private val projectBasePath: String? = null,
    private val clarificationBridge: L1ClarificationBridge? = null
) : SeedSelector {

    private val gson = Gson()

    override fun selectSeeds(srText: String, graph: ProjectGraphQueryable): SeedSelectionResult {
        println("\n================================================================")
        println("   [AgenticGraphExplorer] Starting Autonomous Graph Search Loop")
        println("================================================================")
        println("SR: $srText")

        val toolsEngine = AgenticGraphTools(graph)
        val toolDefinitions = AgenticGraphTools.DEFINITIONS

        val systemPrompt = """
            당신은 시스템의 소스코드 및 메타그래프를 능동적으로 탐색하는 전문 AI 분석 에이전트입니다.
            주어진 요구사항(SR)을 분석하여, 기능을 구현하거나 수정하는 데 반드시 필요한 핵심 Seed 클래스들을 찾아내야 합니다.
            
            당신에게는 4개의 그래프 탐색 도구가 제공됩니다:
            1. search_graph_nodes(query, domain_hint): 메타그래프에서 클래스명(약어/CamelCase), 한글 주석/로컬명, 메서드명을 3-Tier 구조 매칭으로 검색합니다.
            2. inspect_node_detail(class_name): 특정 클래스의 메서드, 주석, 호출하는 클래스(dependsOn) 및 호출받는 클래스(dependedBy)를 상세 조회합니다.
            3. expand_connected_nodes(class_name, direction, max_hops): 기준 클래스로부터 연결된 호출/참조 체인을 확장합니다.
            4. confirm_final_seeds(seed_classes, rationale): 탐색 결과를 종합하여 최종 Seed 클래스들을 확정하고 탐색을 완료합니다.
            
            [도메인 무관 상태 전이 및 턴 규율]
            - 탐색은 반드시 3단계 전이 흐름(Search -> Inspect -> Confirm)으로 진행해야 합니다:
              1단계 (Search): 요구사항(SR)의 핵심 목표 및 주요 업무 약어를 조합하여 `search_graph_nodes`를 호출합니다.
              2단계 (Inspect & Verify): 검색 결과 목록에서 요구사항과 관련된 유력한 핵심 후보 클래스(Service, BIZ, VO 등)를 선택하여 `inspect_node_detail` 또는 `expand_connected_nodes`로 세부 메서드/의존관계를 확인합니다.
              3단계 (Confirm): `inspect_node_detail`로 후보를 확인한 후에는 불필요한 추가 검색을 멈추고 즉시 `confirm_final_seeds`를 호출하여 핵심 업무 클래스 1~4개를 최종 확정하고 탐색을 종료하세요.
            - 요구사항(SR)에 '신규 개발', '신규 생성' 등의 표현이 있더라도, 시스템 메타그래프에 이미 존재하는 관련 기준 클래스(Controller, Service, BIZ, Repository, VO/DTO 등)를 계층에 구애받지 않고 폭넓게 Seed 후보로 확정하세요.
            - 공통 유틸리티(StringUtil, ConstantUtil 등)나 단순 로그 클래스는 Seed로 확정하지 말고, 실제 비즈니스 로직을 수행하는 서비스(Service/SVC), BIZ, VO 클래스를 Seed로 확정하세요.
            
            [도구 호출 시 설명 작성 지침]
            - 도구를 호출할 때, 이번 탐색/검색의 의도, 발견된 결과에 대한 해석, 그리고 다음에 무엇을 할 것인지에 대한 간결한 설명(1~3문장)을 assistant 메시지 텍스트로 함께 작성하세요.
        """.trimIndent()

        val messages = mutableListOf(
            ChatMessage(role = "user", content = "요구사항(SR):\n$srText\n\n위 요구사항을 분석하여 핵심 Seed 클래스들을 탐색하고 확정해주세요.")
        )

        val searchTool = toolDefinitions.find { it.function.name == "search_graph_nodes" }!!
        val inspectTool = toolDefinitions.find { it.function.name == "inspect_node_detail" }!!
        val expandTool = toolDefinitions.find { it.function.name == "expand_connected_nodes" }!!
        val confirmTool = toolDefinitions.find { it.function.name == "confirm_final_seeds" }!!

        val phase1Tools = listOf(searchTool, inspectTool, expandTool, confirmTool)
        val phase2Tools = listOf(inspectTool, expandTool, confirmTool)

        var finalSeeds = listOf<String>()
        var finalRationale = "기본 탐색 완료"
        val maxTurns = 6
        var totalSearchCount = 0
        var lastSearchQuery = ""
        var lastSearchCandidates = emptyList<Map<String, Any>>()
        var lastSearchDomains = emptyList<String>()
        val executedQueries = mutableSetOf<String>()
        val accumulatedTopCandidates = linkedMapOf<String, Double>()

        for (turn in 1..maxTurns) {
            println("\n[AgenticGraphExplorer] === TURN $turn ===")
            
            // Phase gating: 2턴 이상이거나 검색을 3회 이상 수행했으면 Inspect/Confirm 단계로 강제 전이
            val activeTools = if (turn >= 3 || totalSearchCount >= 3) phase2Tools else phase1Tools
            val activeToolChoice = "auto" // 강제 confirm 제거하고 항상 자율 판단(auto) 유지

            val response = try {
                llmClient.chatWithTools(
                    systemPrompt = systemPrompt,
                    messages = messages,
                    maxTokens = 2048,
                    tools = activeTools,
                    toolChoice = activeToolChoice,
                    temperature = 0.0
                )
            } catch (e: Exception) {
                println("[AgenticGraphExplorer] Turn $turn Error calling LLM: ${e.message}")
                break
            }

            val choice = response?.choices?.firstOrNull()
            val assistantMessage = choice?.message
            if (assistantMessage == null) {
                println("[AgenticGraphExplorer] Turn $turn: No response from LLM.")
                break
            }

            messages.add(assistantMessage)

            val reasoning = assistantMessage.reasoningContent
            if (!reasoning.isNullOrBlank()) {
                println("[AgenticGraphExplorer] Turn $turn Thought:\n$reasoning")
            }
            if (!assistantMessage.content.isNullOrBlank()) {
                println("[AgenticGraphExplorer] Turn $turn Message: ${assistantMessage.content}")
            }

            val toolCalls = assistantMessage.toolCalls
            if (toolCalls.isNullOrEmpty()) {
                println("[AgenticGraphExplorer] Turn $turn: LLM finished without tool calls.")
                break
            }

            var confirmed = false
            for (tc in toolCalls) {
                val fnName = tc.function.name
                val argsJson = tc.function.arguments
                println("\n>>> [AgenticGraphExplorer] Tool Call: $fnName")
                println(">>> Args: $argsJson")

                val toolResultString = when (fnName) {
                    "search_graph_nodes" -> {
                        val args = gson.fromJson(argsJson, Map::class.java)
                        val q = args["query"]?.toString() ?: ""
                        val hint = args["domain_hint"]?.toString()
                        val queryKey = "${q.trim().lowercase()}::${hint?.trim()?.lowercase() ?: ""}"

                        // [가드 1] 중복 쿼리 차단 가드
                        if (executedQueries.contains(queryKey)) {
                            println("<<< [Guard 1 Warning] Duplicate search query detected: '$q' (hint: $hint)")
                            gson.toJson(mapOf(
                                "warning" to "이미 실행된 검색어입니다. 동일한 검색을 반복하지 말고, 이전 검색 결과 후보 중 관련 노드를 inspect_node_detail로 확인하거나 confirm_final_seeds를 호출하세요.",
                                "status" to "DUPLICATE_QUERY"
                            ))
                        } else {
                            executedQueries.add(queryKey)
                            totalSearchCount++
                            val res = toolsEngine.searchGraphNodes(q, hint, limit = 10)
                            println("<<< Result Count: ${res.size}")
                            res.forEachIndexed { i, r ->
                                val cName = r["className"]?.toString() ?: ""
                                val score = (r["matchScore"] as? Number)?.toDouble() ?: 0.0
                                println("    ${i+1}. $cName (${r["fileType"]}) - ${r["localName"]} (Score: $score)")
                                if (cName.isNotBlank() && score > 0) {
                                    val currentMax = accumulatedTopCandidates[cName] ?: 0.0
                                    accumulatedTopCandidates[cName] = maxOf(currentMax, score)
                                }
                            }

                            // 이번 턴 검색 결과 저장 (되묻기 payload용)
                            lastSearchQuery = q
                            lastSearchCandidates = res.take(5)
                            lastSearchDomains = res.map { extractDomainFromPath(it["path"]?.toString() ?: "") }.distinct()

                            if (executedQueries.size >= 2) {
                                val topCandidatesSummary = accumulatedTopCandidates.entries
                                    .sortedByDescending { it.value }
                                    .take(5)
                                    .map { it.key }
                                val responseWithGuidance = mapOf(
                                    "searchResults" to res,
                                    "guidance" to "충분한 검색이 수행되었습니다. 지금까지 발견된 유력 후보 $topCandidatesSummary 중 핵심 클래스를 inspect_node_detail로 확인하거나 confirm_final_seeds를 호출하여 확정하세요."
                                )
                                gson.toJson(responseWithGuidance)
                            } else {
                                gson.toJson(res)
                            }
                        }
                    }
                    "inspect_node_detail" -> {
                        val args = gson.fromJson(argsJson, Map::class.java)
                        val className = args["class_name"]?.toString() ?: ""
                        val res = toolsEngine.inspectNodeDetail(className)
                        println("<<< Inspected: $className (Methods: ${(res["methods"] as? List<*>)?.size ?: 0}, DependsOn: ${(res["dependsOn_Downstream"] as? List<*>)?.size ?: 0})")
                        if (className.isNotBlank()) {
                            accumulatedTopCandidates[className] = (accumulatedTopCandidates[className] ?: 50.0) + 20.0
                        }
                        gson.toJson(res)
                    }
                    "expand_connected_nodes" -> {
                        val args = gson.fromJson(argsJson, Map::class.java)
                        val className = args["class_name"]?.toString() ?: ""
                        val dir = args["direction"]?.toString() ?: "BOTH"
                        val hops = (args["max_hops"] as? Number)?.toInt() ?: 1
                        val res = toolsEngine.expandConnectedNodes(className, dir, hops)
                        println("<<< Expanded from $className: ${res["connectedCount"]} nodes connected")
                        gson.toJson(res)
                    }
                    "confirm_final_seeds" -> {
                        val args = gson.fromJson(argsJson, Map::class.java)
                        val rawSeeds = args["seed_classes"] as? List<*>
                        val seeds = rawSeeds?.map { it.toString() } ?: emptyList()
                        val rationale = args["rationale"]?.toString() ?: ""
                        println("<<< [AgenticGraphExplorer] SEEDS CONFIRMED! (${seeds.size} classes): $seeds")
                        println("<<< Rationale: $rationale")
                        finalSeeds = seeds
                        finalRationale = rationale
                        confirmed = true
                        gson.toJson(mapOf("status" to "SUCCESS", "confirmed_count" to seeds.size))
                    }
                    else -> {
                        gson.toJson(mapOf("error" to "알 수 없는 도구: $fnName"))
                    }
                }

                messages.add(
                    ChatMessage(
                        role = "tool",
                        content = toolResultString,
                        toolCallId = tc.id
                    )
                )
            }

            // 매 턴 검색 수행 직후: 검색 결과 + LLM 해석/계획을 사용자에게 공유하고 대화형 피드백 수신
            if (lastSearchCandidates.isNotEmpty() && !confirmed) {
                val explanation = assistantMessage.content ?: assistantMessage.reasoningContent ?: ""
                val userHint = clarificationBridge?.requestClarification(
                    turn = turn,
                    query = lastSearchQuery,
                    topCandidates = lastSearchCandidates,
                    domains = lastSearchDomains,
                    explanation = explanation
                )
                if (!userHint.isNullOrBlank()) {
                    println("[AgenticGraphExplorer] Turn $turn Clarification received from user: '$userHint'")
                    messages.add(
                        ChatMessage(
                            role = "user",
                            content = "사용자 피드백: \"$userHint\"\n이 피드백을 반영해 다음 탐색을 진행하세요. 방향이 틀렸다면 지금까지의 후보 도메인을 버리고 다른 업무 도메인이나 영문 약어로 search_graph_nodes를 다시 실행하세요."
                        )
                    )
                }
                lastSearchCandidates = emptyList() // 다음 턴 재초기화
            }

            if (confirmed) {
                println("[AgenticGraphExplorer] Exploration loop gracefully completed in Turn $turn.")
                break
            }
        }

        if (finalSeeds.isNotEmpty()) {
            return SeedSelectionResult(
                seedClasses = finalSeeds,
                changeIntent = ChangeIntent.MODIFY,
                layerHint = listOf("SERVICE", "BIZ", "PRESENTATION"),
                frontendRelevant = false,
                reasoning = finalRationale,
                judgePicks = finalSeeds,
                rawCandidates = finalSeeds
            )
        }

        // [가드 2] 턴 소진 시 누적 최상위 매칭 후보 자동 Seed 채택 (Fallback 이전 방어선)
        if (accumulatedTopCandidates.isNotEmpty()) {
            val topPicks = accumulatedTopCandidates.entries
                .sortedByDescending { it.value }
                .take(3)
                .map { it.key }
            println("[AgenticGraphExplorer] Guard 2: Auto-adopting top candidates after turn limit: $topPicks")
            return SeedSelectionResult(
                seedClasses = topPicks,
                changeIntent = ChangeIntent.MODIFY,
                layerHint = listOf("SERVICE", "BIZ", "PRESENTATION"),
                frontendRelevant = false,
                reasoning = "Auto-adopted top matching nodes from exploration after turn limit",
                judgePicks = topPicks,
                rawCandidates = topPicks
            )
        }

        println("[AgenticGraphExplorer] Fallback: No final seeds confirmed, applying heuristic fallback.")
        return fallbackSelection(srText, graph)
    }

    private fun fallbackSelection(srText: String, graph: ProjectGraphQueryable): SeedSelectionResult {
        val englishTokens = Regex("[a-zA-Z]{3,}").findAll(srText).map { it.value }.toList()
        val stopWords = setOf("추가", "생성", "신규", "삭제", "제거", "수정", "변경", "기능", "항목", "목록", "조회", "화면", "출력", "관련", "처리", "동작", "적용", "로직", "기반", "부분")
        val koreanTokens = Regex("[가-힣]{2,}").findAll(srText)
            .map { it.value }
            .filter { it !in stopWords }
            .toList()
        
        val seeds = mutableSetOf<String>()
        
        for (node in graph.files.values) {
            val className = node.className
            if (englishTokens.isNotEmpty() && englishTokens.any { className.contains(it, ignoreCase = true) }) {
                seeds.add(className)
            }
            if (koreanTokens.isNotEmpty()) {
                val matchLocalName = node.localName?.let { ln -> koreanTokens.any { ln.contains(it) } } == true
                val matchComments = node.koreanComments.any { c -> koreanTokens.any { c.contains(it) } }
                if (matchLocalName || matchComments) {
                    seeds.add(className)
                }
            }
            if (seeds.size >= 5) break
        }

        val isCreate = srText.contains("추가") || srText.contains("생성") || srText.contains("신규")
        val isDelete = srText.contains("삭제") || srText.contains("제거")
        val isFrontend = srText.contains("화면") || srText.contains("UI") || srText.contains("표시")

        return SeedSelectionResult(
            seedClasses = seeds.toList(),
            changeIntent = if (isCreate) ChangeIntent.CREATE else if (isDelete) ChangeIntent.DELETE else ChangeIntent.MODIFY,
            layerHint = listOf("SERVICE", "PRESENTATION"),
            frontendRelevant = isFrontend,
            reasoning = "Fallback: Keyword matching due to LLM failure or timeout"
        )
    }

    private fun extractDomainFromPath(path: String): String {
        val norm = path.replace('\\', '/').lowercase()
        val parts = norm.split('/').filter { it.isNotBlank() }
        val ismIdx = parts.indexOf("ism")
        if (ismIdx != -1 && ismIdx + 1 < parts.size) {
            return parts[ismIdx + 1]
        }
        val apcIdx = parts.indexOf("samsungcard")
        if (apcIdx != -1 && apcIdx + 1 < parts.size) {
            return parts[apcIdx + 1]
        }
        val mmIdx = parts.indexOf("membermarket")
        if (mmIdx != -1 && mmIdx + 1 < parts.size) {
            return parts[mmIdx + 1]
        }
        val surveyIdx = parts.indexOf("survey")
        if (surveyIdx != -1) {
            return "survey"
        }
        if (parts.size >= 2) {
            return parts[parts.size - 2]
        }
        return "root"
    }
}

/**
 * 그래프 툴콜 에이전트를 위한 4대 탐색 도구 엔진 및 3-Tier 구조 매처
 */
class AgenticGraphTools(
    private val graph: ProjectGraphQueryable
) {
    companion object {
        private val STRUCTURAL_STOPWORDS = setOf(
            "response", "request", "service", "impl", "vo", "dto", "controller", "repository",
            "mapper", "dao", "biz", "svo", "bvo", "dvo", "dem", "dqm", "util", "helper",
            "config", "entity", "model", "api", "app", "bo", "src", "main", "java", "com", "sc", "chn", "aps", "apc"
        )

        val DEFINITIONS: List<ToolDefinition> = listOf(
            ToolDefinition(
                type = "function",
                function = FunctionDefinition(
                    name = "search_graph_nodes",
                    description = "메타그래프에서 클래스명(약어/CamelCase), 한글 주석/로컬명, 메서드명, API 엔드포인트 URL을 검색하여 상위 관련 노드 목록을 조회합니다.",
                    parameters = FunctionParameters(
                        type = "object",
                        properties = mapOf(
                            "query" to PropertyDefinition(
                                type = "string",
                                description = "검색 키워드 (예: 'sspy otc', '삼성페이 서명', 'otc 서명', 'PointHistory', '/api/point')"
                            ),
                            "domain_hint" to PropertyDefinition(
                                type = "string",
                                description = "특정 패키지/도메인 경로 힌트 (선택 사항, 예: 'mm05', 'st.pstat', 'py01')"
                            )
                        ),
                        required = listOf("query")
                    )
                )
            ),
            ToolDefinition(
                type = "function",
                function = FunctionDefinition(
                    name = "inspect_node_detail",
                    description = "특정 클래스 노드의 상세 메타데이터(한글 주석, 메서드 목록, API 엔드포인트, 입출력 VO, 호출하는 클래스 dependsOn, 호출받는 클래스 dependedBy)를 상세 조회합니다.",
                    parameters = FunctionParameters(
                        type = "object",
                        properties = mapOf(
                            "class_name" to PropertyDefinition(
                                type = "string",
                                description = "조회할 클래스명 (예: 'APCMMSspyLkSVCImpl', 'PDsbUseController')"
                            )
                        ),
                        required = listOf("class_name")
                    )
                )
            ),
            ToolDefinition(
                type = "function",
                function = FunctionDefinition(
                    name = "expand_connected_nodes",
                    description = "특정 클래스 노드로부터 그래프 엣지(호출/참조 관계)를 따라 연결된 상하위 노드 체인을 확장 탐색합니다.",
                    parameters = FunctionParameters(
                        type = "object",
                        properties = mapOf(
                            "class_name" to PropertyDefinition(
                                type = "string",
                                description = "기준 클래스명"
                            ),
                            "direction" to PropertyDefinition(
                                type = "string",
                                description = "확장 방향: DOWNSTREAM, UPSTREAM, BOTH",
                                enum = listOf("DOWNSTREAM", "UPSTREAM", "BOTH")
                            ),
                            "max_hops" to PropertyDefinition(
                                type = "integer",
                                description = "탐색할 최대 홉 수 (기본 1, 최대 2)"
                            )
                        ),
                        required = listOf("class_name")
                    )
                )
            ),
            ToolDefinition(
                type = "function",
                function = FunctionDefinition(
                    name = "confirm_final_seeds",
                    description = "요구사항(SR) 구현에 필요한 핵심 수정 대상 및 참조 진입점 Seed 클래스들을 최종 확정하고 탐색을 완료합니다.",
                    parameters = FunctionParameters(
                        type = "object",
                        properties = mapOf(
                            "seed_classes" to PropertyDefinition(
                                type = "array",
                                items = PropertyDefinition(type = "string"),
                                description = "최종 확정된 Seed 클래스명 목록"
                            ),
                            "rationale" to PropertyDefinition(
                                type = "string",
                                description = "해당 클래스들을 Seed로 선정한 구체적 근거 및 구현 역할 설명"
                            )
                        ),
                        required = listOf("seed_classes", "rationale")
                    )
                )
            )
        )
    }

    private fun splitCamelCase(text: String): List<String> {
        return text.replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .replace(Regex("([A-Z])([A-Z][a-z])"), "$1 $2")
            .lowercase()
            .split(Regex("[^a-z0-9]"))
            .filter { it.isNotBlank() && it !in STRUCTURAL_STOPWORDS }
    }

    fun searchGraphNodes(query: String, domainHint: String? = null, limit: Int = 10): List<Map<String, Any>> {
        val rawTokens = query.lowercase().split(Regex("[^a-z0-9가-힣]")).filter { it.isNotBlank() }
        val englishTokens = rawTokens.filter { it.matches(Regex("[a-z0-9]+")) }
        val koreanTokens = rawTokens.filter { it.matches(Regex("[가-힣]+")) }

        val scored = graph.files.values.mapNotNull { node ->
            var score = 0.0
            val className = node.className
            val classSegments = splitCamelCase(className)
            val pathLower = node.path.lowercase()
            val localName = node.localName ?: ""
            val comments = node.koreanComments?.joinToString(" ") ?: ""
            val methods = node.methods ?: emptyList()
            val methodSegments = methods.flatMap { splitCamelCase(it.name) }
            val apiEndpoints = node.apiEndpoints ?: emptyList()
            val apiSegments = apiEndpoints.flatMap { ep ->
                splitCamelCase(ep.path) + splitCamelCase(ep.handlerMethod)
            }

            // Tier 1: CamelCase / 약어 / API 엔드포인트 일치
            for (eng in englishTokens) {
                if (eng.length < 2) continue
                if (classSegments.any { it == eng }) {
                    score += 100.0
                } else if (classSegments.any { it.contains(eng) }) {
                    score += 60.0
                } else if (className.lowercase().contains(eng)) {
                    score += 40.0
                }

                if (methodSegments.any { it == eng }) {
                    score += 50.0
                } else if (methodSegments.any { it.contains(eng) }) {
                    score += 30.0
                }

                if (apiSegments.any { it == eng }) {
                    score += 40.0
                } else if (apiSegments.any { it.contains(eng) }) {
                    score += 20.0
                }
            }

            // Tier 2: 한글 주석 / 로컬명 일치
            for (kor in koreanTokens) {
                if (kor.length < 2) continue
                if (localName.contains(kor)) {
                    score += 50.0
                }
                if (comments.contains(kor)) {
                    score += 25.0
                }
            }

            // Tier 3: 도메인/패키지 힌트 스코프
            if (!domainHint.isNullOrBlank()) {
                val hintClean = domainHint.lowercase().trim()
                if (pathLower.contains(hintClean)) {
                    score += 30.0
                }
            }

            if (score > 0) {
                Triple(node, score, classSegments)
            } else null
        }.sortedByDescending { it.second }

        return scored.take(limit).map { (node, score, _) ->
            val keyMethods = node.methods?.map { it.name }?.take(5) ?: emptyList()
            val apiSummary = node.apiEndpoints?.take(3)?.map { "${it.httpMethod} ${it.path}" } ?: emptyList()
            val resultMap = mutableMapOf<String, Any>(
                "className" to node.className,
                "path" to node.path,
                "fileType" to node.fileType.name,
                "localName" to (node.localName ?: "주석 없음"),
                "keyMethods" to keyMethods,
                "matchScore" to score
            )
            if (apiSummary.isNotEmpty()) {
                resultMap["apiEndpoints"] = apiSummary
            }
            resultMap
        }
    }

    fun inspectNodeDetail(className: String): Map<String, Any> {
        val node = graph.files.values.find { it.className == className || it.path.endsWith("/$className.java") }
            ?: return mapOf("error" to "노드를 찾을 수 없습니다: $className")

        val downstream = node.dependsOn.mapNotNull { depPath ->
            graph.files[depPath]?.let { "${it.className} (${it.fileType.name})" } ?: depPath.substringAfterLast('/')
        }

        val upstream = graph.files.values.filter { it.dependsOn.contains(node.path) }.map {
            "${it.className} (${it.fileType.name})"
        }

        val methodDetails = node.methods?.map {
            mapOf(
                "name" to it.name,
                "returnType" to it.returnType,
                "parameters" to it.parameters
            )
        } ?: emptyList()

        val apiDetails = node.apiEndpoints?.map {
            mapOf(
                "httpMethod" to it.httpMethod,
                "path" to it.path,
                "handlerMethod" to it.handlerMethod
            )
        } ?: emptyList()

        val result = mutableMapOf<String, Any>(
            "className" to node.className,
            "path" to node.path,
            "fileType" to node.fileType.name,
            "localName" to (node.localName ?: ""),
            "koreanComments" to (node.koreanComments ?: emptyList()),
            "methods" to methodDetails,
            "dependsOn_Downstream" to downstream,
            "dependedBy_Upstream" to upstream
        )
        if (apiDetails.isNotEmpty()) {
            result["apiEndpoints"] = apiDetails
        }
        return result
    }

    fun expandConnectedNodes(className: String, direction: String = "BOTH", maxHops: Int = 1): Map<String, Any> {
        val rootNode = graph.files.values.find { it.className == className }
            ?: return mapOf("error" to "기준 노드를 찾을 수 없습니다: $className")

        val result = mutableSetOf<FileNode>()
        var currentFrontier = setOf(rootNode)
        val visitedPaths = mutableSetOf(rootNode.path)
        val actualHops = maxHops.coerceIn(1, 2)

        for (hop in 1..actualHops) {
            val nextFrontier = mutableSetOf<FileNode>()
            for (curr in currentFrontier) {
                if (direction == "DOWNSTREAM" || direction == "BOTH") {
                    for (depPath in curr.dependsOn) {
                        val target = graph.files[depPath]
                        if (target != null && !visitedPaths.contains(target.path)) {
                            visitedPaths.add(target.path)
                            nextFrontier.add(target)
                            result.add(target)
                        }
                    }
                }
                if (direction == "UPSTREAM" || direction == "BOTH") {
                    for (candidate in graph.files.values) {
                        if (candidate.dependsOn.contains(curr.path) && !visitedPaths.contains(candidate.path)) {
                            visitedPaths.add(candidate.path)
                            nextFrontier.add(candidate)
                            result.add(candidate)
                        }
                    }
                }
            }
            currentFrontier = nextFrontier
        }

        return mapOf(
            "rootClass" to rootNode.className,
            "connectedCount" to result.size,
            "connectedNodes" to result.map { node ->
                mapOf(
                    "className" to node.className,
                    "fileType" to node.fileType.name,
                    "localName" to (node.localName ?: ""),
                    "path" to node.path
                )
            }
        )
    }
}
