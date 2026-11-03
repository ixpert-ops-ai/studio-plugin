package net.ib.ixpert.ops.wuwagent.agent.eval

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.agent.clarify.Stage0ClarificationEngine
import net.ib.ixpert.ops.wuwagent.agent.clarify.Stage0GraphScanner
import net.ib.ixpert.ops.wuwagent.client.OpenAIClient
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

/**
 * B-83 A, C, D 측정 전용(운영 연결 없음). d는 서버 없이 돌고, a·c는 -DrunLiveLlmTests=true일 때만 실서버를 쓴다. 결과는 build/b83_*.txt.
 */
class MeasureB83Test {
    private val root = File("C:/Workspace/HC_card_survey_admin/survey_admin")
    private val sr = "설문 응답 테이블에 이메일 컬럼을 추가하여 응답자 이메일을 수집하고, 관리자 설문 상세 조회 시 해당 이메일을 앞 2자리와 도메인만 노출되도록 마스킹 처리하여 백엔드 응답에 포함하도록 수정한다."
    private val original = "기존 설문조사 상세 조회 기능에 응답자 이메일 정보도 같이 내려주도록 백엔드를 수정해주세요"
    private val answer = "응답자 이메일은 아직 수집하지 않아. 설문 응답 테이블에 이메일 컬럼을 새로 추가해서 저장하는 방향으로 해줘. 관리자 설문 상세 화면용이고, 이메일은 앞 2자리와 도메인만 보이게 마스킹해줘."

    private fun live() = System.getProperty("runLiveLlmTests") == "true" || System.getenv("RUN_LIVE_LLM_TESTS") == "true"

    private fun graph(): ProjectGraph {
        Assume.assumeTrue(File(root, ".meta/project-graph.json").isFile)
        return Gson().fromJson(File(root, ".meta/project-graph.json").readText(Charsets.UTF_8), ProjectGraph::class.java).normalizeLegacyCollections()
    }

    /** D: comment 점수(RelevanceScorer.kt:111-117)가 어떤 SR 단어(명사·동사)와 한글 주석이 맞아서 20점이 되는지. 주석 본문은 남기지 않고 일치한 단어와 주석 수만 센다. */
    @Test
    fun d_comment_score_basis() {
        val g = graph()
        // RelevanceScorer.extractKeywords(:545-554)의 명사·동사 추출을 그대로 옮겼다(조사 제거가 단어를 깨뜨리는지 보기 위해).
        val nouns = mutableListOf<String>(); val verbs = mutableListOf<String>()
        for (word in sr.split(Regex("\\s+"))) {
            val clean = word.replace(Regex("[^가-힣a-zA-Z0-9]"), "")
            if (clean.length < 2) continue
            if (clean.endsWith("한다") || clean.endsWith("해라") || clean.endsWith("추가") || clean.endsWith("수정") || clean.endsWith("삭제")) verbs.add(clean.replace("한다", "").replace("해라", ""))
            else nouns.add(clean.replace("을", "").replace("를", "").replace("이", "").replace("가", "").replace("은", "").replace("는", ""))
        }
        val out = StringBuilder("SR(${sr.length}자) 명사=$nouns\n동사=$verbs\n")
        out.appendLine("원 단어 → 조사 제거 뒤 변형: " + sr.split(Regex("\\s+")).map { it.replace(Regex("[^가-힣a-zA-Z0-9]"), "") }.filter { it.length >= 2 }
            .mapNotNull { w -> val n = w.replace("을", "").replace("를", "").replace("이", "").replace("가", "").replace("은", "").replace("는", ""); if (n != w) "$w→$n" else null })
        for (name in listOf("ReviewDto", "ClosingLinkDto", "UserServiceImpl", "SurveyDao", "SurveyService", "SurveyDto", "SurveyServiceImpl")) {
            val node = g.files.values.firstOrNull { it.className == name } ?: continue
            val byNoun = nouns.distinct().associateWith { n -> node.koreanComments.count { it.contains(n) } }.filter { it.value > 0 }
            val byVerb = verbs.distinct().associateWith { v -> node.koreanComments.count { it.contains(v) } }.filter { it.value > 0 }
            out.appendLine("$name: 주석 ${node.koreanComments.size}개, 일치 명사(주석 수)=$byNoun, 일치 동사=$byVerb, localName=${node.localName}")
        }
        // 일치 단어별로 그래프 전체에서 몇 개 노드가 이 단어로 20점을 받는지(잡음 규모)
        for (n in (nouns + verbs).distinct()) {
            val hit = g.files.values.count { f -> f.koreanComments.any { it.contains(n) } }
            if (hit > 0) out.appendLine("  단어 '$n': 한글 주석이 맞는 노드 ${hit}개 / 전체 ${g.files.size}개")
        }
        File("build/b83_comment.txt").writeText(out.toString(), Charsets.UTF_8); println(out)
    }

