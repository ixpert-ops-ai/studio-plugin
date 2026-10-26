package net.ib.ixpert.ops.wuwagent.agent.eval

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import net.ib.ixpert.ops.wuwagent.client.OpenAIClient
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

/**
 * 질문 생성 실험(테스트 전용). 운영 경로는 쓰지 않고 바꾸지 않는다.
 *  - 지시문 문장에서 정한 기대값(q1~q6): 출력 형식, 질문 수 상한, 후보 목록 미포함, 추정 근거 질문 형태. 실행 결과를 보고 바꾸지 않는다.
 *  - 결정적 단계(n1~n4): 노드 선정 규칙, 소스 본문에서 현재 동작 문장 만들기, 근거 대조(LLM 없음, 합성 데이터).
 *  - 실행(runQuestionGenExperiment): -DrunLiveLlmTests=true일 때만. 시나리오마다 3회 순차, 비스트림(max_tokens 900 → 90초 읽기 제한과 기존 재시도).
 *    옵션: -Dqgen.only=D1,S1 -Dqgen.runs=3 -Dqgen.out=<파일>
 */
class QuestionGenExperimentTest {

    companion object {
        const val SERVER = "http://vllm.ixpertops.cloud"
        const val MODEL = "Qwen/Qwen3.8-27B-FP8"
        val DEFAULT_IDS = listOf("D1", "D5", "S1", "S2", "S3", "SR-01", "SR-02", "SR-04", "SR-05", "SR-05X")
    }

    // ───────────── 지시문 문장에서 정한 기대값 ─────────────

    @Test
    fun q1_지시문은_출력_형식과_질문_수_상한을_정한다() {
        val p = QgenPrompt.system()
        assertTrue(p.contains("질문은 최대 5개"))
        assertTrue(p.contains("JSON 하나만 출력"))
        for (key in listOf("\"questions\"", "\"question\"", "\"options\"", "\"shortAnswer\"", "\"recommended\"", "\"evidence\"")) assertTrue(key, p.contains(key))
        assertTrue(p.contains("보기를 2~5개"))
        assertTrue(p.contains("추천 답"))
    }

    @Test
    fun q2_지시문은_파일_클래스_선택_질문과_후보_목록을_금지한다() {
        val p = QgenPrompt.system()
        assertTrue(p.contains("구현 방법, 파일, 클래스, 패키지, 모듈을 고르는 질문은 하지 않습니다"))
        assertTrue(p.contains("후보 파일 목록을 만들거나 제시하지 않습니다"))
        assertTrue(p.contains("입력은 [요구사항]과 [현재 동작 문장] 두 가지뿐"))
    }

    @Test
    fun q3_지시문은_추정_근거_질문의_형태를_정한다() {
        val p = QgenPrompt.system()
        assertTrue(p.contains("\"~로 보입니다. 맞습니까?\" 형태"))
        assertTrue(p.contains("현재는 ~입니다. 바뀐 뒤에도 유지합니까?"))
    }

    @Test
    fun q4_사용자_입력에는_후보_파일_목록이_없다() {
        val facts = listOf(
            QgenFact("추정", "`OrganizationService`은(는) SERVICE이다."),
            QgenFact("확인", "`OrganizationService.getOrganizations(String type)`는 a/B.java:23-36에 있다.", listOf(QgenEvidence("a/B.java", 23, 36, emptyList())))
        )
        val u = QgenPrompt.user("조직 목록에는 운영 중인 조직만 보이게 해 주세요.", facts)
        assertTrue(u.startsWith("[요구사항]"))
        assertTrue(u.contains("[현재 동작 문장]"))
        assertFalse(u.contains("후보"))
        assertEquals(2, Regex("^\\d+\\. \\[", RegexOption.MULTILINE).findAll(u).count())
        // 사실이 없으면 없다고 말한다
        assertTrue(QgenPrompt.user("x", emptyList()).contains("조회된 현재 동작 없음"))
    }

