package net.ib.ixpert.ops.wuwagent.agent.eval

import com.google.gson.GsonBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/** 보고서 작성기: 숫자 표(그룹별)와 시나리오별 정제문 원문·상위 파일 목록을 md로, 같은 내용과 상위 30 전체를 json으로 쓴다. */
object EvalReport {

    private fun cell(s: String?): String = (s ?: "").replace("|", "\\|").replace("\n", " ")

    private fun f1(v: Double): String = String.format("%.1f", v)

    private fun pct(v: Double): String = String.format("%.0f%%", v * 100)

    private fun gitHead(): String = try {
        val p = ProcessBuilder("git", "rev-parse", "--short", "HEAD").redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText().trim().ifEmpty { "미확인" }
    } catch (e: Exception) {
        "미확인"
    }

    private fun recalls(runs: List<RunResult>, pick: (RunResult) -> Int): List<Double> =
        runs.filter { it.ok && it.gtTotal > 0 }.map { pick(it).toDouble() / it.gtTotal }

    private fun recallCell(values: List<Double>, draft: Boolean): String {
        if (values.isEmpty()) return "GT 없음"
        val body = "${pct(values.average())} (${pct(values.min())}~${pct(values.max())})"
        return if (draft) "(초안) $body" else body
    }

    private fun scenarioRow(s: ScenarioSummary): String {
        val sc = s.scenario
        val runs = s.runs
        val draft = sc.status() != "confirmed"
        val okRuns = runs.filter { it.ok }
        val allCalls = runs.flatMap { it.callMs }
        val timeouts = runs.sumOf { it.llmClarify.timeouts + it.llmAnalyze.timeouts }
        val badCalls = runs.sumOf { it.llmClarify.nullResults + it.llmClarify.exceptions + it.llmAnalyze.nullResults + it.llmAnalyze.exceptions }
        val medianMs = if (allCalls.isEmpty()) 0 else allCalls.sorted().let { if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }
        val maxMs = allCalls.maxOrNull() ?: 0
        val missingAvg = if (okRuns.isEmpty()) "-" else f1(okRuns.map { it.missingBeforeFix.size }.average())
        val deliveredAll = okRuns.flatMap { it.delivered }
        val deliveredText = if (deliveredAll.isEmpty()) "-" else "${deliveredAll.count { it.inStage3 == true }}/${deliveredAll.size}" + (if (deliveredAll.any { it.inStage3 == null }) " (미관찰 ${deliveredAll.count { it.inStage3 == null }})" else "")
        val excludedText = if (okRuns.isEmpty()) "-" else "확정 ${f1(okRuns.map { it.excludedFiles.size }.average())}개, 위반 ${okRuns.sumOf { it.excludedInTargets.size }}" +
            (okRuns.mapNotNull { it.expectedExcludedConfirmed }.let { if (it.isEmpty()) "" else ", 기대 제외 확정 ${it.count { b -> b }}/${it.size}" })
        val turns = okRuns.mapNotNull { it.turns }
        val turnText = if (turns.isEmpty()) "-" else "질문 ${f1(turns.map { it.engineQuestions }.average())}, 답함 ${f1(turns.map { it.answeredQuestions }.average())}, 대본 먼저 끝남 ${turns.count { it.scriptEndedBeforeQuestion }}/${turns.size}"
        val fp = if (okRuns.isEmpty()) "-" else f1(okRuns.map { it.fp30 }.average())
        val refText = if (okRuns.isEmpty() || okRuns.first().referenceTotal == 0) "-" else {
            val total = okRuns.first().referenceTotal
            "${f1(okRuns.map { it.referenceIn10.size }.average())}/${total} | ${f1(okRuns.map { it.referenceIn30.size }.average())}/${total}"
        }
        return "| ${sc.id} | ${okRuns.size}/${runs.size} | ${recallCell(recalls(runs) { it.gtHit30 }, draft)} | ${recallCell(recalls(runs) { it.gtHit10 }, draft)} | $fp | ${refText} | $missingAvg / $deliveredText | $excludedText | $turnText | ${medianMs} / ${maxMs} | $timeouts / $badCalls |"
    }

