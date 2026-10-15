package net.ib.ixpert.ops.wuwagent.agent.eval

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import net.ib.ixpert.ops.wuwagent.agent.FileRelevanceVerifier
import net.ib.ixpert.ops.wuwagent.agent.TargetFileSpec
import net.ib.ixpert.ops.wuwagent.client.OpenAIClient
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * B-39 Stage 3 V2 구현 검증(실제 구현 코드 경로 재생): 덤프의 Stage 3 요청에서 요구사항과 후보 목록을 복원해 `FileRelevanceVerifier.verify`를
 * 운영 클라이언트(OpenAIClient)로 시나리오마다 3회 호출한다. 프롬프트·스키마·파서가 모두 구현 코드이며, 남은 목록·제거·판정 누락·시간을 기록한다.
 * 실행: ./gradlew test --tests "net.ib.ixpert.ops.wuwagent.agent.eval.Stage3ImplReplayTest" -DrunLiveLlmTests=true -i
 * 옵션: -Deval.replay.runs=3 -Deval.replay.parallel=4 -Deval.out=<출력 폴더>
 */
class Stage3ImplReplayTest {

    private val sources = linkedMapOf(
        "D1" to "dumps_ui", "D2" to "dumps_ui", "D3" to "dumps_ui", "D4" to "dumps", "D5" to "dumps_ui",
        "S1" to "dumps_ui", "S2" to "dumps_ui", "S3" to "dumps_ui"
    )

    private fun norm(p: String) = p.replace('\\', '/')

    private fun gtMatch(sc: EvalScenario, g: EvalGt, path: String): Boolean =
        if (sc.matchMode() == "harness-contains") path.contains(g.name ?: g.path ?: "", ignoreCase = true)
        else norm(path).equals(norm(g.path ?: ""), ignoreCase = true) || norm(path).endsWith("/" + norm(g.path ?: ""), ignoreCase = true)

    data class Case(val id: String, val query: String, val candidates: List<TargetFileSpec>)

    data class RunOut(
        val id: String, val run: Int, val ms: Long, val remaining: List<String>, val removed: List<String>, val unjudged: List<String>,
        val reasoningSample: String, val gtRemoved: Int
    )

