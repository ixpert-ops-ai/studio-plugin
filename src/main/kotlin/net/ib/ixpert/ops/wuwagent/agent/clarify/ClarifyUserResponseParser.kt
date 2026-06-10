package net.ib.ixpert.ops.wuwagent.agent.clarify

class ClarifyUserResponseParser {

    /**
     * 단순 규칙 기반(정규식) 파서 (Option A)
     * 예시: "1번 Y, 2번 N", "1 y 2 n", "확정 2번 빼줘"
     * 복잡한 파싱 실패 시 기본값(빈 응답)을 반환하여 Fallback 처리
     */
    fun parse(userInput: String): ClarifyUserResponse {
        val answers = mutableMapOf<Int, String>()
        val additionalNotes = mutableListOf<String>()
        // 참고: Option A에서는 확정 항목 제거를 텍스트로 완벽히 매핑하기 어렵지만,
        // 명시적인 "확정 N번 빼줘" 패턴 정도는 인식할 수 있습니다.
        // 현재는 구현을 단순화하여 추가 노트와 답변만 주로 파싱합니다.
        
        // 빈 응답이거나 "그냥 진행" 등 긍정적 기본 진행 의도
        if (userInput.isBlank() || userInput.trim() == "확인" || userInput.trim() == "그냥 진행") {
            return ClarifyUserResponse(emptyMap(), emptyList(), null)
        }

        // 숫자 뒤에 Y 또는 N이 오는 패턴: "1번 Y", "1 y", "2 N"
        val answerRegex = Regex("(\\d+)번?\\s*([yYnN])")
        answerRegex.findAll(userInput).forEach { matchResult ->
            val id = matchResult.groupValues[1].toInt()
            val answer = matchResult.groupValues[2].uppercase()
            answers[id] = answer
        }

        // 사용자가 숫자 Y/N 패턴을 전혀 사용하지 않았는데 뭔가 입력했다면, 
        // 이를 커스텀 노트로 취급할 수 있음.
        if (answers.isEmpty()) {
            return ClarifyUserResponse(emptyMap(), emptyList(), userInput.trim())
        }
        
        // 정규식 매칭 부분 외에 남은 텍스트가 있다면 additionalNotes로?
        // 단순화를 위해 생략하거나 전체를 넣음
        
        return ClarifyUserResponse(answers, emptyList(), null)
    }
}
