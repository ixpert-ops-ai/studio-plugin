package net.ib.ixpert.ops.wuwagent.agent.eval

import com.google.gson.Gson
import com.google.gson.JsonParser
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.ChangeIntent
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DiscoveryConfig
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DomainExtractor
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.GraphExpander
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.SeedSelectionResult
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * B-46 후보 밖 GT 분류(LLM 호출 없음): 기준 평가의 최종 시드로 운영 GraphExpander(maxHop 3, AdaptiveFileDiscovery.kt:46-47)를 그대로 돌려
 * 후보 밖 GT가 (가) 그래프에 없음, (나) 시드에서 3홉 밖, (다) 홉 안인데 후보에 못 듦 중 어디인지 분류한다.
 * 실행: ./gradlew test --tests "net.ib.ixpert.ops.wuwagent.agent.eval.SeedLossClassifyTest" -Deval.seedloss=true -i
 * 운영의 srText·frontend 힌트를 완전히 복원하지는 못하므로 재현 계산이다(srText는 기준 평가의 첫 시드 호출 사용자 메시지에서 추출).
 */
class SeedLossClassifyTest {

    private fun norm(p: String) = p.replace('\\', '/')
    private fun base(p: String) = norm(p).substringAfterLast('/')
    private fun stem(p: String) = base(p).substringBeforeLast('.')

