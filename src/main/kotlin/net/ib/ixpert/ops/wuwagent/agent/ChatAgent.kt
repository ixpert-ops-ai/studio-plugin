package net.ib.ixpert.ops.wuwagent.agent

import net.ib.ixpert.ops.wuwagent.prompt.PromptManager

/**
 * 일반적인 대화 및 기술 문답에 대응하기 위해 신설된 Agent 입니다.
 */
class ChatAgent : BaseAgent() {

    override fun execute(
        context: AgentContext, 
        onSuccess: (String) -> Unit, 
        onChunk: ((String) -> Unit)?,
        onError: (String) -> Unit
    ) {
        val userQuery = context.payloadText
        if (userQuery.isBlank()) {
            onError("[알림] 입력된 질문이 없어 답변을 제공할 수 없습니다.")
            return
        }

        var systemPrompt = PromptManager.loadPrompt("chat_prompt.txt")

        // [Phase 1b] 메타그래프 컨텍스트 자동 주입
        val contextAssembler = context.project.getService(net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.ContextAssembler::class.java)
        val graphContext = contextAssembler.assemble(context, userQuery)
        if (graphContext.isNotBlank()) {
            systemPrompt = "$graphContext\n\n$systemPrompt"
        }

        callLlmStreamAsync(context.project, "iXpert AI Assistant: Answering Chat", systemPrompt, userQuery, onSuccess, onChunk, onError)
    }
}
