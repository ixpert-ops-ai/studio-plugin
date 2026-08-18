package net.ib.ixpert.ops.wuwagent.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger

@Service(Service.Level.APP)
class DebugManager {

    data class DebugEntry(
        val requestId: String,
        val timestamp: String,
        val model: String,
        val requestUrl: String,
        val requestPayload: String,
        val messageCount: Int,
        val isStreaming: Boolean,
        var responseText: String = "",
        var errorMessage: String = "",
        var durationMs: Long = 0L,
        var isSuccess: Boolean = false,
        var responseLength: Int = 0,
        /** LLM이 응답을 왜 멈췄는지(OpenAI 계열 finish_reason / Ollama done_reason 통일 필드).
         *  "length"면 토큰 한도로 응답이 잘렸다는 뜻. 확인 불가하면 null. */
        var finishReason: String? = null
    )

    private val logs = ConcurrentLinkedDeque<DebugEntry>()
    private val counter = AtomicInteger(0)
    private val formatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    fun newEntry(
        model: String,
        requestUrl: String,
        requestPayload: String,
        messageCount: Int,
        isStreaming: Boolean
    ): DebugEntry {
        val id = "req-${counter.incrementAndGet()}"
        val ts = LocalDateTime.now().format(formatter)
        return DebugEntry(id, ts, model, requestUrl, requestPayload, messageCount, isStreaming)
    }

    fun addLog(entry: DebugEntry) {
        logs.addFirst(entry)
        while (logs.size > 50) logs.pollLast()
    }

    fun getLogs(): List<DebugEntry> = logs.toList()

    fun clear() = logs.clear()

    companion object {
        fun getInstance(): DebugManager =
            ApplicationManager.getApplication().getService(DebugManager::class.java)
    }
}