    private fun detail(s: ScenarioSummary, sb: StringBuilder) {
        val sc = s.scenario
        sb.appendLine("### ${sc.id} (${sc.group}, 그래프 ${sc.graphKey}, GT ${sc.status()})")
        sb.appendLine()
        sb.appendLine("- SR: ${cell(sc.sr)}")
        sb.appendLine("- 발화 대본: ${if (sc.scriptOrEmpty().isEmpty()) "(없음)" else sc.scriptOrEmpty().joinToString(" / ") { cell(it) }}")
        sc.note?.let { sb.appendLine("- 비고: ${cell(it)}") }
        sb.appendLine()
        for (r in s.runs) {
            sb.appendLine("#### ${sc.id} run ${r.run}: ${if (r.ok) "성공" else "실패"}, 총 ${r.totalMs}ms")
            if (!r.ok) { sb.appendLine("- 오류: ${cell(r.error)}"); sb.appendLine(); continue }
            sb.appendLine("- 정제문 원문(raw): \"${cell(r.rawRefined)}\"")
            sb.appendLine("- refinedRequirement: \"${cell(r.refinedRequirement)}\"")
            sb.appendLine("- expectedIdentifiers: ${r.expectedIdentifiers}")
            sb.appendLine("- missingBeforeFix: ${r.missingBeforeFix}, 차감(2d): ${r.subtractedIdentifiers}, 미확정: ${r.unresolved}")
            sb.appendLine("- Stage 3 요구사항 구간 전달 확인: " + if (r.delivered.isEmpty()) "(대상 없음)" else r.delivered.joinToString(", ") { "${it.identifier}[${it.source}]=${it.inStage3 ?: "미관찰"}" } + " (Stage 3 호출 ${r.stage3Calls}회)")
            sb.appendLine("- 제외 확정: ${r.excludedFiles}, 최종 목록에 포함된 제외 파일: ${r.excludedInTargets}, Stage 3 섹션에 나타난 제외 파일명: ${r.excludedNameInStage3Sections}, 본문에 나타난 제외 파일명(참고): ${r.excludedNameInStage3Body}")
            r.turns?.let { sb.appendLine("- 턴: 엔진 질문 ${it.engineQuestions}회, 답한 질문 ${it.answeredQuestions}회, 처리한 발화 ${it.utterancesProcessed}개, 대본이 질문보다 먼저 끝남=${it.scriptEndedBeforeQuestion}, 정제 완료(isReadyForStage1)=${it.readyForStage1}, 마지막 미확정 ${it.unresolvedAtEnd}개") }
            sb.appendLine("- LLM 호출: clarify ${r.llmClarify}, analyze ${r.llmAnalyze}")
            sb.appendLine("- GT 적중(required, 그래프 내): 상위30 ${r.gtHit30}/${r.gtTotal}, 상위10 ${r.gtHit10}/${r.gtTotal}, 미적중 ${r.gtMissing}, 오탐(GT가 불완전하면 상한) ${r.fp30}")
            if (r.referenceTotal > 0) sb.appendLine("- reference(읽기만 하는 파일): 상위10 ${r.referenceIn10}, 상위30 ${r.referenceIn30}, 전체 ${r.referenceTotal}개")
            if (r.graphMissingGt.isNotEmpty()) sb.appendLine("- 그래프 누락 GT(분모에서 제외): ${r.graphMissingGt}")
            sb.appendLine("- 상위 10 파일(★=GT):")
            r.targets.take(10).forEach { sb.appendLine("  ${it.order}. ${if (it.isGt) "★" else if (it.isReference) "☆" else " "} [${it.type}] ${it.path}") }
            sb.appendLine()
        }
        val needMark = sc.status() != "confirmed" || sc.gtOrEmpty().isEmpty()
        val firstOk = s.runs.firstOrNull { it.ok }
        if (needMark && firstOk != null) {
            sb.appendLine("**GT 채택 표시용 후보(run ${firstOk.run} 기준 상위 30, 채택할 파일에 x 표시):**")
            sb.appendLine()
            sb.appendLine("| 채택 | 순서 | type | 경로 | GT 초안 |")
            sb.appendLine("|---|---|---|---|---|")
            firstOk.targets.take(30).forEach { sb.appendLine("| [ ] | ${it.order} | ${it.type} | ${cell(it.path)} | ${if (it.isGt) "★" else if (it.isReference) "☆" else ""} |") }
            sb.appendLine()
        }
        if (sc.extrasOrEmpty().isNotEmpty()) {
            sb.appendLine("**추가 후보(GT 초안에 넣지 않은 관련 파일):**")
            sc.extrasOrEmpty().forEach { sb.appendLine("- ${cell(it.path)}${if (it.inGraph == false) " (그래프 누락)" else ""}: ${cell(it.reason)}") }
            sb.appendLine()
        }
        if (sc.gtOrEmpty().isNotEmpty() && sc.matchMode() == "path") {
            sb.appendLine("**GT 초안과 근거:**")
            sc.gtOrEmpty().forEach { sb.appendLine("- [${if (it.isReference()) "reference" else "required"}${if (!it.isInGraph()) ", 그래프 누락" else ""}] ${cell(it.path)}: ${cell(it.reason)}") }
            sb.appendLine()
        }
    }

