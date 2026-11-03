package net.ib.ixpert.ops.wuwagent.agent

/**
 * 확정(/clarify-confirm) 수신 시각을 기억해 이후 로그(카드 요청 전송 등)에 "confirm 후 N ms"를 붙인다(B-84 1). 로그 전용이며 동작에는 쓰이지 않는다.
 */
object ConfirmClock {
    @Volatile private var startedMs: Long = 0L

    fun start() { startedMs = System.currentTimeMillis() }

    /** 마지막 확정 수신 이후 경과 ms. 확정을 받은 적이 없으면 null. */
    fun sinceMs(): Long? = if (startedMs == 0L) null else System.currentTimeMillis() - startedMs

    fun sinceText(): String = sinceMs()?.let { "confirm 후 $it ms" } ?: "confirm 시각 없음"
}
