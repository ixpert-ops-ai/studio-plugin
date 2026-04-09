package net.ib.ixpert.ops.wuwagent.agent

import net.ib.ixpert.ops.wuwagent.prompt.PromptManager
import net.ib.ixpert.ops.wuwagent.service.EditorContextService

/** 코드 변경 영향 범위를 분석하는 Agent */
class ImpactAgent : BaseAgent() {
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
        callLlmAsync(context.project, "WuwAgent: Analyzing Impact",
            PromptManager.loadPrompt("impact_prompt.txt"), code, onSuccess, onError)
    }
}
