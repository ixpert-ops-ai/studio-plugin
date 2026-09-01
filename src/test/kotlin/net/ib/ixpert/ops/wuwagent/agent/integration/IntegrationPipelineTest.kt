package net.ib.ixpert.ops.wuwagent.agent.integration

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.client.OpenAIClient
import net.ib.ixpert.ops.wuwagent.agent.PipelineE2ETestVllmClient
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.AdaptiveFileDiscovery
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.AgenticSeedSelector
import org.junit.Test
import java.io.File

class IntegrationPipelineTest {

    data class RunResult(
        val project: String,
        val runIndex: Int,
        val totalGt: Int,
        val survivedGt: Int,
        val survivedGtNames: List<String>,
        val stage2Count: Int,
        val stage3Count: Int,
        val stage3TimeMs: Long,
        val completed: Boolean,
        val fallback: Boolean,
        val notes: String
    )

    @Test
    fun runAll() {
        val client = PipelineE2ETestVllmClient()
        val gson = Gson()
        val results = mutableListOf<RunResult>()

        val projects = listOf(
            mapOf(
                "name" to "survey_admin_case_b",
                "path" to "C:/Workspace/HC_card_survey_admin/survey_admin/.meta/project-graph.json",
                "sr" to "기존 설문 발송에 브랜드메시지 발송 채널 추가 및 어드민 개발",
                "gt" to listOf(
                    "SurveyServiceImpl", "SurveyDaoImpl", "SurveyDao", "SurveyDto",
                    "sql_survey.xml", "survey_write.jsp", "survey.write.js",
                    "survey_list.jsp", "survey.list.js",
                    "BrandmessageTemplateBatchJob", "BizgoApiServiceImpl"
                )
            ),
            mapOf(
                "name" to "member-market",
                "path" to "C:/Workspace/member-market/.meta/project-graph.json",
                "sr" to "상품 상세 내 단말기 스펙 조회",
                "gt" to listOf("ProductController", "ProductResponse", "Product") 
            ),
            mapOf(
                "name" to "ISM",
                "path" to "C:/Workspace/graph/project-graph-i/project-graph.json",
                "sr" to "케어회원 관리 화면 조회",
                "gt" to listOf("CareMemberMgmtController", "CareMemberMgmtServiceImpl", "ECMBTBISM006Mapper", "ECMBTBISM006Mapper.xml")
            ),
            mapOf(
                "name" to "apc",
                "path" to "C:/Users/dffrp/Downloads/project-graph_a/project-graph.json",
                "sr" to "교통카드 발급업체 변경 후, 이전 모바일교통카드 이용회원 체크 및 재발급 안내를 위한 신규서비스 개발 건. 교통카드 구 발급정보 조회 신규서비스 개발 (SAPACMM0802S01 기존서비스 참고). APCMMTrcdIsInfSVO 수정, APCMMTrcdIsSVC.java 신규서비스 추가, aCMBTBAPC024DEM.selTrcdIsInf 참조하여 앱카드회원ID 및 모니모페이회원ID 최근이력 1건 조회",
                "gt" to listOf("APCMMTrcdIsInfSVO", "APCMMTrcdIsSVC", "APCMMTrcdIsSVCImpl", "APCMMTrcdIsBIZ", "ACMBTBAPC024DEM")
            )
        )

        for (proj in projects) {
            val name = proj["name"] as String
            val path = proj["path"] as String
            val projectBasePath = if (path.contains("/.meta/")) path.substringBefore("/.meta/") else null
            val sr = proj["sr"] as String
            @Suppress("UNCHECKED_CAST")
            val gt = proj["gt"] as List<String>
            
            val iterations = 1
            
            val actualPath = path
            
            if (!File(actualPath).exists()) {
                println("Skipping $name: file not found at $actualPath")
                continue
            }
            
            println("Loading graph for $name from $actualPath")
            val graphContent = File(actualPath).readText()
            val graph = gson.fromJson(graphContent, ProjectGraph::class.java)
            
            for (i in 1..iterations) {
                println("=== Running $name (Run $i) ===")
                try {
                    val start = System.currentTimeMillis()
                    var stage2Count = 0
                    
                    val discoveryResult = AdaptiveFileDiscovery.filter(
                        primaryReq = sr,
                        secondaryReq = "",
                        graph = graph,
                        client = client,
                        project = null,
                        projectBasePath = projectBasePath,
                        enhancedRequirements = emptyList(),
                        seedSelector = null,
                        onProgress = { msg ->
                            if (msg.contains("후보")) {
                                val match = Regex("\\((\\d+)개 후보\\)").find(msg)
                                if (match != null) {
                                    stage2Count = match.groupValues[1].toInt()
                                }
                            }
                        }
                    )
                    
                    val verifiedFiles = discoveryResult.relevantFiles
                    val stage3TimeMs = System.currentTimeMillis() - start
                    
                    println("=== FINAL STAGE 3 RESULT FILES FOR $name ===")
                    verifiedFiles.forEach { file -> 
                        println("RESULT_FILE: [$name] " + file.path + " (Score: " + file.score + " / Reason: " + file.discoveryReason + ")")
                    }
                    // --- Recall Diagnostics Instrument (CP0 ~ CP6) ---
                    fun matchesGt(identifier: String, targetGt: String): Boolean {
                        val rawName = identifier.substringAfterLast('/').substringAfterLast('\\')
                        val nameWithoutExt = rawName.substringBeforeLast('.')
                        val gtWithoutExt = targetGt.substringBeforeLast('.')
                        return rawName.equals(targetGt, ignoreCase = true) ||
                               rawName.equals(gtWithoutExt, ignoreCase = true) ||
                               nameWithoutExt.equals(targetGt, ignoreCase = true) ||
                               nameWithoutExt.equals(gtWithoutExt, ignoreCase = true) ||
                               identifier.contains(targetGt, ignoreCase = true)
                    }

                    val cpReport = StringBuilder()
                    cpReport.appendLine("\n==========================================================================================")
                    cpReport.appendLine("📊 RECALL DIAGNOSTICS TABLE: $name (Run $i)")
                    cpReport.appendLine("==========================================================================================")
                    cpReport.appendLine("| GT Name | CP0(Node) | CP1(BM25) | CP2(Seed/Judge) | CP3(Graph) | CP4(Scorer) | CP6(Final) | Drop Point Diagnosis |")
                    cpReport.appendLine("|---|:---:|:---:|:---:|:---:|:---:|:---:|---|")

                    val meta = discoveryResult.metadata
                    for (g in gt) {
                        val cp0 = graph.files.keys.any { matchesGt(it, g) } || graph.resourceNodes.any { matchesGt(it.path, g) }
                        val cp1 = meta.rawCandidates.any { matchesGt(it, g) }
                        val cp2 = meta.seedClasses.any { matchesGt(it, g) } || meta.judgePicks.any { matchesGt(it, g) } || meta.frontendFileHints.any { matchesGt(it, g) }
                        val cp3 = meta.expansionTrace.keys.any { matchesGt(it, g) }
                        val cp4 = discoveryResult.relevantFiles.any { matchesGt(it.path, g) || matchesGt(it.className, g) }
                        val cp6 = verifiedFiles.any { matchesGt(it.path, g) || matchesGt(it.className, g) }

                        val dropReason = when {
                            !cp0 -> "CP0: 메타그래프 노드 부재 (신규 A)"
                            !cp1 && !cp3 -> "CP1: Layer 1 후보 풀 탈락 (BM25/사전 누락)"
                            !cp2 && !cp3 -> "CP2->3: Seed/Judge 미선정으로 확장 단절"
                            !cp3 -> "CP3: GraphExpander 미도달 (필터 차단 or 엣지 단절)"
                            !cp4 -> "CP4: RelevanceScorer 컷오프 탈락 (점수<55 & Bypass 부재)"
                            !cp6 -> "CP6: Verifier LLM 거부 (UNNECESSARY 판정)"
                            else -> "✅ 최종 생존"
                        }

                        fun mark(b: Boolean) = if (b) "⭕" else "❌"
                        cpReport.appendLine("| $g | ${mark(cp0)} | ${mark(cp1)} | ${mark(cp2)} | ${mark(cp3)} | ${mark(cp4)} | ${mark(cp6)} | $dropReason |")
                    }
                    cpReport.appendLine("==========================================================================================\n")
                    println(cpReport.toString())

                    val survivedGtNames = gt.filter { g -> verifiedFiles.any { vf -> matchesGt(vf.path, g) || matchesGt(vf.className, g) } }
                    val survivedGt = survivedGtNames.size
                    
                    val fallback = verifiedFiles.any { it.discoveryReason.contains("Fallback") || it.discoveryReason.contains("실패") }
                    
                    results.add(RunResult(
                        project = name,
                        runIndex = i,
                        totalGt = gt.size,
                        survivedGt = survivedGt,
                        survivedGtNames = survivedGtNames,
                        stage2Count = stage2Count,
                        stage3Count = verifiedFiles.size,
                        stage3TimeMs = stage3TimeMs,
                        completed = true,
                        fallback = fallback,
                        notes = "Success"
                    ))
                    
                } catch (e: Exception) {
                    println("Error in $name: " + e.message)
                    e.printStackTrace()
                    results.add(RunResult(
                        project = name,
                        runIndex = i,
                        totalGt = gt.size,
                        survivedGt = 0,
                        survivedGtNames = emptyList(),
                        stage2Count = 0,
                        stage3Count = 0,
                        stage3TimeMs = 0,
                        completed = false,
                        fallback = false,
                        notes = "Exception: " + e.javaClass.simpleName
                    ))
                }
            }
        }
        
        val out = java.lang.StringBuilder()
        out.appendLine("=== Integration Test Report ===")
        out.appendLine("| Project | Run | GT (Surv/Total) | Survived GT Names | Stg2 Cnt | Stg3 Cnt | Stg3 Time | Completed | Fallback |")
        out.appendLine("|---|---|---|---|---|---|---|---|---|")
        for (res in results) {
            val gtStr = if (res.totalGt == 0) "N/A" else "" + res.survivedGt + "/" + res.totalGt
            val time = if (res.stage3TimeMs > 0) "" + res.stage3TimeMs + "ms" else "N/A"
            out.appendLine("| " + res.project + " | " + res.runIndex + " | " + gtStr + " | " + res.survivedGtNames.joinToString(", ") + " | " + res.stage2Count + " | " + res.stage3Count + " | " + time + " | " + res.completed + " | " + res.fallback + " |")
        }
        
        File("C:/Workspace/DEV-ASSISTANT/IDE-PLUGIN/intelliJ/ai-assistant-plugin/integration_test_report.txt").writeText(out.toString())
        File("C:/Workspace/DEV-ASSISTANT/IDE-PLUGIN/intelliJ/ai-assistant-plugin/integration_test_raw.json").writeText(gson.toJson(results))
        println(out.toString())
    }
}