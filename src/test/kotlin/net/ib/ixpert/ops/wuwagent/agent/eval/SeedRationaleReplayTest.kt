package net.ib.ixpert.ops.wuwagent.agent.eval

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import net.ib.ixpert.ops.wuwagent.client.OpenAIClient
import net.ib.ixpert.ops.wuwagent.model.ChatMessage
import net.ib.ixpert.ops.wuwagent.model.PropertyDefinition
import net.ib.ixpert.ops.wuwagent.model.ToolDefinition
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * B-43 시드 선정 마지막 호출(confirm_final_seeds)의 rationale 길이 지시 변경 전/후 재생.
 * 154520 덤프에서 시나리오별 마지막 시드 호출의 요청(system + 이력 + tools + tool_choice)을 그대로 보내고, 후 조건에서는 tools의 rationale 설명만 바꾼다.
 * 실행: ./gradlew test --tests "net.ib.ixpert.ops.wuwagent.agent.eval.SeedRationaleReplayTest" -DrunLiveLlmTests=true -i
 * 옵션: -Deval.replay.runs=3 -Deval.replay.parallel=4 -Deval.out=<폴더>
 */
class SeedRationaleReplayTest {

    companion object {
        /** 운영 코드(AgenticGraphTools.DEFINITIONS)의 변경 후 설명과 같아야 한다(SeedRationaleDescriptionTest가 확인). */
        const val AFTER_DESCRIPTION = "해당 클래스들을 Seed로 선정한 근거와 구현 역할. 클래스당 한 문장 이하로 간결하게 작성"
    }

    private val gson = Gson()

    data class Case(
        val id: String, val system: String, val messages: List<ChatMessage>, val tools: List<ToolDefinition>,
        val maxTokens: Int?, val toolChoice: Any?, val beforeMs: Long
    )

    data class RunOut(
        val id: String, val variant: String, val run: Int, val ms: Long, val error: String?, val toolNames: List<String>,
        val confirmed: Boolean, val seeds: List<String>, val rationaleChars: Int, val seedChars: Int, val contentChars: Int, val argsText: String?
    )

