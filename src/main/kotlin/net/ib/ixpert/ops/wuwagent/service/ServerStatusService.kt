package net.ib.ixpert.ops.wuwagent.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.io.HttpRequests
import net.ib.ixpert.ops.wuwagent.setting.SettingsState
import net.ib.ixpert.ops.wuwagent.ui.bridge.JcefBridge
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CompletableFuture

/** 하단 상태 램프에 표시되는 서버 상태 */
enum class ServerState { CHECKING, OK, MODEL_NOT_FOUND, UNREACHABLE }

/**
 * 웹뷰로 전달되는 서버 상태 스냅샷.
 * [errorSummary]는 고정 문구만 사용한다 (서버 주소가 담긴 원본 예외 메시지를 노출하지 않기 위함).
 */
data class ServerStatus(
    val state: ServerState,
    val latencyMs: Long?,
    val lastCheckedAt: Long?,
    val failingSince: Long?,
    val errorSummary: String?
)

/** [ServerStatusService.check] 결과. [models]는 목록을 얻지 못했을 때(실패/미지원 서버) null. */
data class ServerCheckResult(val status: ServerStatus, val models: List<String>?)

/**
 * 서버 상태 보관/전이 규칙 (IntelliJ 의존성 없음 → 단위 테스트 가능).
 *
 * - 확인 중에는 [snapshot]이 CHECKING을 돌려주되, 확인 직전의 확정 상태(실패 시작 시각 포함)는 그대로 보관한다.
 * - failingSince는 UNREACHABLE로 처음 전이된 시각을 복구 전까지 유지한다.
 */
class ServerStatusTracker(private val now: () -> Long = System::currentTimeMillis) {
    private var committed = ServerStatus(ServerState.CHECKING, null, null, null, null)
    private var checking = false

    @Synchronized fun snapshot(): ServerStatus =
        if (checking) committed.copy(state = ServerState.CHECKING) else committed

    @Synchronized fun begin(): ServerStatus {
        checking = true
        return snapshot()
    }

    /** /v1/models 확인 결과 확정 */
    @Synchronized fun finish(state: ServerState, latencyMs: Long?, errorSummary: String?): ServerStatus {
        checking = false
        return commit(state, latencyMs, errorSummary)
    }

    /** 실제 LLM 요청 성공: 응답시간은 유지하고 마지막 확인 시각만 갱신, 복구(→OK) */
    @Synchronized fun requestSucceeded(): ServerStatus =
        commit(ServerState.OK, committed.latencyMs, null)

    /** 실제 LLM 요청이 연결 에러로 실패 */
    @Synchronized fun requestFailed(errorSummary: String): ServerStatus =
        commit(ServerState.UNREACHABLE, null, errorSummary)

    private fun commit(state: ServerState, latencyMs: Long?, errorSummary: String?): ServerStatus {
        val t = now()
        committed = ServerStatus(
            state = state,
            latencyMs = latencyMs,
            lastCheckedAt = t,
            failingSince = if (state == ServerState.UNREACHABLE) (committed.failingSince ?: t) else null,
            errorSummary = if (state == ServerState.UNREACHABLE) errorSummary else null
        )
        return snapshot()
    }
}

/** 예외 → 연결 에러 여부/요약 문구 분류 */
object ServerErrors {
    private val STATUS_CODE_REGEX = Regex("""(?:response code|status code)\D{0,3}(\d{3})""", RegexOption.IGNORE_CASE)

    private fun causes(e: Throwable): Sequence<Throwable> = generateSequence(e) { it.cause?.takeIf { c -> c !== it } }.take(8)

    fun statusCodeOf(e: Throwable): Int? = causes(e).firstNotNullOfOrNull {
        (it as? HttpRequests.HttpStatusException)?.statusCode
            ?: it.message?.let { m -> STATUS_CODE_REGEX.find(m)?.groupValues?.get(1)?.toIntOrNull() }
    }

    /** LLM 요청 실패를 "서버에 연결 못 함"으로 볼지: 접속 불가, 타임아웃, 5xx */
    fun isConnectionFailure(e: Throwable): Boolean {
        if (causes(e).any {
                it is ConnectException || it is NoRouteToHostException || it is UnknownHostException ||
                    it is SocketTimeoutException || it.message?.contains("refused", ignoreCase = true) == true
            }) return true
        return (statusCodeOf(e) ?: 0) >= 500
    }

    fun summarize(e: Throwable): String {
        val code = statusCodeOf(e)
        return when {
            causes(e).any { it is SocketTimeoutException } -> "응답 시간 초과"
            causes(e).any { it is UnknownHostException } -> "서버를 찾을 수 없음"
            causes(e).any { it is ConnectException || it.message?.contains("refused", ignoreCase = true) == true } -> "연결 거부"
            causes(e).any { it is NoRouteToHostException } -> "서버에 도달할 수 없음"
            code == 401 || code == 403 -> "인증 실패($code)"
            code != null -> "서버 오류($code)"
            e is java.io.IOException -> "네트워크 오류"
            else -> "응답 형식 오류"
        }
    }
}

/**
 * LLM 서버 상태 확인 서비스. 모델 목록 조회만 하며 LLM 생성 호출은 하지 않는다.
 *
 * 확인 시점은 다음으로만 한정한다 (타이머/주기 호출 없음):
 *  1. IDE 시작 시 1회 (ModelValidationStartupActivity)
 *  2. 모델 변경(/changeModel) 직후
 *  3. 웹뷰 ↻ 클릭(/checkServer)
 *  4. 실제 LLM 요청 결과 반영 — 추가 호출 없음 ([onRequestSuccess]/[onRequestFailure], 클라이언트에서 호출)
 */
