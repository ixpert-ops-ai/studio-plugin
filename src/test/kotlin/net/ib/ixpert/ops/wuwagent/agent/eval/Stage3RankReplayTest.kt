package net.ib.ixpert.ops.wuwagent.agent.eval

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import net.ib.ixpert.ops.wuwagent.client.OpenAIClient
import net.ib.ixpert.ops.wuwagent.model.ChatMessage
import net.ib.ixpert.ops.wuwagent.model.FunctionDefinition
import net.ib.ixpert.ops.wuwagent.model.FunctionParameters
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
 * B-43 Stage 3 E(제거 대신 순위만) 재생: 154520 덤프의 Stage 3 요청(요구사항·번호가 붙은 후보 목록)을 그대로 쓰고,
 * 시스템 프롬프트·도구 스키마·마지막 지시문만 E 변형(stage3_rank_variant_prompt.md)으로 바꿔 운영 OpenAIClient로 시나리오마다 3회 호출한다.
 * A(현재 V2) 값은 92ddb39 재생 결과(stage3_impl_replay_*.json)를 읽어 쓴다(다시 돌리지 않는다).
 * 실행: ./gradlew test --tests "net.ib.ixpert.ops.wuwagent.agent.eval.Stage3RankReplayTest" -DrunLiveLlmTests=true -i
 * 옵션: -Deval.replay.runs=3 -Deval.replay.parallel=4 -Deval.out=<폴더> -Deval.rank.aref=<A 재생 json>
 */
class Stage3RankReplayTest {

