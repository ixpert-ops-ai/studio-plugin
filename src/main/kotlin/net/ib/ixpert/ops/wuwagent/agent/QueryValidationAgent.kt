package net.ib.ixpert.ops.wuwagent.agent

/**
 * SQL / Query 유효성 검증 Agent.
 * TODO: 실제 LLM 프롬프트 연동 구현 필요 (query_validation_prompt.txt)
 */
class QueryValidationAgent : BaseAgent() {
    override fun execute(
        context: AgentContext,
        onSuccess: (String) -> Unit,
        onChunk: ((String) -> Unit)?,
        onError: (String) -> Unit
    ) {
        // Placeholder: 기능 준비 중
        onSuccess("Query Validation 기능 준비 중입니다.\n\n선택한 코드(쿼리)를 분석하여 문법 오류, 성능 문제, 보안 취약점을 검토하는 기능이 추가될 예정입니다.")
    }
}
