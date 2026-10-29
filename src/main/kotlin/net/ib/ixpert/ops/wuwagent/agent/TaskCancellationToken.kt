package net.ib.ixpert.ops.wuwagent.agent

import com.intellij.openapi.diagnostic.Logger
import java.io.InputStream
import java.net.HttpURLConnection
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LLM 요청 실행을 외부에서 취소할 수 있는 전역 토큰.
 *
 * - [cancel]          : 실행 중인 요청을 취소 — 스레드 인터럽트 + HTTP 스트림 강제 종료
 * - [reset]           : 다음 요청 시작 전 상태 초기화
 * - [isCancelled]     : 전역 취소 여부 (AtomicBoolean)
 * - [isCancelledFor]  : 특정 messageId의 취소 여부 확인
 * - [activeMessageId] : 현재 실행 중인 요청의 messageId (취소 응답 전송 시 역참조)
 */
object TaskCancellationToken {
    private val logger = Logger.getInstance(TaskCancellationToken::class.java)

    /** 한 번의 분석(또는 대화 세션)이 쓰는 취소 상태. 분석마다 새로 만들어 이전 분석의 취소가 다음 분석에 남지 않게 한다(B-78). */
    class Scope {
        val flag = AtomicBoolean(false)
    }

    @Volatile
    private var scope = Scope()

    /** 현재 분석의 취소 여부. 호출부가 `isCancelled.get()`으로 읽는 방식은 그대로다. */
    val isCancelled: AtomicBoolean get() = scope.flag

    /**
     * 새 분석을 시작할 때 부른다: 새 취소 범위를 만들어 현재 범위로 삼는다(전역 플래그를 되돌리는 [reset]과 다르게, 이전 범위의 플래그 객체는 그대로 취소 상태로 남는다).
     * 분석이 돌려받은 [Scope]를 쥐고 있으면 이후의 `/cancel`이 다른 분석에 번지지 않는지 확인할 수 있다.
     */
    fun beginScope(): Scope {
        val next = Scope()
        scope = next
        cancelledIds.clear()
        backgroundThread = null
        activeInputStream = null
        activeHttpConnection = null
        activeMessageId = null
        return next
    }

    /** messageId별 취소 상태 — 다중 요청 취소 추적용 */
    private val cancelledIds = ConcurrentHashMap<String, Boolean>()

    /** HTTP 요청으로 블로킹 중인 백그라운드 스레드 참조 */
    @Volatile
    var backgroundThread: Thread? = null

    /** OllamaClient 스트리밍 InputStream — cancel() 시 강제 close()로 즉시 종료 */
    @Volatile
    var activeInputStream: InputStream? = null

    /**
     * OllamaClient 비스트리밍 HttpURLConnection — cancel() 시 disconnect()로 블로킹 강제 해제.
     * getInputStream() 자체가 블로킹이므로 inputStream 등록이 불가 → 상위 connection 레벨로 처리.
     */
    @Volatile
    var activeHttpConnection: HttpURLConnection? = null

    /** 현재 실행 중인 요청의 messageId — /cancel 응답 시 UI 말풍선 특정에 사용 */
    @Volatile
    var activeMessageId: String? = null

    /**
     * 실행 중인 요청 취소.
     * 1) isCancelled 플래그 설정
     * 2) messageId별 취소 기록 (선택)
     * 3) 백그라운드 스레드 인터럽트
     * 4) HTTP InputStream 강제 close → 스트리밍 루프 즉시 종료
     */
    fun cancel(messageId: String? = null) {
        logger.info("TaskCancellationToken: 취소 요청 (messageId=$messageId)")
        scope.flag.set(true)
        if (messageId != null) cancelledIds[messageId] = true
        backgroundThread?.interrupt()
        try { activeInputStream?.close()      } catch (_: Exception) {}
        try { activeHttpConnection?.disconnect() } catch (_: Exception) {}
    }

    /** messageId 또는 전역 플래그로 취소 여부 확인 */
    fun isCancelledFor(messageId: String): Boolean =
        isCancelled.get() || cancelledIds[messageId] == true

    /**
     * 다음 요청 시작 전 상태 초기화.
     * messageId를 지정하면 해당 id의 취소 기록만 제거, null이면 전체 초기화.
     */
    fun reset(messageId: String? = null) {
        scope.flag.set(false)
        if (messageId != null) cancelledIds.remove(messageId) else cancelledIds.clear()
        backgroundThread = null
        activeInputStream = null
        activeHttpConnection = null
        activeMessageId = null
    }
}
