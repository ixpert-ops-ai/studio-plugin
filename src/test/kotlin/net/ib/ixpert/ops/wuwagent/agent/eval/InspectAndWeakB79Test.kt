package net.ib.ixpert.ops.wuwagent.agent.eval

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.AgenticGraphTools
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.AgenticSeedSelector
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DiscoveryConfig
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DomainExtractor
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.GraphExpander
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.RelevanceScorer
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.SeedSelectionResult
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Assume
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/**
 * B-79 8(읽기 전용 실험, 운영 반영 없음, LLM 없음). 시나리오는 SR/그래프 15쌍(기존 24개 시나리오의 중복 제거), 후보는 대체 시드의 Stage 1 상위 30개다.
 * a) 탐색기가 inspect한 노드를 구제(= Judge Pick으로 보호)하면 후보와 필수 적중이 얼마나 느는가. 탐색기의 실제 inspect 선택은 시나리오별로 기록이 없어
 *    결정적인 검색 도구(AgenticGraphTools.searchGraphNodes, SR 원문을 질의로)의 시드 아닌 상위 3개를 대리값으로 쓴다(한계: LLM이 고르는 노드와 다를 수 있다).
 * b) 남은 동사 번역(약한 일치)이 후보에 점수를 준 노드를 토큰별로 집계하고 그 노드가 정답인지 본다(RelevanceScorer가 출력하는 [NAMEMATCH-DIAG] 줄을 읽는다).
 * 결과: build/b79_inspect_rescue.txt, build/b79_weak_tokens.txt.
 */
class InspectAndWeakB79Test {

    private fun norm(p: String) = p.replace('\\', '/')
    private fun gtMatch(sc: EvalScenario, g: EvalGt, path: String): Boolean =
        if (sc.matchMode() == "harness-contains") path.contains(g.name ?: g.path ?: "", ignoreCase = true)
        else norm(path).equals(norm(g.path ?: ""), ignoreCase = true) || norm(path).endsWith("/" + norm(g.path ?: ""), ignoreCase = true)

    private fun setup(): Triple<List<EvalScenario>, Map<String, String>, HashMap<String, ProjectGraph>> {
        val scenarios = EvalScenarios.loadAll()
        val paths = EvalScenarios.graphPaths()
        Assume.assumeTrue(paths.values.all { File(it).isFile })
        return Triple(scenarios, paths, HashMap())
    }

    private fun candidates(g: ProjectGraph, sr: String, seed: SeedSelectionResult): List<String> {
        val expanded = GraphExpander(g, DomainExtractor(g.files), DiscoveryConfig(maxHop = 3)).expand(seed, sr)
        return RelevanceScorer(g, fileLimit = 30, minScore = 55).scoreAndFilter(sr, expanded, seed).take(30).map { it.path }
    }

