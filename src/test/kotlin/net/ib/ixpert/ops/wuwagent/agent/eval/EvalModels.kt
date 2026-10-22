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
    val unresolvedAtEnd: Int,
    val ruleAnswersUsed: Int = 0,                     // 시나리오 답변 규칙(예: 제외 확인에 '네')을 쓴 횟수
    val ruleAnswerText: String? = null,
    val fallbackAnswersUsed: Int = 0,                 // 대본에 없는 엔진 질문에 대체 답변을 쓴 횟수(1회 한도)
    val questionRemainedAfterFallback: Boolean = false, // 대체 답변 뒤에도 질문이 남아 그대로 분석으로 진행했는지
    val remainingQuestion: String? = null,            // 분석으로 넘어갈 때 남아 있던 엔진 질문
    val engineQuestionTexts: List<String> = emptyList(),
    val utterances: List<UtteranceLog> = emptyList(),
    val initialQuestion: String? = null                 // initSession 직후 엔진이 낸 첫 질문(없으면 null). 턴별 질문 블록용
)

/** 대본 발화 한 건을 UI 경로(processUtterance)로 보낸 결과: 화면에 나가는 안내문과 질문, 판정·시드 변화. */
data class UtteranceLog(
    val statement: String,
    val echo: String?,
    val openQuestion: String?,
    val rejectedBefore: Int,
    val rejectedAfter: Int,
    val seedBefore: Int,
    val seedAfter: Int,
    val ready: Boolean,
    val exclusionPayload: String? = null   // 화면 payload의 excludedFiles·pendingExclusions 원문(JSON)
)

data class DeliveredCheck(val identifier: String, val source: String, val inStage3: Boolean?)

data class TargetRow(val order: Int, val path: String, val type: String, val isGt: Boolean, val isReference: Boolean = false)

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
    val gtTotal: Int,               // Recall 분모: required이면서 그래프에 있는 GT
    val gtHit30: Int,
    val gtHit10: Int,
    val gtMissing: List<String>,    // 분모 중 상위 30에 없는 것
    val referenceTotal: Int,        // reference 항목 수(분모 아님)
    val referenceIn10: List<String>,
    val referenceIn30: List<String>,
    val graphMissingGt: List<String>, // inGraph=false인 GT(그래프 누락)
    val fp30: Int,                  // GT(required·reference 모두)와 무관한 상위 30 파일 수. GT가 불완전하면 상한
    val llmClarify: LlmStats,
    val llmAnalyze: LlmStats,
    val callMs: List<Long>,
    val unconfirmedExclusions: List<String> = emptyList(), // 의도에 기록된 제외 미확정(문장과 후보)
    val summaryHead: String? = null,                      // 분석 요약(화면에 보이는 결과)의 앞부분 원문
    val summaryAtConfirm: String? = null,                 // 확정 시점에 카드에 보이던 작업 요약 상태(NONE/PENDING/READY/FAILED 또는 해당 없음)
    val mustNotAppearTotal: Int = 0,                      // 시나리오의 제외 대상(mustNotAppear) 파일 수
    val mustNotAppearInTargets: List<String> = emptyList(), // 그중 최종 목록에 나타난 파일
    // required GT 네 갈래(GtSplit). 합이 gtTotal과 다르면 보고서에 "합계 불일치"로 표시한다
    val gtKept: List<String> = emptyList(),
    val gtStage3Removed: List<String> = emptyList(),
    val gtOutsideExcluded: List<String> = emptyList(),
    val gtOutsideLost: List<String> = emptyList(),
    val gtUnobserved: List<String> = emptyList(),         // Stage 3 호출이 관찰되지 않아 제거/후보 밖을 가를 수 없는 항목
    val gtTotalCertain: Int = 0,                          // uncertain 제외 Recall 분모
    val gtHit30Certain: Int = 0,
    // 되살리기 측정(안 1의 A). removedListSize -1 = 결과에 목록 필드 없음
    val removedListSize: Int = -1,
    val removedUserExcluded: List<String> = emptyList(),        // 목록의 USER_EXCLUDED 경로
    val removedRequiredInList: List<String> = emptyList(),      // 최종 목록에서 빠진 required 중 목록에 있는 것("이름 (순위 r, source)")
    val removedRequiredNotInList: List<String> = emptyList(),   // 빠졌는데 목록에도 없는 required
    val restoredPaths: List<String> = emptyList(),              // 평가 정책("목록에 있는 required를 전부 되살린다")으로 되살린 경로
    val restoreLlmCalls: Int = 0,                               // 되살릴 때 LLM 호출 수(기대 0)
    val recallAfterRestore: Int = -1,                           // 복원 후 /implement 입력(lastResult)의 required 적중 수
    val restoredInImplementInput: Boolean? = null               // 되살린 파일이 모두 lastResult에 들어갔는가(되살린 것이 없으면 null)
)

data class ScenarioSummary(
    val scenario: EvalScenario,
    val runs: List<RunResult>
)