    @Test
    fun q5_검증기는_질문_수_상한과_보기_범위와_추천_답을_본다() {
        val grades = listOf("확인", "추정")
        fun q(n: Int) = (1..n).joinToString(",") { """{"question":"질문$it","options":["가","나"],"shortAnswer":false,"recommended":"가","evidence":[1]}""" }
        val ok = QgenValidator.validate("""{"questions":[${q(5)}]}""", grades, "")
        assertTrue(ok.parsed); assertTrue(ok.violations.isEmpty()); assertEquals(5, ok.questions.size)
        val over = QgenValidator.validate("""{"questions":[${q(6)}]}""", grades, "")
        assertTrue(over.violations.any { it.contains("상한 5") })
        val bad = QgenValidator.validate("""{"questions":[{"question":"a","options":["하나"],"shortAnswer":false,"recommended":"","evidence":[9]}]}""", grades, "")
        assertTrue(bad.violations.any { it.contains("보기 1개") })
        assertTrue(bad.violations.any { it.contains("추천 답 없음") })
        assertTrue(bad.violations.any { it.contains("범위 밖") })
        val short = QgenValidator.validate("""{"questions":[{"question":"a","options":[],"shortAnswer":true,"recommended":"가","evidence":[0]}]}""", grades, "")
        assertTrue(short.violations.isEmpty())
        // 설명이 앞뒤에 붙어도 JSON만 뽑는다. JSON이 아니면 해석 실패
        assertTrue(QgenValidator.validate("설명\n```json\n{\"questions\":[]}\n```", grades, "").parsed)
        assertFalse(QgenValidator.validate("질문 없음", grades, "").parsed)
        assertNull(QgenValidator.parse(null))
    }

    @Test
    fun q6_검증기는_추정_근거_질문_형태와_파일_범위_질문과_근거_밖_식별자를_센다() {
        val grades = listOf("확인", "추정", "미확인")
        val json = """{"questions":[
          {"question":"조직 목록은 상태와 무관하게 전부 보이는 것으로 보입니다. 맞습니까?","options":["예","아니오"],"shortAnswer":false,"recommended":"예","evidence":[2]},
          {"question":"조직 목록은 전부 보입니다. 유지합니까?","options":["예","아니오"],"shortAnswer":false,"recommended":"예","evidence":[2]},
          {"question":"현재는 findAll을 호출합니다. 바뀐 뒤에도 유지합니까?","options":["예","아니오"],"shortAnswer":false,"recommended":"예","evidence":[1]},
          {"question":"어느 파일을 수정할까요?","options":["A.java","B.xml"],"shortAnswer":false,"recommended":"A.java","evidence":[0]},
          {"question":"OrganizationFooBarService는 어떻습니까?","options":[],"shortAnswer":true,"recommended":"예","evidence":[3]}
        ]}"""
        val v = QgenValidator.validate(json, grades, "findAll OrganizationService")
        assertEquals(1, v.inferenceFormBad) // 두 번째 질문만 추정 근거인데 형태가 틀림(첫 질문은 맞고, 셋째는 확인 근거)
        assertEquals(1, v.fileScope)
        assertTrue(v.identifiersOutsideEvidence.contains("OrganizationFooBarService"))
        assertFalse(v.identifiersOutsideEvidence.contains("findAll"))
    }

    // ───────────── 결정적 단계(LLM 없음, 합성 데이터) ─────────────

    private fun syntheticGraph(root: String, extra: String = "", withOrgService: Boolean = true): ProjectGraph {
        fun node(cls: String, type: String, comments: String = "", path: String = "src/$cls.java") =
            """"$path":{"path":"$path","className":"$cls","fileType":"$type","layer":"BUSINESS","koreanComments":[$comments],"methods":[{"name":"get${cls}List","returnType":"List","parameters":["String type"]}],"dependsOn":[],"riskAssessment":{"riskScore":1,"changeRisk":"LOW","riskReasons":[]}}"""
        val nodes = (if (withOrgService) listOf(node("OrganizationService", "SERVICE", "\"조직 관리\"")) else emptyList()) + listOf(
            node("OrganizationController", "REST_CONTROLLER"),
            node("OrganizationRepository", "REPOSITORY"),
            node("ProductService", "SERVICE"),
            node("PaymentDto", "DTO"),
        ) + (1..12).map { node("MemberThing$it", "UTIL", "\"회원 보조 $it\"") } + listOfNotNull(extra.takeIf { it.isNotBlank() })
        val json = """{"version":"1.0","graphType":"SINGLE","generatedAt":"2026-08-20T00:00:00Z","projectRoot":"$root","framework":"spring-boot","frameworkType":"SPRING_BOOT_JPA","files":{${nodes.joinToString(",")}},"resourceNodes":[],"relationships":[],"statistics":{"totalFiles":${nodes.size}}}"""
        return Gson().fromJson(json, ProjectGraph::class.java).normalizeLegacyCollections()
    }

