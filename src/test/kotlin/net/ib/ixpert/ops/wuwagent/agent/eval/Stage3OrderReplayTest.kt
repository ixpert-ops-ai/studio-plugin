package net.ib.ixpert.ops.wuwagent.agent.eval

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import net.ib.ixpert.ops.wuwagent.agent.FileRelevanceVerifier
import net.ib.ixpert.ops.wuwagent.agent.TargetFileSpec
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.client.OpenAIClient
import net.ib.ixpert.ops.wuwagent.model.ChatCompletionResponse
import net.ib.ixpert.ops.wuwagent.model.ChatMessage
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.ToolDefinition
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.util.Date

/**
 * B-48 D1 정제문 순서 진단(Stage 3 쪽): 정제문의 절 순서만 바꾼 요청을 운영 FileRelevanceVerifier.verify(buildBatchPrompt)로 만들어 원본/변형을 번갈아 순차 3회씩 호출한다(사내 Qwen).
 * 원본 후보로 만든 프롬프트가 덤프와 같은지 먼저 확인하고 다르면 멈춘다(신규 제안 후보는 정보 블록을 description으로 복원).
 * 실행: ./gradlew test --tests "net.ib.ixpert.ops.wuwagent.agent.eval.Stage3OrderReplayTest" -DrunLiveLlmTests=true -i
 * 옵션: -Deval.order.dumps=dumps_defer -Deval.order.live=live_eval_20261016_111920.json -Deval.replay.runs=3
 */
class Stage3OrderReplayTest {

    companion object {
        const val SUFFIX = "하여 설문 발송 채널에 브랜드메시지를 추가한다."
        const val FRONT = "설문 발송 채널에 브랜드메시지를 추가한다."

        /** 마지막 절을 맨 앞으로 옮기고 연결어미 "개발하여"를 종결어미로 바꾼다(그 밖의 절·단어는 그대로). */
        fun reorder(original: String): String {
            val trailing = original.takeLastWhile { it.isWhitespace() }   // 시드 SR 원문에 붙은 줄바꿈 등은 그대로 보존
            val core = original.trimEnd()
            require(core.endsWith(SUFFIX)) { "정제문이 예상한 끝 절로 끝나지 않음: $original" }
            return FRONT + " " + core.removeSuffix(SUFFIX) + "한다." + trailing
        }
    }

    private fun norm(p: String) = p.replace('\\', '/')

