package net.ib.ixpert.ops.wuwagent.agent.eval

import com.google.gson.Gson
import com.google.gson.JsonParser
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.ChangeIntent
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DiscoveryConfig
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DomainExtractor
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.GraphExpander
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.RelevanceScorer
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.SeedSelectionResult
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * B-47 RelevanceScorer 점수 분석(LLM 호출 없음): 기준 평가의 최종 시드로 운영 GraphExpander(maxHop 3)와 RelevanceScorer(운영은 minScore 55, 30개 상한,
 * AdaptiveFileDiscovery.kt:46)를 그대로 돌려 (1) 후보에서 탈락한 GT의 점수와 구성, (2) minScore 55/50/45/40별 후보·GT 회수·오탐을 계산한다.
 * 실행: ./gradlew test --tests "net.ib.ixpert.ops.wuwagent.agent.eval.ScorerAnalysisTest" -Deval.scorer=true -i
 * 운영의 srText·frontend 힌트를 완전히 복원하지는 못하므로 재현 계산이다(T=55 결과를 실제 Stage 3 후보와 대조해 일치 여부를 보고한다).
 */
class ScorerAnalysisTest {

    private fun norm(p: String) = p.replace('\\', '/')
    private fun base(p: String) = norm(p).substringAfterLast('/')

