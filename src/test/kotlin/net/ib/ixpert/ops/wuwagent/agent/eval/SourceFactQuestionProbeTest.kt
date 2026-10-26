package net.ib.ixpert.ops.wuwagent.agent.eval

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.agent.clarify.Stage0GraphScanner
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.LinkHint
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * B-73 3단계 실험: 소스 사실 기반 질문 시제품(운영 경로 연결 금지, LLM 호출 없음, 로컬 소스가 없으면 건너뜀).
 * R-EMAIL(survey_admin, IDE에서 쓴 SR) 합격 기준:
 *  (a) 응답자 이메일 출처를 묻는 질문 1개
 *  (b) 근거로 sql_survey.xml의 EMAIL이 관리자(ISSW_ADMIN_USER) 쿼리에만 있다는 사실을 인용
 *  (c) 정답 출처는 판정하지 않는다
 */
class SourceFactQuestionProbeTest {

    private val srEmail = "기존 설문조사 상세 조회 기능에 응답자 이메일 정보도 같이 내려주도록 백엔드를 수정해주세요"
    private val surveyRoot = "C:/Workspace/HC_card_survey_admin/survey_admin"

    private fun load(path: String): ProjectGraph? {
        val f = File(path)
        return if (f.isFile) Gson().fromJson(f.readText(Charsets.UTF_8), ProjectGraph::class.java).normalizeLegacyCollections() else null
    }

    private fun pathOfClass(g: ProjectGraph, cls: String) = g.files.values.first { it.className == cls }.path

    @Test
    fun r_email_응답자_이메일_출처_질문이_한_개_나오고_관리자_쿼리_근거를_인용한다() {
        val g = load("$surveyRoot/.meta/project-graph.json")
        Assume.assumeNotNull(g)
        g!!
        // 시드: IDE 재테스트의 Stage 1 확정 시드(SurveyServiceImpl, SurveyDto, SurveyDaoImpl)
        val seeds = listOf("SurveyServiceImpl", "SurveyDto", "SurveyDaoImpl").map { pathOfClass(g, it) }
        val scope = SourceFactQuestionProbe.scopeOf(g, seeds)
        val qs = SourceFactQuestionProbe.questionsFor(srEmail, g, surveyRoot, scope)
        println("[R-EMAIL] 범위 파일 ${scope.files.size}개, 도메인 토큰 ${scope.domainTokens}")
        qs.forEach { println("[R-EMAIL] ${it.question}"); it.evidence.forEach { e -> println("    ${e.cite()}  ${e.note}  | ${e.text.take(60)}") } }

        // 참고(기록용, 합격 기준 아님): 시드를 clarify 시점에 쓸 수 있는 Stage 0 후보로 바꾸면 범위가 어떻게 달라지는가
        val scanner = Stage0GraphScanner(g)
        val stage0 = scanner.rescanUnverified(scanner.extractTokens(srEmail), emptyList())
            .mapNotNull { (it.hint as? LinkHint.ExistingRef)?.filePath }.filter { g.files.containsKey(it) }
        val qs0 = SourceFactQuestionProbe.questionsFor(srEmail, g, surveyRoot, SourceFactQuestionProbe.scopeOf(g, stage0))
        println("[R-EMAIL/Stage0 시드] 범위 파일 ${SourceFactQuestionProbe.scopeOf(g, stage0).files.size}개, 질문 ${qs0.size}개")
        File("build/b73_remail_stage0.txt").writeText("Stage 0 후보 ${stage0.size}개 시드: 질문 ${qs0.size}개 ${qs0.joinToString { it.question.take(80) }}", Charsets.UTF_8)

        // (a) 질문 1개, 응답자 이메일 출처, 근거 등급은 ✓ 확인(범위 파일을 모두 읽었다)
        assertEquals(1, qs.size)
        val q = qs.single()
        assertEquals("이메일", q.noun)
        assertEquals("응답자", q.subject)
        assertEquals(SourceFactQuestionProbe.Grade.CONFIRMED, q.grade)
        assertEquals("범위 파일을 모두 읽었다", 0, q.scopeFilesUnread)
        assertTrue(q.question.contains("응답자 이메일"))
        assertTrue(q.question.contains("어디서 가져올까요"))
        // (b) 근거: sql_survey.xml의 EMAIL 줄이 모두 ISSW_ADMIN_USER 문장이다
        assertTrue(q.evidence.isNotEmpty())
        assertTrue(q.evidence.all { it.file.endsWith("sql_survey.xml") })
        assertTrue(q.evidence.all { it.note.contains("ISSW_ADMIN_USER") })
        assertEquals("EMAIL 줄: selectUser(주석 처리) 1, insertUser 2, updateUser 2", listOf(670, 841, 857, 888, 889), q.evidence.map { it.line })
        // 화면 스크립트의 같은 명사는 업무를 가리지 못해 근거에서 빼고 추정 참고로만 알린다
        assertTrue(q.unclassified.isNotEmpty() && q.unclassified.all { it.file.endsWith(".js") })
        assertTrue(q.question.contains("참고, 추정"))
        assertTrue("주석 처리된 selectUser 표시", q.evidence.first { it.line == 670 }.note.contains("주석 처리됨"))
        assertEquals("도메인(설문) 쿼리에는 이메일이 없다", 0, q.inDomainHitCount)
        assertTrue(q.question.contains("sql_survey.xml:670"))
        // 파일 목록(후보 파일 이름)은 질문 본문에 넣지 않는다: 근거 인용(file:line) 외에는 파일 이름이 없다
        val bodyWithoutCites = q.question.substringBefore("[근거:")
        assertFalse(bodyWithoutCites, bodyWithoutCites.contains(".java") || bodyWithoutCites.contains(".xml") || bodyWithoutCites.contains("Impl"))
        // (c) 정답 출처는 판정하지 않는다: 선택지에 "잘 모르겠음"이 있고 단정 표현이 없다
        assertTrue(q.question.contains("(다) 잘 모르겠음"))
        assertFalse(q.question.contains("가져옵니다") || q.question.contains("가져와야 합니다") || q.question.contains("주소록"))
    }

