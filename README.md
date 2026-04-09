# WUW Agent Plugin

---

## 📌 프로젝트 소개

WUW Agent Plugin은 단순한 AI 기능이 아닌,

코드 분석 → 개선 → Diff → 적용까지 이어지는  
AI 기반 개발 보조 시스템입니다.

Agent 구조를 기반으로 설계되어  
기능을 확장 가능하고 재사용 가능한 형태로 제공합니다.

---

## 🎯 핵심 목표

- AI 기반 코드 분석 및 개선 자동화
- Diff 기반 안전한 코드 변경
- Agent 구조를 통한 확장 가능한 시스템 구축

---

## 🧠 핵심 개념

    Action → Agent → Service → LLM → Result → UI

---

## 🔹 Agent 기반 구조

- Agent는 기능 단위 실행 주체
- Prompt를 통해 기능 정의
- Service를 통해 실제 작업 수행

---

## 🔹 Diff 기반 적용

- 코드 변경은 반드시 Diff 형태로 수행
- 사용자 검토 후 Apply
- 자동 적용 금지

---

## 🧩 주요 기능

우클릭 메뉴:

    iXpert AI Assistant →
        ├ Explain This Code (코드 설명)
        ├ Review This Code (코드 리뷰)
        ├ Impact Analysis (영향도 분석)
        ├ Query Validation (쿼리 검증)
        ├ Improve This Code (코드 개선)
        ├ Generate Test (테스트 생성)

---

## ⚙️ 프로젝트 구조

    ui/
    action/
    agent/
    service/
    prompt/
    setting/
    model/
    util/

---

## 🔄 실행 흐름

### Explain

    코드 선택
    → Explain Action
    → Explain Agent
    → LLM 호출
    → 텍스트 출력

---

### Improve

    코드 선택
    → Improve Action
    → Improve Agent
    → 코드 분석
    → LLM 개선 요청
    → Diff 생성
    → Apply

---

## 🧱 역할 분리

| Layer   | 역할 |
|--------|------|
| Action | 이벤트 진입점 |
| Agent  | 흐름 제어 + LLM 호출 |
| Service| 파일/IDE 처리 |
| Prompt | 기능 정의 |
| UI     | 결과 출력 |

---

## 👥 팀 개발 방식

### 개발 원칙

- Action 수정 금지
- Agent 중심 개발
- Prompt 기반 기능 정의
- Service 공통 사용

---

### 기능 개발 방법

1. Agent 생성
2. Prompt 작성
3. Agent에서 LLM 호출 구현

---

## 📄 문서

- Architecture.md
- WUW_GUIDELINES.md
- AI_RULES.md

---

## 🌿 브랜치 전략

    main     → 배포
    develop  → 개발 통합
    feature/* → 기능 단위 작업

---

## 🔐 핵심 원칙

- 구조를 깨지 않는다
- 최소 변경 원칙
- Agent 중심 구조 유지
- Diff 기반 코드 수정

---

## 💡 철학

    기능을 만드는 것이 아니라
    AI 실행 구조를 만든다

---

## 🚀 Summary

    AI 기반 개발 보조 사이클

    → 분석
    → 개선
    → Diff
    → 적용

    확장 가능한 Agent 구조