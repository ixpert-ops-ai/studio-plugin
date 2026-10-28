package net.ib.ixpert.ops.wuwagent.agent.eval

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.AgenticSeedSelector
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DiscoveryConfig
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DomainExtractor
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.GraphExpander
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.RelevanceScorer
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.ScopeSelector
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraphQueryable
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * B-77 2(오프라인, LLM 없음): 패키지 선택 카드(50개 초과면 표시)를 건너뛰고 전체 그래프로 진행하면 필수(required) 정답 적중을 잃는가.
 * 비교 대상은 "이상적인 패키지 선택"(필수 정답 파일이 있는 폴더만 고른 경우, 카드가 줄 수 있는 최선)과 "선택 없음"이다.
 * 이상적인 선택보다 선택 없음이 적중을 덜 하는 시나리오가 없으면 카드를 건너뛰어도 손실이 없다.
 * 시드는 키워드 대체 경로(AgenticSeedSelector.heuristicSeeds)이고 후보는 Stage 1 점수 상위 30개다(Stage 2·3과 LLM 시드는 포함하지 않는다). 결과는 build/b77_scope_card.txt.
 */
class ScopeCardLossB77Test {

    private fun norm(p: String) = p.replace('\\', '/')
    private val prefixes = listOf("src/main/java/", "src/main/kotlin/", "src/main/resources/", "src/main/webapp/", "frontend/src/")

    private fun gtMatch(sc: EvalScenario, g: EvalGt, path: String): Boolean =
        if (sc.matchMode() == "harness-contains") path.contains(g.name ?: g.path ?: "", ignoreCase = true)
        else norm(path).equals(norm(g.path ?: ""), ignoreCase = true) || norm(path).endsWith("/" + norm(g.path ?: ""), ignoreCase = true)

    private fun candidates(graph: ProjectGraphQueryable, sr: String): List<String> {
        val seed = AgenticSeedSelector.heuristicSeeds(sr, graph)
        val domain = DomainExtractor(graph.files)
        val expanded = GraphExpander(graph, domain, DiscoveryConfig(maxHop = 3)).expand(seed, sr)
        return RelevanceScorer(graph, fileLimit = 30, minScore = 55).scoreAndFilter(sr, expanded, seed).take(30).map { it.path }
    }

    @Test
    fun s1_패키지_선택_없이_진행하면_필수_적중을_잃는가() {
        val scenarios = EvalScenarios.loadAll()
        val paths = EvalScenarios.graphPaths()
        Assume.assumeTrue(paths.values.all { File(it).isFile })
        val graphs = HashMap<String, ProjectGraph>()
        fun graphOf(key: String) = graphs.getOrPut(key) { Gson().fromJson(File(paths.getValue(key)).readText(Charsets.UTF_8), ProjectGraph::class.java).normalizeLegacyCollections() }

        val out = StringBuilder("시나리오|그래프|전체 파일 수|카드(>50)|필수 정답 수|선택 없음 적중|이상적 선택 적중|선택 범위 파일 수|이상적 선택이 더 맞춘 정답\n")
        var helps = 0
        var hurts = 0
        var compared = 0
        val seen = HashSet<String>()
        for (sc in scenarios) {
            if (!seen.add(sc.graphKey + "|" + sc.sr)) continue
            val g = graphOf(sc.graphKey)
            val gts = sc.gtOrEmpty().filter { it.countsForRecall() }
            if (gts.isEmpty()) continue
            val gtFiles = gts.flatMap { gt -> g.files.values.map { it.path }.filter { gtMatch(sc, gt, it) } }.distinct()
            val dirs = gtFiles.map { f -> prefixes.fold(norm(f)) { s, p -> s.removePrefix(p) }.substringBeforeLast('/') }.distinct()
            val sub = ScopeSelector.buildSubMetaGraph(g, dirs)
            val full = candidates(g, sc.sr)
            val sel = candidates(sub, sc.sr)
            fun hits(c: List<String>) = gts.filter { gt -> c.any { gtMatch(sc, gt, it) } }
            val hf = hits(full)
            val hs = hits(sel)
            val moreInSel = hs.filter { it !in hf }
            compared++
            if (moreInSel.isNotEmpty()) helps++
            if (hf.any { it !in hs }) hurts++
            out.appendLine("${sc.id}|${sc.graphKey}|${g.totalFileCount}|${g.totalFileCount > 50}|${gts.size}|${hf.size}|${hs.size}|${sub.totalFileCount}|${moreInSel.map { it.path ?: it.name }}")
        }
        out.appendLine("합계|비교 ${compared}개 SR(그래프 조합)|이상적 선택이 선택 없음보다 더 맞춘 시나리오 ${helps}개|선택 없음이 더 맞춘 시나리오 ${hurts}개")
        graphs.forEach { (k, v) -> out.appendLine("그래프 $k: 전체 파일 수 ${v.totalFileCount} (files ${v.files.size})") }
        File("build/b77_scope_card.txt").writeText(out.toString(), Charsets.UTF_8)
        println(out)
    }
}
