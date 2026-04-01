package net.ib.ixpert.ops.wuwagent.agent

import net.ib.ixpert.ops.wuwagent.prompt.PromptManager

/**
 * 일반적인 대화 및 기술 문답에 대응하기 위해 신설된 Agent 입니다.
 */
class ChatAgent : BaseAgent() {

    override fun execute(context: AgentContext, onSuccess: (String) -> Unit) {
        val userQuery = context.payloadText
        if (userQuery.isBlank()) {
            onSuccess("[알림] 입력된 질문이 없어 답변을 제공할 수 없습니다.")
            return
        }

        val systemPrompt = PromptManager.loadPrompt("chat_prompt.txt")

        callLlmAsync(context.project, "WuwAgent: Answering Chat", systemPrompt, userQuery, onSuccess)
    }
}
