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
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.ChangeIntent
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DiscoveryConfig
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DomainExtractor
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.GraphExpander
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.RelevanceScorer
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.SeedSelectionResult
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.util.Date

/**
 * B-48 1-e(1홉 호출자 보호) 회수 파일이 Stage 3에서 유지되는지 재생한다(사내 Qwen만).
 * 143658 덤프의 Stage 3 요청에서 요구사항과 후보 목록을 복원해 운영 FileRelevanceVerifier.verify(프롬프트는 buildBatchPrompt)로 원본/변형을 번갈아 순차 3회씩 호출한다.
 * 복원이 운영 프롬프트와 같은지(원본 후보의 buildBatchPrompt 출력 == 덤프 사용자 메시지)를 먼저 확인하고, 다르면 멈춘다.
 * 실행: ./gradlew test --tests "net.ib.ixpert.ops.wuwagent.agent.eval.Stage3AddedCandidatesReplayTest" -DrunLiveLlmTests=true -i
 * 옵션: -Deval.added.ids=D2,S3,D5 -Deval.replay.runs=3 -Deval.out=<폴더> -Deval.seed.dumps=dumps_op -Deval.seed.live=live_eval_20261016_143658.json
 */
class Stage3AddedCandidatesReplayTest {

    private fun norm(p: String) = p.replace('\\', '/')
    private fun base(p: String) = norm(p).substringAfterLast('/')

