package net.ib.ixpert.ops.wuwagent.agent.eval

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.agent.clarify.Stage0ClarificationEngine
import net.ib.ixpert.ops.wuwagent.agent.clarify.Stage0GraphScanner
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.LinkHint
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.AgenticSeedSelector
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DiscoveryConfig
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DomainDictionary
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DomainExtractor
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.GenericTranslationTokens
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.GraphExpander
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.RelevanceScorer
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.SeedSelectionResult
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Assume
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/** B-81 1, 2, 6, 11 측정 전용(운영 연결 없음, 그래프 파일이 없으면 건너뜀): 시드 대체안 A/B/C, 4글자 미만 번역 토큰, Stage 1 점수 재현, Stage 0 후보 사전 비교. 결과는 build/b81_seed_design_probe.txt. */
class SeedDesignB81Test {
    private fun norm(p: String) = p.replace('\\', '/')
    private fun gtMatch(sc: EvalScenario, g: EvalGt, path: String): Boolean =
        if (sc.matchMode() == "harness-contains") path.contains(g.name ?: g.path ?: "", ignoreCase = true)
        else norm(path).equals(norm(g.path ?: ""), ignoreCase = true) || norm(path).endsWith("/" + norm(g.path ?: ""), ignoreCase = true)

    private val stop = setOf("추가", "생성", "신규", "삭제", "제거", "수정", "변경", "기능", "항목", "목록", "조회", "화면", "출력", "관련", "처리", "동작", "적용", "로직", "기반", "부분")

    private class Sc(val className: String, val dir: String, val direct: Int, val trans: Int, val local: Int, val comment: Int, val rank: Int, val order: Int) {
        val score get() = direct + trans + local + comment
        val korean get() = local + comment
    }

    private fun scored(sr: String, g: ProjectGraph): List<Sc> {
        val en = Regex("[a-zA-Z]{3,}").findAll(sr).map { it.value }.toList()
        val ko = Regex("[가-힣]{2,}").findAll(sr).map { it.value }.filter { it !in stop }.toList()
        val dict = DomainDictionary.load(g, domainNouns = true)
        val tr = ko.flatMap { dict.translate(it) }.filter { it.length >= 2 && !GenericTranslationTokens.isGeneric(it) }
        val trOnly = tr.filter { it.length >= 4 && it !in en }.distinct()
        return g.files.values.mapIndexedNotNull { order, n ->
            val d = 4 * en.distinct().count { n.className.contains(it, true) }
            val t = 2 * trOnly.count { n.className.contains(it, true) }
            val l = 3 * ko.distinct().count { k -> n.localName?.contains(k) == true }
            val c = ko.distinct().count { k -> n.koreanComments.any { it.contains(k) } }
            if (d + t + l + c > 0) Sc(n.className, n.path.substringBeforeLast('/'), d, t, l, c, rankOf(n.className), order) else null
        }
    }

    private fun rankOf(c: String) = when {
        Regex("Service(Impl)?$|SVC(Impl)?$").containsMatchIn(c) -> 0
        Regex("(Dao|DaoImpl|Mapper|Repository|Controller)$").containsMatchIn(c) -> 1
        Regex("(Dto|VO|Vo|Entity|Request|Response)$").containsMatchIn(c) -> 2
        else -> 3
    }

    private fun byScore(l: List<Sc>) = l.sortedWith(compareByDescending<Sc> { it.score }.thenBy { it.rank }.thenBy { it.order })

    /** 제안 A: 점수 상위 5(그래프 순서 없음). */
    private fun propA(l: List<Sc>) = byScore(l).map { it.className }.distinct().take(5)

    /** 제안 B: 점수 상위 3 + 한글 근거(이름·주석)가 있는 노드 중 상위 3의 폴더와 같은 폴더인 것을 점수순으로 2개(그래프 순서 없음). */
    private fun propB(l: List<Sc>): List<String> {
        val top = byScore(l).take(3)
        val dirs = top.map { it.dir }.toSet()
        val extra = byScore(l).filter { it.korean > 0 && it.dir in dirs }.map { it.className }
        return (top.map { it.className } + extra).distinct().take(5)
    }