    companion object {
        val SYSTEM_E = """
당신은 코드 변경 범위 판정자입니다.
아래 요구사항을 구현하기 위해 수정이 필요한 정도에 따라 후보 파일의 순위를 매깁니다.
반드시 제공된 `submit_ranking` 도구를 호출하여 결과를 제출하세요.

## 역할
- 어떤 후보도 제거하지 마세요. 모든 후보에 순위를 매기세요.
- 요구사항을 구현하기 위해 반드시 수정해야 하는 파일일수록 앞에, 수정하지 않아도 영향이 없는 파일일수록 뒤에 두세요.
- 파일 간의 의존 관계를 반드시 고려하세요.
  예: Service에서 새로운 데이터를 저장해야 한다면, 해당 데이터를 담는 Entity도 수정이 필요합니다.

## 판정 원칙
- 새로운 필드, 상태값, 메서드를 추가해야 요구사항을 구현할 수 있다면, 현재 해당 코드가 없더라도 수정 대상이므로 앞쪽 순위입니다.
- 앞쪽 순위에서 내릴 확실한 근거가 없으면 앞쪽에 두세요.
- 요구사항의 데이터 흐름(입력 → 처리 → 저장)을 추적하여 판단하세요.
- reason은 반드시 해당 파일에서 구체적으로 어떤 수정이 필요한지를 포함해야 합니다. 빈 문자열이나 '수정이 필요합니다' 같은 동어반복은 허용되지 않습니다.
- 중요: JSON 응답 생성 시, reason 필드 값 내부에 실제 줄바꿈 문자(\n)를 사용하지 마세요. 줄바꿈 대신 띄어쓰기나 마침표를 사용하세요.

## 신규 파일 순위 원칙
- (신규 제안) 정보가 있는 파일은 "새로 만들 필요가 있는가?"를 기준으로 순위를 정합니다.
- 다음 중 하나라도 해당하면 뒤쪽 순위에 두세요:
  · 요구사항이 단일 기능 추가/수정이고, 기존 파일 1~2개 수정으로 구현 가능한 경우
  · 기존 파일의 수정으로 동일한 목적을 달성할 수 있는 경우
  · 요구사항의 복잡도 대비 과설계인 경우 (단순 기능에 별도 계층 분리 등)
  · 기존 패키지에 동일 역할의 클래스/파일이 이미 존재하는 경우
- 신규 파일을 앞쪽에 두려면, 기존 파일에 해당 책임을 추가했을 때 단일 책임 원칙이 명백히 위반되는 경우여야 합니다.

## 출력 형식(중요)
- 후보 파일마다 [번호]가 붙어 있습니다. 경로를 다시 쓰지 말고 번호만 사용하세요.
- ranking에는 모든 후보 번호를 한 번씩, 수정이 필요한 정도가 큰 순서대로 나열하세요.
- reasons에는 ranking의 상위 10개에 대해서만 번호와 구체적인 수정 내용(reason)을 쓰세요.
- JSON은 들여쓰기·줄바꿈 없이 한 줄로 출력하세요.
- 각 reason은 40자 이내 한 문장으로 쓰세요.
""".trim()

        const val INSTRUCTION_A = "위 후보 파일 목록에서 수정이 필요한 후보는 requiredFiles에, 제거할 후보는 removedFiles(번호만)에 담고 reasoning을 작성하세요."
        const val INSTRUCTION_E = "위 후보 파일 목록 전체의 순위를 ranking에 담고, 상위 10개 후보의 수정 내용을 reasons에 담으세요."

        val TOOL_E = ToolDefinition(
            function = FunctionDefinition(
                name = "submit_ranking",
                description = "후보 파일의 수정 필요 순위를 제출합니다.",
                parameters = FunctionParameters(
                    properties = mapOf(
                        "ranking" to PropertyDefinition(type = "array", description = "모든 후보 번호를 수정이 필요한 정도가 큰 순서로 한 번씩 나열", items = PropertyDefinition(type = "integer")),
                        "reasons" to PropertyDefinition(
                            type = "array", description = "상위 10개 후보의 번호와 수정 내용(40자 이내)",
                            items = PropertyDefinition(
                                type = "object",
                                properties = mapOf(
                                    "n" to PropertyDefinition(type = "integer", description = "후보 번호"),
                                    "reason" to PropertyDefinition(type = "string", description = "구체적인 수정 내용(40자 이내)")
                                ),
                                required = listOf("n", "reason")
                            )
                        )
                    ),
                    required = listOf("ranking", "reasons")
                )
            )
        )

        /** 순위 해석: 범위 밖·중복은 무시하고 빠진 번호는 순서대로 뒤에 붙인다. */
        data class Parsed(val order: List<Int>, val outOfRange: Int, val duplicates: Int, val missing: Int)

        fun parseRanking(ranking: List<Int>, n: Int): Parsed {
            val seen = LinkedHashSet<Int>()
            var oor = 0
            var dup = 0
            for (x in ranking) {
                if (x !in 1..n) { oor++; continue }
                if (!seen.add(x)) dup++
            }
            val missing = (1..n).filter { it !in seen }
            return Parsed(seen.toList() + missing, oor, dup, missing.size)
        }
    }

    private fun norm(p: String) = p.replace('\\', '/')

    private fun gtMatch(sc: EvalScenario, g: EvalGt, path: String): Boolean =
        if (sc.matchMode() == "harness-contains") path.contains(g.name ?: g.path ?: "", ignoreCase = true)
        else norm(path).equals(norm(g.path ?: ""), ignoreCase = true) || norm(path).endsWith("/" + norm(g.path ?: ""), ignoreCase = true)

    data class Case(val id: String, val system: String, val user: String, val paths: List<String>)

    data class ARun(val k: Int, val keptGt: Int, val ms: Long, val keptPaths: List<String>)

    data class RunOut(
        val id: String, val run: Int, val ms: Long, val ok: Boolean, val error: String?, val argsText: String?,
        val order: List<String>, val reasons: Map<Int, String>, val outOfRange: Int, val duplicates: Int, val missing: Int,
        val gtRanks: List<Int>, val nonGtMeanRank: Double?, val kA: Int, val aKeptGt: Int, val eTopKGt: Int, val eTop10Gt: Int
    )

