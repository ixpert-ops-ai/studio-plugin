package net.ib.ixpert.ops.wuwagent.agent.clarify

class ClarifyUserResponseParser {

    /**
     * 단순 규칙 기반(정규식) 파서 (Option A)
     * 예시: "1번 Y, 2번 N", "1 y 2 n", "확정 2번 빼줘"
     * 복잡한 파싱 실패 시 기본값(빈 응답)을 반환하여 Fallback 처리
     */
    fun parse(userInput: String): ClarifyUserResponse {
        // 빈 응답이거나 "그냥 진행" 등 긍정적 기본 진행 의도
        if (userInput.isBlank() || userInput.trim() == "확인" || userInput.trim() == "그냥 진행") {
            return ClarifyUserResponse(emptyList())
        }

        return ClarifyUserResponse(listOf(userInput.trim()))
    }
}
