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

## 5. 작업 완료 기준

하나의 작업(Task)은 아래 조건을 만족해야 완료로 간주한다.

- 코드 수정 완료
- 빌드 성공
- _release 폴더에 최신 zip 반영
- 기능 테스트 완료
- Git 커밋 완료

---

## 6. 빌드 및 배포 규칙 (필수)

모든 코드 수정 작업 완료 후 반드시 아래 절차를 수행한다.

1. 플러그인 빌드 수행
   - 명령어: ./gradlew buildPlugin

2. 빌드 결과 확인
   - 경로: build/distributions
   - 최신 .zip 파일 생성 여부 확인

3. 배포 파일 복사
   - build/distributions의 최신 zip을 루트의 `_release` 폴더로 복사
   - 기존 파일이 있을 경우 덮어쓰기 또는 정리

4. 실패 처리
   - 빌드 실패 시 즉시 중단하고 오류 로그 출력
   - 복사 실패 시 원인 출력

5. 검증
   - 항상 최신 빌드 파일 기준으로 테스트 수행
   - 이전 빌드 파일 사용 금지

목표:
코드 수정 → 빌드 → 배포 → 테스트가 항상 일관되게 수행되도록 한다.

---

## 7. Diff / Apply 규칙

모든 코드 수정은 반드시 Diff 기반으로 수행한다.

1. LLM은 직접 코드 수정 결과를 반환하지 않는다
2. 반드시 원본 대비 변경점(Diff) 형태로 결과 생성
3. 사용자는 Diff를 검토 후 Apply 수행
4. 자동 적용 금지 (사용자 승인 필수)
5. Diff 없이 파일 직접 수정 금지

목표:
코드 변경의 안정성과 추적 가능성 확보

---

## 8. Agent 역할 규칙

Agent는 역할에 따라 아래 두 가지로 구분한다.

### Text Agent
- Explain
- Review
- Impact Analysis
- Secure Coding
- Query Validation

특징:
- 텍스트만 출력
- 코드 수정 금지

---

### Code Agent
- Improve
- Generate Test

특징:
- Diff 생성 가능
- Apply와 연결됨

---

규칙:
- Text Agent는 코드 변경 금지
- Code Agent만 Diff 생성 가능

---

## 9. Tool (Skill) 규칙

Agent는 직접 작업을 수행하지 않고 Tool을 통해 작업을 수행한다.

### 주요 Tool 예시
- read_file: 파일 내용 조회
- get_selection: 선택 영역 추출
- apply_diff: 코드 변경 적용
- search_reference: 참조 코드 탐색

### 규칙
1. Agent는 직접 파일 수정 금지
2. 모든 파일 변경은 apply_diff Tool을 통해 수행
3. Tool은 service 레이어를 통해 구현
4. Tool은 재사용 가능하도록 설계

목표:
Agent는 판단만, 실제 작업은 Tool이 수행하도록 역할 분리

---

## Summary

```text
IDE Plugin + Agent Architecture + LLM Integration
```

이 프로젝트는 단순 기능 구현이 아닌,
확장 가능한 AI Agent 기반 개발 보조 시스템을 구축하는 것을 목표로 한다.