    /** 7(D) 측정: 주석 점수 idf 가중과 조사 제거 수정을 분리해 15쌍의 Stage 1 후보(상위 30)에서 필수 적중·잡음(필수 아닌 후보 수)을 비교한다. 구현은 보류(스위치는 기본 꺼짐). */
    @Test
    fun d_comment_variants() {
        val paths = EvalScenarios.graphPaths()
        Assume.assumeTrue(paths.values.all { File(it).isFile })
        val graphs = HashMap<String, ProjectGraph>()
        fun graphOf(key: String) = graphs.getOrPut(key) { Gson().fromJson(File(paths.getValue(key)).readText(Charsets.UTF_8), ProjectGraph::class.java).normalizeLegacyCollections() }
        val variants = listOf("기준" to (false to false), "idf만" to (true to false), "조사만" to (false to true), "idf+조사" to (true to true))
        val seen = HashSet<String>()
        val out = StringBuilder("시나리오|필수 수|" + variants.joinToString("|") { "${it.first}(적중/잡음/후보)" } + "\n")
        val tot = Array(variants.size) { intArrayOf(0, 0, 0) }
        for (sc in EvalScenarios.loadAll().filter { seen.add(it.graphKey + "|" + it.sr) }) {
            val gts = sc.gtOrEmpty().filter { it.countsForRecall() }
            if (gts.isEmpty()) continue
            val g = graphOf(sc.graphKey)
            val seed = AgenticSeedSelector.heuristicSeeds(sc.sr, g)
            val ex = GraphExpander(g, DomainExtractor(g.files), DiscoveryConfig(maxHop = 3)).expand(seed, sc.sr)
            fun match(gt: EvalGt, p: String) = if (sc.matchMode() == "harness-contains") p.contains(gt.name ?: gt.path ?: "", ignoreCase = true) else p.replace('\\', '/').endsWith((gt.path ?: "").replace('\\', '/'))
            val cells = variants.mapIndexed { i, (_, f) ->
                val old = System.out
                System.setOut(PrintStream(ByteArrayOutputStream()))
                val r = try { RelevanceScorer(g, 30, 55, commentIdf = f.first, fixParticles = f.second).scoreAndFilter(sc.sr, ex, seed) } finally { System.setOut(old) }
                val hit = gts.count { gt -> r.any { match(gt, it.path) } }
                val noise = r.count { c -> gts.none { match(it, c.path) } }
                tot[i][0] += hit; tot[i][1] += noise; tot[i][2] += r.size
                "$hit/$noise/${r.size}"
            }
            out.appendLine("${sc.id}|${gts.size}|" + cells.joinToString("|"))
        }
        out.appendLine("합계|-|" + tot.joinToString("|") { "${it[0]}/${it[1]}/${it[2]}" })
        // 오늘 IDE 시드(탐색기 확정)와 오늘 정제문: 잡음 후보(ReviewDto, ClosingLinkDto)의 점수
        val sg = graphOf("survey_admin")
        val seed = AgenticSeedSelector.heuristicSeeds(sr, sg).copy(seedClasses = listOf("SurveyServiceImpl", "SurveyDto", "SurveyDao", "SurveyDaoImpl"))
        val ex = GraphExpander(sg, DomainExtractor(sg.files), DiscoveryConfig(maxHop = 3)).expand(seed, sr)
        out.appendLine("\n오늘 SR(탐색기 시드 4개) 후보 점수 변화(ReviewDto, ClosingLinkDto, UserServiceImpl, SurveyDao):")
        for ((label, f) in variants) {
            val old = System.out
            val baos = ByteArrayOutputStream()
            System.setOut(PrintStream(baos, true, "UTF-8"))
            try { RelevanceScorer(sg, 30, 55, commentIdf = f.first, fixParticles = f.second).scoreAndFilter(sr, ex, seed) } finally { System.setOut(old) }
            val scores = baos.toString("UTF-8").lines().filter { it.startsWith("[RelevanceScorer] Node: ") }.mapNotNull { l -> Regex("Node: (\\w+), Score: (\\d+)").find(l)?.let { it.groupValues[1] to it.groupValues[2] } }.toMap()
            out.appendLine("  $label: " + listOf("ReviewDto", "ClosingLinkDto", "UserServiceImpl", "SurveyDao").joinToString(", ") { "$it=${scores[it]}" })
        }
        File("build/b83_comment_variants.txt").writeText(out.toString(), Charsets.UTF_8); println(out)
    }

