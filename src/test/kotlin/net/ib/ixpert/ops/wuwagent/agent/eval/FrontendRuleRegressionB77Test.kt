package net.ib.ixpert.ops.wuwagent.agent.eval

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.AgenticSeedSelector
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DiscoveryConfig
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DomainExtractor
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.FrontendKeywordRule
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.GraphExpander
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.RelevanceScorer
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * B-77 3: dd60fa5 규칙("백엔드/서버/API가 있으면 화면 확장 끔")의 반대쪽 회귀.
 * D1(설문 발송 채널에 브랜드메시지 추가, 화면 파일 4개가 필요한 정답)의 SR에 API 문구를 붙인 변형에서
 * 이전 규칙(이 파일의 legacyBackend)이 화면 파일 4개를 잃는 것을 드러내고, 좁힌 규칙(백엔드/서버만 말하고 화면 수정 의도 없음일 때만 끔)에서는 잃지 않는 것을 확인한다.
 * 시드는 키워드 대체 경로, 후보는 Stage 1 점수 상위 30개(LLM 없음). 결과는 build/b77_frontend_rule.txt.
 */
class FrontendRuleRegressionB77Test {

    // ── 이전 규칙(dd60fa5)의 복사: 비교용 ──
    private val legacyBackend = Regex("백엔드|서버|\\bapi\\b|backend", RegexOption.IGNORE_CASE)
    private val legacyAudience = Regex("화면\\s*(용|에서\\s*(쓰|사용|쓸|쓴|사용하는|사용할))")
    private val legacyFront = listOf("어드민", "화면", "UI", "프론트", "vue", "jsp", "페이지", "웹", "frontend", "admin")
    private fun legacyExplicit(sr: String): Boolean {
        if (legacyBackend.containsMatchIn(sr)) return false
        val t = legacyAudience.replace(sr, " ")
        return legacyFront.any { t.contains(it, ignoreCase = true) }
    }
    private fun norm(p: String) = p.replace('\\', '/')
    private val screenExt = Regex("\\.(jsp|js|vue|html|jsx|tsx|css)$", RegexOption.IGNORE_CASE)

    private fun gtMatch(sc: EvalScenario, g: EvalGt, path: String): Boolean =
        if (sc.matchMode() == "harness-contains") path.contains(g.name ?: g.path ?: "", ignoreCase = true)
        else norm(path).equals(norm(g.path ?: ""), ignoreCase = true) || norm(path).endsWith("/" + norm(g.path ?: ""), ignoreCase = true)

    private class Row(val id: String, val sr: String, val legacy: Boolean, val now: Boolean, val gts: Int, val screenGts: Int,
                      val hitsTrue: Int, val hitsLegacy: Int, val hitsNow: Int, val screenTrue: Int, val screenLegacy: Int, val screenNow: Int,
                      val candTrue: Int, val candLegacy: Int, val candNow: Int)

    private fun measure(sc: EvalScenario, g: ProjectGraph): Row {
        val seed = AgenticSeedSelector.heuristicSeeds(sc.sr, g)
        // 규칙의 판정만 본다: 시드 구조 판정(컨트롤러·화면 자원 시드)은 true라고 가정한다. 대체 시드로는 구조 판정이 규칙과 상관없이 false가 되어 규칙의 효과가 가려진다.
        val legacy = !legacyBackend.containsMatchIn(sc.sr)
        val now = !FrontendKeywordRule.isBackendOnly(sc.sr)
        val domain = DomainExtractor(g.files)
        fun cands(flag: Boolean): List<String> {
            val s = seed.copy(frontendRelevant = flag)
            val expanded = GraphExpander(g, domain, DiscoveryConfig(maxHop = 3)).expand(s, sc.sr)
            return RelevanceScorer(g, fileLimit = 30, minScore = 55).scoreAndFilter(sc.sr, expanded, s).take(30).map { it.path }
        }
        val ct = cands(true); val cl = cands(legacy); val cn = cands(now)
        val gts = sc.gtOrEmpty().filter { it.countsForRecall() }
        fun hit(c: List<String>, gt: EvalGt) = c.any { gtMatch(sc, gt, it) }
        val screen = gts.filter { screenExt.containsMatchIn(it.path ?: it.name ?: "") }
        return Row(sc.id, sc.sr, legacy, now, gts.size, screen.size,
            gts.count { hit(ct, it) }, gts.count { hit(cl, it) }, gts.count { hit(cn, it) },
            screen.count { hit(ct, it) }, screen.count { hit(cl, it) }, screen.count { hit(cn, it) }, ct.size, cl.size, cn.size)
    }

