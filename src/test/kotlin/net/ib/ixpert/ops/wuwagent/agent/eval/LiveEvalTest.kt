package net.ib.ixpert.ops.wuwagent.agent.eval

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.agent.RequirementAnalysisPipeline
import net.ib.ixpert.ops.wuwagent.agent.RequirementAnalysisResult
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
 * 선택 옵션: -Deval.graphs=<graphs.json> -Deval.real=<real_scenarios.json> -Deval.only=D1,S2 -Deval.runs=1
 *           -Deval.callTimeoutSec=300 -Deval.out=<출력 폴더, 기본 build/eval-reports>
 */
class LiveEvalTest {

    companion object {
        const val SERVER = "http://vllm.ixpertops.cloud"
        const val MODEL = "Qwen/Qwen3.8-27B-FP8"
        /** 대본에 없는 엔진 질문에 한 번만 쓰는 대체 답변 */
        const val FALLBACK_ANSWER = "잘 모르겠어요, 말씀드린 범위로 진행해 주세요"
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

    private fun emptyStats() = LlmStats(0, 0, 0, 0, 0, 0, 0)

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

    private fun runOnce(sc: EvalScenario, run: Int, graph: ProjectGraph, metered: MeteredLlmClient, proxy: UsageProxy): RunResult {
        metered.reset()
        proxy.scenarioId = sc.id
        proxy.run = run
        val t0 = System.nanoTime()
        fun elapsed() = (System.nanoTime() - t0) / 1_000_000
        fun llm(phase: String) = metered.records.filter { it.phase == phase }.let { if (it.isEmpty()) emptyStats() else stats(it) }
        fun callMs() = metered.records.map { it.ms }

        var turns: TurnStats? = null
        var rawRefined: String? = null
        var refined: String? = null
        var expectedIds: List<String> = emptyList()
        var missing: List<String> = emptyList()
        var subtracted: List<String> = emptyList()
        var unresolved: List<String> = emptyList()
        var excluded: List<String> = emptyList()
        var expectConfirmed: Boolean? = null
        val gtAll = sc.gtOrEmpty()

        fun partial(error: String) = RunResult(
            scenarioId = sc.id, run = run, ok = false, error = error, totalMs = elapsed(),
            rawRefined = rawRefined, refinedRequirement = refined, expectedIdentifiers = expectedIds, missingBeforeFix = missing,
            subtractedIdentifiers = subtracted, unresolved = unresolved, delivered = emptyList(), stage3Calls = metered.stage3UserContents.size,
            stage3Observed = false, excludedFiles = excluded, expectedExcludedConfirmed = expectConfirmed, excludedInTargets = emptyList(),
            excludedNameInStage3Sections = emptyList(), excludedNameInStage3Body = emptyList(), turns = turns, targets = emptyList(),
            gtTotal = gtAll.count { it.countsForRecall() }, gtHit30 = 0, gtHit10 = 0,
            gtMissing = gtAll.filter { it.countsForRecall() }.map { it.label() },
            referenceTotal = gtAll.count { it.isReference() }, referenceIn10 = emptyList(), referenceIn30 = emptyList(),
            graphMissingGt = gtAll.filter { !it.isInGraph() }.map { it.label() }, fp30 = 0,
            llmClarify = llm("clarify"), llmAnalyze = llm("analyze"), callMs = callMs()
        )

        try {
            metered.phase = "clarify"
            proxy.phase = "clarify"
            val scanner = Stage0GraphScanner(graph, minSpecificityScore = 1.0, proposalBudget = 10)
            val engine = Stage0ClarificationEngine(scanner, graph, metered)

            var result = engine.initSession(sc.sr)
            val questionTexts = ArrayList<String>()
            result.openQuestion?.let { questionTexts.add(it) }
            var answered = 0
            var processed = 0
            for (stmt in sc.scriptOrEmpty()) {
                val awaiting = result.openQuestion != null
                result = engine.processTurn(result.state, Stage0ClarificationEngine.UserInput(userStatement = stmt))
                processed++
                if (awaiting) answered++
                result.openQuestion?.let { questionTexts.add(it) }
            }
            // 대본이 끝났는데 엔진 질문이 남아 있으면(대본에 없는 질문) 대체 답변을 1회 쓴다. 그래도 남으면 그대로 분석으로 진행한다.
            val scriptEnded = result.openQuestion != null && !result.isReadyForStage1
            var fallbackUsed = 0
            var remainedAfterFallback = false
            if (scriptEnded) {
                result = engine.processTurn(result.state, Stage0ClarificationEngine.UserInput(userStatement = FALLBACK_ANSWER))
                fallbackUsed = 1
                answered++
                result.openQuestion?.let { questionTexts.add(it) }
                remainedAfterFallback = result.openQuestion != null && !result.isReadyForStage1
            }
            val intent = engine.buildClarifyIntent(result.state)
            val audit = intent.retentionAudit
            turns = TurnStats(
                engineQuestions = questionTexts.size,
                answeredQuestions = answered,
                utterancesProcessed = processed,
                scriptEndedBeforeQuestion = scriptEnded,
                readyForStage1 = result.isReadyForStage1,
                unresolvedAtEnd = intent.unresolvedItems.size,
                fallbackAnswersUsed = fallbackUsed,
                questionRemainedAfterFallback = remainedAfterFallback,
                remainingQuestion = if (result.openQuestion != null && !result.isReadyForStage1) result.openQuestion else null,
                engineQuestionTexts = questionTexts
            )

            val resolved = AnalyzeInputResolver.resolve(rawInput = intent.refinedRequirement, inMemoryIntent = intent)
            rawRefined = audit?.rawRefinedRequirement
            refined = intent.refinedRequirement
            expectedIds = audit?.expectedIdentifiers ?: emptyList()
            missing = audit?.missingBeforeFix ?: emptyList()
            subtracted = resolved.subtractedIdentifiers
            unresolved = intent.unresolvedItems.map { "${it.identifier} (${it.kind})" }
            excluded = resolved.excludedFiles
            val expectExcl = sc.expectExcludedOrEmpty()
            expectConfirmed = if (expectExcl.isEmpty()) null else expectExcl.all { exp -> excluded.any { pathMatches(it, exp) || pathMatches(exp, it) } }

            metered.phase = "analyze"
            proxy.phase = "analyze"
            val analysis: RequirementAnalysisResult = try {
                kotlinx.coroutines.runBlocking {
                    RequirementAnalysisPipeline(metered).analyze(
                        primaryReq = resolved.effectiveRequirement,
                        secondaryReq = "",
                        projectGraph = graph,
                        clarifyIntent = resolved.effectiveIntent,
                        analyzeInput = resolved
                    )
                }
            } catch (e: Throwable) {
                // 분석 단계 예외: clarify 결과는 그대로 남긴다.
                return partial("분석 단계 예외: " + e.javaClass.simpleName + ": " + (e.message ?: "").take(200))
            }

            // GT 판정: 하네스 규칙(경로가 이름을 포함)을 유지하고, 합성·실제 그룹은 경로 일치. onlyType이 있으면 그 type만 본다.
            val sorted = analysis.targetFiles.sortedBy { it.order }
            val eligible = sorted.filter { sc.onlyType == null || it.type == sc.onlyType }
            val required = gtAll.filter { it.countsForRecall() }
            val references = gtAll.filter { it.isReference() }
            val top10 = eligible.take(10)
            val hit30 = required.filter { g -> eligible.any { matchesGt(sc, g, it.path) } }
            val hit10 = required.filter { g -> top10.any { matchesGt(sc, g, it.path) } }
            val refIn30 = references.filter { g -> eligible.any { matchesGt(sc, g, it.path) } }
            val refIn10 = references.filter { g -> top10.any { matchesGt(sc, g, it.path) } }
            val targets = sorted.map { t ->
                TargetRow(t.order, t.path, t.type,
                    isGt = required.any { g -> matchesGt(sc, g, t.path) },
                    isReference = references.any { g -> matchesGt(sc, g, t.path) })
            }
            val fp30 = eligible.count { t -> gtAll.none { g -> matchesGt(sc, g, t.path) } }

            // 누락 식별자가 Stage 3 입력(요구사항 구간)에 실제로 들어갔는지: Stage 3 검증기 호출에서 잘라 확인
            val segment = stage3Segment(metered.stage3UserContents.toList())
            val delivered = ArrayList<DeliveredCheck>()
            for (id in resolved.missingIdentifiers) delivered.add(DeliveredCheck(id, "missing", segment?.let { IdentifierRetentionChecker.containsIdentifier(it, id) }))
            for (u in resolved.unresolvedItems) delivered.add(DeliveredCheck(u.identifier, "unresolved", segment?.let { IdentifierRetentionChecker.containsIdentifier(it, u.identifier) }))

            // 제외 준수: 제외 확정 파일이 최종 목록에 있는지, 제외 파일 이름이 Stage 3 요구사항에 나타나는지(섹션과 본문을 나눠 기록)
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

            return RunResult(
                scenarioId = sc.id, run = run, ok = true, error = null,
                totalMs = elapsed(),
                rawRefined = rawRefined, refinedRequirement = refined,
                expectedIdentifiers = expectedIds, missingBeforeFix = missing, subtractedIdentifiers = subtracted, unresolved = unresolved,
                delivered = delivered, stage3Calls = metered.stage3UserContents.size, stage3Observed = segment != null,
                excludedFiles = excluded, expectedExcludedConfirmed = expectConfirmed, excludedInTargets = excludedInTargets,
                excludedNameInStage3Sections = inSections, excludedNameInStage3Body = inBody,
                turns = turns, targets = targets,
                gtTotal = required.size, gtHit30 = hit30.size, gtHit10 = hit10.size,
                gtMissing = required.filter { it !in hit30 }.map { it.label() },
                referenceTotal = references.size, referenceIn10 = refIn10.map { it.label() }, referenceIn30 = refIn30.map { it.label() },
                graphMissingGt = gtAll.filter { !it.isInGraph() }.map { it.label() }, fp30 = fp30,
                llmClarify = llm("clarify"), llmAnalyze = llm("analyze"), callMs = callMs()
            )
        } catch (e: Throwable) {
            return partial("clarify 단계 예외: " + e.javaClass.simpleName + ": " + (e.message ?: "").take(200))
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
        val runs = System.getProperty("eval.runs")?.toIntOrNull() ?: 1
        val callTimeoutSec = System.getProperty("eval.callTimeoutSec")?.toLongOrNull() ?: 300L
        val metered = MeteredLlmClient(OpenAIClient(), callTimeoutSec)

        // 호출마다 usage·TTFB를 서버 응답에서 직접 기록하는 투명 프록시(내용은 바꾸지 않음)
        val proxy = UsageProxy(SERVER)
        proxy.start()
        proxy.install()

        val summaries = ArrayList<ScenarioSummary>()
        val startedAt = System.currentTimeMillis()
        try {
            for (sc in scenarios) {
                val graph = loadGraph(sc.graphKey)
                val results = ArrayList<RunResult>()
                if (graph == null) {
                    println("[EVAL] ${sc.id}: 그래프 없음(${sc.graphKey}) - 건너뜀")
                    proxy.scenarioId = sc.id
                    results.add(RunResult(
                        scenarioId = sc.id, run = 1, ok = false, error = "그래프 파일 없음: ${sc.graphKey}", totalMs = 0,
                        rawRefined = null, refinedRequirement = null, expectedIdentifiers = emptyList(), missingBeforeFix = emptyList(),
                        subtractedIdentifiers = emptyList(), unresolved = emptyList(), delivered = emptyList(), stage3Calls = 0, stage3Observed = false,
                        excludedFiles = emptyList(), expectedExcludedConfirmed = null, excludedInTargets = emptyList(),
                        excludedNameInStage3Sections = emptyList(), excludedNameInStage3Body = emptyList(), turns = null, targets = emptyList(),
                        gtTotal = sc.gtOrEmpty().count { it.countsForRecall() }, gtHit30 = 0, gtHit10 = 0,
                        gtMissing = sc.gtOrEmpty().filter { it.countsForRecall() }.map { it.label() },
                        referenceTotal = sc.gtOrEmpty().count { it.isReference() }, referenceIn10 = emptyList(), referenceIn30 = emptyList(),
                        graphMissingGt = sc.gtOrEmpty().filter { !it.isInGraph() }.map { it.label() }, fp30 = 0,
                        llmClarify = emptyStats(), llmAnalyze = emptyStats(), callMs = emptyList()
                    ))
                } else {
                    for (run in 1..runs) {
                        println("[EVAL] ${sc.id} run $run/$runs 시작")
                        val r = runOnce(sc, run, graph, metered, proxy)
                        println("[EVAL] ${sc.id} run $run: ok=${r.ok} GT ${r.gtHit30}/${r.gtTotal} 총 ${r.totalMs}ms 타임아웃=${r.llmClarify.timeouts + r.llmAnalyze.timeouts}" + (r.error?.let { " 오류=$it" } ?: ""))
                        results.add(r)
                    }
                }
                summaries.add(ScenarioSummary(sc, results))
                if (summaries.size == 1) {
                    // 중단 조건: 첫 시나리오에서 LLM 호출의 절반 이상이 상한 초과(timeout)이면 평가를 멈추고 그 사실만 출력한다.
                    val calls = results.sumOf { it.llmClarify.calls + it.llmAnalyze.calls }
                    val timeouts = results.sumOf { it.llmClarify.timeouts + it.llmAnalyze.timeouts }
                    if (calls > 0 && timeouts * 2 >= calls) {
                        println("[EVAL] 평가를 중단함: 첫 시나리오(${sc.id})에서 호출 ${calls}건 중 ${timeouts}건이 호출 상한(${callTimeoutSec}초)을 넘음")
                        return
                    }
                }
            }
            // 상한으로 포기한 호출의 서버 응답이 끝나기를 잠시 기다려 토큰 수·총 시간을 채운다.
            proxy.awaitIdle(120_000)
        } finally {
            proxy.uninstall()
            proxy.stop()
        }
        val outDir = File(System.getProperty("eval.out") ?: "build/eval-reports")
        val written = EvalReport.write(outDir, server, summaries, runs, callTimeoutSec, metered.firstModel, System.currentTimeMillis() - startedAt, proxy.calls.toList())
        println("[EVAL] 보고서: ${written.first.absolutePath}")
        println("[EVAL] 보고서(json): ${written.second.absolutePath}")
    }
}
