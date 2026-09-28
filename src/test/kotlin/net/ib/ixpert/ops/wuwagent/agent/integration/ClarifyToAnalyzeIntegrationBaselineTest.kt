package net.ib.ixpert.ops.wuwagent.agent.integration

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import net.ib.ixpert.ops.wuwagent.agent.PipelineE2ETestVllmClient
import net.ib.ixpert.ops.wuwagent.agent.clarify.ClarifyIntentStore
import net.ib.ixpert.ops.wuwagent.agent.clarify.Stage0ClarificationEngine
import net.ib.ixpert.ops.wuwagent.agent.clarify.Stage0GraphScanner
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.AdaptiveFileDiscovery
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ClarifyToAnalyzeIntegrationBaselineTest {

    data class IdentifierCheck(
        val identifier: String,
        val status: String // EXACT, CASE_ALTERED, SUFFIX_DROPPED, DROPPED
    )

    data class PairRunResult(
        val project: String,
        val runIndex: Int,
        val originalSr: String,
        val refinedSr: String,
        val identifierChecks: List<IdentifierCheck>,
        val directGtScore: String,
        val refinedGtScore: String,
        val directTotalCount: Int,
        val refinedTotalCount: Int,
        val directFpCount: Int,
        val refinedFpCount: Int,
        val directSurvivedGt: List<String>,
        val refinedSurvivedGt: List<String>,
        val notes: String = ""
    )

    data class BenchmarkCase(
        val name: String,
        val category: String, // Case 1 (식별자 포함), Case 2 (순수 자연어), Case 3 (실존 무관 식별자 혼합)
        val graphPath: String,
        val sr: String,
        val gt: List<String>,
        val targetIdentifiers: List<String> = emptyList(),
        val knownFalsePositives: List<String> = emptyList(),
        val isSyntheticInput: Boolean = false
    )

    private val surveyAdminKnownFp = listOf(
        "AddressDaoImpl", "AddressDao", "sql_address.xml",
        "login.js", "common.js", "infobank.js", "ips_common.js",
        "authority.manage.js", "company.manage.js", "manager.list.js", "sender.list.js"
    )

    @Test
    fun runBaseline() {
        org.junit.Assume.assumeTrue(
            "Live vLLM 테스트 실행은 -DrunLiveLlmTests=true 명시 시에만 활성화됩니다.",
            System.getProperty("runLiveLlmTests") == "true"
        )

        val client = PipelineE2ETestVllmClient(temperature = 0.1)
        val gson = GsonBuilder().setPrettyPrinting().create()
        val allPairResults = mutableListOf<PairRunResult>()

        val cases = listOf(
            // === Case 1: 식별자 포함 케이스 ===
            BenchmarkCase(
                name = "apc_Ident (Original)",
                category = "Case 1: 식별자 명시",
                graphPath = "C:/Workspace/graph/project-graph-a/project-graph.json",
                sr = "교통카드 발급업체 변경 후, 이전 모바일교통카드 이용회원 체크 및 재발급 안내를 위한 신규서비스 개발 건. 교통카드 구 발급정보 조회 신규서비스 개발 (SAPACMM0802S01 기존서비스 참고). APCMMTrcdIsInfSVO 수정, APCMMTrcdIsSVC.java 신규서비스 추가, aCMBTBAPC024DEM.selTrcdIsInf 참조하여 앱카드회원ID 및 모니모페이회원ID 최근이력 1건 조회",
                gt = listOf("APCMMTrcdIsInfSVO", "APCMMTrcdIsSVC", "APCMMTrcdIsSVCImpl", "APCMMTrcdIsBIZ", "ACMBTBAPC024DEM"),
                targetIdentifiers = listOf("SAPACMM0802S01", "APCMMTrcdIsInfSVO", "APCMMTrcdIsSVC.java", "aCMBTBAPC024DEM.selTrcdIsInf"),
                isSyntheticInput = false
            ),
            BenchmarkCase(
                name = "ISM_Ident (Synthetic)",
                category = "Case 1: 식별자 명시",
                graphPath = "C:/Workspace/graph/project-graph-i/project-graph.json",
                sr = "ECMBTBISM006Mapper, ECMBTBISM006Mapper.xml 기반 케어회원 관리 화면 조회 및 목록 처리",
                gt = listOf("CareMemberMgmtController", "CareMemberMgmtServiceImpl", "ECMBTBISM006Mapper", "ECMBTBISM006Mapper.xml"),
                targetIdentifiers = listOf("ECMBTBISM006Mapper", "ECMBTBISM006Mapper.xml"),
                isSyntheticInput = true
            ),
            BenchmarkCase(
                name = "survey_admin_Ident (Synthetic)",
                category = "Case 1: 식별자 명시",
                graphPath = "C:/Workspace/HC_card_survey_admin/survey_admin/.meta/project-graph.json",
                sr = "SurveyServiceImpl, sql_survey.xml 기반 기존 설문 발송에 브랜드메시지 발송 채널 추가 및 어드민 개발",
                gt = listOf(
                    "SurveyServiceImpl", "SurveyDaoImpl", "SurveyDao", "SurveyDto",
                    "sql_survey.xml", "survey_write.jsp", "survey.write.js",
                    "survey_list.jsp", "survey.list.js",
                    "BrandmessageTemplateBatchJob", "BizgoApiServiceImpl"
                ),
                targetIdentifiers = listOf("SurveyServiceImpl", "sql_survey.xml"),
                knownFalsePositives = surveyAdminKnownFp,
                isSyntheticInput = true
            ),

            // === Case 2: 순수 자연어 케이스 ===
            BenchmarkCase(
                name = "survey_admin_case_b",
                category = "Case 2: 순수 자연어",
                graphPath = "C:/Workspace/HC_card_survey_admin/survey_admin/.meta/project-graph.json",
                sr = "기존 설문 발송에 브랜드메시지 발송 채널 추가 및 어드민 개발",
                gt = listOf(
                    "SurveyServiceImpl", "SurveyDaoImpl", "SurveyDao", "SurveyDto",
                    "sql_survey.xml", "survey_write.jsp", "survey.write.js",
                    "survey_list.jsp", "survey.list.js",
                    "BrandmessageTemplateBatchJob", "BizgoApiServiceImpl"
                ),
                knownFalsePositives = surveyAdminKnownFp,
                isSyntheticInput = false
            ),
            BenchmarkCase(
                name = "member-market",
                category = "Case 2: 순수 자연어",
                graphPath = "C:/Workspace/member-market/.meta/project-graph.json",
                sr = "상품 상세 내 단말기 스펙 조회",
                gt = listOf("ProductController", "ProductResponse", "Product"),
                isSyntheticInput = false
            ),
            BenchmarkCase(
                name = "ISM_Natural",
                category = "Case 2: 순수 자연어",
                graphPath = "C:/Workspace/graph/project-graph-i/project-graph.json",
                sr = "케어회원 관리 화면 조회",
                gt = listOf("CareMemberMgmtController", "CareMemberMgmtServiceImpl", "ECMBTBISM006Mapper", "ECMBTBISM006Mapper.xml"),
                isSyntheticInput = false
            ),

            // === Case 3: 실존하지만 무관한 식별자 혼합 케이스 ===
            BenchmarkCase(
                name = "apc_AddressDaoNoise (Strong Noise)",
                category = "Case 3: 실존 무관 식별자 혼합",
                graphPath = "C:/Workspace/graph/project-graph-a/project-graph.json",
                sr = "AddressDao 및 AddressDaoImpl 연동 검토 포함, 교통카드 발급업체 변경 후 이전 모바일교통카드 이용회원 체크 및 재발급 안내를 위한 신규서비스 개발 건. APCMMTrcdIsInfSVO 수정, APCMMTrcdIsSVC.java 신규서비스 추가, aCMBTBAPC024DEM.selTrcdIsInf 참조하여 최근이력 조회",
                gt = listOf("APCMMTrcdIsInfSVO", "APCMMTrcdIsSVC", "APCMMTrcdIsSVCImpl", "APCMMTrcdIsBIZ", "ACMBTBAPC024DEM"),
                targetIdentifiers = listOf("AddressDao", "AddressDaoImpl", "APCMMTrcdIsInfSVO", "APCMMTrcdIsSVC.java", "aCMBTBAPC024DEM.selTrcdIsInf"),
                isSyntheticInput = true
            )
        )

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

        fun checkIdentifierRetention(target: String, refined: String): IdentifierCheck {
            val baseTarget = target.substringBeforeLast('.').substringBeforeLast('(').substringBeforeLast(';')

            return when {
                refined.contains(target, ignoreCase = false) -> IdentifierCheck(target, "EXACT")
                refined.contains(target, ignoreCase = true) -> IdentifierCheck(target, "CASE_ALTERED")
                refined.contains(baseTarget, ignoreCase = true) -> IdentifierCheck(target, "SUFFIX_DROPPED")
                else -> IdentifierCheck(target, "DROPPED")
            }
        }

        for (bc in cases) {
            val graphFile = File(bc.graphPath)
            if (!graphFile.exists()) {
                println("Skipping ${bc.name}: graph file not found at ${bc.graphPath}")
                continue
            }

            println("\n" + "=".repeat(90))
            println("▶ START BENCHMARK CASE: [${bc.category}] ${bc.name} (Synthetic: ${bc.isSyntheticInput})")
            println("SR: ${bc.sr}")
            println("=".repeat(90))

            val graphContent = graphFile.readText(Charsets.UTF_8)
            val graph = gson.fromJson(graphContent, ProjectGraph::class.java).normalizeLegacyCollections()
            val projectBasePath = if (bc.graphPath.contains("/.meta/")) bc.graphPath.substringBefore("/.meta/") else null

            for (runIdx in 1..3) {
                println("\n>>> [${bc.name}] Run $runIdx / 3 (Temperature = 0.1)")

                // --- (a) Direct Execution (원문 직행) ---
                val directResult = AdaptiveFileDiscovery.filter(
                    primaryReq = bc.sr,
                    secondaryReq = "",
                    graph = graph,
                    client = client,
                    project = null,
                    projectBasePath = projectBasePath
                )
                val directFiles = directResult.relevantFiles
                val directSurvived = bc.gt.filter { g -> directFiles.any { vf -> matchesGt(vf.path, g) || matchesGt(vf.className, g) } }
                val directScore = "${directSurvived.size}/${bc.gt.size}"
                val directFp = if (bc.knownFalsePositives.isNotEmpty()) {
                    directFiles.count { f -> bc.knownFalsePositives.any { fp -> matchesGt(f.path, fp) || matchesGt(f.className, fp) } }
                } else {
                    directFiles.count { f -> bc.gt.none { g -> matchesGt(f.path, g) || matchesGt(f.className, g) } }
                }

                // --- (b) Clarify Refinement + Intent Save/Load ---
                val scanner = Stage0GraphScanner(graph)
                val engine = Stage0ClarificationEngine(scanner, graph, client)
                val session = engine.initSession(bc.sr)
                val turn1 = engine.processUtterance(session.state, "해당 요구사항으로 분석 진행해줘")
                val clarifyIntent = engine.buildClarifyIntent(turn1.state)

                val tempDir = Files.createTempDirectory("clarify_analyze_baseline_test").toFile()
                val savedIntentFile = try {
                    ClarifyIntentStore.saveIntent(tempDir, clarifyIntent, key = "baseline_${bc.name.replace(Regex("[^a-zA-Z0-9]"), "_")}_run$runIdx")
                } catch (e: Exception) {
                    println("Intent save failed: ${e.message}")
                    null
                }

                val loadedIntent = if (savedIntentFile != null) {
                    ClarifyIntentStore.loadIntent(savedIntentFile, graph)
                } else {
                    clarifyIntent
                }

                val refinedSr = loadedIntent.refinedRequirement
                val idChecks = bc.targetIdentifiers.map { id -> checkIdentifierRetention(id, refinedSr) }

                val refinedResult = AdaptiveFileDiscovery.filter(
                    primaryReq = refinedSr,
                    secondaryReq = "",
                    graph = graph,
                    client = client,
                    project = null,
                    projectBasePath = projectBasePath
                )
                val refinedFiles = refinedResult.relevantFiles
                val refinedSurvived = bc.gt.filter { g -> refinedFiles.any { vf -> matchesGt(vf.path, g) || matchesGt(vf.className, g) } }
                val refinedScore = "${refinedSurvived.size}/${bc.gt.size}"
                val refinedFp = if (bc.knownFalsePositives.isNotEmpty()) {
                    refinedFiles.count { f -> bc.knownFalsePositives.any { fp -> matchesGt(f.path, fp) || matchesGt(f.className, fp) } }
                } else {
                    refinedFiles.count { f -> bc.gt.none { g -> matchesGt(f.path, g) || matchesGt(f.className, g) } }
                }

                // Temp 디렉토리 안전 정리
                try { tempDir.deleteRecursively() } catch (_: Exception) {}

                println("  - Refined SR: $refinedSr")
                if (idChecks.isNotEmpty()) {
                    println("  - Identifiers: " + idChecks.joinToString { "${it.identifier}=${it.status}" })
                }
                println("  - (a) Direct  GT Recall: $directScore (${directFiles.size}개 출력, FP: $directFp)")
                println("  - (b) Refined GT Recall: $refinedScore (${refinedFiles.size}개 출력, FP: $refinedFp)")

                allPairResults.add(
                    PairRunResult(
                        project = bc.name,
                        runIndex = runIdx,
                        originalSr = bc.sr,
                        refinedSr = refinedSr,
                        identifierChecks = idChecks,
                        directGtScore = directScore,
                        refinedGtScore = refinedScore,
                        directTotalCount = directFiles.size,
                        refinedTotalCount = refinedFiles.size,
                        directFpCount = directFp,
                        refinedFpCount = refinedFp,
                        directSurvivedGt = directSurvived,
                        refinedSurvivedGt = refinedSurvived,
                        notes = "Category: ${bc.category}"
                    )
                )
            }
        }

        // --- Generate Comprehensive Markdown Report ---
        val report = StringBuilder()
        report.appendLine("# Clarify $\\rightarrow$ Analyze Integration Baseline Report (Pair A/B, 3 Runs, Temp=0.1)")
        report.appendLine("* **하네스 성격**: 단발 정제(Single-turn Refinement) 기반 식별자 보존력 및 Analyze 연계 영향 측정 (constraints는 미포함)")
        report.appendLine("* **측정 일시**: ${java.time.LocalDateTime.now()}")
        report.appendLine()
        report.appendLine("## 1. 종합 비교 실측표 (A: Direct 원문 직행 vs B: Refined 정제 경유)")
        report.appendLine("| Category | Project | Run | (a) Direct Recall | (b) Refined Recall | (a) Cnt | (b) Cnt | (a) FP | (b) FP | Identifier Retention |")
        report.appendLine("|---|---|:---:|:---:|:---:|:---:|:---:|:---:|:---:|---|")

        for (res in allPairResults) {
            val idSummary = if (res.identifierChecks.isEmpty()) "N/A" else res.identifierChecks.joinToString("<br>") { "${it.identifier}: **${it.status}**" }
            report.appendLine("| ${res.notes.substringAfter("Category: ")} | ${res.project} | ${res.runIndex} | ${res.directGtScore} | ${res.refinedGtScore} | ${res.directTotalCount} | ${res.refinedTotalCount} | ${res.directFpCount} | ${res.refinedFpCount} | $idSummary |")
        }

        report.appendLine()
        report.appendLine("## 2. 정제문(refinedRequirement) 원문 3회분 기록 (Case별)")
        for (bc in cases) {
            report.appendLine("### ${bc.name}")
            report.appendLine("* **Original SR**: `${bc.sr}`")
            val caseRuns = allPairResults.filter { it.project == bc.name }
            for (cr in caseRuns) {
                report.appendLine("- **Run ${cr.runIndex} Refined**: `${cr.refinedSr}`")
            }
            report.appendLine()
        }

        val outDir = File("C:/Workspace/DEV-ASSISTANT/IDE-PLUGIN/intelliJ/ai-assistant-plugin")
        File(outDir, "clarify_analyze_baseline_report.md").writeText(report.toString(), Charsets.UTF_8)
        File(outDir, "clarify_analyze_baseline_raw.json").writeText(gson.toJson(allPairResults), Charsets.UTF_8)

        println("\n" + "=".repeat(90))
        println(report.toString())
        println("=".repeat(90))
    }
}
