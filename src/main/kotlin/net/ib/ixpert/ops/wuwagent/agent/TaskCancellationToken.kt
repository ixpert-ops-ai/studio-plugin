package net.ib.ixpert.ops.wuwagent.agent

import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TaskAgent의 실행을 외부에서 취소할 수 있는 전역 토큰.
 *
 * - [cancel]   : 실행 중인 Task를 취소 요청 + 블로킹 중인 HTTP 스레드 인터럽트
 * - [reset]    : 다음 Task 실행 전에 상태 초기화
 * - [isCancelled] : 취소 여부 확인
 */
object TaskCancellationToken {
    private val logger = Logger.getInstance(TaskCancellationToken::class.java)

    val isCancelled = AtomicBoolean(false)

    /** HTTP 요청으로 블로킹 중인 백그라운드 스레드 참조 */
    @Volatile
    var backgroundThread: Thread? = null

    fun cancel() {
        logger.info("TaskCancellationToken: 취소 요청")
        isCancelled.set(true)
        // 현재 HTTP 블로킹 중인 스레드를 인터럽트 → OllamaClient IOException 발생 → 스텝 루프 종료
        backgroundThread?.interrupt()
    }

    fun reset() {
        isCancelled.set(false)
        backgroundThread = null
    }
}
