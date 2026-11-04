package net.ib.ixpert.ops.wuwagent.agent.eval

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.agent.L1ClarificationBridge
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.client.OpenAIClient
import net.ib.ixpert.ops.wuwagent.model.ChatCompletionResponse
import net.ib.ixpert.ops.wuwagent.model.ChatMessage
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.OllamaMessage
import net.ib.ixpert.ops.wuwagent.model.ToolDefinition
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
 * B-84 2·3 측정 전용(실서버, -DrunLiveLlmTests=true일 때만). 탐색기 temperature가 0.0이라 같은 입력 반복은 하지 않고 입력 변형마다 1회만 돈다.
 *
 * 입력은 IDE 17:36 회차 [ExplorerInput] userMessage 전문(fixture src/test/resources/b84/ide_1737_explorer_input.txt)에서 그대로 뽑는다(SR 문장, 사실 블록 471자).
 * bridge=true였던 IDE 조건은 힌트를 주지 않는 브리지(항상 null, IDE에서 카드를 취소한 것과 같다)로 맞춘다. 브리지는 힌트가 비면 탐색기 입력을 바꾸지 않는다.
 *
 * repro: IDE 입력 그대로 1회(userMessage 전문 일치와 시드·턴·검색어를 확인). variants: SR 2종 x 끝 줄바꿈 x 사실 블록 → build/b84_variants.md.
 */
class MeasureB84Test {
    private val root = File("C:/Workspace/HC_card_survey_admin/survey_admin")

    /** 13:34 IDE 회차에서 관찰된 정제문(108자). 17:36 문장(97자)은 로그에서 뽑는다. */
    private val sr108 = "설문 응답 테이블에 이메일 컬럼을 추가하여 응답자 이메일을 수집하고, 관리자 설문 상세 조회 시 해당 이메일을 앞 2자리와 도메인만 노출되도록 마스킹 처리하여 백엔드 응답에 포함하도록 수정한다."

    private fun live() = System.getProperty("runLiveLlmTests") == "true" || System.getenv("RUN_LIVE_LLM_TESTS") == "true"

    private class Ide(val message: String, val sr: String, val facts: String)

    private fun loadIde(): Ide {
        // fixture: IDE 17:36 회차 [ExplorerInput] userMessage 전문(659자, \n 이스케이프를 푼 것). 소스 본문은 없다.
        // core.autocrlf가 켜진 체크아웃은 이 파일을 CRLF로 바꿀 수 있으므로 줄바꿈을 LF로 맞춰 읽는다(IDE 로그의 \n 이스케이프는 LF였다)
        val msg = File("src/test/resources/b84/ide_1737_explorer_input.txt").readText(Charsets.UTF_8).replace("\r\n", "\n")
        val parts = msg.split("\n\n\n")
        val sr = parts[0].removePrefix("요구사항(SR):\n")
        val facts = parts[1].substringBefore("\n\n위 요구사항")
        return Ide(msg, sr, facts)
    }

    private class Run(val seeds: List<String>, val turns: Int, val queries: List<String>, val log: List<String>, val ms: Long, val inputLine: String)

    private val noHint = L1ClarificationBridge { _, _, _, _, _ -> null }

    /** 탐색기에 응답을 주지 않는 가짜 LLM(하네스의 StrictPipelineLlm과 같다): 서버 없이 탐색기 입력([ExplorerInput])만 만들어 본다. */
    private class NoAnswerLlm : LLMClient {
        override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): OllamaChatResponse =
            OllamaChatResponse(model = "no-answer", createdAt = "", message = OllamaMessage("assistant", "{}"), done = true)

        override fun chatWithTools(
            systemPrompt: String, messages: List<ChatMessage>, maxTokens: Int?, tools: List<ToolDefinition>?, toolChoice: Any?, temperature: Double?
        ): ChatCompletionResponse? = null

