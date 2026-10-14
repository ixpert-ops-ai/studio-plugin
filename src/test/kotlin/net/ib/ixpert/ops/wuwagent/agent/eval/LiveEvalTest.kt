package net.ib.ixpert.ops.wuwagent.agent.eval

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.agent.RequirementAnalysisPipeline
import net.ib.ixpert.ops.wuwagent.agent.clarify.AnalyzeInputResolver
import net.ib.ixpert.ops.wuwagent.agent.clarify.IdentifierRetentionChecker
import net.ib.ixpert.ops.wuwagent.agent.clarify.Stage0ClarificationEngine
import net.ib.ixpert.ops.wuwagent.agent.clarify.Stage0GraphScanner
import net.ib.ixpert.ops.wuwagent.client.OpenAIClient
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * 실사용 평가 세트(B-39): 고정 시나리오를 실제 LLM(운영 경로 OpenAIClient)으로 돌려 GT Recall, 누락 식별자, 제외 준수, 턴 수,
 * 응답 시간·타임아웃을 기록하고 보고서(md, json 한 쌍)를 쓴다. 판정(단언)은 하지 않는다.
 *
 * 실행: ./gradlew test --tests "net.ib.ixpert.ops.wuwagent.agent.eval.LiveEvalTest" -DrunLiveLlmTests=true -i
 * 선택 옵션: -Deval.graphs=<graphs.json> -Deval.real=<real_scenarios.json> -Deval.only=D1,S2 -Deval.runs=3
 *           -Deval.callTimeoutSec=120 -Deval.out=<출력 폴더, 기본 build/eval-reports>
 */
class LiveEvalTest {

    companion object {
        const val SERVER = "http://vllm.ixpertops.cloud"
        const val MODEL = "Qwen/Qwen3.8-27B-FP8"
    }

    private val graphCache = HashMap<String, ProjectGraph?>()

    private fun loadGraph(key: String): ProjectGraph? = graphCache.getOrPut(key) {
        val path = EvalScenarios.graphPaths()[key] ?: return@getOrPut null
        val file = File(path)
        if (!file.exists()) return@getOrPut null
        Gson().fromJson(file.readText(Charsets.UTF_8), ProjectGraph::class.java).normalizeLegacyCollections()
    }

    private fun norm(p: String) = p.replace('\\', '/')

    private fun pathMatches(actual: String, expected: String): Boolean {
        val a = norm(actual)
        val e = norm(expected)
        return a.equals(e, ignoreCase = true) || a.endsWith("/$e", ignoreCase = true)
    }

    private fun matchesGt(sc: EvalScenario, gt: EvalGt, actualPath: String): Boolean =
        if (sc.matchMode() == "harness-contains") actualPath.contains(gt.name ?: gt.path ?: "", ignoreCase = true)
        else pathMatches(actualPath, gt.path ?: gt.name ?: "")

    private fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    private fun stats(records: List<MeteredLlmClient.CallRecord>): LlmStats = LlmStats(
        calls = records.size,
        ok = records.count { it.outcome == "ok" },
        nullResults = records.count { it.outcome == "null" },
        timeouts = records.count { it.outcome == "timeout" },
        exceptions = records.count { it.outcome == "exception" },
        medianMs = median(records.map { it.ms }),
        maxMs = records.maxOfOrNull { it.ms } ?: 0
    )

    /** Stage 3 검증기 user 내용에서 `## 요구사항` 구간만 자른다(없으면 null). */
    private fun stage3Segment(contents: List<String>): String? {
        val content = contents.firstOrNull() ?: return null
        val marker = "## 요구사항\n"
        val start = content.indexOf(marker)
        if (start < 0) return null
        val from = start + marker.length
        val end = content.indexOf("\n\n## 후보 파일 목록", from)
        return if (end < 0) null else content.substring(from, end)
    }

    private fun emptyStats() = LlmStats(0, 0, 0, 0, 0, 0, 0)

    private fun failedRun(sc: EvalScenario, run: Int, error: String, totalMs: Long, turns: TurnStats?, metered: MeteredLlmClient?): RunResult {
        val c = metered?.records?.filter { it.phase == "clarify" } ?: emptyList()
        val a = metered?.records?.filter { it.phase == "analyze" } ?: emptyList()
        return RunResult(
            scenarioId = sc.id, run = run, ok = false, error = error, totalMs = totalMs,
            rawRefined = null, refinedRequirement = null, expectedIdentifiers = emptyList(), missingBeforeFix = emptyList(),
            subtractedIdentifiers = emptyList(), unresolved = emptyList(), delivered = emptyList(), stage3Calls = 0, stage3Observed = false,
            excludedFiles = emptyList(), expectedExcludedConfirmed = null, excludedInTargets = emptyList(),
            excludedNameInStage3Sections = emptyList(), excludedNameInStage3Body = emptyList(), turns = turns, targets = emptyList(),
            gtTotal = sc.gtOrEmpty().size, gtHit30 = 0, gtHit10 = 0, gtMissing = sc.gtOrEmpty().map { it.label() }, fp30 = 0,
            llmClarify = if (c.isEmpty()) emptyStats() else stats(c), llmAnalyze = if (a.isEmpty()) emptyStats() else stats(a),
            callMs = (c + a).map { it.ms }
        )
    }

