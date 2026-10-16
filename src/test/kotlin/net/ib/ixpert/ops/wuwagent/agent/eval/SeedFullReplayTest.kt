package net.ib.ixpert.ops.wuwagent.agent.eval

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.client.OpenAIClient
import net.ib.ixpert.ops.wuwagent.model.ChatCompletionResponse
import net.ib.ixpert.ops.wuwagent.model.ChatMessage
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.PropertyDefinition
import net.ib.ixpert.ops.wuwagent.model.ToolDefinition
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.AgenticSeedSelector
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * B-45 시드 선정 전체 경로 전/후 재생: 운영 AgenticSeedSelector.selectSeeds를 첫 호출부터 그대로 돌리고,
 * 도구 목록의 rationale 설명만 전(e34b94d 이전 문구)/후(현재 문구)로 바꿔 보내는 얇은 래퍼 클라이언트를 쓴다.
 * 실행: ./gradlew test --tests "net.ib.ixpert.ops.wuwagent.agent.eval.SeedFullReplayTest" -DrunLiveLlmTests=true -i
 * 옵션: -Deval.seed.variants=after(전/후 중 일부만) -Deval.seed.ids=D1,S2 -Deval.replay.runs=3 -Deval.replay.parallel=4 -Deval.out=<폴더> -Deval.seed.dumps=dumps_op -Deval.seed.live=live_eval_20261016_143658.json
 */
class SeedFullReplayTest {

    companion object {
        const val BEFORE_DESCRIPTION = "해당 클래스들을 Seed로 선정한 구체적 근거 및 구현 역할 설명"
    }

    /** 도구 목록의 rationale 설명을 바꾸고 호출 수·마지막 이력을 기록하는 래퍼. 그 밖의 요청은 그대로 위임한다. */
    private class RewritingClient(private val delegate: LLMClient, private val rationaleDescription: String?) : LLMClient {
        val calls = AtomicInteger()
        @Volatile var lastMessages: List<ChatMessage> = emptyList()

        override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): OllamaChatResponse? =
            delegate.chat(systemPrompt, userCode, maxTokens, onChunk)

        override fun chatWithTools(
            systemPrompt: String, messages: List<ChatMessage>, maxTokens: Int?, tools: List<ToolDefinition>?, toolChoice: Any?, temperature: Double?
        ): ChatCompletionResponse? {
            calls.incrementAndGet()
            lastMessages = messages
            val sent = if (rationaleDescription == null) tools else tools?.map { t ->
                if (t.function.name != "confirm_final_seeds") t
                else {
                    val props = t.function.parameters.properties.toMutableMap()
                    props["rationale"] = PropertyDefinition(type = props.getValue("rationale").type, description = rationaleDescription)
                    t.copy(function = t.function.copy(parameters = t.function.parameters.copy(properties = props)))
                }
            }
            return delegate.chatWithTools(systemPrompt, messages, maxTokens, sent, toolChoice, temperature)
        }

