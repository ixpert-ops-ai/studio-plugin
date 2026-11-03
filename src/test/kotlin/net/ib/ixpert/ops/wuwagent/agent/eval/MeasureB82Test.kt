package net.ib.ixpert.ops.wuwagent.agent.eval

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.agent.clarify.Stage0ClarificationEngine
import net.ib.ixpert.ops.wuwagent.agent.clarify.Stage0GraphScanner
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.LinkHint
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.TokenKind
import net.ib.ixpert.ops.wuwagent.agent.completeness.CompletenessEngine
import net.ib.ixpert.ops.wuwagent.agent.completeness.ProjectGraphAdapter
import net.ib.ixpert.ops.wuwagent.agent.completeness.model.SrFacts
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.AgenticGraphTools
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.AgenticSeedSelector
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DiscoveryConfig
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DomainExtractor
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.GraphExpander
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.RelevanceScorer
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Assume
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/** B-82 6, 8, GUARD 분포 측정 전용(운영 연결 없음, 그래프 파일이 없으면 건너뜀). 결과는 build/b82_measure.txt. */
class MeasureB82Test {
    private fun norm(p: String) = p.replace('\\', '/')
    private fun gtMatch(sc: EvalScenario, g: EvalGt, path: String): Boolean =
        if (sc.matchMode() == "harness-contains") path.contains(g.name ?: g.path ?: "", ignoreCase = true)
        else norm(path).equals(norm(g.path ?: ""), ignoreCase = true) || norm(path).endsWith("/" + norm(g.path ?: ""), ignoreCase = true)

    private val collectWords = Regex("수집|저장|입력|등록|전달|받")

    private fun expandedAndScore(g: ProjectGraph, sr: String, seeds: List<String>, rescue: Set<String> = emptySet()): Pair<List<net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.ScoredFile>, String> {
        val s = AgenticSeedSelector.heuristicSeeds(sr, g).copy(seedClasses = seeds)
        val ex = GraphExpander(g, DomainExtractor(g.files), DiscoveryConfig(maxHop = 3)).expand(s, sr)
        val old = System.out
        val baos = ByteArrayOutputStream()
        System.setOut(PrintStream(baos, true, "UTF-8"))
        val r = try { RelevanceScorer(g, 30, 55, rescueInspected = rescue).scoreAndFilter(sr, ex, s) } finally { System.setOut(old) }
        return r to baos.toString("UTF-8")
    }

