package net.ib.ixpert.ops.wuwagent.agent.eval

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import net.ib.ixpert.ops.wuwagent.client.OpenAIClient
import net.ib.ixpert.ops.wuwagent.model.ChatMessage
import net.ib.ixpert.ops.wuwagent.model.PropertyDefinition
import net.ib.ixpert.ops.wuwagent.model.ToolDefinition
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.AgenticGraphTools
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * B-43/B-44 시드 선정 confirm_final_seeds의 rationale 길이 지시 변경 전/후 재생.
 * 154520 덤프에서 시나리오별 마지막 시드 호출의 요청(system + 이력 + tools + tool_choice)을 그대로 보내고, 후 조건에서는 tools의 rationale 설명만 바꾼다.
 * 운영 조건과 같게 temperature 0.0, 덤프의 max_tokens·tool_choice를 쓰고, 응답이 confirm이 아니면 도구를 운영과 같은 AgenticGraphTools로 실행해
 * 결과를 붙인 뒤 confirm이 나오거나 maxTurns(10, AgenticSeedSelector.kt:71)가 될 때까지 이어서 재생한다.
 * 실행: ./gradlew test --tests "net.ib.ixpert.ops.wuwagent.agent.eval.SeedRationaleReplayTest" -DrunLiveLlmTests=true -i
 * 옵션: -Deval.replay.runs=3 -Deval.replay.parallel=4 -Deval.out=<폴더> -Deval.seed.ids=D5(쉼표로 시나리오 선택)
 *       -Deval.seed.runsFor=D5:10(시나리오별 회차 지정, 쉼표로 여러 개) -Deval.seed.temp=0.0(기본 0.0, 운영 값)
 */
class SeedRationaleReplayTest {

    companion object {
        /** 운영 코드(AgenticGraphTools.DEFINITIONS)의 변경 후 설명과 같아야 한다(SeedRationaleDescriptionTest가 확인). */
        const val AFTER_DESCRIPTION = "해당 클래스들을 Seed로 선정한 근거와 구현 역할. 클래스당 한 문장 이하로 간결하게 작성"
        const val MAX_TURNS = 10   // AgenticSeedSelector.kt:71
    }

    private val gson = Gson()

    data class Case(
        val id: String, val system: String, val messages: List<ChatMessage>, val tools: List<ToolDefinition>,
        val maxTokens: Int?, val toolChoice: Any?, val beforeMs: Long, val startTurn: Int, val graph: ProjectGraph
    )

    data class RunOut(
        val id: String, val variant: String, val run: Int, val ms: Long, val error: String?,
        val firstToolNames: List<String>, val firstConfirmed: Boolean, val turns: Int, val confirmed: Boolean,
        val reachedLimit: Boolean, val endedWithoutTool: Boolean, val seeds: List<String>, val rationaleChars: Int,
        val turnMs: List<Long>, val argsText: String?
    )

    private fun loadCase(root: File, id: String, seq: Int, ms: Long, graph: ProjectGraph): Case {
        val req = JsonParser.parseString(File(root, "dumps_ui3/${id}_run1_c$seq.req.json").readText(Charsets.UTF_8)).asJsonObject
        val msgs = req.getAsJsonArray("messages").map { it.asJsonObject }
        val system = msgs.first { it.get("role").asString == "system" }.get("content").asString
        val rest = msgs.filter { it.get("role").asString != "system" }.map { gson.fromJson(it, ChatMessage::class.java) }
        val tools = req.getAsJsonArray("tools").map { gson.fromJson(it, ToolDefinition::class.java) }
        val tc = req.get("tool_choice")?.takeIf { !it.isJsonNull }?.let { gson.fromJson(it, Any::class.java) }
        val startTurn = rest.count { it.role == "assistant" } + 1
        return Case(id, system, rest, tools, req.get("max_tokens")?.asInt, tc, ms, startTurn, graph)
    }

    private fun withRationale(tools: List<ToolDefinition>, description: String): List<ToolDefinition> = tools.map { t ->
        if (t.function.name != "confirm_final_seeds") t
        else {
            val props = t.function.parameters.properties.toMutableMap()
            val old = props.getValue("rationale")
            props["rationale"] = PropertyDefinition(type = old.type, description = description)
            t.copy(function = t.function.copy(parameters = t.function.parameters.copy(properties = props)))
        }
    }

