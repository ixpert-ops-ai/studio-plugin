package net.ib.ixpert.ops.wuwagent.agent.eval

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.AgenticSeedSelector
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DiscoveryConfig
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DomainExtractor
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.GraphExpander
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.GenericTranslationTokens
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.RelevanceScorer
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.SeedSelectionResult
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.ChangeIntent
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import net.ib.ixpert.ops.wuwagent.agent.RequirementAnalysisPipeline
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.model.ChatChoice
import net.ib.ixpert.ops.wuwagent.model.ChatCompletionResponse
import net.ib.ixpert.ops.wuwagent.model.ChatMessage
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.OllamaMessage
import net.ib.ixpert.ops.wuwagent.model.ToolCall
import net.ib.ixpert.ops.wuwagent.model.ToolCallFunction
import net.ib.ixpert.ops.wuwagent.model.ToolDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * B-78 3: 동작어 번역(add, search)을 이름 일치에서 뺀다. 오늘 SR에서 AddressDto, SearchHistRDto가 후보에서 빠지고,
 * 기존 시나리오(24개, SR/그래프 15쌍)의 Stage 1 후보 안 필수 적중은 줄지 않는다(LLM 없음, 대체 시드). 결과는 build/b78_action_tokens.txt.
 */
class ActionTokenLossB78Test {

    private fun norm(p: String) = p.replace('\\', '/')
    private fun gtMatch(sc: EvalScenario, g: EvalGt, path: String): Boolean =
        if (sc.matchMode() == "harness-contains") path.contains(g.name ?: g.path ?: "", ignoreCase = true)
        else norm(path).equals(norm(g.path ?: ""), ignoreCase = true) || norm(path).endsWith("/" + norm(g.path ?: ""), ignoreCase = true)

    private fun candidates(g: ProjectGraph, sr: String, seed: SeedSelectionResult, exclude: Boolean): List<String> {
        val domain = DomainExtractor(g.files)
        val expanded = GraphExpander(g, domain, DiscoveryConfig(maxHop = 3)).expand(seed, sr)
        return RelevanceScorer(g, fileLimit = 30, minScore = 55, excludeActionTokens = exclude).scoreAndFilter(sr, expanded, seed).take(30).map { it.path }
    }

    @Test
    fun a1_동작어_판정() {
        assertTrue(GenericTranslationTokens.isActionOnly("add"))
        assertTrue(GenericTranslationTokens.isActionOnly("Search"))
        assertFalse(GenericTranslationTokens.isActionOnly("survey"))
        assertFalse(GenericTranslationTokens.isActionOnly("delete"))
    }

    @Test
    fun a2_오늘_SR에서_AddressDto_SearchHistRDto가_후보에서_빠진다() {
        val root = File("C:/Workspace/HC_card_survey_admin/survey_admin")
        Assume.assumeTrue(File(root, ".meta/project-graph.json").isFile)
        val g = Gson().fromJson(File(root, ".meta/project-graph.json").readText(Charsets.UTF_8), ProjectGraph::class.java).normalizeLegacyCollections()
        val refined = "설문 응답 테이블에 이메일 컬럼을 추가하여 수집하고, 관리자 설문 상세 조회 시 응답자 이메일을 앞 2자리와 도메인만 보이도록 마스킹하여 반환한다."
        val seedClasses = listOf("SurveyServiceImpl", "SurveyDaoImpl", "SurveyDto")
        val seed = SeedSelectionResult(seedClasses = seedClasses, changeIntent = ChangeIntent.MODIFY, layerHint = listOf("SERVICE"), frontendRelevant = false, reasoning = "test", usedFallback = false)
        val before = candidates(g, refined, seed, exclude = false).map { it.substringAfterLast('/') }
        val after = candidates(g, refined, seed, exclude = true).map { it.substringAfterLast('/') }
        File("build/b78_action_tokens_today.txt").writeText("전: ${before.size}개 $before\n후: ${after.size}개 $after\n", Charsets.UTF_8)
        println("전: $before\n후: $after")
        val droppedNow = listOf("AddressDto.java", "SearchHistRDto.java").filter { it in before && it !in after }
        assertTrue("변경 후에는 AddressDto, SearchHistRDto가 후보에 없다: $after", "AddressDto.java" !in after && "SearchHistRDto.java" !in after)
        println("변경으로 빠진 후보: $droppedNow")
    }

