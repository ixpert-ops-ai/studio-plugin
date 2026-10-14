package net.ib.ixpert.ops.wuwagent.agent.eval

import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL

/** `/v1/models` 사전 확인 결과. 모델이 목록에 없으면 평가를 시작하지 않는다. */
data class ServerCheck(
    val server: String,
    val model: String,
    val httpStatus: Int?,
    val listedModels: List<String>,
    val modelListed: Boolean,
    val message: String
)

object EvalServerCheck {
    fun check(server: String, model: String): ServerCheck {
        return try {
            val conn = URL(server.trimEnd('/') + "/v1/models").openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            val status = conn.responseCode
            if (status != 200) {
                return ServerCheck(server, model, status, emptyList(), false, "/v1/models 응답 HTTP $status")
            }
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val ids = JsonParser.parseString(body).asJsonObject.getAsJsonArray("data").map { it.asJsonObject.get("id").asString }
            val listed = model in ids
            ServerCheck(server, model, status, ids, listed, if (listed) "모델이 목록에 있음" else "모델 '$model'이 /v1/models 목록에 없음: $ids")
        } catch (e: Exception) {
            ServerCheck(server, model, null, emptyList(), false, "/v1/models 호출 실패: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}

data class LlmStats(
    val calls: Int,
    val ok: Int,
    val nullResults: Int,
    val timeouts: Int,
    val exceptions: Int,
    val medianMs: Long,
    val maxMs: Long
)

/**
 * 턴 수. 대본은 가상의 사용자가 정해 둔 발화이므로 "대본이 엔진 질문보다 먼저 끝났는지"를 따로 기록한다:
 * 대본을 모두 처리한 뒤에도 엔진이 질문을 남겼고(`openQuestion`) 정제가 끝나지 않았으면(`isReadyForStage1 == false`) true.
 */
data class TurnStats(
    val engineQuestions: Int,
    val answeredQuestions: Int,
    val utterancesProcessed: Int,
    val scriptEndedBeforeQuestion: Boolean,
    val readyForStage1: Boolean,
    val unresolvedAtEnd: Int
)

data class DeliveredCheck(val identifier: String, val source: String, val inStage3: Boolean?)

data class TargetRow(val order: Int, val path: String, val type: String, val isGt: Boolean)

data class RunResult(
    val scenarioId: String,
    val run: Int,
    val ok: Boolean,
    val error: String?,
    val totalMs: Long,
    val rawRefined: String?,
    val refinedRequirement: String?,
    val expectedIdentifiers: List<String>,
    val missingBeforeFix: List<String>,
    val subtractedIdentifiers: List<String>,
    val unresolved: List<String>,
    val delivered: List<DeliveredCheck>,
    val stage3Calls: Int,
    val stage3Observed: Boolean,
    val excludedFiles: List<String>,
    val expectedExcludedConfirmed: Boolean?,
    val excludedInTargets: List<String>,
    val excludedNameInStage3Sections: List<String>,
    val excludedNameInStage3Body: List<String>,
    val turns: TurnStats?,
    val targets: List<TargetRow>,
    val gtTotal: Int,
    val gtHit30: Int,
    val gtHit10: Int,
    val gtMissing: List<String>,
    val fp30: Int,
    val llmClarify: LlmStats,
    val llmAnalyze: LlmStats,
    val callMs: List<Long>
)

data class ScenarioSummary(
    val scenario: EvalScenario,
    val runs: List<RunResult>
)