    fun write(
        outDir: File,
        server: ServerCheck,
        summaries: List<ScenarioSummary>,
        runs: Int,
        callTimeoutSec: Long,
        firstModel: String?,
        elapsedMs: Long
    ): Pair<File, File> {
        outDir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val mdFile = File(outDir, "live_eval_$stamp.md")
        val jsonFile = File(outDir, "live_eval_$stamp.json")
        val git = gitHead()
        val groups = listOf("dev" to "개발용 시나리오", "synthetic" to "합성 시나리오", "real" to "실제 업무 SR")

        val sb = StringBuilder()
        sb.appendLine("# 실사용 평가 보고서 ($stamp)")
        sb.appendLine()
        sb.appendLine("- 서버: ${server.server}, 모델: ${server.model}, 온도: 0.1(OpenAIClient 기본값), 응답에 찍힌 모델: ${firstModel ?: "미관찰"}")
        sb.appendLine("- /v1/models: HTTP ${server.httpStatus}, 모델 목록에 있음=${server.modelListed}, 목록=${server.listedModels}")
        sb.appendLine("- 시나리오당 ${runs}회 반복, 호출 상한 ${callTimeoutSec}초, 총 소요 ${elapsedMs / 1000}초, git ${git}")
        sb.appendLine("- 경로: 운영과 같은 OpenAIClient(tool call 포함)를 MeteredLlmClient로 감싸 호출 시간·결과를 기록")
        sb.appendLine("- 판정(단언)은 하지 않는다. 첫 측정은 기준값을 잡는 용도다.")
        sb.appendLine()
        for ((key, title) in groups) {
            val list = summaries.filter { it.scenario.group == key }
            sb.appendLine("## $title")
            sb.appendLine()
            if (list.isEmpty()) { sb.appendLine("(시나리오 없음)"); sb.appendLine(); continue }
            if (list.any { it.scenario.status() != "confirmed" }) sb.appendLine("GT가 초안인 시나리오가 있어 해당 Recall은 확정값이 아니다((초안) 표기).")
            sb.appendLine()
            sb.appendLine("| 시나리오 | 성공 | Recall@30 평균(최소~최대) | Recall@10 | 오탐@30 | reference 상위10 | reference 상위30 | 누락 식별자 평균 / Stage 3 전달 | 제외 | 턴(평균) | 응답 ms 중앙 / 최대 | 타임아웃 / 실패·null |")
            sb.appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|")
            sb.appendLine("(Recall 분모는 required이면서 그래프에 있는 GT만. reference는 상위 N 안에 들었는지만 표시)")
            sb.appendLine()
            list.forEach { sb.appendLine(scenarioRow(it)) }
            sb.appendLine()
        }
        sb.appendLine("## 시나리오별 상세")
        sb.appendLine()
        summaries.forEach { detail(it, sb) }
        mdFile.writeText(sb.toString(), Charsets.UTF_8)

        val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
        val header = mapOf(
            "generatedAt" to stamp, "server" to server, "responseModel" to firstModel, "runsPerScenario" to runs,
            "callTimeoutSec" to callTimeoutSec, "elapsedMs" to elapsedMs, "git" to git
        )
        jsonFile.writeText(gson.toJson(mapOf("header" to header, "scenarios" to summaries)), Charsets.UTF_8)
        return mdFile to jsonFile
    }
}
