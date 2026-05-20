package net.ib.ixpert.ops.wuwagent.agent

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.ProjectSummaryFormatter
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.RepoMapFormatter
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.RelevanceFilter

data class TargetFileSpec(
    val order: Int,
    val path: String,
    val type: String,
    val description: String
)

data class RequirementAnalysisResult(
    val summary: String,
    val targetFiles: List<TargetFileSpec>,
    val warnings: String,
    val rawResponse: String
)

/**
 * [Phase 2a] 자연어 요구사항을 분석하여 수정/신규 대상 파일 목록을 추출하는 파이프라인.
 */
class RequirementAnalysisPipeline(private val project: Project?, private val client: LLMClient) {
    constructor(client: LLMClient) : this(null, client)

    companion object {
        var lastResult: RequirementAnalysisResult? = null

        /**
         * 이 파일 수 이하이면 필터링 없이 전체 전달 (소규모 프로젝트는 필터링 불필요).
         */
        private const val FILE_COUNT_THRESHOLD = 10
    }

    private val logger = Logger.getInstance(RequirementAnalysisPipeline::class.java)

    fun analyze(requirement: String, projectGraph: ProjectGraph, onChunk: ((String) -> Unit)? = null): RequirementAnalysisResult {
        // 멀티모듈 레벨 1이거나 대형 프로젝트(10+ 파일)인 경우 RelevanceFilter로 관련 파일만 추출
        val (workingGraph, keywords) = if (projectGraph.graphType == net.ib.ixpert.ops.wuwagent.service.metagraph.model.GraphType.MULTI_LEVEL_1 || projectGraph.files.size > FILE_COUNT_THRESHOLD) {
            val filterMsg = if (projectGraph.graphType == net.ib.ixpert.ops.wuwagent.service.metagraph.model.GraphType.MULTI_LEVEL_1) {
                "> 📊 멀티 모듈 요약 그래프가 감지되었습니다. 요구사항과 관련 있는 모듈 및 파일만 필터링합니다.\n\n"
            } else {
                "> 📊 프로젝트 규모: **${projectGraph.files.size}개 파일** — 관련 파일만 필터링합니다.\n\n"
            }
            onChunk?.invoke(filterMsg)
            
            val filterResult = RelevanceFilter.filter(requirement, projectGraph, client, project) { progress ->
                onChunk?.invoke(progress)
            }
            // Ollama 서버의 연속 호출(키워드 추출 -> 전체 분석) 시 컨텍스트 정리 및 커넥션 안정화를 위한 대기
            // OpenAI, AIPro 등 클라우드 API는 비동기 병렬 처리가 원활하므로 딜레이 제외
            val settings = net.ib.ixpert.ops.wuwagent.setting.SettingsState.getInstance().state
            if (settings.apiType == net.ib.ixpert.ops.wuwagent.setting.SettingsState.ApiType.OLLAMA) {
                Thread.sleep(2000)
            }
            filterResult.filteredGraph to filterResult.keywords
        } else {
            onChunk?.invoke("> 📊 프로젝트 규모: **${projectGraph.files.size}개 파일** — 전체 분석합니다.\n\n")
            projectGraph to emptyList()
        }

        val oldSummaryContext = ProjectSummaryFormatter.format(workingGraph)
        val summaryContext = RepoMapFormatter.format(workingGraph)
        
        val reductionRate = if (oldSummaryContext.isNotEmpty()) {
            (oldSummaryContext.length - summaryContext.length).toDouble() / oldSummaryContext.length * 100
        } else 0.0
        
        logger.info("==== Phase 1 Token Test ====")
        logger.info("Old Markdown Length: ${oldSummaryContext.length} chars")
        logger.info("New RepoMap Length: ${summaryContext.length} chars")
        logger.info(String.format("Reduction Rate: %.1f%%", reductionRate))
        logger.info("============================")

        val filterNote = if (keywords.isNotEmpty()) {
            "\n\n> ℹ️ 아래 파일 목록은 요구사항 키워드(${keywords.joinToString(", ")}) 기반으로 필터링된 결과입니다. " +
            "목록에 없는 파일이 필요하면 \"신규\"로 표시하세요."
        } else ""

        val systemPrompt = """
            당신은 Spring Boot 프로젝트의 구조를 분석하는 시니어 아키텍트입니다.
            사용자의 요구사항을 분석하여, 프로젝트에서 수정하거나 새로 생성해야 할 파일 목록을 도출하세요.
            
            ## 분석 규칙
            1. 반드시 아래 제공된 [프로젝트 파일 목록]에 있는 파일만 "수정" 대상으로 지목하세요.
            2. 목록에 없는 파일이 필요하면 "신규" 로 표시하고 권장 경로를 제안하세요.
            3. 작업 순서는 의존성 방향(하위 계층 → 상위 계층)으로 정렬하세요. (Entity/DAO → Service → Controller 순)
            4. 각 파일에 대해 구체적으로 무엇을 해야 하는지 한 줄로 설명하세요.
            5. ChangeRisk가 HIGH 이상인 파일을 수정할 경우 ⚠️ 표시를 붙이세요.
            6. 파일 경로는 프로젝트 루트 기준 상대 경로로 작성하세요 (실제 경로와 정확히 일치해야 함).
            
            ## 응답 포맷
            아래 포맷을 준수하여 분석 결과를 작성하세요. 필요하다면 분석 과정을 서술해도 좋습니다.
            
            ### 요구사항 요약
            (요구사항을 1~2문장으로 재정리)
            
            ### 수정 대상 파일
            
            | 순서 | 파일 경로 | 유형 | 작업 내용 |
            |:---:|:---|:---:|:---|
            | 1 | src/main/java/... | 수정 | (구체적 작업 설명) |
            | 2 | src/main/java/... | 신규 | (구체적 작업 설명) |
            
            ### 작업 시 주의사항
            (ChangeRisk, 의존성 전파 관련 주의사항 2~3줄)
            $filterNote
            ---
            ## 프로젝트 파일 목록
            $summaryContext
        """.trimIndent()

        logger.info("==== Phase 2a: LLM에 전달되는 Project Summary Context (${workingGraph.files.size}개 파일) ====\n$summaryContext\n==================================================")

        val userMessage = "## 요구사항\n$requirement\n\n위 요구사항을 분석하여 응답을 작성해주세요. 자유롭게 생각 과정을 거친 후, 최종 결과는 지정된 포맷(테이블 포함)에 맞게 출력하세요."

        val response = client.chat(systemPrompt, userMessage, onChunk = onChunk)
        val rawResponse = response?.message?.content ?: "[오류] LLM 응답을 받지 못했습니다."

        val result = parseResponse(rawResponse, projectGraph)
        lastResult = result
        return result
    }

