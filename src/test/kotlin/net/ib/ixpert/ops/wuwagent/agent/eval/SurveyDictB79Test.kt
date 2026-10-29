package net.ib.ixpert.ops.wuwagent.agent.eval

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.agent.ClaimedFieldCheck
import net.ib.ixpert.ops.wuwagent.agent.clarify.Stage0GraphScanner
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.LinkHint
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.AgenticSeedSelector
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.ChangeIntent
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DiscoveryConfig
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DomainDictionary
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DomainExtractor
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.GraphExpander
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.RelevanceScorer
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.SeedSelectionResult
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * B-79 1: 사전에 "설문" → survey를 넣은 효과. 오늘 SR로 (1) 사전 번역 (2) Stage 0 키워드 시드·후보·질문 카드의 읽기 범위 (3) sql_survey.xml 점수,
 * 그리고 기존 시나리오(24개, SR/그래프 15쌍)의 Stage 1 후보 안 필수 적중을 이전(B-78 3의 b78_action_tokens.txt "후" 열)과 비교한다. 결과는 build/b79_survey_dict.txt.
 */
class SurveyDictB79Test {

    private val surveyRoot = File("C:/Workspace/HC_card_survey_admin/survey_admin")
    private val original = "기존 설문조사 상세 조회 기능에 응답자 이메일 정보도 같이 내려주도록 백엔드를 수정해주세요"
    private val refined = "설문 응답 테이블에 이메일 컬럼을 추가하여 수집하고, 관리자 설문 상세 조회 시 응답자 이메일을 앞 2자리와 도메인만 보이도록 마스킹하여 반환한다."

    private fun norm(p: String) = p.replace('\\', '/')
    private fun gtMatch(sc: EvalScenario, g: EvalGt, path: String): Boolean =
        if (sc.matchMode() == "harness-contains") path.contains(g.name ?: g.path ?: "", ignoreCase = true)
        else norm(path).equals(norm(g.path ?: ""), ignoreCase = true) || norm(path).endsWith("/" + norm(g.path ?: ""), ignoreCase = true)

    private fun load(): ProjectGraph {
        Assume.assumeTrue(File(surveyRoot, ".meta/project-graph.json").isFile)
        return Gson().fromJson(File(surveyRoot, ".meta/project-graph.json").readText(Charsets.UTF_8), ProjectGraph::class.java).normalizeLegacyCollections()
    }

    @Test
    fun d1_설문이_survey로_번역된다() {
        val dict = DomainDictionary.load(load())
        assertEquals(setOf("survey"), dict.translate("설문"))
        assertTrue(dict.translate("설문조사").contains("survey"))
    }

