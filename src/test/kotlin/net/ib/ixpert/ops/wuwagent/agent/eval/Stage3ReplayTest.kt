package net.ib.ixpert.ops.wuwagent.agent.eval

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Stage 3 검증 호출 출력 구성 확인(B-39 원인 확인, 재생 실험). 프록시가 덤프한(-Deval.dump) Stage 3 요청을 그대로 다시 보낸다.
 *  - A0: 덤프 시점에 파이프라인이 실제로 보낸 원본 호출의 응답 구성(덤프의 .resp.json)
 *  - A': 같은 요청을 원본 그대로 다시 보냄(재현성 확인, -Deval.replay.a=D3,D4 기본)
 *  - B : 같은 요청에 chat_template_kwargs {"enable_thinking": false}만 추가
 * 측정만 한다(src/main 변경 없음). 실행:
 *   ./gradlew test --tests "net.ib.ixpert.ops.wuwagent.agent.eval.Stage3ReplayTest" -DrunLiveLlmTests=true -i
 * 옵션: -Deval.replay.dir=<덤프 폴더, 기본 build/eval-reports/dumps> -Deval.replay.a=D3,D4 -Deval.out=<출력 폴더>
 */
class Stage3ReplayTest {

    companion object {
        const val SERVER = "http://vllm.ixpertops.cloud"
        const val STAGE3 = "analyze:Stage3 검증"
    }

    data class Outcome(
        val label: String,
        val httpStatus: Int?,
        val ttfbMs: Long?,
        val totalMs: Long?,
        val finishReason: String?,
        val chunks: Int?,
        val reasoningChunks: Int?,
        val contentChunks: Int?,
        val toolArgChunks: Int?,
        val promptTokens: Int?,
        val completionTokens: Int?,
        val deltaKeys: String?,
        val required: List<String>?,
        val unnecessary: List<String>?,
        val note: String?
    )

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    /** 도구 호출 인자(또는 content)의 JSON에서 fileVerdicts를 읽어 REQUIRED/UNNECESSARY 경로를 낸다. */
    private fun verdicts(toolArgs: String, content: String): Triple<List<String>?, List<String>?, String?> {
        val text = if (toolArgs.isNotBlank()) toolArgs else content
        if (text.isBlank()) return Triple(null, null, "도구 인자·content 모두 비어 있음")
        return try {
            val start = text.indexOf('{')
            val o = JsonParser.parseString(if (start >= 0) text.substring(start) else text).asJsonObject
            val arr = o.getAsJsonArray("fileVerdicts") ?: return Triple(null, null, "fileVerdicts 없음")
            val req = ArrayList<String>()
            val un = ArrayList<String>()
            arr.forEach {
                val v = it.asJsonObject
                val p = v.get("filePath")?.asString ?: return@forEach
                if (v.get("verdict")?.asString == "REQUIRED") req.add(p) else un.add(p)
            }
            Triple(req.sorted(), un.sorted(), null)
        } catch (e: Exception) {
            Triple(null, null, "JSON 해석 실패: ${e.javaClass.simpleName}")
        }
    }

