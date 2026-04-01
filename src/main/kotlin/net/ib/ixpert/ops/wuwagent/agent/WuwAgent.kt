package net.ib.ixpert.ops.wuwagent.agent

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project

/**
 * 플러그인에 소속된 모든 오케스트레이션 에이전트들이 공통적으로 취해야할 스펙
 */
data class AgentContext(
    val project: Project,
    val editor: Editor? = null,
    val payloadText: String = ""
)

interface WuwAgent {
    /**
     * 에이전트의 워크플로우를 진입시킵니다.
     * 결과는 UI(Bridge) 콜백이나 외부로 토스하기 위해 onSuccess 로 반환.
     */
    fun execute(context: AgentContext, onSuccess: (String) -> Unit)
}
