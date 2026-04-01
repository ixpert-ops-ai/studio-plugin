package net.ib.ixpert.ops.wuwagent.agent

import net.ib.ixpert.ops.wuwagent.prompt.PromptManager
import net.ib.ixpert.ops.wuwagent.service.EditorContextService

/** 코드 품질·안티패턴·가독성을 리뷰하는 Agent */
class ReviewAgent : BaseAgent() {
    override fun execute(context: AgentContext, onSuccess: (String) -> Unit) {
        val editor = context.editor ?: run {
            onSuccess("[상태 이상] 에디터 컨텍스트가 주어지지 않았습니다."); return
        }
        val code = EditorContextService.extractCode(editor, context.project)
        if (code.isBlank()) { onSuccess("[알림] 분석할 코드를 도출하지 못했습니다."); return }
        callLlmAsync(context.project, "WuwAgent: Reviewing Code",
            PromptManager.loadPrompt("review_prompt.txt"), code, onSuccess)
    }
}