    @Test
    fun measure() {
        val paths = EvalScenarios.graphPaths()
        Assume.assumeTrue(paths.values.all { File(it).isFile })
        val graphs = HashMap<String, ProjectGraph>()
        fun graphOf(key: String) = graphs.getOrPut(key) { Gson().fromJson(File(paths.getValue(key)).readText(Charsets.UTF_8), ProjectGraph::class.java).normalizeLegacyCollections() }
        val out = StringBuilder()
        val seen = HashSet<String>()
        val scenarios = EvalScenarios.loadAll().filter { seen.add(it.graphKey + "|" + it.sr) }.filter { it.gtOrEmpty().any { g -> g.countsForRecall() } }

        // ── 6b) Seed Direct Dependency 구제 ──
        out.appendLine("=== [6b] Seed Direct Dependency 구제(시나리오별): 구제 수, 같은 도메인 패키지 수, 필수 적중(구제로만 들어온 것) ===")
        out.appendLine("시나리오|구제 수(점수)|같은 패키지/다른 패키지|구제 중 필수 적중(같은/다른)|40점 이하 구제 수")
        var totRescued = 0; var totSame = 0; var totDiff = 0; var gtSame = 0; var gtDiff = 0; var lowCount = 0
        for (sc in scenarios) {
            val g = graphOf(sc.graphKey)
            val seeds = AgenticSeedSelector.heuristicSeeds(sc.sr, g).seedClasses
            val (scored, _) = expandedAndScore(g, sc.sr, seeds)
            val seedDirs = seeds.mapNotNull { c -> g.files.values.firstOrNull { it.className == c }?.path?.substringBeforeLast('/') }.toSet()
            val resc = scored.filter { it.protectionReason == "Seed Direct Dependency" }
            val gts = sc.gtOrEmpty().filter { it.countsForRecall() }
            fun same(p: String) = p.substringBeforeLast('/') in seedDirs
            val rSame = resc.filter { same(it.path) }; val rDiff = resc.filter { !same(it.path) }
            fun gtHits(l: List<net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.ScoredFile>) = gts.count { gt -> l.any { gtMatch(sc, gt, it.path) } }
            val low = resc.count { it.score <= 40 }
            totRescued += resc.size; totSame += rSame.size; totDiff += rDiff.size; gtSame += gtHits(rSame); gtDiff += gtHits(rDiff); lowCount += low
            out.appendLine("${sc.id}|${resc.size}${resc.map { "${it.className}(${it.score})" }}|${rSame.size}/${rDiff.size}|${gtHits(rSame)}/${gtHits(rDiff)}|$low")
        }
        out.appendLine("합계: 구제 $totRescued (같은 패키지 $totSame, 다른 패키지 $totDiff), 구제 중 필수 적중 같은=$gtSame 다른=$gtDiff, 40점 이하 $lowCount")
        out.appendLine("→ '같은 패키지로 한정'하면 빠지는 구제 $totDiff 개 중 필수 적중 손실 $gtDiff 개(이것이 손실, 잡음 감소 $totDiff 개)")

        // ── 6a) 탐색기가 inspect한 컨트롤러 구제(측정 플래그) ──
        out.appendLine("\n=== [6a] inspect 구제안(대리: 시드와 의존 1홉인 컨트롤러 + SR에 수집/저장/입력 단어): 시나리오별 추가 후보와 필수 적중 ===")
        out.appendLine("시나리오|SR 조건|추가 컨트롤러|추가 중 필수 적중|추가 중 잡음")
        var addTot = 0; var addHit = 0
        for (sc in scenarios) {
            val g = graphOf(sc.graphKey)
            val seeds = AgenticSeedSelector.heuristicSeeds(sc.sr, g).seedClasses
            val cond = collectWords.containsMatchIn(sc.sr)
            val ctrls = g.files.values.filter { it.className.endsWith("Controller") }.map { it.className }.toSet()
            val (base, _) = expandedAndScore(g, sc.sr, seeds)
            val (resc, _) = expandedAndScore(g, sc.sr, seeds, if (cond) ctrls else emptySet())
            val added = resc.filter { r -> base.none { it.path == r.path } }
            val gts = sc.gtOrEmpty().filter { it.countsForRecall() }
            val hit = gts.count { gt -> added.any { gtMatch(sc, gt, it.path) } }
            addTot += added.size; addHit += hit
            out.appendLine("${sc.id}|$cond|${added.size}${added.map { it.className }}|$hit|${added.size - added.count { a -> gts.any { gtMatch(sc, it, a.path) } }}")
        }
        out.appendLine("합계: 추가 후보 $addTot 개, 추가 중 필수 적중 $addHit 개, 잡음 ${addTot - addHit}개")

        // 오늘 IDE 시드와 정제문에서 IpsController 점수 분해(진단 로그)
        val root = "C:/Workspace/HC_card_survey_admin/survey_admin"
        val sg = graphOf("survey_admin")
        val ref = "설문 응답 테이블에 이메일 컬럼을 추가하여 수집하고, 관리자 설문 상세 조회 시 응답자 이메일을 앞 2자리와 도메인만 노출되도록 마스킹 처리하여 반환하도록 백엔드를 수정한다."
        val (_, logs) = expandedAndScore(sg, ref, listOf("SurveyServiceImpl", "SurveyDaoImpl", "SurveyDto", "sql_survey.xml"))
        out.appendLine("\n[6a] 오늘 IDE 시드 + 11-03 정제문 점수 분해 진단 로그(IpsController 등):")
        logs.lines().filter { it.contains("SCORE-DIAG") && Regex("IpsController|AddressDao|SurveyDao |SurveyDao,|UserServiceImpl|ClosingLinkDto").containsMatchIn(it) }.forEach { out.appendLine("  $it") }
        val (withRescue, _) = expandedAndScore(sg, ref, listOf("SurveyServiceImpl", "SurveyDaoImpl", "SurveyDto", "sql_survey.xml"), setOf("IpsController"))
        out.appendLine("  inspect 구제(IpsController만) 적용 시 후보: ${withRescue.map { it.className }}")

        // ── 8) 사전 통일(domainNouns) ──
        out.appendLine("\n=== [8] 사전 통일: Stage 0 후보(스캐너 domainNouns false→true) 필수 적중·잡음 ===")
        out.appendLine("시나리오|후보 수 false/true|필수 적중 false/true|잡음 false/true")
        var lostTot = 0
        for (sc in scenarios) {
            val g = graphOf(sc.graphKey)
            val gts = sc.gtOrEmpty().filter { it.countsForRecall() }
            fun cands(flag: Boolean): List<String> = try {
                val old = System.out; System.setOut(PrintStream(ByteArrayOutputStream()))
                try { Stage0ClarificationEngine(Stage0GraphScanner(g, domainNouns = flag), g, null, deferTaskSummary = true, projectRoot = null).initSession(sc.sr).state.items.mapNotNull { (it.hint as? LinkHint.ExistingRef)?.filePath } } finally { System.setOut(old) }
            } catch (e: Throwable) { emptyList() }
            val a = cands(false); val b = cands(true)
            fun hit(l: List<String>) = gts.count { gt -> l.any { gtMatch(sc, gt, it) } }
            fun noise(l: List<String>) = l.count { p -> gts.none { gtMatch(sc, it, p) } }
            lostTot += maxOf(0, hit(a) - hit(b))
            out.appendLine("${sc.id}|${a.size}/${b.size}|${hit(a)}/${hit(b)}|${noise(a)}/${noise(b)}")
        }
        out.appendLine("합계: 사전을 켜서 잃는 필수 적중 $lostTot 개")
        // 오늘 SR의 Stage 0 후보와 스캐너 토큰(왜 설문 백엔드가 빠지는지: Stage0GraphScanner.kt:228-241의 다중 토큰 조건)
        val today = "기존 설문조사 상세 조회 기능에 응답자 이메일 정보도 같이 내려주도록 백엔드를 수정해주세요"
        for (flag in listOf(false, true)) {
            val sc0 = Stage0GraphScanner(sg, domainNouns = flag)
            val toks = sc0.extractTokens(today)
            val st = toks.filter { it.kind == TokenKind.STRUCTURAL }.map { it.value.lowercase() }
            val cc = toks.filter { it.kind == TokenKind.CONCEPTUAL }.map { it.value.lowercase() }
            val generic = setOf("controller", "service", "impl", "dao", "mapper", "dto", "vo", "response", "request", "entity", "repository", "base", "util", "helper", "common", "config", "job", "batch", "list", "get", "set", "save", "update", "delete", "insert", "select", "find", "search", "read", "view", "page", "screen", "manage", "management", "mgr", "mgmt", "admin", "main", "detail", "info", "data", "handle", "process", "execute", "deal", "query")
            val qd = (st + cc).filter { it.length >= 2 && it !in generic && !Regex("[가-힣]+").containsMatchIn(it) }.distinct()
            out.appendLine("[8] domainNouns=$flag 구조 토큰=$st 개념 토큰 영문=${cc.filter { !Regex("[가-힣]").containsMatchIn(it) }} → queryDomainTokens(영문, 일반어 제외)=$qd (0-B 조건: 일치 2개 이상, 또는 1개 + 정확한 클래스명 또는 queryDomainTokens가 1개뿐)")
        }
        // 한글 검색 "설문": search_graph_nodes 1위(AgenticGraphTools 사전 flag)
        for (flag in listOf(false, true)) {
            val tools = AgenticGraphTools(sg, domainNouns = flag)
            for (q in listOf("설문", "설문 응답")) {
                val r = tools.searchGraphNodes(q, null, 5)
                out.appendLine("[8] 검색 \"$q\" domainNouns=$flag: " + r.joinToString { "${it["className"] ?: it["path"]}(${(it["matchScore"] as? Number)?.toInt()})" })
            }
        }
        // 시나리오별 search_graph_nodes 상위 10에 필수 정답이 드는가(SR 전문을 질의로 쓴 대리)
        out.appendLine("시나리오|검색 상위10 필수 적중 false/true|잡음 false/true")
        for (sc in scenarios) {
            val g = graphOf(sc.graphKey)
            val gts = sc.gtOrEmpty().filter { it.countsForRecall() }
            fun top(flag: Boolean) = AgenticGraphTools(g, domainNouns = flag).searchGraphNodes(sc.sr, null, 10).mapNotNull { (it["path"] as? String) ?: g.files.values.firstOrNull { f -> f.className == it["className"] }?.path }
            val a = top(false); val b = top(true)
            fun hit(l: List<String>) = gts.count { gt -> l.any { gtMatch(sc, gt, it) } }
            fun noise(l: List<String>) = l.count { p -> gts.none { gtMatch(sc, it, p) } }
            out.appendLine("${sc.id}|${hit(a)}/${hit(b)}|${noise(a)}/${noise(b)}")
        }

        // ── GUARD 분포: 정답 파일(필수)을 requiredFiles로 넣었을 때 REQUIRED/RECOMMENDED 위반 ──
        out.appendLine("\n=== [GUARD] 24시나리오 정답 필수 파일 기준 역할 앵커 위반(REQUIRED)과 동반자 위반(MANDATORY/RECOMMENDED) ===")
        out.appendLine("시나리오|프레임워크|역할 위반(앵커)|MANDATORY 동반자 위반|RECOMMENDED 동반자 미충족")
        var daoIfViol = 0; var svcIfViol = 0; var scCount = 0
        for (sc in scenarios) {
            val g = graphOf(sc.graphKey)
            val gtPaths = sc.gtOrEmpty().filter { it.countsForRecall() }.mapNotNull { gt -> (g.files.keys + g.resourceNodes.map { it.path }).firstOrNull { gtMatch(sc, gt, it) } }.toSet()
            if (gtPaths.isEmpty()) continue
            val hasService = gtPaths.any { it.contains("Service") }
            val hasData = gtPaths.any { it.contains("Dao") || it.contains("Repository") || it.endsWith("xml") }
            val hasUi = gtPaths.any { it.endsWith(".jsp") || it.endsWith(".html") || it.endsWith(".js") }
            val facts = SrFacts(hasUserAction = hasUi, touchesUi = hasUi, hasBusinessLogic = hasService, readsOrWritesData = hasData, addsNewMethod = false)
            val fw = g.frameworkDetection?.userOverride ?: g.frameworkType
            val rep = try { CompletenessEngine(ProjectGraphAdapter(g)).evaluate(fw, gtPaths, facts) } catch (e: Throwable) { null } ?: continue
            scCount++
            val roleV = rep.roleViolations.map { "${it.role}:${it.anchorKind}" }
            if (roleV.any { it.endsWith("DAO_INTERFACE") }) daoIfViol++
            if (roleV.any { it.endsWith("SERVICE_INTERFACE") }) svcIfViol++
            val comp = rep.companionFindings
            out.appendLine("${sc.id}|$fw|$roleV|${rep.companionViolations.size}|${comp.count { it.pairing.name == "RECOMMENDED" && !it.satisfied }}")
        }
        out.appendLine("합계: 시나리오 $scCount 개 중 DAO_INTERFACE 앵커 위반 $daoIfViol 개, SERVICE_INTERFACE 앵커 위반 $svcIfViol 개(정답 파일만으로 평가, addsNewMethod=false)")
        // 오늘 SR: 필수만 / 필수+DaoImpl / 필수+Dao+DaoImpl
        val base = setOf("src/main/java/net/infobank/iss/sql/mysql/sql_survey.xml", "src/main/java/net/infobank/iss/survey/dto/SurveyDto.java", "src/main/java/net/infobank/iss/survey/service/SurveyServiceImpl.java")
        val dao = "src/main/java/net/infobank/iss/survey/dao/SurveyDao.java"; val daoImpl = "src/main/java/net/infobank/iss/survey/dao/SurveyDaoImpl.java"; val svc = "src/main/java/net/infobank/iss/survey/service/SurveyService.java"
        val factsToday = SrFacts(hasUserAction = false, touchesUi = false, hasBusinessLogic = true, readsOrWritesData = true, addsNewMethod = false)
        for ((label, files) in listOf("필수 3개" to base, "필수 + DaoImpl(IDE 11-03 최종)" to base + daoImpl, "필수 + Dao + DaoImpl" to base + dao + daoImpl, "필수 + Dao + DaoImpl + Service" to base + dao + daoImpl + svc)) {
            val rep = CompletenessEngine(ProjectGraphAdapter(sg)).evaluate(sg.frameworkDetection?.userOverride ?: sg.frameworkType, files, factsToday)
            out.appendLine("[GUARD] 오늘 SR $label: 역할 위반=${rep.roleViolations.map { "${it.role}:${it.anchorKind}" }} 동반자 위반=${rep.companionViolations.map { "${it.companionKind}" }}")
        }
        File("build/b82_measure.txt").writeText(out.toString(), Charsets.UTF_8)
        println(out)
    }
}