    private fun runOnce(sc: EvalScenario, run: Int, graph: ProjectGraph, metered: MeteredLlmClient): RunResult {
        metered.reset()
        val t0 = System.nanoTime()
        var turns: TurnStats? = null
        try {
            metered.phase = "clarify"
            val scanner = Stage0GraphScanner(graph, minSpecificityScore = 1.0, proposalBudget = 10)
            val engine = Stage0ClarificationEngine(scanner, graph, metered)

            var result = engine.initSession(sc.sr)
            var questions = if (result.openQuestion != null) 1 else 0
            var answered = 0
            var processed = 0
            for (stmt in sc.scriptOrEmpty()) {
                val awaiting = result.openQuestion != null
                result = engine.processTurn(result.state, Stage0ClarificationEngine.UserInput(userStatement = stmt))
                processed++
                if (awaiting) answered++
                if (result.openQuestion != null) questions++
            }
            val intent = engine.buildClarifyIntent(result.state)
            val audit = intent.retentionAudit
            turns = TurnStats(
                engineQuestions = questions,
                answeredQuestions = answered,
                utterancesProcessed = processed,
                scriptEndedBeforeQuestion = result.openQuestion != null && !result.isReadyForStage1,
                readyForStage1 = result.isReadyForStage1,
                unresolvedAtEnd = intent.unresolvedItems.size
            )

            val resolved = AnalyzeInputResolver.resolve(rawInput = intent.refinedRequirement, inMemoryIntent = intent)

            metered.phase = "analyze"
            val analysis = kotlinx.coroutines.runBlocking {
                RequirementAnalysisPipeline(metered).analyze(
                    primaryReq = resolved.effectiveRequirement,
                    secondaryReq = "",
                    projectGraph = graph,
                    clarifyIntent = resolved.effectiveIntent,
                    analyzeInput = resolved
                )
            }

            // GT 판정: 하네스 규칙(경로가 이름을 포함)을 유지하고, 합성·실제 그룹은 경로 일치. onlyType이 있으면 그 type만 본다.
            val sorted = analysis.targetFiles.sortedBy { it.order }
            val eligible = sorted.filter { sc.onlyType == null || it.type == sc.onlyType }
            val gt = sc.gtOrEmpty()
            val hit30 = gt.filter { g -> eligible.any { matchesGt(sc, g, it.path) } }
            val top10 = eligible.take(10)
            val hit10 = gt.filter { g -> top10.any { matchesGt(sc, g, it.path) } }
            val targets = sorted.map { t -> TargetRow(t.order, t.path, t.type, gt.any { g -> matchesGt(sc, g, t.path) }) }
            val fp30 = eligible.count { t -> gt.none { g -> matchesGt(sc, g, t.path) } }

            // 누락 식별자가 Stage 3 입력(요구사항 구간)에 실제로 들어갔는지: Stage 3 검증기 호출에서 잘라 확인
            val segment = stage3Segment(metered.stage3UserContents.toList())
            val delivered = ArrayList<DeliveredCheck>()
            for (id in resolved.missingIdentifiers) delivered.add(DeliveredCheck(id, "missing", segment?.let { IdentifierRetentionChecker.containsIdentifier(it, id) }))
            for (u in resolved.unresolvedItems) delivered.add(DeliveredCheck(u.identifier, "unresolved", segment?.let { IdentifierRetentionChecker.containsIdentifier(it, u.identifier) }))

            // 제외 준수: 제외 확정 파일이 최종 목록에 있는지, 제외 파일 이름이 Stage 3 요구사항에 나타나는지(섹션과 본문을 나눠 기록)
            val excluded = resolved.excludedFiles
            val excludedInTargets = sorted.map { it.path }.filter { p -> excluded.any { e -> pathMatches(p, e) } }
            val sectionHeaders = listOf("[미확정(확정 아님) 식별자]", "[원문에 있었으나 정제문에서 빠진 식별자]")
            val firstHeader = segment?.let { s -> sectionHeaders.map { s.indexOf(it) }.filter { it >= 0 }.minOrNull() }
            val body = segment?.let { s -> if (firstHeader == null) s else s.substring(0, firstHeader) }
            val sections = segment?.let { s -> if (firstHeader == null) "" else s.substring(firstHeader) }
            fun names(path: String): List<String> {
                val file = norm(path).substringAfterLast('/')
                return listOf(file, file.substringBeforeLast('.')).distinct()
            }
            val inSections = excluded.filter { e -> sections != null && names(e).any { IdentifierRetentionChecker.containsIdentifier(sections, it) } }
            val inBody = excluded.filter { e -> body != null && names(e).any { IdentifierRetentionChecker.containsIdentifier(body, it) } }
            val expectExcl = sc.expectExcludedOrEmpty()
            val expectConfirmed = if (expectExcl.isEmpty()) null else expectExcl.all { exp -> excluded.any { pathMatches(it, exp) || pathMatches(exp, it) } }

            val clarifyRecords = metered.records.filter { it.phase == "clarify" }
            val analyzeRecords = metered.records.filter { it.phase == "analyze" }
            return RunResult(
                scenarioId = sc.id, run = run, ok = true, error = null,
                totalMs = (System.nanoTime() - t0) / 1_000_000,
                rawRefined = audit?.rawRefinedRequirement, refinedRequirement = intent.refinedRequirement,
                expectedIdentifiers = audit?.expectedIdentifiers ?: emptyList(),
                missingBeforeFix = audit?.missingBeforeFix ?: emptyList(),
                subtractedIdentifiers = resolved.subtractedIdentifiers,
                unresolved = intent.unresolvedItems.map { "${it.identifier} (${it.kind})" },
                delivered = delivered, stage3Calls = metered.stage3UserContents.size, stage3Observed = segment != null,
                excludedFiles = excluded, expectedExcludedConfirmed = expectConfirmed, excludedInTargets = excludedInTargets,
                excludedNameInStage3Sections = inSections, excludedNameInStage3Body = inBody,
                turns = turns, targets = targets,
                gtTotal = gt.size, gtHit30 = hit30.size, gtHit10 = hit10.size,
                gtMissing = gt.filter { it !in hit30 }.map { it.label() }, fp30 = fp30,
                llmClarify = stats(clarifyRecords), llmAnalyze = stats(analyzeRecords),
                callMs = (clarifyRecords + analyzeRecords).map { it.ms }
            )
        } catch (e: Throwable) {
            return failedRun(sc, run, e.javaClass.simpleName + ": " + (e.message ?: "").take(200), (System.nanoTime() - t0) / 1_000_000, turns, metered)
        }
    }

