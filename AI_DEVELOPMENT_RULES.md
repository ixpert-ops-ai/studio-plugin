# AI Development Rules (WuwAgent)

---

## 0. 목적

이 문서는 모든 AI가 WuwAgent 프로젝트에서 코드를 수정하거나 생성할 때 반드시 따라야 하는 기준이다.
모든 작업은 이 규칙을 최우선으로 따른다.

---

## 1. Architecture (절대 준수)

```text
Action → Agent → Service → LLM → UI
```

규칙:

* Action: 진입점만 담당 (로직 금지)
* Agent: 판단 및 흐름 제어
* Service: 실행 (Tool 역할)
* LLM 호출은 Agent에서만 수행

---

## 2. Layer Rules

### Agent

* LLM 호출 담당
* 비즈니스 로직 처리
* Tool(Service) 호출만 수행
* 직접 파일 수정 금지

---

### Service (Tool)

* 파일 처리, IDE API, Diff 적용
* LLM 호출 금지
* Agent의 요청을 실행만 수행

---

### Action

* 이벤트 처리만 수행
* 비즈니스 로직 금지

---

## 3. Prompt Rules

* 모든 프롬프트는 prompt/*.txt에서 관리
* 코드 내 하드코딩 금지
* 기능별로 분리

---

## 4. Context Engineering Rules

LLM 응답 품질은 컨텍스트에 의해 결정된다.

### 구성 요소

* 코드 (필수)
* 코드 상태 (에러 여부)
* 추가 설명 (선택)

---

### 규칙

* 컴파일 가능한 코드 우선
* 에러 코드일 경우 반드시 명시
* 불완전한 코드 그대로 전달 금지
* 불필요하게 긴 코드 금지

---

### 보정

다음 경우 반드시 설명 추가:

* 에러 존재 → "컴파일 오류 포함"
* 일부 코드 → "일부 코드만 제공"
* 역할 불명확 → 코드 유형 명시

---

## 5. Diff Rules

* 코드 수정은 반드시 Diff로 생성
* 직접 코드 작성 금지
* apply_diff Tool 사용

---

## 6. Development Rules

* 최소 변경 원칙 (불필요한 수정 금지)
* 기존 구조 유지
* 다른 기능 코드 수정 금지

---

## 7. Cancellation Rules

* 모든 LLM 요청은 messageId 기반 관리
* 취소 요청 시 즉시 스트림 종료
* UI와 동기화 유지

---

## 8. 금지 사항

* Service에서 LLM 호출 금지
* Agent에서 파일 직접 수정 금지
* 구조 변경 금지
* Action에 로직 추가 금지

---

## 9. 목표

AI는 다음을 만족해야 한다:

* 구조를 유지한다
* 불필요한 변경을 하지 않는다
* 정확한 컨텍스트 기반으로 응답한다

## 10. Action Rules (Critical)

* 기존 Action 클래스 수정 금지
* 새로운 기능은 기존 Action 패턴을 복제하여 생성
* Action에 비즈니스 로직 구현 금지

---

## 11. AI Role Constraints

### Codex

* 기능 구현만 수행
* 구조 변경 금지
* Agent 흐름 변경 금지
* Action 수정 금지

### Gemini

* 코드 리뷰 및 검수만 수행
* 구조 변경 제안 금지
* Action 수정 금지

---

## 12. Error Handling Rules

* 에러 발생 시 Diff 생성 금지
* Apply 수행 금지
* 즉시 처리 중단

---

## 13. Prompt Mandatory Rule

* Prompt 없이 LLM 호출 금지
* Prompt는 기능 정의의 핵심
* 모든 기능은 Prompt 기반으로 동작해야 한다
