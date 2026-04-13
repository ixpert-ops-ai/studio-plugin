# WuwAgent

AI Agent 기반 IDE Plugin 프로젝트

---

## 📌 Overview

WuwAgent는 IntelliJ / Android Studio 환경에서
코드 설명, 리뷰, 개선 등을 지원하는 AI 기반 개발 도구이다.

---

## 📖 시작 전 필독 순서

처음 합류했다면 아래 순서대로 읽는다.

| 순서 | 문서 | 목적 |
|---|---|---|
| 1 | README (지금 이 문서) | 전체 구조 파악 |
| 2 | DEVELOPMENT_GUIDE | 아키텍처 및 레이어 이해 |
| 3 | WORKING_GUIDE | 내 담당 기능 확인 + 환경 설정 |
| 4 | AI_DEVELOPMENT_RULES | AI 도구 사용 시 규칙 확인 |

---

## 🔧 작업 시작 체크리스트

```
□ WORKING_GUIDE 1번 — 내 담당 기능 확인
□ WORKING_GUIDE 3번 — 환경 설정 (Context Window 32768 필수)
□ WORKING_GUIDE 4번 — 작업 범위 확인 (수정 가능 / 금지 영역)
□ AI_DEVELOPMENT_RULES — AI 도구 쓰기 전 필독
□ feature/* 브랜치 생성 후 작업 시작
```

---

## 📄 문서 목록

| 문서 | 내용 |
|---|---|
| DEVELOPMENT_GUIDE | 구조 및 설계 원칙 |
| WORKING_GUIDE | 담당 기능, 환경 설정, 작업 방식, Git 규칙 |
| AI_DEVELOPMENT_RULES | AI 도구 작업 규칙 |

---

## 🚀 실행

```
./gradlew runIde
```

---

## 🎯 핵심 구조

```
Action → Agent → Service → LLM → UI
```

---

## 💡 핵심 원칙

구조를 유지하면서 기능을 확장한다.