object ServerStatusService {
    private val logger = Logger.getInstance(ServerStatusService::class.java)
    private val tracker = ServerStatusTracker()
    private val lock = Any()
    private var inFlight: CompletableFuture<ServerCheckResult>? = null

    fun current(): ServerStatus = tracker.snapshot()

    /**
     * 서버 확인을 수행하고 결과를 반환한다 (블로킹 — 백그라운드 스레드에서 호출).
     * 이미 확인이 진행 중이면 새 요청을 보내지 않고 진행 중인 결과를 공유한다.
     */
    fun check(): ServerCheckResult {
        val mine = CompletableFuture<ServerCheckResult>()
        val running = synchronized(lock) { inFlight ?: run { inFlight = mine; null } }
        if (running != null) return running.get()

        try {
            val result = doCheck()
            mine.complete(result)
            return result
        } catch (t: Throwable) {
            mine.completeExceptionally(t)
            throw t
        } finally {
            synchronized(lock) { inFlight = null }
        }
    }

    /** 비동기 확인. 이미 진행 중이면 아무것도 하지 않는다 (진행 중인 확인이 끝나면 결과가 전달됨). */
    fun checkAsync() {
        if (synchronized(lock) { inFlight != null }) return
        ApplicationManager.getApplication().executeOnPooledThread {
            try { check() } catch (e: Exception) { logger.warn("ServerStatusService: 확인 중 예외", e) }
        }
    }

    private fun doCheck(): ServerCheckResult {
        publish(tracker.begin())

        val settings = SettingsState.getInstance().state
        val baseUrl = when (settings.apiType) {
            SettingsState.ApiType.OLLAMA -> settings.ollamaServerUrl
            SettingsState.ApiType.OPENAI_COMPATIBLE -> settings.openaiServerUrl
            SettingsState.ApiType.AIPRO -> settings.aiproServerUrl
        }

        val startNs = System.nanoTime()
        var models: List<String>? = null
        var state: ServerState
        var summary: String? = null
        try {
            models = WuwLlmService.getClient().fetchModelsOrThrow(baseUrl, settings.effectiveApiKey())
            state = verdict(models)
        } catch (e: Exception) {
            val code = ServerErrors.statusCodeOf(e)
            if (code != null && code in REACHABLE_WITHOUT_MODEL_LIST) {
                // 서버는 응답했지만 모델 목록 조회를 지원하지 않거나 한도 초과 → 연결은 정상, 모델은 검증 불가
                state = ServerState.OK
            } else {
                state = ServerState.UNREACHABLE
                summary = ServerErrors.summarize(e)
            }
            logger.info("ServerStatusService: 모델 목록 조회 실패 → $state ($summary)")
        }
        val latencyMs = (System.nanoTime() - startNs) / 1_000_000

        val status = tracker.finish(state, if (state == ServerState.UNREACHABLE) null else latencyMs, summary)
        logger.info("ServerStatusService: 확인 완료 state=${status.state}, ${latencyMs}ms")
        publish(status)
        return ServerCheckResult(status, models)
    }

    /** 완료 시점의 설정 모델 기준으로 판정 (확인 도중 모델이 바뀌어도 올바르게 반영) */
    private fun verdict(models: List<String>?): ServerState {
        // 목록 형식을 인식하지 못했으면(null) 검증 불가 → 연결 정상으로 간주
        if (models == null) return ServerState.OK
        val configured = SettingsState.getInstance().state.model.trim()
        return if (configured.isNotBlank() && models.any { it == configured }) ServerState.OK else ServerState.MODEL_NOT_FOUND
    }

    /** LLM 요청 성공 (추가 네트워크 호출 없음) */
    fun onRequestSuccess() {
        publish(tracker.requestSucceeded())
    }

    /** LLM 요청 실패 — 연결 에러(접속 불가/타임아웃/5xx)일 때만 UNREACHABLE로 반영 (추가 네트워크 호출 없음) */
    fun onRequestFailure(e: Throwable) {
        if (!ServerErrors.isConnectionFailure(e)) return
        publish(tracker.requestFailed(ServerErrors.summarize(e)))
    }

    /** 웹뷰가 늦게 로드된 경우: 서버 호출 없이 보관된 최신 상태만 전송 */
    fun sendCurrentTo(bridge: JcefBridge) = send(bridge, tracker.snapshot())

    private fun publish(status: ServerStatus) {
        ApplicationManager.getApplication().invokeLater {
            ProjectManager.getInstance().openProjects.filter { !it.isDisposed }.forEach {
                send(JcefBridge.getInstance(it), status)
            }
        }
    }

    private fun send(bridge: JcefBridge, s: ServerStatus) {
        bridge.sendMessage(
            subType = "server_status",
            content = s.state.name,
            meta = mapOf(
                "status" to s.state.name,
                "latencyMs" to (s.latencyMs?.toString() ?: ""),
                "lastCheckedAt" to (s.lastCheckedAt?.toString() ?: ""),
                "failingSince" to (s.failingSince?.toString() ?: ""),
                "errorSummary" to (s.errorSummary ?: "")
            )
        )
    }

    // 서버가 응답은 했지만 /v1/models 가 없거나(404/405/501) 한도 초과(429)인 경우
    private val REACHABLE_WITHOUT_MODEL_LIST = setOf(404, 405, 429, 501)
}
