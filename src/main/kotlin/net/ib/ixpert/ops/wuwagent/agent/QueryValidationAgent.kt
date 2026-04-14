package net.ib.ixpert.ops.wuwagent.agent

import net.ib.ixpert.ops.wuwagent.prompt.PromptManager
import net.ib.ixpert.ops.wuwagent.service.EditorContextService

/** SQL / Query 유효성 검증 Agent */
class QueryValidationAgent : BaseAgent() {
    override fun execute(
        context: AgentContext,
        onSuccess: (String) -> Unit,
        onChunk: ((String) -> Unit)?,
        onError: (String) -> Unit
    ) {
        val editor = context.editor ?: run {
            onError("[상태 이상] 에디터 컨텍스트가 주어지지 않았습니다."); return
        }
        val code = EditorContextService.extractCode(editor, context.project)
        if (code.isBlank()) { onError("[알림] 분석할 코드를 도출하지 못했습니다."); return }

        // OllamaClient 스트리밍 완료 시 done 청크의 message.content가 빈 문자열로 반환되는 문제 대응.
        // onSuccess로 전달되는 resultText가 비어있을 경우 누적된 청크 내용을 사용한다.
        val accumulated = StringBuilder()

        val wrappedOnChunk: ((String) -> Unit)? = onChunk?.let { forwardChunk ->
            { chunk ->
                accumulated.append(chunk)
                forwardChunk(chunk)
            }
        }

        val wrappedOnSuccess: (String) -> Unit = { resultText ->
            val finalContent = if (resultText.isBlank() && accumulated.isNotBlank()) {
                accumulated.toString()
            } else {
                resultText
            }
            onSuccess(finalContent)
        }

        callLlmStreamAsync(
            context.project,
            "WuwAgent: Validating Query",
            PromptManager.loadPrompt("query_validation_prompt.txt"),
            code,
            wrappedOnSuccess,
            wrappedOnChunk,
            onError
        )
    }
}
