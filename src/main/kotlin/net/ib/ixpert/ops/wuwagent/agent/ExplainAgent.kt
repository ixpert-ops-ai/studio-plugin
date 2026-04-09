package net.ib.ixpert.ops.wuwagent.agent

import net.ib.ixpert.ops.wuwagent.prompt.PromptManager
import net.ib.ixpert.ops.wuwagent.service.EditorContextService

/**
 * 코드 설명(Explain)을 전담하는 특정 목적의 에이전트.
 */
class ExplainAgent : BaseAgent() {

    override fun execute(
        context: AgentContext, 
        onSuccess: (String) -> Unit, 
        onChunk: ((String) -> Unit)?,
        onError: (String) -> Unit
    ) {
        val editor = context.editor
        if (editor == null) {
            onError("[상태 이상] 에디터 컨텍스트가 주어지지 않았습니다.")
            return
        }

        // 1. Service: 코드 영역 획득
        val codeToExplain = EditorContextService.extractCode(editor, context.project)
        if (codeToExplain.isBlank()) {
            onError("[알림] 분석할 코드를 도출하지 못했습니다.")
            return
        }

        // 2. PromptManager: 프롬프트 세팅
        val systemPrompt = PromptManager.loadPrompt("explain_prompt.txt")

        // 3. BaseAgent의 스트리밍 호출 메서드를 사용하여 구동 위임
        callLlmStreamAsync(
            context.project, 
            "WuwAgent: Explaining Code", 
            systemPrompt, 
            codeToExplain, 
            onSuccess, 
            onChunk,
            onError
        )
    }
}
