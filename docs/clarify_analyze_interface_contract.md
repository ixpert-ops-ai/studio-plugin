# Clarify $\rightarrow$ Analyze Interface Contract Specification (v1.1)

---

## 1. 핵심 설계 철학 및 3대 불변 원칙

### 원칙 1: 완전한 실행 독립성 (Execution Independence)
- **`/clarify` (Stage 0)**와 **`/analyze` (Stage 1~3)**는 완전히 독립된 서브시스템이다.
- 사용자는 `/clarify` 없이 언제든지 `/analyze <요구사항>`을 단독으로 실행할 수 있어야 하며, 시스템은 어떠한 사전 계약(Contract/Intent) 없이도 메타그래프 기반 자립 탐색을 100% 완주해야 한다.
- **AnalyzeInputResolver 독립성 규칙**:
  ```kotlin
  val resolved = AnalyzeInputResolver.resolve(
      rawInput = userInput,
      inMemoryIntent = null // 단독 실행 시 디스크 Stale Intent 0건 주입
  )
  ```

---

### 원칙 2: 단방향 소프트 힌트 및 배제 계약 (Soft Constraint & Hard Exclusion)
- `ClarifyIntent`는 Analyze의 객관적 탐색 알고리즘(Discovery $\rightarrow$ Trimming $\rightarrow$ Verification)을 **대체하거나 강제하지 않는다.**
- 각 필드의 역할과 소비 경계는 엄격히 제한된다:
  1. **`refinedRequirement`**: Analyze의 초기 시드 탐색(Seed Selection)을 위한 정제된 텍스트 입력으로만 제공된다.
  2. **`excludedFiles`**: 대화 과정에서 명시적으로 제외된 파일들을 Stage 2(Trimming) 진입 직전에 단칼에 제거하는 **1급 배제 필터**로 작동한다.
  3. **`constraints` & `anchorTokens`**: 자연어 정제문 내에 녹아들어 LLM 탐색의 맥락 힌트로만 작용하며, 그래프 탐색 엣지를 임의로 조작하지 않는다.

---

### 원칙 3: 과거 세션 유령 오염 원천 차단 (ADR-001 Zero Ghost Contamination)
- 세션 간 거절 정보(`previousContract` 디스크 로드) 승계를 전면 폐지하고, 신규 세션은 항상 `previousContract = null`로 완전히 격리한다.
- 과거 세션의 잔존 파일로 인한 precision/recall 왜곡 및 유령 오염(Ghost Contamination)을 원천 차단한다.
- `src/main` 전역에서 디스크의 intent/contract 자동 조회 메서드(`findIntentFile`, `findContractFile`, `loadIntentByKey`, `loadContractByKey`) 호출을 0건으로 통제한다.

---

## 2. 계약 데이터 모델 (`ClarifyIntent` v1.1)

```kotlin
data class ClarifyIntent(
    // 최초 원문 (불변 기록/추적용)
    val originalRequirement: String,

    // 대화로 정제된 최종 요구사항 (Analyze 탐색 방향타)
    val refinedRequirement: String,

    // 사용자 추가 발화 원문 목록 (Analyze는 refinedRequirement를 소비하고, userStatements는 무손실 원본 기록 및 후속 Implement 단계용)
    val userStatements: List<String> = emptyList(),

    // 대화 중 검증/확정된 핵심 앵커 토큰 (Analyze Seed 우선 부여용)
    val anchorTokens: List<String> = emptyList(),

    // 대화에서 추출된 구조화 제약 조건 (오탐 사전 차단용)
    val constraints: List<IntentConstraint> = emptyList(),

    // 대화 중 명시적으로 배제된 파일 경로 목록 (1급 결정 이양용)
    val excludedFiles: List<String> = emptyList(),

    // 결정론 앵커 (그래프 정합성 검증)
    val graphHash: String,

    // 식별자 보존 검사 및 안전판 보정 감사 기록 (지표 왜곡 방지 및 품질 모니터링용)
    val retentionAudit: RetentionAudit? = null,

    // 계약 버전
    val contractVersion: String = "1.1"
)

data class RetentionAudit(
    val rawRefinedRequirement: String,
    val expectedIdentifiers: List<String> = emptyList(),
    val missingBeforeFix: List<String> = emptyList(),
    val auxiliaryIdentifiers: List<String> = emptyList(), // 그래프 클래스명 구성단어와 일치하는 보조 식별자 (정제문 강제병기 없이 기록/모니터링)
    val wasRetainedWithoutModification: Boolean = true,
    val scopeModifierDropped: Boolean = false // '만' 등 범위 한정 조사가 LLM 정제문에서 누락되었는지 감사 기록
)

data class IntentConstraint(
    val kind: ConstraintKind = ConstraintKind.OTHER,
    val value: String,
    val rawStatement: String? = null,
    val evidence: String? = null // 발화 원문 내 직접적 근거 구문 (환각 검증 및 100% 사실 기반성 보장용)
)

enum class ConstraintKind {
    INCLUDE_CHANNEL,    // 특정 채널/경로 포함 (예: 알림톡 채널)
    EXCLUDE_COMPONENT,  // 특정 컴포넌트/배치/기능 배제 (예: 알림톡 배치 수정 제외)
    EXCLUDE_EXTERNAL,   // 외부 연동 배제 (예: 외부 API 미사용)
    NEW_MODULE,         // 신규 모듈 생성 필요 (사용자가 신규 개발/생성을 명시한 경우만)
    SCOPE_LIMIT,        // 범위 한정 (예: 특정 파일/클래스만 수정)
    OTHER               // 분류 불능 자유 제약 (안전판 폴백)
}
```

