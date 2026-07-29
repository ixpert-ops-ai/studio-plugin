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
                if (subGraph.totalFileCount == 0) {
                    onChunk?.invoke("> ⚠️ **선택된 패키지에 해당하는 파일이 없습니다. 전체 프로젝트를 대상으로 진행합니다.**\n")
                    projectGraph
                } else {
                    onChunk?.invoke("> ✅ **선택 범위 반영 완료:** ${subGraph.totalFileCount}개 파일로 대상을 축소했습니다.\n")
                    
                    // 외부 의존성 제안
                    val suggestions = net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DependencySuggester.suggestExternalDependencies(
                        subGraph, projectGraph, net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.SuggestionConfig()
                    )
                    
                    if (suggestions.isNotEmpty()) {
                        // 프론트엔드에 의존성 선택 UI(showDependency)가 아직 없으므로 자동 포함하되,
                        // 패키지 전체(packagePath)가 아니라 "실제로 참조된 파일"만 포함하여 범위 폭발을 방지한다.
                        val autoAcceptedFiles = suggestions
                            .filter { !it.isUtility }                 // 유틸 패키지 제외
                            .flatMap { it.referencedFiles }           // 패키지 통째 → 참조된 파일만
                            .distinct()
                            .take(50)                                 // 자동 포함 안전 캡 (UI 부재 임시방편)
    
                        val expandedPaths = scopeResult.selectedPaths + autoAcceptedFiles
                        val finalGraph = net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.ScopeSelector.buildSubMetaGraph(projectGraph, expandedPaths)
                        onChunk?.invoke("> ✅ **외부 의존성 자동 포함 완료:** ${finalGraph.totalFileCount}개 파일로 범위가 확장되었습니다.\n")
                        finalGraph
                    } else {
                        subGraph
                    }
                }
            } else {
                // 잠정 보수적 임계값 (정밀 경계 아님).
                // 실측 근거: member-market(105), survey-admin(137) skip 시 안전 / APC(2,670) skip 시 recall 0%.
                // 138~2,669 구간은 미검증 — 이 값이 정당한 로컬 SR을 오차단할 가능성 있음. 
                // 향후 150~2000 규모의 SR 케이스 확보 시 재조정 요망.
                val hardLimit = 200
                if (projectGraph.totalFileCount > hardLimit) {
                    val msg = "> 📦 **대상 파일이 너무 많습니다 (${projectGraph.totalFileCount}개)**\n" +
                              "> 파일이 많아 이 상태로는 정확한 대상을 좁히기 어렵습니다. 아래 중 하나를 진행해 주세요:\n" +
                              "> - **범위 좁히기** — 작업과 관련된 폴더/패키지를 선택해 다시 실행해 주세요. (권장)\n" +
                              "> - **전역 공통 변경인 경우** — 만약 여러 도메인에 공통으로 적용되는 변경(예: 전 API 공통 로깅)이라면, 개별 파일 수정보다 공통 모듈(AOP/인터셉터/부모 클래스) 관점에서 접근하는 것이 적절합니다.\n"
                    onChunk?.invoke(msg)
                    throw IllegalArgumentException("대상 파일이 너무 많습니다 (${projectGraph.totalFileCount}개). 폴더/패키지를 선택하여 범위를 좁히거나, 공통 모듈 관점에서 요구사항을 재정의해 주세요.")
                } else {
                    onChunk?.invoke("> \uD83D\uDCA1 **Tip:** 전체 ${projectGraph.totalFileCount}개 파일을 대상으로 탐색 중입니다. Project 뷰에서 관련 패키지를 선택 후 실행하시면 분석의 정확도와 속도가 크게 향상됩니다.\n\n")
                    projectGraph
                }
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
        println("=== Stage 3 Candidates ===")
        correctedFiles.forEach { println(it.path) }
        println("==========================")
        val verifier = FileRelevanceVerifier(client, projectGraph, mdRoot)
        val fullRequirement = if (secondaryReq.isNotBlank()) "$primaryReq\n$secondaryReq" else primaryReq
        val verificationOutput = verifier.verify(fullRequirement, correctedFiles)
        val verifiedFiles = verificationOutput.files
        val validatedTargetFiles = TargetFileValidator.sortByDependency(verifiedFiles, projectGraph)
        
        // --- SHADOW LOGGER INTEGRATION ---
        try {
            val guard = net.ib.ixpert.ops.wuwagent.agent.completeness.CompletenessGuardIntegration(net.ib.ixpert.ops.wuwagent.agent.completeness.GuardMode.SHADOW)
            
            // Heuristic SrFacts derivation from validatedTargetFiles
            val hasCreate = validatedTargetFiles.any { it.type == "CREATE" }
            val hasService = validatedTargetFiles.any { it.path.contains("Service") }
            val hasDataLayer = validatedTargetFiles.any { it.path.contains("Dao") || it.path.contains("Repository") || it.path.endsWith("xml") }
            val hasUi = validatedTargetFiles.any { it.path.endsWith(".jsp") || it.path.endsWith(".html") || it.path.endsWith(".js") }

            val srFacts = net.ib.ixpert.ops.wuwagent.agent.completeness.model.SrFacts(
                hasUserAction = hasUi, // Conservative: assume UI means user action
                touchesUi = hasUi,
                hasBusinessLogic = hasService,
                readsOrWritesData = hasDataLayer,
                // [KNOWN ISSUE] addsNewMethod는 현재 파일 생성(CREATE) 여부만으로 판정하므로, 
                // 기존 파일(MODIFY)에 새 메서드를 추가하는 경우를 놓칠 수 있습니다. 
                // 이는 수집된 로그의 srFactsSource가 'heuristic-from-pipeline-output'일 때 분석가가 감안해야 합니다.
                addsNewMethod = hasCreate 
            )
            
            val srKeyHex = String.format("SR-%08x", primaryReq.hashCode())
            guard.evaluateAfterVerifier(
                frameworkType = fwType,
                requiredFiles = validatedTargetFiles.map { it.path }.toSet(),
                srFacts = srFacts,
                ctx = net.ib.ixpert.ops.wuwagent.agent.completeness.ProjectGraphAdapter(projectGraph),
                projectRoot = project?.basePath ?: projectGraph.projectRoot,
                runId = java.util.UUID.randomUUID().toString(),
                srKey = srKeyHex,
                srFactsSource = "heuristic-from-pipeline-output"
            )
            logger.info("CompletenessGuardIntegration invoked for \$srKeyHex in SHADOW mode (addsNewMethod=\$hasCreate).")
        } catch (e: Exception) {
            logger.warn("Failed to execute CompletenessGuardIntegration", e)
        }
        // ---------------------------------

        val formattedOutput = buildString {
            appendLine("### 요구사항 분석 요약")
            
            // Stage 3(Batch)에서 판정한 전체 요약 근거가 있다면 우선적으로 보여줌
            if (verificationOutput.reasoning.isNotBlank()) {
                appendLine(verificationOutput.reasoning.replace(Regex("\\s+(?=\\d+[\\.\\)]\\s)"), "\n"))
            } else {
                appendLine("> ⚠️ **검증 단계 파싱 실패**: LLM 응답 포맷 오류로 인해 Stage 1/2 후보 결과를 모두 유지합니다. 결과를 직접 확인하세요.")
                appendLine()
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
            val totalCandidates = discoveryResult.metadata.totalCandidates + discoveryResult.suggestedNewFiles.size
            appendLine("- **파일 필터링 결과**: 총 ${totalCandidates}개 대상(신규 제안 ${discoveryResult.suggestedNewFiles.size}개 포함) 중 ${validatedTargetFiles.size}개 최종 선정")
        }
        onChunk?.invoke("\n" + formattedOutput + "\n")
        
        val rawReasoning = if (verificationOutput.reasoning.isNotBlank()) {
            verificationOutput.reasoning
        } else {
            discoveryResult.metadata.reasoning.ifBlank { "그래프 탐색 완료" }
        }
        val finalReasoning = rawReasoning.replace(Regex("\\s+(?=\\d+[\\.\\)]\\s)"), "\n")
        
        // Verifier에서 UNNECESSARY로 판정되어 제거된 신규 파일은 suggestedNewFiles에서도 제외
        val verifiedNewPaths = validatedTargetFiles.filter { it.type == "CREATE" || it.type == "신규" }.map { it.path }
        val verifiedSuggestedNewFiles = discoveryResult.suggestedNewFiles.filter { it.suggestedPath in verifiedNewPaths }

        val result = RequirementAnalysisResult(
            summary = finalReasoning,
            targetFiles = validatedTargetFiles,
            warnings = discoveryResult.metadata.reasoning,
            rawResponse = "그래프 탐색 결과 처리 완료",
            suggestedNewFiles = verifiedSuggestedNewFiles
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