        override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = delegate.fetchModels(baseUrl, apiKey)
    }

    data class RunOut(
        val id: String, val variant: String, val run: Int, val ms: Long, val turns: Int, val seeds: List<String>, val reasoningHead: String,
        val usedGuardOrFallback: Boolean, val keyInSeeds: Boolean, val keyInToolResults: Boolean, val error: String?
    )

    @Test
    fun replayFullSeedSelection() {
        Assume.assumeTrue(
            "Live vLLM 재생은 -DrunLiveLlmTests=true 또는 환경변수 RUN_LIVE_LLM_TESTS=true 명시 시에만 활성화됩니다.",
            System.getProperty("runLiveLlmTests") == "true" || System.getenv("RUN_LIVE_LLM_TESTS") == "true"
        )
        val root = File(System.getProperty("eval.out") ?: "build/eval-reports")
        val runs = System.getProperty("eval.replay.runs")?.toIntOrNull() ?: 3
        val parallel = System.getProperty("eval.replay.parallel")?.toIntOrNull() ?: 4
        val ids = System.getProperty("eval.seed.ids")?.split(",")?.map { it.trim() } ?: listOf("D1", "S2")
        val dumpsDir = System.getProperty("eval.seed.dumps") ?: "dumps_op"
        val liveName = System.getProperty("eval.seed.live") ?: "live_eval_20261016_143658.json"
        val keyOf = mapOf("D1" to "SurveyServiceImpl", "S2" to "MemberStatus")
        val scenarios = EvalScenarios.loadAll().associateBy { it.id }
        val graphs = HashMap<String, ProjectGraph>()
        fun graphOf(key: String) = graphs.getOrPut(key) {
            Gson().fromJson(File(EvalScenarios.graphPaths().getValue(key)).readText(Charsets.UTF_8), ProjectGraph::class.java).normalizeLegacyCollections()
        }
        val live = JsonParser.parseString(File(root, liveName).readText(Charsets.UTF_8)).asJsonObject
        val calls = live.getAsJsonArray("calls").map { it.asJsonObject }
        // 시나리오별 SR: 기준 평가의 첫 시드 호출 사용자 메시지에서 그대로 추출
        val srOf = ids.associateWith { id ->
            val first = calls.filter { it.get("scenarioId").asString == id && it.get("stage").asString.contains("시드") }.minByOrNull { it.get("startMs").asLong }!!
            val req = JsonParser.parseString(File(root, "$dumpsDir/${id}_run1_c${first.get("seq").asInt}.req.json").readText(Charsets.UTF_8)).asJsonObject
            val user = req.getAsJsonArray("messages").map { it.asJsonObject }.first { it.get("role").asString == "user" }.get("content").asString
            user.substringAfter("요구사항(SR):\n").substringBefore("\n\n위 요구사항을 분석하여")
        }
        srOf.forEach { (id, sr) -> println("[SEEDFULL] $id SR: ${sr.take(120)}") }
        println("[SEEDFULL] 재생 조건: selectSeeds 그대로(운영 temperature 0.0), 동시=$parallel, 전=이전 rationale 문구, 후=현재 문구")

        val base = OpenAIClient()
        val pool = Executors.newFixedThreadPool(parallel)
        val futures = ArrayList<java.util.concurrent.Future<RunOut>>()
        val variants = System.getProperty("eval.seed.variants")?.split(",")?.map { it.trim() } ?: listOf("before", "after")
        for (run in 1..runs) for (id in ids) for (variant in variants) {
            futures.add(pool.submit(Callable {
                val client = RewritingClient(base, if (variant == "before") BEFORE_DESCRIPTION else null)
                val graph = graphOf(scenarios.getValue(id).graphKey)
                val t0 = System.currentTimeMillis()
                var error: String? = null
                var seeds = emptyList<String>()
                var reasoning = ""
                try {
                    val res = AgenticSeedSelector(client).selectSeeds(srOf.getValue(id), graph)
                    seeds = res.seedClasses
                    reasoning = res.reasoning
                } catch (e: Throwable) {
                    val cause = generateSequence(e) { it.cause }.last()
                    error = "${cause.javaClass.simpleName}: ${(cause.message ?: e.message ?: "").take(120)}"
                }
                val ms = System.currentTimeMillis() - t0
                val key = keyOf.getValue(id)
                val toolText = client.lastMessages.filter { it.role == "tool" }.joinToString("\n") { it.content ?: "" }
                val guard = reasoning.startsWith("Auto-adopted") || reasoning.startsWith("Fallback")
                val out = RunOut(id, variant, run, ms, client.calls.get(), seeds, reasoning.take(60), guard, seeds.any { it == key }, toolText.contains(key), error)
                println("[SEEDFULL] $id $variant run$run: ${ms / 1000}s 턴 ${client.calls.get()} 시드=$seeds ${key}시드=${out.keyInSeeds} 결과에등장=${out.keyInToolResults}" + (if (guard) " Guard2/fallback" else "") + (error?.let { " 오류=$it" } ?: ""))
                out
            }))
        }
        val results = futures.map { it.get() }
        pool.shutdown()

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val sb = StringBuilder()
        sb.appendLine("# 시드 선정 전체 경로 전/후 재생 ($stamp)")
        sb.appendLine()
        sb.appendLine("- 운영 AgenticSeedSelector.selectSeeds를 첫 호출부터 그대로 호출(temperature 0.0), 전 = 도구 목록의 rationale 설명을 e34b94d 이전 문구로 바꿔 전송, 후 = 현재 문구. 시나리오당 전/후 각 ${runs}회, 동시 $parallel")
        sb.appendLine()
        sb.appendLine("| 시나리오 | 핵심 파일 | 조건 | 시드에 포함 | 도구 결과에 등장 | 평균 턴 | 구간 평균(초) | seed_classes 집합 종류 | Guard 2/fallback | 오류 |")
        sb.appendLine("|---|---|---|---|---|---|---|---|---|---|")
        for (id in ids) for (v in variants) {
            val r = results.filter { it.id == id && it.variant == v }
            sb.appendLine("| $id | ${keyOf.getValue(id)} | ${if (v == "before") "전" else "후"} | ${r.count { it.keyInSeeds }}/${r.size} | ${r.count { it.keyInToolResults }}/${r.size} | ${"%.1f".format(r.map { it.turns }.average())} | ${"%.0f".format(r.map { it.ms }.average() / 1000)} | ${r.map { it.seeds.sorted() }.toSet().size} | ${r.count { it.usedGuardOrFallback }} | ${r.count { it.error != null }} |")
        }
        sb.appendLine()
        sb.appendLine("## 회차별 최종 seed_classes")
        for (id in ids) for (v in variants) {
            sb.appendLine("- $id $v: " + results.filter { it.id == id && it.variant == v }.sortedBy { it.run }.joinToString(" ; ") { "${it.seeds.sorted()}(턴 ${it.turns}, ${it.ms / 1000}초)" })
        }
        File(root, "seed_full_replay_$stamp.md").writeText(sb.toString(), Charsets.UTF_8)
        File(root, "seed_full_replay_$stamp.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(results), Charsets.UTF_8)
        println("[SEEDFULL] 보고서: ${File(root, "seed_full_replay_$stamp.md").absolutePath}")
    }
}
