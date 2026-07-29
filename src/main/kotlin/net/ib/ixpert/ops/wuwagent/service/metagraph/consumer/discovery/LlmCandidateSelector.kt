package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.intellij.openapi.diagnostic.Logger
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.ToolService
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph

/**
 * Stage 2: LLM을 활용한 최종 후보 선정기.
 * Stage 1(휴리스틱/규칙 기반)에서 도출된 ScoredCandidate 리스트 중 최상위 N개를
 * LLM(qwen3-coder 등)에게 전달하여 실제 수정/생성 대상 파일을 JSON 포맷으로 확정받습니다.
 */
class LlmCandidateSelector(
    private val llmClient: LLMClient,
    private val projectGraph: ProjectGraph
) {
    private val logger = Logger.getInstance(LlmCandidateSelector::class.java)
    private val gson = Gson()

    data class SelectionResult(
        val summary: String,
        val modify: List<FileAction>,
        val create: List<FileAction>,
        val warnings: List<String>,
        val reasoning: String
    )
    
    data class FileAction(
        val order: Int,
        val path: String,
        val reason: String
    )

    private data class SelectionResultRaw(
        @SerializedName("summary") val summary: String?,
        @SerializedName("modify") val modify: List<FileActionRaw>?,
        @SerializedName("create") val create: List<FileActionRaw>?,
        @SerializedName("warnings") val warnings: List<String>?,
        @SerializedName("reasoning") val reasoning: String?
    )
    
    private data class FileActionRaw(
        @SerializedName("order") val order: Int?,
        @SerializedName("path") val path: String?,
        @SerializedName("reason") val reason: String?
    )

    /**
     * Stage 1 후보 목록을 기반으로 LLM을 호출하여 최종 수정/생성 대상 파일을 결정합니다.
     */
    fun select(
        userQuery: String,
        candidates: List<ScoredCandidate>,
        maxCandidates: Int = 10,
        onChunk: ((String) -> Unit)? = null
    ): SelectionResult {
        val topCandidates = candidates
            .sortedByDescending { it.score }
            .take(maxCandidates)
            
        // --- STAGE 1.5: Deterministic Call Graph Pre-expansion ---
        val (expandedCandidates, chainMarkdown) = DeterministicChainExpander.buildCallChains(topCandidates, projectGraph)
            
        val workTools = ToolService.buildSchema()
        val systemPrompt = buildSystemPrompt()
        // expandedCandidates는 원래 maxCandidates + maxExtraCandidates 만큼 늘어났을 수 있습니다.
        val userPrompt = buildUserPrompt(userQuery, expandedCandidates, chainMarkdown)
        
        val messages = mutableListOf(
            net.ib.ixpert.ops.wuwagent.model.ChatMessage(role = "user", content = userPrompt)
        )
        
        var turn = 0
        var hasRetriedJson = false
        
        val maxTurns = 7
        while (turn < maxTurns) {
            try {
                // 턴에 관계없이 auto (마지막 턴은 null). 요건이 단순하면 1턴만에 응답 가능
                val currentToolChoice = "auto"
                
                // 마지막 턴에는 툴 호출을 막고 무조건 JSON 응답을 강제함
                val isLastTurn = turn == maxTurns - 1
                if (isLastTurn) {
                    messages.add(
                        net.ib.ixpert.ops.wuwagent.model.ChatMessage(
                            role = "user",
                            content = "탐색이 완료되었습니다. 도구를 더 사용하지 말고, 지금까지 수집한 정보를 바탕으로 최종 JSON 응답을 생성하세요."
                        )
                    )
                }
                
                val response = llmClient.chatWithTools(
                    systemPrompt = systemPrompt,
                    messages = messages,
                    maxTokens = 8192,
                    tools = if (isLastTurn) null else workTools,
                    toolChoice = if (isLastTurn) null else currentToolChoice
                )
                
                if (response == null) {
                    logger.warn("LLM response is null")
                    messages.clear()
                    return fallbackToStage1(topCandidates)
                }
                
                // 씽킹 로그가 들어오면 내부 트레이스로 로깅
                response.reasoningContent?.let { logger.info("[LLM Thinking]: $it") }
                
                val assistantMsg = response.choices?.firstOrNull()?.message
                if (assistantMsg == null) {
                    logger.warn("LLM returned empty message")
                    messages.clear()
                    return fallbackToStage1(topCandidates)
                }
                
                messages.add(assistantMsg)
                
                if (!assistantMsg.toolCalls.isNullOrEmpty()) {
                    for (toolCall in assistantMsg.toolCalls) {
                        onChunk?.invoke("> 🛠️ **Tool Calling**: `${toolCall.function.name}` 탐색 중...\n")
                        logger.info("LLM requested Tool Calling: ${toolCall.function.name} (args: ${toolCall.function.arguments})")
                        
                        val startMs = System.currentTimeMillis()
                        val observation = ToolService.execute(toolCall.function.name, toolCall.function.arguments, projectGraph)
                        val elapsed = System.currentTimeMillis() - startMs
                        
                        logger.info("[Tool Execution Turn ${turn + 1}] tool_name: ${toolCall.function.name}, elapsed: ${elapsed}ms")
                        
                        if (observation.startsWith("Error")) {
                            onChunk?.invoke("> ⚠️ 툴 실행 실패: ${toolCall.function.name}\n")
                        }
                        
                        messages.add(
                            net.ib.ixpert.ops.wuwagent.model.ChatMessage(
                                role = "tool",
                                content = observation,
                                toolCallId = toolCall.id,
                                name = toolCall.function.name
                            )
                        )
                    }
                    turn++
                } else {
                    // 최종 결과 JSON 도출 성공 시 탈출
                    val content = assistantMsg.content
                    if (content.isNullOrBlank()) {
                        logger.warn("Parsed result is empty")
                        messages.clear()
                        return fallbackToStage1(topCandidates)
                    }
                    
                    val parsed = parseResponse(content, topCandidates)
                    if (parsed != null && (parsed.modify.isNotEmpty() || parsed.create.isNotEmpty())) {
                        return parsed
                    } else {
                        if (!hasRetriedJson) {
                            logger.warn("Parsed result is empty or invalid, prompting LLM to retry returning JSON")
                            onChunk?.invoke("> ⚠️ JSON 형식이 올바르지 않아 재시도합니다...\n")
                            messages.add(
                                net.ib.ixpert.ops.wuwagent.model.ChatMessage(
                                    role = "user",
                                    content = "JSON 포맷 파싱에 실패했습니다. 지정된 JSON 형식으로 최종 결과를 다시 응답해주세요."
                                )
                            )
                            hasRetriedJson = true
                            turn++
                            continue
                        } else {
                            logger.warn("Parsed result is empty or invalid after retry")
                            messages.clear()
                            return fallbackToStage1(topCandidates)
                        }
                    }
                }
            } catch (e: Exception) {
                // 1. HTTP 400 Bad Request 또는 파싱 에러(JsonSyntaxException 등) -> 폴백
                // 2. HTTP 503, ConnectException -> 상위로 에러 전파
                val isBadRequest = e.message?.contains("400") == true || e.message?.contains("Bad Request") == true
                val isParseError = e is com.google.gson.JsonSyntaxException || e is com.fasterxml.jackson.core.JsonParseException
                
                if (isBadRequest || isParseError) {
                    logger.warn("Tool calling not supported or parsing failed. Falling back to one-shot.", e)
                    messages.clear()
                    return fallbackToLegacyPrompting(topCandidates, systemPrompt, userPrompt)
                }
                
                logger.error("LLM selection failed with critical error", e)
                throw e
            }
        }
        
        // 5턴 초과 시 강제 폴백 (Context Rollback)
        logger.warn("Tool calling loop exceeded max turns ($maxTurns). Falling back to Stage 1.")
        messages.clear()
        return fallbackToStage1(topCandidates)
    }

    private fun fallbackToLegacyPrompting(candidates: List<ScoredCandidate>, systemPrompt: String, userPrompt: String): SelectionResult {
        return try {
            val response = llmClient.chat(
                systemPrompt = systemPrompt,
                userCode = userPrompt,
                maxTokens = 8192
            )
            
            val content = response?.message?.content
            if (content.isNullOrBlank()) {
                fallbackToStage1(candidates)
            } else {
                val parsed = parseResponse(content, candidates)
                if (parsed != null && (parsed.modify.isNotEmpty() || parsed.create.isNotEmpty())) {
                    parsed
                } else {
                    fallbackToStage1(candidates)
                }
            }
        } catch (e: Exception) {
            logger.warn("Legacy fallback failed", e)
            fallbackToStage1(candidates)
        }
    }

    /**
     * LLM 호출/파싱 실패 시 사용할 Fallback.
     * Stage 1의 상위 5개 후보를 그대로 반환하여 파이프라인 중단을 방지합니다.
     */
    private fun fallbackToStage1(candidates: List<ScoredCandidate>): SelectionResult {
        return SelectionResult(
            summary = "LLM 분석 실패 — Stage 1 스코어 기반 결과",
            modify = candidates.take(5).mapIndexed { i, c ->
                FileAction(order = i + 1, path = c.filePath, reason = "Score: ${c.score}")
            },
            create = emptyList(),
            warnings = listOf("⚠️ LLM 응답 파싱 실패로 휴리스틱 결과를 사용합니다"),
            reasoning = "Fallback: Stage 1 top-5 candidates by score"
        )
    }

    private fun buildSystemPrompt(): String {
        val fwType = projectGraph.frameworkDetection?.userOverride ?: projectGraph.frameworkType
        val frameworkSpecificRules = when (fwType) {
            net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType.SPRING_BOOT_JPA -> 
                "- Use JPA Entities and JpaRepositories. Prioritize @Entity and @Repository files.\n" +
                "- DTOs must be created/modified BEFORE Service and Controller."
            net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType.SPRING_BOOT_MYBATIS -> 
                "- DAO layer uses @Mapper interfaces (no DaoImpl classes).\n" +
                "- Adding a new query requires modifying the @Mapper interface and its XML mapper file.\n" +
                "- Do NOT suggest JPA or JpaRepository."
            net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType.SPRING_MVC_MYBATIS -> 
                "- This project uses Interface + Impl pairs (e.g., SurveyService + SurveyServiceImpl).\n" +
                "- Adding a new feature REQUIRES modifying BOTH the Interface and its Impl.\n" +
                "- DAO layer uses SqlSessionDaoSupport-based DaoImpl classes.\n" +
                "- If a new query is needed, modify BOTH DaoInterface and DaoImpl.\n" +
                "- Do NOT suggest JPA, @Entity, or JpaRepository — this project uses MyBatis."
            net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType.ANYFRAME_AP -> 
                "- Target Anyframe Enterprise components: DEM/DQM, BIZ, SVC.\n" +
                "- Note: There is no Controller layer in Anyframe AP; SVC is the entry point.\n" +
                "- Follow LAYERED_SVO_BVO_DVO VoStrategy.\n" +
                "- This project strictly uses Interface + Impl pairs. You MUST select BOTH the Interface and the Impl."
            else -> 
                "- Standard Layered Architecture (Repository -> DTO -> Service -> Controller)."
        }

        return """
        You are a senior Spring Boot architect. Your ONLY job is to select which files 
        to modify and suggest new files to create, based on a user requirement and a 
        pre-filtered candidate list.

        You MUST respond in Korean (한국어). All output including summary, reasoning, and descriptions must be in Korean.

        ## TOOL USAGE (CRITICAL)
        You have access to tools (`search_files`, `get_file_summary`, `get_dependencies`).
        - DO NOT guess file paths. If a file you need is NOT in the Candidate Files list, YOU MUST use the `search_files` or `get_dependencies` tool to find its exact path first.
        - You can and should call tools multiple times to explore the project structure before giving your final JSON answer.
        - Only output the final JSON once you have verified the paths and dependencies using tools.
        - You have a maximum of 6 tool-calling opportunities. On your 7th response, you MUST provide the final JSON answer without any tool calls.
        - If a search returns empty results, do NOT retry with similar keywords. This means the project does not have that component and you should plan to CREATE it.
        - EXCEPTION: If the user requirement mentions UI changes (e.g. 화면, UI, 캘린더, 입력 필드, 폼, 버튼) AND no JSP/JS file has been found in your searches so far, you MUST perform `search_files(keyword=".jsp", scope="path")` exactly once before producing the final JSON.
        - CRITICAL: When searching for MyBatis XML mapper files, you MUST use `search_files` with `scope="path"`.
        - If the Candidate Files list already contains all files you need, skip tool calls and respond with the final JSON directly.
        - When you need detailed information about a file:
          - For .java or .kt files: use `get_file_summary`
          - For .jsp, .js, .xml, .html, .css files: use `get_resource_summary`
          - If `get_file_summary` returns "File not found matching", retry with `get_resource_summary`
        - STRICT BAN on redundant `get_file_summary`: If a file is already in the Candidate Files list and shows 'Metadata', 'Injects', or 'Endpoints', DO NOT call `get_file_summary` for it. Wasting tool calls on known metadata is strictly forbidden. Instead, immediately use `get_dependencies` on those files to trace the Call Chain!

        ## FILE SELECTION PROCESS (Chain-of-Thought)
        You MUST follow this 4-step reasoning process before selecting files:
        1. Identify the Trigger: 식별된 요구사항의 성격을 파악하세요 (예: 사용자 화면 조작, 배치 작업, 외부 연동 등).
        2. Map Trigger to Entry Point: 트리거 유형에 맞는 진입점을 찾으세요.
           - 사용자가 UI 조작이면 -> Controller 엔드포인트부터 식별
           - 스케줄/배치 작업이면 -> @Scheduled, Job 클래스를 식별
           - 핵심: 요구사항의 트리거 유형과 Entry Point의 역할이 일치하는지 반드시 확인하세요.
        3. Trace the Call Chain: Entry Point(Controller 또는 Job) -> Service -> DAO/Mapper 로 이어지는 실제 의존성 호출 체인을 추적하세요.
        4. Validate Necessity: "이 파일이 없으면 요구사항 구현이 불가능한가?" 자문하여 증명된 파일만 선정하세요.

        ## TOOL USAGE STRATEGY (CRITICAL)
        - Priority 1: Controller 또는 Job에서 `get_dependencies`를 호출하여 주입된 Service를 확인하세요.
        - Priority 2: Service에서 `get_dependencies`를 호출하여 DAO/Mapper를 확인하세요.
        - Priority 3: `search_files`는 위 체인에서 누락된 파일을 보완할 때만 사용하세요.
        - 원칙: 의존성 체인을 확인하지 않은 채 파일명만으로 관련성을 짐작하여 선정하지 마세요.
        - 예외: `get_dependencies` 결과가 불완전하거나 비어있을 경우, `search_files`나 `get_file_summary`로 파일 내용을 직접 확인하여 호출 관계를 증명한 후에는 선정할 수 있습니다.

        ## STRICT RULES
        1. You may ONLY select files from the [Candidate Files] list OR files you have verified exist via tool calls.
           - CRITICAL: If the user requests UI/Frontend changes or DB query changes, you MUST select the relevant "Linked Resources" (JSP, JS, XML) shown under the Candidate Files and add them to the "modify" list. Do NOT just mention them in warnings.
           - CRITICAL: DO NOT put hallucinated or guessed file paths in the "modify" array. If a file does not exist in the candidate list and you cannot find it via `search_files`, you MUST put it in the "create" array.
        2. Use the EXACT file path as provided or discovered — do not alter, shorten, or guess paths.
        3. If a new file is needed, mark it as "create" (NOT "modify") with a full path following the project's existing package conventions.
        4. Framework Rules:
           $frameworkSpecificRules
        5. Each file must have a one-line actionable description of WHAT to change.
        6. Do NOT include files unrelated to the requirement.
        7. For "modify", ONLY include files directly related to the user's requirement. Do not include files just because they depend on the same Repository.
        8. Do NOT put the same file in BOTH "modify" and "create". Adding code to an existing file is a "modify" action.
        9. Use the EXACT path, including the module path (e.g. member-market-api/src/main/java/...).

        ## RESPONSE FORMAT (JSON ONLY)
        {
          "reasoning": "반드시 이 필드에 4단계 추론 절차(1. Trigger, 2. Entry Point, 3. Call Chain, 4. Necessity)의 결과를 먼저 상세히 작성하세요. 그 후 modify와 create를 채우세요.",
          "summary": "requirement summary",
          "modify": [{"order": 1, "path": "exact/path.java", "reason": "what to do"}],
          "create": [{"order": 2, "path": "new/path.java", "reason": "what this does"}],
          "warnings": ["risk notes"]
        }
    """.trimIndent()
    }
    
    private fun buildUserPrompt(
        userQuery: String, 
        candidates: List<ScoredCandidate>,
        chainMarkdown: String = ""
    ): String = buildString {
        appendLine("## User Requirement")
        appendLine(userQuery)
        appendLine()
        
        if (chainMarkdown.isNotBlank()) {
            appendLine(chainMarkdown)
        }
        
        appendLine("## Candidate Files (scored by relevance)")
        appendLine()
        
        candidates.forEachIndexed { index, candidate ->
            val node = projectGraph.files[candidate.filePath]
            if (node != null) {
                val riskFlag = if (node.riskAssessment.riskScore >= 5) " ⚠️ ${node.riskAssessment.changeRisk}" else ""
                
                appendLine("### #${index + 1} [Score: ${candidate.score}]$riskFlag")
                appendLine("- Path: ${node.path}")
                appendLine("- Type: ${node.fileType} | Layer: ${node.layer}")
                
                if (node.methodNames.isNotEmpty()) {
                    appendLine("- Methods: ${node.methodNames.joinToString(", ")}")
                }
                if (node.apiEndpoints.isNotEmpty()) {
                    val endpoints = node.apiEndpoints.joinToString("; ") { ep ->
                        "${ep.httpMethod} ${ep.path} -> ${ep.relatedServiceMethod}"
                    }
                    appendLine("- Endpoints: $endpoints")
                }
                if (node.injections.isNotEmpty()) {
                    val injects = node.injections.joinToString(", ") { it.targetType }
                    appendLine("- Injects: $injects")
                }
                
                val linkedResources = projectGraph.resourceNodes.filter { it.linkedTo.contains(node.path) }
                if (linkedResources.isNotEmpty()) {
                    val resStr = linkedResources.joinToString(", ") { "${it.path} (${it.type})" }
                    appendLine("- Linked Resources: $resStr")
                }
                if (node.koreanComments.isNotEmpty()) {
                    appendLine("- Korean Comments: ${node.koreanComments.joinToString(", ")}")
                }
                if (node.dependedBy.isNotEmpty()) {
                    val dependedBy = node.dependedBy.map { path ->
                        projectGraph.files[path]?.className ?: path.substringAfterLast("/")
                    }
                    appendLine("- DependedBy: ${dependedBy.joinToString(", ")}")
                }
                appendLine()
            } else {
                val rNode = projectGraph.resourceNodes.find { it.path == candidate.filePath }
                if (rNode != null) {
                    appendLine("### #${index + 1} [Score: ${candidate.score}]")
                    appendLine("- Path: ${rNode.path}")
                    appendLine("- Type: ${rNode.type} | Layer: ${rNode.layer}")
                    if (rNode.linkedTo.isNotEmpty()) {
                        appendLine("- Linked to: ${rNode.linkedTo.joinToString(", ")}")
                    }
                    if (rNode.metadata.isNotEmpty()) {
                        val metaStr = rNode.metadata.entries.joinToString("; ") { "${it.key}: ${it.value}" }
                        appendLine("- Metadata: $metaStr")
                    }
                    appendLine()
                }
            }
        }
        
        appendLine("## Architecture Context")
        appendLine("- Project root: ${projectGraph.projectRoot}")
        appendLine("- Framework: ${projectGraph.frameworkType}")
        appendLine("- Base package: ${inferBasePackage()}")
        appendLine()
        appendLine("Analyze the requirement and respond with JSON only.")
    }
    
    private fun parseResponse(
        rawResponse: String, 
        candidates: List<ScoredCandidate>
    ): SelectionResult? {
        return try {
            val json = extractJson(rawResponse)
            val parsed = gson.fromJson(json, SelectionResultRaw::class.java) ?: return null
            
            val validPaths = candidates.map { it.filePath }.toSet()
            
            val validModify = parsed.modify?.filter { action ->
                val path = action.path ?: return@filter false
                // 후보 목록에 없더라도 프로젝트 전체 그래프에 존재하는 실제 파일이라면 허용 (LLM의 스마트 추론 인정)
                if (path in validPaths) {
                    true
                } else if (projectGraph.files.containsKey(path) || projectGraph.resourceNodes.any { it.path == path }) {
                    // 2차 필터: 전체 프로젝트에 존재하면 허용하되 사용자 경고용 태그 추가
                    true
                } else {
                    logger.warn("LLM returned path not in candidates nor in project graph: ${action.path}")
                    false
                }
            }?.map { 
                val isResource = projectGraph.resourceNodes.any { r -> r.path == it.path }
                val existsInProject = projectGraph.files.containsKey(it.path) || isResource
                val reason = if (it.path!! !in validPaths && existsInProject) {
                    "[추가 탐색된 파일] " + (it.reason ?: "")
                } else {
                    it.reason ?: ""
                }
                FileAction(it.order ?: 0, it.path, reason)
            } ?: emptyList()
            
            val createList = parsed.create?.filter { !it.path.isNullOrBlank() }
                ?.map { FileAction(it.order ?: 0, it.path!!, it.reason ?: "") } ?: emptyList()

            SelectionResult(
                summary = parsed.summary ?: "",
                modify = validModify,
                create = createList,
                warnings = parsed.warnings ?: emptyList(),
                reasoning = parsed.reasoning ?: ""
            )
        } catch (e: Exception) {
            logger.error("Failed to parse LLM response JSON", e)
            null
        }
    }
    
    private fun extractJson(response: String): String {
        // 1. <think>...</think> 블록 제거 (qwen3-coder 대응)
        val withoutThinking = response.replace(
            Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), ""
        ).trim()
        
        // 2. ```json ... ``` 코드블록 추출
        val codeBlockPattern = Regex("```json\\s*\\n?(.*?)\\n?```", RegexOption.DOT_MATCHES_ALL)
        val match = codeBlockPattern.find(withoutThinking)
        if (match != null) return match.groupValues[1].trim()
        
        // 3. 순수 JSON 추출
        val firstBrace = withoutThinking.indexOf('{')
        val lastBrace = withoutThinking.lastIndexOf('}')
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return withoutThinking.substring(firstBrace, lastBrace + 1)
        }
        
        return withoutThinking
    }
    
    private fun inferBasePackage(): String {
        return projectGraph.files.values
            .firstOrNull()?.packageName
            ?.split(".")
            ?.take(3)
            ?.joinToString(".") ?: "com.unknown"
    }
}