    /** 운영 selectSeeds 루프의 도구 실행과 같은 결과를 만든다(검색 중복 가드, 충분 검색 안내 포함). */
    private fun executeTool(name: String, argsJson: String, tools: AgenticGraphTools, executed: MutableSet<String>): String = when (name) {
        "search_graph_nodes" -> {
            val args = gson.fromJson(argsJson, Map::class.java)
            val q = args["query"]?.toString() ?: ""
            val hint = args["domain_hint"]?.toString()
            val key = "${q.trim().lowercase()}::${hint?.trim()?.lowercase() ?: ""}"
            if (executed.contains(key)) {
                gson.toJson(mapOf(
                    "warning" to "이미 실행된 검색어입니다. 동일한 검색을 반복하지 말고, 이전 검색 결과 후보 중 관련 노드를 inspect_node_detail로 확인하거나 confirm_final_seeds를 호출하세요.",
                    "status" to "DUPLICATE_QUERY"
                ))
            } else {
                executed.add(key)
                val res = tools.searchGraphNodes(q, hint, limit = 10)
                if (executed.size >= 3) {
                    val top = res.mapNotNull { r -> r["className"]?.toString()?.takeIf { it.isNotBlank() } }.take(5)
                    gson.toJson(mapOf(
                        "searchResults" to res,
                        "guidance" to "충분한 검색이 수행되었습니다. 지금까지 발견된 유력 후보 $top 중 핵심 클래스를 inspect_node_detail로 확인하거나 confirm_final_seeds를 호출하여 확정하세요."
                    ))
                } else gson.toJson(res)
            }
        }
        "inspect_node_detail" -> gson.toJson(tools.inspectNodeDetail(gson.fromJson(argsJson, Map::class.java)["class_name"]?.toString() ?: ""))
        "expand_connected_nodes" -> {
            val args = gson.fromJson(argsJson, Map::class.java)
            gson.toJson(tools.expandConnectedNodes(args["class_name"]?.toString() ?: "", args["direction"]?.toString() ?: "BOTH", (args["max_hops"] as? Number)?.toInt() ?: 1))
        }
        else -> gson.toJson(mapOf("error" to "알 수 없는 도구: $name"))
    }

    private fun runOne(client: OpenAIClient, c: Case, variant: String, run: Int, temperature: Double): RunOut {
        val tools = if (variant == "after") withRationale(c.tools, AFTER_DESCRIPTION) else c.tools
        val engine = AgenticGraphTools(c.graph)
        val messages = c.messages.toMutableList()
        val executed = mutableSetOf<String>()
        // 이력에서 이미 실행된 검색어를 복원한다
        messages.filter { it.role == "assistant" }.flatMap { it.toolCalls.orEmpty() }.filter { it.function.name == "search_graph_nodes" }.forEach { tc ->
            val a = gson.fromJson(tc.function.arguments, Map::class.java)
            executed.add("${a["query"]?.toString()?.trim()?.lowercase() ?: ""}::${a["domain_hint"]?.toString()?.trim()?.lowercase() ?: ""}")
        }
        var error: String? = null
        var firstNames = emptyList<String>()
        var firstConfirmed = false
        var seeds = emptyList<String>()
        var rationaleChars = 0
        var argsText: String? = null
        var confirmed = false
        var endedWithoutTool = false
        val turnMs = ArrayList<Long>()
        var turn = c.startTurn
        var usedTurns = 0
        try {
            while (turn <= MAX_TURNS) {
                val t0 = System.currentTimeMillis()
                val resp = client.chatWithTools(c.system, messages, c.maxTokens, tools, c.toolChoice, temperature)
                turnMs.add(System.currentTimeMillis() - t0)
                usedTurns++
                val msg = resp?.choices?.firstOrNull()?.message
                if (msg == null) { endedWithoutTool = true; break }
                messages.add(msg)
                val calls = msg.toolCalls
                if (calls.isNullOrEmpty()) { endedWithoutTool = true; break }
                if (usedTurns == 1) firstNames = calls.map { it.function.name }
                val confirm = calls.firstOrNull { it.function.name == "confirm_final_seeds" }
                if (confirm != null) {
                    val o = JsonParser.parseString(confirm.function.arguments).asJsonObject
                    seeds = o.getAsJsonArray("seed_classes").map { it.asString }
                    rationaleChars = o.get("rationale")?.asString?.length ?: 0
                    argsText = confirm.function.arguments
                    confirmed = true
                    if (usedTurns == 1) firstConfirmed = true
                    break
                }
                for (tc in calls) {
                    messages.add(ChatMessage(role = "tool", content = executeTool(tc.function.name, tc.function.arguments, engine, executed), toolCallId = tc.id))
                }
                turn++
            }
        } catch (e: Exception) {
            error = "${e.javaClass.simpleName}: ${(e.message ?: "").take(120)}"
        }
        val reached = !confirmed && !endedWithoutTool && error == null && turn > MAX_TURNS
        val ms = turnMs.sum()
        println("[SEED] ${c.id} $variant run$run: 구간 ${ms / 1000}s 턴 $usedTurns 첫호출=$firstNames confirm=$confirmed 시드=${seeds.size} rationale=${rationaleChars}자" + (if (reached) " 10턴소진" else "") + (if (endedWithoutTool) " 도구없이종료" else "") + (error?.let { " 오류=$it" } ?: ""))
        return RunOut(c.id, variant, run, ms, error, firstNames, firstConfirmed, usedTurns, confirmed, reached, endedWithoutTool, seeds, rationaleChars, turnMs, argsText)
    }