    @Test
    fun n1_노드_선정은_주석_맞춤_3점_사전_번역_2점이고_일반어는_버린다() {
        val g = syntheticGraph("C:/none")
        val sel = QgenSelector.select(g, "조직 목록에는 운영 중인 조직만 보이게 해 주세요. 회원은 그대로 두세요.")
        // 조직: OrganizationService 주석에 있어 3점. 번역은 내장 사전에 없다
        assertEquals("OrganizationService", sel.nodes.first().label)
        assertEquals(3, sel.nodes.first().score)
        // 회원: 번역 토큰(member 등) 대신 주석 "회원 보조"에 12개 노드가 맞아 전체의 10%(약 1.7개)를 넘으므로 용어에서 뺀다
        assertTrue(sel.droppedTerms.any { it.startsWith("회원") })
        assertTrue(sel.nodes.none { it.label.startsWith("MemberThing") })
        assertTrue(sel.nodes.size <= 5)
    }

    @Test
    fun n2_사전_번역_토큰은_클래스_토큰과_같을_때만_2점이고_점수_2_미만은_뽑지_않는다() {
        val g = syntheticGraph("C:/none")
        val sel = QgenSelector.select(g, "상품 검색에서 판매 중이 아닌 것은 빼 주세요.")
        // 상품 → product: ProductService 클래스 토큰과 같다(2점)
        assertEquals(listOf("ProductService"), sel.nodes.map { it.label })
        assertEquals(2, sel.nodes.single().score)
        // 맞는 용어가 없으면 노드는 0개
        assertTrue(QgenSelector.select(g, "전혀 관련 없는 요구사항입니다.").nodes.isEmpty())
    }