        override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
    }

    private fun explore(
        g: ProjectGraph, srText: String, facts: String?, llm: LLMClient = OpenAIClient()
    ): Pair<Run, net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.SeedSelectionResult> {
        val old = System.out
        val baos = ByteArrayOutputStream()
        System.setOut(PrintStream(baos, true, "UTF-8"))
        val t0 = System.currentTimeMillis()
        val seed = try { AgenticSeedSelector(llm, root.path, noHint, facts).selectSeeds(srText, g) } finally { System.setOut(old) }
        val ms = System.currentTimeMillis() - t0
        val log = baos.toString("UTF-8").lines()
        val queries = log.filter { it.contains(">>> Args:") }.map { it.substringAfter(">>> Args:").trim() }
        val input = log.firstOrNull { it.contains("[ExplorerInput] userMessage") } ?: ""
        return Run(seed.seedClasses, log.count { it.contains("=== TURN") }, queries, log, ms, input) to seed
    }

    private fun loadGraph(): ProjectGraph {
        val gf = File(root, ".meta/project-graph.json")
        Assume.assumeTrue(gf.isFile)
        return Gson().fromJson(gf.readText(Charsets.UTF_8), ProjectGraph::class.java).normalizeLegacyCollections()
    }

    private fun userMessageOf(inputLine: String): String {
        val raw = inputLine.substring(inputLine.indexOf("userMessage("))
        return raw.substring(raw.indexOf(")=") + 2).trimEnd('\r').split("\\n").joinToString("\n")
    }

    @Test
    fun repro() {
        Assume.assumeTrue(live())
        val g = loadGraph()
        val ide = loadIde()
        val (run, _) = explore(g, ide.sr + "\n", ide.facts)
        val mine = userMessageOf(run.inputLine)
        val same = mine == ide.message
        val sb = StringBuilder("# B-84 재현 확인(IDE 17:36 입력 그대로, 브리지=힌트 없음)\n")
        sb.appendLine("- userMessage 전문 일치: $same (IDE ${ide.message.length}자 / 이번 ${mine.length}자)")
        sb.appendLine("- 시드: ${run.seeds} (기준 [SurveyServiceImpl, SurveyDao, SurveyDaoImpl, SurveyDto])")
        sb.appendLine("- 턴: ${run.turns} (기준 4), 시간 ${run.ms / 1000}초")
        sb.appendLine("- 도구 인자 순서: ${run.queries}")
        sb.appendLine("- 탐색기 종료 로그: ${run.log.firstOrNull { it.contains("[ExplorerTiming]") }?.trim()}")
        sb.appendLine("- 기준 인자 순서: Survey Response, Survey Detail, (inspect) SurveyServiceImpl, SurveyDao, Survey Result Email, SurveyDto, expand SurveyServiceImpl, confirm")
        File("build/b84_repro.md").writeText(sb.toString(), Charsets.UTF_8)
        println(sb)
        // 회귀 단언 두 단계(이 시험은 실서버 전용). 입력 경로 단언은 서버 없이 도는 fixtureIntact가 맡고, 여기서는 서버 출력을 본다.
        // 입력이 어긋났으면 입력 경로가 바뀐 것이고, 입력이 같은데 출력만 다르면 서버(모델·가중치·서버 설정) 쪽 차이일 수 있다. 둘을 메시지로 구분한다.
        org.junit.Assert.assertTrue("입력 불일치: userMessage 전문이 IDE 17:36과 다르다(입력 경로가 바뀜). 이번 ${mine.length}자 / IDE ${ide.message.length}자", same)
        val expectedSeeds = setOf("SurveyServiceImpl", "SurveyDao", "SurveyDaoImpl", "SurveyDto")
        val searches = run.queries.filter { it.startsWith("{\"query\"") }.take(3)
        val expectedSearches = listOf("{\"query\": \"Survey Response\"}", "{\"query\": \"Survey Detail\"}", "{\"query\": \"Survey Result Email\"}")
        val diffs = mutableListOf<String>()
        if (run.seeds.toSet() != expectedSeeds) diffs += "시드(집합 비교) 기준 $expectedSeeds / 이번 ${run.seeds}"
        if (run.turns != 4) diffs += "턴 기준 4 / 이번 ${run.turns}"
        if (searches != expectedSearches) diffs += "검색어 기준 $expectedSearches / 이번 $searches"
        if (diffs.isNotEmpty()) {
            org.junit.Assert.fail("입력 동일(userMessage 전문 일치), 서버 출력 차이 가능. 설정 모델=${modelId()}, 서버 /v1/models=${serverModels()}, 시각=${java.time.ZonedDateTime.now()}. 차이: ${diffs.joinToString("; ")}")
        }
    }

    /** 실패 메시지용(성공 경로에서는 부르지 않는다): 서버가 지금 내놓는 모델 id 목록(GET {openaiServerUrl}/v1/models). 조회 실패는 "알 수 없음(예외 종류)". */
    private fun serverModels(): String = try {
        val client = OpenAIClient()
        val m = OpenAIClient::class.java.getDeclaredMethod("getEffectiveSettings").apply { isAccessible = true }
        val s = m.invoke(client)
        val base = s.javaClass.getMethod("getOpenaiServerUrl").invoke(s).toString().trimEnd('/')
        val c = java.net.URL("$base/v1/models").openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 5000
        val body = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        com.google.gson.JsonParser.parseString(body).asJsonObject.getAsJsonArray("data").map { it.asJsonObject.get("id").asString }.toString()
    } catch (e: Exception) {
        "알 수 없음(${e.javaClass.simpleName})"
    }

    private fun modelId(): String = try {
        val client = OpenAIClient()
        val m = OpenAIClient::class.java.getDeclaredMethod("getEffectiveSettings").apply { isAccessible = true }
        val s = m.invoke(client)
        s.javaClass.getMethod("getModel").invoke(s).toString()
    } catch (e: Exception) {
        "알 수 없음(${e.javaClass.simpleName})"
    }

    /**
     * 서버 없이 도는 입력 단언(엄격): fixture 자체(659자, SR 97자, 사실 블록 471자 5줄)와, 탐색기가 실제로 만드는 입력이 IDE 17:36과 같다는 것.
     * 가짜 LLM으로 탐색기를 돌려 [ExplorerInput] 두 줄을 읽는다: userMessage 전문 일치, systemPromptHash c56386b9, graphFiles 59 / resources 73.
     * 탐색기 프롬프트나 메시지 틀을 일부러 바꿀 때만 이 값을 새로 정하고, 이유를 커밋 메시지에 적는다.
     */
    @Test
    fun fixtureIntact() {
        val ide = loadIde()
        org.junit.Assert.assertEquals(659, ide.message.length)
        org.junit.Assert.assertEquals(97, ide.sr.length)
        org.junit.Assert.assertEquals(471, ide.facts.length)
        org.junit.Assert.assertEquals(5, ide.facts.lines().size)
        // 탐색기 메시지 틀: "요구사항(SR):\n$sr\n" + "\n\n" + 사실 블록 → SR 뒤에 \n\n\n
        org.junit.Assert.assertTrue(ide.message.startsWith("요구사항(SR):\n" + ide.sr + "\n\n\n[Stage 0에서 확인한 사실]"))
        // 로컬 그래프가 없는 환경에서는 조용히 통과시키지 않고 Assumptions로 skip 처리하고 이유를 출력한다(fixture 단언은 위에서 이미 끝났다)
        val graphFile = File(root, ".meta/project-graph.json")
        if (!graphFile.isFile) println("[fixtureIntact] skip: 로컬 그래프 파일이 없어 탐색기 입력 경로 단언(userMessage 전문, systemPromptHash, graphFiles/resources)을 건너뜁니다: ${graphFile.path}")
        Assume.assumeTrue("로컬 그래프 파일 없음(${graphFile.path}): 탐색기 입력 경로 단언 skip", graphFile.isFile)
        val g = loadGraph()
        val (run, _) = explore(g, ide.sr + "\n", ide.facts, NoAnswerLlm())
        org.junit.Assert.assertEquals("탐색기 userMessage 전문이 IDE 17:36과 같아야 한다", ide.message, userMessageOf(run.inputLine))
        val meta = run.log.firstOrNull { it.contains("[ExplorerInput] systemPromptHash") } ?: ""
        org.junit.Assert.assertTrue("systemPromptHash c56386b9 기대, 실제: $meta", meta.contains("systemPromptHash=c56386b9"))
        org.junit.Assert.assertTrue("graphFiles=59 기대, 실제: $meta", meta.contains("graphFiles=59 "))
        org.junit.Assert.assertTrue("resources=73 기대, 실제: $meta", meta.contains("resources=73 "))
    }

    // 부정문(없다·않다·발견되지·확인되지·미확인)과 긍정 단정을 나눠 센다
    // 줄이 아니라 언급 단위로 센다(한 줄에 단정과 "추가 대상"이 같이 있어도 단정은 센다): 언급 앞뒤 25자에 부정 표현이 있으면 부정문, 없으면 긍정 단정
    private val negation = Regex("없|않|발견되지|확인되지|미확인|아니")
    private val saveMention = Regex("조회\\s*/\\s*저장|저장\\s*/\\s*조회|수집\\s*/\\s*저장|updateResult|저장\\s*(처리|로직|담당)|수집\\s*(처리|로직|담당)|응답\\s*(저장|수집)")
    private val emailAffirm = Regex("이메일.*(이미|포함되어|존재|조회하)")

    /** (긍정 단정, 부정문) 언급 수 */
    private fun countMentions(lines: List<String>): Pair<Int, Int> {
        var affirm = 0; var denied = 0
        for (line in lines) for (m in saveMention.findAll(line)) {
            val window = line.substring(maxOf(0, m.range.first - 25), minOf(line.length, m.range.last + 26))
            if (negation.containsMatchIn(window)) denied++ else affirm++
        }
        return affirm to denied
    }

    @Test
    fun variants() {
        Assume.assumeTrue(live())
        val g = loadGraph()
        val ide = loadIde()
        val out = StringBuilder(
            "# B-84 2 입력 변형 비교(IDE 입력 기준, 변형마다 1회, temperature 0.0, 브리지=힌트 없음)\n" +
                "| SR | 끝 줄바꿈 | 사실 블록 | 시드 | 턴 | 시드에 매퍼 | 후보 수 | sql_survey.xml 후보(점수/경로) | 이메일 단정 | 저장·수집 긍정 | 저장·수집 부정문 | 초 | 처음 3개 인자 |\n" +
                "|---|---|---|---|---|---|---|---|---|---|---|---|---|\n"
        )
        val detail = StringBuilder()
        for ((srName, sr) in listOf("97자(17:36)" to ide.sr, "108자(13:34)" to sr108)) for (trailing in listOf(true, false)) for (facts in listOf(false, true)) {
            val srText = if (trailing) "$sr\n" else sr
            val (run, seed) = explore(g, srText, if (facts) ide.facts else null)
            val msgs = run.log.filter { it.contains("Message:") || it.contains("rationale") }
            val emailClaims = msgs.count { emailAffirm.containsMatchIn(it) && !negation.containsMatchIn(it) && !Regex("추가|신설|새로|수집|마스킹").containsMatchIn(it) }
            // 원문(탐색기 Turn 메시지와 확정 rationale 도구 인자)을 남겨 집계 방식을 나중에 바꿔도 다시 셀 수 있게 한다
            val dumpName = "b84_var_${srName.take(3)}_${if (trailing) "nl" else "nonl"}_${if (facts) "facts" else "nofacts"}.txt"
            File("build/b84_variant_logs").apply { mkdirs() }.resolve(dumpName).writeText(
                (msgs + run.queries.filter { it.contains("rationale") }).joinToString("\n---\n"), Charsets.UTF_8
            )
            // 같은 문장이 로그 줄과 도구 인자에 두 번 들어가므로, 다른 항목에 통째로 들어 있는 항목은 뺀다(scripts/recount-b84.js와 같은 규칙)
            val kept = mutableListOf<String>()
            for (p in msgs + run.queries.filter { it.contains("rationale") }) if (kept.none { it.contains(p) || p.contains(it) }) kept.add(p)
            val (affirmN, deniedN) = countMentions(kept)
            val ex = GraphExpander(g, DomainExtractor(g.files), DiscoveryConfig(maxHop = 3)).expand(seed, sr)
            val old = System.out
            val scored = try { System.setOut(PrintStream(ByteArrayOutputStream())); RelevanceScorer(g, 30, 55).scoreAndFilter(sr, ex, seed) } finally { System.setOut(old) }
            val mapper = scored.firstOrNull { it.className == "sql_survey.xml" }
            out.appendLine("| $srName | ${if (trailing) "유" else "무"} | ${if (facts) "유" else "무"} | ${run.seeds} | ${run.turns} | ${"sql_survey.xml" in run.seeds} | ${scored.size} | ${mapper != null}${mapper?.let { "(${it.score}/${it.protectionReason})" } ?: ""} | $emailClaims | $affirmN | $deniedN | ${run.ms / 1000} | ${run.queries.take(3).joinToString(" ")} |")
            File("build/b84_variants.md").writeText(out.toString() + detail.toString(), Charsets.UTF_8)
        }
        println(out)
    }
}
