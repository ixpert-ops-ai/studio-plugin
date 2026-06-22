package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.client.LLMClient

class NewFileDetector(private val llmClient: LLMClient? = null) {
    private val gson = Gson()

    fun detectNewFiles(
        srText: String,
        seedResult: SeedSelectionResult,
        scoredFiles: List<ScoredFile>,
        enhancedRequirements: List<String> = emptyList()
    ): List<NewFileProposal> {
        val proposals = mutableListOf<NewFileProposal>()

        val isCreate = seedResult.changeIntent == ChangeIntent.CREATE ||
                srText.contains("추가") || srText.contains("생성") || srText.contains("신규")

        if (isCreate) {
            if (llmClient != null) {
                val llmProposals = tryLlmDetection(srText, seedResult, scoredFiles, enhancedRequirements)
                if (llmProposals != null) {
                    return llmProposals
                }
            }

            // Fallback: 패턴 1: 배치/스케줄러
            if (srText.contains("배치") || srText.contains("스케줄러") || srText.contains("스케줄")) {
                proposals.add(
                    NewFileProposal(
                        suggestedPath = "src/main/java/com/membermarket/batch/NewBatchScheduler.java",
                        suggestedFileType = "SERVICE",
                        reason = "배치/스케줄러 관련 신규 생성 요구사항 감지",
                        referencePattern = "기존 배치/스케줄러 설정 구조 (예: @Component + @Scheduled) 참조"
                    )
                )
            }
            
            // Fallback: 패턴 2: 화면 추가
            if (seedResult.frontendRelevant && (srText.contains("화면") || srText.contains("뷰") || srText.contains("페이지"))) {
                proposals.add(
                    NewFileProposal(
                        suggestedPath = "src/views/NewFeatureView.vue",
                        suggestedFileType = "VIEW",
                        reason = "신규 화면 추가 요구사항 감지",
                        referencePattern = "기존 Vue 컴포넌트 템플릿 참조"
                    )
                )
            }
            
            // Fallback: 패턴 3: API/Controller 추가
            if (srText.contains("API") || srText.contains("컨트롤러") || srText.contains("엔드포인트")) {
                proposals.add(
                    NewFileProposal(
                        suggestedPath = "src/main/java/com/membermarket/api/NewFeatureController.java",
                        suggestedFileType = "REST_CONTROLLER",
                        reason = "신규 API 컨트롤러 생성 요구사항 감지",
                        referencePattern = "기존 @RestController 패턴 참조"
                    )
                )
            }
            
            // Fallback: 기본 제안
            if (proposals.isEmpty()) {
                proposals.add(
                    NewFileProposal(
                        suggestedPath = "src/main/java/com/membermarket/domain/NewFeatureService.java",
                        suggestedFileType = "SERVICE",
                        reason = "신규 비즈니스 로직 클래스 생성",
                        referencePattern = "기존 Service 계층 구현 패턴 참조"
                    )
                )
            }
        }
        
        return proposals
    }

