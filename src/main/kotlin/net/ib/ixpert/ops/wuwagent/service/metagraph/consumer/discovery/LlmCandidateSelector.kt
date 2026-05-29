package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.intellij.openapi.diagnostic.Logger
import net.ib.ixpert.ops.wuwagent.client.LLMClient
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
        maxCandidates: Int = 10
    ): SelectionResult {
        val topCandidates = candidates
            .sortedByDescending { it.score }
            .take(maxCandidates)
        
        return try {
            val systemPrompt = buildSystemPrompt()
            val userPrompt = buildUserPrompt(userQuery, topCandidates)
            
            // 기존 LLMClient 활용. maxTokens는 2048 할당.
            val response = llmClient.chat(
                systemPrompt = systemPrompt,
                userCode = userPrompt,
                maxTokens = 2048
            )
            
            val content = response?.message?.content
            if (content.isNullOrBlank()) {
                logger.warn("LLM response is empty or null")
                fallbackToStage1(topCandidates)
            } else {
                val parsed = parseResponse(content, topCandidates)
                if (parsed != null && parsed.modify.isNotEmpty()) {
                    parsed
                } else {
                    logger.warn("Parsed result is empty or invalid")
                    fallbackToStage1(topCandidates)
                }
            }
        } catch (e: Exception) {
            logger.warn("LLM selection failed, falling back to Stage 1 results", e)
            fallbackToStage1(topCandidates)
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
                "- Target Anyframe Enterprise components: DEM/DQM, BIZ, SVC, Controller.\n" +
                "- Follow LAYERED_SVO_BVO_DVO VoStrategy.\n" +
                "- This project strictly uses Interface + Impl pairs. You MUST select BOTH the Interface and the Impl."
            else -> 
                "- Standard Layered Architecture (Repository -> DTO -> Service -> Controller)."
        }

        return """
        You are a senior Spring Boot architect. Your ONLY job is to select which files 
        to modify and suggest new files to create, based on a user requirement and a 
        pre-filtered candidate list.

        ## STRICT RULES
        1. You may ONLY select files from the [Candidate Files] list for modification.
        2. Use the EXACT file path as provided — do not alter, shorten, or guess paths.
        3. If a file not in the list is needed, mark it as "create" with full path 
           following the project's existing package conventions.
        4. Framework Rules:
           $frameworkSpecificRules
        5. Each file must have a one-line actionable description of WHAT to change.
        6. Do NOT include files unrelated to the requirement.
        7. For "modify", ONLY include files directly related to the user's requirement. Do not include files just because they depend on the same Repository.
        8. Do NOT put the same file in BOTH "modify" and "create". Adding code to an existing file is a "modify" action.
        9. Use the EXACT path provided in the candidate list, including the module path (e.g. member-market-api/src/main/java/...).

        ## RESPONSE FORMAT (JSON ONLY)
        {
          "summary": "requirement summary",
          "modify": [{"order": 1, "path": "exact/path.java", "reason": "what to do"}],
          "create": [{"order": 2, "path": "new/path.java", "reason": "what this does"}],
          "warnings": ["risk notes"],
          "reasoning": "selection logic explanation"
        }
    """.trimIndent()
    }
    
    private fun buildUserPrompt(
        userQuery: String, 
        candidates: List<ScoredCandidate>
    ): String = buildString {
        appendLine("## User Requirement")
        appendLine(userQuery)
        appendLine()
        appendLine("## Candidate Files (scored by relevance)")
        appendLine()
        
        candidates.forEachIndexed { index, candidate ->
            val node = projectGraph.files[candidate.filePath] ?: return@forEachIndexed
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
                if (path in validPaths) {
                    true
                } else {
                    logger.warn("LLM returned path not in candidates: ${action.path}")
                    false
                }
            }?.map { FileAction(it.order ?: 0, it.path!!, it.reason ?: "") } ?: emptyList()
            
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