    private class CapturingClient : LLMClient {
        @Volatile var user: String? = null
        override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): OllamaChatResponse? = null
        override fun chatWithTools(
            systemPrompt: String, messages: List<ChatMessage>, maxTokens: Int?, tools: List<ToolDefinition>?, toolChoice: Any?, temperature: Double?
        ): ChatCompletionResponse? { user = messages.last().content; return null }
        override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
    }

    data class RunOut(
        val id: String, val variant: String, val run: Int, val ms: Long, val kept: List<String>, val removed: List<String>, val unjudged: List<String>,
        val reasoning: String, val keptReasons: Map<String, String>, val error: String?
    )

    private fun tier(reason: String?): Int = when (reason) {
        "Seed Class", "Judge Pick", "Frontend Hint" -> 1
        "Seed Direct Dependency", "Resource Reverse Link" -> 2
        "Frontend Resource" -> 3
        else -> 4
    }

    @Test
    fun replayAddedCandidates() {
        Assume.assumeTrue(
            "Live vLLM 재생은 -DrunLiveLlmTests=true 또는 환경변수 RUN_LIVE_LLM_TESTS=true 명시 시에만 활성화됩니다.",
            System.getProperty("runLiveLlmTests") == "true" || System.getenv("RUN_LIVE_LLM_TESTS") == "true"
        )
        val root = File(System.getProperty("eval.out") ?: "build/eval-reports")
        val runs = System.getProperty("eval.replay.runs")?.toIntOrNull() ?: 3
        val dumps = System.getProperty("eval.seed.dumps") ?: "dumps_op"
        val liveName = System.getProperty("eval.seed.live") ?: "live_eval_20261016_143658.json"
        val ids = System.getProperty("eval.added.ids")?.split(",")?.map { it.trim() } ?: listOf("D2", "S3", "D5")
        val addNames = mapOf("D2" to listOf("APCMMTrcdIsSVC", "APCMMTrcdIsSVCImpl"), "S3" to listOf("KmcisController"), "D5" to listOf("IpsController"))
        val scenarios = Gson().fromJson(javaClass.getResource("/eval/scenarios.json")!!.readText(Charsets.UTF_8), EvalScenarioFile::class.java).scenarios!!.associateBy { it.id }
        val live = JsonParser.parseString(File(root, liveName).readText(Charsets.UTF_8)).asJsonObject
        val calls = live.getAsJsonArray("calls").map { it.asJsonObject }
        val graphs = HashMap<String, ProjectGraph>()
        fun graphOf(key: String) = graphs.getOrPut(key) {
            Gson().fromJson(File(EvalScenarios.graphPaths().getValue(key)).readText(Charsets.UTF_8), ProjectGraph::class.java).normalizeLegacyCollections()
        }
        val client = OpenAIClient()
        val results = ArrayList<RunOut>()
        val sb = StringBuilder()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        sb.appendLine("# 1-e 회수 파일의 Stage 3 유지 여부 재생 ($stamp)")
        sb.appendLine()
        sb.appendLine("- 요청 원본: $liveName 의 Stage 3 요청($dumps). 운영 FileRelevanceVerifier.verify 그대로(temperature는 클라이언트 기본값), 시나리오마다 원본/변형 번갈아 순차 ${runs}회")
        var requiredKeptEnough = 0
        var anyExistingGtDrop = false
        val lines = StringBuilder()
        for (id in ids) {
            val sc = scenarios.getValue(id)
            val graph = graphOf(sc.graphKey)
            val mine = calls.filter { it.get("scenarioId").asString == id }.sortedBy { it.get("startMs").asLong }
            val s3 = mine.first { it.get("stage").asString.contains("Stage3") }
            val seedCalls = mine.filter { it.get("stage").asString.contains("시드") }
            val user3 = JsonParser.parseString(File(root, "$dumps/${id}_run1_c${s3.get("seq").asInt}.req.json").readText(Charsets.UTF_8)).asJsonObject
                .getAsJsonArray("messages").map { it.asJsonObject }.first { it.get("role").asString == "user" }.get("content").asString
            val query = user3.substringAfter("## 요구사항\n").substringBefore("\n\n## 후보 파일 목록")
            val paths = Regex("^- \\[\\d+] (\\S+)\\n--- 정보 ---\\n", RegexOption.MULTILINE).findAll(user3).map { it.groupValues[1] }.toList()
            check(!user3.contains("(신규 제안)")) { "$id: 신규 제안 후보가 있어 이 재생 방식을 쓸 수 없음" }
            val specs = paths.mapIndexed { i, p -> TargetFileSpec(i + 1, p, "MODIFY", "") }
            // 1) 복원 검증: 원본 후보로 buildBatchPrompt가 만든 사용자 메시지 == 덤프의 사용자 메시지
            val cap = CapturingClient()
            FileRelevanceVerifier(cap, graph, Paths.get("nonexistent-docs")).verify(query, specs)
            val same = cap.user == user3
            println("[ADDED] $id 복원 검증: buildBatchPrompt 출력 == 덤프 사용자 메시지: $same (후보 ${paths.size}개)")
            sb.appendLine("- $id 복원 검증: 원본 후보로 만든 프롬프트가 덤프와 ${if (same) "같음" else "다름"}")
            if (!same) {
                File(root, "stage3_added_mismatch_$id.txt").writeText("[재생]\n${cap.user}\n\n[덤프]\n$user3", Charsets.UTF_8)
                sb.appendLine("  → 운영과 같은 형식으로 만들 수 없어 $id 는 실행하지 않음(차이는 stage3_added_mismatch_$id.txt)")
                continue
            }
            // 2) 변형: 운영 정렬상 보호 2순위 블록 끝에 해당하는 자리에 추가 파일을 넣는다
            val seeds = JsonParser.parseString(JsonParser.parseString(File(root, "$dumps/${id}_run1_c${seedCalls.last().get("seq").asInt}.resp.json").readText(Charsets.UTF_8)).asJsonObject.get("toolArgs").asString)
                .asJsonObject.getAsJsonArray("seed_classes").map { it.asString }
            val srText = JsonParser.parseString(File(root, "$dumps/${id}_run1_c${seedCalls.first().get("seq").asInt}.req.json").readText(Charsets.UTF_8)).asJsonObject
                .getAsJsonArray("messages").map { it.asJsonObject }.first { it.get("role").asString == "user" }.get("content").asString
                .substringAfter("요구사항(SR):\n").substringBefore("\n\n위 요구사항을 분석하여")
            val frontend = listOf("어드민", "화면", "UI", "프론트", "vue", "jsp", "페이지", "웹", "frontend", "admin").any { srText.contains(it, ignoreCase = it.all { c -> c.isLowerCase() }) }
            val seedResult = SeedSelectionResult(seeds, ChangeIntent.MODIFY, listOf("SERVICE", "BIZ", "PRESENTATION"), frontend, "", judgePicks = seeds, rawCandidates = seeds)
            val expanded = GraphExpander(graph, DomainExtractor(graph.files), DiscoveryConfig(maxHop = 3)).expand(seedResult, srText)
            val scored = RelevanceScorer(graph, fileLimit = 30, minScore = 55).scoreAndFilter(srText, expanded, seedResult).associateBy { norm(it.path) }
            val insertAt = paths.indexOfFirst { tier(scored[norm(it)]?.protectionReason) >= 3 }.let { if (it < 0) paths.size else it }
            val addPaths = addNames.getValue(id).map { n -> graph.files.keys.first { graph.files.getValue(it).className == n } }
            val variantPaths = paths.toMutableList().apply { addAll(insertAt, addPaths) }
            val variantSpecs = variantPaths.mapIndexed { i, p -> TargetFileSpec(i + 1, p, "MODIFY", "") }
            println("[ADDED] $id 추가 ${addPaths.map { base(it) }} 위치 $insertAt (후보 ${paths.size} → ${variantPaths.size})")
            val required = sc.gtOrEmpty().filter { it.countsForRecall() }
            fun gtMatch(g: EvalGt, p: String): Boolean =
                if (sc.matchMode() == "harness-contains") norm(p).contains(g.name ?: g.path ?: "", ignoreCase = true)
                else norm(p).equals(norm(g.path ?: ""), ignoreCase = true) || norm(p).endsWith("/" + norm(g.path ?: ""), ignoreCase = true)

            fun runOnce(variant: String, run: Int): RunOut {
                val list = if (variant == "variant") variantSpecs else specs
                var out: FileRelevanceVerifier.VerificationOutput? = null
                var error: String? = null
                val t0 = System.currentTimeMillis()
                var attempts = 0
                while (out == null && attempts < 2) {
                    attempts++
                    try { out = FileRelevanceVerifier(client, graph, Paths.get("nonexistent-docs")).verify(query, list) }
                    catch (e: Throwable) { error = generateSequence(e) { it.cause }.last().let { "${it.javaClass.simpleName}: ${(it.message ?: "").take(100)}" } }
                }
                val ms = System.currentTimeMillis() - t0
                val o = out
                val r = RunOut(id, variant, run, ms, o?.files?.map { it.path } ?: emptyList(), o?.removed ?: emptyList(), o?.unjudged ?: emptyList(), o?.displayReasoning()?.text ?: "",
                    o?.files?.associate { it.path to it.description } ?: emptyMap(), if (o == null) error else null)
                println("[ADDED] $id $variant run$run: ${ms / 1000}s 유지 ${r.kept.size}/${list.size} 추가파일 ${addPaths.map { base(it) to (if (variant == "variant") (if (it in r.kept) "유지" else if (it in r.removed) "제거" else "누락") else "-") }}")
                return r
            }
            val mine2 = ArrayList<RunOut>()
            for (run in 1..runs) { mine2.add(runOnce("base", run)); mine2.add(runOnce("variant", run)) }
            results.addAll(mine2)

            val baseRuns = mine2.filter { it.variant == "base" }
            val varRuns = mine2.filter { it.variant == "variant" }
            fun keptExistingGt(r: RunOut) = required.count { g -> paths.any { p -> gtMatch(g, p) && p in r.kept } }
            val baseGt = baseRuns.sumOf { keptExistingGt(it) }
            val varGt = varRuns.sumOf { keptExistingGt(it) }
            if (varGt < baseGt) anyExistingGtDrop = true
            lines.appendLine("| $id | ${paths.size} → ${variantPaths.size} | ${base(addPaths.first())}${if (addPaths.size > 1) " 외 ${addPaths.size - 1}" else ""} | ${baseRuns.joinToString("/") { keptExistingGt(it).toString() }} (합 $baseGt) | ${varRuns.joinToString("/") { keptExistingGt(it).toString() }} (합 $varGt) | ${baseRuns.map { it.ms }.average().toLong() / 1000} / ${varRuns.map { it.ms }.average().toLong() / 1000} |")
            for (ap in addPaths) {
                val keptN = varRuns.count { ap in it.kept }
                val isReq = required.any { gtMatch(it, ap) }
                if (isReq && keptN >= 2) requiredKeptEnough++
                lines.appendLine("|  ↳ ${base(ap)} (${if (isReq) "required GT" else "GT 밖"}) | | 유지 ${keptN}/${varRuns.size}, 제거 ${varRuns.count { ap in it.removed }}, 누락 ${varRuns.count { ap in it.unjudged }} | | | |")
            }
            for (r in varRuns) {
                sb.appendLine("- $id 변형 run${r.run}: 추가 파일 " + addPaths.joinToString(", ") { ap -> base(ap) + "=" + (if (ap in r.kept) "유지(사유: ${r.keptReasons[ap]})" else if (ap in r.removed) "제거" else "누락") } + " / 요약 원문: " + r.reasoning.take(400))
            }
        }
        sb.appendLine()
        sb.appendLine("| 시나리오 | 후보 수 | 추가 파일 | 원본 유지 GT(3회) | 변형 유지 GT(3회, 추가 파일 제외) | Stage 3 시간 평균(초) 원본/변형 |")
        sb.appendLine("|---|---|---|---|---|---|")
        sb.append(lines)
        sb.appendLine()
        sb.appendLine("- 사전 판정: 추가한 required 파일 중 3회 중 2회 이상 유지된 파일 수 = $requiredKeptEnough (필요 2개 이상, 대상은 D2 SVC·SVCImpl, S3 KmcisController), 기존 유지 GT 감소 여부 = ${if (anyExistingGtDrop) "있음" else "없음"}")
        File(root, "stage3_added_replay_$stamp.md").writeText(sb.toString(), Charsets.UTF_8)
        File(root, "stage3_added_replay_$stamp.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(results), Charsets.UTF_8)
        println(sb.toString())
    }
}