    /** 모의 탐색기(시드 고정)로 실제 파이프라인을 태우며 Stage 3 요청의 후보 목록을 캡처한다. 서버 호출 없음. */
    private class CaptureStub(val seeds: List<String>) : LLMClient {
        @Volatile var stage3User: String? = null
        override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): OllamaChatResponse =
            OllamaChatResponse(model = "stub", createdAt = "", message = OllamaMessage("assistant", "{}"), done = true)
        private fun call(name: String, args: String) =
            ChatCompletionResponse("x", listOf(ChatChoice(0, ChatMessage("assistant", null, toolCalls = listOf(ToolCall("t", "function", ToolCallFunction(name, args)))), "tool_calls")))
        override fun chatWithTools(systemPrompt: String, messages: List<ChatMessage>, maxTokens: Int?, tools: List<ToolDefinition>?, toolChoice: Any?, temperature: Double?): ChatCompletionResponse? {
            if (systemPrompt.contains("당신은 시스템의 소스코드 및 메타그래프를"))
                return call("confirm_final_seeds", "{\"seed_classes\":[${seeds.joinToString(",") { "\"$it\"" }}],\"rationale\":\"실험\"}")
            if (!systemPrompt.contains("당신은 코드 변경 범위 검증자입니다.")) return null
            stage3User = messages.mapNotNull { it.content }.joinToString("\n")
            return call("submit_verification", "{\"requiredFiles\":[{\"n\":1,\"reason\":\"수정이 필요한 구체적인 사유\"}],\"removedFiles\":[],\"reasoning\":\"요약\"}")
        }
        override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
    }

    @Test
    fun a2b_실제_파이프라인_Stage3_후보에서_AddressDto_SearchHistRDto가_빠진다() {
        val root = File("C:/Workspace/HC_card_survey_admin/survey_admin")
        Assume.assumeTrue(File(root, ".meta/project-graph.json").isFile)
        val g = Gson().fromJson(File(root, ".meta/project-graph.json").readText(Charsets.UTF_8), ProjectGraph::class.java).normalizeLegacyCollections()
        val refined = "설문 응답 테이블에 이메일 컬럼을 추가하여 수집하고, 관리자 설문 상세 조회 시 응답자 이메일을 앞 2자리와 도메인만 보이도록 마스킹하여 반환한다."
        val stub = CaptureStub(listOf("SurveyServiceImpl", "SurveyDaoImpl", "SurveyDto", "sql_survey.xml"))
        kotlinx.coroutines.runBlocking {
            RequirementAnalysisPipeline(null, stub, true).analyze(primaryReq = refined, secondaryReq = "", projectGraph = g, projectRoot = root) { }
        }
        val user = stub.stage3User ?: error("Stage 3 요청을 캡처하지 못했다")
        val names = Regex("(?m)^- \\[\\d+] (\\S+)").findAll(user).map { it.groupValues[1].substringAfterLast('/') }.toList()
        File("build/b78_action_tokens_stage3.txt").writeText("Stage 3 후보 ${names.size}개: $names\n", Charsets.UTF_8)
        println("Stage 3 후보: $names")
        assertFalse(names.toString(), "AddressDto.java" in names)
        assertFalse(names.toString(), "SearchHistRDto.java" in names)
    }

    @Test
    fun a3_기존_시나리오_필수_적중_손실은_0건이다() {
        val scenarios = EvalScenarios.loadAll()
        val paths = EvalScenarios.graphPaths()
        Assume.assumeTrue(paths.values.all { File(it).isFile })
        val graphs = HashMap<String, ProjectGraph>()
        fun graphOf(key: String) = graphs.getOrPut(key) { Gson().fromJson(File(paths.getValue(key)).readText(Charsets.UTF_8), ProjectGraph::class.java).normalizeLegacyCollections() }
        val out = StringBuilder("시나리오|그래프|필수 정답 수|적중(전/후)|후보 수(전/후)|후로 빠진 후보 수|후에 잃은 정답\n")
        var lost = 0
        val seen = HashSet<String>()
        for (sc in scenarios) {
            if (!seen.add(sc.graphKey + "|" + sc.sr)) continue
            val gts = sc.gtOrEmpty().filter { it.countsForRecall() }
            if (gts.isEmpty()) continue
            val g = graphOf(sc.graphKey)
            val seed = AgenticSeedSelector.heuristicSeeds(sc.sr, g)
            val b = candidates(g, sc.sr, seed, false)
            val a = candidates(g, sc.sr, seed, true)
            fun hits(c: List<String>) = gts.filter { gt -> c.any { gtMatch(sc, gt, it) } }
            val hb = hits(b); val ha = hits(a)
            val lostNow = hb.filter { it !in ha }
            lost += lostNow.size
            out.appendLine("${sc.id}|${sc.graphKey}|${gts.size}|${hb.size}/${ha.size}|${b.size}/${a.size}|${b.count { it !in a }}|${lostNow.map { it.path ?: it.name }}")
        }
        out.appendLine("합계|SR/그래프 ${seen.size}쌍|잃은 필수 정답 ${lost}개")
        File("build/b78_action_tokens.txt").writeText(out.toString(), Charsets.UTF_8)
        println(out)
        assertEquals("필수 정답 손실", 0, lost)
    }
}
