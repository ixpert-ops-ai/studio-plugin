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
                "path" to "C:/Workspace/graph/project-graph-a/project-graph.json",
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
            
            val iterations = 2
            
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

    @Test
    fun testHeldOut() {
        val client = PipelineE2ETestVllmClient()
        val gson = Gson()
        val results = mutableListOf<RunResult>()

        val heldOutProjects = listOf(
            mapOf(
                "name" to "ISM_HeldOut_PDsbUse",
                "path" to "C:/Workspace/graph/project-graph-i/project-graph.json",
                "sr" to "개인별 포인트유형별 사용내역 조회 화면 및 엑셀 다운로드 개발",
                "gt" to listOf("PDsbUseController", "PDsbUseServiceImpl", "ECSITBISM015Mapper", "ECSITBISM015Mapper.xml")
            ),
            mapOf(
                "name" to "ISM_HeldOut_ScReturn",
                "path" to "C:/Workspace/graph/project-graph-i/project-graph.json",
                "sr" to "반품지연 목록 및 상세 내역 조회, 반품지연 메모 등록 기능 개발",
                "gt" to listOf("ScReturnController", "ScReturnServiceImpl", "SC_ECOPTBISM026Mapper", "ECOPTBISM026.xml")
            ),
            mapOf(
                "name" to "member_market_HeldOut_Chat",
                "path" to "C:/Workspace/member-market/.meta/project-graph.json",
                "sr" to "채팅방 목록 및 메시지 내역 조회, 메시지 전송 및 읽음 처리 기능 개발",
                "gt" to listOf("ChatController", "ChatService", "ChatMessage", "ChatRoom")
            )
        )

        for (proj in heldOutProjects) {
            val name = proj["name"] as String
            val path = proj["path"] as String
            val projectBasePath = if (path.contains("/.meta/")) path.substringBefore("/.meta/") else null
            val sr = proj["sr"] as String
            @Suppress("UNCHECKED_CAST")
            val gt = proj["gt"] as List<String>
            
            val iterations = 3
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

                    val survivedGtNames = gt.filter { g -> verifiedFiles.any { matchesGt(it.path, g) || matchesGt(it.className, g) } }
                    results.add(RunResult(
                        project = name,
                        runIndex = i,
                        totalGt = gt.size,
                        survivedGt = survivedGtNames.size,
                        survivedGtNames = survivedGtNames,
                        stage2Count = stage2Count,
                        stage3Count = verifiedFiles.size,
                        stage3TimeMs = stage3TimeMs,
                        completed = true,
                        fallback = false,
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
        out.appendLine("=== Held-Out Test Report ===")
        out.appendLine("| Project | Run | GT (Surv/Total) | Survived GT Names | Stg2 Cnt | Stg3 Cnt | Stg3 Time | Completed | Fallback |")
        out.appendLine("|---|---|---|---|---|---|---|---|---|")
        for (res in results) {
            val gtStr = if (res.totalGt == 0) "N/A" else "" + res.survivedGt + "/" + res.totalGt
            val time = if (res.stage3TimeMs > 0) "" + res.stage3TimeMs + "ms" else "N/A"
            out.appendLine("| " + res.project + " | " + res.runIndex + " | " + gtStr + " | " + res.survivedGtNames.joinToString(", ") + " | " + res.stage2Count + " | " + res.stage3Count + " | " + time + " | " + res.completed + " | " + res.fallback + " |")
        }
        println(out.toString())
    }

    @Test
    fun testOpenAIClientStreaming30FilesDirectly() {
        val client = net.ib.ixpert.ops.wuwagent.client.OpenAIClient()
        val payloadJsContent = File("C:/Users/dffrp/.gemini/antigravity/brain/dead1e82-1168-4141-b615-c0196d0a2637/scratch/vllm_payload.js").readText()

        val systemPrompt = "당신은 코드 변경 범위 검증자입니다.\n아래 요구사항을 구현하기 위해 수정이 필요한 파일 목록을 검증합니다.\n반드시 제공된 `submit_verification` 도구를 호출하여 결과를 제출하세요."
        val userPromptStartIndex = payloadJsContent.indexOf("## 요구사항")
        val userPromptEndIndex = payloadJsContent.indexOf("`\n    }\n  ],\n  tools:")
        val userContent = payloadJsContent.substring(userPromptStartIndex, userPromptEndIndex).replace("\\n", "\n")

        val tool = net.ib.ixpert.ops.wuwagent.model.ToolDefinition(
            type = "function",
            function = net.ib.ixpert.ops.wuwagent.model.FunctionDefinition(
                name = "submit_verification",
                description = "파일 목록 검증 결과를 제출합니다.",
                parameters = net.ib.ixpert.ops.wuwagent.model.FunctionParameters(
                    type = "object",
                    properties = mapOf(
                        "fileVerdicts" to net.ib.ixpert.ops.wuwagent.model.PropertyDefinition(
                            type = "array",
                            description = "각 후보 파일에 대한 판정 결과 목록",
                            items = net.ib.ixpert.ops.wuwagent.model.PropertyDefinition(
                                type = "object",
                                properties = mapOf(
                                    "filePath" to net.ib.ixpert.ops.wuwagent.model.PropertyDefinition(type = "string"),
                                    "verdict" to net.ib.ixpert.ops.wuwagent.model.PropertyDefinition(type = "string", enum = listOf("REQUIRED", "UNNECESSARY")),
                                    "reason" to net.ib.ixpert.ops.wuwagent.model.PropertyDefinition(type = "string")
                                ),
                                required = listOf("filePath", "verdict", "reason")
                            )
                        ),
                        "reasoning" to net.ib.ixpert.ops.wuwagent.model.PropertyDefinition(
                            type = "string",
                            description = "전체 판정 근거 종합 요약"
                        )
                    ),
                    required = listOf("fileVerdicts", "reasoning")
                )
            )
        )

        println("=== [START] OpenAIClient.chatWithTools Streaming 30-Files Real Test ===")
        val startMs = System.currentTimeMillis()

        val response = client.chatWithTools(
            systemPrompt = systemPrompt,
            messages = listOf(net.ib.ixpert.ops.wuwagent.model.ChatMessage(role = "user", content = userContent)),
            maxTokens = 4000,
            tools = listOf(tool),
            toolChoice = mapOf("type" to "function", "function" to mapOf("name" to "submit_verification")),
            temperature = 0.1
        )

        val elapsedMs = System.currentTimeMillis() - startMs
        println("=== [COMPLETE] OpenAIClient.chatWithTools Finished in ${elapsedMs}ms (${elapsedMs / 1000}s) ===")

        org.junit.Assert.assertNotNull("Response must not be null", response)
        val choice = response?.choices?.firstOrNull()
        org.junit.Assert.assertNotNull("Choice must not be null", choice)
        org.junit.Assert.assertEquals("Finish reason must be stop", "stop", choice?.finishReason)

        val toolCalls = choice?.message?.toolCalls
        org.junit.Assert.assertNotNull("ToolCalls must not be null", toolCalls)
        org.junit.Assert.assertTrue("ToolCalls must not be empty", toolCalls?.isNotEmpty() == true)

        val primaryCall = toolCalls!!.first()
        org.junit.Assert.assertEquals("submit_verification", primaryCall.function.name)

        val argsJson = primaryCall.function.arguments
        println("Assembled Arguments Length: ${argsJson.length}")
        org.junit.Assert.assertTrue("Arguments length should be > 5000 chars", argsJson.length > 5000)

        val gson = Gson()
        val parsed = gson.fromJson(argsJson, Map::class.java)
        val fileVerdicts = parsed["fileVerdicts"] as? List<*>
        org.junit.Assert.assertNotNull("fileVerdicts must be present", fileVerdicts)
        println("Parsed fileVerdicts count: ${fileVerdicts?.size}")
        org.junit.Assert.assertEquals("Must parse 30 file verdicts", 30, fileVerdicts?.size)

        println("=== OpenAIClient Streaming ToolCalling Kotlin Test PASSED PERFECTLY! ===")
    }

    @Test
    fun runHeldOutEvaluation() {
        val client = OpenAIClient()
        val gson = Gson()

        val heldOutCases = listOf(
            mapOf(
                "name" to "HELD_OUT_ISM",
                "srPath" to "heldout/ism_sr.json",
                "graphPath" to "C:/Workspace/graph/project-graph-i/project-graph.json"
            ),
            mapOf(
                "name" to "HELD_OUT_APC",
                "srPath" to "heldout/apc_sr.json",
                "graphPath" to "C:/Workspace/graph/project-graph-a/project-graph.json"
            )
        )

        for (hc in heldOutCases) {
            val name = hc["name"] as String
            val srPath = hc["srPath"] as String
            val graphPath = hc["graphPath"] as String

            val srFile = File(srPath)
            if (!srFile.exists()) {
                println("Skipping $name: SR file not found at $srPath")
                continue
            }
            val srData = gson.fromJson(srFile.readText(), Map::class.java)
            val srBody = srData["sr_body"] as String

            println("\n=======================================================")
            println("=== Running Held-out Evaluation for $name ===")
            println("=======================================================")
            println("SR: $srBody")
            println("Graph: $graphPath")

            val graphContent = File(graphPath).readText()
            val graph = gson.fromJson(graphContent, ProjectGraph::class.java)

            val discoveryResult = AdaptiveFileDiscovery.filter(
                primaryReq = srBody,
                secondaryReq = "",
                graph = graph,
                client = client,
                project = null,
                projectBasePath = null,
                enhancedRequirements = emptyList()
            )

            val verifiedFiles = discoveryResult.relevantFiles
            println("\n=== Final Surviving Files for $name (Count: ${verifiedFiles.size}) ===")
            verifiedFiles.forEach { item ->
                println("RESULT_FILE: [$name] ${item.path} (Score: ${item.score} / Reason: ${item.discoveryReason})")
            }
        }
    }

    @Test
    fun traceApcHeldOutPipeline() {
        val client = OpenAIClient()
        val gson = Gson()

        val srFile = File("heldout/apc_sr.json")
        val srData = gson.fromJson(srFile.readText(), Map::class.java)
        val srBody = srData["sr_body"] as String

        val graphPath = "C:/Workspace/graph/project-graph-a/project-graph.json"
        val graph = gson.fromJson(File(graphPath).readText(), ProjectGraph::class.java)

        val gtTargets = listOf(
            "src/main/java/sc/chn/aps/apc/mm/mm05/svc/impl/APCMMSspyLkSVCImpl.java",
            "src/main/java/sc/chn/aps/apc/mm/mm05/svc/svo/APCMMSspyOtcAkSVO.java",
            "src/main/java/sc/chn/aps/apc/mm/mm05/biz/APCMMSspyLkBIZ.java",
            "src/main/java/sc/chn/aps/apc/mm/mm05/svc/APCMMSspyLkSVC.java"
        )

        println("\n================================================================")
        println("   APC HELD-OUT 4-STAGE PIPELINE TRACING INSTRUMENT")
        println("================================================================")
        println("SR: $srBody")

        // -------------------------------------------------------------
        // STAGE 1: AgenticSeedSelector
        // -------------------------------------------------------------
        println("\n--- [STAGE 1] AgenticSeedSelector.selectSeeds ---")
        val selector = net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.AgenticSeedSelector(client)
        val seedResult = selector.selectSeeds(srBody, graph)
        val seedClasses = seedResult.seedClasses
        val judgePicks = seedResult.judgePicks
        val rawCandidates = seedResult.rawCandidates

        println("Raw BM25 Candidates Count: ${rawCandidates.size}")
        rawCandidates.take(15).forEachIndexed { i, c -> println("  Raw Candidate ${i+1}: $c") }

        println("\nJudge Picks Count: ${judgePicks.size}")
        judgePicks.forEachIndexed { i, p -> println("  Judge Pick ${i+1}: $p") }

        println("\nFinal Seed Classes Count: ${seedClasses.size}")
        seedClasses.forEachIndexed { i, s -> println("  Seed ${i+1}: $s") }

        println("\n[GT Stage 1 Seed Status]:")
        gtTargets.forEach { gt ->
            val className = gt.substringAfterLast('/').substringBeforeLast('.')
            val inRaw = rawCandidates.any { it.contains(className) }
            val inJudge = judgePicks.any { it.contains(className) }
            val inFinal = seedClasses.any { it.contains(className) }
            println("  * $className -> In Raw BM25? ${if (inRaw) "✅ YES" else "❌ NO"} | In Judge Picks? ${if (inJudge) "✅ YES" else "❌ NO"} | In Final Seeds? ${if (inFinal) "✅ YES" else "❌ NO"}")
        }

        // -------------------------------------------------------------
        // STAGE 2: GraphExpander
        // -------------------------------------------------------------
        println("\n--- [STAGE 2] GraphExpander.expand ---")
        val domainExtractor = net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DomainExtractor(graph.files)
        val config = net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DiscoveryConfig(maxHop = 3)
        val expander = net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.GraphExpander(graph, domainExtractor, config)
        val expandedFiles: Map<String, net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.ExpansionStep> = expander.expand(seedResult, srBody)
        val expandedPaths = expandedFiles.keys
        println("Expanded Files Count: ${expandedPaths.size}")

        println("\n[GT Stage 2 Graph Expander Status]:")
        gtTargets.forEach { gt ->
            val className = gt.substringAfterLast('/').substringBeforeLast('.')
            val matchingPath = expandedPaths.find { it.contains(className) }
            val step = if (matchingPath != null) expandedFiles[matchingPath] else null
            println("  * $className -> In Expanded? ${if (matchingPath != null) "✅ YES (Hop: ${step?.hop}, via: ${step?.via})" else "❌ NO"}")
        }

        // -------------------------------------------------------------
        // STAGE 3: RelevanceScorer (Stage 3 Reranking & Top 30 Cutoff)
        // -------------------------------------------------------------
        println("\n--- [STAGE 3] RelevanceScorer.scoreAndFilter ---")
        val scorer = net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.RelevanceScorer(graph, fileLimit = 30, minScore = 55)
        val relevantFiles = scorer.scoreAndFilter(srBody, expandedFiles, seedResult).take(30)
        val relevantPaths = relevantFiles.map { it.path }

        println("Relevant Files (Top 30 Count: ${relevantFiles.size}):")
        relevantFiles.forEachIndexed { i, f ->
            println("  ${i+1}위: ${f.path.substringAfterLast('/')} (Score: ${f.score}, Reason: ${f.discoveryReason})")
        }

        println("\n[GT Stage 3 RelevanceScorer Status]:")
        gtTargets.forEach { gt ->
            val className = gt.substringAfterLast('/').substringBeforeLast('.')
            val isRelevant = relevantPaths.any { it.contains(className) }
            val rank = relevantPaths.indexOfFirst { it.contains(className) } + 1
            val f = relevantFiles.find { it.path.contains(className) }
            if (isRelevant) {
                println("  * $className -> Passed Final Top 30? ✅ YES (${rank}위, Score: ${f?.score}, Reason: ${f?.discoveryReason})")
            } else {
                println("  * $className -> Passed Final Top 30? ❌ NO (Cutoff or Dropped)")
            }
        }
    }
}