    /** 2(A): 계약 쌍 매퍼 추가가 24시나리오(15쌍)에서 더한 후보 수, 그중 필수 정답 적중, 30개 한도로 밀려난 후보 수. */
    @Test
    fun mapper_pair_effect() {
        val paths = EvalScenarios.graphPaths()
        Assume.assumeTrue(paths.values.all { File(it).isFile })
        val graphs = HashMap<String, ProjectGraph>()
        fun graphOf(key: String) = graphs.getOrPut(key) { Gson().fromJson(File(paths.getValue(key)).readText(Charsets.UTF_8), ProjectGraph::class.java).normalizeLegacyCollections() }
        val seen = HashSet<String>()
        val out = StringBuilder("시나리오|더한 매퍼 수|더한 것 중 필수 적중|잡음(필수 아님)|30개 한도로 밀려난 후보\n")
        var added = 0; var hits = 0; var displaced = 0
        for (sc in EvalScenarios.loadAll().filter { seen.add(it.graphKey + "|" + it.sr) }) {
            val gts = sc.gtOrEmpty().filter { it.countsForRecall() }
            if (gts.isEmpty()) continue
            val g = graphOf(sc.graphKey)
            val seed = AgenticSeedSelector.heuristicSeeds(sc.sr, g)
            val ex = GraphExpander(g, DomainExtractor(g.files), DiscoveryConfig(maxHop = 3)).expand(seed, sc.sr)
            val old = System.out
            System.setOut(PrintStream(ByteArrayOutputStream()))
            val (r, all) = try { RelevanceScorer(g, 30, 55).scoreAndFilter(sc.sr, ex, seed) to RelevanceScorer(g, 1000, 55).scoreAndFilter(sc.sr, ex, seed) } finally { System.setOut(old) }
            val adds = r.filter { it.discoveryReason.contains("CONTRACT_PAIR [Mapper") }
            val addPaths = adds.map { it.path }.toSet()
            val base30 = all.filter { it.path !in addPaths }.take(30)
            val dis = base30.count { b -> r.none { it.path == b.path } }
            fun match(gt: EvalGt, p: String) = if (sc.matchMode() == "harness-contains") p.contains(gt.name ?: gt.path ?: "", ignoreCase = true) else p.replace('\\', '/').endsWith((gt.path ?: "").replace('\\', '/'))
            val hit = gts.count { gt -> adds.any { match(gt, it.path) } }
            added += adds.size; hits += hit; displaced += dis
            out.appendLine("${sc.id}|${adds.size}${adds.map { it.className }}|$hit|${adds.size - adds.count { a -> gts.any { match(it, a.path) } }}|$dis")
        }
        out.appendLine("합계: 더한 매퍼 $added 개, 필수 적중 $hits 개, 한도로 밀려난 후보 $displaced 개")
        File("build/b83_mapper_pair_effect.txt").writeText(out.toString(), Charsets.UTF_8); println(out)
    }

