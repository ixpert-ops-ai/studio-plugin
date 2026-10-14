package net.ib.ixpert.ops.wuwagent.agent.eval

import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.model.ChatCompletionResponse
import net.ib.ixpert.ops.wuwagent.model.ChatMessage
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.ToolDefinition
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 운영 경로의 [LLMClient](보통 OpenAIClient)를 감싸 호출마다 시간·결과를 기록하고, 호출 상한(기본 120초)을 넘으면 포기한다.
 * 상한 초과는 `timeout`으로 기록하고 평가는 계속한다: chat은 null, chatWithTools는 [SocketTimeoutException]을 돌려준다(운영 클라이언트가 던지는 모양).
 * 포기한 호출의 연결은 위임 클라이언트의 자체 읽기 제한(설정 기본 300초)까지 남을 수 있다.
 */
class MeteredLlmClient(
    private val delegate: LLMClient,
    private val callTimeoutSec: Long = 120
) : LLMClient {

    data class CallRecord(
        val phase: String,        // clarify | analyze
        val kind: String,         // chat | chatWithTools
        val ms: Long,
        val outcome: String,      // ok | null | timeout | exception
        val detail: String? = null
    )

    /** Stage 3 검증기 호출(시스템 프롬프트 표지)의 user 내용. 요구사항 구간 추출용. */
    val stage3UserContents = CopyOnWriteArrayList<String>()
    val records = CopyOnWriteArrayList<CallRecord>()
    @Volatile var phase: String = "clarify"
    @Volatile var firstModel: String? = null

    private val executor = Executors.newCachedThreadPool { r -> Thread(r, "eval-llm").apply { isDaemon = true } }

    fun reset() {
        records.clear()
        stage3UserContents.clear()
    }

    private fun noteStage3(systemPrompt: String, userContents: List<String>) {
        if (systemPrompt.contains("당신은 코드 변경 범위 검증자입니다.")) stage3UserContents.addAll(userContents)
    }

    private fun <T> timed(kind: String, onTimeout: () -> T?, block: () -> T?): T? {
        val future = executor.submit<T?> { block() }
        val t0 = System.nanoTime()
        fun ms() = (System.nanoTime() - t0) / 1_000_000
        try {
            val result = future.get(callTimeoutSec, TimeUnit.SECONDS)
            records.add(CallRecord(phase, kind, ms(), if (result == null) "null" else "ok"))
            return result
        } catch (e: TimeoutException) {
            future.cancel(true)
            records.add(CallRecord(phase, kind, ms(), "timeout", "상한 ${callTimeoutSec}초 초과"))
            return onTimeout()
        } catch (e: ExecutionException) {
            val cause = e.cause ?: e
            records.add(CallRecord(phase, kind, ms(), "exception", cause.javaClass.simpleName + ": " + (cause.message ?: "").take(120)))
            throw cause
        }
    }

    override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): OllamaChatResponse? {
        noteStage3(systemPrompt, listOf(userCode))
        val r = timed("chat", onTimeout = { null }) { delegate.chat(systemPrompt, userCode, maxTokens, onChunk) }
        if (firstModel == null && r != null) firstModel = r.model
        return r
    }

    override fun chatWithTools(
        systemPrompt: String,
        messages: List<ChatMessage>,
        maxTokens: Int?,
        tools: List<ToolDefinition>?,
        toolChoice: Any?,
        temperature: Double?
    ): ChatCompletionResponse? {
        noteStage3(systemPrompt, messages.mapNotNull { it.content })
        return timed("chatWithTools", onTimeout = { throw SocketTimeoutException("eval 호출 상한 ${callTimeoutSec}초 초과") }) {
            delegate.chatWithTools(systemPrompt, messages, maxTokens, tools, toolChoice, temperature)
        }
    }

    override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = delegate.fetchModels(baseUrl, apiKey)
}