    @Test
    fun replayBeforeAfter() {
        Assume.assumeTrue(
            "Live vLLM 재생은 -DrunLiveLlmTests=true 또는 환경변수 RUN_LIVE_LLM_TESTS=true 명시 시에만 활성화됩니다.",
            System.getProperty("runLiveLlmTests") == "true" || System.getenv("RUN_LIVE_LLM_TESTS") == "true"
        )
        val root = File(System.getProperty("eval.out") ?: "build/eval-reports")
        val defaultRuns = System.getProperty("eval.replay.runs")?.toIntOrNull() ?: 3
        val runsFor = System.getProperty("eval.seed.runsFor")?.split(",")?.mapNotNull { it.split(":").takeIf { p -> p.size == 2 }?.let { p -> p[0].trim() to p[1].trim().toInt() } }?.toMap() ?: emptyMap()
        val parallel = System.getProperty("eval.replay.parallel")?.toIntOrNull() ?: 4
        val temperature = System.getProperty("eval.seed.temp")?.toDoubleOrNull() ?: 0.0
        val ids = System.getProperty("eval.seed.ids")?.split(",")?.map { it.trim() } ?: listOf("D1", "D2", "D3", "D4", "D5", "S1", "S2", "S3")
        val scenarios = EvalScenarios.loadAll().associateBy { it.id }
        val graphs = HashMap<String, ProjectGraph>()
        fun graphOf(key: String) = graphs.getOrPut(key) {
            Gson().fromJson(File(EvalScenarios.graphPaths().getValue(key)).readText(Charsets.UTF_8), ProjectGraph::class.java).normalizeLegacyCollections()
        }
        val live = JsonParser.parseString(File(root, "live_eval_20261015_154520.json").readText(Charsets.UTF_8)).asJsonObject
        val calls = live.getAsJsonArray("calls").map { it.asJsonObject }
        val cases = ids.map { id ->
            val last = calls.filter { it.get("scenarioId").asString == id && it.get("stage").asString.contains("시드") }.maxByOrNull { it.get("startMs").asLong }!!
            loadCase(root, id, last.get("seq").asInt, last.get("totalMs").asLong, graphOf(scenarios.getValue(id).graphKey))
        }
        println("[SEED] 재생 조건: temperature=$temperature maxTurns=$MAX_TURNS 동시=$parallel")
        cases.forEach { println("[SEED] ${it.id}: 이력 ${it.messages.size}개, 시작 턴 ${it.startTurn}, max_tokens=${it.maxTokens}, tool_choice=${it.toolChoice}, 154520 실측 ${it.beforeMs / 1000}초") }

        val client = OpenAIClient()
        val pool = Executors.newFixedThreadPool(parallel)
        val futures = ArrayList<java.util.concurrent.Future<RunOut>>()
        val maxRuns = cases.maxOf { runsFor[it.id] ?: defaultRuns }
        // 전/후를 번갈아 제출해 서버 부하 편향을 줄인다
        for (run in 1..maxRuns) for (c in cases) {
            if (run > (runsFor[c.id] ?: defaultRuns)) continue
            for (variant in listOf("before", "after")) futures.add(pool.submit(Callable { runOne(client, c, variant, run, temperature) }))
        }
        val results = futures.map { it.get() }
        pool.shutdown()

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val sb = StringBuilder()
        sb.appendLine("# 시드 rationale 길이 지시 변경 전/후 재생, 운영 조건 ($stamp)")
        sb.appendLine()
        sb.appendLine("- 조건: temperature=$temperature (운영 AgenticSeedSelector.kt:89), 덤프의 max_tokens·tool_choice, maxTurns=$MAX_TURNS, 동시 $parallel. 후 설명: \"$AFTER_DESCRIPTION\" (전: 덤프 원문)")
        sb.appendLine("- 마지막 호출이 confirm이 아니면 도구를 실행해 붙이고 confirm이 나올 때까지 이어서 재생(구간 시간 = 마지막 호출부터 confirm까지 합)")
        sb.appendLine()
        sb.appendLine("| 시나리오 | 회차 | 154520 마지막 호출(초) | 전 구간 평균(초) | 후 구간 평균(초) | 감소 | 전 첫 호출 confirm | 후 첫 호출 confirm | 전 평균 턴 | 후 평균 턴 | seed_classes 집합 종류 전 | 후 | 10턴 소진 전/후 | 도구없이 종료 전/후 | 오류 전/후 |")
        sb.appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
        val perScenario = ArrayList<Triple<String, Double, Double>>()
        var setB = 0; var setA = 0
        var anyGuard = 0
        var maxGap = 0.0
        for (c in cases) {
            val b = results.filter { it.id == c.id && it.variant == "before" }
            val a = results.filter { it.id == c.id && it.variant == "after" }
            fun avgMs(l: List<RunOut>) = if (l.isEmpty()) Double.NaN else l.map { it.ms }.average() / 1000
            val bm = avgMs(b.filter { it.confirmed }); val am = avgMs(a.filter { it.confirmed })
            perScenario.add(Triple(c.id, bm, am))
            val sb2 = b.filter { it.confirmed }.map { it.seeds.sorted() }.toSet().size
            val sa2 = a.filter { it.confirmed }.map { it.seeds.sorted() }.toSet().size
            setB += sb2; setA += sa2
            val guardB = b.count { it.reachedLimit }; val guardA = a.count { it.reachedLimit }
            val noToolB = b.count { it.endedWithoutTool }; val noToolA = a.count { it.endedWithoutTool }
            anyGuard += guardB + guardA + noToolB + noToolA
            val gap = (b.count { it.firstConfirmed }.toDouble() / b.size - a.count { it.firstConfirmed }.toDouble() / a.size) * 100
            maxGap = maxOf(maxGap, gap)
            sb.appendLine("| ${c.id} | ${b.size} | ${c.beforeMs / 1000} | ${"%.0f".format(bm)} | ${"%.0f".format(am)} | ${"%.0f".format((1 - am / bm) * 100)}% | ${b.count { it.firstConfirmed }}/${b.size} | ${a.count { it.firstConfirmed }}/${a.size} | ${"%.1f".format(b.map { it.turns }.average())} | ${"%.1f".format(a.map { it.turns }.average())} | $sb2 | $sa2 | $guardB/$guardA | $noToolB/$noToolA | ${b.count { it.error != null }}/${a.count { it.error != null }} |")
        }
        sb.appendLine()
        val bAvg = perScenario.map { it.second }.filter { !it.isNaN() }.average()
        val aAvg = perScenario.map { it.third }.filter { !it.isNaN() }.average()
        sb.appendLine("- 시나리오 평균 시드 구간 시간(시나리오별 평균의 평균): 전 ${"%.1f".format(bAvg)}초, 후 ${"%.1f".format(aAvg)}초, 감소 ${"%.1f".format((1 - aAvg / bAvg) * 100)}%")
        sb.appendLine("- 첫 호출 confirm 비율 차이(전 - 후)의 시나리오별 최댓값: ${"%.0f".format(maxGap)}%p")
        sb.appendLine("- seed_classes 집합 종류 합계: 전 $setB, 후 $setA (후 ≤ 전이어야 함)")
        sb.appendLine("- 10턴 소진(Guard 2/fallback 도달) + 도구 없이 종료 합계: $anyGuard, 오류 회차: ${results.count { it.error != null }}/${results.size}")
        sb.appendLine()
        sb.appendLine("## 시나리오별 시드 목록(회차별)")
        for (c in cases) for (v in listOf("before", "after")) {
            sb.appendLine("- ${c.id} $v: " + results.filter { it.id == c.id && it.variant == v }.sortedBy { it.run }.joinToString(" ; ") { if (it.confirmed) "${it.seeds.sorted()}(턴 ${it.turns})" else "(confirm 없음: 첫 호출 ${it.firstToolNames})" })
        }
        File(root, "seed_rationale_replay_op_$stamp.md").writeText(sb.toString(), Charsets.UTF_8)
        File(root, "seed_rationale_replay_op_$stamp.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(results), Charsets.UTF_8)
        println("[SEED] 보고서: ${File(root, "seed_rationale_replay_op_$stamp.md").absolutePath}")
    }
}