    /** 0-②: 오늘 SR의 시드와 각 시드의 근거 종류(이름 일치 / 한글명 / 주석). 한글 근거 전용 노드는 이름 근거가 없는 시드다. */
    @Test
    fun seed_evidence() {
        val g = graph()
        val seeds = net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.AgenticSeedSelector.heuristicSeeds(original, g).seedClasses
        val ko = Regex("[가-힣]{2,}").findAll(original).map { it.value }.filter { it !in setOf("추가", "생성", "신규", "삭제", "제거", "수정", "변경", "기능", "항목", "목록", "조회", "화면", "출력", "관련", "처리", "동작", "적용", "로직", "기반", "부분") }.distinct().toList()
        val out = StringBuilder("시드 ${seeds.size}개: $seeds (그래프 순서 앞 5개 규칙: 코드에서 제거됨)\n")
        for (s in seeds) {
            val n = g.files.values.firstOrNull { it.className == s } ?: continue
            val local = ko.filter { n.localName?.contains(it) == true }
            val comment = ko.filter { t -> n.koreanComments.any { it.contains(t) } }
            val nameHit = n.className.contains("survey", true)
            val kinds = listOfNotNull("이름(survey 번역)".takeIf { nameHit }, "한글명 $local".takeIf { local.isNotEmpty() }, "주석 $comment".takeIf { comment.isNotEmpty() })
            out.appendLine("  $s: 근거=$kinds ${if (!nameHit) "← 한글 근거 전용" else ""}")
        }
        File("build/b83_seed_evidence.txt").writeText(out.toString(), Charsets.UTF_8); println(out)
    }

    /** vLLM /metrics에서 실행 중·대기 중 요청 수를 한 번 읽는다(서버 주소는 -Dvllm.base, 기본은 로그의 서버). 실패하면 사유 문자열. */
    private fun vllmQueue(): String = try {
        val base = (System.getProperty("vllm.base") ?: "http://vllm.ixpertops.cloud").trimEnd('/').substringBefore("/v1")
        val text = java.net.URL("$base/metrics").openConnection().apply { connectTimeout = 5000; readTimeout = 5000 }.getInputStream().bufferedReader().readText()
        text.lines().filter { it.startsWith("vllm:num_requests_running") || it.startsWith("vllm:num_requests_waiting") }.joinToString(" | ") { it.take(120) }.ifBlank { "지표 없음" }
    } catch (e: Exception) { "읽기 실패(${e.javaClass.simpleName})" }

    /** C: 확정 시 두 LLM 호출(정제문, 제약 추출)의 토큰 사용량과 시간. 실서버. 확정 대기 전/후와 서버 대기열(/metrics) 기록. */
    @Test
    fun c_confirm_calls() {
        Assume.assumeTrue(live())
        val g = graph()
        val old = System.out
        val baos = ByteArrayOutputStream()
        System.setOut(PrintStream(baos, true, "UTF-8"))
        val t0 = System.currentTimeMillis()
        var confirmMs = 0L
        var queueBefore = ""; var queueAfter = ""; var hits = 0
        try {
            val eng = Stage0ClarificationEngine(Stage0GraphScanner(g), g, OpenAIClient(), deferTaskSummary = true, projectRoot = root)
            val st0 = eng.initSession(original).state
            val turn = eng.processTurn(st0, Stage0ClarificationEngine.UserInput(userStatement = answer))
            queueBefore = vllmQueue()
            val t1 = System.currentTimeMillis()
            eng.buildClarifyIntentChecked(turn.state)
            confirmMs = System.currentTimeMillis() - t1
            queueAfter = vllmQueue()
            hits = eng.extractionCacheHits
        } finally { System.setOut(old) }
        val lines = baos.toString("UTF-8").lines().filter { it.contains("[LlmCall]") || it.contains("[LlmUsage]") || it.contains("OpenAI API Call") }
        val out = StringBuilder("확정(buildClarifyIntentChecked) 소요 ${confirmMs}ms(캐시 재사용 $hits 회), 전체 ${System.currentTimeMillis() - t0}ms\nvLLM 대기열 확정 직전: $queueBefore\nvLLM 대기열 확정 직후: $queueAfter\n")
        lines.forEach { out.appendLine(it.take(400)) }
        File("build/b83_confirm_calls.txt").writeText(out.toString(), Charsets.UTF_8); println(out)
    }