    @Test
    fun classifyOutOfCandidateGt() {
        Assume.assumeTrue("-Deval.seedloss=true 명시 시에만 실행", System.getProperty("eval.seedloss") == "true")
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
        val ids = System.getProperty("eval.seed.ids")?.split(",")?.map { it.trim() } ?: listOf("D1", "D2", "D5", "S2", "S3")
        val sb = StringBuilder()
        sb.appendLine("| 시나리오 | 후보 밖 GT | 분류 | 상세 |")
        sb.appendLine("|---|---|---|---|")
        val counts = linkedMapOf("가" to 0, "나" to 0, "다-a" to 0, "다-b" to 0)
        for (id in ids) {
            val sc = scenarios.getValue(id)
            val graph = graphOf(sc.graphKey)
            val mine = calls.filter { it.get("scenarioId").asString == id }.sortedBy { it.get("startMs").asLong }
            val seedCalls = mine.filter { it.get("stage").asString.contains("시드") }
            val lastSeed = seedCalls.last()
            val firstSeed = seedCalls.first()
            val s3 = mine.first { it.get("stage").asString.contains("Stage3") }
            val seeds = JsonParser.parseString(JsonParser.parseString(File(root, "$dumps/${id}_run1_c${lastSeed.get("seq").asInt}.resp.json").readText(Charsets.UTF_8)).asJsonObject.get("toolArgs").asString)
                .asJsonObject.getAsJsonArray("seed_classes").map { it.asString }
            val srText = JsonParser.parseString(File(root, "$dumps/${id}_run1_c${firstSeed.get("seq").asInt}.req.json").readText(Charsets.UTF_8)).asJsonObject
                .getAsJsonArray("messages").map { it.asJsonObject }.first { it.get("role").asString == "user" }.get("content").asString
                .substringAfter("요구사항(SR):\n").substringBefore("\n\n위 요구사항을 분석하여")
            val user3 = JsonParser.parseString(File(root, "$dumps/${id}_run1_c${s3.get("seq").asInt}.req.json").readText(Charsets.UTF_8)).asJsonObject
                .getAsJsonArray("messages").map { it.asJsonObject }.first { it.get("role").asString == "user" }.get("content").asString
            val cands = Regex("^- \\[\\d+] (\\S+)", RegexOption.MULTILINE).findAll(user3).map { norm(it.groupValues[1]) }.toList()
            fun gtMatch(g: EvalGt, p: String): Boolean =
                if (sc.matchMode() == "harness-contains") norm(p).contains(g.name ?: g.path ?: "", ignoreCase = true)
                else norm(p).equals(norm(g.path ?: ""), ignoreCase = true) || norm(p).endsWith("/" + norm(g.path ?: ""), ignoreCase = true)
            // -Deval.seedloss.legacy=true: D5 GT 안 A 확정 전의 GT 8개 기준(후보 밖 16개 분류)으로 본다
            val legacyD5 = if (System.getProperty("eval.seedloss.legacy") == "true" && id == "D5") listOf("survey_list.jsp", "survey_write.jsp", "survey.list.js", "survey.write.js", "AlimtalkChnlDto.java", "AlimtalkTmplDto.java").map { EvalGt(name = it) } else emptyList()
            val gts = sc.gtOrEmpty().filter { it.countsForRecall() } + legacyD5
            val outs = gts.filter { g -> cands.none { gtMatch(g, it) } }

            val frontend = listOf("어드민", "화면", "UI", "프론트", "vue", "jsp", "페이지", "웹", "frontend", "admin").any { srText.contains(it, ignoreCase = it.all { c -> c.isLowerCase() }) }
            val seedResult = SeedSelectionResult(seeds, ChangeIntent.MODIFY, listOf("SERVICE", "BIZ", "PRESENTATION"), frontend, "", judgePicks = seeds, rawCandidates = seeds)
            val expanded = GraphExpander(graph, DomainExtractor(graph.files), DiscoveryConfig(maxHop = 3)).expand(seedResult, srText)

            // 무방향 BFS 거리(참고용 n홉)
            val adj = HashMap<String, MutableSet<String>>()
            fun add(a: String, b: String) { adj.getOrPut(a) { mutableSetOf() }.add(b); adj.getOrPut(b) { mutableSetOf() }.add(a) }
            graph.relationships.forEach { add(it.source, it.target) }
            graph.files.forEach { (p, f) -> f.dependsOn.forEach { add(p, it) }; f.dependedBy.forEach { add(p, it) } }
            graph.resourceNodes.forEach { r -> r.linkedTo.forEach { add(r.path, it) } }
            val seedPaths = seeds.mapNotNull { sn -> graph.files.keys.firstOrNull { graph.files.getValue(it).className == sn || stem(it) == sn } }
            val dist = HashMap<String, Int>()
            val q = ArrayDeque<String>()
            seedPaths.forEach { dist[it] = 0; q.add(it) }
            while (q.isNotEmpty()) { val c = q.removeFirst(); for (n in adj[c].orEmpty()) if (n !in dist) { dist[n] = dist.getValue(c) + 1; q.add(n) } }

            // 재현 검증: 실제 Stage 3 후보(그래프에 있는 파일) 중 이 재현의 확장 결과에 들어 있는 수
            val graphCands = cands.filter { c -> graph.files.containsKey(c) || graph.resourceNodes.any { it.path == c } }
            val inExp = graphCands.count { expanded.containsKey(it) }
            sb.appendLine("| $id | (재현 검증) | 실제 후보 중 확장 결과에 있음 | ${inExp}/${graphCands.size} (후보 ${cands.size}개 중 그래프에 있는 파일 ${graphCands.size}개) |")
            println("[SEEDLOSS] $id 시드=$seeds 확장 결과 ${expanded.size}개, 후보 ${cands.size}개, 후보 밖 GT ${outs.map { it.label() }}")
            for (g in outs) {
                val nodePaths = graph.files.keys.filter { gtMatch(g, it) } + graph.resourceNodes.map { it.path }.filter { gtMatch(g, it) }
                val label = g.label()
                if (nodePaths.isEmpty()) { counts["가"] = counts.getValue("가") + 1; sb.appendLine("| $id | $label | (가) 그래프에 없음 | |"); continue }
                val bfs = nodePaths.mapNotNull { dist[it] }.minOrNull()
                val step = nodePaths.firstNotNullOfOrNull { expanded[it] }
                val reachedPath = nodePaths.firstOrNull { expanded.containsKey(it) }
                when {
                    step != null -> { counts["다-a"] = counts.getValue("다-a") + 1; sb.appendLine("| $id | $label | (다-a) 확장기는 도달, 후보에서 탈락 | 확장 hop ${step.hop} via ${step.via}(from ${step.from?.let { base(it) }}), 시드에서 BFS ${bfs ?: "불가"}홉, 이후 점수·상한(minScore 55, 30개)에서 빠짐 |") }
                    bfs != null && bfs <= 3 -> { counts["다-b"] = counts.getValue("다-b") + 1; sb.appendLine("| $id | $label | (다-b) 3홉 안인데 확장기가 도달하지 못함 | 시드에서 BFS ${bfs}홉이나 확장기 규칙(조건부 확장·도메인 필터)으로 미도달 |") }
                    else -> { counts["나"] = counts.getValue("나") + 1; sb.appendLine("| $id | $label | (나) 시드에서 3홉 밖 | 시드에서 BFS ${bfs ?: "도달 불가"}${if (bfs != null) "홉" else ""} |") }
                }
                if (reachedPath != null && step == null) println("[SEEDLOSS] 이상: $label")
            }
        }
        sb.appendLine()
        sb.appendLine("합계: (가) ${counts["가"]}, (나) ${counts["나"]}, (다-a) ${counts["다-a"]}, (다-b) ${counts["다-b"]}, 전체 ${counts.values.sum()}")
        File(root, if (System.getProperty("eval.seedloss.legacy") == "true") "seed_loss_classify_legacy.md" else "seed_loss_classify.md").writeText(sb.toString(), Charsets.UTF_8)
        println("[SEEDLOSS] 보고서: ${File(root, "seed_loss_classify.md").absolutePath}")
        println(sb.toString())
    }
}
