package net.ib.ixpert.ops.wuwagent.agent.eval

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.agent.clarify.Stage0ClarificationEngine
import net.ib.ixpert.ops.wuwagent.agent.clarify.Stage0GraphScanner
import net.ib.ixpert.ops.wuwagent.agent.clarify.SummaryPromptEcho
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * B-78 5(실험만, 운영 연결 없음): Stage 0 첫 요약에 소스 확인 사실 줄을 넣기 전/후로 같은 SR의 요약을 5회씩 만들어 필드 주장(후보 파일이 응답자 이메일을 담고 있다는 문장)을 센다.
 * 판독은 사람이 보고서의 요약 전문을 읽고 한다(정규식은 보조). 예측은 build/eval-reports/pred_b78.md(실행 전 작성).
 * 실행: ./gradlew test --tests "*SummaryLeakB78Test" -DrunLiveLlmTests=true -i
 */
class SummaryLeakB78Test {

    private val surveyRoot = File("C:/Workspace/HC_card_survey_admin/survey_admin")
    private val sr = "기존 설문조사 상세 조회 기능에 응답자 이메일 정보도 같이 내려주도록 백엔드를 수정해주세요"

    /** 보조 정규식: 같은 문장에 이메일과 후보 파일 이름(User 계열 포함)이 함께 있고 조회·포함·존재 같은 동사가 있는가. */
    private val claimRe = Regex("(이메일|email)[^.\\n]{0,60}(조회|포함|담|제공|존재|반환|내려)|(조회|포함|담|제공|반환)[^.\\n]{0,40}(이메일|email)", RegexOption.IGNORE_CASE)

    /** 모델이 쓴 원문(후처리 전)을 스레드별로 기록하는 래퍼. */
    private class RawRecordingClient(val delegate: LLMClient) : LLMClient {
        val raw = ThreadLocal<String?>()
        override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): OllamaChatResponse? {
            val r = delegate.chat(systemPrompt, userCode, maxTokens, onChunk)
            raw.set(r?.message?.content)
            return r
        }
        override fun chatWithTools(systemPrompt: String, messages: List<ChatMessage>, maxTokens: Int?, tools: List<ToolDefinition>?, toolChoice: Any?, temperature: Double?): ChatCompletionResponse? =
            delegate.chatWithTools(systemPrompt, messages, maxTokens, tools, toolChoice, temperature)
        override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = delegate.fetchModels(baseUrl, apiKey)
    }

    private val outsideRe = Regex("후보 범위 밖")

    data class Out(val cond: String, val run: Int, val ms: Long, val summary: String, val claimRegex: Boolean, val rawText: String, val rawLeak: Boolean, val shownLeak: Boolean, val outside: Boolean)

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

        val rec = RawRecordingClient(OpenAIClient())
        val engine = Stage0ClarificationEngine(Stage0GraphScanner(g), g, rec, deferTaskSummary = true, projectRoot = surveyRoot)
        val state = engine.initSession(sr).state
        println("[B78SUM] 후보 ${state.items.size}개, 사실 ${state.fieldFacts.map { it.label + ":" + (if (it.found) "찾음" else "못 찾음") }}")
        val conds = linkedMapOf("새 프롬프트" to state)

        val pool = Executors.newFixedThreadPool(3)
        val jobs = ArrayList<Pair<Pair<String, Int>, java.util.concurrent.Future<Out>>>()
        for ((cond, st) in conds) for (run in 1..runs) {
            jobs.add((cond to run) to pool.submit(Callable {
                val t0 = System.currentTimeMillis()
                val s = try { engine.buildTaskSummary(st) } catch (e: Throwable) { null }
                val ms = System.currentTimeMillis() - t0
                val text = s ?: "(요약 없음)"
                val rawText = rec.raw.get() ?: ""
                val o = Out(cond, run, ms, text, claimRe.containsMatchIn(text), rawText, SummaryPromptEcho.leaks(rawText), SummaryPromptEcho.leaks(text), outsideRe.containsMatchIn(text))
                println("[B78SUM] $cond run$run: ${ms / 1000}s 정규식 주장=${o.claimRegex} ${text.take(80).replace("\n", " ")}")
                o
            }))
        }
        val results = jobs.mapNotNull { (_, f) -> try { f.get(300, TimeUnit.SECONDS) } catch (e: Exception) { f.cancel(true); null } }
        pool.shutdownNow()

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val sb = StringBuilder("# B-78 5 요약 필드 주장 실험 ($stamp)\n\nSR: $sr\n사실 줄: ${state.fieldFacts.map { net.ib.ixpert.ops.wuwagent.agent.ClaimedFieldCheck.promptLine(it) }}\n\n")
        sb.appendLine("| 조건 | 회수 | 원문 표지(후처리 전) | 화면 표지(후처리 후) | 후보 범위 밖 오서술 | 정규식 주장 | 시간(초) |\n|---|---|---|---|---|---|---|")
        for (c in conds.keys) {
            val rs = results.filter { it.cond == c }.sortedBy { it.run }
            sb.appendLine("| $c | ${rs.size} | ${rs.count { it.rawLeak }}/${rs.size} | ${rs.count { it.shownLeak }}/${rs.size} | ${rs.count { it.outside }}/${rs.size} | ${rs.count { it.claimRegex }}/${rs.size} | ${rs.joinToString("/") { (it.ms / 1000).toString() }} |")
        }
        sb.appendLine("\n## 회차별 요약 전문(사람 판독용)")
        for (r in results.sortedWith(compareBy({ it.cond }, { it.run }))) {
            sb.appendLine("\n### ${r.cond} run${r.run} (${r.ms / 1000}초, 원문 표지=${r.rawLeak}, 화면 표지=${r.shownLeak})\n[화면]\n${r.summary}\n[모델 원문]\n${r.rawText}")
        }
        File("build/eval-reports").mkdirs()
        val f = File("build/eval-reports/b78_summary_leak_$stamp.md")
        f.writeText(sb.toString(), Charsets.UTF_8)
        println("[B78SUM] 보고서: ${f.absolutePath}")
    }
}