    /** A: 탐색기를 같은 SR로 반복 실행해 시드 구성, 턴 수, 매퍼 포함 여부, Stage 1 후보의 매퍼 처리를 표로 남긴다. 실서버, 순차 실행. */
    @Test
    fun a_explorer_runs() {
        Assume.assumeTrue(live())
        val g = graph()
        val runs = System.getProperty("b83.runs")?.toIntOrNull() ?: 10
        val out = StringBuilder("# 탐색기 ${runs}회 (SR ${sr.length}자)\n| 회차 | 시간(초) | 턴 수 | 시드 | 시드에 매퍼 | 시드에 Dao/DaoImpl | Stage 1 후보에 sql_survey.xml | 매퍼 점수·보호 |\n|---|---|---|---|---|---|---|---|\n")
        var claimTotal = 0
        var saveClaimTotal = 0
        for (run in 1..runs) {
            val old = System.out
            val baos = ByteArrayOutputStream()
            System.setOut(PrintStream(baos, true, "UTF-8"))
            val t0 = System.currentTimeMillis()
            // IDE 경로(AdaptiveFileDiscovery.kt: srText = "$primaryReq\n$secondaryReq")와 같게 SR 끝에 줄바꿈이 붙는다(-Db83.trailing=false로 끈다).
            val srText = if (System.getProperty("b83.trailing") == "false") sr else "$sr\n"
            val facts = if (System.getProperty("b83.facts") == "true") net.ib.ixpert.ops.wuwagent.agent.ClaimedFieldCheck.explorerFacts(original, g, root, net.ib.ixpert.ops.wuwagent.agent.ClaimedFieldCheck.answersOf(listOf("응답자 이메일 출처: $answer"))) else null
            if (run == 1 && facts != null) File("build/b83_facts_block.txt").writeText(facts, Charsets.UTF_8)
            val seed = try { AgenticSeedSelector(OpenAIClient(), root.path, null, facts).selectSeeds(srText, g) } finally { System.setOut(old) }
            val ms = System.currentTimeMillis() - t0
            val turns = baos.toString("UTF-8").lines().count { it.contains("=== TURN") }
            // 잘못된 단정(Turn 3 같은 것): 탐색기 메시지나 확정 사유에서 이메일이 이미 있다/포함된다고 말하는 줄(없다·추가·신설·수집을 함께 말하면 제외)
            val claimRe = Regex("이메일.*(이미|포함|있|조회)")
            val negRe = Regex("없|추가|신설|새로|수집|마스킹")
            val claims = baos.toString("UTF-8").lines().filter { (it.contains("Message:") || it.contains("rationale")) && claimRe.containsMatchIn(it) && !negRe.containsMatchIn(it) }
            claimTotal += claims.size
            // 응답 저장 단정: 서비스·DAO가 응답을 저장·처리한다고 말하는 줄(없다·않다·발견되지·추가·신설을 함께 말하면 제외)
            val saveClaims = baos.toString("UTF-8").lines().filter { (it.contains("Message:") || it.contains("rationale")) && Regex("응답 (저장|테이블).*(처리|로직|담당|저장/조회)").containsMatchIn(it) && !Regex("없|않|발견되지|확인되지|추가|신설|새로").containsMatchIn(it) }
            saveClaimTotal += saveClaims.size
            if (claims.isNotEmpty()) File("build/b83_explorer_claims_run$run.txt").writeText(claims.joinToString("\n") { it.take(300) }, Charsets.UTF_8)
            if (run == 1) File("build/b83_explorer_run1.log").writeText(baos.toString("UTF-8"), Charsets.UTF_8) // IDE 로그와 줄 단위로 비교하기 위한 첫 회차 전문
            val ex = GraphExpander(g, DomainExtractor(g.files), DiscoveryConfig(maxHop = 3)).expand(seed, sr)
            val scored = try { System.setOut(PrintStream(ByteArrayOutputStream())); RelevanceScorer(g, 30, 55).scoreAndFilter(sr, ex, seed) } finally { System.setOut(old) }
            val mapper = scored.firstOrNull { it.className == "sql_survey.xml" }
            out.appendLine("| $run | ${ms / 1000} | $turns | ${seed.seedClasses} | ${"sql_survey.xml" in seed.seedClasses} | ${seed.seedClasses.filter { it == "SurveyDao" || it == "SurveyDaoImpl" }} | ${mapper != null} | ${mapper?.let { "${it.score}/${it.protectionReason}" } ?: "-"} |")
            File("build/b83_explorer_runs.md").writeText(out.toString() + "\n잘못된 단정(이메일이 이미 있다/포함된다는 줄) 누계: $claimTotal 줄, 응답 저장 단정 누계: $saveClaimTotal 줄 (facts=${System.getProperty("b83.facts") == "true"}, trailing=${System.getProperty("b83.trailing") != "false"})\n", Charsets.UTF_8)
        }
        out.appendLine("\n잘못된 단정 누계: $claimTotal 줄")
        println(out)
    }
}