    @Test
    fun runLiveEval() {
        Assume.assumeTrue(
            "Live vLLM 평가는 -DrunLiveLlmTests=true 또는 환경변수 RUN_LIVE_LLM_TESTS=true 명시 시에만 활성화됩니다.",
            System.getProperty("runLiveLlmTests") == "true" || System.getenv("RUN_LIVE_LLM_TESTS") == "true"
        )

        val server = EvalServerCheck.check(SERVER, MODEL)
        println("[EVAL] 서버 확인: ${server.message} (HTTP ${server.httpStatus}, 모델 ${server.listedModels})")
        if (!server.modelListed) {
            println("[EVAL] 평가를 시작하지 않음: ${server.message}")
            Assume.assumeTrue("평가를 시작하지 않음: ${server.message}", false)
            return
        }

        val scenarios = EvalScenarios.loadAll()
        val runs = System.getProperty("eval.runs")?.toIntOrNull() ?: 3
        val callTimeoutSec = System.getProperty("eval.callTimeoutSec")?.toLongOrNull() ?: 120L
        val metered = MeteredLlmClient(OpenAIClient(), callTimeoutSec)

        val summaries = ArrayList<ScenarioSummary>()
        val startedAt = System.currentTimeMillis()
        for (sc in scenarios) {
            val graph = loadGraph(sc.graphKey)
            val results = ArrayList<RunResult>()
            if (graph == null) {
                println("[EVAL] ${sc.id}: 그래프 없음(${sc.graphKey}) - 건너뜀")
                results.add(failedRun(sc, 1, "그래프 파일 없음: ${sc.graphKey}", 0, null, null))
            } else {
                for (run in 1..runs) {
                    println("[EVAL] ${sc.id} run $run/$runs 시작")
                    val r = runOnce(sc, run, graph, metered)
                    println("[EVAL] ${sc.id} run $run: ok=${r.ok} GT ${r.gtHit30}/${r.gtTotal} 총 ${r.totalMs}ms 타임아웃=${r.llmClarify.timeouts + r.llmAnalyze.timeouts}" + (r.error?.let { " 오류=$it" } ?: ""))
                    results.add(r)
                }
            }
            summaries.add(ScenarioSummary(sc, results))
        }
        val outDir = File(System.getProperty("eval.out") ?: "build/eval-reports")
        val written = EvalReport.write(outDir, server, summaries, runs, callTimeoutSec, metered.firstModel, System.currentTimeMillis() - startedAt)
        println("[EVAL] 보고서: ${written.first.absolutePath}")
        println("[EVAL] 보고서(json): ${written.second.absolutePath}")
    }
}