    private class CapturingClient : LLMClient {
        @Volatile var user: String? = null
        override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): OllamaChatResponse? = null
        override fun chatWithTools(
            systemPrompt: String, messages: List<ChatMessage>, maxTokens: Int?, tools: List<ToolDefinition>?, toolChoice: Any?, temperature: Double?
        ): ChatCompletionResponse? { user = messages.last().content; return null }
        override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
    }

    data class RunOut(
        val variant: String, val run: Int, val ms: Long, val kept: List<String>, val removed: List<String>, val unjudged: List<String>,
        val reasoning: String, val keptReasons: Map<String, String>, val error: String?
    )

    @Test
    fun replayOrder() {
        Assume.assumeTrue(
            "Live vLLM 재생은 -DrunLiveLlmTests=true 또는 환경변수 RUN_LIVE_LLM_TESTS=true 명시 시에만 활성화됩니다.",
            System.getProperty("runLiveLlmTests") == "true" || System.getenv("RUN_LIVE_LLM_TESTS") == "true"
        )
        val root = File(System.getProperty("eval.out") ?: "build/eval-reports")
        val runs = System.getProperty("eval.replay.runs")?.toIntOrNull() ?: 3
        val dumps = System.getProperty("eval.order.dumps") ?: "dumps_defer"
        val liveName = System.getProperty("eval.order.live") ?: "live_eval_20261016_111920.json"
        val sc = Gson().fromJson(javaClass.getResource("/eval/scenarios.json")!!.readText(Charsets.UTF_8), EvalScenarioFile::class.java).scenarios!!.first { it.id == "D1" }
        val live = JsonParser.parseString(File(root, liveName).readText(Charsets.UTF_8)).asJsonObject
        val s3 = live.getAsJsonArray("calls").map { it.asJsonObject }.filter { it.get("scenarioId").asString == "D1" && it.get("stage").asString.contains("Stage3") }.minByOrNull { it.get("startMs").asLong }!!
        val user3 = JsonParser.parseString(File(root, "$dumps/D1_run1_c${s3.get("seq").asInt}.req.json").readText(Charsets.UTF_8)).asJsonObject
            .getAsJsonArray("messages").map { it.asJsonObject }.first { it.get("role").asString == "user" }.get("content").asString
        val query = user3.substringAfter("## 요구사항\n").substringBefore("\n\n## 후보 파일 목록")
        val graph = Gson().fromJson(File(EvalScenarios.graphPaths().getValue(sc.graphKey)).readText(Charsets.UTF_8), ProjectGraph::class.java).normalizeLegacyCollections()
        // 후보 복원: "- [n] 경로\n--- 정보 ---\n<정보>\n-------------"
        val specs = Regex("^- \\[(\\d+)] (\\S+)\\n--- 정보 ---\\n(.*?)\\n-------------", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL)).findAll(user3).map { m ->
            val info = m.groupValues[3]
            if (info.startsWith("- (신규 제안)")) TargetFileSpec(m.groupValues[1].toInt(), m.groupValues[2], "CREATE", info.removePrefix("- (신규 제안)\n"))
            else TargetFileSpec(m.groupValues[1].toInt(), m.groupValues[2], "MODIFY", "")
        }.toList()
        val cap = CapturingClient()
        FileRelevanceVerifier(cap, graph, Paths.get("nonexistent-docs")).verify(query, specs)
        val same = cap.user == user3
        println("[ORDER] 복원 검증: buildBatchPrompt 출력 == 덤프 사용자 메시지: $same (후보 ${specs.size}개, 신규 ${specs.count { it.type == "CREATE" }}개)")
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val sb = StringBuilder()
        sb.appendLine("# D1 정제문 순서 진단: Stage 3 ($stamp)")
        sb.appendLine()
        sb.appendLine("- 요청 원본: $liveName 의 D1 Stage 3 요청($dumps), 후보 ${specs.size}개. 복원 검증: 원본 후보로 만든 프롬프트가 덤프와 ${if (same) "같음" else "다름"}")
        if (!same) {
            File(root, "stage3_order_mismatch.txt").writeText("[재생]\n${cap.user}\n\n[덤프]\n$user3", Charsets.UTF_8)
            sb.appendLine("- 운영과 같은 형식으로 만들 수 없어 실행하지 않음(차이는 stage3_order_mismatch.txt)")
            File(root, "stage3_order_replay_$stamp.md").writeText(sb.toString(), Charsets.UTF_8)
            println(sb.toString())
            return
        }
        val variantQuery = reorder(query)
        sb.appendLine("- 원본 정제문: $query")
        sb.appendLine("- 변형 정제문: $variantQuery")
        val ssi = specs.first { it.path.endsWith("/SurveyServiceImpl.java") }
        val client = OpenAIClient()
        fun gtMatch(g: EvalGt, p: String) = norm(p).contains(g.name ?: g.path ?: "", ignoreCase = true)
        val required = sc.gtOrEmpty().filter { it.countsForRecall() }
        val results = ArrayList<RunOut>()
        fun runOnce(variant: String, run: Int): RunOut {
            val q = if (variant == "variant") variantQuery else query
            var out: FileRelevanceVerifier.VerificationOutput? = null
            var error: String? = null
            val t0 = System.currentTimeMillis()
            var attempts = 0
            while (out == null && attempts < 2) {
                attempts++
                try { out = FileRelevanceVerifier(client, graph, Paths.get("nonexistent-docs")).verify(q, specs) }
                catch (e: Throwable) { error = generateSequence(e) { it.cause }.last().let { "${it.javaClass.simpleName}: ${(it.message ?: "").take(100)}" } }
            }
            val ms = System.currentTimeMillis() - t0
            val o = out
            val r = RunOut(variant, run, ms, o?.files?.map { it.path } ?: emptyList(), o?.removed ?: emptyList(), o?.unjudged ?: emptyList(), o?.displayReasoning()?.text ?: "",
                o?.files?.associate { it.path to it.description } ?: emptyMap(), if (o == null) error else null)
            println("[ORDER] $variant run$run: ${ms / 1000}s 유지 ${r.kept.size}/${specs.size} SurveyServiceImpl=${if (ssi.path in r.kept) "유지" else if (ssi.path in r.removed) "제거" else "누락"}")
            return r
        }
        for (run in 1..runs) { results.add(runOnce("orig", run)); results.add(runOnce("variant", run)) }
        sb.appendLine()
        sb.appendLine("| 조건 | SurveyServiceImpl 유지 | 기존 유지 GT(후보 안 required, 3회) | 유지 파일 수(3회) | 시간 평균(초) |")
        sb.appendLine("|---|---|---|---|---|")
        for (v in listOf("orig", "variant")) {
            val rs = results.filter { it.variant == v }
            sb.appendLine("| ${if (v == "orig") "원본" else "변형"} | ${rs.count { ssi.path in it.kept }}/${rs.size} | ${rs.joinToString("/") { r -> required.count { g -> specs.any { s -> gtMatch(g, s.path) && s.path in r.kept } }.toString() }} | ${rs.joinToString("/") { it.kept.size.toString() }} | ${rs.map { it.ms }.average().toLong() / 1000} |")
        }
        sb.appendLine()
        for (r in results) sb.appendLine("- ${if (r.variant == "orig") "원본" else "변형"} run${r.run}: SurveyServiceImpl=" + (if (ssi.path in r.kept) "유지(사유: ${r.keptReasons[ssi.path]})" else if (ssi.path in r.removed) "제거" else "누락") + " / 유지 ${r.kept.map { it.substringAfterLast('/') }} / 요약 원문: " + r.reasoning)
        File(root, "stage3_order_replay_$stamp.md").writeText(sb.toString(), Charsets.UTF_8)
        File(root, "stage3_order_replay_$stamp.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(results), Charsets.UTF_8)
        println(sb.toString())
    }
}
