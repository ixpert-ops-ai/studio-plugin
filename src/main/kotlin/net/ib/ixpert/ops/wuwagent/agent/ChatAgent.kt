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

        val settings = net.ib.ixpert.ops.wuwagent.setting.SettingsState.getInstance()
        val isAndroid = settings.state.frameworkType == net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType.ANDROID
        var systemPrompt = PromptManager.loadPrompt(if (isAndroid) "chat_android_prompt.txt" else "chat_prompt.txt")

        // [Phase 1b] 메타그래프 컨텍스트 자동 주입
        val contextAssembler = context.project.getService(net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.ContextAssembler::class.java)
        val graphContext = contextAssembler.assemble(context, userQuery)
        if (graphContext.isNotBlank()) {
            systemPrompt = "$graphContext\n\n$systemPrompt"
        }

        callLlmStreamAsync(context.project, "iXpert AI Assistant: Answering Chat", systemPrompt, userQuery, onSuccess, onChunk, onError)
    }
}