    private fun loadCase(root: File, id: String, seq: Int, ms: Long): Case {
        val req = JsonParser.parseString(File(root, "dumps_ui3/${id}_run1_c$seq.req.json").readText(Charsets.UTF_8)).asJsonObject
        val msgs = req.getAsJsonArray("messages").map { it.asJsonObject }
        val system = msgs.first { it.get("role").asString == "system" }.get("content").asString
        val rest = msgs.filter { it.get("role").asString != "system" }.map { gson.fromJson(it, ChatMessage::class.java) }
        val tools = req.getAsJsonArray("tools").map { gson.fromJson(it, ToolDefinition::class.java) }
        val tc = req.get("tool_choice")?.takeIf { !it.isJsonNull }?.let { gson.fromJson(it, Any::class.java) }
        return Case(id, system, rest, tools, req.get("max_tokens")?.asInt, tc, ms)
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

    @Test
    fun replayBeforeAfter() {
        Assume.assumeTrue(
            "Live vLLM 재생은 -DrunLiveLlmTests=true 또는 환경변수 RUN_LIVE_LLM_TESTS=true 명시 시에만 활성화됩니다.",
            System.getProperty("runLiveLlmTests") == "true" || System.getenv("RUN_LIVE_LLM_TESTS") == "true"
        )
        val root = File(System.getProperty("eval.out") ?: "build/eval-reports")
        val runs = System.getProperty("eval.replay.runs")?.toIntOrNull() ?: 3
        val parallel = System.getProperty("eval.replay.parallel")?.toIntOrNull() ?: 4
        val ids = listOf("D1", "D2", "D3", "D4", "D5", "S1", "S2", "S3")
        val live = JsonParser.parseString(File(root, "live_eval_20261015_154520.json").readText(Charsets.UTF_8)).asJsonObject
        val calls = live.getAsJsonArray("calls").map { it.asJsonObject }
        val cases = ids.map { id ->
            val last = calls.filter { it.get("scenarioId").asString == id && it.get("stage").asString.contains("시드") }.maxByOrNull { it.get("startMs").asLong }!!
            loadCase(root, id, last.get("seq").asInt, last.get("totalMs").asLong)
        }
        cases.forEach { println("[SEED] ${it.id}: 이력 ${it.messages.size}개, 도구 ${it.tools.map { t -> t.function.name }}, 154520 실측 ${it.beforeMs / 1000}초") }

        val client = OpenAIClient()
        val pool = Executors.newFixedThreadPool(parallel)
        val futures = ArrayList<java.util.concurrent.Future<RunOut>>()
        // 전/후를 번갈아 제출해 서버 부하 편향을 줄인다
        for (run in 1..runs) for (c in cases) for (variant in listOf("before", "after")) {
            futures.add(pool.submit(Callable {
                val tools = if (variant == "after") withRationale(c.tools, AFTER_DESCRIPTION) else c.tools
                val t0 = System.currentTimeMillis()
                var error: String? = null
                var names = emptyList<String>()
                var seeds = emptyList<String>()
                var rationaleChars = 0
                var seedChars = 0
                var contentChars = 0
                var argsText: String? = null
                try {
                    val resp = client.chatWithTools(c.system, c.messages, c.maxTokens, tools, c.toolChoice, null)
                    names = resp?.toolCalls?.map { it.function.name } ?: emptyList()
                    contentChars = resp?.content?.length ?: 0
                    val confirm = resp?.toolCalls?.firstOrNull { it.function.name == "confirm_final_seeds" }
                    argsText = confirm?.function?.arguments
                    if (confirm != null) {
                        val o = JsonParser.parseString(confirm.function.arguments).asJsonObject
                        seeds = o.getAsJsonArray("seed_classes").map { it.asString }
                        rationaleChars = o.get("rationale")?.asString?.length ?: 0
                        seedChars = o.get("seed_classes").toString().length
                    }
                } catch (e: Exception) {
                    error = "${e.javaClass.simpleName}: ${(e.message ?: "").take(120)}"
                }
                val ms = System.currentTimeMillis() - t0
                val out = RunOut(c.id, variant, run, ms, error, names, seeds.isNotEmpty(), seeds, rationaleChars, seedChars, contentChars, argsText)
                println("[SEED] ${c.id} $variant run$run: ${ms / 1000}s 도구=$names 시드=${seeds.size} rationale=${rationaleChars}자 content=${contentChars}자" + (error?.let { " 오류=$it" } ?: ""))
                out
            }))
        }
        val results = futures.map { it.get() }
        pool.shutdown()

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val sb = StringBuilder()
        sb.appendLine("# 시드 rationale 길이 지시 변경 전/후 재생 ($stamp)")
        sb.appendLine()
        sb.appendLine("- 후 설명: \"$AFTER_DESCRIPTION\" (전: 덤프 원문). 154520 마지막 시드 호출 요청을 그대로 재생, 시나리오당 전/후 각 ${runs}회, 동시 ${parallel}개")
        sb.appendLine()
        sb.appendLine("| 시나리오 | 154520 실측(초) | 전 평균(초) | 후 평균(초) | 감소 | 전 confirm 호출 | 후 confirm 호출 | 전 rationale 평균(자) | 후 rationale 평균(자) | 전 content 평균(자) | 후 content 평균(자) | seed_classes 집합 종류 전 | 후 |")
        sb.appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|---|")
        var bSum = 0.0; var aSum = 0.0; var bN = 0; var aN = 0; var setB = 0; var setA = 0
        for (c in cases) {
            val b = results.filter { it.id == c.id && it.variant == "before" }
            val a = results.filter { it.id == c.id && it.variant == "after" }
            val bc = b.filter { it.confirmed && it.error == null }
            val ac = a.filter { it.confirmed && it.error == null }
            fun avg(l: List<Long>) = if (l.isEmpty()) Double.NaN else l.average() / 1000
            fun avgI(l: List<Int>) = if (l.isEmpty()) Double.NaN else l.average()
            val bm = avg(bc.map { it.ms }); val am = avg(ac.map { it.ms })
            if (!bm.isNaN()) { bSum += bm; bN++ }
            if (!am.isNaN()) { aSum += am; aN++ }
            val sb2 = bc.map { it.seeds.sorted() }.toSet().size
            val sa2 = ac.map { it.seeds.sorted() }.toSet().size
            setB += sb2; setA += sa2
            sb.appendLine("| ${c.id} | ${c.beforeMs / 1000} | ${"%.0f".format(bm)} | ${"%.0f".format(am)} | ${"%.0f".format((1 - am / bm) * 100)}% | ${bc.size}/${b.size} | ${ac.size}/${a.size} | ${"%.0f".format(avgI(bc.map { it.rationaleChars }))} | ${"%.0f".format(avgI(ac.map { it.rationaleChars }))} | ${"%.0f".format(avgI(bc.map { it.contentChars }))} | ${"%.0f".format(avgI(ac.map { it.contentChars }))} | $sb2 | $sa2 |")
        }
        sb.appendLine()
        val reduction = if (bN > 0 && aN > 0) (1 - (aSum / aN) / (bSum / bN)) * 100 else Double.NaN
        sb.appendLine("- 시나리오 평균 시간: 전 ${"%.1f".format(bSum / bN)}초, 후 ${"%.1f".format(aSum / aN)}초, 감소 ${"%.1f".format(reduction)}%")
        sb.appendLine("- seed_classes 집합 종류 합계: 전 $setB, 후 $setA (후 ≤ 전이어야 함)")
        sb.appendLine("- 오류 회차: ${results.count { it.error != null }}/${results.size}, confirm 미호출 회차: ${results.count { it.error == null && !it.confirmed }}")
        sb.appendLine()
        sb.appendLine("## 시나리오별 시드 목록(회차별)")
        for (c in cases) for (v in listOf("before", "after")) {
            sb.appendLine("- ${c.id} $v: " + results.filter { it.id == c.id && it.variant == v }.sortedBy { it.run }.joinToString(" ; ") { if (it.confirmed) it.seeds.sorted().toString() else "(confirm 미호출: ${it.toolNames})" })
        }
        File(root, "seed_rationale_replay_$stamp.md").writeText(sb.toString(), Charsets.UTF_8)
        File(root, "seed_rationale_replay_$stamp.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(results), Charsets.UTF_8)
        println("[SEED] 보고서: ${File(root, "seed_rationale_replay_$stamp.md").absolutePath}")
    }
}
