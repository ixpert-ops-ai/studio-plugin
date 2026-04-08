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
        onChunk: ((String) -> Unit)?
    )

    protected fun callLlmAsync(
        project: Project,
        taskTitle: String,
        systemPrompt: String,
        userMessage: String,
        onSuccess: (String) -> Unit
    ) {
        callLlmStreamAsync(project, taskTitle, systemPrompt, userMessage, onSuccess, null)
    }

    protected fun callLlmStreamAsync(
        project: Project,
        taskTitle: String,
        systemPrompt: String,
        userMessage: String,
        onSuccess: (String) -> Unit,
        onChunk: ((String) -> Unit)? = null
    ) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, taskTitle, false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "$taskTitle 요청을 Ollama API에 전달하는 중..."

                logger.info("BaseAgent: 프롬프트 전달 완료. Model: qwen3-coder:30b (Stream=${onChunk != null})")

                // 1. Client HTTP 스트리밍/블로킹 호출
                val response = ollamaClient.callChatApiStream(systemPrompt, userMessage, onChunk)
                logger.info("BaseAgent: LLM 응답 수신 완료 여부 = ${response?.message != null}")

                // 2. 내용 파싱 및 방어 로직
                val resultText = response?.message?.content ?: "[오류 발생] 알 수 없는 데이터 널 응답"

                // 3. 전체 완료 콜백 (UI Thread 로 넘기는 동작은 밖의 람다에서 해줌)
                onSuccess(resultText)
            }
        })
    }
}