    @Test
    fun analyze() {
        Assume.assumeTrue("-Deval.scorer=true 명시 시에만 실행", System.getProperty("eval.scorer") == "true")
        val root = File(System.getProperty("eval.out") ?: "build/eval-reports")
        val liveName = System.getProperty("eval.seed.live") ?: "live_eval_20261016_143658.json"
        val dumps = System.getProperty("eval.seed.dumps") ?: "dumps_op"
        val scenarios = Gson().fromJson(javaClass.getResource("/eval/scenarios.json")!!.readText(Charsets.UTF_8), EvalScenarioFile::class.java).scenarios!!.associateBy { it.id }
        val live = JsonParser.parseString(File(root, liveName).readText(Charsets.UTF_8)).asJsonObject
        val calls = live.getAsJsonArray("calls").map { it.asJsonObject }
        val graphs = HashMap<String, ProjectGraph>()
        fun graphOf(key: String) = graphs.getOrPut(key) {
            Gson().fromJson(File(EvalScenarios.graphPaths().getValue(key)).readText(Charsets.UTF_8), ProjectGraph::class.java).normalizeLegacyCollections()
        }
        val ids = System.getProperty("eval.seed.ids")?.split(",")?.map { it.trim() } ?: listOf("D1", "D2", "D3", "D4", "D5", "S1", "S2", "S3")
        val thresholds = listOf(55, 50, 45, 40)
        val sb = StringBuilder()
        val tableA = StringBuilder()
        val tableB = StringBuilder()
        tableA.appendLine("| 시나리오 | 탈락 GT | 총점 | hop(점수) | via | 레이어 정렬 | 키워드·유형 합(총점-hop-레이어, 역산) | 보호 | 통과 후보 최저점(보호 제외) | 55 미만 | 30개 밖 |")
        tableA.appendLine("|---|---|---|---|---|---|---|---|---|---|---|")
        val tableC = StringBuilder()
        tableC.appendLine("| 시나리오 | 55 미만으로 탈락한 1홉 호출자(DEPENDED_BY/USED_BY_TYPE, Controller·Service·BIZ 계열) 수 | 그중 required GT | 그중 GT 밖 | 파일명 |")
        tableC.appendLine("|---|---|---|---|---|")
        tableB.appendLine("| 시나리오 | minScore | 스코어러 후보 수 | 신규 제안 수 | Stage 3 후보 합 | 회수 required | 회수 reference | 새로 들어온 GT 밖 파일 | 55 대비 추가 파일 수 | 30개 이내 |")
        tableB.appendLine("|---|---|---|---|---|---|---|---|---|---|")
        val sumB = linkedMapOf<Int, IntArray>() // T -> [required 회수, 새 GT 밖, 시나리오 중 30 초과 수, 추가 파일 합]
        thresholds.forEach { sumB[it] = IntArray(4) }
        var recoveredAt55 = 0
        for (id in ids) {
            val sc = scenarios.getValue(id)
            val graph = graphOf(sc.graphKey)
            val mine = calls.filter { it.get("scenarioId").asString == id }.sortedBy { it.get("startMs").asLong }
            val seedCalls = mine.filter { it.get("stage").asString.contains("시드") }
            val seeds = JsonParser.parseString(JsonParser.parseString(File(root, "$dumps/${id}_run1_c${seedCalls.last().get("seq").asInt}.resp.json").readText(Charsets.UTF_8)).asJsonObject.get("toolArgs").asString)
                .asJsonObject.getAsJsonArray("seed_classes").map { it.asString }
            val srText = JsonParser.parseString(File(root, "$dumps/${id}_run1_c${seedCalls.first().get("seq").asInt}.req.json").readText(Charsets.UTF_8)).asJsonObject
                .getAsJsonArray("messages").map { it.asJsonObject }.first { it.get("role").asString == "user" }.get("content").asString
                .substringAfter("요구사항(SR):\n").substringBefore("\n\n위 요구사항을 분석하여")
            val s3 = mine.first { it.get("stage").asString.contains("Stage3") }
            val user3 = JsonParser.parseString(File(root, "$dumps/${id}_run1_c${s3.get("seq").asInt}.req.json").readText(Charsets.UTF_8)).asJsonObject
                .getAsJsonArray("messages").map { it.asJsonObject }.first { it.get("role").asString == "user" }.get("content").asString
            val actual = Regex("^- \\[\\d+] (\\S+)", RegexOption.MULTILINE).findAll(user3).map { norm(it.groupValues[1]) }.toList()
            val newFileCount = actual.count { c -> !graph.files.containsKey(c) && graph.resourceNodes.none { it.path == c } }
            fun gtMatch(g: EvalGt, p: String): Boolean =
                if (sc.matchMode() == "harness-contains") norm(p).contains(g.name ?: g.path ?: "", ignoreCase = true)
                else norm(p).equals(norm(g.path ?: ""), ignoreCase = true) || norm(p).endsWith("/" + norm(g.path ?: ""), ignoreCase = true)
            val required = sc.gtOrEmpty().filter { it.countsForRecall() }
            val references = sc.gtOrEmpty().filter { it.isReference() }
            // D5 계열은 안 A 이전 GT를 보는 (다-a) 분류 대상도 함께 본다(분류 보고서와 같은 16개)
            val legacyD5 = if (System.getProperty("eval.seedloss.legacy") == "true" && id == "D5") listOf("AlimtalkChnlDto.java", "AlimtalkTmplDto.java").map { EvalGt(name = it) } else emptyList()
            val frontend = listOf("어드민", "화면", "UI", "프론트", "vue", "jsp", "페이지", "웹", "frontend", "admin").any { srText.contains(it, ignoreCase = it.all { c -> c.isLowerCase() }) }
            val seedResult = SeedSelectionResult(seeds, ChangeIntent.MODIFY, listOf("SERVICE", "BIZ", "PRESENTATION"), frontend, "", judgePicks = seeds, rawCandidates = seeds)
            val expanded = GraphExpander(graph, DomainExtractor(graph.files), DiscoveryConfig(maxHop = 3)).expand(seedResult, srText)

            val results = thresholds.associateWith { t -> RelevanceScorer(graph, fileLimit = 30, minScore = t).scoreAndFilter(srText, expanded, seedResult) }
            val all = RelevanceScorer(graph, fileLimit = Int.MAX_VALUE, minScore = Int.MIN_VALUE).scoreAndFilter(srText, expanded, seedResult)
            val at55 = results.getValue(55)
            val at55Paths = at55.map { norm(it.path) }.toSet()
            val actualInGraph = actual.filter { graph.files.containsKey(it) || graph.resourceNodes.any { r -> r.path == it } }
            val match55 = actualInGraph.count { it in at55Paths }
            println("[SCORER] $id T=55 재현: 실제 후보(그래프에 있는 것) ${actualInGraph.size}개 중 ${match55}개가 재현 결과에 있음, 재현 ${at55.size}개, 신규 제안 $newFileCount")
            tableB.appendLine("| $id | 55 재현 검증 | ${at55.size} | $newFileCount | | 실제 후보 ${actualInGraph.size}개 중 ${match55}개 일치 | | | | |")

            // (A) 탈락 GT의 점수
            val gtAll = required + legacyD5
            val lostGt = gtAll.filter { g -> actual.none { gtMatch(g, it) } }
            val minPass = at55.filter { !it.isProtected }.minByOrNull { it.score }
            val passingSorted = all.filter { it.score >= 55 || it.isProtected }
            for (g in lostGt) {
                val sf = all.firstOrNull { gtMatch(g, norm(it.path)) } ?: continue
                val node = graph.files[sf.path] ?: continue
                val hop = when (sf.hopDistance) { 0 -> 40; 1 -> 30; 2 -> 15; else -> 0 }
                val layer = if (seedResult.layerHint.any { l -> node.layer.name.contains(l, ignoreCase = true) || node.fileType.name.contains(l, ignoreCase = true) }) 15 else 0
                val rest = sf.score - hop - layer
                val posInAll = results.getValue(55).indexOfFirst { norm(it.path) == norm(sf.path) }
                val beyond30 = sf.score >= 55 && posInAll < 0
                tableA.appendLine("| $id | ${base(sf.path)} (${node.fileType.name}) | ${sf.score} | ${sf.hopDistance}홉 $hop | ${sf.discoveryReason} | $layer | $rest | ${sf.protectionReason ?: "-"} | ${minPass?.let { "${it.className} ${it.score}" } ?: "-"} | ${sf.score < 55 && !sf.isProtected} | $beyond30 |")
            }
            // (C) 구조 후보: 55 미만으로 탈락한 1홉 호출자(DEPENDED_BY/USED_BY_TYPE)이면서 상위 계층 파일
            val callerTypes = setOf("CONTROLLER", "REST_CONTROLLER", "SERVICE", "SERVICE_INTERFACE", "BIZ")
            val callers = all.filter { it.hopDistance == 1 && it.discoveryReason in setOf("DEPENDED_BY", "USED_BY_TYPE") && it.score < 55 && !it.isProtected && it.fileType in callerTypes }
            val callersGt = callers.filter { sf -> required.any { gtMatch(it, norm(sf.path)) } }
            tableC.appendLine("| $id | ${callers.size} | ${callersGt.size} | ${callers.size - callersGt.size} | ${callers.joinToString(", ") { it.className + "(" + it.score + ")" }} |")
            // (B) minScore별 시뮬레이션
            for (t in thresholds) {
                val r = results.getValue(t)
                val paths = r.map { norm(it.path) }.toSet()
                val recReq = required.count { g -> paths.any { gtMatch(g, it) } }
                val recRef = references.count { g -> paths.any { gtMatch(g, it) } }
                val added = paths - at55Paths
                val isGt = { p: String -> required.any { gtMatch(it, p) } || references.any { gtMatch(it, p) } }
                val newNonGt = added.count { !isGt(it) }
                val total = r.size + newFileCount
                val a = sumB.getValue(t)
                a[0] += recReq; a[1] += newNonGt; if (total > 30) a[2]++; a[3] += added.size
                if (t == 55) recoveredAt55 += recReq
                tableB.appendLine("| $id | $t | ${r.size} | $newFileCount | $total | ${recReq}/${required.size} | ${recRef}/${references.size} | $newNonGt | ${added.size} | ${if (r.size <= 30) "예" else "아니오"} |")
            }
        }
        sb.appendLine("(A) 후보에서 탈락한 GT의 점수")
        sb.appendLine(tableA.toString())
        sb.appendLine("(B) minScore별 시뮬레이션(운영 GraphExpander·RelevanceScorer 그대로, 30개 상한 유지)")
        sb.appendLine(tableB.toString())
        sb.appendLine("(C) 구조 후보: 55 미만으로 탈락한 1홉 호출자를 보호(Seed Direct Dependency와 같은 방식)한다면")
        sb.appendLine(tableC.toString())
        sb.appendLine("minScore별 합계(시나리오 ${ids.size}개): required 회수 / 새 GT 밖 파일 / 후보 30개 초과 시나리오 / 55 대비 추가 파일")
        for (t in thresholds) sb.appendLine("- $t: ${sumB.getValue(t).let { "${it[0]} / ${it[1]} / ${it[2]} / ${it[3]}" }}")
        val out = File(root, if (System.getProperty("eval.seedloss.legacy") == "true") "scorer_analysis_legacy.md" else "scorer_analysis.md")
        out.writeText(sb.toString(), Charsets.UTF_8)
        println(sb.toString())
    }
}