    fun parseResponse(rawResponse: String, projectGraph: ProjectGraph? = null): RequirementAnalysisResult {
        val lines = rawResponse.lines()
        
        var summary = ""
        val targetFiles = mutableListOf<TargetFileSpec>()
        var warnings = ""
        
        var currentSection = ""
        
        for (line in lines) {
            val trimmed = line.trim()
            
            if (trimmed.contains("요구사항 요약")) {
                currentSection = "SUMMARY"
                continue
            } else if (trimmed.contains("수정 대상 파일")) {
                currentSection = "TABLE"
                continue
            } else if (trimmed.contains("작업 시 주의사항")) {
                currentSection = "WARNINGS"
                continue
            }
            
            when (currentSection) {
                "SUMMARY" -> {
                    if (trimmed.isNotBlank() && !trimmed.startsWith("###")) {
                        summary += "$trimmed\n"
                    }
                }
                "TABLE" -> {
                    // 테이블 파싱 (다양한 포맷 허용)
                    if (trimmed.startsWith("|") && !trimmed.contains("|:---:|")) {
                        val parts = trimmed.split("|").map { it.trim() }
                        // 맨 앞과 맨 뒤의 파이프 때문에 인덱스 1부터 시작
                        if (parts.size >= 5 && parts[1].toIntOrNull() != null) {
                            val order = parts[1].toInt()
                            val path = parts[2]
                            val type = parts[3]
                            val desc = parts[4]
                            targetFiles.add(TargetFileSpec(order, path, type, desc))
                        }
                    } else if (trimmed.contains("\t")) {
                        // 탭 구분자 (웹뷰에서 복사한 텍스트 또는 탭 포맷)
                        val parts = trimmed.split("\t").map { it.trim() }
                        if (parts.size >= 4 && parts[0].toIntOrNull() != null) {
                            targetFiles.add(TargetFileSpec(parts[0].toInt(), parts[1], parts[2], parts[3]))
                        }
                    } else if (!trimmed.startsWith("|") && trimmed.isNotBlank()) {
                        // 공백이나 숫자 리스트 포맷 (예: "1 src/main/... 수정 내용")
                        val firstToken = trimmed.substringBefore(" ").replace(".", "")
                        if (firstToken.toIntOrNull() != null) {
                            val order = firstToken.toInt()
                            val remainder = trimmed.substringAfter(" ").trim()
                            val path = remainder.substringBefore(" ").trim()
                            val rest = remainder.substringAfter(" ").trim()
                            val type = rest.substringBefore(" ").trim()
                            val desc = rest.substringAfter(" ").trim()
                            if (path.contains("/")) {
                                targetFiles.add(TargetFileSpec(order, path, type, desc))
                            }
                        }
                    }
                }
                "WARNINGS" -> {
                    if (trimmed.isNotBlank() && !trimmed.startsWith("###")) {
                        warnings += "$trimmed\n"
                    }
                }
            }
        }

        val validatedTargetFiles = if (projectGraph != null) {
            TargetFileValidator.validate(targetFiles, projectGraph)
        } else {
            targetFiles
        }

        return RequirementAnalysisResult(
            summary = summary.trim(),
            targetFiles = validatedTargetFiles,
            warnings = warnings.trim(),
            rawResponse = rawResponse
        )
    }
}
