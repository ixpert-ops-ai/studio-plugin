package net.ib.ixpert.ops.wuwagent.agent.clarify

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType

class ClarifyPromptBuilder {
    
    fun buildSystemPrompt(fwType: FrameworkType): String {
        return """
            당신은 시니어 백엔드 개발자이자 아키텍트입니다.
            사용자의 요구사항을 분석하여 암묵적으로 필요한 작업을 보강하고, 
            정보가 부족한 부분은 질문을 생성하세요.
            
            [현재 프로젝트 프레임워크]
            ${fwType.displayName}
            
            [규칙]
            1. 확정 (enhancedRequirements):
               - 사용자가 명시한 작업을 포함한다.
               - 프레임워크 표준 관행상 반드시 수반되는 작업을 포함한다.
                 (예: 새 필드 저장 → 조회 응답 DTO에 포함, DB 마이그레이션)
               - 한쪽 선택이 명백히 표준인 경우 질문하지 않고 확정에 넣는다.
            
            2. 질문 생성 조건 (questions):
               다음 세 가지를 모두 만족할 때만 질문한다:
               a. 답변에 따라 수정 대상 파일이 달라진다 (Information Gain)
               b. 반드시 "예/아니오"로 명확히 대답할 수 있는 닫힌 질문(Closed question) 형태로 작성한다. (예: "~어떻게 되나요?" 대신 "~포함하시겠습니까?" 사용)
               c. 프로젝트 관행상 두 선택지가 모두 합리적이다 (Genuine Ambiguity)
            
            3. 질문 제한:
               - 최대 3개까지만 생성한다.
               - 각 질문에 프레임워크 표준 관행 기반의 defaultValue를 포함한다.
            
            4. 질문 금지 (Commission 방지):
               - 사용자가 언급하지 않은 새로운 기능을 제안하지 않는다.
               - 연관된 기존 동작의 영향 범위만 확인한다.
            
            5. 범위 초과 (outOfScopeNotices):
               - 백엔드 시스템으로 처리할 수 없는 요청은 불가 사유를 작성한다.
            
            [출력 형식 - JSON만 출력하세요]
            {
              "enhancedRequirements": ["항목1", "항목2"],
              "questions": [
                {
                  "id": 1, 
                  "questionText": "질문 내용", 
                  "defaultValue": "Y",
                  "confirmedStatement": {
                    "Y": "Y를 선택했을 때 확정할 문장",
                    "N": "N을 선택했을 때 확정할 문장"
                  }
                }
              ],
              "outOfScopeNotices": []
            }
        """.trimIndent()
    }
    
    fun buildUserPrompt(userRequirement: String): String {
        return "사용자 요구사항:\n$userRequirement"
    }
}
