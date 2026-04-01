# WhatUWant? (WuwAgent) 개발 지침 및 사양서

---

## 0. Development Rules (Priority Highest)

이 프로젝트는 아래 개발 규칙을 최우선으로 따른다.
기존 기능 구현보다 구조와 일관성을 우선한다.

---

### 0.1 Architecture

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

### 0.2 Layer Responsibilities

* **action/**

  * IDE 이벤트 처리 (우클릭, 버튼 클릭 등)
  * 기능 실행의 시작점

* **ui/**

  * Tool Window 및 UI 구성
  * 비즈니스 로직 금지

* **agent/**

  * 핵심 로직 담당
  * LLM 호출 및 흐름 제어
  * 기능 orchestration 담당

* **service/**

  * 파일 IO, IDE API 처리
  * LLM 관련 로직 금지

* **prompt/**

  * 모든 프롬프트 관리
  * 코드 내 하드코딩 금지

* **setting/**

  * 환경설정 및 LLM 서버 설정 관리

* **model/**

  * 데이터 모델 정의

* **util/**

  * 공통 유틸

---

### 0.3 Core Flow

```text
Action → Agent → Service → LLM → Result → UI
```

---

### 0.4 LLM Rules

* 모든 LLM 호출은 agent 레이어에서만 수행
* 다른 레이어에서 직접 HTTP 호출 금지
* 프롬프트는 반드시 외부 파일로 관리
* 기능별 프롬프트 분리

---

### 0.5 Development Rules

* UI와 로직은 반드시 분리
* Action에서 비즈니스 로직 구현 금지
* Agent 중심 구조 유지
* 최소 변경 원칙 적용 (불필요한 파일 수정 금지)
* 확장 가능한 구조 유지

---

## 1. 프로젝트 개요

* **설명**: 폐쇄망 환경에서 내부 Ollama 서버와 연동하여 코드 설명, 리뷰, 개선 등 개발 보조 기능 제공
* **타겟 IDE**: IntelliJ IDEA, Android Studio (2023.2 이상)
* **개발 스택**: Kotlin (Plugin) + React/TypeScript (JCEF WebView)
* **LLM 서버**: `http://ollama.jodongik.cloud` (포트 : 11434, API Key: ollama)
* **사용 모델**: `qwen3-coder:30b`

---

## 2. 핵심 기능 목록 (컨텍스트 메뉴)

에디터 우클릭 시 컨텍스트(선택 범위 또는 전체 파일)에 따라 다음 작업들을 수행합니다:

1. **Explain This Code (코드 설명)**
2. **Review This Code (코드 리뷰)**
3. **Run PMD Analysis (정적 분석)**
4. **Impact Analysis (영향도 분석)**
5. **Query Validation (쿼리 검증)**
6. **Secure Coding (보안 취약점 분석)**
7. **Improve This Code (코드 개선)**
8. **Generate Test (테스트 자동 생성)**

---

## 3. 구조 및 UI 동작 원리

* **Tool Window**: 우측 패널에 React 기반의 웹 앱 (JCEF) 렌더링. 단일 HTML 파일로 번들링됨 (`_release/` 퀄리티)
* **상태 관리**: `PersistentStateComponent`를 통해 세션 및 설정 등 상태 영구 저장
* **명령어 입력**: 슬래시(`/`) 기반의 프롬프트 호출 (e.g., `/explain`, `/review`)

---

## 4. 에이전트 개발 규칙 (AI 가이드라인)

* 플러그인 완성 시 설치 파일은 루트의 `_release` 폴더에 생성(`.zip`)
* 백엔드(Kotlin)와 웹뷰(React)간의 인터페이스는 JCEF Bridge를 통해 메시지 패싱 방식으로 구성
* 불필요한 파일 덮어쓰기를 방지하고 최소한의 변경으로 점진적으로 기능을 추가

---

## Summary

```text
IDE Plugin + Agent Architecture + LLM Integration
```

이 프로젝트는 단순 기능 구현이 아닌,
확장 가능한 AI Agent 기반 개발 보조 시스템을 구축하는 것을 목표로 한다.
