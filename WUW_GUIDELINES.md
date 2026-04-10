# WhatUWant? (WuwAgent) 개발 지침 및 사양서 (최종 개정본)

---

## 0. Development Rules (Priority Highest)

이 프로젝트는 아래 개발 규칙을 최우선으로 따른다.
기능 구현보다 구조, 일관성, 안정성을 우선한다.

---

## 0.1 Architecture

ui/
action/
agent/
service/
prompt/
setting/
model/
util/

---

## 0.2 Layer Responsibilities

action/

* IDE 이벤트 처리 (우클릭, 버튼 클릭 등)
* 기능 실행의 시작점
* 비즈니스 로직 금지

ui/

* Tool Window 및 UI 구성
* 상태 표시 및 사용자 인터랙션 처리
* 비즈니스 로직 금지

agent/

* 핵심 로직 담당
* LLM 호출 및 흐름 제어
* 기능 orchestration 담당

service/

* 파일 IO, IDE API 처리
* Tool 구현 레이어
* LLM 관련 로직 금지

prompt/

* 모든 프롬프트 관리
* 코드 내 하드코딩 금지
* 기능별 프롬프트 분리

setting/

* 환경설정 및 LLM 서버 설정 관리

model/

* 데이터 모델 정의

util/

* 공통 유틸

---

## 0.3 Core Flow

Action → Agent → Service → LLM → Result → UI

---

## 0.4 LLM Rules

* 모든 LLM 호출은 반드시 agent 레이어에서 수행
* 다른 레이어에서 직접 HTTP 호출 금지
* 프롬프트는 반드시 외부 파일로 관리
* 기능별 프롬프트 분리
* system / user 역할 구조 유지

---

## 0.5 Development Rules

* UI와 로직은 반드시 분리
* Action에서 비즈니스 로직 구현 금지
* Agent 중심 구조 유지
* 최소 변경 원칙 적용 (불필요한 파일 수정 금지)
* 확장 가능한 구조 유지

---

## 1. 프로젝트 개요

* 설명: 폐쇄망 환경에서 내부 Ollama 서버와 연동하여 코드 설명, 리뷰, 개선 등 개발 보조 기능 제공
* 타겟 IDE: IntelliJ IDEA, Android Studio (2023.2 이상)
* 개발 스택: Kotlin (Plugin) + React/TypeScript (JCEF WebView)
* LLM 서버: http://ollama.jodongik.cloud:11434

---

## 2. 핵심 기능 목록

1. Explain This Code (코드 설명)
2. Review This Code (코드 리뷰)
3. Impact Analysis (영향도 분석)
4. Query Validation (쿼리 검증)
5. Improve This Code (코드 개선)
6. Generate Test (테스트 생성)

---

## 3. UI 및 동작 구조

* Tool Window: React 기반 JCEF WebView
* 단일 HTML 번들 (_release)
* 상태 관리: PersistentStateComponent
* 명령 입력: 슬래시 (/explain, /review 등)

---

## 4. Agent 개발 규칙

* Agent는 orchestration만 담당
* 실제 작업은 Tool을 통해 수행
* 불필요한 파일 수정 금지
* 최소 변경 원칙 유지

---

## 5. 개발 워크플로우 (핵심)

개발 → runIde 테스트 → 안정화 → 빌드 → 설치 검증 → 커밋

---

### 단계별 규칙

1. 개발 단계

* 코드 수정 및 기능 구현
* 커밋 / 빌드 / 배포 금지

2. 테스트 단계

* ./gradlew runIde 실행
* 기능 검증 수행
* 문제 발생 시 수정 반복

3. 안정화 단계

* runIde 기준 정상 동작 확인

4. 빌드 단계

* ./gradlew buildPlugin

5. 배포 파일 생성

* build/distributions 경로 확인
* zip 생성 확인 후 _release로 복사

6. 설치 검증

* IDE 설치 후 최종 테스트

7. 커밋 및 푸시

* 기능 단위 완료 후 1회 커밋
* 중간 커밋 금지

---

### 금지 규칙

* 테스트 전 빌드 금지
* 미완성 코드 커밋 금지
* 자동 커밋 금지
* 디버깅 코드 커밋 금지

---

### 핵심 원칙

커밋은 결과물이다, 과정이 아니다

---

## 6. Diff / Apply 규칙

1. LLM은 직접 코드 수정 결과를 반환하지 않는다
2. 반드시 Diff 형태로 반환
3. 사용자가 검토 후 Apply 수행
4. 자동 적용 금지
5. 파일 직접 수정 금지

---

## 7. Agent 역할 정의

Text Agent

* Explain
* Review
* Impact Analysis
* Query Validation

특징:

* 텍스트만 출력
* 코드 수정 금지

Code Agent

* Improve
* Generate Test

특징:

* Diff 생성
* Apply 가능

---

## 8. Tool 규칙

주요 Tool

* read_file
* get_selection
* apply_diff
* search_reference

규칙

* Agent는 직접 파일 수정 금지
* 모든 변경은 apply_diff 통해 수행
* Tool은 service 레이어에서 구현
* Tool은 재사용 가능해야 함

---

## 9. 기능 개발 가이드

1. Agent 생성
2. prompt 파일 생성
3. LLM 호출 구현
4. UI 출력 연결

주의사항

* Action 수정 금지
* Service는 공통 로직만 사용
* LLM 호출은 Agent에서만

---

## 10. 브랜치 전략

main: 배포
develop: 통합
feature/*: 기능

feature → develop → main

---

## Summary

IDE Plugin + Agent Architecture + LLM Integration

이 프로젝트는 단순 기능 구현이 아닌
확장 가능한 AI Agent 기반 개발 시스템 구축을 목표로 한다.