    @Test
    fun r1_inspect한_노드를_구제하면_후보와_필수_적중이_얼마나_느는가() {
        val (scenarios, paths, graphs) = setup()
        fun graphOf(key: String) = graphs.getOrPut(key) { Gson().fromJson(File(paths.getValue(key)).readText(Charsets.UTF_8), ProjectGraph::class.java).normalizeLegacyCollections() }
        val out = StringBuilder("시나리오|그래프|필수 정답 수|구제한 노드(대리값)|후보 수(전/후)|필수 적중(전/후)|늘어난 정답\n")
        var candGain = 0
        var hitGain = 0
        var n = 0
        val seen = HashSet<String>()
        for (sc in scenarios) {
            if (!seen.add(sc.graphKey + "|" + sc.sr)) continue
            val gts = sc.gtOrEmpty().filter { it.countsForRecall() }
            if (gts.isEmpty()) continue
            val g = graphOf(sc.graphKey)
            val seed = AgenticSeedSelector.heuristicSeeds(sc.sr, g)
            val inspected = AgenticGraphTools(g).searchGraphNodes(sc.sr, null, 10).mapNotNull { it["className"]?.toString() }
                .filter { c -> c !in seed.seedClasses }.distinct().take(3)
            val before = candidates(g, sc.sr, seed)
            val after = candidates(g, sc.sr, seed.copy(judgePicks = (seed.judgePicks + inspected).distinct()))
            fun hits(c: List<String>) = gts.filter { gt -> c.any { gtMatch(sc, gt, it) } }
            val hb = hits(before); val ha = hits(after)
            val gained = ha.filter { it !in hb }
            candGain += after.size - before.size
            hitGain += gained.size
            n++
            out.appendLine("${sc.id}|${sc.graphKey}|${gts.size}|$inspected|${before.size}/${after.size}|${hb.size}/${ha.size}|${gained.map { it.path ?: it.name }}")
        }
        out.appendLine("합계|SR/그래프 ${n}쌍|후보 수 합계 +$candGain (시나리오당 평균 +${"%.1f".format(candGain.toDouble() / n)})|필수 적중 합계 +$hitGain")
        File("build/b79_inspect_rescue.txt").writeText(out.toString(), Charsets.UTF_8)
        println(out)
    }

    @Test
    fun r2_남은_동사_번역의_약한_일치가_점수를_준_노드를_토큰별로_센다() {
        val (scenarios, paths, graphs) = setup()
        fun graphOf(key: String) = graphs.getOrPut(key) { Gson().fromJson(File(paths.getValue(key)).readText(Charsets.UTF_8), ProjectGraph::class.java).normalizeLegacyCollections() }
        val diag = Regex("\\[NAMEMATCH-DIAG] File: (\\S+) \\| Direct: \\[[^]]*], Trans: \\[[^]]*], Weak: \\[([^]]+)] \\| Score: ([0-9.]+)")
        val out = StringBuilder("시나리오|약한 일치 노드(토큰)|후보에 남음|정답\n")
        val perToken = HashMap<String, IntArray>() // 토큰 -> [노드 수, 후보에 남은 수, 그중 정답 수]
        val seen = HashSet<String>()
        val realOut = System.out
        try {
            for (sc in scenarios) {
                if (!seen.add(sc.graphKey + "|" + sc.sr)) continue
                val gts = sc.gtOrEmpty().filter { it.countsForRecall() }
                if (gts.isEmpty()) continue
                val g = graphOf(sc.graphKey)
                val seed = AgenticSeedSelector.heuristicSeeds(sc.sr, g)
                val buf = ByteArrayOutputStream()
                System.setOut(PrintStream(buf, true, "UTF-8"))
                val cands = candidates(g, sc.sr, seed)
                System.setOut(realOut)
                val classNames = cands.map { it.substringAfterLast('/').substringBeforeLast('.') }.toSet()
                val weakNodes = diag.findAll(buf.toString("UTF-8")).map { Triple(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }.distinct().toList()
                for ((cls, tokens, _) in weakNodes) {
                    val inCand = cls in classNames
                    val isGt = inCand && gts.any { gt -> cands.any { gtMatch(sc, gt, it) && it.substringAfterLast('/').substringBeforeLast('.') == cls } }
                    for (t in tokens.split(",").map { it.trim() }) {
                        val a = perToken.getOrPut(t) { IntArray(3) }
                        a[0]++; if (inCand) a[1]++; if (isGt) a[2]++
                    }
                    out.appendLine("${sc.id}|$cls($tokens)|$inCand|$isGt")
                }
            }
        } finally {
            System.setOut(realOut)
        }
        out.appendLine("\n토큰|약한 일치 노드 수|후보에 남은 수|그중 정답")
        perToken.entries.sortedByDescending { it.value[1] }.forEach { (t, a) -> out.appendLine("$t|${a[0]}|${a[1]}|${a[2]}") }
        File("build/b79_weak_tokens.txt").writeText(out.toString(), Charsets.UTF_8)
        println(out)
    }
}
