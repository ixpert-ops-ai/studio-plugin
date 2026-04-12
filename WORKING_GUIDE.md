# WuwAgent Working Guide

---

## 0. 목적

이 문서는 팀원이 실제로 작업할 때 따라야 할 방식과 범위를 정의한다.
모든 작업은 이 가이드를 기준으로 수행한다.

---

## 1. 개발 환경

### IntelliJ 사용자 (팀원)

./gradlew runIde

* 플러그인을 개발 모드로 실행
* 코드 수정 후 즉시 테스트 가능

---

### Android Studio 사용자 (검증 담당)

./gradlew buildPlugin

* zip 파일 생성 후 IDE에 설치하여 테스트

---

## 2. 작업 범위 (중요)

모든 팀원은 아래 범위 내에서만 작업한다.

### 허용

* prompt/*.txt 수정
* 각 기능별 *Agent.kt 수정

---

### 금지

* Action 수정 금지
* Service 수정 금지
* 구조 변경 금지
* 다른 기능 코드 수정 금지

---

## 3. 작업 흐름

개발 → runIde 테스트 → 수정 반복 → 완료 → 커밋

---

## 4. 테스트 흐름

### 개발 단계

* runIde로 기능 테스트
* 빠르게 반복 개발

---

### 최종 검증

* buildPlugin 실행
* zip 생성 확인
* IDE 설치 후 테스트

---

## 5. 역할 분리

* 팀원: 기능 개발 및 1차 테스트
* 담당자: 최종 빌드 및 검증

---

## 6. 핵심 규칙

* 구조를 건드리지 않는다
* Agent + Prompt만 수정한다
* 테스트 후 커밋한다

---

## 핵심 한 줄

각자 기능은 다르지만
작업 방식은 동일하다.

---

## 7. Git 작업 규칙

---

### 브랜치 전략

* main: 배포 기준 브랜치
* develop: 개발 통합 브랜치
* feature/*: 기능 작업 브랜치

---

### 작업 흐름

feature 브랜치 생성
→ 기능 개발
→ runIde 테스트
→ 안정화 완료
→ 커밋
→ develop 브랜치로 푸시

---

### 커밋 규칙

* 기능 단위 완료 후 1회 커밋
* 중간 커밋 금지
* 디버깅 코드 커밋 금지

---

### 커밋 메시지 예시

feat: implement improve agent
fix: resolve stop cancellation issue
refactor: clean agent structure

---

### 금지 사항

* 테스트 전 커밋 금지
* 미완성 코드 푸시 금지
* main 브랜치 직접 푸시 금지

---

## 핵심 원칙

커밋은 결과물이다, 과정이 아니다
