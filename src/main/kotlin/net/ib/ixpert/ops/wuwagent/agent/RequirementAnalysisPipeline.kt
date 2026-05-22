package net.ib.ixpert.ops.wuwagent.agent

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.ProjectSummaryFormatter
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.RepoMapFormatter
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.RelevanceFilter
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.AdaptiveFileDiscovery

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
        val (workingGraph, keywords, candidates) = if (projectGraph.graphType == net.ib.ixpert.ops.wuwagent.service.metagraph.model.GraphType.MULTI_LEVEL_1 || projectGraph.files.size > FILE_COUNT_THRESHOLD) {
            val filterMsg = if (projectGraph.graphType == net.ib.ixpert.ops.wuwagent.service.metagraph.model.GraphType.MULTI_LEVEL_1) {
                "> 📊 멀티 모듈 요약 그래프가 감지되었습니다. 요구사항과 관련 있는 모듈 및 파일만 필터링합니다.\n\n"
            } else {
                "> 📊 프로젝트 규모: **${projectGraph.files.size}개 파일** — 관련 파일만 필터링합니다.\n\n"
            }
            onChunk?.invoke(filterMsg)
            
            val filterResult = AdaptiveFileDiscovery.filter(requirement, projectGraph, client, project) { progress ->
                onChunk?.invoke(progress)
            }
            // Ollama 서버의 연속 호출(키워드 추출 -> 전체 분석) 시 컨텍스트 정리 및 커넥션 안정화를 위한 대기
            // OpenAI, AIPro 등 클라우드 API는 비동기 병렬 처리가 원활하므로 딜레이 제외
            val settings = net.ib.ixpert.ops.wuwagent.setting.SettingsState.getInstance().state
            if (settings.apiType == net.ib.ixpert.ops.wuwagent.setting.SettingsState.ApiType.OLLAMA) {
                Thread.sleep(2000)
            }
            Triple(filterResult.filteredGraph, filterResult.keywords, filterResult.candidates)
        } else {
            onChunk?.invoke("> 📊 프로젝트 규모: **${projectGraph.files.size}개 파일** — 전체 분석합니다.\n\n")
            Triple(projectGraph, emptyList<String>(), null)
        }

        val candidatesList = candidates ?: workingGraph.files.values.map { 
            net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.ScoredCandidate(it.path, 0.0, emptyList())
        }

        val selector = net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.LlmCandidateSelector(client, workingGraph)
        onChunk?.invoke("> 🤖 (Stage 2) LLM을 통한 정밀 분석을 시작합니다...\n")
        
        val selectionResult = selector.select(requirement, candidatesList)
        
        val targetFiles = mutableListOf<TargetFileSpec>()
        selectionResult.modify?.forEach { action ->
            targetFiles.add(TargetFileSpec(action.order ?: 0, action.path ?: "", "수정", action.reason ?: ""))
        }
        selectionResult.create?.forEach { action ->
            targetFiles.add(TargetFileSpec(action.order ?: 0, action.path ?: "", "신규", action.reason ?: ""))
        }
        
        val formattedOutput = buildString {
            if (!selectionResult.summary.isNullOrBlank()) {
                appendLine("### 요구사항 요약")
                appendLine(selectionResult.summary)
                appendLine()
            }
            if (targetFiles.isNotEmpty()) {
                appendLine("### 분석된 대상 파일")
                appendLine("| 순서 | 파일 경로 | 유형 | 작업 내용 |")
                appendLine("|:---:|:---|:---:|:---|")
                targetFiles.forEach {
                    appendLine("| ${it.order} | ${it.path} | ${it.type} | ${it.description} |")
                }
            } else {
                appendLine("⚠️ 관련된 대상 파일을 찾지 못했습니다.")
            }
            if (!selectionResult.warnings.isNullOrEmpty()) {
                appendLine()
                appendLine("### 작업 시 주의사항")
                selectionResult.warnings.forEach { appendLine("- $it") }
            }
            if (!selectionResult.reasoning.isNullOrBlank()) {
                appendLine()
                appendLine("### 선정 사유")
                appendLine(selectionResult.reasoning)
            }
        }
        onChunk?.invoke("\n" + formattedOutput + "\n")
        
        val validatedTargetFiles = TargetFileValidator.validate(targetFiles, workingGraph)
        
        val result = RequirementAnalysisResult(
            summary = selectionResult.summary ?: "",
            targetFiles = validatedTargetFiles,
            warnings = selectionResult.warnings?.joinToString("\n") ?: "",
            rawResponse = "JSON Response Processed by LlmCandidateSelector"
        )
        
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
