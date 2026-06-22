package net.ib.ixpert.ops.wuwagent.agent

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.AdaptiveFileDiscovery
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.ChangeIntent
import java.nio.file.Paths

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
    val rawResponse: String,
    val suggestedNewFiles: List<net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.NewFileProposal> = emptyList()
)

class RequirementAnalysisPipeline(private val project: Project?, private val client: LLMClient) {
    constructor(client: LLMClient) : this(null, client)

    companion object {
        var lastResult: RequirementAnalysisResult? = null
    }

    private val logger = Logger.getInstance(RequirementAnalysisPipeline::class.java)

    suspend fun analyze(
        primaryReq: String, 
        secondaryReq: String, 
        projectGraph: ProjectGraph, 
        enhancedRequirements: List<String> = emptyList(),
        onChunk: ((String) -> Unit)? = null
    ): RequirementAnalysisResult {
        val fwType = projectGraph.frameworkDetection?.userOverride ?: projectGraph.frameworkType
        logger.info("Starting RequirementAnalysisPipeline. Resolved Framework Type: ${fwType.name}")
        
        // --- Stage 0.5: Scope Selection ---
        val threshold = 50 // Configuration threshold
        val workingMetaGraph: net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraphQueryable = if (projectGraph.totalFileCount > threshold) {
            onChunk?.invoke("> 📦 **프로젝트 규모가 큽니다 (${projectGraph.totalFileCount}개 파일). 관련 패키지 선택을 요청합니다.**\n")
            val scopeConfig = net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.ScopeConfig()
            val tree = net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.ScopeSelector.buildDirectoryTree(projectGraph, scopeConfig)
            
            // UI 이벤트 대기 (비동기)
            val scopeResult = net.ib.ixpert.ops.wuwagent.agent.ScopeSelectionBridge.requestScopeSelection(project, tree, scopeConfig, onChunk)
            
            if (scopeResult != null && scopeResult.selectedPaths.isNotEmpty()) {
                val subGraph = net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.ScopeSelector.buildSubMetaGraph(projectGraph, scopeResult.selectedPaths)
                onChunk?.invoke("> ✅ **선택 범위 반영 완료:** ${subGraph.totalFileCount}개 파일로 대상을 축소했습니다.\n")
                
                // 외부 의존성 제안
                val suggestions = net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DependencySuggester.suggestExternalDependencies(
                    subGraph, projectGraph, net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.SuggestionConfig()
                )
                
                if (suggestions.isNotEmpty()) {
                    // 프론트엔드에 의존성 선택 UI(showDependency)가 아직 없으므로, 제안된 의존성을 자동으로 포함합니다.
                    val autoAcceptedPaths = suggestions.map { it.packagePath }
                    val expandedPaths = scopeResult.selectedPaths + autoAcceptedPaths
                    val finalGraph = net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.ScopeSelector.buildSubMetaGraph(projectGraph, expandedPaths)
                    onChunk?.invoke("> ✅ **외부 의존성 자동 포함 완료:** ${finalGraph.totalFileCount}개 파일로 범위가 확장되었습니다.\n")
                    finalGraph
                } else {
                    subGraph
                }
            } else {
                onChunk?.invoke("> ⚠️ **선택이 취소되었거나 타임아웃 되었습니다. 전체 프로젝트를 대상으로 진행합니다.**\n")
                projectGraph
            }
        } else {
            projectGraph
        }
        // -----------------------------------

        try {
            val basePath = project?.basePath
            if (basePath != null && workingMetaGraph is net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.SubMetaGraph) {
                val dumpFile = java.io.File(basePath, ".meta/sub-graph.json")
                dumpFile.parentFile.mkdirs()
                val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
                dumpFile.writeText(gson.toJson(workingMetaGraph), Charsets.UTF_8)
                logger.info("SubGraph dumped to ${dumpFile.absolutePath}")
            }
        } catch (e: Exception) {
            logger.warn("Failed to dump sub-graph", e)
        }

        val discoveryResult = AdaptiveFileDiscovery.filter(primaryReq, secondaryReq, workingMetaGraph, client, project, enhancedRequirements) { progress ->
            onChunk?.invoke("> $progress\n")
        }
        
        val targetFiles = mutableListOf<TargetFileSpec>()
        
        discoveryResult.relevantFiles.forEachIndexed { index, file ->
            targetFiles.add(TargetFileSpec(
                order = index + 1, 
                path = file.path, 
                type = "MODIFY", 
                description = "Score: ${file.score}, Via: ${file.discoveryReason}"
            ))
        }

        discoveryResult.suggestedNewFiles.forEachIndexed { index, file ->
            targetFiles.add(TargetFileSpec(
                order = targetFiles.size + 1,
                path = file.suggestedPath,
                type = "CREATE",
                description = "- suggestedFileType: ${file.suggestedFileType}\n- reason: ${file.reason}\n- referencePattern: ${file.referencePattern}"
            ))
        }
        onChunk?.invoke("\n> **(Stage 2) Trimming** - 불필요한 파일 경로 보정 및 필터링...\n")
        val correctedFiles = TargetFileValidator.correctPaths(targetFiles, projectGraph)
        val mdRoot = Paths.get(project?.basePath ?: "", "docs")
        
        onChunk?.invoke("\n> **(Stage 3) LLM Verification** - 최종 연관성 검증...\n")
        val verifier = FileRelevanceVerifier(client, projectGraph, mdRoot)
        val fullRequirement = if (secondaryReq.isNotBlank()) "$primaryReq\n$secondaryReq" else primaryReq
        val verificationOutput = verifier.verify(fullRequirement, correctedFiles)
        val verifiedFiles = verificationOutput.files
        val validatedTargetFiles = TargetFileValidator.sortByDependency(verifiedFiles, projectGraph)
        
        val formattedOutput = buildString {
            appendLine("### 요구사항 분석 요약")
            
            // Stage 3(Batch)에서 판정한 전체 요약 근거가 있다면 우선적으로 보여줌
            if (verificationOutput.reasoning.isNotBlank()) {
                appendLine(verificationOutput.reasoning.replace(Regex("\\s+(?=\\d+[\\.\\)]\\s)"), "\n"))
            } else {
                val formattedReasoning = discoveryResult.metadata.reasoning.replace(Regex("\\s+(?=\\d+[\\.\\)]\\s)"), "\n")
                if (formattedReasoning.isNotBlank()) {
                    appendLine(formattedReasoning)
                }
            }
            appendLine()
            val koreanIntent = when(discoveryResult.metadata.changeIntent.name) {
                "MODIFY" -> "수정"
                "CREATE" -> "신규"
                "DELETE" -> "삭제"
                else -> discoveryResult.metadata.changeIntent.name
            }
            appendLine("**(작업유형: $koreanIntent) 관련된 초기 핵심 파일(Seed) 식별:** ${discoveryResult.metadata.seedClasses.joinToString()}")
            appendLine()
            
            if (validatedTargetFiles.isNotEmpty()) {
                appendLine("### 🎯 분석된 대상 파일 목록")
                appendLine("| 순번 | 파일 경로 | 작업유형 | 수정 사유 |")
                appendLine("|:---:|:---|:---:|:---|")
                validatedTargetFiles.forEach {
                    val koreanType = when(it.type) {
                        "MODIFY" -> "수정"
                        "CREATE" -> "신규"
                        "DELETE" -> "삭제"
                        else -> it.type
                    }
                    val descForTable = it.description.replace("\n", "<br>")
                    appendLine("| ${it.order} | ${it.path} | $koreanType | $descForTable |")
                }
            } else {
                appendLine("⚠️ **경고**: 관련된 대상 파일을 찾지 못했습니다.")
            }
            appendLine()
            appendLine("### 📊 탐색 메타데이터")
            appendLine("- **선정된 초기 파일(Seed)**: ${discoveryResult.metadata.seedClasses.joinToString()}")
            appendLine("- **파일 필터링 결과**: 총 ${discoveryResult.metadata.totalCandidates}개 대상 중 ${validatedTargetFiles.size}개 최종 선정")
        }
        onChunk?.invoke("\n" + formattedOutput + "\n")
        
        val rawReasoning = if (verificationOutput.reasoning.isNotBlank()) {
            verificationOutput.reasoning
        } else {
            discoveryResult.metadata.reasoning.ifBlank { "그래프 탐색 완료" }
        }
        val finalReasoning = rawReasoning.replace(Regex("\\s+(?=\\d+[\\.\\)]\\s)"), "\n")

        val result = RequirementAnalysisResult(
            summary = finalReasoning,
            targetFiles = validatedTargetFiles,
            warnings = discoveryResult.metadata.reasoning,
            rawResponse = "그래프 탐색 결과 처리 완료",
            suggestedNewFiles = discoveryResult.suggestedNewFiles
        )
        
        lastResult = result
        return result
    }

    fun parseResponse(rawResponse: String, projectGraph: ProjectGraph? = null): RequirementAnalysisResult {
        val targetFiles = mutableListOf<TargetFileSpec>()
        return RequirementAnalysisResult(
            summary = "",
            targetFiles = targetFiles,
            warnings = "",
            rawResponse = rawResponse,
            suggestedNewFiles = emptyList()
        )
    }
}