    @Test
    fun n3_소스_본문에서_현재_동작_문장을_만들고_근거를_다시_읽어_대조한다() {
        val dir = Files.createTempDirectory("qgen-src").toFile()
        try {
            File(dir, "src").mkdirs()
            File(dir, "src/OrganizationService.java").writeText(
                """
                package a;
                public class OrganizationService {
                    private final OrganizationRepository organizationRepository;

                    public List<OrganizationResponse> getOrganizations(String type) {
                        List<Organization> organizations;
                        if (type != null && !type.isEmpty()) {
                            organizations = organizationRepository.findByType(type);
                        } else {
                            organizations = organizationRepository.findAll();
                        }
                        return organizations.stream().map(OrganizationResponse::from).collect(toList());
                    }
                }
                """.trimIndent(), Charsets.UTF_8
            )
            val extra = """"src/OrganizationService.java":{"path":"src/OrganizationService.java","className":"OrganizationService","fileType":"SERVICE","layer":"BUSINESS","koreanComments":["조직 관리"],"methods":[{"name":"getOrganizations","returnType":"List<OrganizationResponse>","parameters":["String type"]}],"dependsOn":["src/OrganizationRepository.java"],"riskAssessment":{"riskScore":1,"changeRisk":"LOW","riskReasons":[]}}"""
            val g = syntheticGraph(dir.absolutePath.replace('\\', '/'), extra, withOrgService = false)
            val sel = QgenSelector.select(g, "조직 목록에는 운영 중인 조직만 보이게 해 주세요.")
            val facts = QgenFacts.build(g, sel.nodes, "조직 목록에는 운영 중인 조직만 보이게 해 주세요.")
            val confirmed = facts.filter { it.grade == "확인" }
            assertEquals(1, confirmed.size)
            val f = confirmed.single()
            assertTrue(f.text, f.text.contains("organizationRepository.findByType") && f.text.contains("organizationRepository.findAll"))
            assertTrue(f.text, f.text.contains("`type != null && !type.isEmpty()`"))
            assertTrue(f.text, f.text.contains("src/OrganizationService.java:5-13"))
            assertTrue(facts.all { QgenFacts.verifyFact(g.projectRoot, it) })
            // 근거가 어긋나면(문장이 말한 호출이 근거 범위에 없으면) 대조가 실패한다 = 본문 근거가 실제로는 없는 "확인"
            val fake = QgenFact("확인", "가짜", listOf(QgenEvidence("src/OrganizationService.java", 5, 13, listOf("organizationRepository.deleteAll"))))
            assertFalse(QgenFacts.verifyFact(g.projectRoot, fake))
            val noEvidence = QgenFact("확인", "근거 없음")
            assertFalse(QgenFacts.verifyFact(g.projectRoot, noEvidence))
            val wrongRange = QgenFact("확인", "범위 밖", listOf(QgenEvidence("src/OrganizationService.java", 1, 500, emptyList())))
            assertFalse(QgenFacts.verifyFact(g.projectRoot, wrongRange))
            // 추정 문장은 대조 대상이 아니다
            assertTrue(facts.filter { it.grade == "추정" }.all { QgenFacts.verifyFact(g.projectRoot, it) })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun n4_소스가_없으면_미확인_문장이고_노드가_없으면_전부_미확인이다() {
        val extra = """"src/OrganizationService.java":{"path":"src/OrganizationService.java","className":"OrganizationService","fileType":"SERVICE","layer":"BUSINESS","koreanComments":["조직 관리"],"methods":[],"dependsOn":[],"riskAssessment":{"riskScore":1,"changeRisk":"LOW","riskReasons":[]}}"""
        val g = syntheticGraph("C:/definitely/not/here", extra, withOrgService = false)
        val facts = QgenFacts.build(g, QgenSelector.select(g, "조직 목록").nodes, "조직 목록")
        assertTrue(facts.any { it.grade == "미확인" && it.text.contains("소스") && it.text.contains("읽지 못") })
        assertTrue(facts.none { it.grade == "확인" })
        val none = QgenFacts.build(g, emptyList(), "x")
        assertEquals(1, none.size)
        assertEquals("미확인", none.single().grade)
    }

    // ───────────── 실행(LLM 호출): -DrunLiveLlmTests=true ─────────────

    @Test
    fun runQuestionGenExperiment() {
        Assume.assumeTrue(
            "질문 생성 실험은 -DrunLiveLlmTests=true 또는 환경변수 RUN_LIVE_LLM_TESTS=true 명시 시에만 활성화됩니다.",
            System.getProperty("runLiveLlmTests") == "true" || System.getenv("RUN_LIVE_LLM_TESTS") == "true"
        )
        val server = EvalServerCheck.check(SERVER, MODEL)
        println("[QGEN] 서버 확인: ${server.message} (HTTP ${server.httpStatus}, 모델 ${server.listedModels})")
        if (!server.modelListed) {
            println("[QGEN] 실험을 시작하지 않음: ${server.message}")
            Assume.assumeTrue("실험을 시작하지 않음: ${server.message}", false)
            return
        }
        val onlyIds = (System.getProperty("qgen.only")?.takeIf { it.isNotBlank() }?.split(",")?.map { it.trim() }) ?: DEFAULT_IDS
        val runs = System.getProperty("qgen.runs")?.toIntOrNull() ?: 3
        val outFile = File(System.getProperty("qgen.out") ?: "build/eval-reports/qgen_results.json")
        val dumpDir = File(outFile.parentFile, "dumps_qgen")
        val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

        val scenarios = EvalScenarios.loadAll().filter { it.id in onlyIds }.sortedBy { onlyIds.indexOf(it.id) }
        val graphCache = HashMap<String, ProjectGraph?>()
        fun loadGraph(key: String): ProjectGraph? = graphCache.getOrPut(key) {
            val path = EvalScenarios.graphPaths()[key] ?: return@getOrPut null
            val file = File(path)
            if (!file.exists()) return@getOrPut null
            Gson().fromJson(file.readText(Charsets.UTF_8), ProjectGraph::class.java).normalizeLegacyCollections()
        }

        val metered = MeteredLlmClient(OpenAIClient(), 300L) // 읽기 제한(90초)과 재시도는 운영 클라이언트가 한다. 바깥 상한은 그보다 넉넉히 둔다.
        val proxy = UsageProxy(SERVER, dumpDir)
        proxy.start()
        proxy.install()

        val records = ArrayList<Map<String, Any?>>()
        var aborted: String? = null
        var globalRuns = 0
        var firstThreeFailures = 0
        val system = QgenPrompt.system()
        try {
            outer@ for (sc in scenarios) {
                val graph = loadGraph(sc.graphKey)
                if (graph == null || !File(graph.projectRoot).isDirectory) { println("[QGEN] ${sc.id}: 그래프나 소스 폴더가 없어 건너뜀"); continue }
                val generatedAt = Instant.parse(graph.generatedAt).toEpochMilli()
                val sel = QgenSelector.select(graph, sc.sr)
                val facts = QgenFacts.build(graph, sel.nodes, sc.sr)
                val user = QgenPrompt.user(sc.sr, facts)
                val grades = facts.map { it.grade }
                val falseConfirmed = facts.count { it.grade == "확인" && !QgenFacts.verifyFact(graph.projectRoot, it) }
                val evidenceFiles = facts.flatMap { it.evidence }.map { it.file }.distinct()
                val lateEvidence = evidenceFiles.filter { File(graph.projectRoot, it).lastModified() > generatedAt }
                println("[QGEN] ${sc.id}: 노드 ${sel.nodes.map { it.label }}, 문장 ${grades.groupingBy { it }.eachCount()}, 거짓 확인 $falseConfirmed, generatedAt 뒤 수정 근거 ${lateEvidence.size}")
                for (run in 1..runs) {
                    proxy.scenarioId = sc.id; proxy.run = run; proxy.phase = "qgen"
                    val t0 = System.nanoTime()
                    var content: String? = null
                    var outcome = "ok"
                    var detail: String? = null
                    try {
                        val resp = metered.chat(system, user, 900, null)
                        content = resp?.message?.content
                        if (resp == null) outcome = "null"
                    } catch (e: Exception) {
                        outcome = "exception"; detail = e.javaClass.simpleName + ": " + (e.message ?: "").take(160)
                    }
                    val wallMs = (System.nanoTime() - t0) / 1_000_000
                    proxy.awaitIdle(5_000)
                    val pcalls = proxy.calls.filter { it.scenarioId == sc.id && it.run == run }
                    val last = pcalls.lastOrNull()
                    val validation = QgenValidator.validate(content, grades, user)
                    if (outcome == "ok" && !validation.parsed) { outcome = "해석 실패" }
                    globalRuns++
                    if (globalRuns <= 3 && outcome != "ok") firstThreeFailures++
                    records.add(linkedMapOf(
                        "scenario" to sc.id, "run" to run, "graph" to sc.graphKey, "outcome" to outcome, "detail" to detail,
                        "wallMs" to wallMs, "proxyCalls" to pcalls.size,
                        "promptTokens" to last?.promptTokens, "completionTokens" to last?.completionTokens, "callMs" to last?.totalMs,
                        "finishReason" to last?.finishReason, "httpStatus" to last?.httpStatus,
                        "nodes" to sel.nodes.map { mapOf("label" to it.label, "path" to it.path, "type" to it.fileType, "score" to it.score, "reasons" to it.reasons) },
                        "terms" to sel.terms, "droppedTerms" to sel.droppedTerms,
                        "facts" to facts.mapIndexed { i, f -> mapOf("n" to i + 1, "grade" to f.grade, "text" to f.text, "evidence" to f.evidence.map { "${it.file}:${it.startLine}-${it.endLine}" }) },
                        "falseConfirmed" to falseConfirmed, "lateEvidenceFiles" to lateEvidence,
                        "questions" to validation.questions.map { mapOf("question" to it.question, "options" to it.options, "shortAnswer" to it.shortAnswer, "recommended" to it.recommended, "evidence" to it.evidence) },
                        "violations" to validation.violations, "fileScope" to validation.fileScope, "inferenceFormBad" to validation.inferenceFormBad,
                        "identifiersOutsideEvidence" to validation.identifiersOutsideEvidence,
                        "userPromptChars" to user.length, "rawContent" to content
                    ))
                    println("[QGEN] ${sc.id} #$run: $outcome ${wallMs}ms, 질문 ${validation.questions.size}, prompt ${last?.promptTokens} completion ${last?.completionTokens}")
                    outFile.parentFile?.mkdirs()
                    outFile.writeText(gson.toJson(mapOf("startedAt" to Instant.now().toString(), "aborted" to aborted, "records" to records)), Charsets.UTF_8)
                    if (globalRuns == 3 && firstThreeFailures >= 2) { aborted = "처음 3회 중 ${firstThreeFailures}회 실패"; break@outer }
                }
            }
        } finally {
            proxy.uninstall(); proxy.stop()
        }
        outFile.writeText(gson.toJson(mapOf("finishedAt" to Instant.now().toString(), "aborted" to aborted, "records" to records)), Charsets.UTF_8)
        println("[QGEN] 끝: 기록 ${records.size}건" + (aborted?.let { ", 중단: $it" } ?: ""))
        assertNotNull(records)
    }
}
