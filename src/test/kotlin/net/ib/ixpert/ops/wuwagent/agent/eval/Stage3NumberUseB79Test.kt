package net.ib.ixpert.ops.wuwagent.agent.eval

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.agent.FileRelevanceVerifier
import net.ib.ixpert.ops.wuwagent.agent.RequirementAnalysisPipeline
import net.ib.ixpert.ops.wuwagent.agent.Stage3Prompt
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

/**
 * B-79 9(실험만, 운영 연결 없음): Stage 3 프롬프트의 후보 번호를 없앤 효과. 같은 후보·같은 SR로 번호 방식(전)과 이름 방식(후)을 5회씩 실서버에 보내
 * 모델이 쓴 요약(reasoning 원문)에 후보 번호 인용("(10,12,13)", "[10]")이 몇 회 나오는지, 응답 해석 실패(대체 경로)가 몇 회인지, 남은 후보 목록이 같은지 센다.
 * 예측은 build/eval-reports/pred_b79.md(실행 전 작성). 실행: ./gradlew test --tests "*Stage3NumberUseB79Test" -DrunLiveLlmTests=true
 */
class Stage3NumberUseB79Test {

    private val surveyRoot = File("C:/Workspace/HC_card_survey_admin/survey_admin")
    private val sr = "설문 응답 테이블에 이메일 컬럼을 추가하여 수집하고, 관리자 설문 상세 조회 시 응답자 이메일을 앞 2자리와 도메인만 보이도록 마스킹하여 반환한다."
    private val numberCitation = Regex("\\(\\s*\\d+(?:[\\s,]+\\d+)*\\s*\\)|\\[\\s*\\d+\\s*]")

    /** 모의 탐색기(시드 고정)로 파이프라인을 태워 Stage 3 요청의 후보 순서를 캡처한다. 서버 호출 없음. */
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
            stage3User = messages.mapNotNull { it.content }.joinToString("\n")
            return call("submit_verification", "{\"requiredFiles\":[],\"removedFiles\":[],\"reasoning\":\"캡처\"}")
        }
        override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
    }

    data class Out(val cond: String, val run: Int, val ms: Long, val fallback: Boolean, val kept: List<String>, val raw: String, val shown: String, val numbersRaw: Boolean, val numbersShown: Boolean)

    @Test
    fun runExperiment() {
        Assume.assumeTrue(
            "Live vLLM 실험은 -DrunLiveLlmTests=true 또는 환경변수 RUN_LIVE_LLM_TESTS=true 명시 시에만 활성화됩니다.",
            System.getProperty("runLiveLlmTests") == "true" || System.getenv("RUN_LIVE_LLM_TESTS") == "true"
        )
        Assume.assumeTrue(File(surveyRoot, ".meta/project-graph.json").isFile)
        val g = Gson().fromJson(File(surveyRoot, ".meta/project-graph.json").readText(Charsets.UTF_8), ProjectGraph::class.java).normalizeLegacyCollections()
        val runs = System.getProperty("eval.replay.runs")?.toIntOrNull() ?: 5

        val stub = CaptureStub(listOf("SurveyServiceImpl", "SurveyDaoImpl", "SurveyDto", "sql_survey.xml"))
        kotlinx.coroutines.runBlocking {
            RequirementAnalysisPipeline(null, stub, true).analyze(primaryReq = sr, secondaryReq = "", projectGraph = g, projectRoot = surveyRoot) { }
        }
        val paths = Stage3Prompt.candidates(stub.stage3User ?: error("Stage 3 요청을 캡처하지 못했다")).map { it.second }
        println("[B79NUM] 후보 ${paths.size}개: ${paths.map { it.substringAfterLast('/') }}")
        val specs = paths.mapIndexed { i, p -> TargetFileSpec(i + 1, p, "MODIFY", "") }

        val conds = linkedMapOf("번호 방식(전)" to false, "이름 방식(후)" to true)
        val pool = Executors.newFixedThreadPool(3)
        val jobs = ArrayList<java.util.concurrent.Future<Out>>()
        for ((cond, names) in conds) for (run in 1..runs) {
            jobs.add(pool.submit(Callable {
                val t0 = System.currentTimeMillis()
                val out = try { FileRelevanceVerifier(OpenAIClient(), g, Paths.get("nonexistent-docs"), candidateNames = names).verify(sr, specs) } catch (e: Throwable) { null }
                val ms = System.currentTimeMillis() - t0
                val raw = out?.reasoning ?: ""
                val shown = out?.displayReasoning()?.text ?: ""
                val o = Out(cond, run, ms, out?.usedFallback ?: true, out?.files?.map { it.path.substringAfterLast('/') }?.sorted() ?: emptyList(), raw, shown, numberCitation.containsMatchIn(raw), numberCitation.containsMatchIn(shown))
                println("[B79NUM] $cond run$run: ${ms / 1000}s 대체=${o.fallback} 남음 ${o.kept.size} 원문번호=${o.numbersRaw} 화면번호=${o.numbersShown}")
                o
            }))
        }
        val results = jobs.mapNotNull { try { it.get(300, TimeUnit.SECONDS) } catch (e: Exception) { it.cancel(true); null } }
        pool.shutdownNow()

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val sb = StringBuilder("# B-79 9 Stage 3 후보 번호 실험 ($stamp)\n\nSR: $sr\n후보 ${paths.size}개: ${paths.map { it.substringAfterLast('/') }}\n\n")
        sb.appendLine("| 방식 | 회수 | 요약 원문에 번호 인용 | 화면(복원 뒤)에 번호 | 응답 해석 실패(대체 경로) | 서로 다른 남은 목록 | 시간(초) |\n|---|---|---|---|---|---|---|")
        for (c in conds.keys) {
            val rs = results.filter { it.cond == c }.sortedBy { it.run }
            sb.appendLine("| $c | ${rs.size} | ${rs.count { it.numbersRaw }}/${rs.size} | ${rs.count { it.numbersShown }}/${rs.size} | ${rs.count { it.fallback }} | ${rs.map { it.kept }.toSet().size} | ${rs.joinToString("/") { (it.ms / 1000).toString() }} |")
        }
        sb.appendLine("\n## 회차별 요약(원문)과 남은 파일")
        for (r in results.sortedWith(compareBy({ it.cond }, { it.run }))) {
            sb.appendLine("\n### ${r.cond} run${r.run} (${r.ms / 1000}초, 대체=${r.fallback}, 원문 번호=${r.numbersRaw})\n- 남음(${r.kept.size}): ${r.kept}\n- 원문: ${r.raw}\n- 화면: ${r.shown}")
        }
        File("build/eval-reports").mkdirs()
        val f = File("build/eval-reports/b79_stage3_names_$stamp.md")
        f.writeText(sb.toString(), Charsets.UTF_8)
        println("[B79NUM] 보고서: ${f.absolutePath}")
    }
}
