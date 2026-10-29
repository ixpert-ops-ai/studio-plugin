package net.ib.ixpert.ops.wuwagent.agent.eval

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import net.ib.ixpert.ops.wuwagent.agent.FileRelevanceVerifier
import net.ib.ixpert.ops.wuwagent.agent.RequirementAnalysisPipeline
import net.ib.ixpert.ops.wuwagent.agent.TargetFileSpec
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.client.OpenAIClient
import net.ib.ixpert.ops.wuwagent.model.ChatChoice
import net.ib.ixpert.ops.wuwagent.model.ChatCompletionResponse
import net.ib.ixpert.ops.wuwagent.model.ChatMessage
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.OllamaMessage
import net.ib.ixpert.ops.wuwagent.model.ToolCall
import net.ib.ixpert.ops.wuwagent.model.ToolCallFunction
import net.ib.ixpert.ops.wuwagent.model.ToolDefinition
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * B-77 4: Stage 3 검증기 입력에 상위 N개 후보의 본문(자바 소스·매퍼 SQL)을 붙이면 달라지는지 보는 실험(시험 코드만, 운영 연결 없음).
 * 설계와 예측은 build/eval-reports/pred_b77.md(실행 전 작성). 본문은 [BodyInjectingClient]가 사용자 메시지 끝에 붙인다.
 * 실행: ./gradlew test --tests "*Stage3BodyExperimentB77Test" -DrunLiveLlmTests=true -i
 */
class Stage3BodyExperimentB77Test {

    private val surveyRoot = File("C:/Workspace/HC_card_survey_admin/survey_admin")
    private val srA = "관리자 설문 상세 화면용 기존 설문조사 상세 조회 기능에서 응답자 이메일 정보를 앞 2자리와 도메인만 보이도록 마스킹하여 함께 반환하도록 백엔드를 수정한다."
    private val srB = "기존 설문조사 상세 조회 기능에 응답자 이메일 정보도 같이 내려주도록 백엔드를 수정해주세요"

    private val perCandidateCap = 1800
    private val totalCap = 9000 // 추정 토큰 3000 = 9000자 ÷ 3