---

## 3. 필드별 역할 및 소비 규칙

| 필드명 | 타입 | 생산자 (Clarify) | 소비자 (Analyze) | 소비자 (Implement) |
|---|---|---|---|---|
| `originalRequirement` | `String` | 사용자 최초 입력 | 추적 및 로그용 | 요구사항 최초 원문 참조 |
| `refinedRequirement` | `String` | LLM 정제문 + `IdentifierRetentionChecker` 보정 | **핵심 입력 (1차 탐색 질의)** | 최종 요건 명세 |
| `userStatements` | `List<String>` | 사용자 순수 발화 누적 (assistant 제외) | 비소비 (무손실 보관) | **세부 구현 지침 및 맥락 참조** |
| `anchorTokens` | `List<String>` | 시드 토큰 목록 | Top-N 시드 매칭 보조 | - |
| `constraints` | `List<IntentConstraint>` | 제약 분류기 (`extractConstraints`) | `SCOPE_LIMIT` (약한 편향) | 상세 비기능 제약 |
| `excludedFiles` | `List<String>` | 대화 중 REJECTED 확정된 파일 경로 | **Stage 2 Trimming 100% 원천 배제** | 변경 대상 제외 |
| `retentionAudit` | `RetentionAudit?` | 식별자 보존 검사기 (`IdentifierRetentionChecker`) | 품질 로깅 및 실측 지표 산출용 | - |
| `graphHash` | `String` | 정본화 메타그래프 SHA-256 | 세션 정합성 검증 | - |

---

## 4. 실증 사례 및 벤치마크 기준선

| 벤치마크 케이스 | 전체 노드 | Stage 0 회수 | **Stage 1 최종 선정 (Top-N)** | 판정 상태 | 비고 |
| :--- | :---: | :---: | :---: | :---: | :--- |
| **survey_admin (Case B)** | 132개 | **8/8 (100%)** | **6/8 (75%)** | **합격 (회귀 0건)** | `SurveyServiceImpl`(75점 1위), JSP/JS/Mapper 전원 상위 안착 |
| **ISM (Care Member - Core)** | 4,396개 | **1/4 (25%)** | **4/4 (100%)** | **합격 (0% → 100% 복원)** | `Controller`(69점), `ServiceImpl`(79점), `Mapper`(40점), `XML`(20점) **전원 생존** |
| **apc (Transit Card)** | 2,670개 | **2/5 (40%)** | **5/5 (100%)** | **합격 (0% → 100% 복원)** | `InfSVO`(105점), `BIZ`(82점), `SVC`(90점), `Impl`(110점), `DEM`(100점) **전원 생존** |
| **apc (Samsung Pay OTC)** | 2,670개 | **1/2 (50%)** | **1/2 (50%)** | **합격 (상위권 유지)** | `OfflOtcIsBIZ`(99점 3위) |
| **ISM (Point Usage - HeldOut)** | 4,396개 | 2/4 (LOW) | 0/4 (0%) | **판정 제외 (백로그 P3)** | 압축 약어(`PDsbUse`) 직접 대화 시드 매핑 대상 |