    private fun loadCase(root: File, id: String): Case {
        val dir = File(root, sources.getValue(id))
        val resp = dir.listFiles { f -> f.name.startsWith("${id}_run1_") && f.name.endsWith(".resp.json") }!!.sortedBy { it.name }.first { f ->
            JsonParser.parseString(f.readText(Charsets.UTF_8)).asJsonObject.get("stage").asString.contains("Stage3")
        }
        val req = JsonParser.parseString(File(dir, resp.name.removeSuffix(".resp.json") + ".req.json").readText(Charsets.UTF_8)).asJsonObject
        val user = req.getAsJsonArray("messages").map { it.asJsonObject }.first { it.get("role").asString == "user" }.get("content").asString
        val start = user.indexOf("## 요구사항\n") + "## 요구사항\n".length
        val end = user.indexOf("\n\n## 후보 파일 목록", start)
        val query = user.substring(start, end)
        val block = user.substring(end)
        val cands = ArrayList<TargetFileSpec>()
        val re = Regex("^- (\\S+)\\n--- 정보 ---\\n(.*?)\\n-------------", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))
        re.findAll(block).forEach { m ->
            val path = m.groupValues[1]
            val info = m.groupValues[2]
            if (info.startsWith("- (신규 제안)")) cands.add(TargetFileSpec(cands.size + 1, path, "CREATE", info.removePrefix("- (신규 제안)").trim()))
            else cands.add(TargetFileSpec(cands.size + 1, path, "MODIFY", ""))
        }
        return Case(id, query, cands)
    }

    @Test
    fun replayThroughImplementation() {
        Assume.assumeTrue(
            "Live vLLM 재생은 -DrunLiveLlmTests=true 또는 환경변수 RUN_LIVE_LLM_TESTS=true 명시 시에만 활성화됩니다.",
            System.getProperty("runLiveLlmTests") == "true" || System.getenv("RUN_LIVE_LLM_TESTS") == "true"
        )
        val root = File(System.getProperty("eval.out") ?: "build/eval-reports")
        val runs = System.getProperty("eval.replay.runs")?.toIntOrNull() ?: 3
        val parallel = System.getProperty("eval.replay.parallel")?.toIntOrNull() ?: 4
        val scenarios = EvalScenarios.loadAll().associateBy { it.id }
        val graphs = HashMap<String, ProjectGraph>()
        fun graphOf(key: String): ProjectGraph = graphs.getOrPut(key) {
            Gson().fromJson(File(EvalScenarios.graphPaths().getValue(key)).readText(Charsets.UTF_8), ProjectGraph::class.java).normalizeLegacyCollections()
        }

        val cases = sources.keys.map { loadCase(root, it) }
        cases.forEach { println("[IMPL] ${it.id}: 후보 ${it.candidates.size}개, 신규 ${it.candidates.count { c -> c.type == "CREATE" }}개") }

        val pool = Executors.newFixedThreadPool(parallel)
        val futures = ArrayList<java.util.concurrent.Future<RunOut>>()
        for (c in cases) for (run in 1..runs) {
            futures.add(pool.submit(Callable {
                val sc = scenarios.getValue(c.id)
                val t0 = System.currentTimeMillis()
                val verifier = FileRelevanceVerifier(OpenAIClient(), graphOf(sc.graphKey), Paths.get("nonexistent-docs"))
                val out = verifier.verify(c.query, c.candidates)
                val ms = System.currentTimeMillis() - t0
                val remaining = out.files.map { it.path }
                val gts = sc.gtOrEmpty().filter { it.countsForRecall() }.filter { g -> c.candidates.any { gtMatch(sc, g, it.path) } }
                val gtRemoved = gts.count { g -> remaining.none { gtMatch(sc, g, it) } }
                println("[IMPL] ${c.id} run$run: ${ms / 1000}s 남음 ${remaining.size}/${c.candidates.size} 제거 ${out.removed.size} 판정누락 ${out.unjudged.size} GT제거 $gtRemoved")
                RunOut(c.id, run, ms, remaining, out.removed, out.unjudged, out.reasoning.take(160), gtRemoved)
            }))
        }
        val results = futures.map { it.get() }
        pool.shutdown()

        val sb = StringBuilder()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        sb.appendLine("# Stage 3 V2 구현 경로 재생 ($stamp)")
        sb.appendLine()
        sb.appendLine("- 덤프의 요구사항·후보 목록을 복원해 FileRelevanceVerifier.verify(실제 프롬프트·스키마·파서)를 OpenAIClient로 시나리오당 ${runs}회, 동시 ${parallel}개로 호출")
        sb.appendLine()
        sb.appendLine("| 시나리오 | 후보 | 3회 남은 수 | 서로 다른 목록 수 | 후보 안 GT 제거(3회) | 판정 누락(3회 합) | 제거 후보 수(3회) | 시간(초, 최소~최대) |")
        sb.appendLine("|---|---|---|---|---|---|---|---|")
        var distinctTotal = 0
        val gtRemovedPerRun = IntArray(runs)
        var gtCandTotal = 0
        for (c in cases) {
            val sc = scenarios.getValue(c.id)
            val rs = results.filter { it.id == c.id }.sortedBy { it.run }
            val distinct = rs.map { it.remaining.sorted() }.toSet().size
            distinctTotal += distinct
            rs.forEach { gtRemovedPerRun[it.run - 1] += it.gtRemoved }
            val gtCand = sc.gtOrEmpty().filter { it.countsForRecall() }.count { g -> c.candidates.any { gtMatch(sc, g, it.path) } }
            gtCandTotal += gtCand
            sb.appendLine("| ${c.id} | ${c.candidates.size} | ${rs.joinToString("/") { it.remaining.size.toString() }} | $distinct | ${rs.joinToString("/") { it.gtRemoved.toString() }} (후보 안 GT $gtCand) | ${rs.sumOf { it.unjudged.size }} | ${rs.joinToString("/") { it.removed.size.toString() }} | ${rs.minOf { it.ms } / 1000}~${rs.maxOf { it.ms } / 1000} |")
        }
        sb.appendLine()
        sb.appendLine("- 서로 다른 남은 목록 수 합계(최소 ${cases.size}): $distinctTotal")
        sb.appendLine("- 후보 안 GT 제거 수(회차별 8개 시나리오 합): ${gtRemovedPerRun.joinToString(" / ")} (후보 안 GT ${gtCandTotal}개)")
        sb.appendLine("- 판정 누락 합계: ${results.sumOf { it.unjudged.size }}")
        sb.appendLine("- 최대 시간: ${results.maxOf { it.ms } / 1000}초")
        sb.appendLine()
        sb.appendLine("## 회차별 남은 파일")
        for (r in results.sortedWith(compareBy({ it.id }, { it.run }))) sb.appendLine("- ${r.id} run${r.run}: ${r.remaining.map { it.substringAfterLast('/') }}")
        File(root, "stage3_impl_replay_$stamp.md").writeText(sb.toString(), Charsets.UTF_8)
        File(root, "stage3_impl_replay_$stamp.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(results), Charsets.UTF_8)
        println("[IMPL] 보고서: ${File(root, "stage3_impl_replay_$stamp.md").absolutePath}")
    }
}