    private fun send(label: String, body: String, stream: Boolean): Outcome {
        val started = System.currentTimeMillis()
        return try {
            val conn = URL("$SERVER/v1/chat/completions").openConnection(Proxy.NO_PROXY) as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.connectTimeout = 30_000
            conn.readTimeout = 900_000
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val input = if (status >= 400) conn.errorStream else conn.inputStream
            val buf = java.io.ByteArrayOutputStream()
            var ttfb: Long? = null
            val chunk = ByteArray(8192)
            while (input != null) {
                val n = input.read(chunk)
                if (n < 0) break
                if (ttfb == null) ttfb = System.currentTimeMillis() - started
                buf.write(chunk, 0, n)
            }
            val total = System.currentTimeMillis() - started
            val text = String(buf.toByteArray(), Charsets.UTF_8)
            if (status >= 400) return Outcome(label, status, ttfb, total, null, null, null, null, null, null, null, null, null, null, "HTTP $status: ${text.take(300)}")
            val p = if (stream) ResponseParser.parseStream(text) else ResponseParser.parseJson(text)
            val (req, un, note) = verdicts(p.toolArgs.toString(), p.content.toString())
            Outcome(label, status, ttfb, total, p.finishReason, if (stream) p.chunks else null,
                if (stream) p.reasoningChunks else null, if (stream) p.contentChunks else null, if (stream) p.toolArgChunks else null,
                p.promptTokens, p.completionTokens, p.deltaKeys.joinToString(","), req, un, note)
        } catch (e: Exception) {
            Outcome(label, null, null, System.currentTimeMillis() - started, null, null, null, null, null, null, null, null, null, null, "예외: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun sec(ms: Long?) = if (ms == null) "-" else String.format("%.1f", ms / 1000.0)

    private fun split(o: Outcome) = "r${o.reasoningChunks ?: "-"}/c${o.contentChunks ?: "-"}/t${o.toolArgChunks ?: "-"}"

    @Test
    fun replayStage3() {
        Assume.assumeTrue(
            "Live vLLM 재생 실험은 -DrunLiveLlmTests=true 또는 환경변수 RUN_LIVE_LLM_TESTS=true 명시 시에만 활성화됩니다.",
            System.getProperty("runLiveLlmTests") == "true" || System.getenv("RUN_LIVE_LLM_TESTS") == "true"
        )
        val dir = File(System.getProperty("eval.replay.dir") ?: "build/eval-reports/dumps")
        val aIds = (System.getProperty("eval.replay.a") ?: "D3,D4").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val outDir = File(System.getProperty("eval.out") ?: "build/eval-reports")
        outDir.mkdirs()

        // Stage 3 호출 덤프 찾기(시나리오별 1건)
        val found = LinkedHashMap<String, Pair<File, JsonObject>>()
        (dir.listFiles { f -> f.name.endsWith(".resp.json") } ?: emptyArray()).sortedBy { it.name }.forEach { rf ->
            val o = JsonParser.parseString(rf.readText(Charsets.UTF_8)).asJsonObject
            if (o.get("stage")?.asString == STAGE3) {
                val id = rf.name.substringBefore("_run")
                val req = File(dir, rf.name.removeSuffix(".resp.json") + ".req.json")
                if (req.exists() && id !in found) found[id] = req to o
            }
        }
        println("[REPLAY] Stage 3 덤프 ${found.size}개: ${found.keys}")
        Assume.assumeTrue("Stage 3 덤프가 없음: $dir", found.isNotEmpty())

        val rows = ArrayList<Map<String, Any?>>()
        val sb = StringBuilder()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val started = System.currentTimeMillis()

        // 청크≈토큰 보정: 짧은 스트림 요청 1건에 stream_options.include_usage를 붙여 청크 수와 completion_tokens를 비교한다.
        val probeBody = """{"model":"Qwen/Qwen3.8-27B-FP8","messages":[{"role":"user","content":"Count from 1 to 40 separated by commas."}],"max_tokens":200,"temperature":0.1,"stream":true,"stream_options":{"include_usage":true}}"""
        val probe = send("probe", probeBody, true)
        println("[REPLAY] 보정 호출: 청크 ${probe.chunks}, completion_tokens ${probe.completionTokens}, r/c/t ${split(probe)}")

        for ((id, pair) in found) {
            val (reqFile, resp) = pair
            val body = reqFile.readText(Charsets.UTF_8)
            val a0Required = verdicts(resp.get("toolArgs")?.asString ?: "", resp.get("content")?.asString ?: "")
            val a0 = Outcome("A0(원본 호출)", resp.get("httpStatus")?.asInt, resp.get("ttfbMs")?.takeIf { !it.isJsonNull }?.asLong, resp.get("totalMs")?.takeIf { !it.isJsonNull }?.asLong,
                resp.get("finishReason")?.takeIf { !it.isJsonNull }?.asString, resp.get("chunks")?.takeIf { !it.isJsonNull }?.asInt,
                resp.get("reasoningChunks")?.takeIf { !it.isJsonNull }?.asInt, resp.get("contentChunks")?.takeIf { !it.isJsonNull }?.asInt, resp.get("toolArgChunks")?.takeIf { !it.isJsonNull }?.asInt,
                null, null, resp.get("deltaKeys")?.takeIf { !it.isJsonNull }?.asString, a0Required.first, a0Required.second, a0Required.third)

            val reqObj = JsonParser.parseString(body).asJsonObject
            val stream = reqObj.get("stream")?.asBoolean ?: false
            println("[REPLAY] $id: 요청 ${body.length}자, stream=$stream, max_tokens=${reqObj.get("max_tokens")}, B 재생 시작")
            val bObj = JsonParser.parseString(body).asJsonObject
            bObj.add("chat_template_kwargs", JsonParser.parseString("""{"enable_thinking": false}"""))
            val b = send("B(enable_thinking=false)", gson.toJson(bObj), stream)
            println("[REPLAY] $id B: ${sec(b.totalMs)}초, 청크 ${b.chunks} (${split(b)}), finish=${b.finishReason}, 판정=${b.note ?: "ok"}")

            var aPrime: Outcome? = null
            if (id in aIds) {
                println("[REPLAY] $id: A' 재생 시작")
                aPrime = send("A'(원본 재생)", body, stream)
                println("[REPLAY] $id A': ${sec(aPrime.totalMs)}초, 청크 ${aPrime.chunks} (${split(aPrime)}), finish=${aPrime.finishReason}")
            }
            rows.add(mapOf("scenario" to id, "requestChars" to body.length, "stream" to stream, "maxTokens" to reqObj.get("max_tokens")?.toString(),
                "A0" to a0, "Aprime" to aPrime, "B" to b))
        }

        sb.appendLine("# Stage 3 검증 호출 재생 실험 ($stamp)")
        sb.appendLine()
        sb.appendLine("- 서버 ${SERVER}, 덤프 폴더 ${dir.path}, 시나리오 ${found.size}개, A' 재생 대상 ${aIds}, 총 소요 ${(System.currentTimeMillis() - started) / 1000}초")
        sb.appendLine("- A0는 덤프를 만든 실행에서 파이프라인이 실제로 보낸 원본 호출, B는 같은 요청에 `chat_template_kwargs: {\"enable_thinking\": false}`만 추가")
        sb.appendLine("- 청크≈토큰 보정 호출(짧은 스트림, include_usage): 청크 ${probe.chunks}, completion_tokens ${probe.completionTokens}, 출력 구성 ${split(probe)}, delta 키 ${probe.deltaKeys}")
        sb.appendLine()
        sb.appendLine("| 시나리오 | 요청(자) | A0 reasoning/content+tool(청크) | A0 총(초) | A' 청크(r/c/t) / 총(초) | B 출력(청크 r/c/t, usage) | B 총(초) | B finish | REQUIRED A0 | REQUIRED B | 같음 |")
        sb.appendLine("|---|---|---|---|---|---|---|---|---|---|---|")
        for (r in rows) {
            @Suppress("UNCHECKED_CAST") val a0 = r["A0"] as Outcome
            val ap = r["Aprime"] as Outcome?
            val b = r["B"] as Outcome
            val same = if (a0.required == null || b.required == null) "비교 불가" else if (a0.required == b.required) "같음" else "다름"
            sb.appendLine("| ${r["scenario"]} | ${r["requestChars"]} | ${split(a0)} (총 ${a0.chunks ?: "-"}) | ${sec(a0.totalMs)} | ${if (ap == null) "-" else "${ap.chunks} (${split(ap)}) / ${sec(ap.totalMs)}"} | ${b.chunks ?: "-"} (${split(b)}), usage ${b.completionTokens ?: "미제공"} | ${sec(b.totalMs)} | ${b.finishReason ?: "-"}${b.note?.let { " [$it]" } ?: ""} | ${a0.required?.size ?: "-"}개 | ${b.required?.size ?: "-"}개 | $same |")
        }
        sb.appendLine()
        sb.appendLine("## 판정 결과(남은 파일 REQUIRED) 비교")
        for (r in rows) {
            val a0 = r["A0"] as Outcome
            val b = r["B"] as Outcome
            sb.appendLine("- ${r["scenario"]}: A0 ${a0.required?.map { it.substringAfterLast('/') }} / B ${b.required?.map { it.substringAfterLast('/') }}" +
                (if (a0.required != null && b.required != null && a0.required != b.required) " (A0에만 ${(a0.required!! - b.required!!.toSet()).map { it.substringAfterLast('/') }}, B에만 ${(b.required!! - a0.required!!.toSet()).map { it.substringAfterLast('/') }})" else ""))
        }
        File(outDir, "stage3_replay_$stamp.md").writeText(sb.toString(), Charsets.UTF_8)
        File(outDir, "stage3_replay_$stamp.json").writeText(gson.toJson(mapOf("probe" to probe, "rows" to rows)), Charsets.UTF_8)
        println("[REPLAY] 보고서: ${File(outDir, "stage3_replay_$stamp.md").absolutePath}")
    }
}
