package net.ib.ixpert.ops.wuwagent.agent.eval

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.AgenticSeedSelector
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DiscoveryConfig
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DomainExtractor
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.FrontendKeywordRule
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.GraphExpander
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.RelevanceScorer
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * B-78 6d(읽기만, 보고용): "수집/입력/저장/등록" 의도가 있는 SR에서 frontendRelevant=false 때문에 화면 정답 파일이 빠지는 시나리오를 기존 시나리오로 집계한다.
 * 화면 확장이 꺼지는 경로는 둘이다: (1) 규칙(백엔드/서버만 말함: isBackendOnly) (2) 대체 시드의 구조 판정(컨트롤러·뷰 시드가 없음). 둘 다 보고한다. 결과는 build/b78_collect_intent.txt.
 */
class CollectIntentFrontendB78Test {

    private val intentRe = Regex("수집|입력|저장|등록|작성")
    private val screenExt = Regex("\\.(jsp|js|vue|html|jsx|tsx|css)$", RegexOption.IGNORE_CASE)
    private fun norm(p: String) = p.replace('\\', '/')
    private fun gtMatch(sc: EvalScenario, g: EvalGt, path: String): Boolean =
        if (sc.matchMode() == "harness-contains") path.contains(g.name ?: g.path ?: "", ignoreCase = true)
        else norm(path).equals(norm(g.path ?: ""), ignoreCase = true) || norm(path).endsWith("/" + norm(g.path ?: ""), ignoreCase = true)

    @Test
    fun c1_수집_입력_의도_SR의_화면_파일_손실을_집계한다() {
        val scenarios = EvalScenarios.loadAll()
        val paths = EvalScenarios.graphPaths()
        Assume.assumeTrue(paths.values.all { File(it).isFile })
        val graphs = HashMap<String, ProjectGraph>()
        fun graphOf(key: String) = graphs.getOrPut(key) { Gson().fromJson(File(paths.getValue(key)).readText(Charsets.UTF_8), ProjectGraph::class.java).normalizeLegacyCollections() }

        val out = StringBuilder("시나리오|SR(앞 36자)|수집·입력 의도|규칙으로 꺼짐(isBackendOnly)|대체 시드 판정(frontendRelevant)|화면 정답 수|화면 적중(강제 true/대체 시드 판정)|화면 손실\n")
        var intentCount = 0
        var ruleOff = 0
        var lossScenarios = 0
        var lossFiles = 0
        var allLoss = 0
        val seen = HashSet<String>()
        for (sc in scenarios) {
            if (!seen.add(sc.graphKey + "|" + sc.sr)) continue
            val g = graphOf(sc.graphKey)
            val gts = sc.gtOrEmpty().filter { it.countsForRecall() }
            val screens = gts.filter { screenExt.containsMatchIn(it.path ?: it.name ?: "") }
            val intent = intentRe.containsMatchIn(sc.sr)
            val seed = AgenticSeedSelector.heuristicSeeds(sc.sr, g)
            val off = FrontendKeywordRule.isBackendOnly(sc.sr)
            fun cands(flag: Boolean): List<String> {
                val s = seed.copy(frontendRelevant = flag)
                val expanded = GraphExpander(g, DomainExtractor(g.files), DiscoveryConfig(maxHop = 3)).expand(s, sc.sr)
                return RelevanceScorer(g, fileLimit = 30, minScore = 55).scoreAndFilter(sc.sr, expanded, s).take(30).map { it.path }
            }
            val ct = cands(true)
            val cs = cands(seed.frontendRelevant && !off)
            fun hits(c: List<String>) = screens.count { gt -> c.any { gtMatch(sc, gt, it) } }
            val ht = hits(ct); val hs = hits(cs)
            val loss = ht - hs
            if (loss > 0) allLoss += loss
            if (intent) {
                intentCount++
                if (off) ruleOff++
                if (loss > 0) { lossScenarios++; lossFiles += loss }
            }
            out.appendLine("${sc.id}|${sc.sr.take(36)}|$intent|$off|${seed.frontendRelevant}|${screens.size}|$ht/$hs|$loss")
        }
        out.appendLine("합계|SR/그래프 ${seen.size}쌍 중 수집·입력 의도 ${intentCount}개|그중 규칙으로 꺼진 SR ${ruleOff}개|화면 손실이 있는 의도 SR ${lossScenarios}개(화면 파일 ${lossFiles}개)|의도와 상관없이 전체 화면 손실 ${allLoss}개")
        File("build/b78_collect_intent.txt").writeText(out.toString(), Charsets.UTF_8)
        println(out)
    }
}