    /** 모의 탐색기(시드 고정)로 파이프라인을 태우며 실제 Stage 3 요청의 후보 순서를 캡처한다. 서버 호출 없음. */
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
            val user = messages.mapNotNull { it.content }.joinToString("\n")
            stage3User = user
            val cands = net.ib.ixpert.ops.wuwagent.agent.Stage3Prompt.candidates(user).map { it.first }
            return call("submit_verification", "{\"requiredFiles\":[{\"n\":${cands.first()},\"reason\":\"수정이 필요한 구체적인 사유\"}],\"removedFiles\":[],\"reasoning\":\"1) 실험 캡처\"}")
        }
        override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
    }

    /** 운영 클라이언트를 감싸 Stage 3 호출의 마지막 사용자 메시지에 본문 구역을 붙이고, 보낸 입력 크기를 기록한다. */
    private class BodyInjectingClient(val delegate: LLMClient, val bodies: String) : LLMClient {
        @Volatile var sentUser: String = ""
        @Volatile var sentSystem: String = ""
        override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): OllamaChatResponse? =
            delegate.chat(systemPrompt, userCode, maxTokens, onChunk)
        override fun chatWithTools(systemPrompt: String, messages: List<ChatMessage>, maxTokens: Int?, tools: List<ToolDefinition>?, toolChoice: Any?, temperature: Double?): ChatCompletionResponse? {
            val out = if (bodies.isBlank() || messages.isEmpty()) messages
            else messages.dropLast(1) + messages.last().copy(content = (messages.last().content ?: "") + "\n\n" + bodies)
            sentSystem = systemPrompt
            sentUser = out.mapNotNull { it.content }.joinToString("\n")
            return delegate.chatWithTools(systemPrompt, out, maxTokens, tools, toolChoice, temperature)
        }
        override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = delegate.fetchModels(baseUrl, apiKey)
    }

    private fun stripJava(src: String) =
        src.lines().filterNot { it.startsWith("package ") || it.startsWith("import ") || it.isBlank() }.joinToString("\n")

    private val stmtRe = Regex("(?s)<(select|insert|update|delete)\\s+id=\"([^\"]+)\".*?</\\1>")

    private fun readRel(graph: ProjectGraph, path: String): String? {
        val rel = graph.files[path]?.path ?: graph.resourceNodes.firstOrNull { it.path == path }?.path ?: path
        val f = File(surveyRoot, rel)
        return if (f.isFile) f.readText(Charsets.UTF_8) else null
    }

    /** 상위 n개 후보의 본문 구역. 일반 규칙: 자바는 소스 앞부분, 매퍼는 상위 자바 후보 소스에 id가 나오는 문을 먼저. */
    private fun buildBodies(paths: List<String>, n: Int, graph: ProjectGraph): String {
        if (n <= 0) return ""
        val top = paths.take(n)
        val sources = top.associateWith { readRel(graph, it) }
        val javaText = top.filter { it.endsWith(".java") }.mapNotNull { sources[it] }.joinToString("\n")
        var remaining = totalCap
        val sb = StringBuilder("## 후보 본문(참고: 위 후보 중 상위 ${top.size}개 파일 소스의 일부)\n")
        top.forEachIndexed { i, p ->
            val src = sources[p] ?: return@forEachIndexed
            if (remaining < 300) return@forEachIndexed
            val cap = minOf(perCandidateCap, remaining)
            val body = if (p.endsWith(".xml")) {
                val stmts = stmtRe.findAll(src).map { it.groupValues[2] to it.value }.toList()
                val ordered = stmts.filter { javaText.contains(it.first) } + stmts.filterNot { javaText.contains(it.first) }
                val acc = StringBuilder()
                for ((_, text) in ordered) { if (acc.length + text.length > cap) break; acc.appendLine(text) }
                acc.toString().ifBlank { src.take(cap) }
            } else {
                val t = if (p.endsWith(".java")) stripJava(src) else src.lines().filter { it.isNotBlank() }.joinToString("\n")
                t.take(cap)
            }
            remaining -= body.length
            sb.appendLine("### [${i + 1}] ${p.substringAfterLast('/')}")
            sb.appendLine(body.trimEnd())
        }
        return sb.toString()
    }

    private fun captureCandidates(g: ProjectGraph, sr: String): List<String> {
        val stub = CaptureStub(listOf("SurveyServiceImpl", "SurveyDaoImpl", "SurveyDto"))
        kotlinx.coroutines.runBlocking {
            RequirementAnalysisPipeline(null, stub, true).analyze(primaryReq = sr, secondaryReq = "", projectGraph = g, projectRoot = surveyRoot) { }
        }
        val user = stub.stage3User ?: error("Stage 3 요청을 캡처하지 못했다")
        return net.ib.ixpert.ops.wuwagent.agent.Stage3Prompt.candidates(user).map { it.second }
    }

    data class RunOut(
        val sr: String, val cond: String, val run: Int, val ms: Long, val outcome: String,
        val inputChars: Int, val estTokens: Int, val bodyChars: Int, val xmlRank: Int, val xmlKept: Boolean?,
        val remaining: List<String>, val removed: List<String>, val reasoning: String,
        val contradictionRegex: Boolean, val claim: Boolean, val evidenceInInput: Boolean, val evidenceInBodies: Boolean, val unsupportedClaim: Boolean
    )

    private val claimRe = Regex("(그대로|기존|이미)[^.\\n]{0,40}(조회|전달|반환|select|존재|포함)|(조회|전달|반환)[^.\\n]{0,20}그대로", RegexOption.IGNORE_CASE)
    private val daoNotNeeded = Regex("(DAO|Dao|Mapper|매퍼|SQL|MyBatis|XML|mapper)[^.\\n]{0,60}(수정 불필요|변경 불필요|수정이 필요 없|변경 없|수정하지 않|불필요)")
    private val sqlNeeded = Regex("(SQL|mapper|Mapper|매퍼|컬럼)[^.\\n]{0,40}(추가|수정|변경)[^.\\n]{0,10}(필요|해야)")
    private val emailRe = Regex("(?i)e[-_ ]?mail|이메일")

    @Test
    fun runExperiment() {
        Assume.assumeTrue(
            "Live vLLM 실험은 -DrunLiveLlmTests=true 또는 환경변수 RUN_LIVE_LLM_TESTS=true 명시 시에만 활성화됩니다.",
            System.getProperty("runLiveLlmTests") == "true" || System.getenv("RUN_LIVE_LLM_TESTS") == "true"
        )
        val graphFile = File(surveyRoot, ".meta/project-graph.json")
        Assume.assumeTrue(graphFile.isFile)
        val g = Gson().fromJson(graphFile.readText(Charsets.UTF_8), ProjectGraph::class.java).normalizeLegacyCollections()
        val runs = System.getProperty("eval.replay.runs")?.toIntOrNull() ?: 5
        val srs = linkedMapOf("A(87자)" to srA, "B(50자)" to srB)
        val conds = linkedMapOf("N=0" to 0, "N=5" to 5)

        val cands = srs.mapValues { captureCandidates(g, it.value) }
        cands.forEach { (k, v) -> println("[B77EXP] $k 후보 ${v.size}개, sql_survey.xml 순위=${v.indexOfFirst { it.endsWith("sql_survey.xml") } + 1}: ${v.take(10).map { it.substringAfterLast('/') }}") }

        val pool = Executors.newFixedThreadPool(3)
        val jobs = ArrayList<Triple<String, String, java.util.concurrent.Future<RunOut>>>()
        for ((srKey, sr) in srs) for ((condKey, n) in conds) for (run in 1..runs) {
            val f = pool.submit(Callable {
                val paths = cands.getValue(srKey)
                val specs = paths.mapIndexed { i, p -> TargetFileSpec(i + 1, p, "MODIFY", "") }
                val bodies = buildBodies(paths, n, g)
                val client = BodyInjectingClient(OpenAIClient(), bodies)
                val verifier = FileRelevanceVerifier(client, g, Paths.get("nonexistent-docs"))
                val t0 = System.currentTimeMillis()
                val out = try { verifier.verify(sr, specs) } catch (e: Throwable) { null }
                val ms = System.currentTimeMillis() - t0
                val reasoning = out?.reasoning ?: ""
                val remaining = out?.files?.map { it.path } ?: emptyList()
                val xmlRank = paths.indexOfFirst { it.endsWith("sql_survey.xml") } + 1
                val xmlKept = if (xmlRank > 0 && out != null) remaining.any { it.endsWith("sql_survey.xml") } else null
                val daoMapperKept = remaining.any { it.endsWith(".xml") || Regex("Dao|Mapper").containsMatchIn(it.substringAfterLast('/')) }
                val contradiction = (daoNotNeeded.containsMatchIn(reasoning) && !reasoning.contains("이메일 컬럼") && daoMapperKept && !sqlNeeded.containsMatchIn(reasoning)) ||
                    (sqlNeeded.containsMatchIn(reasoning) && !daoMapperKept)
                val claim = claimRe.containsMatchIn(reasoning)
                val userNoReq = client.sentUser.let { u -> u.substringBefore("## 요구사항") + u.substringAfter("## 후보 파일 목록", "") }
                val evInput = emailRe.containsMatchIn(userNoReq)
                val evBodies = emailRe.containsMatchIn(bodies)
                val r = RunOut(srKey, condKey, run, ms, if (out == null) "exception" else if (out.usedFallback) "fallback" else "ok",
                    client.sentSystem.length + client.sentUser.length, (client.sentSystem.length + client.sentUser.length) / 3, bodies.length, xmlRank, xmlKept,
                    remaining.map { it.substringAfterLast('/') }, (out?.removed ?: emptyList()).map { it.substringAfterLast('/') }, reasoning,
                    contradiction, claim, evInput, evBodies, claim && !evInput)
                println("[B77EXP] $srKey $condKey run$run: ${ms / 1000}s ${r.outcome} 입력 ${r.inputChars}자 남음 ${remaining.size}/${paths.size} xml=${xmlKept} 불일치(정규식)=$contradiction 주장=$claim 근거=$evInput")
                r
            })
            jobs.add(Triple(srKey, condKey, f))
        }
        val results = jobs.map { (s, c, f) ->
            try { f.get(300, TimeUnit.SECONDS) } catch (e: TimeoutException) { f.cancel(true); null }
        }.filterNotNull()
        pool.shutdownNow()

        val root = File("build/eval-reports")
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val sb = StringBuilder("# B-77 4 Stage 3 본문 입력 실험 ($stamp)\n\n")
        sb.appendLine("후보 순서는 모의 탐색기(시드 고정)로 캡처했다. 입력 토큰은 문자수÷3 추정(서버 값 아님). 불일치(정규식)는 거친 규칙이며 사람 확인은 아래 회차별 요약 전문으로 한다.\n")
        sb.appendLine("| 정제문 | 조건 | 후보 수 | xml 후보 순위 | 5회 xml 남음 | 5회 남은 수 | 서로 다른 목록 | 입력 자(추정 토큰) | 본문 자 | 시간(초) | 불일치(정규식) | 주장 | 근거 없는 주장 | 비정상(예외·대체) |")
        sb.appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
        for (srKey in srs.keys) for (condKey in conds.keys) {
            val rs = results.filter { it.sr == srKey && it.cond == condKey }.sortedBy { it.run }
            if (rs.isEmpty()) continue
            sb.appendLine("| $srKey | $condKey | ${cands.getValue(srKey).size} | ${rs.first().xmlRank} | ${rs.joinToString("/") { it.xmlKept?.let { k -> if (k) "O" else "X" } ?: "-" }} | ${rs.joinToString("/") { it.remaining.size.toString() }} | ${rs.map { it.remaining.sorted() }.toSet().size} | ${rs.first().inputChars}(${rs.first().estTokens}) | ${rs.first().bodyChars} | ${rs.joinToString("/") { (it.ms / 1000).toString() }} | ${rs.count { it.contradictionRegex }}/${rs.size} | ${rs.count { it.claim }}/${rs.size} | ${rs.count { it.unsupportedClaim }}/${rs.size} | ${rs.count { it.outcome != "ok" }} |")
        }
        sb.appendLine("\n## 회차별 요약 전문과 남은 파일(사람 확인용)")
        for (r in results.sortedWith(compareBy({ it.sr }, { it.cond }, { it.run }))) {
            sb.appendLine("\n### ${r.sr} ${r.cond} run${r.run} (${r.ms / 1000}초, ${r.outcome}, 불일치(정규식)=${r.contradictionRegex}, 주장=${r.claim}, 입력 이메일 근거=${r.evidenceInInput}, 본문 이메일 근거=${r.evidenceInBodies})")
            sb.appendLine("- 남음(${r.remaining.size}): ${r.remaining}")
            sb.appendLine("- 요약: ${r.reasoning}")
        }
        File(root, "b77_body_experiment_$stamp.md").writeText(sb.toString(), Charsets.UTF_8)
        File(root, "b77_body_experiment_$stamp.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(results), Charsets.UTF_8)
        println("[B77EXP] 보고서: ${File(root, "b77_body_experiment_$stamp.md").absolutePath}")
    }
}