    /** 제안 C: 배치 이름(Batch|Runner|Job|Cli) 제외 점수 상위 5 + 이름 근거 없이 한글 근거만 있는 노드를 그래프 순서로 2개(배치 제외). */
    private fun propC(l: List<Sc>): List<String> {
        val bat = Regex("Batch|Runner|Job|Cli")
        val nb = l.filter { !bat.containsMatchIn(it.className) }
        val top = byScore(nb).take(5).map { it.className }
        val kOnly = nb.filter { it.direct + it.trans == 0 && it.korean > 0 }.sortedBy { it.order }.map { it.className }.take(2)
        return (top + kOnly).distinct()
    }

    private fun cands(g: ProjectGraph, sr: String, seeds: List<String>): List<String> {
        val s: SeedSelectionResult = AgenticSeedSelector.heuristicSeeds(sr, g).copy(seedClasses = seeds)
        val ex = GraphExpander(g, DomainExtractor(g.files), DiscoveryConfig(maxHop = 3)).expand(s, sr)
        return RelevanceScorer(g, 30, 55).scoreAndFilter(sr, ex, s).take(30).map { it.path }
    }

    @Test
    fun probe() {
        val paths = EvalScenarios.graphPaths()
        Assume.assumeTrue(paths.values.all { File(it).isFile })
        val graphs = HashMap<String, ProjectGraph>()
        fun graphOf(key: String) = graphs.getOrPut(key) { Gson().fromJson(File(paths.getValue(key)).readText(Charsets.UTF_8), ProjectGraph::class.java).normalizeLegacyCollections() }
        val out = StringBuilder()

        // ── 2) 4글자 미만 번역 토큰 ──
        out.appendLine("=== [2] 4글자 미만 번역 토큰(15쌍) ===")
        val seen = HashSet<String>()
        val droppedCount = sortedMapOf<String, Int>()
        val biz = setOf("sms", "mms", "faq", "kmc", "rcs", "otp", "api", "sso", "csv", "vat", "url", "pdf", "sdk", "jwt")
        for (sc in EvalScenarios.loadAll()) {
            if (!seen.add(sc.graphKey + "|" + sc.sr)) continue
            val g = graphOf(sc.graphKey)
            val en = Regex("[a-zA-Z]{3,}").findAll(sc.sr).map { it.value }.toList()
            val ko = Regex("[가-힣]{2,}").findAll(sc.sr).map { it.value }.filter { it !in stop }.toList()
            val dict = DomainDictionary.load(g, domainNouns = true)
            val tr = ko.flatMap { dict.translate(it) }.filter { it.length >= 2 && !GenericTranslationTokens.isGeneric(it) }.distinct()
            val dropped = tr.filter { it.length < 4 && it !in en }
            val gtNames = sc.gtOrEmpty().filter { it.countsForRecall() }.map { (it.path ?: it.name ?: "").substringAfterLast('/').substringBeforeLast('.') }
            val gtHit = dropped.filter { t -> gtNames.any { it.contains(t, true) } }
            dropped.forEach { droppedCount[it] = (droppedCount[it] ?: 0) + 1 }
            out.appendLine("${sc.id}|${sc.graphKey}| 번역=${tr} | 빠짐=${dropped} | 필수 정답 이름에 든 빠진 토큰=${gtHit} | 업무 약어 후보=${dropped.filter { it in biz }}")
        }
        out.appendLine("빠진 토큰 합계(쌍 수): $droppedCount")
        // 업무 약어가 사전 번역으로 나오는지(한글 단어 → 번역 3글자 이하)
        for (gk in paths.keys.take(3)) {
            val dict = DomainDictionary.load(graphOf(gk), domainNouns = true)
            for (w in listOf("문자", "알림톡", "인증", "팩스", "쿠폰", "결제", "가입", "자주묻는질문", "로그인", "카카오", "발송", "설문", "이메일", "휴대폰", "단말")) {
                val t = dict.translate(w)
                if (t.any { it.length < 4 }) out.appendLine("  [$gk] '$w' → $t (3글자 이하 포함)")
            }
        }

        // ── 1) 시드 대체안 ──
        out.appendLine("\n=== [1] 시드: 현재(점수 3 + 그래프 순서 5) vs 제안 A(점수 상위 5) vs 제안 B(점수 3 + 같은 폴더 한글 근거 2) ===")
        out.appendLine("시나리오|그래프|필수|적중 현재/A/B|배치 시드 수 현재/A/B|A에서 잃음|B에서 잃음")
        var lostC = 0; var lostA = 0; var lostB = 0; var batchCur = 0; var batchA = 0; var batchB = 0
        seen.clear()
        for (sc in EvalScenarios.loadAll()) {
            if (!seen.add(sc.graphKey + "|" + sc.sr)) continue
            val gts = sc.gtOrEmpty().filter { it.countsForRecall() }
            if (gts.isEmpty()) continue
            val g = graphOf(sc.graphKey)
            val l = scored(sc.sr, g)
            val cur = AgenticSeedSelector.heuristicSeeds(sc.sr, g).seedClasses
            val a = propA(l); val b = propB(l); val cc = propC(l)
            fun hits(seeds: List<String>) = cands(g, sc.sr, seeds).let { c -> gts.filter { gt -> c.any { gtMatch(sc, gt, it) } } }
            val hc = hits(cur); val ha = hits(a); val hb = hits(b); val hcc = hits(cc); val lc = hc.filter { it !in hcc }; lostC += lc.size; if (lc.isNotEmpty() || cc.any { Regex("Batch|Runner|Job|Cli").containsMatchIn(it) }) out.appendLine("   C=$cc 잃음=${lc.map { it.path ?: it.name }}")
            val bat = Regex("Batch|Runner|Job|Cli")
            batchCur += cur.count { bat.containsMatchIn(it) }; batchA += a.count { bat.containsMatchIn(it) }; batchB += b.count { bat.containsMatchIn(it) }
            val la = hc.filter { it !in ha }; val lb = hc.filter { it !in hb }
            lostA += la.size; lostB += lb.size
            out.appendLine("${sc.id}|${sc.graphKey}|${gts.size}|${hc.size}/${ha.size}/${hb.size}|${cur.count { bat.containsMatchIn(it) }}/${a.count { bat.containsMatchIn(it) }}/${b.count { bat.containsMatchIn(it) }}|${la.map { it.path ?: it.name }}|${lb.map { it.path ?: it.name }}")
            if (la.isNotEmpty() || lb.isNotEmpty()) out.appendLine("   현재=$cur\n   A=$a\n   B=$b")
        }
        out.appendLine("C가 잃은 필수 정답 ${lostC}개"); out.appendLine("합계: 현재 대비 A가 잃은 필수 정답 ${lostA}개, B가 잃은 필수 정답 ${lostB}개 | 배치 시드 합계 현재/A/B = $batchCur/$batchA/$batchB")

        // 오늘 SR
        val root = "C:/Workspace/HC_card_survey_admin/survey_admin"
        val sg = Gson().fromJson(File("$root/.meta/project-graph.json").readText(Charsets.UTF_8), ProjectGraph::class.java).normalizeLegacyCollections()
        val today = "기존 설문조사 상세 조회 기능에 응답자 이메일 정보도 같이 내려주도록 백엔드를 수정해주세요"
        val tl = scored(today, sg)
        out.appendLine("\n[오늘 SR] 점수 상위 12: " + byScore(tl).take(12).joinToString { "${it.className}(${it.score}=d${it.direct}+t${it.trans}+l${it.local}+c${it.comment})" })
        out.appendLine("[오늘 SR] 현재=${AgenticSeedSelector.heuristicSeeds(today, sg).seedClasses}\n[오늘 SR] A=${propA(tl)}\n[오늘 SR] B=${propB(tl)}")
        out.appendLine("[오늘 SR] C=${propC(tl)} / 폴더=${propC(tl).mapNotNull { c -> sg.files.values.firstOrNull { it.className == c }?.path?.substringBeforeLast('/')?.substringAfterLast('/') }.distinct()}")
        // 한글 근거만으로 잡히는 노이즈(ClosingLinkDto 같은 것)가 어디까지 올라가는지
        out.appendLine("[오늘 SR] 한글 근거 점수 상위 8: " + tl.sortedByDescending { it.korean }.take(8).joinToString { "${it.className}(l${it.local}+c${it.comment})" })
        val ref = "설문 응답 테이블에 이메일 컬럼을 추가하여 수집하고, 관리자 설문 상세 조회 시 응답자 이메일을 앞 2자리와 도메인만 노출되도록 마스킹 처리하여 백엔드 API 응답에 포함하도록 수정한다."
        val rl = scored(ref, sg)
        out.appendLine("[오늘 정제문] 점수 상위 8: " + byScore(rl).take(8).joinToString { "${it.className}(${it.score})" } + " | 한글 근거 상위 5: " + rl.sortedByDescending { it.korean }.take(5).joinToString { "${it.className}(${it.korean})" })

        // ── 6, 11) 오늘 IDE 시드로 Stage 1 점수 재현 + 구성 ──
        val baos = ByteArrayOutputStream(); val old = System.out
        System.setOut(PrintStream(baos, true, "UTF-8"))
        try { cands(sg, ref, listOf("SurveyServiceImpl", "SurveyDaoImpl", "SurveyDto", "sql_survey.xml")) } finally { System.setOut(old) }
        val lines = baos.toString("UTF-8").lines()
        out.appendLine("\n=== [6, 11] 오늘 정제문 + IDE 시드 4개로 Stage 1 점수(하네스 재현) ===")
        lines.filter { Regex("Node: (IpsController|sql_survey.xml|SurveyDao|SurveyService|UserServiceImpl|ClosingLinkDto|ReviewDto),").containsMatchIn(it) || it.contains("VETTED BYPASS: sql_survey") || it.contains("RESOURCE] Node: sql_survey") }.forEach { out.appendLine("  " + it) }
        val total = sg.files.size + sg.resourceNodes.size
        val df = sg.files.values.count { it.className.contains("survey", true) } + sg.resourceNodes.count { it.path.substringAfterLast('/').contains("survey", true) }
        val idf = Math.log(total.toDouble() / df) / Math.log(total.toDouble())
        out.appendLine("  전체 노드 $total, df(survey)=$df, idf=${"%.3f".format(idf)} → 리소스 이름 일치 번역 30×idf=${"%.1f".format(30 * idf)}, 직접 50×idf=${"%.1f".format(50 * idf)}, 리소스 최대(홉 10+직접+층 15)=${"%.1f".format(10 + 50 * idf + 15)}")

        // ── 1) Stage 0 카드 후보 경로: 사전 유무 비교(스캐너 사전 domainNouns) ──
        out.appendLine("\n=== [1] Stage 0 카드 후보: 스캐너 사전 domainNouns false(현재) vs true ===")
        for (flag in listOf(false, true)) {
            val b2 = ByteArrayOutputStream()
            System.setOut(PrintStream(b2, true, "UTF-8"))
            val items = try { Stage0ClarificationEngine(Stage0GraphScanner(sg, domainNouns = flag), sg, null, deferTaskSummary = true, projectRoot = File(root)).initSession(today).state.items } finally { System.setOut(old) }
            out.appendLine("  domainNouns=$flag 후보 ${items.size}개: " + items.joinToString { ((it.hint as? LinkHint.ExistingRef)?.filePath ?: it.statement).substringAfterLast('/') })
            b2.toString("UTF-8").lines().filter { it.contains("[Stage0Candidates]") || it.contains("[Stage0Seeds]") }.forEach { out.appendLine("    " + it.take(900)) }
        }
        File("build/b81_seed_design_probe.txt").writeText(out.toString(), Charsets.UTF_8)
        println(out)
    }
}