    private fun tryLlmDetection(
        srText: String,
        seedResult: SeedSelectionResult,
        scoredFiles: List<ScoredFile>,
        enhancedRequirements: List<String>
    ): List<NewFileProposal>? {
        val existingFiles = scoredFiles.take(10).joinToString("\n") { it.path }
        
        val systemPrompt = """
            당신은 프로젝트 아키텍트입니다. 요구사항(SR)을 분석하여 새롭게 만들어야 할 파일(신규 파일)의 이름, 경로, 유형을 구체적으로 제안하세요.
            기존 파일 목록을 참고하여 패키지 구조와 네이밍 규칙(예: 할인율 관련이면 DiscountRateService.java 등)을 유추하세요.
            반드시 제공된 `propose_new_files` 도구를 호출하여 배열 형태로 응답하세요.

            [제약 조건]
            1. 아래 "참고용 기존 파일 목록"에 이미 존재하는 경로는 절대 제안하지 마세요. 기존 파일의 수정은 별도 단계에서 처리됩니다.
            2. 분석 결과 신규 파일 생성이 불필요하다고 판단되면, proposals를 빈 배열([])로 반환하세요.
        """.trimIndent()

        val userPromptBuilder = StringBuilder()
        userPromptBuilder.append("## 요구사항 (SR)\n")
        userPromptBuilder.append(srText).append("\n\n")

        if (enhancedRequirements.isNotEmpty()) {
            userPromptBuilder.append("## 확정된 구현 방향\n")
            enhancedRequirements.forEach { userPromptBuilder.append("- $it\n") }
            userPromptBuilder.append("\n")
        }

        userPromptBuilder.append("## 참고용 기존 파일 목록 (패키지 구조 파악용)\n")
        userPromptBuilder.append(existingFiles)

        val userPrompt = userPromptBuilder.toString()

        val tool = net.ib.ixpert.ops.wuwagent.model.ToolDefinition(
            type = "function",
            function = net.ib.ixpert.ops.wuwagent.model.FunctionDefinition(
                name = "propose_new_files",
                description = "신규 생성해야 할 파일 목록을 제안합니다.",
                parameters = net.ib.ixpert.ops.wuwagent.model.FunctionParameters(
                    type = "object",
                    properties = mapOf(
                        "proposals" to net.ib.ixpert.ops.wuwagent.model.PropertyDefinition(
                            type = "array",
                            description = "신규 파일 제안 목록",
                            items = net.ib.ixpert.ops.wuwagent.model.PropertyDefinition(
                                type = "object",
                                properties = mapOf(
                                    "suggestedPath" to net.ib.ixpert.ops.wuwagent.model.PropertyDefinition(type = "string", description = "파일 전체 경로 (예: src/main/java/.../DiscountRateService.java)"),
                                    "suggestedFileType" to net.ib.ixpert.ops.wuwagent.model.PropertyDefinition(type = "string", description = "파일 유형 (예: SERVICE, CONTROLLER, ENTITY, DTO, EXCEPTION, VIEW, REPOSITORY)"),
                                    "reason" to net.ib.ixpert.ops.wuwagent.model.PropertyDefinition(type = "string", description = "생성 사유"),
                                    "referencePattern" to net.ib.ixpert.ops.wuwagent.model.PropertyDefinition(type = "string", description = "참조할 디자인 패턴이나 기존 구조")
                                ),
                                required = listOf("suggestedPath", "suggestedFileType", "reason", "referencePattern")
                            )
                        )
                    ),
                    required = listOf("proposals")
                )
            )
        )

        try {
            val response = llmClient?.chatWithTools(
                systemPrompt = systemPrompt,
                messages = listOf(net.ib.ixpert.ops.wuwagent.model.ChatMessage(role = "user", content = userPrompt)),
                maxTokens = 1500,
                tools = listOf(tool),
                toolChoice = mapOf("type" to "function", "function" to mapOf("name" to "propose_new_files"))
            )
            
            val toolCall = response?.toolCalls?.firstOrNull { it.function.name == "propose_new_files" }
            if (toolCall != null) {
                val type = object : com.google.gson.reflect.TypeToken<Map<String, List<NewFileProposal>>>() {}.type
                val map: Map<String, List<NewFileProposal>> = gson.fromJson(toolCall.function.arguments, type)
                return filterExistingFiles(map["proposals"] ?: emptyList(), scoredFiles)
            } else if (!response?.content.isNullOrBlank()) {
                // Some models return the tool arguments directly in the text content
                var cleanJson = response!!.content!!
                cleanJson = cleanJson.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "").trim()
                val jsonMatch = Regex("\\{.*\\}", RegexOption.DOT_MATCHES_ALL).find(cleanJson)
                val cleanJsonForParse = jsonMatch?.value ?: cleanJson.replace("```json", "").replace("```", "").trim()
                val type = object : com.google.gson.reflect.TypeToken<Map<String, List<NewFileProposal>>>() {}.type
                val map: Map<String, List<NewFileProposal>> = gson.fromJson(cleanJsonForParse, type)
                return filterExistingFiles(map["proposals"] ?: emptyList(), scoredFiles)
            }
        } catch (e: Exception) {
            println("LLM NewFileDetector failed: ${e.message}")
        }
        return null
    }

    private fun filterExistingFiles(proposals: List<NewFileProposal>, scoredFiles: List<ScoredFile>): List<NewFileProposal> {
        val existingPaths = scoredFiles.map { it.path }.toSet()
        return proposals.filter { it.suggestedPath !in existingPaths }
    }
}
