package net.ib.ixpert.ops.wuwagent.agent.eval

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.model.ChatCompletionResponse
import net.ib.ixpert.ops.wuwagent.model.ChatMessage
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.OllamaMessage
import net.ib.ixpert.ops.wuwagent.model.ToolDefinition
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
 * B-76 5(오프라인, LLM 없음): dd60fa5의 "백엔드/서버/API가 있으면 frontendRelevant=false" 규칙이 화면 파일을 잃는지 본다.
 * 24개 시나리오 SR 가운데 규칙이 실제로 켜지는(백엔드/서버/API를 말하는) SR이 몇 개인지 세고,
 * 켜지는 SR이 없어도 최악의 경우를 알 수 있도록 모든 시나리오에서 frontendRelevant를 true/false로 강제해 후보와 정답(GT) 화면 파일 적중을 비교한다.
 * 시드는 키워드 대체 경로(탐색기 실패와 같은 경로)로 골랐다. 결과는 build/b76_frontend_rule.txt.
 */
class FrontendRuleLossB76Test {

    private class NullClient : LLMClient {
        override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): OllamaChatResponse? =
            OllamaChatResponse(model = "stub", createdAt = "", message = OllamaMessage("assistant", "{}"), done = true)
        override fun chatWithTools(systemPrompt: String, messages: List<ChatMessage>, maxTokens: Int?, tools: List<ToolDefinition>?, toolChoice: Any?, temperature: Double?): ChatCompletionResponse? = null
        override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
    }

    private fun norm(p: String) = p.replace('\\', '/')
    private val screenExt = Regex("\\.(jsp|js|vue|html|jsx|tsx|css)$", RegexOption.IGNORE_CASE)

    private fun gtMatch(sc: EvalScenario, g: EvalGt, path: String): Boolean =
        if (sc.matchMode() == "harness-contains") path.contains(g.name ?: g.path ?: "", ignoreCase = true)
        else norm(path).equals(norm(g.path ?: ""), ignoreCase = true) || norm(path).endsWith("/" + norm(g.path ?: ""), ignoreCase = true)

    @Test
    fun f1_규칙이_켜지는_SR_수와_frontendRelevant_강제_비교() {
        val scenarios = EvalScenarios.loadAll()
        val paths = EvalScenarios.graphPaths()
        Assume.assumeTrue(paths.values.all { File(it).isFile })
        val graphs = HashMap<String, ProjectGraph>()
        fun graphOf(key: String) = graphs.getOrPut(key) { Gson().fromJson(File(paths.getValue(key)).readText(Charsets.UTF_8), ProjectGraph::class.java).normalizeLegacyCollections() }

        val out = StringBuilder("시나리오|그래프|규칙 발동(백엔드/서버/API 언급)|시드(대체 경로)|후보 true→false|GT(recall 대상)|GT 적중 true→false|GT 화면 파일|화면 GT 적중 true→false|false에서 잃은 GT\n")
        var fires = 0
        var lostTotal = 0
        var screenLost = 0
        val seen = HashSet<String>()
        for (sc in scenarios) {
            if (!seen.add(sc.graphKey + "|" + sc.sr)) continue // 같은 SR·그래프 변형(D5E 등)은 한 번만
            val g = graphOf(sc.graphKey)
            val seed = AgenticSeedSelector(NullClient(), null, null).selectSeeds(sc.sr, g)
            val fire = FrontendKeywordRule.mentionsBackend(sc.sr)
            if (fire) fires++
            val domain = DomainExtractor(g.files)
            fun run(flag: Boolean): List<String> {
                val s = seed.copy(frontendRelevant = flag)
                val expanded = GraphExpander(g, domain, DiscoveryConfig(maxHop = 3)).expand(s, sc.sr)
                return RelevanceScorer(g, fileLimit = 30, minScore = 55).scoreAndFilter(sc.sr, expanded, s).take(30).map { it.path }
            }
            val t = run(true)
            val f = run(false)
            val gts = sc.gtOrEmpty().filter { it.countsForRecall() }
            fun hits(c: List<String>) = gts.filter { gt -> c.any { gtMatch(sc, gt, it) } }
            val ht = hits(t); val hf = hits(f)
            val lost = ht.filter { it !in hf }
            val screenGts = gts.filter { screenExt.containsMatchIn(it.path ?: it.name ?: "") }
            val sht = screenGts.count { it in ht }; val shf = screenGts.count { it in hf }
            lostTotal += lost.size
            screenLost += screenGts.count { it in ht && it !in hf }
            out.appendLine("${sc.id}|${sc.graphKey}|$fire|${seed.seedClasses.take(4)}|${t.size}→${f.size}|${gts.size}|${ht.size}→${hf.size}|${screenGts.size}|$sht→$shf|${lost.map { it.path ?: it.name }}")
        }
        out.appendLine("합계|규칙 발동 SR ${fires}개 / 비교한 SR ${seen.size}개|false로 강제 시 잃은 GT ${lostTotal}개(그 중 화면 파일 ${screenLost}개)")
        File("build/b76_frontend_rule.txt").writeText(out.toString(), Charsets.UTF_8)
        println(out)
    }
}
