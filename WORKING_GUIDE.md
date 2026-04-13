# WuwAgent Working Guide

---

## 0. 목적

이 문서는 WuwAgent 팀원이 기능을 개발할 때 따라야 하는 실무 작업 가이드이다.
구조 설계는 `DEVELOPMENT_GUIDE.md`, AI 작업 규칙은 `AI_DEVELOPMENT_RULES.md`를 참고한다.

---

## 1. 담당 기능 현황

| 기능 | 담당 | 상태 |
|---|---|---|
| Explain This Code | 안윤진 | 개발 중 |
| Review This Code | 장진욱 | 개발 중 |
| Impact Analysis | 안윤진 | 개발 중 |
| Query Validation | 장진욱 | 개발 중 |
| Improve This Code | 이정준 | 개발 중 (이슈 대응 중) |
| Generate Test | 조동익 | 개발 중 |

---

## 2. 개발 환경

### IntelliJ 사용자 (팀원)

```
./gradlew runIde
```

* 플러그인을 개발 모드로 실행
* 코드 수정 후 즉시 테스트 가능

---

### Android Studio 사용자 (검증 담당)

```
./gradlew buildPlugin
```

* zip 파일 생성 후 IDE에 설치하여 테스트

---

## 3. 환경 설정 (필수 확인)

플러그인 설정 화면 (`Settings → Tools → iXpert AI Assistant`)에서 반드시 확인:

| 항목 | 권장값 | 비고 |
|---|---|---|
| Base URL | http://ollama.jodongik.cloud | 변경 금지 |
| Model | qwen3-coder:30b | 변경 금지 |
| Context Window | 32768 | 기본값 4096은 큰 파일에서 실패함 |
| Timeout | 300 | 큰 파일 처리 시 필요 |
| Temperature | 0.1 | 코드 생성 최적값 |

> ⚠️ Context Window 4096은 약 250줄 이상 파일에서 LLM 응답 오류를 유발한다.

---

## 4. 작업 범위 (중요)

모든 팀원은 아래 범위 내에서만 작업한다.

### 허용

* `agent/` — 담당 기능의 Agent 클래스
* `prompt/` — 담당 기능의 Prompt 파일

### 금지

* `action/` 수정 금지
* `service/` 수정 금지
* `agent/TaskPipeline.kt` 수정 금지 (공통 파이프라인)
* 타 기능의 Agent / Prompt 수정 금지
* 구조 변경 금지

---

## 5. TaskPipeline 사용 가이드

모든 기능은 `TaskPipeline`을 통해 실행된다.

### 기본 구조

```
TaskPipeline
  └── AgentStep 1 (예: 분석)
  └── AgentStep 2 (예: 코드 수정)
```

### 단일 스텝 기능 예시

```kotlin
// Explain, Review, Query Validation 등 단순 기능
val step = AgentStep(
    label = "explain",
    systemPrompt = loadPrompt("explain_prompt.txt"),
    userMessage = selectedCode
)
pipeline.run(listOf(step))
```

### 2단계 스텝 기능 예시

```kotlin
// Impact Analysis, Improve 등 분석 → 처리 구조
val analysisStep = AgentStep(
    label = "analysis",
    systemPrompt = loadPrompt("impact_analysis_prompt.txt"),
    userMessage = selectedCode
)
val resultStep = AgentStep(
    label = "impact",
    systemPrompt = loadPrompt("impact_prompt.txt"),
    userMessage = buildUserMessage(selectedCode, analysisResult)
)
pipeline.run(listOf(analysisStep, resultStep))
```

---

## 6. Prompt 작성 규칙

* 파일 위치: `src/main/resources/prompt/`
* 파일명 규칙: `{기능명}_prompt.txt`, `{기능명}_analysis_prompt.txt`
* 코드 내 하드코딩 금지
* 기능 간 Prompt 공유 금지

### Prompt 기본 구조

```
[역할 정의]
너는 Android/Kotlin 코드 전문가이다.

[지시]
아래 코드를 분석하고 ...

[출력 형식]
반드시 다음 형식으로만 출력하라:
...

[금지]
코드 외 텍스트 출력 금지
```

---

## 7. 작업 흐름

```
feature 브랜치 생성
→ 기능 개발
→ runIde 테스트
→ 안정화 완료
→ 커밋
→ develop 브랜치로 푸시
```

---

## 8. 테스트 기준

### 테스트 단계

* `runIde`로 기능 테스트 → 빠르게 반복 개발
* `buildPlugin` → zip 생성 → IDE 설치 후 최종 검증

### 파일 크기별 기준

| 파일 크기 | 기준 |
|---|---|
| ~250줄 | 정상 동작 필수 |
| ~500줄 | 정상 동작 목표 |
| 950줄 이상 | 최선 노력 (타임아웃 발생 가능) |

---

## 9. Git 작업 규칙

### 브랜치 전략

* `main` — 배포 기준 브랜치
* `develop` — 개발 통합 브랜치
* `feature/*` — 기능 작업 브랜치

### 커밋 규칙

* 기능 단위 완료 후 1회 커밋
* 중간 커밋 금지
* 디버깅 코드 커밋 금지

### 커밋 메시지 예시

```
feat: implement improve agent
fix: resolve stop cancellation issue
refactor: clean agent structure
```

### 금지 사항

* 테스트 전 커밋 금지
* 미완성 코드 푸시 금지
* `main` 브랜치 직접 푸시 금지

---

## 10. AI 도구 사용 가이드

| 도구 | 용도 |
|---|---|
| Gemini Flash | 메인 구조 작업, 대용량 코드 처리 |
| Claude Code | 기능 단위 구현, 수정 작업 |
| Codex | 기능 단위 구현 보조 |

> AI 도구 사용 시 `AI_DEVELOPMENT_RULES.md`의 역할 제약을 반드시 준수한다.

---

## 11. 현재 기능별 이슈 현황

### Improve This Code (이정준 — 대응 중)

**문제**: 복잡한 파일(950줄+)에서 전체 코드가 아닌 일부만 반환됨

**원인**:
- Context Window 부족 (4096 → 32768로 조정)
- LLM attention이 분석 결과의 특정 함수에 집중

**현재 대응**:
- Search/Replace 방식 전환 완료
- Context Window 32768 적용
- Step2 컨텍스트 최적화 진행 중

**다른 기능 영향 없음**

---

## 핵심 원칙

구조를 유지하면서 기능을 확장한다.
커밋은 결과물이다, 과정이 아니다.