    @Test
    fun f1_D1_API_변형에서_이전_규칙은_화면_파일을_잃고_좁힌_규칙은_잃지_않으며_기존_시나리오는_손실이_없다() {
        val scenarios = EvalScenarios.loadAll()
        val paths = EvalScenarios.graphPaths()
        Assume.assumeTrue(paths.values.all { File(it).isFile })
        val graphs = HashMap<String, ProjectGraph>()
        fun graphOf(key: String) = graphs.getOrPut(key) { Gson().fromJson(File(paths.getValue(key)).readText(Charsets.UTF_8), ProjectGraph::class.java).normalizeLegacyCollections() }

        val d1 = scenarios.firstOrNull { it.id == "D1" }; Assume.assumeNotNull(d1); d1!!
        val variants = listOf(
            d1.copy(id = "D1-API", sr = d1.sr + " (발송 API 포함)"),
            d1.copy(id = "D1-API+화면", sr = d1.sr + ". 발송 API와 설문 등록·목록 화면에도 반영한다"),
            d1.copy(id = "D1-서버+화면", sr = d1.sr + ". 서버 발송 로직과 설문 등록·목록 화면에도 반영한다"),
            // 대조: 백엔드만 고치라는 요구사항은 화면 파일이 정답이 아니다(정답에서 화면 파일을 뺀다). 화면 확장이 꺼져도 손실이 아니다.
            d1.copy(id = "D1-백엔드만", sr = "설문 발송 채널의 백엔드만 수정해 브랜드메시지를 추가한다",
                gt = d1.gtOrEmpty().filterNot { screenExt.containsMatchIn(it.path ?: it.name ?: "") })
        )

        val rows = ArrayList<Row>()
        val seen = HashSet<String>()
        for (sc in scenarios) {
            if (!seen.add(sc.graphKey + "|" + sc.sr)) continue
            if (sc.gtOrEmpty().none { it.countsForRecall() }) continue
            rows.add(measure(sc, graphOf(sc.graphKey)))
        }
        val baseRows = rows.size
        val varRows = variants.map { measure(it, graphOf(it.graphKey)) }
        val out = StringBuilder("시나리오|SR(앞 36자)|이전 규칙 화면확장|좁힌 규칙 화면확장|필수 정답|그 중 화면 파일|적중(강제 true/이전/좁힘)|화면 적중(강제 true/이전/좁힘)|후보 수(true/이전/좁힘)\n")
        for (r in rows + varRows) out.appendLine("${r.id}|${r.sr.take(36)}|${r.legacy}|${r.now}|${r.gts}|${r.screenGts}|${r.hitsTrue}/${r.hitsLegacy}/${r.hitsNow}|${r.screenTrue}/${r.screenLegacy}/${r.screenNow}|${r.candTrue}/${r.candLegacy}/${r.candNow}")
        val regress = rows.filter { it.hitsNow < it.hitsLegacy }
        val varLoss = varRows.filter { it.hitsNow < it.hitsTrue }
        out.appendLine("합계|기존 SR ${baseRows}개(그래프 조합)에서 좁힌 규칙이 이전 규칙보다 적중이 줄어든 시나리오 ${regress.size}개|변형 ${varRows.size}개에서 강제 true보다 줄어든 변형 ${varLoss.size}개")
        File("build/b77_frontend_rule.txt").writeText(out.toString(), Charsets.UTF_8)
        println(out)

        // 규칙 단위
        assertTrue(FrontendKeywordRule.isBackendOnly("관리자 설문 상세 화면용 기존 설문조사 상세 조회 기능의 백엔드를 수정하여 응답자 이메일 정보를 함께 반환한다"))
        assertTrue(FrontendKeywordRule.isBackendOnly("기존 설문조사 상세 조회 기능에 응답자 이메일 정보도 같이 내려주도록 백엔드를 수정해주세요"))
        assertFalse("API만 있는 문장은 화면 확장을 끄지 않는다", FrontendKeywordRule.isBackendOnly("설문 발송 채널에 브랜드메시지 추가 (발송 API 포함)"))
        assertFalse("서버와 화면 수정이 함께면 끄지 않는다", FrontendKeywordRule.isBackendOnly("서버 발송 로직과 설문 등록·목록 화면에도 반영한다"))
        assertTrue(FrontendKeywordRule.isExplicitFrontend("서버 발송 로직과 설문 등록·목록 화면에도 반영한다"))

        // 변형: 이전 규칙은 화면 파일 4개를 잃고, 좁힌 규칙은 잃지 않는다
        val api = varRows.first { it.id == "D1-API" }
        assertFalse("이전 규칙은 API 문구로 화면 확장을 껐다", api.legacy)
        assertEquals("이전 규칙에서 잃는 화면 정답 4개", 4, api.screenTrue - api.screenLegacy)
        assertEquals("좁힌 규칙은 화면 정답을 잃지 않는다", api.screenTrue, api.screenNow)
        assertTrue(varRows.filter { it.id != "D1-백엔드만" }.all { it.hitsNow == it.hitsTrue })
        // 기존 시나리오: 이전 규칙보다 적중이 줄지 않는다(필수 손실 0)
        assertTrue("적중이 줄어든 시나리오: ${regress.map { it.id }}", regress.isEmpty())
    }
}