    @Test
    fun d2_오늘_SR의_Stage0_시드_후보_읽기_범위_sql_survey_점수를_기록한다() {
        val g = load()
        val out = StringBuilder()
        // (1) Stage 0: 사실 확인 시드(키워드), 질문 카드의 읽기 범위
        val seeds = AgenticSeedSelector.heuristicSeeds(original, g).seedClasses
        val facts = ClaimedFieldCheck.stage0Facts(original, g, surveyRoot)
        out.appendLine("Stage 0 키워드 시드(사실 확인): $seeds")
        out.appendLine("Stage 0 질문 카드: ${facts.firstOrNull()?.question?.lineSequence()?.first()}")
        // (2) Stage 0 후보 목록(scanner): 후보 경로를 사실 확인 시드로 쓴다면
        val sc = Stage0GraphScanner(g)
        val cand = sc.rescanUnverified(sc.extractTokens(original), emptyList()).mapNotNull { (it.hint as? LinkHint.ExistingRef)?.filePath }.filter { g.files.containsKey(it) }
        out.appendLine("Stage 0 후보 목록(scanner, 경로 ${cand.size}개): ${cand.map { it.substringAfterLast('/') }}")
        out.appendLine("후보 경로를 시드로 쓴 사실 확인 결과: ${ClaimedFieldCheck.check(original, g, surveyRoot, cand).map { it.label }} (빈 목록 = 질문 없음)")
        // (3) 분석: 탐색기 시드로 점수 계산(정제문)
        val explorer = SeedSelectionResult(seedClasses = listOf("SurveyServiceImpl", "SurveyDaoImpl", "SurveyDto"), changeIntent = ChangeIntent.MODIFY, layerHint = listOf("SERVICE", "BIZ", "PRESENTATION"), frontendRelevant = false, reasoning = "t", judgePicks = listOf("SurveyServiceImpl", "SurveyDaoImpl", "SurveyDto", "sql_survey.xml"))
        val expanded = GraphExpander(g, DomainExtractor(g.files), DiscoveryConfig()).expand(explorer, refined)
        val scored = RelevanceScorer(g, fileLimit = 30, minScore = 55).scoreAndFilter(refined, expanded, explorer)
        out.appendLine("분석 점수(정제문, 탐색기 시드): " + listOf("sql_survey.xml", "SurveyServiceImpl.java", "SurveyDaoImpl.java", "SurveyDto.java", "ReviewDto.java", "IpsController.java", "AddressDto.java", "SearchHistRDto.java").joinToString(", ") { n -> n + "=" + (scored.firstOrNull { it.path.endsWith("/$n") || it.path.endsWith(n) }?.score ?: "후보 아님") })
        File("build/b79_survey_dict.txt").writeText(out.toString(), Charsets.UTF_8)
        println(out)
    }

    @Test
    fun d3_기존_시나리오_필수_적중_손실은_0건이다() {
        val scenarios = EvalScenarios.loadAll()
        val paths = EvalScenarios.graphPaths()
        Assume.assumeTrue(paths.values.all { File(it).isFile })
        val before = File("build/b79_before_hits.txt").takeIf { it.isFile }?.readLines(Charsets.UTF_8)?.drop(1)?.mapNotNull { l ->
            val p = l.split("|"); if (p.size >= 4) p[0] to p[3].substringAfter("/").toIntOrNull() else null
        }?.toMap() ?: emptyMap()
        val graphs = HashMap<String, ProjectGraph>()
        fun graphOf(key: String) = graphs.getOrPut(key) { Gson().fromJson(File(paths.getValue(key)).readText(Charsets.UTF_8), ProjectGraph::class.java).normalizeLegacyCollections() }
        val out = StringBuilder("시나리오|그래프|필수 정답 수|적중(이전/지금)|후보 수\n")
        var worse = 0
        val seen = HashSet<String>()
        for (sc in scenarios) {
            if (!seen.add(sc.graphKey + "|" + sc.sr)) continue
            val gts = sc.gtOrEmpty().filter { it.countsForRecall() }
            if (gts.isEmpty()) continue
            val g = graphOf(sc.graphKey)
            val seed = AgenticSeedSelector.heuristicSeeds(sc.sr, g)
            val expanded = GraphExpander(g, DomainExtractor(g.files), DiscoveryConfig(maxHop = 3)).expand(seed, sc.sr)
            val c = RelevanceScorer(g, fileLimit = 30, minScore = 55).scoreAndFilter(sc.sr, expanded, seed).take(30).map { it.path }
            val h = gts.count { gt -> c.any { gtMatch(sc, gt, it) } }
            val b = before[sc.id]
            if (b != null && h < b) worse++
            out.appendLine("${sc.id}|${sc.graphKey}|${gts.size}|${b ?: "?"}/$h|${c.size}")
        }
        out.appendLine("합계|SR/그래프 ${seen.size}쌍|이전보다 적중이 줄어든 시나리오 ${worse}개")
        File("build/b79_survey_dict_hits.txt").writeText(out.toString(), Charsets.UTF_8)
        println(out)
        assertEquals("필수 정답 손실", 0, worse)
    }
}