    private fun loadCase(root: File, id: String): Case {
        val dir = File(root, "dumps_ui3")
        val resp = dir.listFiles { f -> f.name.startsWith("${id}_run1_") && f.name.endsWith(".resp.json") }!!.sortedBy { it.name }.first { f ->
            JsonParser.parseString(f.readText(Charsets.UTF_8)).asJsonObject.get("stage").asString.contains("Stage3")
        }
        val req = JsonParser.parseString(File(dir, resp.name.removeSuffix(".resp.json") + ".req.json").readText(Charsets.UTF_8)).asJsonObject
        val msgs = req.getAsJsonArray("messages").map { it.asJsonObject }
        val user = msgs.first { it.get("role").asString == "user" }.get("content").asString
        check(user.contains(INSTRUCTION_A)) { "$id: 사용자 메시지의 지시문이 예상과 다름" }
        val paths = Regex("^- \\[(\\d+)] (\\S+)", RegexOption.MULTILINE).findAll(user).sortedBy { it.groupValues[1].toInt() }.map { it.groupValues[2] }.toList()
        return Case(id, "", user.replace(INSTRUCTION_A, INSTRUCTION_E), paths)
    }

    @Test
    fun replayRankVariant() {
        Assume.assumeTrue(
            "Live vLLM 재생은 -DrunLiveLlmTests=true 또는 환경변수 RUN_LIVE_LLM_TESTS=true 명시 시에만 활성화됩니다.",
            System.getProperty("runLiveLlmTests") == "true" || System.getenv("RUN_LIVE_LLM_TESTS") == "true"
        )
        val root = File(System.getProperty("eval.out") ?: "build/eval-reports")
        val runs = System.getProperty("eval.replay.runs")?.toIntOrNull() ?: 3
        val parallel = System.getProperty("eval.replay.parallel")?.toIntOrNull() ?: 4
        val aRef = File(System.getProperty("eval.rank.aref") ?: File(root, "stage3_impl_replay_20261015_143137.json").path)
        val scenarios = EvalScenarios.loadAll().associateBy { it.id }
        val ids = listOf("D1", "D2", "D3", "D4", "D5", "S1", "S2", "S3")
        val cases = ids.map { loadCase(root, it) }
        val gtIdx = HashMap<String, List<Int>>()   // 시나리오 -> 후보 안 GT의 후보 번호(1부터)
        for (c in cases) {
            val sc = scenarios.getValue(c.id)
            val idx = sc.gtOrEmpty().filter { it.countsForRecall() }.mapNotNull { g ->
                c.paths.indexOfFirst { gtMatch(sc, g, it) }.takeIf { it >= 0 }?.plus(1)
            }.distinct()
            gtIdx[c.id] = idx
            println("[RANK] ${c.id}: 후보 ${c.paths.size}개, 후보 안 GT 번호 $idx")
        }
        // A 기준: 92ddb39 재생의 회차별 남은 수(K)와 남은 GT
        val aRuns = HashMap<String, List<ARun>>()
        JsonParser.parseString(aRef.readText(Charsets.UTF_8)).asJsonArray.map { it.asJsonObject }.groupBy { it.get("id").asString }.forEach { (id, list) ->
            val total = gtIdx[id]?.size ?: 0
            aRuns[id] = list.sortedBy { it.get("run").asInt }.map {
                ARun(it.getAsJsonArray("remaining").size(), total - it.get("gtRemoved").asInt, it.get("ms").asLong, it.getAsJsonArray("remaining").map { e -> e.asString })
            }
        }

        val client = OpenAIClient()
        val pool = Executors.newFixedThreadPool(parallel)
        val futures = ArrayList<java.util.concurrent.Future<RunOut>>()
        for (c in cases) for (run in 1..runs) {
            futures.add(pool.submit(Callable {
                val n = c.paths.size
                val a = aRuns[c.id]!![(run - 1).coerceAtMost(aRuns[c.id]!!.size - 1)]
                val t0 = System.currentTimeMillis()
                var argsText: String? = null
                var error: String? = null
                try {
                    val resp = client.chatWithTools(
                        systemPrompt = SYSTEM_E,
                        messages = listOf(ChatMessage(role = "user", content = c.user)),
                        maxTokens = 4000,
                        tools = listOf(TOOL_E),
                        toolChoice = mapOf("type" to "function", "function" to mapOf("name" to "submit_ranking"))
                    )
                    argsText = resp?.toolCalls?.firstOrNull { it.function.name == "submit_ranking" }?.function?.arguments
                    if (argsText == null) error = "도구 호출 없음(content=${resp?.content?.take(80)})"
                } catch (e: Exception) {
                    error = "${e.javaClass.simpleName}: ${(e.message ?: "").take(120)}"
                }
                val ms = System.currentTimeMillis() - t0
                var ranking: List<Int> = emptyList()
                val reasons = LinkedHashMap<Int, String>()
                if (argsText != null) {
                    try {
                        val o = JsonParser.parseString(argsText).asJsonObject
                        ranking = o.getAsJsonArray("ranking").map { it.asInt }
                        o.getAsJsonArray("reasons")?.forEach { r -> val ro = r.asJsonObject; reasons[ro.get("n").asInt] = ro.get("reason").asString }
                    } catch (e: Exception) {
                        error = "해석 실패: ${e.javaClass.simpleName}"
                    }
                }
                val ok = error == null
                val parsed = parseRanking(ranking, n)
                val rankOf = parsed.order.withIndex().associate { (i, x) -> x to i + 1 }
                val gts = gtIdx.getValue(c.id)
                val gtRanks = gts.map { rankOf.getValue(it) }.sorted()
                val nonGt = (1..n).filter { it !in gts }.map { rankOf.getValue(it) }
                val topK = parsed.order.take(a.k)
                val out = RunOut(
                    c.id, run, ms, ok, error, argsText, parsed.order.map { c.paths[it - 1].substringAfterLast('/') }, reasons,
                    parsed.outOfRange, parsed.duplicates, parsed.missing, gtRanks, if (nonGt.isEmpty()) null else nonGt.average(),
                    a.k, a.keptGt, gts.count { it in topK }, gts.count { it in parsed.order.take(10) }
                )
                println("[RANK] ${c.id} run$run: ${ms / 1000}s ok=$ok K=${a.k} E상위K GT=${out.eTopKGt} A남은GT=${a.keptGt} GT순위=$gtRanks 누락=${parsed.missing} 중복=${parsed.duplicates} 범위밖=${parsed.outOfRange}" + (error?.let { " 오류=$it" } ?: ""))
                out
            }))
        }
        val results = futures.map { it.get() }
        pool.shutdown()

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val sb = StringBuilder()
        sb.appendLine("# Stage 3 E(순위만) 재생 ($stamp)")
        sb.appendLine()
        sb.appendLine("- 덤프(154520 dumps_ui3)의 Stage 3 요청에서 지시문·시스템 프롬프트·도구만 E 변형으로 바꿔 OpenAIClient로 시나리오당 ${runs}회, 동시 ${parallel}개 호출. 변형 원문은 stage3_rank_variant_prompt.md")
        sb.appendLine("- A 값은 92ddb39 재생(${aRef.name})에서 읽음. 같은 K: 회차 r의 K = A r회차 남은 파일 수")
        sb.appendLine()
        sb.appendLine("| 시나리오 | 후보 | 후보 안 GT | K(A 3회) | A 남은 GT | E 상위 K의 GT(3회) | E 상위 10의 GT(3회) | GT 순위(3회) | GT 평균순위 / 비GT 평균순위 | 서로 다른 상위10 집합 | 시간 초 E(평균) / A(평균) | 형식 오류(누락·중복·범위밖 합) | 실패 |")
        sb.appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|---|")
        var sameKTotalE = 0
        var sameKTotalA = 0
        var sameKScenarioPass = 0
        var gtBig = 0
        var gtBigTop10 = 0
        var sepAll = true
        for (c in cases) {
            val rs = results.filter { it.id == c.id }.sortedBy { it.run }
            val good = rs.filter { it.ok }
            val gt = gtIdx.getValue(c.id).size
            val ar = aRuns.getValue(c.id)
            val eSum = good.sumOf { it.eTopKGt }
            val aSum = good.sumOf { it.aKeptGt }
            sameKTotalE += eSum; sameKTotalA += aSum
            if (good.size == rs.size && rs.indices.all { rs[it].eTopKGt >= rs[it].aKeptGt }) sameKScenarioPass++
            if (c.paths.size > 10) { gtBig += gt * good.size; gtBigTop10 += good.sumOf { it.eTop10Gt } }
            val gtMean = good.flatMap { it.gtRanks }.takeIf { it.isNotEmpty() }?.average()
            val nonMean = good.mapNotNull { it.nonGtMeanRank }.takeIf { it.isNotEmpty() }?.average()
            if (gtMean != null && nonMean != null && gtMean >= nonMean) sepAll = false
            val tops = good.map { it.order.take(10).sorted() }.toSet().size
            val eAvg = if (rs.isEmpty()) 0 else rs.sumOf { it.ms } / rs.size / 1000
            val aAvg = ar.sumOf { it.ms } / ar.size / 1000
            sb.appendLine("| ${c.id} | ${c.paths.size} | $gt | ${rs.joinToString("/") { it.kA.toString() }} | ${rs.joinToString("/") { it.aKeptGt.toString() }} | ${rs.joinToString("/") { it.eTopKGt.toString() }} (합 $eSum, A 합 $aSum) | ${rs.joinToString("/") { it.eTop10Gt.toString() }} | ${rs.joinToString(" ; ") { it.gtRanks.toString() }} | ${gtMean?.let { "%.1f".format(it) }} / ${nonMean?.let { "%.1f".format(it) }} | $tops | $eAvg / $aAvg | ${rs.sumOf { it.missing + it.duplicates + it.outOfRange }} | ${rs.count { !it.ok }} |")
        }
        sb.appendLine()
        sb.appendLine("- 같은 K 합계: E ${sameKTotalE} / A ${sameKTotalA} (회차 3개 합, 성공한 회차만). 시나리오별 E ≥ A 충족: ${sameKScenarioPass}/${cases.size}")
        sb.appendLine("- 후보 11개 이상 시나리오의 후보 안 GT 중 E 상위 10 안: ${gtBigTop10}/${gtBig}")
        sb.appendLine("- 분리도(모든 시나리오에서 GT 평균순위 < 비GT 평균순위): $sepAll")
        sb.appendLine("- 실패 회차: ${results.count { !it.ok }}/${results.size}, 형식 오류 회차: ${results.count { it.ok && (it.missing + it.duplicates + it.outOfRange) > 0 }}")
        sb.appendLine("- 최대 시간: ${results.maxOf { it.ms } / 1000}초")
        sb.appendLine()
        sb.appendLine("## 회차별 순위(상위 12)와 상위 reason 일부")
        for (r in results.sortedWith(compareBy({ it.id }, { it.run }))) {
            sb.appendLine("- ${r.id} run${r.run}: ${r.order.take(12)}" + if (r.reasons.isNotEmpty()) " / reason 예: " + r.reasons.entries.take(2).joinToString(" | ") { "[${it.key}] ${it.value}" } else "")
        }
        File(root, "stage3_rank_replay_$stamp.md").writeText(sb.toString(), Charsets.UTF_8)
        File(root, "stage3_rank_replay_$stamp.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(results), Charsets.UTF_8)
        println("[RANK] 보고서: ${File(root, "stage3_rank_replay_$stamp.md").absolutePath}")
    }
}
