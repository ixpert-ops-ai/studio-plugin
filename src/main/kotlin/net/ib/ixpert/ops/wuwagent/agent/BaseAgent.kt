package net.ib.ixpert.ops.wuwagent.agent

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import net.ib.ixpert.ops.wuwagent.client.OllamaClient

/**
 * 하위 에이전트들의 쓰레드 분리 및 반복되는 Client 네트워크 로직을 모아주는 추상 클래스
 */
abstract class BaseAgent : WuwAgent {
    protected val logger: Logger = Logger.getInstance(this::class.java)
    protected val ollamaClient = OllamaClient()

    override abstract fun execute(
        context: AgentContext,
        onSuccess: (String) -> Unit,
        onChunk: ((String) -> Unit)?,
        onError: (String) -> Unit
    )

    protected fun callLlmAsync(
        project: Project,
        taskTitle: String,
        systemPrompt: String,
        userMessage: String,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        callLlmStreamAsync(project, taskTitle, systemPrompt, userMessage, onSuccess, null, onError)
    }

    protected fun callLlmStreamAsync(
        project: Project,
        taskTitle: String,
        systemPrompt: String,
        userMessage: String,
        onSuccess: (String) -> Unit,
        onChunk: ((String) -> Unit)? = null,
        onError: (String) -> Unit,
        messageId: String = "msg_${System.currentTimeMillis()}"
    ) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, taskTitle, true) {
            override fun run(indicator: ProgressIndicator) {
                // 새 요청 시작 전 토큰 초기화 및 스레드/messageId 등록
                TaskCancellationToken.reset()
                TaskCancellationToken.backgroundThread = Thread.currentThread()
                TaskCancellationToken.activeMessageId = messageId

                indicator.isIndeterminate = true
                indicator.text = "$taskTitle 요청을 Ollama API에 전달하는 중..."

                logger.info("BaseAgent: 프롬프트 전달 완료. messageId=$messageId, Stream=${onChunk != null}")

                // 1. Client HTTP 스트리밍/블로킹 호출
                val response = ollamaClient.callChatApiStream(systemPrompt, userMessage, onChunk)
                logger.info("BaseAgent: LLM 응답 수신 완료 여부 = ${response?.message != null}")

                // 2. 취소 여부 확인 — onError("__cancelled__") 로 Router에 전달
                //    Router의 onError 에서 "__cancelled__" 는 무시되므로 UI 중복 노출 없음
                if (TaskCancellationToken.isCancelled.get()) {
                    logger.info("BaseAgent: 요청 취소됨 (messageId=$messageId)")
                    onError("__cancelled__")
                    return
                }

                // 3. 내용 파싱 및 방어 로직
                val resultText = response?.message?.content ?: "[오류 발생] 알 수 없는 데이터 널 응답"

                // 4. 에러 감지 및 콜백 분기
                if (resultText.startsWith("[Error]")) {
                    logger.error("BaseAgent: LLM 응답에 에러 포함됨 -> $resultText")
                    onError(resultText)
                } else {
                    onSuccess(resultText)
                }
            }
        })
    }
}
