# WuwAgent Development Guide

---

## 0. 목적

이 문서는 WuwAgent 프로젝트의 구조와 설계 원칙을 설명한다.
모든 개발자는 이 구조를 이해하고 이를 기반으로 기능을 구현해야 한다.

---

## 1. Architecture

프로젝트는 Agent 기반 구조를 따른다.

```text
ui/
action/
agent/
service/
prompt/
setting/
model/
util/
```

---

## 2. Core Flow

```text
Action → Agent → Service → LLM → Result → UI
```

---

## 3. Layer Responsibilities

### action/

* IDE 이벤트 처리 (우클릭, 버튼 등)
* 기능 실행 진입점
* 비즈니스 로직 금지

---

### ui/

* UI 구성 및 상태 표시
* 사용자 인터랙션 처리
* 비즈니스 로직 금지

---

### agent/

* 핵심 로직 담당
* LLM 호출 및 흐름 제어
* 기능 orchestration 담당

---

### service/

* 파일 IO 및 IDE API 처리
* Tool 역할 수행
* LLM 호출 금지

---

### prompt/

* 모든 프롬프트 관리
* 기능별로 분리
* 코드 내 하드코딩 금지

---

### setting/

* 환경 설정 및 LLM 서버 설정

---

### model/

* 데이터 모델 정의

---

### util/

* 공통 유틸

---

## 4. LLM Rules

* 모든 LLM 호출은 agent에서만 수행
* 다른 레이어에서 HTTP 호출 금지
* Prompt 기반으로만 동작

---

## 5. Agent Design Principles

* Agent는 판단만 수행한다
* 실제 작업은 Service를 통해 수행한다
* 직접 파일 수정 금지

---

## 6. Tool (Service) Principles

* Service는 Tool 역할을 수행한다
* Agent의 요청을 실행만 한다
* 재사용 가능하도록 설계한다

---

## 7. Diff / Apply Rules

* 모든 코드 수정은 Diff 기반으로 수행
* 직접 코드 수정 금지
* Apply는 사용자 승인 후 수행

---

## 8. Context Engineering

LLM 응답 품질은 컨텍스트에 의해 결정된다.

---

### 8.1 Context 구성

* 코드 (필수)
* 코드 상태 (에러 여부)
* 추가 설명 (선택)

---

### 8.2 Context 품질 규칙

* 컴파일 가능한 코드 우선
* 에러 코드일 경우 명시
* 불필요한 코드 전달 금지
* 의미 없는 코드 조각 금지

---

### 8.3 Context 보정

다음 경우 반드시 설명 추가:

* 에러 존재 → "컴파일 오류 포함"
* 일부 코드 → "일부 코드만 제공"
* 역할 불명확 → 코드 유형 명시

---

## 9. Development Principles

* 구조를 우선한다
* 최소 변경 원칙을 따른다
* 기존 코드 수정은 최소화한다
* 확장 가능한 방식으로 구현한다

---

## 10. 금지 사항

* Action에 로직 추가 금지
* Service에서 LLM 호출 금지
* Agent에서 직접 파일 수정 금지
* 구조 변경 금지

---

## 11. 커밋 메시지 규칙

* 커밋 메시지는 반드시 한글로 작성
* 예시: `feat: 취소 로직 개선`, `fix: 빈 말풍선 버그 수정`

---

## Summary

이 프로젝트는 단순 기능 구현이 아닌
Agent 기반 개발 시스템이다.

모든 기능은 동일한 구조를 따르며,
구조를 유지하는 것이 가장 중요하다.
