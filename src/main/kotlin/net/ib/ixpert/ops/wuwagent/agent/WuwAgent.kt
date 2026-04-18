package net.ib.ixpert.ops.wuwagent.agent

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project

/**
 * 플러그인에 소속된 모든 오케스트레이션 에이전트들이 공통적으로 취해야할 스펙
 */
data class AgentContext(
    val project: Project,
    val editor: Editor? = null,
    val payloadText: String = "",
    val startLine: Int? = null,
    val endLine: Int? = null
)

interface WuwAgent {
    /**
     * 에이전트의 워크플로우를 진입시킵니다.
     * @param onSuccess 전체 작업 완료 시 결과 반환
     * @param onChunk 스트리밍 데이터 수신 시 호출 (선택 사항)
     */
    fun execute(
        context: AgentContext, 
        onSuccess: (String) -> Unit, 
        onChunk: ((String) -> Unit)? = null,
        onError: (String) -> Unit
    )
}
