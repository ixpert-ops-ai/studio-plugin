package net.ib.ixpert.ops.wuwagent.agent

import net.ib.ixpert.ops.wuwagent.prompt.PromptManager
import net.ib.ixpert.ops.wuwagent.service.EditorContextService

/** JUnit 테스트 코드를 자동 생성하는 Agent */
class TestAgent : BaseAgent() {
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
        callLlmAsync(context.project, "WuwAgent: Generating Tests",
            PromptManager.loadPrompt("test_prompt.txt"), code, onSuccess, onError)
    }
}
