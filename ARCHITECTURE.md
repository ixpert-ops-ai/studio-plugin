# WUW Agent Plugin - Architecture Overview

---

## 1. 프로젝트 개요

본 프로젝트는 단순한 AI 기능이 아닌,

코드 분석 → 개선 → Diff → 적용까지 이어지는  
AI 기반 개발 보조 시스템입니다.

Agent 구조를 기반으로 설계되어  
기능을 확장 가능하고 재사용 가능한 형태로 제공합니다.

---

## 2. 전체 구조 (Flow)

    User Action
        ↓
    Action (진입점)
        ↓
    Agent (흐름 제어 / LLM 호출)
        ↓
    Service (IDE / 파일 처리)
        ↓
    LLM (Ollama / Model)
        ↓
    Agent (결과 처리)
        ↓
    UI (텍스트 / Diff / Apply)

---

## 3. Layer 역할

### 3.1 Action

- 우클릭 메뉴 및 UI 이벤트 진입점
- Agent 호출 역할만 수행
- 비즈니스 로직 포함 금지

    Action → Agent 호출 전용

---

### 3.2 Agent (핵심)

- 전체 흐름 제어
- 프롬프트 선택 및 로드
- LLM 호출
- 결과 해석 및 후처리
- 필요 시 Service 호출

    Agent = 판단 + 흐름 제어

---

### 3.3 Service

- IDE API 처리
- 파일 읽기/쓰기
- Diff 적용
- Document 처리

    Service = 실제 작업 수행

---

### 3.4 Prompt

- 기능 정의
- LLM 출력 형식 제어
- 기능별로 분리 관리

    Explain / Review / Improve 등 기능별 prompt 존재

---

### 3.5 UI

- 결과 출력
- 사용자 인터랙션 처리
- Diff Apply / Undo 제공

---

## 4. 핵심 실행 사이클

### 4.1 Explain

    코드 선택
    → Explain Action
    → Explain Agent
    → Prompt 적용
    → LLM 호출
    → 텍스트 출력

---

### 4.2 Improve

    코드 선택
    → Improve Action
    → Improve Agent
    → 코드 분석
    → LLM 개선 요청
    → Diff 생성
    → UI 표시
    → Apply

---

## 5. UI 구조

### 말풍선 분리 구조

    [텍스트 말풍선]
    - 설명 / 분석 결과
    - Copy / Save

    [코드 말풍선]
    - Diff 카드
    - Apply 버튼

---

## 6. 기능 구조 (Agent 기반)

    ExplainAgent
    ReviewAgent
    ImpactAgent
    QueryValidationAgent
    ImproveAgent
    GenerateTestAgent

각 기능은 아래 구조를 따른다:

    Action → Agent → Prompt → LLM → 결과

---

## 7. 개발 규칙 (필수)

### 반드시 지켜야 할 사항

    1. Action 수정 금지 (공통 구조 유지)
    2. Agent에서만 LLM 호출
    3. Service에서 LLM 호출 금지
    4. Prompt는 외부 파일로 관리
    5. Diff 기반 코드 수정 유지

---

## 8. 팀 작업 방식

각 기능 담당자는 아래만 구현합니다:

    1. Agent 로직
    2. Prompt 작성

공통 영역은 이미 구현되어 있습니다:

    - Action
    - Service
    - UI

---

## 9. 확장 전략

본 시스템은 다음을 목표로 합니다:

    - Agent 단위 기능 확장
    - Prompt 기반 기능 정의
    - 재사용 가능한 구조

---

## 10. 핵심 철학

    기능을 만드는 것이 아니라
    AI 실행 구조를 만든다

---

## Summary

    AI 기반 개발 보조 사이클

    → 분석
    → 개선
    → Diff
    → 적용

    확장 가능한 Agent 구조