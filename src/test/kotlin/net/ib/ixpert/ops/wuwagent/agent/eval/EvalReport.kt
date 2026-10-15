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
        if (values.isEmpty()) return "GT 없음/측정 불가"
        val body = "${pct(values.average())} (${pct(values.min())}~${pct(values.max())})"
        return if (draft) "(초안) $body" else body
    }

    private fun sec(ms: Long?): String = if (ms == null) "-" else String.format("%.1f", ms / 1000.0)

    private fun tokS(tokens: Int?, ms: Long?): String =
        if (tokens == null || ms == null || ms <= 0) "-" else String.format("%.1f", tokens * 1000.0 / ms)

    private fun scenarioRow(s: ScenarioSummary): String {
        val sc = s.scenario
        val runs = s.runs
        val draft = sc.status() != "confirmed"
        val okRuns = runs.filter { it.ok }
        val clarifyRuns = runs.filter { it.turns != null }   // 분석이 실패해도 clarify 결과는 남아 있다
        val allCalls = runs.flatMap { it.callMs }
        val timeouts = runs.sumOf { it.llmClarify.timeouts + it.llmAnalyze.timeouts }
        val badCalls = runs.sumOf { it.llmClarify.nullResults + it.llmClarify.exceptions + it.llmAnalyze.nullResults + it.llmAnalyze.exceptions }
        val medianMs = if (allCalls.isEmpty()) 0 else allCalls.sorted().let { if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }
        val maxMs = allCalls.maxOrNull() ?: 0
        val missingAvg = if (clarifyRuns.isEmpty()) "-" else f1(clarifyRuns.map { it.missingBeforeFix.size }.average())
        val deliveredAll = okRuns.flatMap { it.delivered }
        val deliveredText = if (deliveredAll.isEmpty()) "-" else "${deliveredAll.count { it.inStage3 == true }}/${deliveredAll.size}" + (if (deliveredAll.any { it.inStage3 == null }) " (미관찰 ${deliveredAll.count { it.inStage3 == null }})" else "")
        val excludedText = if (clarifyRuns.isEmpty()) "-" else "확정 ${f1(clarifyRuns.map { it.excludedFiles.size }.average())}개, 위반 ${okRuns.sumOf { it.excludedInTargets.size }}" +
            (clarifyRuns.mapNotNull { it.expectedExcludedConfirmed }.let { if (it.isEmpty()) "" else ", 기대 제외 확정 ${it.count { b -> b }}/${it.size}" })
        val turns = clarifyRuns.mapNotNull { it.turns }
        val turnText = if (turns.isEmpty()) "-" else "질문 ${f1(turns.map { it.engineQuestions }.average())}, 답함 ${f1(turns.map { it.answeredQuestions }.average())}, 대본 먼저 끝남 ${turns.count { it.scriptEndedBeforeQuestion }}/${turns.size}, 대체 답변 ${turns.sumOf { it.fallbackAnswersUsed }}회, 규칙 답변 ${turns.sumOf { it.ruleAnswersUsed }}회(그래도 질문 남음 ${turns.count { it.questionRemainedAfterFallback }})"
        val fp = if (okRuns.isEmpty()) "-" else f1(okRuns.map { it.fp30 }.average())
        val refText = if (okRuns.isEmpty() || okRuns.first().referenceTotal == 0) "-" else {
            val total = okRuns.first().referenceTotal
            "${f1(okRuns.map { it.referenceIn10.size }.average())}/${total} | ${f1(okRuns.map { it.referenceIn30.size }.average())}/${total}"
        }
        return "| ${sc.id} | ${okRuns.size}/${runs.size} | ${recallCell(recalls(runs) { it.gtHit30 }, draft)} | ${recallCell(recalls(runs) { it.gtHit10 }, draft)} | $fp | ${refText} | $missingAvg / $deliveredText | $excludedText | $turnText | ${medianMs} / ${maxMs} | $timeouts / $badCalls |"
    }

    private fun callRow(c: ProxyCall, origin: Long): String {
        val total = c.totalMs
        val decodeMs = if (c.stream && total != null && c.ttfbMs != null) total - c.ttfbMs!! else total
        val approx = if (c.completionTokens == null && c.sseChunks != null) "~${c.sseChunks}청크(r${c.reasoningChunks}/c${c.contentChunks}/t${c.toolArgChunks})" else ""
        return "| ${c.seq} | ${c.phase} | ${cell(c.stage)} | ${sec(c.startMs - origin)} | ${sec(c.ttfbMs)} | ${sec(total)}${if (!c.completed) " (미완료)" else ""}${if (c.clientGone) " (호출자 포기)" else ""} | " +
            "${c.promptTokens ?: "미제공"} | ${c.completionTokens ?: ("미제공" + if (approx.isNotEmpty()) " $approx" else "")} | ${c.finishReason ?: "-"} | ${if (c.stream) "스트림" else "일반"}${if (c.hasTools) "+tool" else ""} | ${c.maxTokens ?: "-"} | ${tokS(c.completionTokens, decodeMs)} |"
    }

    private fun lastAnalyzeCallTable(summaries: List<ScenarioSummary>, calls: List<ProxyCall>, sb: StringBuilder) {
        sb.appendLine("## 분석 마지막 호출(시나리오별 1줄)")
        sb.appendLine()
        sb.appendLine("| 시나리오 | run | 단계 | prompt 토큰 | completion 토큰 | 출력 구성(청크: reasoning / content / tool args) | finish_reason | TTFB(초) | 총(초) | 디코드 속도(tok/s) | 스트림 | max_tokens | 비고 |")
        sb.appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|---|")
        for (s in summaries) for (r in s.runs) {
            val last = calls.filter { it.scenarioId == s.scenario.id && it.run == r.run && it.phase == "analyze" }.maxByOrNull { it.seq }
            if (last == null) { sb.appendLine("| ${s.scenario.id} | ${r.run} | (분석 호출 없음) | | | | | | | | | | |"); continue }
            val decodeMs = if (last.stream && last.totalMs != null && last.ttfbMs != null) last.totalMs!! - last.ttfbMs!! else last.totalMs
            val note = (if (!last.completed) "서버 응답 미완료. " else "") + (if (last.clientGone) "호출자가 상한으로 포기. " else "") + last.usageNote
            sb.appendLine("| ${s.scenario.id} | ${r.run} | ${cell(last.stage)} | ${last.promptTokens ?: "미제공"} | ${last.completionTokens ?: "미제공"} | ${last.reasoningChunks ?: "-"} / ${last.contentChunks ?: "-"} / ${last.toolArgChunks ?: "-"} | ${last.finishReason ?: "-"} | ${sec(last.ttfbMs)} | ${sec(last.totalMs)} | ${tokS(last.completionTokens, decodeMs)} | ${if (last.stream) "스트림" else "일반"} | ${last.maxTokens ?: "-"} | ${cell(note)} |")
        }
        sb.appendLine()
    }

    private fun detail(s: ScenarioSummary, calls: List<ProxyCall>, sb: StringBuilder) {
        val sc = s.scenario
        sb.appendLine("### ${sc.id} (${sc.group}, 그래프 ${sc.graphKey}, GT ${sc.status()})")
        sb.appendLine()
        sb.appendLine("- SR: ${cell(sc.sr)}")
        sb.appendLine("- 발화 대본: ${if (sc.scriptOrEmpty().isEmpty()) "(없음)" else sc.scriptOrEmpty().joinToString(" / ") { cell(it) }}")
        sc.note?.let { sb.appendLine("- 비고: ${cell(it)}") }
        sb.appendLine()
        for (r in s.runs) {
            sb.appendLine("#### ${sc.id} run ${r.run}: ${if (r.ok) "성공" else "실패"}, 총 ${r.totalMs}ms")
            if (!r.ok) sb.appendLine("- 오류: ${cell(r.error)}")
            if (r.rawRefined != null || r.refinedRequirement != null) {
                sb.appendLine("- 정제문 원문(raw): \"${cell(r.rawRefined)}\"")
                sb.appendLine("- refinedRequirement: \"${cell(r.refinedRequirement)}\"")
                sb.appendLine("- expectedIdentifiers: ${r.expectedIdentifiers}")
                sb.appendLine("- missingBeforeFix: ${r.missingBeforeFix}, 차감(2d): ${r.subtractedIdentifiers}, 미확정: ${r.unresolved}")
            }
            if (r.ok) {
                sb.appendLine("- Stage 3 요구사항 구간 전달 확인: " + if (r.delivered.isEmpty()) "(대상 없음)" else r.delivered.joinToString(", ") { "${it.identifier}[${it.source}]=${it.inStage3 ?: "미관찰"}" } + " (Stage 3 호출 ${r.stage3Calls}회)")
            }
            if (r.turns != null) sb.appendLine("- 제외 확정: ${r.excludedFiles}" + if (r.ok) ", 최종 목록에 포함된 제외 파일: ${r.excludedInTargets}, Stage 3 섹션에 나타난 제외 파일명: ${r.excludedNameInStage3Sections}, 본문에 나타난 제외 파일명(참고): ${r.excludedNameInStage3Body}" else " (분석 실패로 최종 목록 비교 불가)")
            r.turns?.let {
                sb.appendLine("- 턴: 엔진 질문 ${it.engineQuestions}회, 답한 질문 ${it.answeredQuestions}회, 처리한 발화 ${it.utterancesProcessed}개, 대본이 질문보다 먼저 끝남=${it.scriptEndedBeforeQuestion}, 대체 답변 ${it.fallbackAnswersUsed}회, 규칙 답변 ${it.ruleAnswersUsed}회(${it.ruleAnswerText ?: "-"}), 답변 뒤에도 질문 남음=${it.questionRemainedAfterFallback}, 정제 완료(isReadyForStage1)=${it.readyForStage1}, 마지막 미확정 ${it.unresolvedAtEnd}개")
                sb.appendLine("- 엔진 질문 원문: ${if (it.engineQuestionTexts.isEmpty()) "(없음)" else it.engineQuestionTexts.joinToString(" / ") { q -> "\"${cell(q)}\"" }}")
                it.remainingQuestion?.let { q -> sb.appendLine("- 분석으로 넘어갈 때 남은 질문: \"${cell(q)}\"") }
                it.utterances.forEachIndexed { i, u ->
                    sb.appendLine("- 발화 ${i + 1} \"${cell(u.statement)}\" -> 화면 안내: \"${cell(u.echo)}\", 다음 질문: \"${cell(u.openQuestion)}\", 거부 항목 ${u.rejectedBefore}->${u.rejectedAfter}, 시드 토큰 ${u.seedBefore}->${u.seedAfter}, 분석 시작 선언=${u.ready}")
                }
            }
            sb.appendLine("- LLM 호출: clarify ${r.llmClarify}, analyze ${r.llmAnalyze}")
            if (r.ok) {
                sb.appendLine("- GT 적중(required, 그래프 내): 상위30 ${r.gtHit30}/${r.gtTotal}, 상위10 ${r.gtHit10}/${r.gtTotal}, 미적중 ${r.gtMissing}, 오탐(GT가 불완전하면 상한) ${r.fp30}")
                if (r.referenceTotal > 0) sb.appendLine("- reference(읽기만 하는 파일): 상위10 ${r.referenceIn10}, 상위30 ${r.referenceIn30}, 전체 ${r.referenceTotal}개")
                if (r.graphMissingGt.isNotEmpty()) sb.appendLine("- 그래프 누락 GT(분모에서 제외): ${r.graphMissingGt}")
                sb.appendLine("- 상위 10 파일(★=required GT, ☆=reference):")
                r.targets.take(10).forEach { sb.appendLine("  ${it.order}. ${if (it.isGt) "★" else if (it.isReference) "☆" else " "} [${it.type}] ${it.path}") }
            }
            val mine = calls.filter { it.scenarioId == sc.id && it.run == r.run }.sortedBy { it.seq }
            if (mine.isNotEmpty()) {
                val origin = mine.first().startMs
                sb.appendLine()
                sb.appendLine("호출별 기록(서버 응답 기준, 시작 시각은 run 첫 호출 기준 초):")
                sb.appendLine()
                sb.appendLine("| # | 단계 | 호출 단계 이름 | 시작(초) | TTFB(초) | 총(초) | prompt 토큰 | completion 토큰 | finish_reason | 방식 | max_tokens | 디코드 tok/s |")
                sb.appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|")
                mine.forEach { sb.appendLine(callRow(it, origin)) }
            }
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
        elapsedMs: Long,
        proxyCalls: List<ProxyCall>
    ): Pair<File, File> {
        outDir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val mdFile = File(outDir, "live_eval_$stamp.md")
        val jsonFile = File(outDir, "live_eval_$stamp.json")
        val git = gitHead()
        val groups = listOf("dev" to "개발용 시나리오", "synthetic" to "합성 시나리오", "real" to "실제 업무 SR", "probe" to "확인용(기준선 아님)")
        val calls = proxyCalls.sortedBy { it.seq }
        val allTurns = summaries.flatMap { it.runs }.mapNotNull { it.turns }

        val sb = StringBuilder()
        sb.appendLine("# 실사용 평가 보고서 ($stamp)")
        sb.appendLine()
        sb.appendLine("- 서버: ${server.server}, 모델: ${server.model}, 온도: 0.1(OpenAIClient 기본값), 응답에 찍힌 모델: ${firstModel ?: "미관찰"}")
        sb.appendLine("- /v1/models: HTTP ${server.httpStatus}, 모델 목록에 있음=${server.modelListed}, 목록=${server.listedModels}")
        sb.appendLine("- 시나리오당 ${runs}회 반복, 호출 상한 ${callTimeoutSec}초, 총 소요 ${elapsedMs / 1000}초, git ${git}")
        sb.appendLine("- 경로: 운영과 같은 OpenAIClient(tool call 포함)를 MeteredLlmClient로 감싸 호출 시간·결과를 기록. 토큰 수·TTFB는 내용을 바꾸지 않는 로컬 투명 프록시가 서버 응답에서 직접 기록(응답에 usage가 없으면 '미제공')")
        sb.appendLine("- 대체 답변 \"${LiveEvalTest.FALLBACK_ANSWER}\": 대본이 끝난 뒤 남은 엔진 질문에 1회 사용. 사용 ${allTurns.sumOf { it.fallbackAnswersUsed }}회 / 실행 ${allTurns.size}회, 대체 답변 뒤에도 질문이 남은 실행 ${allTurns.count { it.questionRemainedAfterFallback }}회")
        sb.appendLine("- 대화 경로: UI와 같은 순서. 대본 발화는 processUtterance(App.tsx:295 '/clarify-utterance'), 마지막은 processTurn(userStatement=null, 확정 선언)(App.tsx:312-316 '/analyze-confirm'). 체크박스 거부(/analyze-confirm의 verdict 변경)는 평가 도구에서 쓰지 않는다.")
        sb.appendLine("- 판정(단언)은 하지 않는다. 이 보고서는 기준값을 잡는 용도다.")
        sb.appendLine()
        for ((key, title) in groups) {
            val list = summaries.filter { it.scenario.group == key }
            sb.appendLine("## $title")
            sb.appendLine()
            if (list.isEmpty()) { sb.appendLine("(시나리오 없음)"); sb.appendLine(); continue }
            if (list.any { it.scenario.status() != "confirmed" }) sb.appendLine("GT가 초안인 시나리오가 있어 해당 Recall은 확정값이 아니다((초안) 표기).")
            sb.appendLine("(Recall 분모는 required이면서 그래프에 있는 GT만. reference는 상위 N 안에 들었는지만 표시. 분석이 실패한 실행도 clarify 결과(누락·제외·턴)는 집계에 포함)")
            sb.appendLine()
            sb.appendLine("| 시나리오 | 성공 | Recall@30 평균(최소~최대) | Recall@10 | 오탐@30 | reference 상위10 | reference 상위30 | 누락 식별자 평균 / Stage 3 전달 | 제외 | 턴(평균) | 응답 ms 중앙 / 최대 | 타임아웃 / 실패·null |")
            sb.appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|")
            list.forEach { sb.appendLine(scenarioRow(it)) }
            sb.appendLine()
        }
        lastAnalyzeCallTable(summaries, calls, sb)
        sb.appendLine("## 시나리오별 상세")
        sb.appendLine()
        summaries.forEach { detail(it, calls, sb) }
        mdFile.writeText(sb.toString(), Charsets.UTF_8)

        val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
        val header = mapOf(
            "generatedAt" to stamp, "server" to server, "responseModel" to firstModel, "runsPerScenario" to runs,
            "callTimeoutSec" to callTimeoutSec, "elapsedMs" to elapsedMs, "git" to git,
            "fallbackAnswer" to LiveEvalTest.FALLBACK_ANSWER, "fallbackUsed" to allTurns.sumOf { it.fallbackAnswersUsed }
        )
        jsonFile.writeText(gson.toJson(mapOf("header" to header, "scenarios" to summaries, "calls" to calls)), Charsets.UTF_8)
        return mdFile to jsonFile
    }
}