    @Test
    fun 기존_시나리오에_돌려_질문_수와_거짓_확인_건수를_기록한다() {
        val surveyGraph = load("$surveyRoot/.meta/project-graph.json")
        val mmGraph = load("C:/Workspace/member-market/.meta/project-graph.json")
        Assume.assumeTrue(surveyGraph != null && mmGraph != null)
        System.setProperty("eval.includeHoldout", "true")
        val roots = mapOf("survey_admin" to surveyRoot, "member_market" to "C:/Workspace/member-market")
        val graphs = mapOf("survey_admin" to surveyGraph!!, "member_market" to mmGraph!!)
        val scenarios = EvalScenarios.loadAll().filter { it.graphKey in graphs.keys }
        val out = StringBuilder()
        out.appendLine("시나리오|그래프|SR의 데이터 명사|방식|질문 수|✓ 확인 질문|추정 질문|거짓 확인(정답 파일에 그 명사가 활성 줄로 있음)|질문")
        var asked = 0; var confirmed = 0; var estimated = 0; var falseConfirmed = 0; var withNouns = 0
        for (sc in scenarios) {
            val g = graphs[sc.graphKey]!!
            val root = roots[sc.graphKey]!!
            val nouns = SourceFactQuestionProbe.dataNounsIn(sc.sr).map { it.second.korean }
            if (nouns.isNotEmpty()) withNouns++
            val gtPaths = sc.gtOrEmpty().filter { it.countsForRecall() }.mapNotNull { gt ->
                if (sc.matchMode() == "path") gt.path?.takeIf { g.files.containsKey(it) || g.resourceNodes.any { r -> r.path == it } }
                else g.files.keys.firstOrNull { p -> gt.name != null && p.lowercase().contains(gt.name.lowercase()) }
            }
            val scanner = Stage0GraphScanner(g)
            val stage0 = scanner.rescanUnverified(scanner.extractTokens(sc.sr), emptyList())
                .mapNotNull { (it.hint as? LinkHint.ExistingRef)?.filePath }.filter { g.files.containsKey(it) }.take(8)
            val oracleSeeds = gtPaths.filter { g.files.containsKey(it) }
            for ((mode, seeds) in listOf("정답 시드" to oracleSeeds, "Stage 0 후보" to stage0)) {
                if (seeds.isEmpty()) { out.appendLine("${sc.id}|${sc.graphKey}|$nouns|$mode|시드 없음|0|0|0|"); continue }
                val scope = SourceFactQuestionProbe.scopeOf(g, seeds)
                val qs = SourceFactQuestionProbe.questionsFor(sc.sr, g, root, scope)
                var fc = 0
                for (q in qs.filter { it.grade == SourceFactQuestionProbe.Grade.CONFIRMED }) {
                    val noun = SourceFactQuestionProbe.dataNounsIn(sc.sr).first { it.second.korean == q.noun }.second
                    val gtHas = gtPaths.any { p ->
                        val f = File(root, p)
                        f.isFile && f.readLines(Charsets.UTF_8).any { l -> noun.matches(l) }
                    }
                    if (gtHas) fc++
                }
                asked += qs.size; confirmed += qs.count { it.grade == SourceFactQuestionProbe.Grade.CONFIRMED }
                estimated += qs.count { it.grade == SourceFactQuestionProbe.Grade.ESTIMATED }; falseConfirmed += fc
                out.appendLine("${sc.id}|${sc.graphKey}|$nouns|$mode|${qs.size}|${qs.count { it.grade == SourceFactQuestionProbe.Grade.CONFIRMED }}|${qs.count { it.grade == SourceFactQuestionProbe.Grade.ESTIMATED }}|$fc|${qs.joinToString(" ## ") { it.question.take(120) }}")
            }
        }
        out.appendLine("합계|시나리오 ${scenarios.size}개(데이터 명사 있는 SR ${withNouns}개)|||질문 $asked|확인 $confirmed|추정 $estimated|거짓 확인 $falseConfirmed|")
        File("build/b73_probe_scenarios.txt").writeText(out.toString(), Charsets.UTF_8)
        println(out)
    }

    /** 반사실 점검: 기존 SR에는 데이터 명사가 거의 없어(위) 사전의 명사 6개를 각 시나리오 범위에 모두 물어 질문 수와 거짓 확인을 센다. */
    @Test
    fun 사전_명사_전체를_기존_시나리오_범위에_물어_거짓_확인을_센다() {
        val surveyGraph = load("$surveyRoot/.meta/project-graph.json")
        val mmGraph = load("C:/Workspace/member-market/.meta/project-graph.json")
        Assume.assumeTrue(surveyGraph != null && mmGraph != null)
        System.setProperty("eval.includeHoldout", "true")
        val roots = mapOf("survey_admin" to surveyRoot, "member_market" to "C:/Workspace/member-market")
        val graphs = mapOf("survey_admin" to surveyGraph!!, "member_market" to mmGraph!!)
        val scenarios = EvalScenarios.loadAll().filter { it.graphKey in graphs.keys }
        val nouns = listOf("이메일", "전화번호", "생년월일", "비밀번호", "계좌번호", "카드번호")
        val out = StringBuilder()
        out.appendLine("방식|시나리오 범위 수|물은 쌍 수|질문 수|확인 질문|추정 질문|거짓 확인")
        for (mode in listOf("정답 시드", "Stage 0 후보")) {
            var pairs = 0; var asked = 0; var conf = 0; var est = 0; var falseConf = 0; var scopes = 0
            for (sc in scenarios) {
                val g = graphs[sc.graphKey]!!; val root = roots[sc.graphKey]!!
                val gtPaths = sc.gtOrEmpty().filter { it.countsForRecall() }.mapNotNull { gt ->
                    if (sc.matchMode() == "path") gt.path?.takeIf { g.files.containsKey(it) || g.resourceNodes.any { r -> r.path == it } }
                    else g.files.keys.firstOrNull { p -> gt.name != null && p.lowercase().contains(gt.name.lowercase()) }
                }
                val scanner = Stage0GraphScanner(g)
                val stage0 = scanner.rescanUnverified(scanner.extractTokens(sc.sr), emptyList())
                    .mapNotNull { (it.hint as? LinkHint.ExistingRef)?.filePath }.filter { g.files.containsKey(it) }.take(8)
                val seeds = if (mode == "정답 시드") gtPaths.filter { g.files.containsKey(it) } else stage0
                if (seeds.isEmpty()) continue
                scopes++
                val scope = SourceFactQuestionProbe.scopeOf(g, seeds)
                for (n in nouns) {
                    pairs++
                    val qs = SourceFactQuestionProbe.questionsFor("대상 " + n + " 정보를 조회", g, root, scope)
                    asked += qs.size; conf += qs.count { it.grade == SourceFactQuestionProbe.Grade.CONFIRMED }; est += qs.count { it.grade == SourceFactQuestionProbe.Grade.ESTIMATED }
                    for (q in qs.filter { it.grade == SourceFactQuestionProbe.Grade.CONFIRMED }) {
                        val hit = SourceFactQuestionProbe.dataNounsIn("대상 " + n + " 정보를 조회").first().second
                        if (gtPaths.any { p -> File(root, p).let { f -> f.isFile && f.readLines(Charsets.UTF_8).any { l -> hit.matches(l) } } }) falseConf++
                    }
                }
            }
            out.appendLine("$mode|$scopes|$pairs|$asked|$conf|$est|$falseConf")
        }
        File("build/b73_probe_counterfactual.txt").writeText(out.toString(), Charsets.UTF_8)
        println(out)
    }
}
