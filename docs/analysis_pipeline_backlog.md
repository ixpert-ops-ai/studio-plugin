# Analyze 파이프라인 백로그 및 미해결 과제 종합 명세서 (Backlog Specification)

---

## 1. 백로그 과제 요약 매트릭스

| ID | 우선순위 | 과제명 | 대상 영역 | 핵심 문제 및 해결 방향 |
| :---: | :---: | :--- | :---: | :--- |
| **B-01** | **P1 (완료)** | **APC Anyframe 클래스명 정규식 일치화 및 AP 체인 계약 쌍 보장** | `apc` / 공통 | 1) `KeywordDecomposer` 토크나이징 일치화<br>2) `RelevanceScorer` AP 체인(`SVC ↔ SVCImpl ↔ BIZ ↔ DEM/DQM`) Contract Pair Tier 2 승급 적용 (룰베이스 5/5, 라이브 5/5 검증 완료) |
| **B-02** | **P1 (진행중)** | **Clarify 1단계 무손실 인계 및 프롬프트/제약 구조화 보강** | `clarify` | 1) `IdentifierRetentionChecker` 순수 형태 규칙 및 `.java` 동치 보정<br>2) 보조 식별자(`auxiliaryIdentifiers`) camelCase 단어 분절 기록<br>3) `refineRequirement` "만" 한정어 보존 및 `extractConstraints` `EXCLUDE_COMPONENT` 분리 (환각 0건 가드) |
| **B-03** | **P2** | **Stage 0 라운드로빈 쿼터 내 Controller 계층 독립 분리** | `Stage0GraphScanner` | `businessQueue`에 Controller와 Service가 동거하여 `proposalBudget=10` 제한 시 Controller가 Top-10 시드에서 탈락하는 현상 개선 |
| **B-04** | **P2** | **룰 폴백 개편 (Map 순서 의존성, Exact Match 미반영, LLM 장애 대응)** | `RelevanceScorer` / `RuleFallback` | 1) `graph.files` 순회 순서에 따른 비결정론 제거<br>2) Exact Match 토큰 가산점 미반영 해소<br>3) LLM 장애 폴백 시 ISM/APC 0점 탈락 결함 방어 |
| **B-05** | **P2** | **프론트엔드 55점 컷 바이패스로 인한 Precision 누수 방어** | `Stage2Trimmer` | JSP/JS 연동 파일이 55점 미만임에도 무조건 생존하여 타 도메인 뷰가 최종 후보군에 혼입되는 오탐 차단 |
| **B-06** | **P3** | **Stage 1 레벨 어휘 부분문자열 오탐 방지 (`add` $\rightarrow$ `Address*`)** | `KeywordDecomposer` / `Stage1` | `add` 검색 시 `Address*`가 부분 문자열로 과매칭되는 현상을 단어 경계(`\b`) 단위로 엄격화 |
| **B-07** | **P3** | **초압축 약어(`PDsbUse`) 대화형 시드 매핑 및 survey_admin GT 정제** | `ISM` / `survey_admin` | 1) `PDsbUse`(Point Disburse Use) 대화 기반 시드 매핑 규칙<br>2) `survey_admin` Case B 알림톡 배치 제외 요건 시 `AlimtalkChnlDto/TmplDto` GT 적합성 재검토 |
| **B-08** | **P3** | **survey_admin 통합 GT 누락 해소 (`BrandmessageTemplateBatchJob`, `BizgoApiServiceImpl`)** | `survey_admin` | 메타그래프 텍스트-only 판별 사각지대(나 유형)로 인한 GT 2건 누락 해소 (본문/관계 기반 판별 연계) |
| **B-09** | **P3** | **하네스 GT 통일, Hold-out 케이스 추가, 프리플라이트 및 무효 행 자동 표시** | 테스트 인프라 | 1) 룰 하네스와 LLM 하네스의 GT 산식 일치화<br>2) 신규 홀드아웃 케이스 확보<br>3) 서버 다운 시 무효 행 자동 마킹 |
| **B-10** | **P4** | **Stage 0 관계 전파 비공명 감쇠율(decay) 튜닝** | `Stage0GraphScanner` | commit `4bf46f9` (`koreanStopwords`) 이후 단독 localName 매칭 노드의 1-hop INJECTS 전파($2.0 \times 0.40 = 0.80 < 1.0$) 감쇠율을 스냅샷 게이트 회귀 없이 정밀 조정 |
| **B-11** | **P4** | **정제문(Refined Requirement) 적용 후 FP(오탐) 증가율 추적 및 통제** | `Analyze` 공통 | 정제문이 구체화되면서 파생 어휘로 인해 후보군이 과도하게 팽창하지 않도록 점수 임계치 및 Trimming 모니터링 |
| **B-12** | **P1 (완료)** | **사용자 명시 식별자 3분기 분해 및 PENDING 중복 재질의 방지** | `Stage0ClarificationEngine` | 1) `resolveUserUtteredIdentifiers` 3분기(실재노드 $\rightarrow$ CONFIRMED, 부존재 $\rightarrow$ PENDING/질의, 미언급 $\rightarrow$ PENDING) 구조화<br>2) 키워드 하드코딩 제거 및 `NEW_MODULE` evidence 경계 매칭<br>3) 2턴 Live 6회 측정 재질의 0건 및 3대 스냅샷 게이트(6/8, 4/4, 5/5) 무손실 보존<br>4) 종료 커밋: `25c1444` |
| **B-17** | **P4** | **PipelineE2ETest HTTP 클라이언트 타임아웃 및 가드 조사** | `PipelineE2ETest` | 1) `PipelineE2ETest.xml`은 `build2/` 잔존 산출물(08-31)로 이번 실행과 무관<br>2) `PipelineE2ETest.kt`의 `PipelineE2ETestVllmClient`에 `connectTimeout`/`readTimeout` 미설정 확인<br>3) 전체 `./gradlew test` 실행 시 블로킹 원인은 미확인(원인 조사 대상) |
| **B-18** | **P3** | **배제 질문에 밀린 미등록 식별자 질문의 후속 턴 재질의 누락 방지** | `Stage0ClarificationEngine` | 같은 턴에 배제 질문 우선 채택으로 밀려난 미등록 질문이 이후 턴에 다시 나가지 않는 현상 방지 및 큐잉 정책 수립 |
| **B-19** | **P1 (완료)** | **배제 응답 판정 시 부분 문자열 매칭 오판정 수정 및 evidence 폴백 제거** | `Stage0ClarificationEngine` | 1) `contains("y")`, `contains("n")` 부분 문자열 매칭으로 인한 식별자 발화 오판정 버그(원인 커밋 `42c6f7c`) 해소<br>2) 공백/부호 제거 후 독립 토큰 완전 일치 판정 적용<br>3) `ec.evidence ?: stmt` 폴백을 `ec.evidence ?: continue`로 엄격화<br>4) 종료 커밋: `648cbc0` |
| **B-20** | **P1 (완료)** | **인텐트 계약 미확정 발화 식별자(unresolvedItems) 구조화 및 1:1 분해** | `ClarifyIntent` / `Stage0ClarificationEngine` | 1) `unresolvedItems` v1.2 필드 도입 및 DTO 역직렬화 무결성/버전 가드(`1.0~1.2` 지원, `1.9` 거부, 필수 필드 누락 검증) 구현<br>2) 미확인 식별자 1:1 분해(`deriveId(NewCreation, token)`) 및 확정 시 동일 id 대체/제거<br>3) 최초 발화 턴(`utteredTurn`) 불변성 및 실제 채택 질문 이력(`lastQuestion`, `lastAskedTurn`) 추적<br>4) 종료 커밋: `82fc879` (커밋 메시지의 `(B-18)` 태그는 백로그 정정에 따라 B-20으로 정정됨) |
| **B-21** | **P2** | **anchorTokens 및 seedSet 대화 토큰 누수 격리 및 세 위치 불변식 단언 강화** | `Stage0ClarificationEngine` / `ClarifyIntent` | 1) 대화성 발화 토큰(`"fooservice"`, `"건드리지"`, `"아니요"` 등)이 `state.seedSet` 및 `ClarifyIntent.anchorTokens`에 무차별 혼입되는 누수 격리<br>2) 인텐트 계약 세 위치(`confirmed` + `excluded` + `unresolved`)의 상호 배타성 및 합 1 보존 불변식 엄격화 |
| **B-22** | **P2** | **Anyframe 식별자 대소문자 불일치 탐색 실패 개선 (`aCMBTBAPC024DEM` vs `ACMBTBAPC024DEM`)** | `Discovery` / `Anyframe` | 요건에 소문자 접두사(`aCMB*`)로 명시된 Anyframe DEM 식별자가 메타그래프 실제 노드(`ACMB*`)와 대소문자 불일치로 매핑 누락되는 결함 개선 |
| **B-23** | **P3** | **NewFileDetector LLM Fallback의 타 프로젝트 패키지(`com/membermarket`) 고정 경로 하드코딩 해소** | `NewFileDetector` | LLM 응답 실패/null 시 발동하는 휴리스틱 Fallback이 프로젝트 패키지 구조와 무관하게 `com/membermarket` 경로로 고정 생성하는 결함 개선 (`NewFileDetector.kt:28-75`) |
| **B-24** | **P3** | **하네스 Top-30 표기 규약, Stage 3 Fallback 프롬프트 검증 격리, srKey 연속성 범위 규정** | `Harness` / `Verification` | 1) Top-30의 `#N`은 스코어 순위가 아닌 제시 순서(presentation order) 규정<br>2) Stage 3 Mock null 시 후보 전원 보존 Fallback으로 작동하므로 프롬프트 주입 검증은 단위 테스트로 격리 단언<br>3) `srKeyHex` 연속성 영향 범위는 태그 부착 인텐트로 한정 |


---

## 2. 세부 과제별 명세

### [B-01] APC Anyframe 클래스명 정규식 토크나이저 수정 및 AP 체인 계약 쌍 보장
- **해결 내역**:
  - `KeywordDecomposer`의 정규식을 `[가-힣]+|[a-zA-Z0-9]+`로 Stage 1과 일치시켜 `aCMBTBAPC024DEM` 토큰 분열 결함 해소.
  - `Stage0GraphScanner`에 Anyframe `DATA_ACCESS` 레이어 및 Exact Match 앵커 점수(20.0점) 적용.
  - `RelevanceScorer`에 Anyframe AP 서비스 체인(`SVC ↔ SVCImpl ↔ BIZ ↔ DEM/DQM`) 계약 쌍 보호(Tier 2) 확장.
- **실측 검증 완료**: 룰베이스 5/5, 라이브 LLM 5/5 완주.

---

### [B-02] Clarify 1단계 무손실 인계 및 실측 발견 손실 4건 대응
- **실측 발견 손실 4건**:
  1. **시나리오 1 ("만" 유실)**: `"SurveyServiceImpl만 수정"` $\rightarrow$ 정제문에서 `"만"` 누락. Analyze는 `refinedRequirement`만 소비하므로 범위 한정이 Analyze에 도달하지 못함.
  2. **시나리오 2 (배제 비구조화)**: `"알림톡 배치 제외"`가 `SCOPE_LIMIT` 문자열 속에 뭉개져 `excludedFiles` 0건 도출.
  3. **시나리오 3 (단일 단어 `Bizgo` 미보존)**: 단일 대문자 단어라 1차 형태 규칙에 미포함 $\rightarrow$ `auxiliaryIdentifiers`로 camelCase 분절 단어 기록하여 안전판 보완.
  4. **시나리오 3 (`NEW_MODULE` 환각)**: 발화에 없는 "모듈 개발" 생성 $\rightarrow$ 발화 근거 없는 분류 0건 가드 및 기존 `BizgoApiServiceImpl` 실재 파일 오인 방지.
- **해결 방향**:
  - `refineRequirement` 프롬프트에 한정어("만", "단독") 보존 가드 추가.
  - `extractConstraints`에 `EXCLUDE_COMPONENT` 추가 및 발화 근거 없는 분류 0건 검증.
  - `IdentifierRetentionChecker`에 `SCOPE_LIMIT` 감지 시 한정어 누락 audit 기록 기능 연계.

---

### [B-03] Stage 0 라운드로빈 쿼터 내 Controller 계층 독립 분리
- **현상**: `Stage0GraphScanner.rescanUnverified`의 균등 선발 큐가 `View -> Script -> Business -> DTO -> DataAccess`로 구성되어 `Controller`가 `businessQueue`에 들어감.
- **영향**: `proposalBudget = 10` 제한 하에서 Service가 다수 선발되면 Controller가 3순위로 밀려 Top-10 시드에서 탈락.
- **해결 방안**: `presentationQueue`(Controller/RestController)를 별도 쿼터로 독립.

---

### [B-04] 룰 폴백 개편 (Deterministic Fallback)
- **현상**: LLM 호출 실패/타임아웃 시 발동하는 룰 기반 폴백이 `graph.files` 맵의 내부 해시 순서에 의존하고, 정확한 클래스명 일치(Exact Match) 가중치를 충분히 반영하지 못하여 ISM/APC에서 점수 0점 탈락 발생.
- **해결 방안**: 결정론적 정렬(Alphabetical tie-breaking) 및 Exact Match 최우선 가산점(Tier 1)을 폴백 스코어러에 명시적 이식.

---

### [B-05] 프론트엔드 55점 컷 바이패스로 인한 Precision 누수 방어
- **현상**: View/Script 파일들이 `frontendRelevant` 플래그에 의해 55점 커트라인을 무조건 우회(Vetted Bypass)하여 타 도메인의 무관한 JSP/JS가 최종 목록에 잔류.
- **해결 방안**: URL 바인딩 및 Seed 노드와의 1-hop 연관성이 증명된 프론트엔드 파일만 선별 바이패스하도록 조건 강화.

---

### [B-06] Stage 1 어휘 부분문자열 오탐 방지 (`add` $\rightarrow$ `Address*`)
- **현상**: `add` 요구사항 검색 시 `AddressDao`, `AddressDto` 등 접두 부분문자열이 일치하는 파일들이 가산점을 받는 현상.
- **해결 방안**: camelCase 분절 및 단어 경계(`\b`) 일치 시에만 도메인 어휘 매칭 인정.

---

### [B-07] 초압축 약어(`PDsbUse`) 대화형 시드 매핑 및 survey_admin GT 정제
- **과제 A**: `PDsbUse`(Point Disburse Use)처럼 3중 압축된 약어를 대화(Clarify)를 통해 시드로 매핑.
- **과제 B**: survey_admin 알림톡 제외 요건 시 알림톡 DTO가 GT에 남아 recall 산식에 왜곡을 주지 않는지 재검토.

---

### [B-08] survey_admin 통합 GT 누락 해소 (`BrandmessageTemplateBatchJob`, `BizgoApiServiceImpl`)
- **현상**: 본문/관계 기반으로만 식별 가능한 2개 파일이 현재 텍스트 메타그래프 탐색에서 누락.
- **해결 방안**: Case B GT 판정 기준(나 유형)을 기반으로 그래프 엣지 탐색 및 본문 인덱싱 연계.

---

### [B-09] 테스트 하네스 표준화 및 신뢰성 강화
- 룰 하네스와 라이브 LLM 하네스의 GT 산식을 동일화하고, 외부 vLLM 서버 장애 시 무효(INVALID) 행으로 명시 기록되도록 프리플라이트 자동화.

---

### [B-10] Stage 0 관계 전파 비공명 감쇠율(decay) 튜닝
- **현상**: `Stage0GraphScannerTest.testInvariant_LocalNameMatchingAndCrossValidationPromotion`에서 단독 localName 매칭(score 2.0) 노드가 INJECTS 대상 `OrderDao`로 전파 시 $2.0 \times 0.40 = 0.80 < 1.0$으로 탈락.
- **해결 방안**: APC 5/5, ISM 4/4, survey_admin 6/8 회귀 영향도를 검증하며 INJECTS 직접 관계의 전파 가중치 정밀 튜닝.

---

### [B-11] 정제문 적용 후 FP(오탐) 증가율 추적 및 통제
- 정제문(`refinedRequirement`)이 길어지고 구체화됨에 따라 파생된 키워드로 인해 1차 검색 후보군이 과도하게 증가하는 정밀도 누수 모니터링 및 상한 제어.

---

### [B-12] 사용자가 이미 명시한 식별자의 PENDING 재질의 방지 및 3분기 분해 처리
- **현상**: 사용자가 초기 발화나 추가 발화에서 명시적으로 언급한 식별자(예: `SurveyServiceImpl`)가 Stage 0 스캐너의 초기 후보 목록에 PENDING 상태로 중복 등록되어, 웹뷰 UI상 액션 버튼이 달린 PENDING 카드로 노출됨으로써 불필요한 재질의가 발생하던 결함.
- **수정 전 Baseline (`17d06d1`) 실측 분석**:
  - **시나리오 1**: `SurveyServiceImpl`이 `CONFIRMED`로 승격된 후에도 스캐너의 `rescanUnverified`에서 PENDING 카드로 중복 잔존하여 2턴에 걸쳐 재확인을 유도함.
  - **시나리오 4**: `ACMBTBAPC024DEM`이 PENDING 카드로 중복 잔존하고, 부존재 식별자인 `SAPACMM0802S01`에 대한 존재 확인 질문이 누락됨.
- **해결 방안 및 구현 원칙**:
  1. **사용자 발화 식별자 3분기 분해 (`resolveUserUtteredIdentifiers`)**:
     - **분기 1 (발화 O + 그래프 실재 O)**: `EXCLUDE_COMPONENT`의 evidence에 포함되지 않은 실재 파일은 즉시 `CONFIRMED` (`source = USER_UTTERED`)로 확정하고 PENDING 중복 원천 배제.
     - **분기 2 (발화 O + 그래프 실재 X)**: `NEW_MODULE` 제약의 `evidence`에 포함된 식별자만 명시적 신규 생성(`CONFIRMED`, `source = USER_UTTERED`)으로 승격하고, 단순 참고/미확인 식별자는 `PENDING` (`source = USER_UTTERED`) 유지 및 1회 확인 질문(`openQuestion`) 생성.
     - **분기 3 (발화 X + 인접 노드)**: 사용자가 명시하지 않은 인터페이스/구현체 등 인접 노드는 자동 확정 없이 `PENDING` (`source = SYSTEM_UNCONFIRMED`) 유지.
  2. **키워드 하드코딩 제거**:
     - `text.contains("신규")`, `text.contains("새로")` 등의 문자열 검사를 제거하고, LLM이 추출한 `NEW_MODULE` 제약의 `evidence` 경계 매칭(`IdentifierRetentionChecker.containsIdentifier`)으로만 판정.
  3. **LLM 부재(`llmClient == null`) 시 배제 문맥 보호 정책**:
     - **`B-12 재질의 방지는 LLM이 켜져 있을 때만 보장됨`**: LLM이 꺼져 있거나 제약 추출이 불가능한 환경에서는 배제 문맥("~는 건드리지 마라")을 안전하게 판별할 수 없으므로, 발화 속 식별자를 무분별하게 자동 `CONFIRMED`하지 않고 `Verdict.PENDING` (`source = HintSource.USER_UTTERED`)으로 안전하게 보존함 (`testFallback_NoLlm_IdentifiersRemainPendingWithoutAutoConfirmed` 검증 완료).
- **검증 완료**:
  - **기준선 대비 검증**: Baseline `17d06d1` 대비 식별자 PENDING 중복 재질의 0건 달성 및 `7f112e3` 이후 LLM/룰 폴백 2분기 정밀 검증.
  - **3대 스냅샷 게이트**: survey_admin Recall 6/8, ISM Recall 4/4, APC Recall 5/5 무손실 보존.
  - **2턴 Live LLM 6회 실측**: 시나리오 1 & 4 전 회차에서 1턴 식별자 재질의 0건 및 2턴 후 `SAPACMM0802S01` `PENDING` 무손실 보존 확인.
- **한계 및 후속 과제**:
  > 한계: LLM이 꺼진 상태에서는 사용자가 말한 신규 모듈이 `newCreations`로 넘어가지 않고 PENDING으로 남음. `ClarifyIntent`에 미해결 항목 필드가 없어 구조화된 인계가 안 됨 (다음 과제)

---

### [B-13] 불용어·조사 목록 중복 정리
- **현상**: `resolveExclusionCandidates` 안에 정의된 불용어/조사 목록이 Stage 0의 `koreanStopwords`/토크나이저와 중복됨.
- **해결 방안**: 기존 공용 토크나이저 및 형태소 정제 유틸과 일원화.

---

### [B-14] 부분 매칭 번역의 신뢰도 구분
- **현상**: `DomainDictionary.translate`가 부분 매칭으로 번역을 반환할 때 이를 완전 번역(Case A)과 동일하게 취급하면 과도한 매칭 발생 가능.
- **해결 방안**: 부분 매칭 번역 결과는 낮은 신뢰도로 취급하여 Case B(항목별 세부 선택)로 유도.

---

### [B-15] 확인 질문의 파일명 표기 정밀화
- **현상**: 배제 확인 질문 생성 시 `.java`가 하드코딩되어 있어 JSP, XML, JS 등 리소스 파일 후보 발생 시 부정확한 파일명이 표기될 수 있음.
- **해결 방안**: 실제 노드 확장자 및 경로 기반의 동적 표기로 개선.

---

### [B-16] Repository 등 보조 컴포넌트의 배제 범위 정책
- **현상**: `AlimtalkTemplateBatchRepository`는 알림톡 배치 묶음에 속하지만, 주석에 "알림톡" 키워드가 없어 후보에서 누락됨 (이전 3건은 하드코딩 버전의 결과였고, 일반화 뒤 2건이 됨).
- **해결 방안**: 본문/관계 기반 연계 대상(`Repository`)의 2차 배제 확장 규칙 설계.

---

### [B-17] PipelineE2ETest HTTP 클라이언트 타임아웃 및 가드 조사
- **확인된 사실**:
  1. `PipelineE2ETest.xml`: `build2/` 잔존 산출물(`2026-08-31 10:34:54`)이며 이번 실행과 무관. (`<testsuite name="net.ib.ixpert.ops.wuwagent.agent.PipelineE2ETest" tests="0" skipped="0" failures="0" errors="0" timestamp="1970-01-01T00:00:00Z" hostname="DESKTOP-93QIMCP" time="0.0">`)
  2. `PipelineE2ETest.kt`의 `PipelineE2ETestVllmClient`에서 `HttpURLConnection` 연결 시 `connectTimeout` 및 `readTimeout` 미설정 확인.
  3. 전체 `./gradlew test` 실행 시 블로킹/지연이 발생한 구체적 원인은 미확인(원인 조사 대상).

---

### [B-18] 배제 질문에 밀린 미등록 식별자 질문의 후속 턴 재질의 누락 방지
- **현상**: 같은 턴에 배제 확인 질문(`exclusionQuestion`)과 미확인 식별자 질문(`unmatchedQuestion`)이 동시에 발생할 경우, 배제 질문이 우선 채택되어 사용자에게 제시됨. 이로 인해 밀려난 미확인 식별자 질문이 이후 턴에서 다시 제시되지 않고 누락되는 현상 발생 가능.
- **해결 방안**: 보류된 미확인 식별자 질문의 후속 턴 재질의(Re-ask) 스케줄링 및 큐잉 정책 수립.

---

### [B-19] 배제 응답 판정 시 부분 문자열 매칭 오판정 수정 및 evidence 폴백 제거
- **현상**: 배제 제안 후 사용자가 `"SurveyServiceImpl도 같이 봐야 해"` 등 `"y"`/`"n"`이 포함된 일반 발화를 했을 때, `contains("y")` 부분 문자열 매칭으로 인해 긍정(Affirmative)으로 오판정되어 배제 후보가 `REJECTED`로 일괄 확정되는 결함 (원인 커밋: `42c6f7c`).
- **해결 내역** (커밋 `648cbc0`):
  1. 공백 및 특수문자를 제거한 정규화 토큰(`cleanStmt`)과 독립 응답 키워드 집합 간의 완전 일치 판정(`cleanStmt in affirmativeTokens`) 적용.
  2. `ec.evidence ?: stmt` null 대체값 패턴을 제거하고 `val evidence = ec.evidence ?: continue`로 엄격화.
- **검증 완료**: `testExclusionResponse_NotTriggeredByPartialSubstringLikeSurvey` 단위 테스트 검증 완료.
- **비고**: 완전 일치 판정 방식 전환에 따라 `"네, 제외해 주세요"`와 같이 응답 키워드가 결합된 복합 발화는 현재 보수적으로 긍정 판정 대상에서 제외됨.

---

### [B-20] 인텐트 계약 미확정 발화 식별자(unresolvedItems) 구조화 및 1:1 분해
- **현상**: 대화 종료 시점까지 미확정(PENDING)으로 남은 사용자 발화 식별자가 문자열 태그(`[보존 식별자: ...]`)로만 임시 보존되어 타입 안정성 및 구조적 질의 이력 추적이 결여됨. 또한 복수 미확인 식별자가 쉼표 결합 단일 항목으로 묶이는 결함 존재.
- **해결 내역** (커밋 `82fc879`):
  1. `ClarifyIntent`에 구조화된 `unresolvedItems: List<UnresolvedItem>`(v1.2) 필드 도입 및 DTO 역직렬화 무결성/버전 가드(`1.0~1.2` 지원, `1.9` 거부, 필수 필드 누락 검증) 구현.
  2. 미확인 식별자 1:1 분해(`deriveId(NewCreation, token)`) 및 확정 시 동일 id 대체/제거 구현.
  3. 최초 발화 턴(`utteredTurn`) 불변성 및 실제 채택된 질문에 대한 이력(`lastQuestion`, `lastAskedTurn`) 추적 구현.
  4. 배제 취소("아니요") 시 사용자 발화 식별자 원복 및 파이프라인 무간섭 불변성(0% 왜곡) 검증 완료.
- **검증 완료**: `ClarifyIntentStoreTest`, `Stage0UserIdentifierDisambiguationTest`, `Stage0UtteranceProcessTest`, `Stage0RouterAndContractTest`, `RequirementAnalysisPipelineIntegrationTest` (총 5개 스위트 단위/통합 테스트 통과).
- **비고**:
  - 커밋 `82fc879`의 메시지에 기재된 `(B-18)` 태그는 백로그 항목 정정에 따라 `B-20`으로 정정하여 관리함.
  - 파이프라인 무간섭 통합 테스트는 현재 `AgenticSeedSelector` 1턴 프롬프트 녹음 및 100% 동일성을 검증함.

---

### [B-21] anchorTokens 및 seedSet 대화 토큰 누수 격리 및 세 위치 불변식 단언 강화
- **현상**: 사용자의 자연어 발화 중 소문자화된 식별자(`fooservice`) 외에 대화성 제어 토큰(`"건드리지"`, `"아니요"` 등)이 `state.seedSet` 및 `ClarifyIntent.anchorTokens`로 무차별 혼입되어 앵커 집합이 오염됨.
- **해결 방향**:
  1. `anchorTokens` 및 `seedSet` 수집 대상을 검증된 식별자/노드로 한정하고 대화성 제어 토큰 격리.
  2. `anchorTokens`를 불변식에 포함. 조건 3에서 excluded이면서 anchor=true인 모순을 제거.
- **선행 조건**: 엔진 경로(`initSession` $\rightarrow$ `processTurn` $\rightarrow$ `buildClarifyIntent`)를 거치는 GT 측정 추가.
- **사례 (APC Transit Card LlmOff, `selTrcdIsInf`)**: `extractIdentifiers`가 뽑아 `expectedIdentifiers`에 들어가지만, 사용자 발화 항목(`utteredIdentifier`)으로는 `state.items`에 없다(13건 중 `USER_UTTERED` 4건은 `APCMMTrcdIsSVC.java`, `APCMMTrcdIsInfSVO`, `aCMBTBAPC024DEM`, `SAPACMM0802S01`). `resolveUserUtteredIdentifiers`의 `unmatched` 필터(`Stage0ClarificationEngine.kt:159`)가 소문자 시작이고 `.`이 없는 토큰을 버리기 때문이다. 이 토큰은 `SYSTEM_UNCONFIRMED` 항목 4건(`APCCmnConstantUtil.java`, `APCMMOpnApiConstantUtil.java`, `APCCOCardImgUrl.java`, `ACMBTBAPC039DVO.java`)의 ExistingRef symbols에 `token:seltrcdisinf`(소문자)로만 나타나, 발화 식별자의 확정·배제·미확정 세 위치 어디에도 들어가지 않는다. 따라서 "세 위치 합 1" 불변식은 소문자 시작 단독 토큰에는 성립하지 않는다(이 불변식이 그런 토큰까지 포함하는 것이 의도인지는 미확인). 다만 Stage 3 입력에서는 사라지지 않는다: 정제문 본문에 있으면 본문에, 정제문에서 빠졌으면 빠진 식별자 섹션에 남는다(2c, B-36).
- **상태**: Open (우선순위 P2)

---

### [B-22] Anyframe 식별자 대소문자 불일치 탐색 실패 개선 (`aCMBTBAPC024DEM` vs `ACMBTBAPC024DEM`)
- **현상**: Anyframe 표준 체계에서 DEM/DQM/BIZ 클래스는 대문자(`ACMBTBAPC024DEM`)로 명명되나, 자연어 요구사항이나 개발자 발화에서 소문자 접두사(`aCMBTBAPC024DEM`)로 언급될 경우 대소문자 불일치로 인해 메타그래프 노드 매칭 및 시드 탐색에서 누락되는 사각지대 존재.
- **해결 방안**: 식별자 인덱싱 및 앵커/시드 매칭 단계에서 case-insensitive 매칭 또는 Anyframe 접두사 정규화 계층 적용.
- **상태**: Open (우선순위 P2)

---

### [B-23] NewFileDetector LLM Fallback의 타 프로젝트 패키지(`com/membermarket`) 고정 경로 하드코딩 해소
- **현상**: `NewFileDetector.kt:28-75`에서 LLM 응답 실패 또는 null 반환 시 휴리스틱 Fallback이 작동하여 신규 파일 제안(`NewFileProposal`)을 생성함. 이때 분석 대상 프로젝트의 실제 패키지/디렉토리 구조와 무관하게 `src/main/java/com/membermarket/domain/NewFeatureService.java`, `com/membermarket/api/NewFeatureController.java`, `com/membermarket/batch/NewBatchScheduler.java` 등 타 프로젝트 패키지가 고정 경로로 하드코딩되어 생성 후보군에 혼입됨.
- **관측 사실**: 엄격한 Mock LLM에서 `NewFileDetector`에 null 응답이 반환될 때, SR에 "신규", "추가", "생성" 키워드가 포함되면 분기 A 호출과 함께 `com/membermarket/...` CREATE 후보가 상위 목록에 등장함.
- **해결 방안**: Fallback 생성 시 메타그래프의 대표 패키지 프리픽스(`DomainExtractor` 산출 결과)를 기반으로 동적 경로를 조합하도록 개선.
- **상태**: Open (우선순위 P3)

---

### [B-24] 하네스 Top-30 표기 규약, Stage 3 Fallback 프롬프트 검증 격리, srKey 연속성 범위 규정
- **규약 정비 내역**:
  1. **Top-30의 `#N` 표기**: 최종 출력 목록의 `#N`은 종합 스코어 랭킹(ranking)이 아니라 파이프라인의 **제시 순서(presentation order)**임. 점수 정렬과 인덱스 표기 간의 오해 방지를 위해 하네스 출력 및 문서에 명시.
  2. **Stage 3 Mock Fallback 프롬프트 검증 격리**: Stage 3 Verifier Mock이 `null`을 반환할 경우 `FileRelevanceVerifier.kt:51`에 의해 "후보 전원 보존 Fallback"으로 작동하므로, 실 파이프라인 하네스 실행만으로는 프롬프트 주입 여부를 완벽히 증명할 수 없음. 프롬프트 주입 검증은 프롬프트 기록 단위 테스트(`Stage3PromptRecordingTest` 등)로 비침습적으로 격리 단언함.
  3. **`srKeyHex` 연속성 영향 범위**: `RequirementAnalysisPipeline.kt:314`의 `srKeyHex = String.format("SR-%08x", primaryReq.hashCode())`는 태그가 제거될 때 해시값이 변경되지만, 그 영향 범위는 **태그가 실제로 부착된 인텐트(`missingBeforeFix`가 비어 있지 않은 경우)**로 국한됨. 태그가 없는 LlmOff 등 단독 SR에서는 해시 변경이 발생하지 않음.
  4. **커밋 2a (`63af698`) Before 서술 정정**: 커밋 2a 이전에도 Router가 `clarifyIntent.refinedRequirement`를 전달하고 있었으나 `AnalyzeInputResolver.resolve` 내부에서 `refinedRequirement.ifBlank` 시 `originalRequirement`로 폴백하는 로직이 이미 존재했음. 따라서 refined가 비어 있을 때도 2a 전후의 실질 동작은 동일함. Router가 `originalRequirement`를 명시적으로 넘기도록 수정한 것은 입력 해석의 단일 책임을 Resolver에 온전히 위임하고 의도를 코드에 명확히 드러내기 위한 리팩토링임. 또한 `stage0Contract`는 `WebviewActionRouter:1513` 런타임에서 호출되지 않는 비활성(dead) 경로임이 확인됨.
  5. **커밋 2a (`63af698`) Before 보충 (운영 경로)**: 운영 경로의 `primaryReq`(`WebviewActionRouter.kt:1514`)에는 2a 전후 모두 `AnalyzeInputResolver.resolve` 출력이 들어가며 실질 동작은 같다. 2a가 바꾼 것은 `analyze()`가 `clarifyIntent`에서 정제문을 스스로 고르지 않게 된 계약이고, 이 때문에 `analyze()`를 직접 부르던 테스트가 깨졌다(B-26). 줄 번호는 `pipeline.analyze(` 호출이 `:1513`, `primaryReq` 인자가 `:1514`, `clarifyIntent` 인자가 `:1517`이다(기존 서술의 `:1513`은 호출 줄 기준으로 유효하며, `primaryReq` 인자를 가리킬 때는 `:1514`). `:1517`의 `clarifyIntent`는 `:1503`의 `resolvedInput.effectiveIntent`이고 Resolver는 `inMemoryIntent`를 복사 없이 그대로 돌려주므로(`AnalyzeInputResolver.kt:27`) 같은 객체다.
  6. **`srKeyHex` 단절 시점 정정 (커밋 2b `b397975`)**: 3번의 "태그가 제거될 때 해시값이 변경"은 커밋 4가 아니라 **커밋 2b 시점**으로 정정한다. `srKeyHex`(`RequirementAnalysisPipeline.kt:313`, `String.format("SR-%08x", primaryReq.hashCode())`; 3번의 `:314`는 현재 `:313`)는 코드를 바꾸지 않았고, 2b에서 `primaryReq`(= `ResolvedAnalyzeInput.effectiveRequirement`)가 태그 형식에서 줄 형식으로 바뀌므로 **`missingBeforeFix`가 비어 있지 않은 인텐트에 한해** 해시가 바뀐다. `missingBeforeFix`가 비어 있는 인텐트는 입력이 바이트 단위로 같아 해시가 같다(5종 중 LlmOff 3종은 불변, LlmOn 2종은 변경). 섀도 로그는 `srKey`로 묶으므로(`ShadowLogAggregator.kt:43`, `:57`) 2b 전후의 같은 SR은 다른 키가 된다. 커밋 4(태그 제거)는 `primaryReq`가 이미 `rawRefined` 기반이라 해시를 다시 바꾸지 않는다. 이 단절을 합쳐 보는 운영 절차가 있는지는 확인하지 않았다. `src/test`의 `srKey`는 고정 문자열뿐이라(예: `MemberMarketJpaRealDataTest.kt:245`) 해시 값에 의존하는 테스트는 찾지 못했다.
  7. **`stage0Contract.enrichedRequirementText`가 빈 문자열이면 Stage 3 본문이 빈 문자열이다**: `RequirementAnalysisPipeline.kt:55`는 `effectiveReq`를 정할 때 `ifBlank { null }`을 쓰지만, `:258`의 `baseRequirement`는 `ifBlank` 없이 `stage0Contract?.enrichedRequirementText ?: …`를 쓴다. 따라서 `enrichedRequirementText == ""`이면 `baseRequirement`는 빈 문자열이고 `secondaryReq`도 붙지 않는다(`?:`는 null에만 반응). 2c는 이 동작을 유지한다(본문 규칙 `enrichedRequirementText ?: …`을 그대로 따름). 운영 경로와는 무관하다: Router는 `stage0Contract`를 넘기지 않으며(`WebviewActionRouter.kt:1513-1519`) 이 값이 비어 있는 입력은 테스트 호출뿐이다. 수정 여부는 별도 결정이며 2c 테스트로 고정하지 않는다.
  8. **2d 이후 `srKeyHex` 추가 단절**: 2d는 `missingBeforeFix`에서 `excludedFiles`에 해당하는 식별자를 `effectiveRequirement`의 줄에서 뺀다(B-36). `srKeyHex`는 `primaryReq`(= `effectiveRequirement`)의 해시이므로 **제외 파일과 겹치는 식별자가 있는 인텐트에서만** 2c 대비 해시가 바뀐다(전부 차감되면 `rawRefined`의 해시). 겹침이 없으면 2c와 같다. 현재 확보한 인텐트에는 겹침이 없다.
- **상태**: Open (우선순위 P3)

---

### [B-25] 정제문(Refinement) 문구 확장으로 인한 시드 편향 및 GT 탈락 (`SurveyServiceImpl`)
- **현상**: `survey_admin` 시나리오에서 LlmOff 시 GT 2/8(`SurveyServiceImpl.java`, `sql_survey.xml`)이 생존하였으나, LlmOn 시 1/8(`sql_survey.xml`)로 하락함.
- **라벨 정의**: LlmOff의 정제문 필드(`refinedRequirement`)는 LLM 정제가 없어 내용이 원문과 같다(하네스 출력 `RefinedReq: "설문 발송 채널에 브랜드메시지 추가"`, 입력 SR `Stage0EngineRoutedBaselineHarnessTest.kt:258`과 같은 문자열). Resolver는 이 필드를 `effectiveRequirement`로 쓴다(`AnalyzeInputResolver.kt:22` `inMemoryIntent.refinedRequirement.ifBlank { ... }`). 아래에서 "LlmOff"는 이 텍스트(원문과 동일), "LlmOn"은 하네스 Mock LLM(`StrictClarifyMockLlm`)의 `refinementResponse` 픽스처 정제문 `설문 발송 채널에 브랜드메시지 추가 및 Bizgo 연동 API 신규 개발`이다. LlmOn 정제문은 **실제 LLM 출력이 아니라 테스트 픽스처 문자열**이다.
- **원인 규명 (절제 실험 `58eb590`, 블록 sha256은 B-32)**: 2×2, 각 칸은 `SurveyServiceImpl` 점수 · GT 생존 · Top-30 건수

  | 정제문 | 식별자 없음 | 식별자 줄 형식 | 식별자 태그 형식 `(명시된 식별자: …)` |
  |---|---|---|---|
  | **LlmOff** (원문과 동일) | 65점 · GT 2/8 · 12건 (`survey_admin (Intent=LlmOff)`) | 65점 · GT 2/8 · 12건 (`Ablation-a`, 식별자 5줄) | 미측정 |
  | **LlmOn** (픽스처 정제문) | 35점 · GT 1/8 · 14건 (`Ablation-b`) | 35점 · GT 1/8 · 14건 (`Simulated 2b`) | 35점 · GT 1/8 · 14건 (`survey_admin (Intent=LlmOn)`) |

  - 시드(`selectSeeds(text, graph)` 단독 호출): LlmOff와 `Ablation-a`는 `[SurveyPartExportRunner, AlimtalkTemplateBatchRepository, SurveyDaoImpl, ReviewDto, SurveyDto]`, LlmOn 3종(`Ablation-b`, `Simulated 2b`, Tagged)은 `[ApiService, ApiServiceImpl, IbCenterApiService, IbCenterApiServiceImpl, SurveyPartExportRunner]`.
  - `SurveyServiceImpl`: LlmOff 계열은 hop 1(`DEPENDED_BY`) 30+0+15+20=**65**, LlmOn 계열은 hop 3(`REPO_TO_SERVICE`) 0+0+15+20=**35**이며, 재계산 합계가 `RelevanceScorer` 반환 score와 같음을 단언으로 확인함(B-32). LlmOn 계열은 최종 목록에서 `SurveyServiceImpl.java`가 빠진다(`GT Missing`).
  - **탈락 기준(`minScore = 55`) 확인**: 파이프라인은 `RequirementAnalysisPipeline.kt:148` `AdaptiveFileDiscovery.filter(...)`를 거치고, 그 안에서 `AdaptiveFileDiscovery.kt:46` `RelevanceScorer(graph, fileLimit = limit, minScore = 55)`를 만든다. `RelevanceScorer.kt:173`은 `if (totalScore >= minScore)`로 통과시키고, 미달인 경우 `:188` `else if (protection != null)`일 때만 구제한다(`SurveyServiceImpl`은 `Protected: false`). 따라서 35점은 제외되고 65점은 통과한다. 하네스 C-12 진단의 스코어러는 탈락 노드 점수를 수집하려고 `minScore = 10`으로 따로 만든 것이며 파이프라인 값이 아니다. 이 확인은 `RelevanceScorer` 단계에 한정되고 이후 단계(Stage 2/3)의 영향은 따로 보지 않았다.
  - Top-30 목록(`Top-30 List:` 이후 줄) 비교(`harness_58eb590_run1.xml`): `Ablation-a` 대 LlmOff는 diff 없음(14줄, exit 0, sha256 `8cffa9cc0cc49f5b704b262cce23027ba53b497d903de422541ffa87faa93fac`), `Ablation-b` 대 LlmOn(Tagged)은 diff 없음(16줄, exit 0, sha256 `4b64b7b86290bea7ba405a7dd15927b47a1244fef2b1458cd09fbf4d0bc0d369`). LlmOn의 줄 형식(`Simulated 2b`)과 태그 형식(Tagged)도 diff 없음(목록 본문 16줄, exit 0, 같은 sha256 `4b64b7b8…d369`). 경계는 Tagged가 `[GT Baseline: survey_admin (Intent=LlmOn)] Top-30 List:`(system-out 1869행) 다음 줄부터 `[MockTelemetry:` 직전(1870-1885행), `Simulated 2b`는 시작 줄 `[GT Baseline:`이 없어 `[Simulated 2b: survey_admin (Intent=LlmOn)] Top-30 List:`(2538행) 다음 줄부터 `[MockTelemetry:` 직전(2539-2554행)으로 잡았다. `Simulated 2b`의 `GT Survived: 1/8 (sql_survey.xml)`, `Top-30 TargetFiles Count: 14`.
  - **결론**: 식별자의 유무와 형식(LlmOn에서는 없음/줄/태그, LlmOff에서는 없음/줄)은 점수·GT·Top-30을 바꾸지 않았고, 결과는 정제문 텍스트와 함께 움직인다. 원인은 **LlmOn 픽스처 정제문의 문구**(`…및 Bizgo 연동 API 신규 개발`)이다.
  - **한계**: `Ablation-b`는 입력 텍스트에서만 식별자를 뺐고 LlmOn 인텐트 객체는 그대로 `analyze()`에 넘겼다. 그러나 `analyze()`가 읽는 인텐트 필드는 `excludedFiles`뿐이다(`RequirementAnalysisPipeline.kt:218`, `:426`, `git grep -n "clarifyIntent" -- src/main` 기준). `anchorTokens`는 clarify 패키지(`ClarifyIntentStore`, `Stage0ClarificationEngine`, 모델 정의) 안에서 저장·생성·정의될 때만 등장하고, 분석 단계(`agent/RequirementAnalysisPipeline.kt`, `service/metagraph/consumer/discovery/`)에서 읽는 곳은 없다. 탐색 단계(`AdaptiveFileDiscovery.filter`)에는 인텐트가 전달되지 않고 텍스트(`primaryReq`, `secondaryReq`, `enhancedRequirements`)만 간다. 따라서 "식별자 없음"은 입력 텍스트 기준이며, 인텐트 객체가 결과에 미치는 경로는 `excludedFiles` 필터뿐이다(그 값이 비어 있는지는 이번에 확인하지 않았다).
- **남은 일**:
  1. 정제문 안의 원인 토큰 분리(`Bizgo`, `API`, `신규 개발` 등 어느 토큰이 시드를 바꾸는지)
  2. LlmOff 태그 형식 미측정 (LlmOn은 줄 형식과 태그 형식의 Top-30 목록이 같음을 확인함)
  3. 두 인텐트(LlmOff, LlmOn)의 `excludedFiles` 값 확인(비어 있는지)
- **해결 방안 (후보, 미검증)**: (1) 정제 프롬프트가 기존 시스템의 맥락을 희석시키지 않도록 조정, (2) 시드 선택 단계에서 도메인 고유 명사 가중치 방어 로직 강화. 어느 쪽도 효과를 측정하지 않았다.
- **상태**: Open (우선순위 P2, 원인 범위는 정제문 텍스트로 좁혀짐, 토큰 단위 분리는 남음)

---

### [B-26] RequirementAnalysisPipeline 완주 계약 생성 시 enrichedRequirementText 불일치 (`effectiveReq` vs `clarifyIntent.refinedRequirement`)
- **현상**: 커밋 2a에서 `RequirementAnalysisPipeline.kt:55`의 `clarifyIntent?.refinedRequirement?.ifBlank { null }` fallback을 제거하고 `primaryReq`를 직접 소비하도록 변경함.
- **영향**: `RequirementAnalysisPipelineIntegrationTest:413`에서 `clarifyIntent`가 주입된 채 `primaryReq`로 `originalRequirement`("서비스 수정 요청")를 넘길 경우, 파이프라인 완주 후 생성되는 계약의 `enrichedRequirementText`(:446)가 `effectiveReq`(즉 `primaryReq`)로 기록되어, 기존 테스트의 기대값(`clarifyIntent.refinedRequirement`)과 불일치 발생.
- **해결 방향**: 계약의 `enrichedRequirementText` 기록 시 `clarifyIntent?.refinedRequirement?.ifBlank { null } ?: effectiveReq`를 참조하도록 명확히 하거나, 계약 스키마 생성 시의 정제문 처리 규칙을 일원화.
- **조사 결과**: `src/main`에서 `enrichedRequirementText`를 읽는 곳은 `RequirementAnalysisPipeline.kt:55`, `:258`뿐이며 둘 다 `stage0Contract` 파라미터를 읽는다. `stage0Contract`를 넘기는 호출자와 `loadContractByKey` 호출자는 `src/main`에 없다. 운영 경로(`WebviewActionRouter.kt:1494-1514`)는 `AnalyzeInputResolver.resolve(...).effectiveRequirement`를 `primaryReq`로 넘기므로, 테스트가 Resolver를 거치지 않고 `originalRequirement`를 직접 넘긴 것이 불일치의 원인이었음.
- **상태**: 해소 — 회귀 수정 커밋 `4ece8af`에서 해소(대상 테스트 8/8 통과, 31개 클래스 필터 기준 `b7ff09e`와 실패·skip 목록 동일). 테스트 전용 수정이며 `src/main` 변경 없음, 단언 기대값 변경 없음.

---

### [B-27] 전체 테스트 기준선 정정 (`./gradlew.bat test --offline`)
- **측정**: 전체 실행 412 testcase / 57 실패 / 9 skip (`4ece8af`, 소요 1h 7m). 이후 낡은 instrumented 클래스(B-28)를 정리하고 `--rerun`으로 재측정.
- **실측 기준선**: **389 tests / 49 failed / 9 skipped** (`a09dfb7`, 2026-10-07 16:37 실행, `./gradlew.bat test --offline --rerun`, 소요 36분). 결정적 기준선: `IntegrationPipelineTest` 제외 실패 46건, skip 9건, 목록은 `b7ff09e` 필터 실행과 동일(실패·skip 목록 diff 비어 있음). 실패 49건 중 3건은 `IntegrationPipelineTest`의 실 vLLM 서버 호출 실패이며(B-34) 실행 시점에 따라 달라질 수 있다. clarify 패키지는 118 testcase 중 실패 1(B-10 `Stage0GraphScannerTest`), skip 3(Live LLM).
- **412 → 389 차이 23건**: 소스가 삭제된 낡은 instrumented 클래스의 testcase다(필터 안 8개 클래스 12건 + 필터 밖 10개 테스트 클래스 11건, `javap`로 `@Test` 메서드 수를 세어 확인). 필터 밖 11건: `PureAnalyzeSurveyAdminTest` 1, `Analyze2V0MethodCTest` 1, `Analyze2V0PipelineTest` 1, `Analyze2V0Stage3ExpansionTest` 1, `Analyze2V0Stage3FirstTurnTest` 1, `Analyze2V0Stage3PipelineTest` 1, `Analyze2V0Stage3Turn2Test` 1, `Analyze2V0Stage3Turn3DialogueTest` 2, `Analyze2V0Stage3Turn3Test` 1, `Analyze2V0Test` 1 (`ConceptSet`, `VLLMClient`는 0).
- **결정적 기준선 46건의 예외 종류 (XML `type` 기준)**: `java.lang.Error` 32, `java.lang.AssertionError` 6, `com.intellij.testFramework.TestLoggerFactory$TestLoggerAssertionError` 5, `java.lang.IllegalAccessError` 1, `java.lang.NoClassDefFoundError` 1, `com.fasterxml.jackson.databind.exc.ValueInstantiationException` 1. 원인은 미조사.
- **상태**: 기준선 확정 (우선순위 P3, 46건의 원인 조사는 별도)

---

### [B-28] `build/instrumented/instrumentTestCode/`의 낡은 테스트 클래스 미삭제
- **현상**: 소스(`.kt`)가 삭제된 테스트의 컴파일 결과(2026-08-19자 `.class`)가 `build/instrumented/instrumentTestCode/`에 남아, 전체 테스트 실행 시 그대로 실행되어 `NoSuchMethodError` 등으로 실패함. `build/classes/kotlin/test/`에는 해당 클래스가 없음.
- **대상**: 소스 없는 외곽 클래스 20개(class 파일 44개): `PureAnalyzeMemberMarketTest`, `PureAnalyzeSurveyAdminTest`, `Analyze2IsmStageATest`, `Analyze2IsmStageLb1LlmRerunTest`, `Analyze2IsmStageLb1Test`, `Analyze2MemberMarketBaselineTest`, `Analyze2SurveyAdminBaselineTest`, `Analyze2V0MethodCTest`, `Analyze2V0PipelineTest`, `Analyze2V0Stage3ExpansionTest`, `Analyze2V0Stage3FirstTurnTest`, `Analyze2V0Stage3PipelineTest`, `Analyze2V0Stage3Turn2Test`, `Analyze2V0Stage3Turn3DialogueTest`, `Analyze2V0Stage3Turn3Test`, `Analyze2V0Test`, `Stage1And2TraceTest`, `Stage1TraceTest` 및 보조 클래스 `ConceptSet`, `VLLMClient`.
- **조치 이력**: 로컬에서는 해당 class 파일 44개와 대응 XML 8개를 `build/` 밖(임시 폴더, 상대 경로 유지)으로 이동만 하고 삭제하지 않았다. 추적되지 않는 산출물이라 저장소에는 반영되지 않는다.
- **해결 방안**: 전체 측정 전 `./gradlew clean test`로 산출물을 재생성하거나, 낡은 instrumented 클래스를 정리하는 절차를 문서화.
- **상태**: Open (우선순위 P3)

---

### [B-29] 테스트가 `fake-project/.wuwagent/`에 파일을 씀
- **현상**: 테스트 실행이 저장소 루트의 `fake-project/.wuwagent/` 아래에 산출물을 기록함. 이번 측정(2026-10-07 16:25:55)에서 바뀐 파일:
  - `fake-project/.wuwagent/shadow_logs.jsonl`
  - `fake-project/.wuwagent/contracts/clarification-contract-default.json`
- **영향**: 별도 worktree에서 실행해도 junction 등으로 `fake-project/`를 공유하면 원본이 변경되며, 실행 간 상태가 오염될 수 있음. `fake-project/`는 추적되지 않음.
- **해결 방안**: 테스트가 임시 폴더(`TemporaryFolder`)를 쓰도록 하거나 쓰기 위치를 실행별로 격리.
- **상태**: Open (우선순위 P4)

---

### [B-30] 빌드가 추적 파일 `src/main/resources/webview/index.html`을 다시 생성함
- **현상**: `buildWebview` → `copyWebviewToResources`가 추적 중인 `src/main/resources/webview/index.html`을 덮어써, 빌드나 테스트 후 `git status`에 `M`으로 나타남(worktree `b7ff09e` 실행 후 11 insertions, 11 deletions).
- **영향**: 의도하지 않은 변경이 커밋에 섞이거나, 변경 여부를 구분하기 어려움.
- **해결 방안**: 빌드 산출물의 추적 여부를 정리(추적 제외 또는 재현 가능한 빌드 결과로 고정).
- **상태**: Open (우선순위 P4)



---

### [B-31] C-12 점수 분해가 테스트 내 재계산이며 단언이 없음
- **현상**: `Stage0EngineRoutedBaselineHarnessTest.kt`의 `Score Components` 출력(`:932`)은 `RelevanceScorer` 반환 객체의 필드가 아니라 테스트가 공식을 복제해 다시 계산한 값이다. `hopScore`는 `:904-909`에 하드코딩(`0→40, 1→30, 2→15, else→0`)되어 있고, `nameMatch`, `layerAlign`, `commentMatch`도 테스트 안에서 계산한다.
- **영향**: `Actual Total Score`(`:853`, Scorer 반환값)와 재계산 합계가 같다는 단언이 없어, Scorer 공식이 바뀌어도 분해 출력이 조용히 어긋날 수 있다. `:780-935` 구간의 assert는 `assertNotNull("SurveyServiceImpl 노드 존재 확인", fileNode)` 하나뿐이다. B-25의 "65 → 35" 서술은 출력 숫자를 대조한 것이며 코드로 단언된 것이 아니다.
- **해결 방안**: `RelevanceScorer` 반환 타입에 구성 요소 필드가 있으면 그 필드를 출력하고, 없으면 `assertEquals(targetScored.score, 재계산 합계)`를 추가.
- **상태**: Open (우선순위 P3)

---

### [B-32] 바이트 비교 스크립트가 저장소 밖에 있어 기준선 비교를 재현할 수 없음
- **현상**: `13e53f7` ↔ `63af698` ↔ `77d0d37`의 GT 기준선 블록(`[GT Baseline:` ~ 같은 시나리오 `[MockTelemetry:`) 비교에 쓴 `compare_baseline_bytes.js`가 저장소 밖(에이전트 작업 폴더의 `scratch/`)에 있고, 경로가 삭제된 worktree(`worktree-13e53f7`, `worktree-63af698`)와 덮어써지는 메인 트리 XML에 하드코딩되어 있다. 같은 이름의 시나리오가 둘이면 뒤의 것이 앞의 것을 덮어쓰며 오류 없이 지나간다. (`87dfbb8`과 10-b로 해소)
- **영향**: 위 세 커밋 사이의 "EXACT_MATCH" 주장을 지금은 재현하거나 검증할 수 없다. (`87dfbb8`과 10-b로 해소)
- **해결 방안**: 비교 도구를 저장소에 커밋(입력: XML 경로 둘, 출력: 블록별 EXACT_MATCH 또는 첫 번째로 다른 줄과 sha256, 중복 시나리오명은 오류 종료)하고 `13e53f7` 대비 HEAD를 다시 측정.
- **추가 확인(스크립트 읽기 기준)**: 기존 `compare_baseline_bytes.js`의 `extractBlocks`는 `[GT Baseline:` 줄을 만날 때마다 버퍼를 `[line]`으로 초기화한다. 하네스는 같은 이름의 `[GT Baseline: ...]` 줄을 5줄 연속 출력(`RefinedReq`, `Top-30 TargetFiles Count`, `GT Survived`, `GT Missing`, `Top-30 List:`)하므로, 비교 범위가 마지막 줄(`Top-30 List:`)부터로 줄어들어 앞 4줄은 비교에서 빠졌을 가능성이 크다(스크립트를 읽은 결론이며 재실행으로 확인하지 않음). 새 도구는 블록 첫 줄(앞 4줄 포함)부터 끝 줄까지 비교하며, `13e53f7` ↔ HEAD(`6ef5e9f`)에서는 앞 4줄까지 포함해 5종이 모두 같음을 확인했다(아래 10-b 결과). 따라서 예전 스크립트의 비교 범위 축소가 이 두 시점 사이의 차이를 가렸을 가능성은 배제된다.
- **조치 (도구)**: `scripts/compare-harness-blocks.js`를 커밋(`87dfbb8`). 블록은 `[GT Baseline: <이름>]` 첫 줄부터 다음 `[MockTelemetry:` 줄까지이고, 같은 이름 블록이 둘이면 오류(exit 2), 블록별 `EXACT_MATCH` 또는 `FIRST_DIFF line <n>`과 sha256을 출력하며 비교 대상이 모두 `EXACT_MATCH`일 때만 exit 0이다. 검증: HEAD(`6ef5e9f`) 하네스 2회 실행 비교는 기존 5종 모두 EXACT_MATCH(exit 0), 블록 안 한 줄(2번째 줄)을 고친 파일은 `FIRST_DIFF line 2`(exit 1), 블록을 복제한 파일은 중복 오류(exit 2).
- **사용법**:
  ```
  node scripts/compare-harness-blocks.js <A.xml> <B.xml> --scenarios "APC Transit Card (Intent=LlmOff),survey_admin (Intent=LlmOff),ISM Core (Intent=LlmOff),survey_admin (Intent=LlmOn),APC DEM Isolated (Intent=LlmOn)"
  ```
  하네스 XML은 `./gradlew.bat test --offline --rerun --tests "net.ib.ixpert.ops.wuwagent.agent.clarify.Stage0EngineRoutedBaselineHarnessTest"` 실행 후 `build/test-results/test/TEST-net.ib.ixpert.ops.wuwagent.agent.clarify.Stage0EngineRoutedBaselineHarnessTest.xml`에 생긴다.
- **HEAD 기준선 파일**: `6ef5e9f` 하네스 결과 XML 2개가 임시 폴더 `%TEMP%\bl\harness_6ef5e9f_run1.xml`, `harness_6ef5e9f_run2.xml`에 있다(저장소에 커밋하지 않음, 임시 폴더라 유실될 수 있으므로 같은 명령으로 재생성). 블록별 sha256(run1=run2): APC Transit Card(LlmOff) `a7284443…4999`, survey_admin(LlmOff) `12396f45…7363`, ISM Core(LlmOff) `9f870558…6c02`, survey_admin(LlmOn) `39c72ebe…be60`, APC DEM Isolated(LlmOn) `b535c4f9…5323`.
- **기준선 sha256 (전체 값)**: 블록 텍스트(`[GT Baseline: <이름>]` 첫 줄 ~ `[MockTelemetry:` 줄, 줄바꿈 `\n`)의 SHA-256이며 두 번 실행(run1, run2)이 같은 값이다.
  - `6ef5e9f` run1 (기존 5종): 
    - `APC Transit Card (Intent=LlmOff)` (29줄) `a7284443110bbd1d3edec4ef88c941799668d5cee5d8fbda28502b1f34c44999`
    - `survey_admin (Intent=LlmOff)` (20줄) `12396f450851d5485c825a35f468f785dad4fd7112b8c3be8bf95802d0407363`
    - `ISM Core (Intent=LlmOff)` (36줄) `9f870558d1374e275923b9179b379fb64e4c6485cf44a604c5d34bec4b8e6c02`
    - `survey_admin (Intent=LlmOn)` (22줄) `39c72ebe90f878548fc1afe393634aca6f3b5be334782f84cee6288d59d2be60`
    - `APC DEM Isolated (Intent=LlmOn)` (23줄) `b535c4f92683ade092225ffcf3c7db1490d91fc107d097ad9ce486ab01475323`
  - `58eb590` run1 (7종): 위 5종과 같은 값(기존 5종 EXACT_MATCH) + 절제 실험 2종
    - `Ablation-a (survey_admin LlmOff refined + 5 identifier lines)` (40줄) `474e1cd9fab814a611929ed8d7921dceb8f20a749ddf82d94b2088a20bb474c0`
    - `Ablation-b (survey_admin LlmOn refined only - no identifiers)` (37줄) `f339f80ecb87dc7ee8dec5f930ca326f7fbf39528629b1a8e41042bbb4a8ccdf`
  - 기준 XML: `%TEMP%\bl\harness_58eb590_run1.xml`, `_run2.xml` (임시 폴더, 미커밋).
- **도구 제약**: `--scenarios`는 쉼표로 이름을 나누므로 시나리오 이름(`[GT Baseline: <이름>]`)에 쉼표를 쓸 수 없다. 쉼표가 들어간 이름은 `MISSING_IN_BOTH`로 나온다(절제 실험 블록 이름을 쉼표 없이 바꾼 이유). 다음 두 경우는 오류(exit 2)로 처리됨을 검증 파일로 확인함: (1) 끝 줄 `[MockTelemetry:` 없이 블록이 끝나는 경우(시작 줄은 있으나 파일 끝까지 끝 줄이 없음), (2) 블록 중간에 다른 이름의 `[GT Baseline:`이 나오는 경우(앞 블록의 끝 줄이 나오기 전에 다른 이름의 시작 줄이 나옴).
- **C-12 점수 분해 단언**: `58eb590`에서 `ScoredFile`에 점수 구성 요소 필드가 없어(`score` 합계만 존재, `DiscoveryModels.kt:33-44`) 테스트가 구성 요소를 재계산하고 `assertEquals(targetScored.score, 재계산 합계)`를 추가했다(C-12 3종 + 절제 실험 2종 모두 통과). 단, 재계산식에는 `RelevanceScorer.kt:133`의 `methodMatchScore`, `typeBonusScore`, `criticalChainBonus` 항이 없고, `SurveyServiceImpl`에서는 단언이 통과하므로 이 세 항이 0이었다는 것만 확인된 상태다(다른 파일에는 적용되지 않음). hop 점수표는 `RelevanceScorer.kt:61-66`(`0→40, 1→30, 2→15, else→0`)과 동일하다.
- **10-b 결과 (`13e53f7` ↔ HEAD `6ef5e9f`)**:
  - 비교 명령: `node scripts/compare-harness-blocks.js harness_13e53f7_run1.xml harness_6ef5e9f_run1.xml --scenarios "APC Transit Card (Intent=LlmOff),survey_admin (Intent=LlmOff),ISM Core (Intent=LlmOff),survey_admin (Intent=LlmOn),APC DEM Isolated (Intent=LlmOn)"` → 5종 모두 `EXACT_MATCH`, exit 0. sha256(양쪽 동일): `APC Transit Card (Intent=LlmOff)` (29줄) `a7284443110bbd1d3edec4ef88c941799668d5cee5d8fbda28502b1f34c44999`, `survey_admin (Intent=LlmOff)` (20줄) `12396f450851d5485c825a35f468f785dad4fd7112b8c3be8bf95802d0407363`, `ISM Core (Intent=LlmOff)` (36줄) `9f870558d1374e275923b9179b379fb64e4c6485cf44a604c5d34bec4b8e6c02`, `survey_admin (Intent=LlmOn)` (22줄) `39c72ebe90f878548fc1afe393634aca6f3b5be334782f84cee6288d59d2be60`, `APC DEM Isolated (Intent=LlmOn)` (23줄) `b535c4f92683ade092225ffcf3c7db1490d91fc107d097ad9ce486ab01475323`.
  - 13e53f7 실행 조건: 별도 worktree(`../wt-13e53f7`, detached HEAD `13e53f7`, 실행 후 삭제함)에서 `./gradlew.bat test --offline --rerun --tests "net.ib.ixpert.ops.wuwagent.agent.clarify.Stage0EngineRoutedBaselineHarnessTest"`(2026-10-08 09:43 시작, BUILD SUCCESSFUL). 결과 XML `tests="10" skipped="0" failures="0" errors="0"`. `fake-project/`, `node_modules/`는 junction으로 연결했고 `.meta`는 worktree에 없는 파일(`project-graph.json_ORG`, `sub-graph.json`)만 복사했다. 13e53f7의 하네스도 strict mock(`StrictClarifyMockLlm`, `StrictPipelineLlm`, 표지와 맞지 않는 프롬프트는 `error("unmatched prompt ...")`)을 쓰며, 5종의 `[MockTelemetry:` 끝 줄이 모두 `unmatched=0`이다.
  - 그래프 경로: 하네스가 절대 경로를 하드코딩한다(13e53f7과 HEAD 모두 `Stage0EngineRoutedBaselineHarnessTest.kt:251`~`:462`에서 같은 줄). survey_admin `C:/Workspace/HC_card_survey_admin/survey_admin/.meta/project-graph.json`(`:251`, `:302`), APC `C:/Workspace/graph/project-graph-a/project-graph.json`(`:361`, `:408`), ISM `C:/Workspace/graph/project-graph-i/project-graph.json`(`:462`). 파일이 없으면 `Assume.assumeNotNull`(13e53f7 `:253`, `:304`, `:363`, `:410`, `:464`)로 건너뛰는데 `skipped="0"`이므로 읽힌 것으로 본다(XML `<system-out>`에는 읽은 경로가 찍히지 않아 이는 추정이다). 세 파일의 수정 시각은 `2026-09-15`, `2026-08-03`, `2026-08-03`로 두 실행(2026-10-07 17:45, 2026-10-08 09:43)보다 앞서 실행 사이에 바뀌지 않았다. sha256(측정 시점): survey_admin `4da6b9ccac71bea236b853da0bed27018b820df42c81e2b9e475db9212190a33`, project-graph-a `1e97b4034193501c3f21c4b164c8eb8381916e9a931c22212b4806e735294bf4`, project-graph-i `7404d171e382471627f9f04708b25f1f224014001a52daff8d994c9cbbfdd8f4`.
  - `63af698`, `77d0d37`은 따로 재지 않았다. 양 끝(`13e53f7`과 HEAD)이 같아서 필요하지 않았다고 판단했다(중간 커밋에서 일시적으로 달라졌다가 되돌아온 경우는 이 비교로 배제되지 않는다).
  - 이 결과는 위 그래프 파일과 Mock LLM에서의 결과이며 실제 LLM에는 적용되지 않는다.
- **상태**: 해소 (우선순위 P3, 도구·HEAD 기준선·`13e53f7` 대비 5종 비교 완료)

---

### [B-33] Router 경로에서 정제문이 원문과 다를 때의 프롬프트 테스트 부재
- **현상**: `AnalyzeInputResolverTest`에서 파이프라인 프롬프트까지 확인하는 테스트(`bf5e33c`)의 픽스처는 `refinedRequirement = ""`(빈 정제문)이라 `primaryReq`가 원문과 같다. 정제문이 비어 있지 않고 원문과 다른 경우에 시드 선택기 프롬프트에 무엇이 들어가는지 확인하는 테스트가 없다. 나머지 정제문 픽스처(`:27`, `:117`)는 Resolver 반환값만 검증한다.
- **영향**: 운영 경로(`executeAnalyzePipeline`)에서 정제문이 시드 프롬프트로 전달된다는 계약이 테스트로 고정되어 있지 않다.
- **해결 방안**: 원문 전용 문구와 정제문 전용 문구가 다른 인텐트로 `executeAnalyzePipeline` 경로를 실행해 프롬프트 포함 여부를 단언.
- **고정된 범위**: Resolver → `analyze()` → 시드 프롬프트는 테스트 `2423ae9`(`AnalyzeRefinedSeedPromptTest`, 원문 "원문 전용 문구 A" / 정제문 "정제문 전용 문구 B", strict mock으로 프롬프트 기록, 시드 프롬프트에 B가 있고 A가 없음을 단언)로 고정. 입력을 원문으로 바꾸면 같은 테스트가 실패함을 확인함. `4ece8af`의 `RequirementAnalysisPipelineIntegrationTest`는 프롬프트를 기록하지 않고 계약의 `enrichedRequirementText`만 단언하므로 이 계약을 고정하지 못한다.
- **미고정 범위**: Router `executeAnalyzePipeline` 내부 연결(`:1499` resolve → `:1503`/`:1504` → `:1514`/`:1517`)은 코드 읽기로만 확인됨(B-35).
- **상태**: 부분 해소 (우선순위 P2, Router 내부 연결은 B-35)

---

### [B-34] `IntegrationPipelineTest`가 실 vLLM 서버를 호출하는데 live 게이트가 없음
- **현상**: `agent/integration/IntegrationPipelineTest.kt`(6개 `@Test`)는 `OpenAIClient`의 기본 서버 주소(`SettingsState.openaiServerUrl` 기본값 `http://vllm.ixpertops.cloud`)로 실제 LLM을 호출하지만, 다른 Live LLM 테스트와 달리 `Assume`/`runLiveLlmTests` 게이트가 없어 기본 `./gradlew test`에서 실행된다.
- **관측**: 2026-10-07 16:37 전체 실행(`a09dfb7`)에서 HTTP 520/502(`Server returned HTTP response code: 520/502 for URL: http://vllm.ixpertops.cloud/v1/chat/completions`)로 6건 중 3건 실패(`runHeldOutEvaluation`, `testOpenAIClientStreaming30FilesDirectly`, `traceApcHeldOutPipeline`, 소요 334초). 같은 3건은 31개 클래스 필터 실행에는 포함되지 않았다.
- **추가 사례 `agent/IntegrationAnalyzeTest#testFullPipeline`**: 같은 서버를 `TestVllmClient`(`IntegrationAnalyzeTest.kt:19-22`, 주소 `http://vllm.ixpertops.cloud/v1/chat/completions`, 모델명 `Qwen/Qwen3.8-27B-FP8`로 하드코딩)로 직접 호출하고(`:168` 부근에서 `analyze()`를 `analyzeInput` 없이 호출) 게이트가 없다. `a09dfb7` 전체 실행에서는 PASS였으나 2026-10-08 전체 실행(2c 테스트 커밋 상태)과 같은 날 단독 `--rerun`에서 모두 `java.lang.AssertionError: Failed to call LLM: HTTP 530: error code: 1033`(`FileRelevanceVerifier.kt:137`)로 실패했다. 같은 요청을 `curl -X POST`로 직접 보내도 `http_code=530`이었으므로 코드와 무관한 서버 상태다. 이 테스트는 `integration-test-result.txt`를 `C:/Workspace/member-market/`에 쓰는 부수 효과도 있다. 결정적 기준선(B-27)에는 `IntegrationPipelineTest`와 함께 "외부 서버 HTTP 5xx"로 분류해 제외한다.
- **서버가 살아 있을 때의 정지 관찰 (2026-10-13, 2d 전체 실행)**: 서버는 응답하는 상태였다(`POST /v1/chat/completions`에 최소 요청을 보내면 0.3초에 HTTP 404(존재하지 않는 모델명 `x`로 보낸 요청), `/v1/models`는 리뷰어가 외부에서 확인한 결과 HTTP 200이고 `Qwen/Qwen3.8-27B-FP8` 포함). 제외 없이 `./gradlew.bat test --offline --rerun`을 09:46:17에 시작했고, 콘솔 로그의 마지막 갱신은 10:19:03(`RequirementPipelineHardLimitTest > testHardLimitBoundary FAILED`)이며 이후 약 10:47까지 새 출력이 없었다(약 28분). 그 시점(실행 시작 후 3626초)의 `jstack` 한 번에서 `Test worker`가 `IntegrationPipelineTest.traceApcHeldOutPipeline`(`IntegrationPipelineTest.kt:556`) → `AgenticSeedSelector.selectSeeds`(`:83`) → `OpenAIClient.chatWithTools`(`OpenAIClient.kt:294`) → 람다 `OpenAIClient.kt:358`(`reader.readLine()`) → `Socket$SocketInputStream.read`에 있었다. 이후 실행은 직접 띄운 것만 `TaskStop`으로 중단했다. 한계: 스택은 한 번만 떴으므로 28분 내내 같은 읽기였는지, 같은 테스트 안에서 여러 호출이 이어진 것인지는 확인하지 못했다.
- **타임아웃 설정 (`OpenAIClient.kt` grep, 확인한 범위)**: `connectTimeout`/`readTimeout`/`timeout` grep에서 `chatWithTools`(`:231`)는 `:290-292`에서 `timeoutMs = settings.timeoutSeconds * 1000`, `connection.connectTimeout = 30_000`, `connection.readTimeout = timeoutMs`를 설정한다. `chat`(`:53`)도 `:96-98`에서 같은 설정이고 `fetchModels`(`:434`)는 `:442-443`에서 5000/5000이다. `SettingsState.timeoutSeconds` 기본값은 300(`SettingsState.kt:30`). 따라서 `:358` 읽기에 읽기 타임아웃 설정이 없는 것이 아니며 "무한 대기"로 단정할 수 없다. `readTimeout`은 읽기 한 번마다 적용되므로 서버가 SSE 청크를 계속 보내면 만료되지 않는다. 그 실행에서 `settings.timeoutSeconds`가 실제로 몇이었는지, 서버가 데이터를 계속 보냈는지는 확인하지 못했다(미확인).
- **단독 실행 필요(미실시)**: 서버가 살아 있을 때 `IntegrationPipelineTest#traceApcHeldOutPipeline`만 단독 실행해 실제 소요 시간과 그 실행의 `settings.timeoutSeconds` 값을 확인해야 위 정지가 "읽기 하나가 길다"인지 "호출이 반복된다"인지 가를 수 있다. 2d 작업에서는 하지 않기로 했고 측정하지 않았다.
- **영향**: 전체 테스트 결과가 외부 서버 상태에 따라 달라져 결정적 기준선(B-27)에 포함할 수 없고, 서버 장애 시 실행 시간이 길어진다.
- **해결 방안**: `-DrunLiveLlmTests=true` 또는 `RUN_LIVE_LLM_TESTS=true` 게이트(`Assume`)를 적용(`IntegrationAnalyzeTest` 포함).
- **상태**: Open (우선순위 P3)

---

### [B-35] `WebviewActionRouter.executeAnalyzePipeline` 테스트 불가
- **현상**: `private fun executeAnalyzePipeline(project, bridge, rawInput, messageId, inMemoryClarifyIntent)`(`WebviewActionRouter.kt:1482`)는 테스트에서 호출할 수 없다. `private`이고, `WuwLlmService.getClient()`(`:1509`)를 함수 안에서 직접 호출해 LLM 클라이언트 주입 지점이 없으며, `JcefBridge`(실 JCEF), `ApplicationManager.executeOnPooledThread`/`invokeLater`, `project.getService(GraphLoader)`에 의존한다. 테스트 소스에는 `WebviewActionRouter`를 생성하는 곳이 없다.
- **선택지**: B) 함수를 `internal`로 열고 LLM 클라이언트·그래프 로더·실행기를 파라미터로 주입. C) 플랫폼 테스트 픽스처(`BasePlatformTestCase` 등)로 Project/Application 구성(`getClient()` 주입이 풀리지 않으므로 B 필요). 선택지 A(입력 조립 경로만 직접 호출)는 `2423ae9`로 수행함. 이 선택은 `action/` 계층 "비즈니스 로직 금지" 규칙과 함께 검토해야 한다.
- **영향**: Router 내부의 `:1499 → :1503/:1504 → :1514/:1517` 연결은 코드 읽기로만 확인되며 테스트로 고정되지 않음.
- **상태**: Open (우선순위 P3)

---

### [B-36] 식별자 태그 제거를 위한 커밋 2b~4 진행 계획과 불변식
- **목표**: `IdentifierRetentionChecker`가 `refinedRequirement`에 붙이는 ` (명시된 식별자: …)` 태그를 없애도 Analyze 입력과 결과가 유지되도록, 식별자 전달을 구조화된 필드로 옮긴다(근거: B-25의 줄 형식 = 태그 형식 결과).
- **커밋 순서**:
  1. **2b** — `ResolvedAnalyzeInput` 확장(`rawRefined`, `missingIdentifiers`, `unresolvedItems`, `excludedFiles`)과 `effectiveRequirement`(`srText`) 조립. `missingBeforeFix`는 차감 없이 그대로(사전순 유지).
  2. **2c** — Stage 3 프롬프트 섹션.
  3. **2d** — 제외 식별자 차감.
  4. **도구 옵션 커밋** — 커밋 4 직전에 `scripts/compare-harness-blocks.js`에 옵션 추가.
  5. **3** — 테스트 단언을 태그 문자열에서 구조화된 필드(`unresolvedItems` 등)로 옮기기(예: `Stage0SurveyAdminSnapshotTest.kt:280`).
  6. **4** — 태그 제거.
- **2b 상태**: `b397975`(`src/main`: `AnalyzeInputResolver.kt` 1개 파일)와 `02f0db5`(테스트 전용: `AnalyzeInputStructureTest` 5건, 하네스 단언 1건) 완료. 조립식은 `missingBeforeFix`가 비면 `rawRefined`, 아니면 `"$rawRefined\n" + missing.joinToString("") { "\n$it" }`이고 `retentionAudit == null`이면 `refinedRequirement`를 그대로 쓴다. 하네스 Simulated 2b의 입력 조립 줄(`Stage0EngineRoutedBaselineHarnessTest.kt:718-720`, `:775-777`)과 같은 식이며, `missingBeforeFix`가 빈 경우만 하네스 쪽이 끝에 `\n`이 하나 더 붙는다(하네스는 LlmOff에 이 식을 쓰지 않음). 측정: 기존 5종 `EXACT_MATCH`(sha256이 B-32의 값과 같음), `b397975` run1 = run2 7종 `EXACT_MATCH`, `AnalyzeInputStructureTest`와 하네스 단언 포함 clarify 패키지 127건 중 실패 1건(B-10)·skip 3건, `RequirementAnalysisPipelineIntegrationTest` 8건과 `AnalyzeRefinedSeedPromptTest` 1건 통과. 새 테스트가 옛 식에서 실제로 실패하는지는 아래 "변이 확인"으로 확인했다.
- **2b 적용만으로 생기는 변화 (실제 LLM 입력)**: 2b 이후 실제 LLM 시드 프롬프트(`AgenticSeedSelector.kt:59`)와 Stage 3 입력(`RequirementAnalysisPipeline.kt:258-271`의 `fullRequirement`는 `effectiveReq` 기반)에는 식별자가 태그 형식 대신 라벨 없이 줄 형식으로 들어간다. Stage 3은 2c에서 보완하고 시드 프롬프트는 섹션을 넣지 않는다. 실제 LLM 영향은 **미검증**이다(하네스의 strict mock은 Stage 3에서 null을 반환해 전원 보존 폴백한다: `FileRelevanceVerifier.kt:224-227`).
- **하네스 기준선 경로 확인**: 하네스의 GT 기준선 5종 테스트는 모두 `AnalyzeInputResolver.resolve(rawInput = intent.refinedRequirement, inMemoryIntent = intent)`를 부르고 그 `effectiveRequirement`를 `analyze()`의 `primaryReq`로 넘긴다(`Stage0EngineRoutedBaselineHarnessTest.kt`의 `resolve` 호출 `:262`, `:321`, `:372`, `:426`, `:473`과 `primaryReq = resolved.effectiveRequirement` `:270`, `:329`, `:380`, `:434`, `:481`). 그래서 2b의 조립식 변경이 기준선 블록 측정에 반영된다.
- **변이 확인 (2b 테스트가 옛 식에서 실패하는지)**: `AnalyzeInputResolver.kt`를 `%TEMP%\bl\resolver_orig.kt`로 복사한 뒤 `val req = …` 식만 옛 식(`inMemoryIntent.refinedRequirement.ifBlank { inMemoryIntent.originalRequirement.ifBlank { rawInput } }`)으로 바꾸고(새 필드는 유지) `AnalyzeInputStructureTest`와 `Stage0EngineRoutedBaselineHarnessTest#testResolvedInputMatchesSimulated2bSrText_SurveyAdmin_LlmOn`만 `--rerun`으로 실행했다(2026-10-08 10:33 시작). 6건 중 2건만 실패했다: `AnalyzeInputStructureTest#missingIdentifiersAreAppendedAsLinesInGivenOrder`(`AnalyzeInputStructureTest.kt:91`, 메시지 첫 줄 `org.junit.ComparisonFailure: expected:<정제문 본문[`)와 `Stage0EngineRoutedBaselineHarnessTest#testResolvedInputMatchesSimulated2bSrText_SurveyAdmin_LlmOn`(`:761`, 메시지 첫 줄 `resolve 결과가 Simulated 2b 입력 문자열과 바이트 단위로 같아야 함: array lengths differed, expected.length=218 actual.length=244; arrays first differed at element [84]; expected:<10> but was:<32>`). 나머지 4건(`auditNullFallsBackToRefinedRequirementByteForByte`, `emptyMissingKeepsEffectiveRequirementIdenticalToRefined`, `blankRefinedStillFallsBackToOriginal`, `standaloneAnalyzeKeepsRawInputAndEmptyFields`)은 통과했다(옛 식과 새 식이 같은 경우라서). 이어서 `cp`로 원본을 덮어써 복원했고(`git checkout`/`restore` 미사용) `cmp` exit 0, `git status -s`와 `git diff -- src/main`이 모두 비어 있었다. 변이 실행 결과는 임시 산출물이라 저장소에는 없다.
- **2c 추가 조건**: Stage 3 입력에는 `rawRefined`와 두 섹션을 넣고, 식별자 줄이 붙은 `effectiveReq`는 넣지 않는다(그대로 두면 식별자가 중복됨). 두 섹션은 겹치지 않게 한다: 미확정 섹션(`[미확정(확정 아님) 식별자]`)에는 `unresolvedItems` 전체(항목마다 `UnresolvedKind` 표기), 빠진 식별자 섹션(`[원문에 있었으나 정제문에서 빠진 식별자]`)에는 `missingIdentifiers − unresolvedItems`(사전순)를 넣는다.
- **2c 설계 확정(구현 완료: `7946da7` src/main, `e267741`·`ecf2b33` 테스트)**:
  - **전달 방식**: `analyze()`에 `analyzeInput: ResolvedAnalyzeInput? = null`을 `onChunk` 앞에 추가하고 Router `:1513-1519`가 `analyzeInput = resolvedInput`을 넘긴다. 순수 함수 `AnalyzeSections.buildStage3Base`(`agent/clarify/AnalyzeSections.kt`, 신규)가 본문과 섹션을 조립한다. `analyzeInput == null`이면 현재 `baseRequirement`(`:258-259`)와 바이트 동일.
  - **본문**: `stage0Contract?.enrichedRequirementText ?: if (secondaryReq.isNotBlank()) "${rawRefined}\n$secondaryReq" else rawRefined`. 섹션은 이 본문 뒤, 앵커 블록(`:260-267`) 앞에 붙는다.
  - **미확정 섹션** `[미확정(확정 아님) 식별자]`: `unresolvedItems` 전체를 **인텐트가 준 순서**로 `- <identifier> (<UnresolvedKind>)`. 걸러내거나 중복 제거하지 않고 본문 포함 여부를 보지 않는다(본문에 이미 있어도 나온다).
  - **빠진 식별자 섹션** `[원문에 있었으나 정제문에서 빠진 식별자]`: `missingIdentifiers − unresolvedItems`(대소문자 무시)를 `missingBeforeFix` 순서(사전순)로 `- <identifier>`. 이 섹션 안에서 대소문자 무시 중복이면 먼저 나온 철자만 남긴다. 두 섹션에 걸쳐 겹치면 `unresolvedItems` 철자가 우선이며 한 번만 나온다.
  - **바이트 형식**(`\n`은 LF 1개, 끝 줄바꿈 없음): `{본문}` + (미확정이 있으면 `\n\n[미확정(확정 아님) 식별자]` + 항목마다 `\n- id (KIND)`) + (빠진 식별자가 있으면 `\n\n[원문에 있었으나 정제문에서 빠진 식별자]` + 항목마다 `\n- id`). 빈 섹션은 헤더 생략. 검증기가 `appendLine(userQuery)`로 줄바꿈을 붙이므로 `FileRelevanceVerifier.kt:239-242`의 user 내용은 `## 요구사항\n{입력}\n\n## 후보 파일 목록…`.
  - **섹션이 모두 비면 본문과 같다**: 두 섹션이 비려면 `unresolvedItems=[]`이고 `missing − unresolved = missing = []`이므로 `effectiveRequirement == rawRefined`(2b 조립식). 정제문 빈 값, `retentionAudit == null`, 단독 `/analyze` 3경우 모두 `resolve()` 출력에서 `effectiveRequirement == rawRefined`(`AnalyzeInputStructureTest`가 단언)이고 Router는 3경우 모두 non-null `analyzeInput`을 넘긴다.
  - **fail-fast**(`analyze()` 첫머리): `analyzeInput != null`이면 `require(analyzeInput.effectiveRequirement == primaryReq)`와 `require(analyzeInput.effectiveIntent === clarifyIntent)`. 첫째는 `primaryReq` 불일치만, 둘째는 `copy()` 객체, `clarifyIntent == null`인데 인텐트가 있는 경우, 인텐트가 null인데 `clarifyIntent`가 있는 경우를 막는다(`ClarifyIntent`는 `data class`라 `copy()`는 `==`로 같고 `===`로 다르다).
  - **`retentionAudit == null`이고 `unresolvedItems`가 있는 경우(의도된 동작)**: 본문은 `refinedRequirement` 그대로(옛 태그가 있을 수 있음), 빠진 식별자 섹션은 없고 미확정 섹션만 생긴다. 현재 운영 경로에는 없다(`buildClarifyIntent`는 항상 `RetentionAudit`을 채우고 Router는 디스크 인텐트를 쓰지 않음). 테스트로 고정한다.
  - **2c 이후 Stage 3 섹션 예상(미실행 유도)**: APC Transit Card LlmOff는 미확정 4개(EXISTING_REF 3, NEW_CREATION 1)로 **2b 대비 바뀜**, survey_admin/ISM Core LlmOff는 섹션 없음, survey_admin LlmOn은 미확정 5개(NEW_CREATION), APC DEM Isolated LlmOn은 빠진 식별자 2개. 하네스 5종 블록은 strict mock이 Stage 3에서 user 내용을 보지 않고 null을 반환하므로 2c를 탐지하지 못한다(회귀 확인용).
  - **테스트 구성**: [순수 함수] 1P(인텐트 픽스처 → `resolve` → 전체 문자열), 2P(대소문자 겹침), 3(빠진 섹션 안 대소문자 중복), 4(본문에 있어도 섹션에 나옴), 5P(3경우 `== effectiveRequirement`), 8(`retentionAudit == null` + `unresolvedItems`). [파이프라인 기록] 1R, 2R, 5R(3경우에서 `analyzeInput=null`과 `=resolved` 구간 바이트 동일), 6, 9(`secondaryReq`/공백/`enrichedRequirementText`). 7i~7v(fail-fast, 메서드 5개)는 LLM 호출 전 예외. 기록 테스트는 user 내용의 `## 요구사항\n`~`\n\n## 후보 파일 목록` 구간을 `assertEquals`로 전체 비교하고 출현 횟수는 보조 단언. 손으로 만든 입력의 `effectiveRequirement`는 2b 조립식으로 계산한다.
  - **변이 확인 계획(11건, 실패 기대 집합은 설계상 예측)**: M1 본문을 `effectiveRequirement`로 {1P,1R,2P,2R,3,6,9} / M2 빠진 섹션에서 `− unresolvedItems` 제거 {1P,1R,2P,2R,6} / M3a 차집합 대소문자 구분 {2P,2R} / M3b 빠진 섹션 안 중복 제거 삭제 {3} / M4 빈 섹션도 헤더 출력 {1P,1R,3,4,5P,5R,6,8,9} / M5 본문 포함 제외 추가 {1P,1R,4} / (a) 파이프라인이 `analyzeInput` 무시 {1R,2R,6,9} / (b) 미확정 섹션 사전순 정렬 {1P,1R,4,6} / (c) 첫 `require` 삭제 {7(i)} / (d) 둘째 `require`의 `===`를 `==`로 {7(ii)} / (e) 둘째 `require` 삭제 {7(ii),7(iii),7(v)}.
- **2c 측정 결과**:
  - **테스트 구성(실제)**: `AnalyzeSectionsTest` 17건(1P, 1R, 2P, 2R, 3, 4, 5P, 5R, 6, 7i~7v, 8, 9R, 9S), clarify 패키지 144건 중 실패 1(B-10), skip 3. 1R은 survey_admin LlmOff를 기록 비교에서 뺐다(한글뿐이라 작은 그래프에서 후보가 없어 Stage 3이 호출되지 않음, 1P에는 있음). 기록 테스트의 그래프는 픽스처의 effectiveRequirement에 나오는 식별자와 같은 클래스명을 가진 6개 파일이다(discovery는 `primaryReq`/`secondaryReq` 텍스트만 보므로).
  - **하네스**: `harness_58eb590_run1` 대 2c run1과 2c run1 대 run2 모두 7블록(5종 + Ablation-a/b) `EXACT_MATCH`(sha256은 B-32의 값과 같음). strict mock이 Stage 3 user 내용을 보지 않고 null을 반환하므로 이 비교는 회귀 확인용이며 2c를 탐지하지 못한다.
  - **전체 `./gradlew.bat test --offline --rerun`**: 411 tests / 50 failed / 9 skipped(약 4분 47초). 실패 목록에서 `IntegrationPipelineTest` 3건을 뺀 결과가 `fail_b7ff09e.txt`와 `IntegrationAnalyzeTest#testFullPipeline` 1건만 다르고, skip 목록은 diff가 비어 있다. 그 1건은 외부 서버 HTTP 530(B-34)이다. 따라서 **외부 서버 HTTP 530 1건 제외 시 diff 없음(조건부 충족)**. 2c 이전 커밋에서의 재현은 하지 않았다(체크아웃 금지). 서버 상태 확인은 B-34 참조.
  - **변이 확인 11건**(한 번에 1건, 새 테스트 클래스만 `--rerun`, `cp` 복원 후 `cmp` exit 0, 두 파일 sha256이 변이 전과 같음, `git status -s`와 `git diff -- src/main` 빈 출력). 실패 집합은 모두 예측과 일치했다.

    | 변이 | 실제 실패 테스트 | 예측과 일치 |
    |---|---|---|
    | M1 본문을 `effectiveRequirement`로 | 1P, 1R, 2P, 2R, 3, 6, 9R (7건) | 일치 |
    | M2 빠진 섹션 `− unresolvedItems` 제거 | 1P, 1R, 2P, 2R, 6 (5건) | 일치 |
    | M3a 차집합 대소문자 구분 | 2P, 2R | 일치 |
    | M3b 빠진 섹션 안 중복 제거 삭제 | 3 | 일치 |
    | M4 빈 섹션도 헤더 출력 | 1P, 1R, 3, 4, 5P, 5R, 6, 8, 9R, 9S (10건) | 일치 |
    | M5 본문 포함 제외 추가 | 1P, 1R, 4 | 일치 |
    | (a) 파이프라인이 `analyzeInput` 무시 | 1R, 2R, 6, 9R | 일치 |
    | (b) 미확정 섹션 사전순 정렬 | 1P, 1R, 4, 6 | 일치 |
    | (c) 첫 `require` 삭제 | 7i | 일치 |
    | (d) 둘째 `require`의 `===`를 `==`로 | 7ii | 일치 |
    | (e) 둘째 `require` 삭제 | 7ii, 7iii, 7v | 일치 |

    7iv는 모든 변이에서 통과했다. 5P/5R/8/9S는 M4에서만 실패하고 (a)~(e)에서는 통과했다(5R은 섹션이 없는 세 경우라 (a)를 잡지 못한다).
  - **미고정 범위**: `stage0Contract` 경로는 기록 수준으로 고정하지 못했다. `enrichedRequirementText`가 본문이 되고 섹션이 뒤따르는 동작은 순수 함수 테스트 9S로만 고정되고, 파이프라인에서 `stage0Contract`를 거친 Stage 3 기록 테스트는 없다(`Stage0TransitionContract.enrichedRequirementText` 기본값이 `""`라 후보 없이 끝날 수 있어 시도하지 않음). 이 경로는 운영에서 쓰이지 않는다(B-24 7). Router의 `analyzeInput = resolvedInput` 연결은 B-35로 미고정.
  - **미확인**: 실제 LLM에서 Stage 3 판정이 새 섹션으로 어떻게 바뀌는지, `UnresolvedKind` 라벨 정확도(B-37).
- **2d 설계 확정(구현 완료: `3c1f502` src/main, `c7f2f55` 테스트)**: `AnalyzeInputResolver.resolve`에서 `missingBeforeFix`에서 `excludedFiles`에 해당하는 식별자를 차감한다(`src/main`은 `AnalyzeInputResolver.kt` 1개 파일).
  - **차감 대상**: `missingIdentifiers` 하나뿐이다. `unresolvedItems`는 생성 시 `PENDING`만 담으므로(`Stage0ClarificationEngine.kt:1019`) 제외 확정(REJECTED) 항목과 구조적으로 겹치지 않고, 소비 쪽에서 다시 거르지 않는다(B-38). `retentionAudit.missingBeforeFix`는 바꾸지 않는다(커밋 4 불변식 (1)). 차감된 식별자는 `ResolvedAnalyzeInput.subtractedIdentifiers`(끝에 기본값 `emptyList()`로 추가, `missingBeforeFix` 순서 유지, 프롬프트에 넣지 않음)에 기록한다.
  - **`excludedFiles` 형식**: 그래프 경로 전체이고 확장자를 포함한다(`Stage0ClarificationEngine.kt:1004-1007`, 예 `com/example/SurveyServiceImpl.java`). `analyze()`는 완전 일치로 쓴다(`RequirementAnalysisPipeline.kt:223`, `:226-227`).
  - **일치 규칙**: 제외 파일 `file`(마지막 `/` 뒤)과 `base`(확장자 제거)에 대해 (1) `id.equals(file, ignoreCase = true)`(모든 확장자), (2) 제외 파일의 확장자가 `java` 또는 `kt`일 때만 `id.equals(base, ignoreCase = true)`. `java`/`kt`는 클래스명이 생기는 확장자다(`ProjectGraphBuilder.kt:410`, `SpringAnnotationResolver.kt:126-129`). 따라서 `aCMBTBAPC024DEM`은 `…/ACMBTBAPC024DEM.java`에 차감되고(B-22), `Foo.xml`은 `…/Foo.java`에, `ECMBTBISM006Mapper`는 `…/ECMBTBISM006Mapper.xml`에, `SurveyServiceImpl`은 `…/SurveyService.java`에 차감되지 않으며, 메서드 토큰 `selTrcdIsInf`는 소유 클래스가 제외돼도 차감하지 않는다(소유 클래스 추론 없음).
  - **영향**: `effectiveRequirement`의 식별자 줄과 시드 선정 입력(`srText`), Stage 3 빠진 식별자 섹션에서 차감된 식별자가 빠진다. 차감할 것이 없으면 2c와 바이트 동일하다. `srKeyHex`는 겹침이 있는 인텐트에서만 바뀐다(B-24 8). 제외된 식별자는 섹션에 표시하지 않는다(제외 파일은 이미 후보에서 빠지고, 이름 제시가 후보를 끌 수 있음).
  - **범위 밖**: 정제문 본문에 남은 제외 식별자, 소유 클래스 추론, 같은 파일명이 여러 패키지에 있는 경우의 구분, 한 파일에 여러 최상위 클래스가 있는 경우의 클래스명.
  - **발생 빈도**: 확보한 인텐트(기준선 5종, 4조건)에서 `excludedFiles ≠ []`인 것은 Condition3뿐이고 `missingBeforeFix`와 `unresolvedItems`가 겹치지 않는다. 2d는 현재 모든 픽스처에서 무동작이다(하네스가 2d를 탐지하지 못하는 이유). LLM 정제문이 제외 식별자를 빠뜨리는 빈도는 미확인.
  - **테스트 16건(`ExcludedIdentifierSubtractionTest`)**: [순수 함수] D1P(차감, `subtractedIdentifiers`가 `missingBeforeFix` 순서), D2aP(파일명 형태, `.java`와 `.xml`), D2bP(`Foo.xml`은 `Foo.java`에 비차감), D2cP(`ECMBTBISM006Mapper`는 `…Mapper.xml`에 비차감), D3P(B-22 대소문자), D4P(`selTrcdIsInf` 비차감), D5P(전부 차감 시 `effectiveRequirement == rawRefined`), D6P(겹침 없으면 2c와 바이트 동일), D7P(`unresolvedItems` 비차감), D8P(`effectiveIntent === intent`, 감사 기록 불변), D9P(부분 문자열 비차감), D10P(본문에 제외 식별자가 있어도 `rawRefined`/`effectiveRequirement` 불변). [파이프라인 기록] D1R(Stage 3 구간 전체 `assertEquals`), D2R(전부 차감 시 구간이 `rawRefined`와 동일), D3R(시드 프롬프트에 차감된 식별자 없음), D0R(엔진 경로: 정제 mock이 `SurveyServiceImpl`을 뺀 정제문을 돌려줌 → `buildClarifyIntent` → `resolve` → `analyze(primaryReq = resolved.effectiveRequirement, clarifyIntent = resolved.effectiveIntent, analyzeInput = resolved)`).
  - **변이 N1~N10(실행 범위: 새 클래스 + `AnalyzeSectionsTest` + `AnalyzeInputStructureTest` + `AnalyzeRefinedSeedPromptTest` + 하네스 `testResolvedInputMatchesSimulated2bSrText_*` + `AnalyzeInputResolverTest`, 예측은 설계상)**: N1 차감 제거 {D1P, D2aP, D3P, D5P, D1R, D2R, D3R, D0R} / N2 규칙 1 삭제(규칙 2의 java/kt 제한 유지) {D2aP} / N3 `ignoreCase = false` {D3P} / N4 규칙 2를 `id.substringBeforeLast('.')`와 `base` 비교로(java/kt 제한 유지) {D2bP} / N5 규칙 2의 `equals`를 `contains`로(java/kt 제한 유지) {D9P, D2bP} / N6 `unresolvedItems`도 `filePath`로 차감 {D7P} / N7 차감본이 비어 있지 않을 때만 `effectiveIntent`를 감사 기록을 바꾼 복사본으로 교체 {D8P} / N8 `effectiveRequirement`에서만 차감하고 `missingIdentifiers`는 그대로 {D1P, D2aP, D3P, D5P, D1R, D2R, D0R} / N9 규칙 2의 java/kt 제한 삭제 {D2cP} / N10 제외 파일의 기본 이름을 `rawRefined` 본문에서 제거 {D10P, `AnalyzeInputStructureTest#auditNullFallsBackToRefinedRequirementByteForByte`}. `AnalyzeInputResolverTest`는 모든 변이에서 통과로 예측한다.
  - **합격 기준**: 하네스 7블록 `EXACT_MATCH`는 회귀 확인용이며 2d를 탐지하지 못한다(모든 하네스 픽스처가 `excludedFiles = []`). clarify 패키지 160건 중 실패 1(B-10)·skip 3. 변이 10건이 예측한 테스트에서만 실패.
- **2d 측정 결과**:
  - **테스트**: `ExcludedIdentifierSubtractionTest` 16건(D0R, D1P, D1R, D2aP, D2bP, D2cP, D2R, D3P, D3R, D4P, D5P, D6P, D7P, D8P, D9P, D10P). 테스트 커밋 `c7f2f55`의 `git diff 3c1f502 --stat -- src/main`은 빈 출력. D0R(엔진 경로)의 단언 1·2·4와 시드 프롬프트 1건 단언은 mock이나 픽스처를 고치지 않고 통과했다(`sr`은 `"신규 FooService 추가\n"`).
  - **clarify 패키지**: 160건 중 실패 1(B-10), skip 3. 하네스 7블록(5종 + Ablation-a/b)은 `harness_58eb590_run1` 대 2d run1, 2d run1 대 run2 모두 `EXACT_MATCH`. 모든 하네스 픽스처가 `excludedFiles = []`이므로 이 비교는 회귀 확인용이며 2d를 탐지하지 못한다.
  - **전체 테스트(④)는 서버 의존 14개 클래스를 제외한 기준**이다. 서버가 살아 있는 상태에서 제외 없이 돌린 전체 실행(2026-10-13 09:46 시작)은 `IntegrationPipelineTest#traceApcHeldOutPipeline`에서 진행하지 않아 중단했다(B-34). 제외 14개: `AgenticExplorationPilotTest`, `AgenticSeedSelectorBenchmarkTest`, `IntegrationAnalyzeTest`, `LlmDomainExtractionBenchmarkTest`, `PipelineE2ETest`, `VllmToolCallPilotTest`, `ClarifyBaselineTest`, `PipelineBaselineTest`, `completeness.ApcPilotHarnessTest`, `completeness.Scenario7E2ETest`, `completeness.Stage3TokenMeasurementTest`, `completeness.TokenMeasurementTest`, `stage0.E2EPipelineIntegrationTest`, `integration.IntegrationPipelineTest`(26건). `runLiveLlmTests` 게이트 4개 클래스는 제외하지 않았다(기본 skip, skip 목록 비교에 필요). 제외는 저장소 파일 수정 없이 TEMP의 init script(`-I`)로 했고, 실행 전 `RUN_LIVE_LLM_TESTS` 환경 변수와 `runLiveLlmTests` 속성이 설정되지 않았음을 확인했다. 실행 전 `runIde`(PID 21144)는 닫힌 상태였다(`jps -l`로 확인).
  - **④ 결과**: `405 tests completed, 42 failed, 5 skipped`(예상 431 − 26 = 405). 같은 14개 클래스를 뺀 `a09dfb7`·2c 전체 실행의 실패 42·skip 5 목록과 diff 없음. 제외 14개 클래스의 XML은 0개이고 `ixpertops.cloud`와 `HTTP `를 포함한 XML은 0건. 이 실행은 서버 의존 클래스를 보지 않으므로 서버 상태에 영향받지 않는 부분만 증명한다. 소요 11분 17초(2c 전체 실행은 4분 47초)이며 차이의 원인은 **미확인**이다(하네스 약 301초와 `Stage0SurveyAdminSnapshotTest` 약 240초가 큰 비중).
  - **진행 신호**: Gradle은 XML을 작업 끝에 쓰므로 실행 중에는 새 XML이 생기지 않는다. 정지 판정에는 새 XML 개수와 함께 `build/test-results/test/binary/output.bin` 크기를 진행 신호로 썼다.
  - **변이 10건**(실행 범위: 새 클래스 + `AnalyzeSectionsTest` + `AnalyzeInputStructureTest` + `AnalyzeRefinedSeedPromptTest` + `AnalyzeInputResolverTest` + 하네스 `testResolvedInputMatchesSimulated2bSrText_SurveyAdmin_LlmOn`, 한 번에 1건, `cp` 복원 후 `cmp` exit 0, 변이 전과 같은 sha256 `7b67b6e0…2b6d`, `git status -s`와 `git diff -- src/main` 빈 출력). 9건은 예측과 일치했고 N1만 예측이 틀려 정정했다.

    | 변이 | 실제 실패 | 예측 |
    |---|---|---|
    | N1 차감 제거 | D0R, D1P, D1R, D2aP, D2R, D3P, D3R, D5P, **D8P** (9건) | **정정**(아래) |
    | N2 규칙 1 삭제 | D2aP | 일치 |
    | N3 `ignoreCase = false` | D3P | 일치 |
    | N4 식별자 쪽 확장자도 떼어 비교 | D2bP | 일치 |
    | N5 `equals`를 `contains`로 | D2bP, D9P | 일치 |
    | N6 `unresolvedItems`도 `filePath`로 차감 | D7P | 일치 |
    | N7 차감이 있을 때 `effectiveIntent` 교체 | D8P | 일치 |
    | N8 `missingIdentifiers`만 원본 | D0R, D1P, D1R, D2aP, D2R, D3P, D5P (7건) | 일치 |
    | N9 java/kt 제한 삭제 | D2cP | 일치 |
    | N10 본문에서 제외 파일 기본 이름 제거 | D10P, `AnalyzeInputStructureTest#auditNullFallsBackToRefinedRequirementByteForByte` | 일치 |

    `AnalyzeSectionsTest`, `AnalyzeRefinedSeedPromptTest`, `AnalyzeInputResolverTest`, 하네스 테스트는 N10의 `AnalyzeInputStructureTest` 1건을 빼고 모든 변이에서 통과했다.
  - **N1 예측 정정**: 예측은 8건(D8P 제외)이었으나 실제는 9건이다. D8P의 첫 단언 `assertTrue("전제: 차감이 실제로 생겨야 함", …)`가 차감이 없는 N1에서도 실패하기 때문이며(N7에서는 신원 단언으로 실패), 예측에서 이 전제 단언을 빠뜨렸다.
  - **변이 실행 절차 기록**: 변이 10건을 한 스크립트로 연속 실행해, N1이 예측과 다르다는 것을 안 시점에 이미 N2~N10이 끝나 있었다(예측과 다르면 그 자리에서 멈추는 규칙을 지키지 못함). 매 변이마다 `cp` 복원 후 `cmp`, sha256, `git status -s`, `git diff -- src/main`을 확인했으므로 저장소 상태와 각 변이의 결과는 유효하다. 다음 변이 실행부터는 스크립트가 예측과 처음 어긋나는 지점에서 멈추게 한다: `check_expected.js`가 변이마다 실제 실패 집합을 `expected` 예측과 정확히 비교하고(예측에 없는데 실패, 예측했는데 통과, XML 누락 모두 불일치), 처음 어긋나는 변이에서 `STOP`을 출력하고 이후 변이를 시작하지 않는다(Gradle 없는 자체 검증 4건 통과, 원래 8건 예측이면 N1에서 멈췄을 것).
- **2d 테스트 예시**: B-22의 `aCMBTBAPC024DEM`과 `ACMBTBAPC024DEM`(대소문자 불일치), `selTrcdIsInf`를 포함한다.
- **도구 옵션(커밋 4 직전)**: `RefinedReq:` 줄만 비교에서 빼고 그 줄은 따로 보고한다. `Top-30 TargetFiles Count`, `GT Survived`, `GT Missing`은 비교 범위에 남긴다.
  - **구현(`b8ee312`)**: `scripts/compare-harness-blocks.js --exclude-line-prefix "RefinedReq:"`(반복 가능, 쉼표로 쪼개지 않음). `[GT Baseline: <이름>] ` 라벨 뒤 내용이 접두어로 시작하는 줄을 양쪽 블록에서 뺀 뒤 비교한다(라벨 없는 줄은 제외 불가). 허용 접두어는 코드 상수 `RefinedReq:` 하나이고 `--scenarios`와 함께만 쓴다. 양쪽 블록에 접두어 줄이 각각 정확히 1줄이어야 하며(0줄 `prefix not found`, 2줄 이상 `ambiguous`) 오류는 종료 코드 2다. 제외 후 일치하면 `MATCH_EXCLUDING`(`EXACT_MATCH`는 제외가 없을 때만)이고 제외한 줄 수, 제외한 줄 원문(A/B), 블록별 `excludedLinesDiffer`를 출력한다. 자체 검증 `scripts/compare-harness-blocks.selftest.js`는 T1~T13(메모리 XML)과 `--real` 실데이터 확인(기준선 대 2d run1, 태그만 지운 합성 사본)을 포함해 24/24 통과했다. 옵션 없이 기준선 대 2d run1을 비교한 결과는 이전과 같은 sha256이다.
  - **커밋 4 사용법**: 엄격 그룹(LlmOff 3종 + Ablation-a/b)은 옵션 없이, 제외 그룹(LlmOn 2종: `survey_admin (Intent=LlmOn)`, `APC DEM Isolated (Intent=LlmOn)`)은 `--scenarios`와 `--exclude-line-prefix "RefinedReq:"`로 두 번 호출한다.
  - **커밋 4 합격 기준(비교 부분)**: 엄격 그룹(LlmOff 3종 + Ablation-a/b) 5종 `EXACT_MATCH`, 제외 그룹(LlmOn 2종) `MATCH_EXCLUDING`이며 2종 모두 `excludedLinesDiffer=true`. 제외한 `RefinedReq:` 줄 원문을 별도 보고. `testResolvedInputMatchesSimulated2bSrText_*` 통과(Simulated 2b 블록은 `[GT Baseline:` 시작이 아니라 도구 비교 대상이 아님).
  - **엄격 그룹 예측의 근거와 조건**: 하네스에서 입력 문자열을 만드는 줄을 확인했다. Ablation-a는 `:1103`에서 `intent.refinedRequirement + "\n" + identifiers`를 읽고(`llmClient = null`인 survey_admin LlmOff 인텐트, `missingBeforeFix = []`이라 태그가 없고 InputText에도 태그 없음), `identifiers`는 `:1096-1102`의 문자열 리터럴 5개라 커밋 4 영향권(`missingBeforeFix` 등)이 아니다. Ablation-b는 `:1136`에서 `retentionAudit?.rawRefinedRequirement`를 우선 읽고 `refinedRequirement`는 audit가 null일 때의 대체값이다(엔진 경로 인텐트는 audit가 항상 non-null). Simulated 2b(`:723`, `:830`)도 audit-first다. 따라서 Ablation-a의 예측 조건은 "LlmOff 인텐트의 `refinedRequirement`가 커밋 4 전후로 같다", Ablation-b의 조건은 "커밋 4가 `rawRefinedRequirement` 값을 바꾸지 않는다"이다(등식 불변식 `rawRefinedRequirement == refinedRequirement`만으로는 두 필드에 같은 태그가 붙는 경우를 막지 못한다). 이 조건이 깨지면 엄격 그룹이 `FIRST_DIFF`로 잡아낸다.
  - **한계**: `RefinedReq:` 줄 자체가 예상과 다르게 바뀌는 것(예: 내용이 잘림)은 이 옵션으로 잡히지 않으므로 제외한 줄 원문을 사람이 확인하고 커밋 4 불변식 테스트가 맡는다.
  - **도구 변이 3건과 자체 검증 보강(`c35f7ce`)**: 자체 검증의 R 계열은 `--real <기준선.xml> <비교.xml>` 인자로만 경로를 받고(하드코딩·환경 변수 없음), 인자가 없거나 파일이 없으면 `SKIP`으로 표시하며 종료 코드는 T 계열만으로 정한다(T 17건, 전체 24건). 도구를 TEMP 사본에 변이해(한 번에 1건, `cp` 복원 후 `cmp` exit 0, 저장소 `scripts/`와 sha256 불변) 자체 검증이 결함을 잡는지 확인했고 3건 모두 정정된 예측과 일치했다: M1 "정확히 1줄" 검사 제거 → {T6, T7, T10}, M2 옵션 없이도 제외 적용 → {T1, T4, T11c, R1b, R2c, R2d}, M3 `excludedLinesDiffer`를 항상 false로 → {T3, T11b, R2a, R2b}. 실행 전 예측 중 M2는 T11d(`--scenarios` 밖 블록 Z는 비교 대상이 아니어서 실패하지 않음)가 빠지고 T1, T4, R1b, R2c, R2d가 추가되도록, M3는 T11(`excludedLinesDiffer`를 단언하지 않음)이 T3으로 바뀌도록 정정했다. M2의 정의는 실행 전에 "`--scenarios` 밖 블록에도 적용"에서 "옵션이 없어도 제외를 항상 적용"으로 교체했다(앞의 정의는 `--scenarios`로 비교 대상을 한정하면 밖 블록이 비교되지 않아 실패하는 테스트가 없는 변이였음).
  - **커밋 3 설계(테스트 전용)**: 태그 문자열에 의존하는 단언 2건을 구조화 필드로 옮긴다. (a) `IdentifierRetentionCheckerTest#testCheckRetentionPreservesRawRefinedRequirementForMetrics`의 `:110`(태그 존재) → `missingIdentifiers == [AlimtalkTemplateBatchRepository, SurveyServiceImpl.java, sql_survey.xml]`(`.sorted()` 순서, 픽스처에서 손으로 도출), `expectedIdentifiers == setOf("SurveyServiceImpl.java", "sql_survey.xml", "AlimtalkTemplateBatchRepository")`(`RetentionResult.expectedIdentifiers`는 `Set<String>`, `IdentifierRetentionChecker.kt:21`), `effectiveRefinedRequirement.startsWith(rawRefined.trimEnd('.'))`(태그가 있을 때와 없을 때 모두 참). (b) `Stage0SurveyAdminSnapshotTest#testCaseBFullE2EAmplificationMeasurement`의 `:280-281` → 전제 `intent.excludedFiles`가 비어 있음, `AnalyzeInputResolver.resolve` 결과의 `effectiveRequirement`에 Category A 5종이 보존됨, 각 식별자가 `rawRefined`에 있거나 `missingIdentifiers`로 인계됨. 옮기지 않는 것: 옛 형식을 재현하는 픽스처 리터럴(`AnalyzeInputStructureTest` `:39`·`:88`, `AnalyzeSectionsTest`, `ExcludedIdentifierSubtractionTest`)과 하네스 `:1008`.
  - **커밋 3 합격 기준**: (1) `git diff HEAD~1 --stat -- src/main` 빈 출력. (2) 서버 의존 14개 클래스를 제외한 전체 실행이 **405건 / 실패 42 / skip 5**이고 실패 42건의 유형·메시지·위치가 기준 실행과 같다(**실패 메시지 불변**). (3) clarify 패키지 **160건 중 실패 1(B-10), skip 3**, 하네스 7블록 `EXACT_MATCH`(`harness_58eb590_run1` 기준, 회귀 확인용). (4) 커밋 4 시뮬레이션(`IdentifierRetentionChecker.kt:182-187`의 태그 조립을 `val effective = refinedRequirement`로 대체)에서 실패가 기준 42건뿐(커밋 3 전에는 +2건이었음). (5) 변이 V1~V5가 메서드 단위 예측과 일치하고 처음 어긋나는 지점에서 멈춘다.
  - **커밋 3 결과(`824d609`)**: 태그 단언 2건을 구조화 필드로 옮겼다(`IdentifierRetentionCheckerTest` 12건, `Stage0SurveyAdminSnapshotTest` 7건 통과, `:110` 기대값은 손 도출과 일치, `excludedFiles == []` 전제 성립). `git diff HEAD~1 --stat -- src/main`은 빈 출력. 서버 의존 14개 클래스 제외 전체 실행 **405건 / 실패 42 / skip 5**(소요 12분 29초), 실패 42건의 유형·메시지·위치는 기준 실행과 diff 없음(실패 메시지 불변). clarify 패키지 160건 중 실패 1(B-10)·skip 3, 하네스 7블록 `EXACT_MATCH`(`harness_58eb590_run1` 기준). 커밋 4 시뮬레이션(태그 조립을 `val effective = refinedRequirement`로 대체) 전체 실행도 405/42/5이고 실패는 기준 42건뿐이며 메시지 불변이다(커밋 3 전에는 +2건이었으므로 옮긴 두 단언이 태그 없이도 성립함을 확인).
  - **커밋 3 변이(두 클래스 `IdentifierRetentionCheckerTest`, `Stage0SurveyAdminSnapshotTest`, 한 번에 1건, `cp` 복원 후 `cmp` exit 0, `src/main` 두 파일 sha256 불변, 예측과 처음 어긋나면 멈추는 스크립트 사용)**: 4건 모두 메서드 단위 예측과 일치했다(V2는 위 커밋 4 시뮬레이션 전체 실행으로 대체: 커밋 3 전 +2건, 후 0건).

    | 변이 | 정의 | 실제 실패 |
    |---|---|---|
    | V1 | `Stage0ClarificationEngine.kt:1059` `missingBeforeFix = emptyList()` | `Stage0SurveyAdminSnapshotTest#testCaseBFullE2EAmplificationMeasurement`(새 단언만 잡음, 태그에서 읽던 옛 단언은 못 잡았음) |
    | V3 | `checkRetention`의 `missing`을 항상 `emptyList()` | `IdentifierRetentionCheckerTest#testPartialSubstringMatchDoesNotFalselySatisfyBoundary`, `…#testCheckRetentionPreservesRawRefinedRequirementForMetrics`, `Stage0SurveyAdminSnapshotTest#testCaseBFullE2EAmplificationMeasurement` |
    | V4 | `missing`을 `.sorted().take(1)`로 | `…#testCheckRetentionPreservesRawRefinedRequirementForMetrics`(`expected:<3> but was:<1>`), `Stage0SurveyAdminSnapshotTest#testCaseBFullE2EAmplificationMeasurement` |
    | V5 | 결과의 `rawRefinedRequirement`에 태그가 붙은 `effective`를 넣음 | `…#testCheckRetentionPreservesRawRefinedRequirementForMetrics`(`:107` 원시 정제문 보존 단언, 새 `startsWith` 단언이 아니라 기존 단언이 잡음) |

    `ICT#testScopeModifierRetentionAndDropDetection`은 V3 전문을 읽고(`missing`이 원래 모든 경우에 `[]`) 영향이 없다고 예측했고 실제로도 실패하지 않았다.
  - **커밋 4 합격 기준 보강**: 자체 검증 `node scripts/compare-harness-blocks.selftest.js --real <기준선.xml> <비교.xml>`이 **24/24이고 SKIP 0**이어야 한다(R 계열이 `SKIP`이면 실데이터 확인이 안 된 것으로 보고 미충족).
  - **커밋 4 시뮬레이션 기록(2026-10-13, 서버 의존 14개 클래스 제외, 커밋 3 설계 단계)**: 기준 405/42/5, 태그 조립 제거 시뮬레이션 405/44/5. 새로 실패한 단언은 정확히 2건(`IdentifierRetentionCheckerTest.kt:110`, `Stage0SurveyAdminSnapshotTest.kt:280`)이고 기준 42건의 실패 메시지는 같았다. 서버 의존 14개 클래스는 `refinedRequirement` 계열 필드와 `IdentifierRetentionChecker`를 읽지 않는다(grep 0건).
  - **정리 대상(미실시)**: `IdentifierRetentionCheckerTest.kt:83`(`SurveyServiceImpl`의 부분 문자열이라 항상 참인 단언), 하네스 `:801-805`의 단언 메시지("기존 태그 형식 정제문", 커밋 4 후 표현이 낡음), 자체 검증 T11 라벨(T11은 `excludedLinesDiffer`를 단언하지 않고 T11b가 단언), 커밋 3의 `startsWith` 단언(커밋 4에서 불변식 (2)에 맞춰 `assertEquals(rawRefined, effectiveRefinedRequirement)`로 강화; 커밋 4c에서 완료), `ClarifyIntent.kt:14`의 낡은 주석("Analyze는 refinedRequirement를 소비"; 커밋 4b에서 고침). **커밋 4 이후 추가**: `ClarifyIntent.kt:11`의 "(Analyze 방향타)" 문구(분석은 `refinedRequirement`가 아니라 `AnalyzeInputResolver` 결과를 쓴다), `IdentifierRetentionChecker.kt`의 누락 감지 로그 문구 "정제문에 안전판 병기 수행"(더는 병기하지 않음). 이 항목들은 사용자 결과에 영향이 없어 한 줄만 남긴다.
  - **`:227` 점검 항목(커밋 4 전)**: `ClarifyToAnalyzeIntegrationBaselineTest.kt:227`(live 게이트, 기본 skip)은 저장·로드한 인텐트의 `loadedIntent.refinedRequirement`를 `refinedSr`로 받아 `checkIdentifierRetention`(`:158`, `EXACT`/`CASE_ALTERED`/`SUFFIX_DROPPED`/`DROPPED`)과 `AdaptiveFileDiscovery.filter(primaryReq = refinedSr)`(`:230-231`)에 직접 쓴다(resolver와 Stage 3 우회). 커밋 4 후에는 태그가 없어 식별자가 `DROPPED`로 나오고 입력도 달라져 "Refined GT Recall" 측정 의미가 바뀐다. `AnalyzeInputResolver.resolve(...).effectiveRequirement`로 옮긴다(커밋 4a). `refinedSr`의 소비처는 `:252`(출력), `:264`(`PairRunResult.refinedSr` 저장), `:302`(리포트 줄 출력)뿐이다(4a 이후 줄 번호)(`git grep`). **기록(수정)**: 운영 입력 형식과 맞춘다. Recall 값은 이어진다(식별자가 태그 대신 줄로 들어가는 설계상의 판단이며 live 게이트라 실측하지 않았다). 2d 차감은 이 테스트에 반영되지 않았음을 미검증으로 둔다. `refinedSr`가 여러 줄이 되므로 `:300`의 인라인 코드 표기가 줄바꿈을 포함한다(표시 문제만).
  - **커밋 4 설계(2026-10-14, 미실행)**: 세 커밋으로 나눈다. **4a(테스트, `src/main` 변경 없음)**: `ExcludedIdentifierSubtractionTest`에 `i1R`(엔진 경로에서 `missingBeforeFix == [SurveyServiceImpl]`)과 `i2a`(`retentionAudit.rawRefinedRequirement == "신규 FooService 추가"` 리터럴)를 추가하고 `ClarifyToAnalyzeIntegrationBaselineTest`의 `:227`을 resolver 경유로 바꾼다. **4b(`src/main` 2개 파일만)**: `IdentifierRetentionChecker.kt`의 태그 `buildString`을 `val effective = refinedRequirement`로 바꾸고 `verifyAndRetain` KDoc을 고치며, `model/ClarifyIntent.kt:14`의 `userStatements` 주석("Analyze는 refinedRequirement를 소비하고…")만 고친다. **4c(테스트)**: `IdentifierRetentionCheckerTest`의 `startsWith`를 `assertEquals(rawRefined, result.effectiveRefinedRequirement)`로 강화하고 `i2b`(`refinedRequirement == "신규 FooService 추가"`, `== rawRefinedRequirement`, 태그 문자열 없음)를 추가한다. 끝 마침표: 태그가 붙을 때만 `trimEnd('.')`가 적용되던 것이 사라져 raw의 끝 마침표가 남는다(하네스 두 시나리오의 raw에는 없음).
  - **커밋 4 예측(실행 전 확정, 손 도출)**: `i1R` 기대값은 `extractIdentifiers`(`:41`)를 `원문 + 발화`에 적용해 도출했다: 규칙 3(UpperCamel)이 `SurveyServiceImpl`과 `FooService`를 뽑고(`EXCLUDED_COMMON_WORDS`에 없음) 규칙 2·4·5는 해당 없음이며, 정제 mock이 `신규 FooService 추가`를 돌려주므로 빠진 것은 `SurveyServiceImpl` 하나다. `engineIntent()`는 `initSession`·`processTurn`·`buildClarifyIntent`가 모두 일반 함수(`suspend` 아님)라 `runBlocking` 없이 호출한다. **숫자**: 4a·4b는 서버 제외 전체 실행 407/42/5, clarify 패키지 162/1/3, 4c는 408/42/5와 163/1/3이다. **하네스**: 4a는 기준(`harness_58eb590_run1`) 대비 7블록 `EXACT_MATCH`, 4b는 엄격 그룹 5종 `EXACT_MATCH`와 제외 그룹 2종 `MATCH_EXCLUDING`(`excludedLinesDiffer=true`)이고 `RefinedReq:` 줄은 `[GT Baseline: survey_admin (Intent=LlmOn)] RefinedReq: "설문 발송 채널에 브랜드메시지 추가 및 Bizgo 연동 API 신규 개발"`, `[GT Baseline: APC DEM Isolated (Intent=LlmOn)] RefinedReq: "앱카드 및 모니모페이 최근이력 1건 조회 기능 개발"`과 바이트가 같아야 한다(근거: 기준 XML의 raw 줄 `:661`, `:666`). 그 밖의 합격 기준: 4b의 `git diff HEAD~1 --stat`이 `src/main` 2개 파일이고 `ClarifyIntent.kt` diff가 `//` 주석 줄만 바뀌며, `IdentifierRetentionChecker.kt`는 시뮬레이션 파일과 `diff`했을 때 KDoc hunk만 다르고, 자체 검증 `--real` 24/24 SKIP 0, `testResolvedInputMatchesSimulated2bSrText_*` 통과.
  - **커밋 4 변이 예측(커밋 4c 이후 트리, TEMP/`cp`)**: (a) 태그를 `effective`에만 다시 붙임 → 실패 `ICT#testCheckRetentionPreservesRawRefinedRequirementForMetrics`, `ExcludedIdentifierSubtractionTest#i2b`. (b) `effective`와 `rawRefinedRequirement` 모두에 태그(체커의 누락 분기에서 `val effective`를 태그 `buildString`으로 되돌리고 `rawRefinedRequirement = effective`로 바꿈) → 실패 `ICT#testCheckRetentionPreservesRawRefinedRequirementForMetrics`, `ExcludedIdentifierSubtractionTest#d0R`(엔진 경로, 정제 시드 단언), `#i2a`, `#i2b`. `d1R`·`d2R`·`d3R`은 `intentFixture`로 손수 만든 인텐트라 엔진을 거치지 않으므로 (a)(b)에서 실패하지 않는다. (c) 엔진 `missingBeforeFix = emptyList()` → 실패 `Stage0SurveyAdminSnapshotTest#testCaseBFullE2EAmplificationMeasurement`, `ExcludedIdentifierSubtractionTest#d0R`(단언 2), `#i1R`. 실행 범위는 `IdentifierRetentionCheckerTest`, `Stage0SurveyAdminSnapshotTest`, `ExcludedIdentifierSubtractionTest` 세 클래스이며 그 밖의 클래스는 예측하지 않는다. **하네스 (b)**: `Ablation-b`는 `:1136`에서 `retentionAudit.rawRefinedRequirement`를 `InputText`로 찍는다(`survey_admin` LlmOn 엔진 인텐트, `missingBeforeFix`는 기준 XML 태그의 5개 식별자). 따라서 (b)에서 `InputText` 줄에 태그가 들어가 `FIRST_DIFF`. 엄격 그룹 나머지 4종(`Ablation-a`, LlmOff 3종)은 누락이 없는 인텐트라 `EXACT_MATCH`. LlmOn 2종은 `RefinedReq:` 줄이 다르다는 것(`excludedLinesDiffer=true`)만 예측하고 판정과 나머지는 관찰 항목이다. `Simulated 2b` 계열과 `testResolvedInputMatchesSimulated2bSrText_*`도 관찰 항목이며 판정·중단 조건이 아니다.
  - **실패 메시지 비교 규칙과 관찰 항목(2026-10-14, 커밋 4b 확인 중 발견)**: 4b 1회차 실행에서 `CallRelationAnalyzerTest#testEdgeCasesForAnyframe`의 `at`(스택에서 뽑은 첫 테스트 클래스 프레임)이 기준과 달랐다(`AnyframeVerificationTest.tearDown(AnyframeVerificationTest.kt:22)`). 원인은 스택 끝의 `Suppressed:` 블록(`AnyframeVerificationTest.tearDown` → `SettingsState.getInstance`, `Application`이 null)이다. 같은 트리의 2회차에서는 재현되지 않았고(`at`이 `""`로 복귀, 메시지 diff 0) type·message는 보관 중인 모든 실행(기준, 커밋 3 HEAD, 커밋 3 시뮬레이션, 4a, 4b 1·2회차)에서 같았다. 이 `Suppressed:`가 1회차에만 붙은 이유는 미확인이다. 두 실행의 `CallRelationAnalyzerTest`·`AnyframeVerificationTest` 순서는 같았다(XML `testsuite` timestamp). **앞으로의 비교 규칙**: `JBR.<clinit>` NPE가 원인인 실패 31건(패키지 `net.ib.ixpert.ops.wuwagent.service.metagraph.` 아래의 클래스#메서드: SpringAnnotationResolverLocalNameTest#{testBoilerplateFiltering, testCleanJavadocPhraseExtraction, testExplicitLocalNameAnnotation, testExtractionDeterminism, testMessyLongJavadocNoiseFiltering, testSingleLineCommentExtraction}; analyzer.CallRelationAnalyzerTest#{testEdgeCasesForAnyframe, testUsesTypeExtraction}; consumer.AnyframeVerificationTest#{testDemAnalyzer, testDependencyAndBizCallAnalyzers, testRelevanceFilterAndFormatterIntegration, testServiceIdAnalyzer, testVoChainAnalyzer}; consumer.PsiMethodExtractorTest#{testCalculateMatchScore_EmptyKeywords, testCalculateMatchScore_HighMatch, testCalculateMatchScore_NoMatch, testClassSkeleton_ToPromptText, testExtractKeywords_Empty, testExtractKeywords_English, testExtractKeywords_Korean, testExtract_AnnotationsIncluded, testExtract_LargeServiceFile, testSplitCamelCase_AllCaps, testSplitCamelCase_Short, testSplitCamelCase_Standard}; consumer.TestFileMapperTest#{testResolve_ClassNameExtraction, testResolve_JavaController, testResolve_JavaServiceImpl, testResolve_KotlinFile, testResolve_NestedPackage, testResolve_UnmappablePath})에 한해 `at`을 관찰 항목으로 두고 차이는 별도 줄로 출력하며 판정에서 제외한다. type·message는 31건에서도 엄격하게 비교하고, 31건 밖의 `at`도 엄격하게 비교한다. 이 31건은 4b 1·2회차에서 같은 목록이었고 42건 안에 모두 들어 있다.
  - **실행 시간 변동(원인 미확인)**: 서버 의존 14클래스 제외 전체 실행이 3분 39초(4b 2회차), 4분 37초(4a), 5분 3초(4b 1회차), 11분 36초(커밋 3 시뮬레이션), 12분 29초(커밋 3 HEAD)로 흔들렸다. 같은 init script와 같은 명령이며 원인은 확인하지 못했다. 이후 전체 실행마다 `build/test-results`를 `%TEMP%\bl\xml_<실행이름>\`에 복사해 증거로 보관한다.
  - **커밋 4 결과(2026-10-14)**: 4a `c928457`(테스트, `src/main` 변경 없음), 4b `ae97eb9`(`src/main` 2개 파일: `IdentifierRetentionChecker.kt` 2 추가·6 삭제, `ClarifyIntent.kt` 1 추가·1 삭제, 시뮬레이션과 KDoc 한 줄만 다름), 4c `a3fb96b`(테스트, `git diff HEAD~1 --stat -- src/main` 빈 출력). 서버 의존 14클래스 제외 전체 실행: 4a 407/42/5(clarify 162/1/3, 하네스 7블록 `EXACT_MATCH`), 4b 407/42/5(2회 실행, 엄격 그룹 5종 `EXACT_MATCH`, 제외 그룹 2종 `MATCH_EXCLUDING`·`excludedLinesDiffer=true`), 4c 408/42/5(clarify 163/1/3, 하네스 4b와 같음, 실패 메시지 판정 차이 0). 하네스 `RefinedReq:` 줄은 예측한 원문과 바이트가 같았다(`survey_admin (Intent=LlmOn)`: `…Bizgo 연동 API 신규 개발`, `APC DEM Isolated (Intent=LlmOn)`: `…최근이력 1건 조회 기능 개발`). 자체 검증 `--real` 24/24. 4b 1회차에서 `JBR.<clinit>` NPE 31건 중 1건의 `at`이 달랐던 것은 관찰 항목으로 처리했다(위 비교 규칙). 비교 도구 `msgs_sim`(커밋 3 전 시뮬레이션)과 기준의 판정 차이 2건(`ICT#…ForMetrics`, `Snapshot#testCaseBFullE2EAmplificationMeasurement`)은 그 시점의 기지 결과이며 정상이다.
  - **커밋 4 변이 결과(4c 트리, `IdentifierRetentionCheckerTest`·`Stage0SurveyAdminSnapshotTest`·`ExcludedIdentifierSubtractionTest` 범위, TEMP/`cp`, `cmp` 복원 확인, `git status -s`·`git diff -- src/main` 빈 출력)**: (a) 태그를 `effective`에만 다시 붙임 → 실패 `ICT#testCheckRetentionPreservesRawRefinedRequirementForMetrics`, `#i2b` — 예측과 일치. (b) `effective`와 `rawRefinedRequirement` 모두에 태그 → 실패 `ICT#…ForMetrics`, `#d0R`, `#i2a`, `#i2b` — 예측과 일치. **생략한 변이**: (c) 엔진 `missingBeforeFix` 비우기(커밋 3의 V1이 같은 결함을 잡음)와 하네스 변이(`i2a`와 `d0R`이 같은 결함을 맡음).
  - **남은 일(커밋 4 이후)**: `ClarifyToAnalyzeIntegrationBaselineTest`의 `:227` 라이브 경로는 실행하지 못했다(live 게이트, 컴파일만 확인, 2d 차감 영향 포함해 미검증). B-38(생성 쪽 불변식) 미구현. 정리 대상(`IdentifierRetentionCheckerTest.kt:83`, 하네스 `:801-805`, 자체 검증 T11 라벨, `ClarifyIntent.kt:11`) 미실시.
  - **커밋 4 전 점검(`git grep -n "refinedRequirement" -- src/main`)**: 분석 입력에 `ClarifyIntent.refinedRequirement`의 내용을 쓰는 곳은 `AnalyzeInputResolver.kt:58`(audit-first: `audit?.rawRefinedRequirement ?: inMemoryIntent.refinedRequirement`)뿐이고 그 밖의 직접 사용은 없다. 나머지는 값을 넘기기만 하거나(`ClarifyIntentStore.kt:137`·`:225`·`:264` 저장/로드 DTO, `Stage0ClarificationEngine.kt:1003`·`:1050` 대입) 생성 쪽(`IdentifierRetentionChecker.kt`)이다. `verifyAndRetain`(`IdentifierRetentionChecker.kt:211`, "기존 호환 메서드")은 `src/main`에 호출처가 없다. `webview/`에는 `refinedRequirement` 사용이 없다.
- **커밋 4 불변식 (합격 기준에 단언으로 포함)**: 태그를 지운 뒤에도 (1) `missingBeforeFix`는 지금처럼 채워져야 하고, (2) `rawRefinedRequirement == refinedRequirement`가 성립해야 한다.
- **상태**: 진행 중 (우선순위 P2, 2b·2c·2d, 도구 옵션, 커밋 3, 커밋 4 완료)

---

### [B-37] `UnresolvedKind` 라벨이 "그래프 존재 여부"이고 "사용자의 신규 생성 의도"가 아님
- **현상**: APC Transit Card LlmOff 시나리오에서 사용자가 "SAPACMM0802S01 기존서비스 참고"라고 썼는데 `unresolvedItems`에는 `(SAPACMM0802S01, NEW_CREATION, USER_UTTERED)`로 나온다(하네스 프로브 출력).
- **원인(코드)**: `resolveUserUtteredIdentifiers`가 그래프에서 못 찾은 식별자 중 `^[A-Z][a-zA-Z0-9]+$` 또는 `.` 포함 토큰을 모두 `LinkHint.NewCreation`·`PENDING`으로 만든다(`Stage0ClarificationEngine.kt:159`, `:206-207`). 이 경로는 발화의 의미(신규/기존)를 보지 않는다. 해당 식별자가 그래프에 실제로 없는지는 **미확인**(코드 경로에서 도출).
- **같은 뿌리의 관찰**: LlmOff에서는 그래프에서 매칭된 항목도 `PENDING`이라 `EXISTING_REF`로 남는다(`:82`의 `matchedVerdict`). LlmOn에서는 `CONFIRMED`라 미확정에서 빠진다. 같은 SR의 미확정 목록이 LLM 유무에 따라 달라진다.
- **2c와의 관계**: 2c의 미확정 섹션은 이 라벨을 `(NEW_CREATION)`처럼 그대로 Stage 3 검증기에 노출한다. 라벨이 틀렸을 때 검증기가 오해하는지는 **미확인**이며 실제 LLM 측정이 필요하다.
- **2c에서 하지 않는 것**: 라벨 교정과 숨김. 후속 선택지: (A) 헤더 문구에 "그래프 기준 분류" 명시 (B) 항목에서 KIND 제거 (C) Stage0에서 라벨 판정 보정.
- **재현**: 하네스 APC Transit Card LlmOff 시나리오.
- **상태**: Open (우선순위 P3)

---

### [B-38] `unresolvedItems`가 `excludedFiles`와 겹치지 않는다는 생성 쪽 불변식이 없음
- **현상**: `buildClarifyIntent`는 `excludedFiles`를 `REJECTED` + `ExistingRef.filePath`로(`Stage0ClarificationEngine.kt:1004-1007`), `unresolvedItems`를 `PENDING`으로(`:1018-1019`) 각각 만든다. 같은 경로를 가리키는 서로 다른 항목(하나는 `REJECTED`, 하나는 `PENDING`)이 `state.items`에 공존하면 한 파일이 제외이면서 미확정이 되는 모순이 가능하다.
- **관측**: 확보한 프로브(기준선 5종, 4조건)에서는 겹침이 없다. Condition3의 `SurveyServiceImpl`은 `excludedFiles`에 있고 `unresolvedItems`에 없다. 엔진이 이런 공존을 만들 수 있는지는 **미확인**.
- **2d와의 관계**: 소비 쪽에서는 `unresolvedItems`를 차감하지 않는다(2d의 D7P가 이 결정을 고정). 불변식은 생성 쪽에서 지켜야 한다.
- **해결 방향(후속)**: 생성 쪽은 경로 정보를 가지므로 `ExistingRef.filePath` 경로 동일성을 기준으로 한다. `unresolvedItems` 중 `kind == EXISTING_REF`인 항목의 `filePath`가 `excludedFiles`에 있으면 안 된다는 불변식을 `buildClarifyIntent`에서 단언하고, 겹치면 생성 시 제거한다. `UnresolvedItem.filePath`(`ClarifyIntent.kt:57`)와 `excludedFiles`는 같은 `ExistingRef.filePath`(`Stage0Models.kt:28`)에서 오므로 형식이 같다. 식별자 이름 기반 일치 규칙(2d)은 소비 쪽 규칙이라 쓰지 않는다. 엔진 경로 테스트로 고정.
- **상태**: Open (우선순위 P3)


---

### [B-39] 실사용 평가 세트(고정 시나리오 + 실제 LLM, GT Recall·누락 식별자·제외 준수·턴 수·응답 시간 기록)
- **목적**: mock LLM 하네스는 회귀만 보고, 사용자가 받는 결과는 재지 못한다. 고정 시나리오를 실제 LLM으로 돌려 사용자 관점 지표를 기록하고, 첫 측정으로 기준값을 잡은 뒤 이후에는 직전 값과 비교한다. 판정(단언)과 합격 기준은 두지 않는다.
- **고정 조건**: 서버 `http://vllm.ixpertops.cloud`, 모델 `Qwen/Qwen3.8-27B-FP8`, 온도 0.1(`OpenAIClient` 기본값). 시작 시 `GET /v1/models`로 모델이 목록에 있는지 확인하고 결과를 보고서 머리말에 적는다. 없으면 평가를 시작하지 않고 그 사실만 출력한다.
- **스모크 호출 결과(2026-10-14)**: `/v1/models` HTTP 200, 모델 3개(`qwen3.8-27b`, `Qwen/Qwen3.8-27B`, `Qwen/Qwen3.8-27B-FP8`, 최대 길이 65536). `chat/completions`에 `Qwen/Qwen3.8-27B-FP8`와 `qwen3.8-27b`를 보내면 둘 다 HTTP 200이고 응답의 `model` 필드는 둘 다 `qwen3.8-27b`로 찍힌다(두 id가 같은 모델의 별칭인지는 서버 설정을 보지 못해 미확인). IDE 밖에서 `OpenAIClient`(설정을 못 읽으면 같은 URL·모델·온도 기본값)로 `chat` 1회 740ms, `chatWithTools` 1회 3310ms로 둘 다 성공했고 tool call(`report_color:{"color": "red"}`)이 돌아왔다. 그래서 평가는 운영 경로의 `OpenAIClient`를 쓴다. 기존 `Stage0ThreeScenarioLiveLlmObservationTest.DirectVllmClient`는 모델 id `qwen3.8-27b`·읽기 60초 고정·`chatWithTools` 없음이라 쓰지 않는다.
- **시나리오 3그룹(집계는 따로)**: 개발용 5개(D1~D5: 기존 하네스·라이브 관찰 테스트의 SR·발화·GT, GT 판정은 기존 하네스 규칙 유지: 경로가 이름을 대소문자 무시하고 포함, D1·D5는 MODIFY만), 합성 3개(S1 기존 기능 수정 member-market 채팅 안 읽은 수, S2 신규 개발 member-market 관리자 회원 정지, S3 제외 요청 포함 survey_admin 본인인증 결과 처리. 발화 1턴, GT는 파일 경로), 실제 업무 SR(자리만: `real_scenarios.json`이 비어 있음). GT가 초안인 동안 합성 그룹의 Recall은 확정값으로 보고하지 않고 `(초안)`으로 표기한다. GT가 없는 SR은 첫 측정 보고서의 상위 30 후보 표에서 사용자가 표시해 GT로 삼고 `gtStatus`를 `confirmed`로 바꾼다.
- **합성 GT 확인에서 나온 것(실제 파일을 열어 확인)**: S1은 `ChatService.getMyChatRooms(:32-40)`가 방마다 `ChatRoomRepository.countUnreadMessages(:24-26)`를 호출하고 `ChatRoomResponse.unreadCount`에 담는 흐름이 확인되어 4개를 GT로 두고, 쌍인 읽음 처리(`ChatMessageRepository.markAllAsRead`, `ChatMessage.isRead`), 웹의 `unreadCount` 사용처 3곳을 추가 후보로 남겼다. S2는 `MemberStatus`에 `SUSPENDED`가 이미 있고 `AdminMemberResponse`도 `status`를 이미 노출하며(참조용), `Member.status`는 `:57`에 있으나 상태를 바꾸는 메서드가 없고 `AuthService.login(:62-74)`에 상태 검사가 없어 차단 로직을 넣을 곳이 된다. `MemberStatus` 사용처는 `Member.java:57` 한 곳뿐이다. S3은 `KmcisController.verifyAuth.do(:44-46)` → `KmcisServiceImpl.verifyAuth(:107)`·`processAuthResult(:306)`를 GT로 두고, `UserServiceImpl`(`verifyOnly :103`)과 `kmcis` 참조 JS/JSP를 추가 후보로 남겼다. S3에서 발화의 "로그인 쪽 컨트롤러"가 `IpsController`(`loginController :207`) 항목으로 연결되어 `excludedFiles`에 들어가는지는 **미확인**이라 기대 제외 파일로 적고 결과를 기록한다. D5(제외·범위 제약 발화)도 `excludedFiles`가 실제로 채워지는지 미확인이라 결과만 기록한다.
- **기록 항목**: GT Recall(상위 30·상위 10, 오탐 수는 GT가 불완전하면 상한), 누락 식별자(`expectedIdentifiers`, `missingBeforeFix`, 2d 차감, 미확정, 각 항목이 Stage 3 검증기 호출의 `## 요구사항` 구간에 실제로 들어갔는지), 제외 준수(제외 확정 파일, 최종 목록 포함 여부, 제외 파일명이 Stage 3 섹션·본문에 나타나는지), 턴 수(엔진 질문 수, 답한 질문 수, **대본이 엔진 질문보다 먼저 끝난 횟수**: 대본을 모두 처리한 뒤에도 `openQuestion`이 남고 `isReadyForStage1`이 false), 응답 시간(시나리오별 중앙값·최대값, 모든 호출 기준), 타임아웃·실패·null 횟수, 3회 반복 안정성(Recall 최소·평균·최대). 보고서는 숫자 표와 함께 시나리오마다 정제문 원문과 상위 10개 파일 목록을 싣는다(json에는 상위 30 전체).
- **호출 상한**: 120초로 시작하고 첫 측정 결과로 조정 여부를 판단한다. 초과하면 `timeout`으로 기록하고 평가는 계속한다(`chat`은 null, `chatWithTools`는 `SocketTimeoutException`). 포기한 호출의 연결은 `OpenAIClient`의 자체 읽기 제한(설정 기본 300초)까지 남을 수 있다. 타임아웃 횟수 자체가 사용 경험 지표다.
- **구현 범위(최소)**: `src/test`만 바꾼다(`src/main` 변경 없음). `agent/eval/`에 `EvalScenarios`, `MeteredLlmClient`, `LiveEvalTest`, `EvalReport`, `EvalModels`와 `resources/eval/scenarios.json`, `real_scenarios.json`. 명령 하나: `./gradlew test --tests "net.ib.ixpert.ops.wuwagent.agent.eval.LiveEvalTest" -DrunLiveLlmTests=true -i`(`runLiveLlmTests` 게이트라 기본 `./gradlew test`에는 실행되지 않는다). 설정값: 메타그래프 경로는 `-Deval.graphs=<graphs.json>` 하나(키→경로 맵, 기본은 현재 쓰는 네 경로). 보조 옵션 `eval.real`, `eval.only`, `eval.runs`, `eval.callTimeoutSec`, `eval.out`(기본 `build/eval-reports`). 평가 도구의 자체 검증과 변이는 하지 않는다. 작성 후 컴파일(`compileTestKotlin`)과, 시나리오 JSON의 경로가 그래프·디스크에 있는지만 일회성 스크립트로 확인했다(`inGraph` 선언 3곳을 이 확인으로 바로잡음).
- **미확인/한계**: 대본의 사용자는 가상이라 "답한 질문 수"가 실제 사용자가 답해야 하는 횟수와 같은지는 첫 측정에서 확인한다. Stage 3 전달 확인은 Stage 3 검증기 호출이 한 번이라도 있어야 관찰되며, 없으면 `미관찰`로 적는다. 총 소요 시간은 호출 수를 몰라 추정하지 못했다. 합성 GT는 파일 이름·메서드·한글 주석·grep 근거이며 사용자 확인 전까지 초안이다.
- **남은 일**: 첫 측정(별도 지시 후 실행), 합성·실제 SR의 GT 확정, 호출 상한 조정 판단.
- **UI 경로 전환(2026-10-15)**: 실제 화면의 입력창은 `/clarify-utterance`(`App.tsx:295`) → `processUtterance`(엔진 `:907`), "확정" 버튼은 `/analyze-confirm`(`App.tsx:312-316`, `userStatement: null`) → `processTurn`이다. 평가 도구는 그동안 대본 발화를 `processTurn(userStatement)`로 보내 UI와 달랐으므로(평가 도구 `LiveEvalTest.kt:130-133`의 `processUtterance`, `:165-170`의 `processTurn(null, 완결 선언)`로 맞춤) 이전 clarify 단계 값(질문, 제외 확인 질문 등)은 UI 결과가 아니다. 체크박스 거부(`verdictUpdates` 변경)는 평가 도구에서 쓰지 않는다. 분석 단계 호출은 두 경로가 같다. 기준선 재측정은 `live_eval_20261015_103120`(D1~D5, S1~S3, 확인용 D5E·D5N, 덤프 `dumps_ui/`).
- **`processTurn` 발화 처리는 UI 미도달**: `Stage0ClarificationEngine.kt:402-445`(대기 중 제외 후보에 대한 "네/응" 규칙)와 `:446-`(제외 후보 탐색·질문)은 UI에서 도달할 수 없다(확정 버튼이 `userStatement: null`). 의존 테스트: `ExcludeComponentDialogueTest`, `ExcludedIdentifierSubtractionTest`, `Stage0EngineRoutedBaselineHarnessTest`, `Stage0RouterAndContractTest`, `Stage0SurveyAdminSnapshotTest`, `Stage0ThreeScenarioObservationTest`, `Stage0UserIdentifierDisambiguationTest`, `Stage0UtteranceProcessTest`, `Stage0GraphScannerTest`, `Stage0B12ReAskLiveLlmObservationTest`, `Stage0ThreeScenarioLiveLlmObservationTest`. 보강하지 않음, 정리 여부 미정.
- **UI 경로 기준선에서 확인된 사용자 영향(2026-10-15, 코드 변경 없음)**: (1) 제외 발화가 `ADD_CONSTRAINT(SCOPE_LIMIT)`로만 처리되는데 화면 안내는 "알림톡 배치 모듈은 제외하고 SurveyServiceImpl.java만 수정 대상으로 한정하겠습니다"처럼 제외를 한 것처럼 말한다(D5·D5E·D5N·S3 모두 `excludedFiles = []`). "네, 제외해 주세요"는 `UNKNOWN`(어떤 파일인지 되묻는 안내), "네"는 `UNKNOWN`이고 "확인했습니다"라고 답한다. (2) 같은 '브랜드메시지 … 외부 연동 API' 질문이 사용자가 이미 답한 뒤에도 다시 나온다(D1, D5). (3) D1에서 Stage 3 호출이 315초(2615청크)로 운영 기본 상한 300초를 넘어 분석이 실패했다. (4) D4에서 시드 선정 스트림이 `finish_reason` 없이 끊겨(응답 728자 도구 인자) `JsonSyntaxException`으로 분석이 실패했다. 분석 단계 호출은 재시도 없이 예외로 끝난다.
- **상태**: 구현 중(첫 측정 전, 코드는 이 기록 다음 커밋)

---

### [B-40] 단계 독립성 원칙, clarify 목적, clarify 출력 계약 초안
- **원칙(이후 모든 설계·판정의 기준, 2026-10-15)**: "각 단계는 독립 실행 가능, 연결 시 계약 준수." (1) analyze는 clarify 없이도 SR만으로 실행 가능해야 한다. (2) clarify 결과가 있으면 analyze는 확정 항목(제외·대상)을 필터·고정 입력으로만 쓰고 문장 재해석으로 결과를 바꾸지 않는다. (3) analyze 입력은 `ResolvedAnalyzeInput` 하나이고 분기는 `AnalyzeInputResolver`에만 둔다. (4) 결과에 입력 출처(clarify 경유 여부, 확정/미확정, 문장 해석으로 빠진 파일)를 표시한다. (5) clarify를 기본 흐름으로 둘지는 측정으로 결정한다. 판단 기준은 "사용자가 결과를 믿고 편하게 쓸 수 있는가"다.
- **clarify 목적 정의**: "사용자가 원하는 변경을 오해 없이 이해하고, 그 이해를 사용자가 확인할 수 있게 한다. 사용자 영역(의도·범위·제약·완료 기준)은 모호할 때만 묻고, 시스템 영역(파일·클래스 매핑)은 묻지 않고 찾은 결과를 보여 준다."
- **현재 코드 사실(읽기만, 2026-10-15)**: (a) 확정 시 넘어가는 값 중 analyze가 실제로 쓰는 것은 `excludedFiles`(REJECTED 항목)뿐이다. `anchorTokens`·`constraints`·`userStatements`·CONFIRMED 판정은 분석 파이프라인·discovery·service에서 읽히지 않는다(grep 0건). `excludedFiles`는 Stage 3까지 끝난 최종 목록에 대한 사후 필터(`RequirementAnalysisPipeline.kt:224-229`)와 계약 저장(`:441-446`)에만 쓰이고, discovery 패키지에는 제외·거부 입력이 없어 시드·후보·Stage 3 입력에는 제외 파일이 그대로 들어간다. (b) 정제문(제약 문장 포함)이 들어가는 곳: 생성 `Stage0ClarificationEngine.kt:1069-1091` → `AnalyzeInputResolver.kt:58` → `RequirementAnalysisPipeline.kt:154` → 시드 선정 `AgenticSeedSelector.kt:59`, 신규 파일 탐지 `NewFileDetector.kt:99`, Stage 3 `RequirementAnalysisPipeline.kt:259-282` → `FileRelevanceVerifier.kt:237-239`. 문장 때문에 파일이 빠질 수 있는 지점은 시드 선정 응답(S3: "로그인 관련 컨트롤러는 수정 대상에서 제외"를 `KmcisController`에 적용해 시드에서 뺌)과 Stage 3 판정 사유(D1·D5: "요구사항에 명시되지 않았다", "~만 수정")다. (c) SR만으로 analyze하는 진입점: UI `/analyze`(`WebviewActionRouter.kt:187-191`, `executeAnalyzePipeline(…, null)` → `AnalyzeInputResolver.kt` 비-인텐트 분기), 테스트(`AnalyzeInputStructureTest`의 standalone 케이스, `AnalyzeSectionsTest`의 standalone 케이스, `PipelineE2ETest.kt:174`, `IntegrationAnalyzeTest.kt:168`, `PipelineGuardIntegrationTest.kt:136`)가 있다. 평가 도구는 항상 clarify를 먼저 거치며 SR만 분석하는 경로가 없다.
- **clarify 출력 계약 초안(구현 금지)**: 전달 객체는 `ClarifyIntent` 하나, analyze 입력은 `ResolvedAnalyzeInput` 하나(분기는 `AnalyzeInputResolver`). **사용자 영역 필드(중심)**: 의도(정제문, 원문 보존), 범위(한정 문장의 확정 여부, 제외 요청 목록: 문장·상태 확정/미확정), 제약(`constraints`: kind·value·근거 구문), 완료 기준(신규 필드, 사용자가 말했을 때만 채움, 비면 "미정"). **시스템이 제시, 사용자가 확인하는 필드**: 파일·클래스 매핑 후보(경로·근거·상태 제시/확인/거부), 확정 제외 파일(`excludedFiles`), 미확정·누락 식별자. **비어 있을 때**: 모든 필드가 선택이며 analyze는 SR만으로 같은 파이프라인을 실행한다(`ResolvedAnalyzeInput`의 `effectiveRequirement`는 SR, 제외 없음). **채워져 있을 때 지킬 것**: ① 확정 제외는 시드·후보·Stage 3 입력·최종 목록 모두에서 하드 필터, ② 확정된 대상·범위 한정은 고정 입력, ③ 정제문은 의도 설명용이며 파일을 빼는 근거로 쓰지 않는다(제외 문장은 프롬프트에서 분리하거나 참고로만 표시), ④ 미확정은 필터로 쓰지 않고 결과에 표시만 한다. **결과 표시**: 입력 출처(clarify 경유/단독), 제외 확정·미확정(행동 안내 포함), 문장 해석으로 빠진 파일(시드·Stage 3 판정 사유가 제외 문장을 인용한 경우), 질문 이력. 제외 확정·미확정 표시(첫 단계)는 구현됨(B-39), 입력 출처와 문장 해석 표시는 이 계약 이후.
- **질문 품질 분류(덤프 3종: `dumps/`(processTurn 경로 151837), `dumps_d5e/`(155344), `dumps_ui/`(UI 경로 103120))**: 서로 다른 엔진 질문은 4개다. Q1 "'브랜드메시지' 관련 기능은 외부 연동 API(예: Bizgo 등) 또는 별도 신규 모듈을 통해 처리됩니까?"(사용자 영역: 구현 방식·범위; 코드 몰라도 답 가능 O; 답에 따라 결과 달라짐 미확인(제약 분류에만 영향); 사용자가 이미 말함: D1은 Bizgo 신규 생성을 말했고 D5계열은 "SurveyServiceImpl만 수정"이 답을 함축해 X). Q2 "'SAPACMM0802S01'은 프로젝트에 존재하지 않습니다. 새로 생성할 파일인가요?"(시스템 영역: 파일 존재 확인; 가능 O; 결과 달라짐 X(D3 최종 목록이 답과 무관하게 같음); 이미 말함 X(SR에 "기존서비스 참고")). Q3 "'알림톡 배치는 건드리지 말고' 관련 파일(Job, Runner)을 분석 대상에서 제외할까요?"(혼합: 제약은 사용자 영역, 파일 매핑은 시스템 영역; 가능 O; 달라짐 O; 이미 말함: 파일 특정은 안 말함 O; UI 경로에서는 발생하지 않음). Q4 "'건드리지 마세요'에 해당하는 클래스나 패키지명을 알려주세요."(시스템 영역; 코드 지식 필요 X; 달라짐 O; 이미 말함 O("로그인 쪽 컨트롤러"를 말함); UI 경로에서는 발생하지 않음). **집계(질문 18건)**: 사용자 영역 13건(Q1), 시스템 영역 3건(Q2 2건, Q4 1건), 혼합 2건(Q3). 이미 말했거나 같은 질문이 반복된 중복 15건(Q1 13건, Q2 2건). UI 경로(11건)는 Q1 10건 + Q2 1건이고 전부 중복이다(같은 Q1이 한 번 답한 뒤에도 다시 나옴).
- **비교 측정 계획(실행은 별도 지시 후)**: 같은 시나리오를 세 방식으로 돌린다. (A) SR만 analyze(`AnalyzeInputResolver.resolve(sr, null)` → `pipeline.analyze(clarifyIntent = null)`), (B) SR과 대본 발화를 한 문장으로 합쳐 analyze 단독 입력으로, (C) clarify를 거침(현재 UI 경로, 기존 `live_eval_20261015_103120` 값 재사용 가능). 지표: Recall@10·@30(기존 GT 규칙), 제외 준수(기대 제외 파일이 최종 목록에 있는지: D5 계열 `AlimtalkTemplateBatchJob`·`Runner`, S3 `IpsController`), 총 시간(clarify+analyze), LLM 호출 수, 분석 실패. 대상: 첫 라운드 D2, D3, D5, S2, S3(발화 있는 5개, A·B 10회 약 1시간), 이후 D1, D4, S1. 구현은 평가 도구에 `eval.mode`(sronly/merged/clarify) 옵션을 더하는 테스트 전용 변경이며 `src/main` 변경은 없다.

- **계약 초안 추가(2026-10-15, 문서만, 구현 금지)**: (a) clarify 결과물은 사용자가 승인하는 문서 한 장이다. 칸은 의도, 범위, 제외(확정/미확정), 완료 기준, 시스템이 찾은 파일(접힘)이다. 정제문은 이 문서에서 파생된 값이다. (b) 질문은 선택지 형식이 기본이다(예: 제외 [제외 / 포함 / 일부만]). 정해진 칸(범위, 제외, 완료 기준) 중 빈 칸에서만, 최대 3개를 묻는다. 이미 말했거나 채워진 칸은 코드에서 질문을 차단한다. 자유 답변 해석은 선택지로 답할 수 없는 경우에만 쓴다. 목표 지표: 질문 분류의 중복 질문 15건을 0으로. (c) 분석 시작 전에 이 문서를 한 화면으로 보여 주고 확인받는다(제외 확정·미확정 안내도 여기에 표시). 승인된 문서가 analyze 계약이다. 확인을 건너뛰면 SR만으로 analyze한다(현재 경로 유지). (d) 질문 전 탐색은 LLM 없이 그래프 검색을 우선하고, 예상 소요 시간을 보여 준다. (e) 바뀌는 위치(파일:라인 목록만, 구현은 4순위 "계약 구현"): 엔진 `Stage0ClarificationEngine.kt:402-445`(`processTurn` 답변 해석, UI 미도달 경로라 정리 대상), `:907-980`(`processUtterance`, 칸 기반 질문·답변), `:593-600`(`openQ` 결정과 `checkOpenQuestionTrigger`, 빈 칸에서만 최대 3개), `:993-1075`(`buildClarifyIntent`, 문서 칸 구성), `:1069-1091`(`refineRequirement`, 정제문을 문서에서 파생); 번역기 `ClarifyUtteranceTranslator.kt:11-17`, `:40-140`, `:180-215`(선택지 응답 우선, 자유 응답은 보조); 라우터 `WebviewActionRouter.kt:165-175`, `:240-250`, `:290-300`(화면 payload에 문서 한 장), `:187-191`(`/analyze` 단독 경로 유지), `:193-324`(확정·승인 흐름), `:1488-1530`(`executeAnalyzePipeline`, 승인 문서를 analyze 입력으로); 화면 `webview/src/App.tsx:295`(입력창), `:312-316`(확정 버튼 → 승인), `:440-470`(질문·선택지 표시), 문서 한 화면 컴포넌트 신규; 진입 `WebviewActionRouter.kt:124-175`(`/clarify` 진입 시 `analyze_start` 메시지에 예상 소요 시간).
- **비교 측정 계획 수정(2026-10-15, 문서만)**: 위 (A) SR만 analyze, (B) SR+발화 합친 문장 analyze, (C) clarify 경유에 (D) Plan 방식을 추가한다. (D)는 평가 도구 시뮬레이션이며 테스트 전용이다: SR → LLM 없이 그래프 탐색 → 승인 문서 한 장(의도·범위·제외·완료 기준·시스템 파일)을 만들고 → 대본으로 1회 승인(빈 칸에서만 최대 3개 선택지 질문, 이미 채워진 칸은 질문하지 않음) → 승인 문서를 analyze 입력으로 실행. 지표는 Recall@10·@30, 제외 준수, 총 시간, LLM 호출 수, 분석 실패에 **중복 질문 수**와 **사용자 답변 턴 수**를 추가한다. 실행 순서는 그대로: Stage 3 V2 → 스트림 재시도 → 비교 측정(A~D).

---

### [B-41] 제외 결과 표시 구현 결과, Stage 3 출력 축소 실험, 스트림 절단 재시도 설계
- **제외 결과 표시 구현(2026-10-15, 커밋 `d9ba1d8`, 동작 변경 없음)**: `ClarifyIntent.unconfirmedExclusions`(제약 추출의 `EXCLUDE_COMPONENT` 문장 중 후보 파일이 모두 제외 확정되지 않은 것, 후보는 `resolveExclusionCandidates` 결과), 분석 요약 머리의 "제외 확정"·"제외 요청이 확정되지 않았습니다 … 분석 결과에 포함될 수 있습니다. 제외하려면 /clarify 단계에서 목록의 체크를 해제하세요." 줄(`exclusionSummaryLines`), 화면 payload의 `excludedFiles`·`pendingExclusions`(`exclusionView`). 수정 파일은 `ClarifyIntent.kt`, `Stage0ClarificationEngine.kt`, `ClarifyIntentStore.kt`, `RequirementAnalysisPipeline.kt`, `WebviewActionRouter.kt`뿐이고 `AnalyzeInputResolver.kt`와 `App.tsx`는 건드리지 않았다(`App.tsx`를 바꾸면 추적되는 `src/main/resources/webview/index.html`이 다시 생성됨). 검증: 단위 테스트 6개 추가(미확정 기록, `SCOPE_LIMIT` 제외, 대상 불명, 확정 시 미확정 아님, 요약 줄, 과거 저장 파일 로딩), 서버 제외 전체 실행 416/42/7(clarify 169/1/3, 실패 메시지 판정 차이 0, 하네스 불변). 예측표(D1~D5, S1~S3, D5E, D5N)와 평가 세트 재실행(`live_eval_20261015_103120` 이후 `120309`, 요약 표시는 `122454`)이 일치: 미확정은 D5·D5E·D5N에서 `'알림톡 배치는 건드리지 말고'`(후보 `AlimtalkTemplateBatchJob.java`, `AlimtalkTemplateBatchRunner.java`), S3에서 `'건드리지 마세요'`(대상 파일 특정 못 함)뿐이고 `SurveyServiceImpl만 수정`(SCOPE_LIMIT)은 잡히지 않았다. 한계: 이 줄은 분석 결과에 나오므로 "확정" 뒤에 보이고, 확정 전 화면에는 확정 제외·대기 후보(payload)만 있다. `App.tsx` 표시는 미구현. D1은 두 번 모두 Stage 3 호출이 300초를 넘어 분석이 실패해 요약 줄은 확인하지 못했다. 평가 도구는 화면 표시를 `onChunk`로 받아야 한다(`RequirementAnalysisResult.summary`는 판정 근거 요약일 뿐).
- **Stage 3 출력 구성(D1 응답, 7273자 / 2615청크)**: 파일 경로 반복 24%, UNNECESSARY 사유 24%, REQUIRED 사유 10%, 전체 요약(reasoning) 11%, 키·구조·들여쓰기 32%. 후보 27개 중 21개가 제거(UNNECESSARY)였고 사유와 경로를 모두 다시 쓴다. REQUIRED 사유는 분석 결과 표의 "수정 사유"로 사용자에게 보이지만 UNNECESSARY 사유는 보이지 않는다. 출력은 도구 호출 인자 JSON이며 reasoning 토큰은 없고(`enable_thinking: false`도 효과 없음) 속도는 8.4청크/초로 고정이다.
- **축소안 재생 결과(TEMP 변형 요청, 덤프 8개 재생, `build/eval-reports/stage3_variants_20261015032509.json`, 스크립트 `stage3_variants.js`)**: V1 = 후보에 [번호]를 붙이고 제거 대상은 번호만(사유 없음), 수정 대상은 번호+사유, 한 줄 JSON. V2 = V1 + 사유 40자 이내, 요약 두 항목 이내. 합계 출력 11327청크 → V1 4648(−59%) → V2 1810(−84%), 시간 1353초 → 568초 → 233초. D1은 315초(운영 상한 300초 초과) → V1 119초, V2 49초. 남은 파일 목록이 원본과 같은 시나리오: V2 6/8(D1, D2, D4, D5, S1, S3), V1 4/8. 다른 경우: V2는 D3에서 원본이 제거한 GT `ACMBTBAPC024DEM.java`를 유지(GT 제거 1→0)하고 S2에서 `MemberRepository.java`, `MemberResponse.java`를 추가로 유지(남은 5→7), V1은 D1에서 설문 파일 5개(GT 포함)를 유지(GT 제거 3→1)하고 S3에서 GT `KmcisService.java`를 제거(0→1). 후보 안의 GT 25개 중 제거 수는 원본 6, V1 4, V2 5. 미확인: V2의 40자 사유가 사용자에게 보이는 "수정 사유"로 충분한지(응답 원문 사유는 저장하지 않았다), 파서(`FileRelevanceVerifier.kt:162`)와 프롬프트 변경 후 전체 파이프라인 결과.
- **서버 동시 처리(D3 V1 요청 7회: 1/2/4 동시)**: 1건 8.38청크/초, 2건 동시 각 8.37·8.34(처리 속도 거의 그대로), 4건 동시 각 7.64~7.79(약 8% 감소, 합계 처리량 약 31청크/초로 단독의 약 3.7배). 후보를 나눠 Stage 3을 병렬 호출하면 벽시계 시간을 크게 줄일 수 있다(미구현, 별도 설계).
- **제외 안내 문구 보정(2026-10-15, 커밋 `e98d919`)**: 후보가 있으면 "…제외하려면 /clarify에서 해당 파일의 체크를 해제한 뒤 확정하세요.", 후보가 없으면 "…대상 파일을 찾지 못해 제외하지 못했습니다. 아래 결과 목록에 해당 파일이 포함됐는지 확인하세요." 확인 사실: D5의 clarify 후보 목록(체크박스)에는 `AlimtalkTemplateBatchJob`·`Runner`·`Repository`가 있고(평가 설정·UI 기본 설정 모두), S3에는 `IpsController`·`KmcisController`가 없다. 결과 화면에는 파일을 제외하는 UI가 없다(`AnalyzeFileReviewForm.tsx`의 "✗ 제외" 버튼은 정의만 있고 사용처가 없음). 파일을 체크 해제할 수 있는 곳은 분석 도중 Stage 3 확인 카드(`Stage3ConfirmationForm.tsx:36-110`, 라우터 `WebviewActionRouter.kt:374-380`, 파이프라인 `RequirementAnalysisPipeline.kt:248-250`, 5분 타임아웃)뿐이며 이 카드에는 미확정 제외 안내가 없다(계약의 "분석 전 한 화면 확인"에서 다룸).
- **Stage 3 판정 규칙 재계산(저장된 응답, D1·S2·S3의 V1·V2)**: R1(requiredFiles에 없으면 제거), R2(removedFiles에 없으면 유지), R3(제거에만 있을 때만 제거, 둘 다 없으면 유지+누락 표시) 모두 남은 수와 GT 제거 수가 같았고(D1 6/3, S2 V1 6/0·V2 8/0, S3 V1 9/0·V2 4/0) 두 목록 어디에도 없거나 양쪽에 있는 후보는 0개였다.
- **Stage 3 안정성 재생(덤프 8개 × 3회, 원본 A0·V2·V2+ref, 72회, 동시 4개, `stage3_stability_20261015042351.json`)**: 3회 남은 목록이 같은 시나리오 수 A0 7/8, V2 7/8, V2+ref 6/8, 서로 다른 목록 수 합계(최소 8) A0 9, V2 9, V2+ref 11. V2의 변동은 A0 수준이다(A0 D2 1/2/2개, V2 S1 4/4/3개). 후보 안 GT 제거 수는 A0와 같거나 적다(D3 A0 1 → V2 0, 나머지 동일, 증가 없음). 출력 청크 평균·시간 평균: D1 A0 2488·313초(3회 중 2회가 300초 초과) → V2 344·51초, D2 1873·234초 → 176·30초, D3 1014·126초 → 298·40초, D4 999·123초 → 233·32초, D5 1330·164초 → 143·22초, S1 958·119초 → 214·30초, S2 1281·158초 → 279·41초, S3 1179·153초 → 207·27초. ref 채움은 D1 100%(18/18), 나머지 0%이고 V2+ref는 S2(6/7/7)·S3(4/9/10)에서 목록이 흔들려 안정성이 낮다. 두 목록 누락·중복 후보는 V2 계열 48회 모두 0개.
- **스트림 절단 재시도 설계(구현 금지)**: 현상은 D4의 시드 선정 스트림이 `finish_reason` 없이 끊겨(도구 인자 728자) `AgenticSeedSelector.kt:133` 부근 `gson.fromJson`에서 `JsonSyntaxException`이 나고 분석이 실패한 것이다. 원인 위치: `OpenAIClient.kt`의 `chatWithTools` 스트림 루프(`readLine()`이 null이면 정상 종료로 보고 `finishReason ?: "stop"`으로 채움, `:~397`)가 `[DONE]`이나 `finish_reason` 없는 종료를 절단으로 구분하지 않는다. 설계: 대상은 (1) `[DONE]`·`finish_reason` 없이 끝난 스트림, (2) 스트림 중 `IOException`(취소 제외), (3) 도구 호출 인자가 JSON으로 해석되지 않는 경우에 한정하고 1회만 재시도한다. 타임아웃(`SocketTimeoutException`)은 재시도하지 않는다. 바뀌는 곳: `OpenAIClient.kt`의 `chatWithTools` 스트림 처리(`:~311-435`, `[DONE]` 수신 플래그와 절단 판정, 재시도 래퍼 한 곳)와 같은 방식의 일반 `chat` 스트림(`:~96-230`), 설정·엔드포인트를 테스트에서 바꿀 수 있는 주입 지점. 호출자(`AgenticSeedSelector`, `FileRelevanceVerifier`, `NewFileDetector`)는 그대로 둔다. 테스트 계획: 로컬 가짜 서버로 (a) 첫 응답 절단 후 정상 → 호출 2회·성공, (b) 두 번 연속 절단 → 호출 2회 후 예외, (c) 응답 지연으로 읽기 제한 초과 → 호출 1회·재시도 없음, (d) 도구 인자 JSON 불완전 → 재시도, (e) 취소 중 → 재시도 없음. 평가 도구는 절단을 한 번 강제로 주입할 수 있는 옵션(프록시)으로 D4를 재현하고 재시도 횟수를 기록한다.
- **Stage 3 V2 구현(2026-10-15, 커밋 `c77e0d5` 구현, `ddcc157` 테스트)**: `FileRelevanceVerifier`가 후보 번호 기반 `requiredFiles`/`removedFiles`/`reasoning` 형식을 요청하고(사유 40자, 요약 두 항목, 한 줄 JSON, `ref` 없음), 새 형식을 우선 해석하며 구 형식(`fileVerdicts`)은 폴백으로 받는다. 판정 규칙은 R3(제거에만 있으면 제거, 요구에 있으면 유지(양쪽이어도 유지), 둘 다 없으면 유지+판정 누락 기록, 범위 밖 번호 무시), 사유 강등 기준은 10자에서 5자, 결과 요약에 "Stage 3에서 제외된 후보 N개: … (사유: 제공하지 않음)"(10개 초과 시 "외 N개")와 "판정 누락으로 유지된 후보: …" 한 줄을 표시한다. 검증: 전체 테스트(서버 제외) 422/42/7, clarify 169/1/3, 실패 메시지 판정 차이 0(`PipelineGuardIntegrationTest`의 실패는 기준 42건에 이미 같은 메시지로 있음). 덤프 8개 × 3회를 구현 코드 경로로 재생(`stage3_impl_replay_20261015_143137`): 후보 안 GT 제거 5/25(3회 모두), 판정 누락 0, 최대 47초, 그러나 서로 다른 남은 목록 수 합계는 10으로 기준 9를 1 넘었다(S1 3/5/3개, S2 6/8/6개).
- **Stage 3 확인 카드에 미확정 제외 안내 없음**: 분석 도중의 확인 카드(`Stage3ConfirmationForm.tsx`, 5분 타임아웃)에는 제외 확정·미확정 안내가 없다. "분석 전 한 화면 확인" 설계 시 이 카드의 활용 여부를 새 화면과 비교한다.
- **결과 화면 제외 기능 후보**: `AnalyzeFileReviewForm.tsx:306`의 "✗ 제외" 버튼은 정의만 있고 사용처가 없다. 결과 화면에서 파일을 제외하는 기능의 후보로 둔다.
- **V2로도 D1 설문 파일 제거(GT 3)는 남음**: 신규 파일을 독립 구현으로 보고 기존 설문 파일을 UNNECESSARY로 판정하는 동작은 출력 형식과 무관하다. 계약 구현에서 다룬다.
- **참고 패턴(ref) 정보**: V2는 사유 40자 제한 때문에 참고한 기존 클래스(예: `IbCenterApiService` 패턴)를 보이지 않는다. 신규 파일 안내 방식으로 계약 단계에서 재검토한다(실험상 `ref`를 스키마에 넣으면 S2·S3에서 남은 목록이 흔들렸다).
- **Stage 3 V2 구현 경로 재생 결과표와 판정(2026-10-15, `stage3_impl_replay_20261015_143137`)**: 덤프 8개 × 3회, 시나리오: 후보 / 3회 남은 수 / 서로 다른 목록 수 / 후보 안 GT 제거(3회) / 시간(초). D1 27 / 6·6·6 / 1 / 3·3·3 (후보 안 GT 3) / 36~47. D2 16 / 2·2·2 / 1 / 1·1·1 (GT 2) / 23~27. D3 9 / 5·5·5 / 1 / 0·0·0 (GT 5) / 32~38. D4 9 / 7·7·7 / 1 / 0·0·0 (GT 4) / 26~36. D5 16 / 1·1·1 / 1 / 1·1·1 (GT 2) / 17~20. S1 8 / 3·5·3 / 2 / 0·0·0 (GT 3) / 21~32. S2 15 / 6·8·6 / 2 / 0·0·0 (GT 3) / 36~42. S3 11 / 4·4·4 / 1 / 0·0·0 (GT 3) / 24~28. 판정 누락 합계 0, 후보 안 GT 제거 회차별 5/25, 최대 47초.
- **예측 불일치 1건**: 사전 기준은 "서로 다른 남은 목록 수 합계 ≤ 9"였고 실제는 10이었다(S1 run2에서 `ChatMessageRepository`·`ChatController`, S2 run2에서 `LoginView.vue`·`MemberResponse`가 추가로 유지). 사용자 판정으로 통과로 처리한다(기준을 완화한 것이 아니라 목적 기준 판정): (1) 흔들림이 모두 유지 쪽(추가)이고 빠진 파일이 0개다. (2) 후보 안 GT 제거가 5/25로 3회 모두 같고 기준 6/25 이하이며 판정 누락이 0이다. (3) 최대 47초로 운영 상한(300초)과 거리가 멀다.
- **비교 기준선이 같은 조건이 아니다**: 합계 9(원본 A0)와 TEMP 안정성 재생의 V2 합계 9는 TEMP 스크립트(덤프 요청 본문을 그대로 변형) 경로의 값이고, 이번 10은 구현 경로(덤프에서 요구사항·후보 목록을 복원해 `FileRelevanceVerifier`가 후보 문맥을 그래프에서 다시 만들어 프롬프트를 구성)의 값이라 요청 본문이 완전히 같지 않다.
- **추가 유지된 파일의 GT 초안 확인(2026-10-15, LLM 재호출 없음)**: S1 `ChatMessageRepository`·`ChatController`는 GT 초안에 있고 둘 다 role=reference이며(추가 후보에는 없음), S2 `LoginView.vue`·`MemberResponse`는 GT 초안에도 추가 후보에도 없다. 즉 S1의 추가 유지는 읽기 참조 파일이고 S2의 추가 유지 2개는 GT 밖 파일이다(precision에는 영향, Recall에는 영향 없음).
- **Stage 3 V2 전체 평가 1회(UI 경로, `live_eval_20261015_154520`, 총 2684초, git `804c199`, 사전 예측표 `pred_eval_v2.md`)**: 300초 초과 호출 0건, 분석 실패 0건(이전 UI 기준선에서 실패한 D1·D4가 완료). 시나리오: 총 시간(초) 새/103120 · Recall@10 새/103120 · Stage 3 시간(초)/청크 · 최종 목록 수 새/103120. D1 425/656(실패) · 13%/실패 · 66/550 · 9/-. D2 295/494 · 20%/20% · 22/170 · 2/2. D3 298/413 · 100%/80% · 43/354 · 5/4. D4 194/127(실패) · 100%/실패 · 30/248 · 7/-. D5 253/394 · 13%/13% · 19/151 · 1/1. D5E 277/419 · 13%/13% · 18/149 · 1/1. D5N 275/423 · 13%/13% · 19/151 · 1/1. S1 218/269 · 100%/100% · 31/252 · 3/4. S2 236/327 · 75%/75% · 33/261 · 8/5. S3 213/333 · 75%/75% · 23/191 · 4/4. 판정 누락 0건. 화면 표시: 제거 후보가 있는 모든 시나리오에 "- Stage 3에서 제외된 후보 N개: … (사유: 제공하지 않음)"(D1 18개 → 10개 + "외 8개"), "판정 누락으로 유지된 후보" 줄은 없음.
- **예측과 달랐던 점(2026-10-15)**: D1 Recall@10이 예측 0%가 아니라 13%(이번에는 `sql_survey.xml`을 유지), D1 Stage 3이 예측 50초 이하가 아니라 66초(550청크), 총 시간이 D1 약 390→425초, D4 약 150→194초, S1 약 180→218초, S2 약 215→236초로 예측보다 길었다. 그 밖의 예측(300초 초과 0, 실패 0, D3 100%, 나머지 Recall 같음, 판정 누락 0, 표시 줄)은 일치.
- **사용자에게 보이는 결함(코드 변경 없음, 후속 대상)**: V2의 전체 요약(reasoning)이 후보 번호를 그대로 인용해(D1: "BizgoApiService(22,23)", "SurveyDao(12), SurveyDaoImpl(8), sql_survey.xml(14)", "기존 알림톡 파일(1,2,5,6,7)") 사용자가 번호를 볼 수 없어 읽기 어렵다. 프롬프트 보정은 이번 승인 범위 밖이므로 표시 단계에서 번호를 파일명으로 바꾸는 방안(요약 문자열 후처리, 후보 순서 보관), 후보 번호를 요약에 함께 보이는 방안, 프롬프트에서 번호 인용을 금지하는 방안을 별도로 비교한다.
- **스트림 재시도에서 length 종료는 재시도하지 않음(2026-10-15)**: 도구 호출 인자가 JSON으로 해석되지 않을 때 1회 재시도하되, `finish_reason=length`(토큰 상한 종료)는 같은 요청이 같은 곳에서 다시 끊기므로 재시도 대상에서 뺐다. 지시에 없던 판단이며, 상한 종료가 실제로 잦다고 확인되면 재검토한다.
- **번호 인용 불일치 1건(백로그, 규칙 추가 없음, 2026-10-16)**: 154520 응답에서 `BizgoApiService(22,23)`은 23번이 `BizgoApiServiceImpl`이라 이름과 정확히도 접미사로도 일치하지 않아 원문 번호가 화면에 남는다(D1 요약 첫 문장). 규칙은 더하지 않고, 같은 유형이 반복되는지 다음 평가에서 불일치 건수로 본다.

### [B-43] 작업 요약 비동기화, Stage 3 E(순위만) 재생, 시드 rationale 축소 (2026-10-16)
- **합격 조건 정정**: 분석 입력에 닿지 않는 변경은 "분석 입력 동일"(정제문 동일, 변경된 값이 분석에 전달되지 않음)을 합격 조건으로 쓴다. "Recall 동일" 요구는 분석 입력·판정 로직이 바뀌는 변경에만 적용한다. 근거: 요약 비동기화 평가에서 D1 -1, S2 +1이 나왔으나 정제문이 같고 요약은 분석에 전달되지 않아 LLM 변동으로 판정했다.
- **① 작업 요약을 턴 응답 밖에서 생성(커밋 ed90c65)**: 호출 3곳(`Stage0ClarificationEngine.kt` initSession/processTurn/processUtterance)이 모두 턴 안에서 동기 호출이라 턴마다 약 21초를 기다렸다. 엔진 `deferTaskSummary` 옵션 + `TaskSummaryScheduler`(새 턴·확정 시 시작 전 요약은 LLM 호출 없이 폐기, 만드는 중이면 결과 미전달, 턴 번호로 짝맞춤)로 바꾸고, 라우터가 카드와 함께 턴 번호를 보낸 뒤 `analyze_clarify_summary`로 요약을 채운다. 웹뷰는 도착 전 "요약 갱신 중…", 실패 시 "요약을 만들지 못했습니다"를 보이고 턴 번호가 같은 요약만 반영한다(확정 버튼은 막지 않음). 하네스는 라우터와 같은 순서로 호출하고 확정 시점의 요약 상태(NONE/PENDING/READY/FAILED)를 기록한다(생각 시간 0이라 대부분 PENDING이 정상이며 판정 기준 아님). 검증: 전체 449/42/10, clarify 177/1/3, 하네스 불변. 웹뷰 변경(App.tsx 핸들러, 재생성된 index.html)은 승인 범위.
- **① 평가 결과(live_eval_20261016_111920 대 154520)**: 총 시간 2683→2139초(-20%, 시나리오별 -25~-71초), 요약 호출 25→18, 실패 0, 300초 초과 호출 0. 예측과 달랐던 점: Recall 동일 예측이었으나 D1 GT@10 1/8→0/8, S2 3/4→4/4(위 정정대로 LLM 변동, D1은 Stage 3이 설문 파일 3개를 이번엔 제거, S2는 마지막 항목 MemberController→AdminController, S3는 목록 길이 4→10으로 달랐고 GT는 같음). 요약 호출 수는 예측 20, 실측 18(D4·S1의 초기 요약이 확정이 즉시 와서 시작 전에 폐기). 하네스는 사용자 생각 시간이 0이라 화면 전달은 1회뿐이며, 실제 UI에서 확정 화면에 요약이 보이는지는 이 평가로 검증되지 않았다. 총 시간은 시나리오별 예측 ±25초 안, 합계 예측 약 2170초 대 실측 2139초.
- **② 판단 전 확인(154520 덤프, 읽기만)**: 시드 선정 마지막 호출(confirm_final_seeds) 8개 시나리오 평균 69초, content 548자(청크 217), tool args 중 seed_classes 78자, rationale 700자. 글자당 토큰을 content에서 잰 값(약 2.5자/청크)으로 환산하면 시간의 약 41%가 content, 약 50%가 rationale, 약 9%가 시드 목록·기타다(환산값). rationale은 시드 목록 뒤에 생성되고 선정에 쓰이지 않는다(`discoveryResult.metadata.reasoning`은 Stage 3 파싱 실패 시 표시와 warnings에만 사용).
- **② rationale 축소(커밋 e34b94d, 806f241의 재생 도구)**: `AgenticSeedSelector.kt:494` rationale 파라미터 설명만 "…근거와 구현 역할. 클래스당 한 문장 이하로 간결하게 작성"으로 변경(seed_classes 설명·인자 순서·도구 순서 불변, `SeedRationaleDescriptionTest`로 고정). 154520 마지막 시드 호출을 전/후 각 3회 재생(D1~D5, S1~S3): 평균 83.5초→62.1초(-25.6%, 합격 기준 20%), seed_classes 집합 종류 합계 10→9(후 ≤ 전). 예측(-32%)보다 작았고 예측 밖 항목이 둘 있다: D5는 후 조건에서 3회 중 2회 confirm 대신 inspect_node_detail을 불러 confirm 호출이 3/3→1/3(감소율 10%), S3는 집합 종류 1→2(한 회차에 TrCertResDto 추가). 시나리오별 감소율이 20% 미만인 곳은 D4 17%, D5 10%, S3 17%. 재생의 전 평균(예: D1 115초)은 동시 4개 부하로 154520 실측(80초)보다 길다(전/후 같은 조건). 3회뿐이라 D5·S3의 변화가 변동인지는 확정하지 못했다.
- **②' 보류**: 도구 호출 전 본문 억제는 시스템 프롬프트 "[도구 호출 시 설명 작성 지침]"(`AgenticSeedSelector.kt:54-55`, 도구 호출 시 1~3문장 설명을 본문으로 쓰라는 지시)를 바꿔야 하고, 이 본문은 시드 목록보다 먼저 생성되어 사고 과정 역할을 할 수 있다(D1 본문은 "요구사항 분석 요약"으로 BizgoApiService 등 구조를 먼저 정리). 억제하면 시드가 바뀔 위험이 있어 시드 목록이 안정적인지 먼저 보기 전에는 하지 않는다. 예상 절감은 약 26초(약 38%)다.
- **E(제거 대신 순위만) 재생(커밋 806f241, 변형 프롬프트 원문 `stage3_rank_variant_prompt.md`)**: 154520 Stage 3 요청 8개 x 3회, A 값은 92ddb39 재생. 같은 K(회차별 A 남은 수)에서 E 상위 K의 GT 합 57, A 남은 GT 합 57, 시나리오별 E ≥ A 8/8(대부분 동률, D1은 둘 다 0). 후보 11개 이상 시나리오 후보 안 GT의 E 상위 10 포함 34/39(87%). 분리도(GT 평균순위 < 비GT)는 7/8(D4가 5.0 대 5.0 동률). Stage 3 시간은 E 380초 대 A 251초(시나리오 평균 합, +51%)로 7/8 시나리오가 +20% 기준을 넘었다. 형식 오류 0, 해석 실패 0, 서로 다른 상위 10 집합은 합계 9(예측 12 이상).
- **E 예측 대비**: 같은 K의 D1 GT는 예측 1~2개였으나 0개(survey 파일이 순위 9~16, A와 같음), 분리도는 8개 모두 예측이었으나 D4가 동률, 시간은 예측보다 모든 시나리오에서 길었다(D1 67초 대 예측 45초 등), 상위 10 집합 종류는 예측보다 적었다(더 안정적). 시나리오별 E ≥ A는 예측 5~6/8, 실측 8/8(동률 포함). A의 후보 안 GT 합은 D3의 GT 5개 중 두 개가 같은 후보로 매칭되어 중복 제거하면 19(예측표의 20은 중복 포함)이다.
- **E 채택 조건 판정**: (1) 같은 K 합계 충족(57 ≥ 57)이지만 동률이라 변별력이 없다. (2) 충족(87%). (3) 미충족(D4 동률). (4) 미충족(시간 +51%). (5) 충족. 같은 K에서는 이득이 없고, K를 10으로 늘리면 A가 제거한 GT 중 D2 1개, D5 1개와 D1 일부(회차당 1~2/3)를 회수하지만 목록이 6→10, D5는 1→10으로 길어진다. D1 설문 파일 3개는 E에서도 낮은 순위라 Stage 3 모델 판단(참조 패턴으로 봄)의 문제이지 제거 여부의 문제가 아니다.
- **이후 단계 입력 길이(읽기만)**: `/implement`는 `RequirementAnalysisPipeline.lastResult`(`:480`)의 `targetFiles` 전체를 받는다(`WebviewActionRouter.kt:397-398, :415` → `ImplementService.kt:92` sortTargetsByLayer → `:111-124` 파일마다 LLM 호출 1회). 개수 상한이 없으므로 E를 적용하면 상한을 정하지 않는 한 D1 27개, D5 16개처럼 후보 수만큼 변경 지시서 호출이 늘어난다. `/test-all`도 같은 결과를 쓴다(`TestGenerationPipeline.kt:43,122`). E의 실제 적용(`sortByDependency` 변경 포함, `RequirementAnalysisPipeline.kt:294`)은 결과 보고와 승인 뒤에 설계한다.

### [B-44] 시드 rationale 변경의 운영 조건 재판정, 평가 1회, 시드 단계 손실 분석, E 미채택 결론 (2026-10-16)
- **재생 조건 원칙**: 모든 재생 실험은 첫 줄에서 운영 조건(temperature, max_tokens, tool_choice, 도구 정의, 최대 턴, 클라이언트)과 같은지 표로 확인하고 실행한다. 이번에 시드 선정 재생이 temperature를 넘기지 않아 클라이언트 기본값 0.1로 돌았고 운영은 0.0(`AgenticSeedSelector.kt:89`)이라 B-43의 ② 합격 판정(3회 재생 -25.6%, 집합 종류 10→9)과 D5 10회 재생(confirm 6/10)은 운영 조건과 다른 값이었다. 두 값은 참고로만 쓴다.
- **② 운영 조건 재판정(커밋 213e9f4의 재생 도구, 사전 예측·판정 규칙 `pred_seed_temp0.md`)**: temperature 0.0, 덤프의 max_tokens 2048·tool_choice auto, 마지막 호출부터 confirm 또는 maxTurns 10까지 도구를 운영과 같은 AgenticGraphTools로 실행하며 이어서 재생(D5 전/후 각 10회, 나머지 7개 각 3회). 8개 시나리오 평균 시드 구간 73.5초→54.1초(-26.4%), 시나리오별 -22~-45%이고 D5만 -7%(65→70초). D5는 후 조건에서 첫 호출이 항상 inspect_node_detail이라 confirm 비율 10/10→0/10(차이 100%p)이고 다음 턴(턴 2)에 10/10이 confirm했다. 10턴 소진(Guard 2/fallback 도달)·도구 없이 종료·오류는 0건. 후 조건에서 S2는 MemberStatus가 빠진 시드가 3회 중 2회 나와 집합 종류가 1→2이고 합계는 9→9. 사전 규칙 3(차이 ≥ 20%p이지만 실측 시간 감소 ≥ 15%)에 해당해 ②를 유지하고 평가로 진행했다. 예측과의 차이: 8개 평균 감소가 예측 -17~-25%보다 큰 -26.4%, D5 쏠림은 예측한 세 경우 중 하나(전부 inspect).
- **운영 시드 선정 소진 동작(읽기)**: `maxTurns = 10`(`AgenticSeedSelector.kt:71`, 루프 `:76`). confirm 없이 소진되면 `finalSeeds` 사용(`:296`), 비었으면 누적 점수 상위 3개 자동 채택(Guard 2, `:310-326`), 그것도 없으면 `fallbackSelection`(키워드 매칭, `:329-371`). 도구 호출 없는 응답과 LLM 예외는 루프를 즉시 끝낸다(`:119-123`, `:90-94`).
- **평가 1회(live_eval_20261016_143658, 요약 비동기화 + rationale 축소 반영, 사전 예측 `pred_eval_after_seed.md`)**: 총 시간 2683(154520)→2139(111920)→1998초, 111920 대비 -7%, 154520 대비 -26%(예측 2025초). 시나리오별 총 시간(154520/111920/신규): D1 425/363/333, D2 295/225/253, D3 298/255/227, D4 194/136/119, D5 253/205/200, D5E 277/208/195, D5N 275/211/194, S1 218/147/119, S2 236/201/162, S3 213/188/195. 예측 ±25초를 벗어난 것은 D2 하나(253초, 예측 202초, 시드 호출이 5→6회로 늘고 변동). 단계별 합(154520/111920/신규): 시드 1237/1189/1069, 작업 요약 535/363/372(턴 응답 밖이라 임계 경로에 없음), 제약 추출 276/290/290, 발화 번역 186/181/195, Stage 3 304/275/308, 신규 파일 탐지 63/75/55. 실패 0, 300초 초과 호출 0, 스트림 절단 재시도 0. 확정 시점 요약 상태: COMPLETE 발화로 바로 분석 5(D1, D3, D5, D5E, D5N), PENDING 5(D2, D4, S1, S2, S3), 하네스는 생각 시간이 0이라 화면 전달 0회가 정상이며 판정 기준이 아니다.
- **시드 호출 수와 시간(154520→신규)**: D1 5→4(157→116초), D2 5→6(144→150), D3 4→4(130→103), D4 3→3(91→77), D5 4→6(123→117), D5E·D5N 4→6(123→110, 111), S1 3→3(105→61), S2 5→5(123→92), S3 4→5(119→132). D5 계열은 예측 5회보다 한 번 더 늘었고 최대 6턴으로 10턴 근처에도 가지 않았다. 앞 턴 경로가 달라진 곳(D1 시드 목록이 7개에서 4개로, D2·S3 호출 증가)이 있으나 rationale 변경 때문인지 변동인지는 같은 코드의 반복 실행이 없어 구분하지 못했다.
- **Recall@10(GT@10, 154520/111920/신규)**: D1 1/0/1, D2 1/1/1, D3 5/5/5, D4 4/4/4, D5 계열 1/1/1, S1 3/3/3, S2 3/4/3, S3 3/3/3. 변동은 D1·S2의 ±1이고 예측 범위 안.
- **후보 안/밖 GT 분리(덤프의 Stage 3 요청에서 복원, 신규 평가)**: 8개 시나리오 합 GT 41개 중 후보 안 25개, 후보 밖 16개, 후보 안 중 최종 목록 유지 21개(84%). 시나리오별(GT 전체/후보 안/후보 밖/후보 안 유지): D1 8/2/6/1, D2 5/3/2/1, D3 5/5/0/5, D4 4/4/0/4, D5 계열 8/2/6/1, S1 3/3/0/3, S2 4/3/1/3, S3 4/3/1/3. 예측과 달리 D1의 후보 안 GT가 3→2(시드가 7개에서 4개로 줄어 SurveyServiceImpl이 후보 밖으로).
- **시드 단계 손실 분석(읽기만, 시드 이력은 신규 평가의 마지막 시드 호출 요청)**: D1(시드 4개: AlimtalkTemplateBatchJob/Runner/Repository, AlimtalkTmplDto) 후보 밖 GT 6개: survey_list.jsp 3홉·검색/확장 결과에 등장만, survey_write.jsp 3홉·조회 이력 없음, survey.list.js 3홉·조회 이력 없음, survey.write.js 3홉·결과에 등장만, SurveyServiceImpl 2홉·결과에 등장만, AlimtalkChnlDto 1홉·결과에 등장만. D5(시드 SurveyServiceImpl, SurveyDto) 후보 밖 GT 6개 전부(survey_list.jsp, survey_write.jsp, survey.list.js, survey.write.js, AlimtalkChnlDto, AlimtalkTmplDto) 2홉이고 조회 이력 없음(결과에도 안 나옴). S3(시드 KmcisServiceImpl, CallbackInfoDto, TrCertResDto) 후보 밖 GT는 KmcisController 1홉, inspect/expand로 조회했으나 시드로 확정하지 않음. 모든 GT는 메타그래프에 있다(JSP/JS는 resourceNodes의 VIEW/SCRIPT). 홉 수는 relationships, dependsOn/dependedBy, 리소스 linkedTo를 이은 무방향 최단 거리다. 주의: D1과 D5는 같은 SR·같은 GT 목록을 쓰고 D5는 대본으로 "SurveyServiceImpl만 수정, 알림톡 배치는 건드리지 말라"고 범위를 좁히므로, D5 GT에는 그 지시와 충돌하는 파일(알림톡 DTO 등)이 포함되어 D5의 후보 밖 손실 일부는 사용자 지시 결과일 수 있다(D5 전용 GT 정의 필요).
- **시드 단계 개선안(제안만)**: (1) 화면 관련 SR(`isExplicitFrontendByKeyword` 등)이면 시드에서 3홉 이내의 VIEW/SCRIPT 리소스를 그래프만으로 후보에 더한다(D1의 jsp·js 4개가 3홉, LLM 호출 없음, 후보 수 증가로 Stage 3 입력만 늘고 출력은 늘지 않음). (2) inspect_node_detail 결과에 연결된 VIEW/SCRIPT/매퍼 리소스를 함께 보여 에이전트가 조회 대상으로 삼게 한다(D1 jsp 2개는 결과에도 안 나옴). (3) 이미 inspect한 1홉 Controller(S3 KmcisController)를 확정에서 빼는 경우를 줄이도록 확정 단계에서 inspect한 후보를 한 번 더 보여 준다(프롬프트 변경이라 별도 승인 필요).
- **E 미채택 결론(사용자 판정)**: 같은 K에서 이득이 없고(57/57), Stage 3 시간이 +51%이며, K를 늘리면 `/implement`가 후보 수만큼 호출해 상한이 없어서 채택하지 않는다. D1 GT 손실은 Stage 3 판단 문제, D5는 시드·그래프 단계 손실로 정리한다(위 D5 GT 정의 주의 참고). E 변형 검증기와 재생 테스트(`Stage3RankReplayTest`)는 그대로 둔다.
- **새 기준선 후보**: live_eval_20261016_143658 (코드: HEAD 213e9f4 + 재생 도구 오류 처리 보정). 총 1998초, 실패 0, 300초 초과 0. 시드 선정(1069초)이 54%를 차지하고 clarify 합(발화 번역·제약 추출·정제문 합 533초, 요약은 임계 경로 밖)과 Stage 3(308초)이 뒤를 잇는다.

### [B-45] D1·S2 시드 선정 전체 경로 재생, 개선안 1 오프라인 시뮬레이션, D5 GT 초안 (2026-10-16)
- **기준선 확정**: live_eval_20261016_143658(총 1,998초, 실패 0, 300초 초과 0)을 기준선으로 확정한다. 근거는 아래 재생에서 후 조건만의 `SurveyServiceImpl` 누락이 없었다는 것이다. 같은 코드의 반복 실행이 없으므로 시드 경로 변동(D1·D2·S3)은 변동 범위로 본다(아래 비결정성 참고).
- **D1·S2 시드 선정 전체 경로 재생(커밋 `SeedFullReplayTest`, 사전 예측 `pred_seed_full.md`)**: 운영 `AgenticSeedSelector.selectSeeds`를 첫 호출부터 그대로 호출(temperature 0.0, max_tokens 2048, tool_choice auto, 도구 실행 동일), 도구 목록의 rationale 설명만 전(e34b94d 이전 문구)/후(현재)로 바꿔 시나리오당 전/후 각 3회. SR은 143658 dumps_op의 첫 시드 호출 사용자 메시지에서 추출.

  | 시나리오 | 핵심 파일 | 조건 | 시드에 포함 | 도구 결과에 등장 | 평균 턴 | 구간 평균(초, 동시 4개 부하) | 집합 종류 | Guard 2/fallback |
  |---|---|---|---|---|---|---|---|---|
  | D1 | SurveyServiceImpl | 전 | 2/3 | 3/3 | 5.0 | 206 | 3 | 0 |
  | D1 | SurveyServiceImpl | 후 | 2/3 | 2/3 | 5.0 | 169 | 3 | 0 |
  | S2 | MemberStatus | 전 | 2/3 | 3/3 | 5.0 | 150 | 3 | 0 |
  | S2 | MemberStatus | 후 | 3/3 | 3/3 | 4.0 | 127 | 3 | 0 |

  판정: 후 조건에서만 누락이 2/3 이상이 아니므로(D1은 전/후 모두 2/3 포함) 규칙에 해당하지 않아 기준선 확정. 예측과의 차이: D1 후 포함은 예측 1/3, 실측 2/3, S2 전 포함 예측 3/3 실측 2/3, S2 후 예측 1/3 실측 3/3(예측이 틀렸고 ②가 시드를 잃게 한다는 증거는 없음). 평균 턴은 예측(D1 4~5, S2 5)과 같고 Guard 2/fallback 0.
- **비결정성 발견**: temperature 0.0인데도 같은 조건에서 3회가 서로 다른 seed_classes 3종류를 냈다(D1·S2 전/후 모두 집합 종류 3). 시드 선정은 단일 실행으로 작은 차이를 가를 수 없고, 평가 간 시드·후보 차이(D1 시드 7→4개 등)는 이 변동 안에 있다. 이후 시드 단계 변경은 시나리오당 3회 이상의 반복 재생으로 판정한다. 마지막 호출 하나만 재생한 값(D5 첫 호출 confirm 0/10)은 더 일정했으나 전체 경로는 앞 턴에서 갈라진다.
- **개선안 1 오프라인 시뮬레이션(읽기·계산만, `sim_resources.js`)**: 143658의 시나리오별 최종 시드와 Stage 3 후보를 기준으로 메타그래프(무방향: relationships, dependsOn/dependedBy, 리소스 linkedTo)에서 VIEW/SCRIPT/MYBATIS_MAPPER 리소스를 센다.
  - (a) 시드에서 홉 수별 누적(리소스 수 / 기존 후보에 없는 수 / 후보 밖 GT 회수): D1 1홉 0/0/0, 2홉 1/0/0, 3홉 46/45/4(survey_list.jsp, survey_write.jsp, survey.list.js, survey.write.js)(후보 밖 GT 6개 중). D5·D5E·D5N 2홉 47/45/4(같은 4개), 1홉 0. D2·D3 0/0/0(후보 밖 GT 2개는 SVC·SVCImpl로 리소스가 아님). D4 2홉 2/0/0. S1 3홉 13/13, S2 3홉 10/9, S3 2홉 49/46(회수 GT 0). 즉 VIEW/SCRIPT는 한 번에 45~49개가 같은 홉에 몰려 들어오고 홉 수만으로는 가려낼 수 없다.
  - (b) 상한·조건별(3홉 이내, 기존 후보에 없는 것, 추가 후보 수 / 회수 GT): (i) 시드당 가까운 3개: D1 3/0, D5 3/0, S1 6, S2 4, S3 4, 회수 0. (ii) 전체 가까운 10개: D1 10/0, D5 10/0, 그 외 최대 10, 회수 0. (iii) 시드 이름 첫 토큰이 같은 것만: D1 0/0(시드가 Alimtalk*라 survey와 안 겹침), D5 13/4, S1 2, S2 5, S3 1. (iv) 기존 후보에서 2홉: D1 45/4, D5 45/4, S3 46/0. (v) 기존 후보에서 3홉 이내이면서 리소스 이름 토큰이 후보 이름 토큰(service, impl, dto 등 일반어 제외)과 겹치는 것만: D1 13/4, D2 0, D3 0, D4 0, D5 13/4, S1 2/0, S2 5/0, S3 2/0(8개 시나리오 합 추가 35개, 회수 GT 8개 중 D1·D5의 같은 4개씩).
  - (c) Stage 3 시간 증가 추정: V2는 출력이 시간을 정한다(후보당 입력 약 100토큰은 프리필이라 후보 13개에 +1~2초, removedFiles에 번호 하나는 약 2~3토큰으로 후보당 약 0.3초, 유지되어 reason을 쓰면 약 3초). (v)의 D1 +13개는 약 +5~6초(Stage 3 36~48초 대비 +12%), 상한 없는 45개는 +16~19초. 8개 합 추가 35개 기준 시나리오 평균 +1~2초.
  - (d) 권장: 적용한다면 조건 (v)(기존 후보 3홉 이내 + 후보 이름 토큰이 겹치는 VIEW/SCRIPT/MAPPER)에 전체 최대 15개 상한. 회수는 D1의 4개로 한정되어 일반화 근거가 약하다(S1~S3·D2~D4는 회수 0, 추가 후보만 2~5개). D5는 사용자 지시("SurveyServiceImpl만 수정")로 이 4개가 제외 대상이라(아래 GT 초안) 추가가 오히려 해가 된다. 화면 SR 키워드(`isExplicitFrontendByKeyword`)로 켜는 조건은 D1 정제문에 해당 단어가 없어(어드민·화면·UI·jsp 등 없음) 이 경우를 놓친다. 구현은 하지 않았고, 적용 여부는 D5 GT 확정과 D1형(리소스 군집) 시나리오가 더 있는지 보고 판단한다.
- **D5 GT 정의 초안(사용자 확정 대기, 커밋·시나리오 파일 변경 없음)**: D5·D5E·D5N 대본 "알림톡 배치는 건드리지 말고 SurveyServiceImpl만 수정할 거야"에 맞춰 현재 GT 8개(D1과 동일)를 나눈다. D1의 GT는 바꾸지 않는다.

  | GT 파일 | 분류 | 근거 |
  |---|---|---|
  | SurveyServiceImpl.java | 유지(required) | 사용자가 수정 대상으로 명시 |
  | survey_list.jsp, survey_write.jsp, survey.list.js, survey.write.js | 제외 | "SurveyServiceImpl만 수정" 지시와 충돌하는 화면 파일 |
  | sql_survey.xml | 판단 필요 | SurveyServiceImpl이 호출하는 SurveyDao의 SQL(linkedTo SurveyDao). 서비스만 수정이면 제외, 채널 조회 SQL 변경이 필요하면 reference/required |
  | AlimtalkChnlDto.java, AlimtalkTmplDto.java | 판단 필요 | batch 패키지 파일이 아니라 dto 패키지 DTO이고 알림톡 배치 Repository와 SurveyDao/SurveyDaoImpl이 함께 사용(usedByTypes), SurveyServiceImpl의 직접 의존은 아님. "알림톡 배치 금지"에 DTO가 포함되는지는 업무 판단 |

  143658 D5·D5E·D5N 최종 목록은 SurveyServiceImpl 1개뿐이다. 새 GT로 다시 계산한 Recall@10(LLM 재호출 없음): 원래 1/8(13%) → 안 A(판단 필요를 reference): required 1/1(100%), reference 상위 10 0/3 → 안 B(판단 필요를 required): 1/4(25%) → 안 C(판단 필요를 제외): 1/1(100%).
- **[B-45 보충] 정정과 결정(2026-10-16)**: 앞선 보고의 "144658"은 143658의 오기다(기준선은 live_eval_20261016_143658). D5 GT는 사용자가 안 A로 확정했다: D5·D5E·D5N의 required는 SurveyServiceImpl 1개, sql_survey.xml·AlimtalkChnlDto.java·AlimtalkTmplDto.java는 reference, 화면 파일 4개(jsp/js)는 대본 지시와 충돌해 GT에서 뺐다(D1의 GT는 그대로 8개). **D5 판정 지표 = 제외 대상 노출 수**: 시나리오 필드 `mustNotAppear`(AlimtalkTemplateBatchJob/Runner/Repository, 메타그래프에서 `batch` 패키지의 3개로 초안과 같음)가 최종 목록(필터 전 전체)에 나타난 수를 보고서 열 "제외 대상 노출(D5 계열, 최종 목록 중/대상)"과 실행별 줄에 기록한다(`LiveEvalTest.kt`의 fp30 계산 뒤, `RunResult.mustNotAppearTotal/InTargets`, `EvalReport.kt` scenarioRow). 커밋 0563568(테스트 전용, `src/main` 변경 없음, 테스트 455/42/13, clarify 177/1/3, 하네스 불변). 저장된 143658·154520·111920 결과로 다시 계산한 D5·D5E·D5N(LLM 재호출 없음): 최종 목록은 모두 SurveyServiceImpl 1개, required@10 1/1, reference@10 0/3, 제외 대상 노출 0/3. Recall은 기록만 하고 판정에는 쓰지 않는다.
- **[B-45 보충] 반복 재생 판정 규칙**: 시드 단계(시드 선정, 후보 생성) 변경은 temperature 0.0에서도 같은 조건의 반복이 서로 다른 시드 집합을 내므로(D1·S2 전/후 각 3회 모두 집합 3종류) 단일 실행이나 마지막 호출 하나의 재생으로 판정하지 않고 시나리오당 3회 이상의 첫 호출부터의 전체 경로 반복 재생으로 판정한다.
- **[B-45 보충] 개선안 1(그래프 기반 VIEW/SCRIPT 후보 추가) 보류 사유**: 오프라인 시뮬레이션에서 회수 GT가 D1의 jsp·js 4개뿐이고(D2·D3·D4·S1~S3는 회수 0), VIEW/SCRIPT가 한 번에 45~49개씩 같은 홉에 몰려 홉 수·시드당 상한·전체 상한으로는 4개가 걸러지지 않으며(i·ii 회수 0), 조건 (v)(기존 후보 3홉 + 후보 이름 토큰 겹침, 최대 15개)만 추가 13개로 4개를 회수한다. D5는 사용자 지시로 같은 4개가 제외 대상이라 오히려 해가 되고, 화면 SR 키워드 조건은 D1 정제문에 해당 단어가 없어 놓친다. 구현 금지, D1형 시나리오가 더 확인될 때까지 보류.
- **[B-45 보충] 명시 식별자 고정 효과 확인(읽기·계산만, `explicit_ids.js`, `extractIdentifiers`(`IdentifierRetentionChecker.kt:41-95`)를 그대로 옮김)**: SR 원문과 정제문의 식별자 중 메타그래프에 노드(클래스·리소스·메서드)가 있는 것은 143658의 10개 실행에서 9개(D2 2: selTrcdIsInf, aCMBTBAPC024DEM / D3 4: APCMMTrcdIsSVC.java, APCMMTrcdIsInfSVO, selTrcdIsInf, aCMBTBAPC024DEM / D5·D5E·D5N 각 1: SurveyServiceImpl; D1은 전부 신규 클래스라 그래프에 없고 D4·S1~S3은 식별자 없음). 그중 최종 시드에 들어간 것 8개, 못 든 것 1개(D3의 aCMBTBAPC024DEM(ACMBTBAPC024DEM)는 시드에는 없고 Stage 3 후보에는 있었음). 후보 밖 GT 16개 중 SR 원문·정제문에 이름이 직접 나온 것은 0개. e056eb5 재생 12회(D1·S2)에서 명시 식별자가 시드에서 빠진 횟수는 0(D1 식별자는 그래프에 없고 S2는 식별자 없음). 사전 판정 기준(c ≥ 3 또는 d ≥ 2)에 해당하지 않아 "명시 식별자 시드 고정" 설계안은 만들지 않고 기록만 한다. 현재 코드에 명시 식별자를 시드로 강제하는 경로는 없다: `AgenticSeedSelector.kt`는 식별자를 다루지 않고 srText를 프론트엔드 키워드 판정에만 쓰며(`:271`, `:363`), `AdaptiveFileDiscovery.kt:32`가 `selectSeeds(srText, graph)`를 호출해 `:57`에서 시드 결과를 그대로 넘긴다. 식별자 추출·보존(`IdentifierRetentionChecker`)은 clarify 정제문에만 쓰인다(`Stage0ClarificationEngine.kt:83`, `IdentifierRetentionChecker.kt:154`).
- **[B-45 보충] 비결정성 원인 확인(D1 전체 경로, 현재 설명, 동시 1개 순차 3회, 원문만)**: 집합 종류 1. 세 회차 모두 `[AlimtalkTemplateBatchJob, AlimtalkTemplateBatchRepository, AlimtalkTemplateBatchRunner, AlimtalkTmplDto]`, 턴 4, 구간 114초, SurveyServiceImpl 시드 포함 0/3(도구 결과에는 3/3 등장), Guard 2/fallback 0, 소요 6분 2초. 같은 조건의 동시 4개 재생(위 표)은 D1 후 조건에서 집합 3종류였다. 해석은 붙이지 않는다.

### [B-46] 시드 단계 판정 규칙, D1 "일관되게 틀림" 분석, 후보 밖 GT 분류 (2026-10-16)
- **판정 규칙 확정**: 시드 단계(시드 선정, 후보 생성) 변경의 효과 판정은 동시 1개 순차 x 시나리오당 3회 이상(첫 호출부터의 전체 경로)으로 한다. 동시 4개 실행에서 나오는 집합 종류 수는 "동시 처리 흔들림" 신뢰성 지표로 기록만 하고 판정에는 쓰지 않는다(사용자가 여럿이면 겪는 흔들림이지만 변경 효과와 섞이므로). 근거: D1 후 조건이 동시 4개에서는 집합 3종류, 순차 3회에서는 1종류(표본 3회라 단정하지 않음).
- **명시 식별자 시드 고정 가설 기각**: 후보에 오르지 못한 GT 16개 중 SR 원문·정제문에 이름이 직접 나온 것 0개, e056eb5 재생 12회에서 명시 식별자가 시드에서 빠진 횟수 0이라 재검토하지 않는다. 놓치는 원인은 "사용자가 말한 파일을 빠뜨리는 것"이 아니라 "말하지 않은 관련 파일을 찾지 못하는 것"이다.
- **D1은 일관되게 틀림(순차 3회, 현재 설명)**: 3회 모두 같은 경로(턴 4, 같은 도구 호출, 같은 rationale 521자)로 시드 `[AlimtalkTemplateBatchJob, AlimtalkTemplateBatchRunner, AlimtalkTemplateBatchRepository, AlimtalkTmplDto]`를 확정했고 SurveyServiceImpl은 0/3이다. 일관되지만 틀린다(GT 8개 중 후보 밖 6개).
  - **근거가 있는 것**: (1) SurveyServiceImpl이 처음 나온 곳은 3회 모두 턴 3의 `search_graph_nodes {"query": "Survey Send Channel"}` 결과 2위(SERVICE, 점수 200.0, 1위 SurveyDto 200.0)이고, 시드 선정이 inspect/expand로 조회한 클래스는 알림톡 4개뿐이다. (2) 턴 1 메시지가 핵심 도메인을 "알림톡 배치(AlimTalk Batch) 구조를 참고하여 브랜드메시지(BrandMessage) 배치를 신규 개발하는 것"으로 해석했다. (3) 확정 직전 턴 4 메시지는 "`survey.write.js`에 loadAlimtalkData, renderAlimtalkChannels, renderAlimtalkTemplates가 있어 …설문 발송 채널에 브랜드메시지를 추가하려면 이 부분도 수정 대상이 됩니다"라고 쓰면서 확정 목록에는 설문 관련 클래스를 넣지 않았다. (4) rationale 원문(521자)에 설문·Survey 언급이 없고 4개 모두 신규 개발 시 기준(복제 원본)으로 설명한다: "AlimtalkTemplateBatchJob: 매시간 정각 실행되는 알림톡 배치 Job으로, BrandMessageTemplateBatchJob 신규 개발 시 스케줄링 구조의 기준 클래스. AlimtalkTemplateBatchRunner: …BrandMessageTemplateBatchRunner 신규 개발 시 BizgoApiService 호출 및 템플릿 파싱 로직의 구조적 기준. AlimtalkTemplateBatchRepository: …save/update 메서드 구조의 기준. AlimtalkTmplDto: …BrandMessageTmplDto 신규 개발 시 필드 구조의 기준." (5) SR 원문은 "설문 발송 채널에 브랜드메시지 추가"이고 정제문은 대본 발화를 그대로 반영해 "외부 Bizgo 연동 API(BizgoApiService)를 신규 생성하고, 알림톡 배치 구조와 동일하게 브랜드메시지 배치 3종(BrandMessageTemplateBatchRunner, BrandMessageTemplateBatchJob, BrandMessageTemplateBatchRepository) 및 DTO(BrandMessageTmplDto)를 신규 개발하여 설문 발송 채널에 브랜드메시지를 추가한다"이다. 설문은 마지막 절에 한 번만 나오고 나머지는 알림톡·BrandMessage·Bizgo 이름이다(쏠림의 근거 문구: "알림톡 배치 구조와 동일하게 …신규 개발"). (6) 시스템 프롬프트가 확정 단계에서 "핵심 업무 클래스 1~4개"를 요구하고(`AgenticSeedSelector.kt:49`) 신규 개발 표현이 있어도 기존 기준 클래스를 폭넓게 Seed로 확정하라고 한다(`:50`).
  - **추정**: 시드 한도 4개가 알림톡 4개로 채워져 설문 클래스가 밀렸을 가능성(154520 실측에서는 시드가 7개로 확정된 사례가 있어 한도를 항상 지키지는 않으므로 단정 불가). survey.write.js 같은 리소스는 seed_classes(클래스명) 형식에 맞지 않아 넣지 못했을 가능성. 설문 클래스가 "수정 대상"이 아니라 "참고용 검색 결과"로 분류됐을 가능성.
  - **개선안(1개, 구현하지 않음)**: 검색 결과의 충분 검색 안내(`guidance`, 검색 도구 처리부)에 "SR이 언급한 적용 대상(예: 설문 발송 채널)에 해당하는 상위 검색 결과 중 inspect하지 않은 SERVICE/Controller 후보"를 한 번 환기하는 문장을 더한다. 프롬프트·도구 안내 변경이라 별도 승인이 필요하다.
  - 순차 재생의 턴별 기록은 gradle 로그(재생 도구가 selectSeeds를 그대로 호출하며 출력한 로그)에서 복원했고, 결과 원문은 도구가 출력한 요약 줄이다.
- **후보 밖 GT 16개 분류(SeedLossClassifyTest, LLM 호출 없음)**: 기준 평가(143658)의 최종 시드로 운영 `GraphExpander`(maxHop 3, `AdaptiveFileDiscovery.kt:46-47`)를 그대로 돌려 분류했다. 재현 검증: 실제 Stage 3 후보(그래프에 있는 파일)가 재현한 확장 결과에 D1 14/14, D2 17/17, D5 16/16, S2 15/15, S3 11/11 모두 들어 있다(srText는 첫 시드 호출 사용자 메시지에서 추출, 운영 입력을 완전히 복원한 것은 아니므로 재현 계산). 분류: (가) 그래프에 없음 0, (나) 시드에서 3홉 밖 0, (다-a) 확장기는 도달했으나 후보에서 탈락(RelevanceScorer minScore 55, 30개 상한) 8, (다-b) 3홉 안인데 확장기가 도달하지 못함(조건부 확장·도메인 필터) 8. 시나리오별: D1(6) SurveyServiceImpl 다-a(확장 hop 2 via DEPENDED_BY from ReviewDto), AlimtalkChnlDto 다-a(hop 1 via SAME_PACKAGE), survey_list.jsp·survey_write.jsp·survey.list.js·survey.write.js 다-b(BFS 3홉). D2(2) APCMMTrcdIsSVC·APCMMTrcdIsSVCImpl 다-a(hop 1 via DEPENDED_BY from APCMMTrcdIsBIZ). D5(6, GT 안 A 확정 전 8개 기준) survey_list.jsp·survey_write.jsp·survey.list.js·survey.write.js 다-b(BFS 2홉), AlimtalkChnlDto·AlimtalkTmplDto 다-a(hop 1 via SAME_PACKAGE from SurveyDto). S2(1) AdminController 다-a(hop 1 via SAME_PACKAGE from AdminService). S3(1) KmcisController 다-a(hop 1 via DEPENDED_BY from KmcisServiceImpl). 안 A 기준(D5 required가 SurveyServiceImpl 1개)으로는 후보 밖 required GT가 10개(D1 6, D2 2, S2 1, S3 1)다.
- **해석**: 후보 밖 GT는 그래프에 없어서(가)도 홉이 멀어서(나)도 아니다. 8개는 확장기가 이미 도달했는데 점수·상한에서 탈락했고(시드 쪽이 아니라 RelevanceScorer 쪽 문제), 8개는 VIEW/SCRIPT 리소스가 3홉 안인데 확장기 규칙으로 미도달이다. 홉 확장(개선안 1) 보류는 유지하며, (나)=0이라 홉 수를 늘려서 얻을 효과의 근거는 더 줄었다. 시드 선정 자체의 손실은 D1처럼 시드 LLM이 설문 클래스를 고르지 않는 경우(다-a의 SurveyServiceImpl은 확장기가 hop 2로 도달했으나 점수에서 탈락)와 겹쳐 있다.

### [B-47] RelevanceScorer 점수 분석: 후보에서 탈락한 GT의 원인은 minScore 55 (2026-10-16)
- **정정**: B-46의 "GraphExpander(maxHop 3, `AdaptiveFileDiscovery.kt:46-47`)"는 `:38-40`이 맞다(`:46`은 RelevanceScorer 생성, `fileLimit=30, minScore=55`). B-46의 (다-b) 8개는 D5 GT 안 A 기준으로는 D1의 jsp·js 4개뿐이다(D5의 화면 파일 4개는 GT가 아니게 됐다). 그래서 VIEW/SCRIPT 확장의 효과 근거는 D1 한 시나리오이고 개선안 1 보류는 유지한다.
- **점수 산식(`RelevanceScorer.kt`, FileNode)**: 총점 = hop 점수(`:61` hop0 40, hop1 30, hop2 15, 그 외 0) + 이름 일치(`:75-79` 직접 일치 50, 번역 일치 30, 약한 번역 15에 IDF 가중) + 메서드 일치(`:100` 최대 30) + 레이어 정렬(`:105` 시드 layerHint(SERVICE/BIZ/PRESENTATION)와 맞으면 15) + 주석 일치(`:114` SR 명사·동사가 한글 주석에 있으면 20) + 유형 보너스(`:120` Mapper/Dao 계열 10) + 핵심 체인 보너스(`:127-129` 저장소 계열에 한해 BIZ_TO_DOWNSTREAM 55, SERVICE_TO_REPO_OR_BIZ 20), 합산 `:133`. 적용 순서: `totalScore >= minScore`이면 통과(`:173`), 미만이어도 보호(Seed Class·Judge Pick·Frontend Hint 1순위, Seed Direct Dependency 2순위)이면 구제(`:189`). Seed Direct Dependency는 DAO·Mapper·DEM·DQM 같은 저장소 계열의 1홉 하향 의존에만 붙는다(`:157`). 마지막에 보호 등급, 점수 내림차순 정렬 후 `fileLimit`(30)로 자른다(`:490` 이후). 운영은 `minScore = 55`, 상한 30(`AdaptiveFileDiscovery.kt:46`).
- **탈락 GT 8개의 점수(기준 평가 143658의 시드, 운영 GraphExpander·RelevanceScorer를 그대로 재현)**: 재현 검증으로 T=55 결과가 실제 Stage 3 후보와 8개 시나리오 모두 전부 일치(D1 14/14, D2 17/17, D3 9/9, D4 9/9, D5 16/16, S1 8/8, S2 15/15, S3 11/11).

  | 시나리오 | 탈락 GT | 총점 | hop 점수 | 레이어 | 키워드·유형 합(총점-hop-레이어) | 보호 | 통과 후보 최저점(보호 제외) |
  |---|---|---|---|---|---|---|---|
  | D1 | SurveyServiceImpl (SERVICE, 2홉 DEPENDED_BY) | 50 | 15 | 15 | 20 | 없음 | SurveyPartExportJob 55 |
  | D1 | AlimtalkChnlDto (DTO, 1홉 SAME_PACKAGE) | 45 | 30 | 15 | 0 | 없음 | 55 |
  | D2 | APCMMTrcdIsSVC (SERVICE_INTERFACE, 1홉 USED_BY_TYPE) | 45 | 30 | 15 | 0 | 없음 | APCSRMnChArtcListInqrBIZ 56 |
  | D2 | APCMMTrcdIsSVCImpl (SERVICE, 1홉 DEPENDED_BY) | 45 | 30 | 15 | 0 | 없음 | 56 |
  | D5 | AlimtalkChnlDto (1홉 SAME_PACKAGE) | 45 | 30 | 15 | 0 | 없음 | AddressDto 55 |
  | D5 | AlimtalkTmplDto (1홉 SAME_PACKAGE) | 45 | 30 | 15 | 0 | 없음 | 55 |
  | S2 | AdminController (REST_CONTROLLER, 1홉 SAME_PACKAGE) | 45 | 30 | 15 | 0 | 없음 | AdminMemberResponse 60 |
  | S3 | KmcisController (CONTROLLER, 1홉 DEPENDED_BY) | 45 | 30 | 15 | 0 | 없음 | UserServiceImpl 65 |

  (키워드·유형 합은 이름·메서드·주석·유형 항목의 합을 총점에서 역산한 값이다.)
- **탈락 원인 판정**: 8개 모두 55 미만으로 탈락했고 30개 상한 때문이 아니다(모든 시나리오의 스코어러 후보 수가 30 미만, 상한 밖 0). 사전 예측(minScore)과 일치.
- **구조적 원인(점수 기준을 바꾸지 않고 회수할 수 있는 이유)**: 7개는 정확히 45점이다 = hop 1 점수 30 + 레이어 정렬 15 + 키워드 일치 0. 시드의 1홉 이웃은 SR 키워드(이름·주석·메서드)가 맞지 않으면 최대 45점이라 55에 10점 모자라고, 구제 경로(Seed Direct Dependency)는 저장소 계열에만 있어 Controller·Service·DTO 이웃에는 없다. D1 SurveyServiceImpl은 15(hop 2)+15+20(주석 일치)=50이다. SR이 신규 클래스 이름(Bizgo, BrandMessage 등)으로 쓰여 기존 이웃 파일 이름과 키워드가 겹치지 않는 경우에 특히 그렇다.
- **minScore 시뮬레이션(운영 코드 그대로, 상한 30 유지, 기준: 안 A GT, 8개 시나리오 합)**:

  | minScore | required 회수(후보 안 required GT 수, 전체 34개) | 새로 들어온 GT 밖 파일 | 55 대비 추가 파일 | 30개 상한에 닿은 시나리오 |
  |---|---|---|---|---|
  | 55(운영) | 24 | 0 | 0 | 0 |
  | 50 | 25 (+1) | 17 | 18 | D2(17→30) |
  | 45 | 28 (+4) | 33 | 39 | D2(30) |
  | 40 | 28 (+4) | 35 | 41 | D2(30) |

  시나리오별(55→50→45→40 후보 수 / required 회수): D1 14→16→18→19 / 2→3→4→4 (8개 중). D2 17→30→30→30 / 3→3→3→3 (5개 중, 50~54점 비 GT 13개가 먼저 들어와 상한 30을 채우고 45점인 SVC·SVCImpl은 상한에서 잘림). D3 9→9→13→14 / 5/5 유지. D4 9 고정 / 4/4. D5 16→16→20→20 / required 1/1, reference 1→1→3→3(3개 중). S1 8→8→9→9 / 3/3. S2 15→15→23→23 / 3→3→4→4(4개 중), 새 GT 밖 7개. S3 11→14→16→16 / 3→3→4→4(4개 중), 새 GT 밖 3~4개.
- **사전 판정 기준 결과(required 4개 이상 회수 + 새 GT 밖 파일이 회수 수의 2배 이하 + 어떤 시나리오도 후보 30개 초과 없음)**: 충족하는 기준값이 없다. 50은 +1(<4), 45는 +4이나 새 GT 밖 33 > 회수의 2배(8), 40은 +4에 35 > 8. D2는 50 이하에서 상한 30에 닿아 회수 효과도 막힌다. 따라서 "점수 기준 변경 설계안"은 만들지 않고 기록만 한다.
- **Stage 3 시간 증가 추정(추가 후보 1개당)**: Stage 3 응답 20개(154520·143658)의 인자는 평균 530자(required 4.0개, removed 9.9개, 요약 210자), removed 번호 1개는 약 2.5자, required 1개는 reason 약 29자+구조 약 25자. 회귀로 구한 시간은 기본 3.4초 + required당 4.6초 + removed당 0.85초(글자·토큰 환산 하한은 required 2.6초, removed 0.17초). V2의 제거 비율(약 71%)을 가정하면 추가 후보 1개의 기대 시간은 약 0.9~1.9초이고 프리필은 무시할 수 있다. minScore 45(추가 39개)는 합계 약 +35~74초(시나리오 평균 +4~9초), D2의 50 이하(추가 13개)는 약 +12~25초.
- **구조 개선안(1개, 구현하지 않음)**: 55 미만으로 탈락한 1홉 호출자(DEPENDED_BY·USED_BY_TYPE)이면서 Controller·Service·BIZ 계열인 파일을 Seed Direct Dependency처럼 보호(시드당 3개 상한)한다(`RelevanceScorer.kt:157` 부근). 재현 계산으로 8개 시나리오에서 해당 파일은 4개뿐이다: D2 APCMMTrcdIsSVC·APCMMTrcdIsSVCImpl(required GT), S3 KmcisController(required GT), D5 IpsController(GT 밖). 즉 required 3개 회수에 GT 밖 1개 추가, 후보 수는 어느 시나리오도 30 근처에 가지 않는다. 한계: 회수는 후보까지이고 그 뒤 Stage 3 유지 여부는 별도다(아래).
- **회수 후 Stage 3 유지 여부(저장 자료만, 모든 덤프 폴더 조사)**: D2 APCMMTrcdIsSVC·SVCImpl, S3 KmcisController는 어느 평가에서도 Stage 3 후보에 들어간 적이 없어 "기록 없음". D1 SurveyServiceImpl은 후보였던 4개 기록 모두 제거됐다: dumps_ui(구 형식) UNNECESSARY "설문 발송 서비스로, 브랜드메시지 채널 추가는 배치 구조(BatchRunner/Job/Repository)와 Bizgo API를 통해 처리되며, 설문 발송 채널 로직 자체의 수정은 요구사항에 명시되지 않았습니다."; dumps_ui2(구 형식) UNNECESSARY "설문 발송 서비스 구현체로, 브랜드메시지 채널 추가는 배치 구조(BizgoApiService, BrandMessageTemplateBatch*)를 통해 구현되며 설문 발송 로직 자체를 수정할 필요는 없습니다."; dumps_ui3(V2)와 dumps_defer(V2)는 removedFiles에 포함(요약: "설문/주소 관련 기존 파일(8~21)은 브랜드메시지 배치 신규 개발과 무관하여 제거"). 즉 D1은 후보에 들어와도 Stage 3이 지금까지 4/4번 제거했다.
- **결론**: 후보에서 빠진 GT 8개의 직접 원인은 minScore 55(30개 상한이 아님)이고, 7개는 hop1+레이어 45점에서 10점 부족하다. minScore를 낮추는 방식은 오탐이 회수의 2배를 넘어 기각 기준에 걸리고(특히 D2는 상한을 채워 효과도 막힘), 구조 개선안(1홉 호출자 보호)은 오탐이 적게 늘지만 required 3개 회수에 그치고 D1에는 효과가 없다. D1은 시드 LLM, 점수, Stage 3의 세 단계가 모두 막는다.

### [B-48] 1-e 회수 파일의 Stage 3 유지 여부, D1 정답 확정과 단계 판단 진단 (2026-10-16)
- **minScore 변경 기각**: B-47의 사전 기준(required 4개 이상 회수, 새 GT 밖 파일이 회수의 2배 이하, 30개 초과 없음)을 충족하는 기준값이 없어 minScore 변경은 하지 않는다. 1-e(1홉 호출자 보호)는 아래 결과로 보류한다.
- **1-e 회수 파일의 Stage 3 유지 재생(`Stage3AddedCandidatesReplayTest`, 사전 예측 `pred_1e_stage3.md`)**: 143658(`dumps_op`) Stage 3 요청에서 요구사항·후보 목록을 복원해 운영 `FileRelevanceVerifier.verify`(프롬프트 `buildBatchPrompt`, temperature 클라이언트 기본값 0.1)로 원본/변형을 번갈아 순차 3회씩 호출. 복원 검증은 D2·S3·D5 모두 "원본 후보로 만든 프롬프트 == 덤프 사용자 메시지"로 통과. 변형은 운영 정렬상 보호 2순위 블록 끝 자리에 추가 파일을 넣었다.

  | 시나리오 | 추가 파일 | 유지(3회) | 원본/변형 유지 GT(3회 합) | Stage 3 시간 원본/변형(초) |
  |---|---|---|---|---|
  | D2 (17→19) | APCMMTrcdIsSVC, APCMMTrcdIsSVCImpl (required GT) | 0/3, 0/3 (모두 제거) | 3 / 3 | 25 / 30 |
  | S3 (11→12) | KmcisController (required GT) | 2/3 | 9 / 8 | 22 / 34 |
  | D5 (16→17) | IpsController (GT 밖) | 0/3 | 3 / 3 | 18 / 24 |

  S3 유지 사유 원문: run1 "KmcisController 인증 결과 반환 처리 로직 수정", run2 "KMC 인증 컨트롤러에서 결과 반환 처리 수정", run3은 제거(요약: "KmcisController는 인증 관련 컨트롤러이나 …결과 처리 로직이 Service에 있으므로 컨트롤러 수정 불필요")이며 run3에서는 기존 유지 GT인 KmcisService.java도 함께 빠졌다(유지 4개 → 3개: TrCertResDto, CallbackInfoDto, KmcisServiceImpl). D2 요약 원문: "DEM에 …조회 로직만 추가하는 것이므로 DEM과 DVO 수정만 필요. BIZ/SVC/VO 등 상위 계층은 …수정이 요구되지 않아 제거". D5 요약 원문: "요구사항이 SurveyServiceImpl만 수정하라고 명시하고 …다른 파일 변경이 불필요".
  - **사전 판정(required 3개 중 2개 이상이 각각 3회 중 2회 이상 유지 + 기존 유지 GT 감소 없음)**: 유지된 required는 KmcisController 1개뿐이고 S3에서 기존 유지 GT 감소가 한 번 있어 미충족, 기록만 하고 멈춤(1-e 설계안 없음).
  - **예측 대비**: D2·D5 유지 0/3은 예측과 같음. S3 KmcisController는 예측 1/3에 실측 2/3, 기존 유지 GT는 예측 변화 없음에 S3 한 회차 -1, Stage 3 시간은 예측 +1~4초에 실측 +5(D2)·+12(S3)·+6(D5)초.
- **D1 정답 확정(사용자, 2026-10-16)**: SurveyServiceImpl.java와 survey.write.js는 required(수정 대상). D1의 나머지 GT(survey_list.jsp, survey_write.jsp, survey.list.js, sql_survey.xml, AlimtalkChnlDto.java, AlimtalkTmplDto.java)는 role을 유지하되 출처에 "사용자 미확인"을 표시했다(시나리오 파일 reason, 커밋 5ed755f, 고정 테스트 `EvalScenariosD1GtTest`). **따라서 Stage 3이 D1의 SurveyServiceImpl을 제거한 것(저장된 4건 모두)은 오판정이다.** 이번 진단 재생 6회(원본 3, 변형 3)에서도 제거돼 후보에 있었던 기록 10건이 모두 제거다.
- **D1 판단 자료(읽기·grep)**: (a) GT 출처: 시나리오 파일 D1 note가 "Stage0EngineRoutedBaselineHarnessTest의 survey_admin LlmOn 시나리오(SR, 발화, GT, MODIFY 필터)를 그대로 옮김"이고, 원본은 하네스 테스트의 하드코딩 `gtFiles`(`Stage0EngineRoutedBaselineHarnessTest.kt:276-279`, 같은 목록이 `:338`, `:737`, `:1029`에도 있음)다. 목록 근거를 적은 문서나 주석은 없다(docs 검색 결과 없음). 같은 이름들이 git에서 가장 먼저 나타나는 커밋은 b811c3d(2026-08-31, dffro77, "test: Add survey_admin Case B to IntegrationPipelineTest (relationship-based detection verified)")다. (b) 발송 채널(알림톡) 분기·렌더링 메서드: `SurveyServiceImpl.java:390-393` "알림톡 관련 추가"(`send_type`, `A`일 때만 `sender_key`·`template_code` 읽음), `:834-835` 요청의 `sendType`(발송구분 실시간/배치), `:881-882`, `:1475-1478` `procType == getAlimtalkTmplList`("알림톡 채널 및 템플릿 목록 조회", `selectAlimtalkChnlList`·`selectAlimtalkTmplList`), `:1813-1851` `sendMsgHyundaiCard`("발송 타입에 따라 알림톡/메시지 분배", `send_type == "A"`이면 `setAlimtalkYn("Y")`). `survey.write.js`(`src/main/webapp/resources/js/survey/survey.write.js`, 그래프의 리소스와 같은 파일): `:19-24` 발송 타입 `A`(알림톡) 선택 시 `#alimtalk_ch_sel`·`#alimtalk_tmpl_sel` 등을 보임, `:129-131` `loadAlimtalkData`(procType `getAlimtalkTmplList` 호출), `:169-175` `renderAlimtalkChannels`(`#sender_key`에 채널 옵션), `:180-187` `renderAlimtalkTemplates`(`#template_code`에 템플릿 옵션), `:193-199` 채널 변경 시 템플릿 렌더링. (같은 이름의 `bin/src/main/webapp/…/survey.write.js`는 다른 내용의 빌드 사본이다.)
- **정제문 순서 진단 재생(`Stage3OrderReplayTest`, `SeedFullReplayTest -Deval.seed.order`, 사전 예측·원문/변형 `pred_d1_order.md`)**: 원본 정제문 "…를 신규 개발하여 설문 발송 채널에 브랜드메시지를 추가한다."를 변형 "설문 발송 채널에 브랜드메시지를 추가한다. …를 신규 개발한다."(절 순서만 변경, 연결어미 "개발하여"→"개발한다"만 바뀜)로 바꿔 사내 Qwen에서 순차 3회씩 재생.
  - Stage 3(`dumps_defer`의 D1 요청, SurveyServiceImpl이 후보 [4]에 있는 가장 최근 V2 덤프, 후보 27개 중 신규 6개, 복원 검증 일치): SurveyServiceImpl 유지 원본 0/3, 변형 0/3, 유지 파일 수 6/6/6 모두 동일, 시간 39초 / 40초. 사유 원문(변형): "기존 Survey/Address 관련 파일들은 브랜드메시지 채널 추가와 무관하여 제거", "설문/주소 관련 파일([4],[8]~[21])은 브랜드메시지 배치 기능과 무관하여 제거".
  - 시드(첫 호출부터, 운영 temperature 0.0): SurveyServiceImpl 시드 포함 원본 0/3, 변형 0/3, 도구 결과에 등장 3/3 대 0/3. 시드 목록 원문: 원본 3회 모두 `[AlimtalkTemplateBatchJob, AlimtalkTemplateBatchRepository, AlimtalkTemplateBatchRunner, AlimtalkTmplDto]`(턴 4, 114초), 변형 3회 모두 같은 4개에 `IbCenterApiService`가 더해진 5개(턴 4, 112~115초). 변형의 첫 시도 3회는 SR 끝 줄바꿈 때문에 `reorder` 함수의 검사가 실패해 오류였고(도구 오류, 모델 호출 전), 후행 공백을 보존하도록 고쳐 변형 3회를 다시 돌렸다.
  - **사전 판정**: 변형에서도 Stage 3 유지 0/3, 시드 포함 0/3(둘 다 원본과 같거나 1/3 이하) → **"단계 판단 문제"**. 정제문 앞에 적용 대상을 두는 clarify 쪽 설계안은 만들지 않는다. 예측(변형 각 1/3)은 둘 다 0/3으로 달랐으나 판정은 예측과 같다.
  - **Stage 3 제거 사유가 나오는 프롬프트 위치(보고만, 변경 없음)**: `FileRelevanceVerifier.kt:131-132` 역할("수정하지 않아도 요구사항 구현에 아무런 영향이 없는 파일"만 제거), `:138` 판정 원칙("제거할 확실한 근거가 없으면 유지"), `:143-150` 신규 파일 판정 원칙(`:146` "요구사항이 단일 기능 추가/수정이고, 기존 파일 1~2개 수정으로 구현 가능한 경우", `:147` "기존 REQUIRED 파일의 수정으로 동일한 목적을 달성할 수 있는 경우", `:149` "기존 패키지에 동일 역할의 클래스/파일이 이미 존재하는 경우", `:150` 단일 책임 원칙), 신규 후보의 정보 블록은 `buildBatchPrompt`의 `(신규 제안)` 출력(`:409`)과 신규 파일 제안의 reason(`RequirementAnalysisPipeline.kt:193`, `NewFileDetector.kt:129-132` 스키마)으로 만들어지며 덤프에서는 "…Runner 클래스를 신규 개발하여 설문 발송 채널에 브랜드메시지 처리를 추가한다"처럼 새 파일이 요구 전체를 수행하는 것으로 읽히는 문장이 들어 있다. 시드 선정 쪽 위치는 B-46의 `AgenticSeedSelector.kt:49-50`(1~4개, 신규 개발이어도 기준 클래스를 Seed로)과 같다.
- **새 평가 SR 초안**: 아키텍처 유형별 평가 SR 12개(holdout 4개)의 초안을 `docs/eval_sr_drafts.md`에 만들었고 사용자 승인을 기다린다(승인 전에는 시나리오 파일에 반영하지 않는다).

### [B-49] 아키텍처 유형별 SR 첫 측정(기본 16개, holdout 제외)과 읽기 확인 (2026-10-20)
- **측정 조건**: 사내 Qwen(Qwen/Qwen3.8-27B-FP8), UI 경로, 프록시 덤프(`dumps_arch1`), 1회, 기본 대상 16개(기존 8 + 새 SR 8, holdout SR-03·06·09·10 제외). 실행 순서는 SR-07, SR-11을 먼저 두고 나머지는 기존 순서(`-Deval.first`, 커밋 f26d774). 보고서 `live_eval_20261020_110533`, git f26d774, 총 3,358초. 사전 예측 `pred_eval_arch1.md`(실행 전 저장, 이후 수정 없음).
- **예측 대조(다른 점만)**:
  - 분석 실패 0건 예측에 실패 1건: SR-08이 clarify 단계에서 `OpenAI 서버 통신 실패: HTTP response code: 524`로 중단됐다(분석 단계가 아니라 clarify 단계, 아래 참조). 300초 넘은 호출 0건은 예측대로(최대 호출 125초). 타임아웃 0건.
  - 총 시간 예측 약 3,140초에 실측 3,357초(+217초, +7%). 시나리오별 차이가 큰 것: SR-07 예측 120초에 실측 223초(+103), SR-11 240초에 305초(+65), S1 119초에 158초(+39), D1 333초에 359초(+26), D5 200초에 228초(+28). 나머지는 ±35초 안(D2 -35, S3 -32).
  - required GT 가장 많은 칸 예측: SR-04, SR-05, SR-12는 "유지"로 맞았다. SR-01은 유지 1, 후보 밖(손실) 1로 동률(예측 유지). SR-02는 "후보 밖(손실)" 예측에 실측 4/4 유지(손실 0). SR-07은 "후보 밖(손실)" 예측에 실측 유지 1, Stage 3 제거 1(손실 0). SR-11은 "Stage 3 제거" 예측에 실측 4/4 유지(제거 0). SR-08은 측정 못 함.
- **시나리오별 결과**(hit30 = 유지 = Recall@30의 분자, 분모는 required GT):

  | 시나리오 | 유형 | 시간(초) | 분석 | Recall 전체(uncertain 제외) | 유지 / Stage 3 제거 / 제외 / 손실 / 미관찰 | 최종 목록 수 |
  |---|---|---|---|---|---|---|
  | SR-07 | MVC_MYBATIS / 기존 수정 | 223 | 성공 | 1/2 (1/2) | 1 / **1**(PrFaqMapper.xml) / 0 / 0 / 0 | 6 |
  | SR-11 | ANYFRAME / 신규 추가 | 305 | 성공 | 4/4 (4/4) | 4 / 0 / 0 / 0 / 0 | 4 |
  | D1 | MVC_MYBATIS | 359 | 성공 | 2/8 (2/8) | 2 / 1(AlimtalkTmplDto) / 0 / 5 / 0 | 10 |
  | D2 | ANYFRAME | 218 | 성공 | 1/5 (1/5) | 1 / 3(APCMMTrcdIsSVC, SVCImpl, BIZ) / 0 / 1 / 0 | 2 |
  | D3 | ANYFRAME | 220 | 성공 | 4/5 (4/5) | 4 / 1(ACMBTBAPC024DEM) / 0 / 0 / 0 | 4 |
  | D4 | MVC_MYBATIS | 119 | 성공 | 4/4 (4/4) | 4 / 0 / 0 / 0 / 0 | 7 |
  | D5 | MVC_MYBATIS | 228 | 성공 | 1/1 (1/1) | 1 / 0 / 0 / 0 / 0 | 1 |
  | S1 | JPA | 158 | 성공 | 3/3 (3/3) | 3 / 0 / 0 / 0 / 0 | 4 |
  | S2 | JPA | 149 | 성공 | 3/4 (3/4) | 3 / 0 / 0 / 1(AdminController) / 0 | 6 |
  | S3 | MVC_MYBATIS | 163 | 성공 | 3/4 (3/4) | 3 / 0 / 0 / 1(KmcisController) / 0 | 4 |
  | SR-01 | MVC_MYBATIS / 기존 수정 | 210 | 성공 | 1/2 (1/2) | 1 / 0 / 0 / 1(ips_common.js) / 0 | 2 |
  | SR-02 | MVC_MYBATIS / 신규 추가 | 244 | 성공 | 4/4 (4/4) | 4 / 0 / 0 / 0 / 0 | 9 |
  | SR-04 | JPA / 기존 수정 | 158 | 성공 | 1/1 (1/1) | 1 / 0 / 0 / 0 / 0 | 2 |
  | SR-05 | JPA / 제외 지시 | 180 | 성공 | 2/2 (2/2) | 2 / 0 / 0 / 0 / 0 | 3 |
  | SR-08 | MVC_MYBATIS / 모호 | 158 | **실패**(clarify 단계, HTTP 524) | 측정 못 함 | 측정 못 함 | 0 |
  | SR-12 | ANYFRAME / 모호 | 267 | 성공 | 2/2 (1/1) | 2 / 0 / 0 / 0 / 0 | 5 |

  네 갈래 합이 gtTotal과 다른 성공 실행은 없다(합계 일치). 시나리오 15개가 집계 대상이고 SR-08은 실패라 제외된다.
- **진행 판단용 합계(required 전체 합)**:
  - 전체 51건 중 유지 36, Stage 3 제거 6, 후보 밖(제외 지시) 0, 후보 밖(Stage 1~2 손실) 9. Stage 3 제거가 나온 시나리오 4/15.
  - 새 SR(SR-08 제외 7개) 17건 중 유지 15, Stage 3 제거 1(SR-07의 PrFaqMapper.xml), 제외 0, 손실 1(SR-01의 ips_common.js). Stage 3 제거가 나온 시나리오 1/7.
  - 기존 8개 34건 중 유지 21, Stage 3 제거 5(D1 1, D2 3, D3 1), 제외 0, 손실 8(D1 5, D2 1, S2 1, S3 1). Stage 3 제거가 나온 시나리오 3/8.
- **유형별 합계**(보고서 "유형별 required GT 합계"):

  | 구분 | 시나리오 | 분모 | 유지 | Stage 3 제거 | 제외 | 손실 | 제거가 나온 시나리오 |
  |---|---|---|---|---|---|---|---|
  | SPRING_MVC_MYBATIS | SR-07, D1, D4, D5, S3, SR-01, SR-02 (SR-08은 측정 못 함) | 25 | 16 | 2 | 0 | 7 | 2/7 |
  | ANYFRAME_AP | SR-11, D2, D3, SR-12 | 16 | 11 | 4 | 0 | 1 | 2/4 |
  | SPRING_BOOT_JPA | S1, S2, SR-04, SR-05 | 10 | 9 | 0 | 0 | 1 | 0/4 |
  | 기존 기능 수정(srType) | SR-07, SR-01, SR-04 | 5 | 3 | 1 | 0 | 1 | 1/3 |
  | 기존 패턴 신규 추가 | SR-11, SR-02 | 8 | 8 | 0 | 0 | 0 | 0/2 |
  | 제외 지시 | SR-05 | 2 | 2 | 0 | 0 | 0 | 0/1 |
  | 모호 | SR-08(측정 못 함), SR-12 | 2 | 2 | 0 | 0 | 0 | 0/1 |
  | (srType 미지정) | D1~D5, S1~S3 | 34 | 21 | 5 | 0 | 8 | 3/8 |

  SPRING_BOOT_MYBATIS, SPRING_BOOT_JDBC, CUSTOM은 미측정 유형이다.
- **관찰(판정 없음)**:
  - Stage 3이 required GT를 지운 시나리오는 SR-07(1), D1(1), D2(3), D3(1) 네 곳이다. 새 SR 중에서는 SR-07 한 곳이다. D1에서는 SurveyServiceImpl이 이번에 유지됐다(과거 저장 4건과 재생 6건은 모두 제거였다). 제거된 것은 AlimtalkTmplDto였다. 1회 실행이라 우연인지 판단할 수 없다.
  - D2의 APCMMTrcdIsSVC, SVCImpl 제거(3건)는 B-48의 1-e 재생과 같은 파일이다. D3에서는 이번에 SVC, SVCImpl이 유지됐고 ACMBTBAPC024DEM이 제거됐다(143658에서는 5/5).
  - 제외 대상 노출(mustNotAppear): SR-05 0/2, SR-12 1/1(APCADUpdtVerMngtBIZ가 최종 목록에 나타남), D5 0/3. SR-12의 노출은 DEM·DVO를 두 BIZ가 함께 의존하는 구조와 같은 방향의 관찰이다(문서의 근거 보충 줄).
  - 엔진 질문은 SR-05, SR-12에서 0회였고, SR-12의 기대 질문 두 가지는 나오지 않았다(SR-12 원문에 스토어·OS·노출 조건이 이미 들어 있다). SR-08은 clarify 단계에서 중단돼 질문 기록이 없다.
- **SR-08 실패(원문)**: `clarify 단계 예외: AssertionError: [Error] OpenAI 서버 통신 실패: Server returned HTTP response code: 524 for URL: http://vllm.ixpertops.cloud/v1/chat/completions`. 이 run의 clarify 호출은 3건이었다: 제약 추출 9.8초(정상), 이어서 "기타: 당신은 엔터프라이즈 시스템 분석 어시스턴트입니다…"(125.5초, finish_reason 없음)와 "발화 번역(UI)"(125.3초, finish_reason 없음)이 약 125초 만에 끝났다. 서버(또는 앞단 게이트웨이) 응답 오류이고 재시도하지 않았다(스트림 재시도는 도구 호출 인자 절단에만 있고 HTTP 오류에는 없음). 이번 지시 범위 밖이라 재실행하지 않았다.
- **읽기 확인 4a (PatternExtractor.kt:91-92)**: `extractSnippet`은 `File(file.path)`로 소스를 읽는다. `file.path`는 그래프의 프로젝트 기준 상대 경로이고(`FileNode.path`, MetaGraphModels.kt:135), `basePath`와 결합하는 코드는 없다. 그러나 `PatternExtractor`는 `SpecEnricher.kt`에서 타입으로만 쓰일 뿐 생성하는 곳이 main과 test 전체에 없다(`PatternExtractor(`, `SpecEnricher`, `RelationExpander(` 호출 grep 결과 없음. analyze.search 패키지에서 밖으로 import되는 것은 `Stage0GraphScanner.kt:3`의 `KeywordDecomposer`뿐). 결론: **운영에서는 스니펫이 들어가지 않음**(호출 경로 없음).
- **읽기 확인 4b (FileRelevanceVerifier.kt:423-431)**: `mdRoot.resolve("<파일명>.md")`에서 파일명은 확장자를 포함한다(예: `UserServiceImpl.java.md`). `mdRoot`는 `RequirementAnalysisPipeline.kt:235`에서 `project?.basePath ?: projectRoot?.absolutePath ?: ""`에 `docs`를 붙이므로 평가에서는 저장소의 `docs/`다. 저장소 `docs/`에는 파일 9개(하위 `adr/` 포함)가 있고 `*.java.md`, `*.xml.md`, `*.js.md` 형태의 파일이 없다. 측정 대상 시나리오 정답 파일 96개의 `<파일명>.md`와 일치하는 파일도 0개다. 결론: **현재 저장소 `docs/`에는 일치하는 .md가 없다**(평가에서 이 경로로 들어가는 문서 없음). 고치지 않았다.
- **부수 관찰**: ISM(`C:/scrad/workspace/ism-pjt/ism-api-bo`)과 APC(`C:/scrad/workspace/apc-pjt`) 그래프의 `projectRoot`에는 소스가 없고 `.wuwagent`만 있다. 분석 실행은 그곳의 `shadow_logs.jsonl`에 덧붙이고 계약 파일을 저장한다(RequirementAnalysisPipeline.kt:331, :423-425, ShadowLogger.kt:34-37). 이번 측정에서도 갱신된다.

### [B-50] 기준선 3회 확정, srType 결정, SR-08 재측정, 제외 문장 경로 읽기 확인 (2026-10-20)
- **기준선 채택**: 110533을 새 SR 첫 기준선(1회)으로 채택하고(사용자 승인), 같은 조건으로 2회 더 측정했다(`live_eval_20261020_155718`, `live_eval_20261020_165435`). 회차마다 별도 Gradle 실행, 기본 16개, holdout 제외, 사내 Qwen, UI 경로, `-Deval.first=SR-07,SR-11`. 총 소요 3,358 / 3,381 / 3,384초. 사전 예측 `pred_eval_arch23.md`(실행 전 저장).
- **srType 결정(사용자, 커밋 192a99f)**: D1 기존 패턴 신규 추가, D2 기존 기능 수정, D3 기존 패턴 신규 추가, D4 모호, D5·D5E·D5N 제외 지시, S1 기존 기능 수정, S2 기존 패턴 신규 추가, S3 제외 지시. `-Deval.first` 단위 테스트를 추가했고 검증은 전체 470/42/17, clarify 177/1/3, 실패 메시지 diff 0, 하네스 EXACT_MATCH 5종·MATCH_EXCLUDING 2종 일치, selftest 24/24(469에서 늘어난 1개는 `EvalScenariosArchTest.evalFirstMovesTheChosenScenariosToTheFrontAndKeepsTheRestInOrder`).
- **SR-08 재측정**: 1차는 524로 실패했다(총 182초). 이 run의 서버 오류 호출 3건은 모두 HTTP 524, 약 125초: #2 작업 요약, #4 작업 요약, #5 제약 추출. 2차는 성공했다(214초, Recall 3/3, uncertain 제외 1/1, required 유지 BadWordMgmtServiceImpl·StBwInfoMapper·stBwInfo.xml, 최종 목록 4개, 엔진 질문 0회). 이후 2회 반복에서도 SR-08은 모두 성공했다(238초, 210초, 엔진 질문 0회). 기대 질문(비슷한 단어의 기준 등)은 한 번도 나오지 않았다.
- **3회 기준선 결과**(SR-08은 110533에서 실패라 1회차 값으로 재측정 2차를 썼다):

  | 시나리오 | 회차별 hit/분모 | Recall 최소/평균/최대 | 시간(초) 회차별 |
  |---|---|---|---|
  | SR-07 | 1/2, 1/2, 1/2 | 50/50/50% | 223, 213, 215 |
  | SR-11 | 4/4 x3 | 100% | 305, 323, 312 |
  | D1 | 2/8, 2/8, 0/8 | 0/17/25% | 359, 334, 321 |
  | D2 | 1/5 x3 | 20% | 218, 195, 193 |
  | D3 | 4/5, 5/5, 5/5 | 80/93/100% | 220, 239, 236 |
  | D4 | 4/4 x3 | 100% | 119, 129, 123 |
  | D5 | 1/1 x3 | 100% | 228, 220, 210 |
  | S1 | 3/3 x3 | 100% | 158, 126, 159 |
  | S2 | 3/4, 3/4, 2/4 | 50/67/75% | 149, 157, 179 |
  | S3 | 3/4, 1/4, 1/4 | 25/42/75% | 163, 185, 180 |
  | SR-01 | 1/2 x3 | 50% | 210, 170, 208 |
  | SR-02 | 4/4 x3 | 100% | 244, 240, 213 |
  | SR-04 | 1/1 x3 | 100% | 158, 161, 150 |
  | SR-05 | 2/2 x3 | 100% | 180, 181, 206 |
  | SR-08 | 3/3 x3 | 100% | 214(재측정 2차), 238, 210 |
  | SR-12 | 2/2 x3 | 100% | 267, 269, 267 |

  required 네 갈래 합계(분모 54):

  | 구분 | 회차 | 분모 | 유지 | Stage 3 제거 | 제외 | 손실 |
  |---|---|---|---|---|---|---|
  | 새 SR 8개 | 1, 2, 3 | 20, 20, 20 | 18, 18, 18 | 1, 1, 1 | 0, 0, 0 | 1, 1, 1 |
  | 기존 8개 | 1 | 34 | 21 | 5 | 0 | 8 |
  | 기존 8개 | 2 | 34 | 20 | 4 | 0 | 10 |
  | 기존 8개 | 3 | 34 | 17 | 5 | 0 | 12 |
  | 전체 16개 | 1, 2, 3 | 54 | 39, 38, 35 | 6, 5, 6 | 0, 0, 0 | 9, 11, 13 |

  - 새 SR은 3회 모두 같다(유지 18/20). 기존 8개는 회차마다 흔들린다(유지 21 → 20 → 17).
  - **3회 중 2회 이상 Stage 3에서 제거**: D1 AlimtalkTmplDto(3/3), D2 APCMMTrcdIsBIZ(3/3), S3 CallbackInfoDto(2/3), S3 KmcisService(2/3), SR-07 PrFaqMapper.xml(2/3). 1회만: D2 APCMMTrcdIsSVC·SVCImpl, D3 ACMBTBAPC024DEM, SR-07 prFaqInfo.xml, D1 sql_survey.xml.
  - **3회 중 2회 이상 Stage 1~2에서 손실**: D1 survey_list.jsp·survey_write.jsp·survey.list.js·survey.write.js·AlimtalkChnlDto(모두 3/3), D2 APCMMTrcdIsInfSVO(3/3), S2 AdminController(3/3), S3 KmcisController(3/3), SR-01 ips_common.js(3/3), D2 APCMMTrcdIsSVC·SVCImpl(2/3). 1회만: D1 SurveyServiceImpl, S2 AuthService.
  - **제외 대상 노출**: D5 0/3, 0/3, 0/3. SR-05 0/2 x3. SR-12 1/1 x3(APCADUpdtVerMngtBIZ가 3회 모두 최종 목록에 나타남).
  - **서버 오류**: 1회차(110533) SR-08에서 HTTP 524 2건(분석 실패 1건), 2회차와 3회차는 0건. 300초 넘은 호출은 3회 모두 0건.
- **예측 대조**: 시나리오별 시간은 16개 모두 ±25% 범위 안. 분석 실패 0건 예측은 2·3회차에서 맞고, 서버 오류도 0건. 네 갈래 합계는 2회차(유지 38, 제거 5, 손실 11)가 ±3 범위 안, 3회차는 유지 35(범위 36~42 밖)와 손실 13(범위 6~12 밖)으로 벗어났다. 반복성 예측 중 D2의 SVC·SVCImpl이 반복 제거된다는 것은 틀렸다(Stage 3 제거는 1회, 나머지는 Stage 1~2 손실 2회). D1 화면 리소스 4개 손실, 제외 노출 3종(D5 0, SR-05 0, SR-12 1/1 x3)은 맞았다.
- **읽기 확인 3a(SR-12 제외 노출)**:
  - 대본의 제외 문장은 "관리 화면은 그대로 두세요."다. 발화 번역(110533 덤프 #168)은 ADD_CONSTRAINT(SCOPE_LIMIT)를 냈고 안내 문구는 "…관리 화면 비수정)을 추가했습니다."였다. EXCLUDE가 아니라 `excludedFiles`는 비어 있다(후보 항목 15개 중 `APCADUpdtVerMngtBIZ`가 없었다).
  - 제약 추출(#170)은 EXCLUDE_COMPONENT "관리 화면 수정 제외"를 정상으로 냈지만 증거 구문 "관리 화면은 그대로 두세요."가 후보 294개(289개 고유)로 풀려 단일 대상이 없고 `pendingExclusions=[]`, 엔진 질문 0회였다. 결과 요약의 미확정 경고에 후보 294개 이름이 모두 나열됐다.
  - `APCADUpdtVerMngtBIZ`는 시드가 아니다(시드 3개 `APCCSAppVerMngtBIZ`, `ACAOTBAPC003DEM`, `APCCSAppInfSVO`, 시드 근거에 이 파일은 "SR상 변경 제외"라고 적혀 있다). 후보 단계(그래프 확장)에서 Stage 3 후보 9개 중 4번으로 들어왔고, Stage 3이 유지했다(사유 "스토어/OS별 최신버전 조회 로직 수정").
  - 결론: UI 경로에서 발화로 한 제외가 `excludedFiles`에 들어가지 않는 기존 문제와 같은 계열이다. 다만 SR-12에서는 확인 단계가 없는 것에 더해 증거 구문이 294개 후보로 풀려 대상이 특정되지 않았다.
- **읽기 확인 3b(SR-08 524)**:
  - 실패한 호출: 110533은 #164 작업 요약(`generateTaskSummary`, `Stage0ClarificationEngine.kt:722-`)과 #165 발화 번역(`ClarifyUtteranceTranslator.translate`). 재측정 1차는 #2·#4 작업 요약과 #5 제약 추출(`extractConstraints`, `:1156-`). 모두 비스트림 `chat` 호출이고 약 125초 만에 524로 끝났다.
  - 운영 코드는 HTTP 오류를 재시도하지 않는다. `OpenAIClient.chat`은 `IOException`을 잡아 "[Error] OpenAI 서버 통신 실패…" 문자열을 응답 내용으로 돌려준다(`OpenAIClient.kt:215-226`). 재시도는 `chatWithTools`의 도구 호출 인자 절단에만 있다(`:253-270`).
  - 사용자 화면: 발화 번역 실패는 "발화 의도를 파악하는 중 오류가 발생했습니다. 다시 말씀해 주세요."(`ClarifyUtteranceTranslator.kt:123-127`, 엔진 `:924`, 라우터 `WebviewActionRouter.kt:308`로 전달). 제약 추출 실패는 조용히 기본 제약으로 대체된다(`Stage0ClarificationEngine.kt:1205-1208`). 정제문 실패는 원문으로 대체된다(`:1146-1149`). 작업 요약 실패는 null이고 화면에 "요약을 만들지 못했습니다"가 나온다(`App.tsx:434`).
  - 평가에서만 다른 점: 테스트에서는 `logger.error`가 AssertionError를 던지고, 이것은 `catch (e: Exception)`에 잡히지 않아 clarify 단계 예외로 run이 중단된다. 운영(IDE)에서는 같은 524가 위 안내문이나 기본값으로 이어지고 세션은 계속된다.
- **다음 단계**: 제외 발화가 실제로 결과에서 빠지게 하는 설계는 별도 문서화한다(안 A 추천, 안 C는 다음 단계, B-51).

### [B-51] 제외 발화가 실제로 결과에서 빠지게 하는 변경(0단계 문구, 1단계 안 A)과 평가 (2026-10-20)
- **상태: 합격 기준 1개 미충족, 커밋은 로컬에만 두고 push하지 않았다.** 로컬 커밋: e6858c8(0단계), 60ebdf1(1단계), a0a2951과 66510a2(평가 변형), 이 문서.
- **1번 사전 확인(`resolveExclusionCandidates`, LLM 없음)**: 코드와 `DomainDictionary` 내장 사전을 따라 한 재현이다. 재현은 실측과 먼저 대조했다(SR-12 "관리 화면은 그대로 두세요." 294개와 앞 10개, D5 "알림톡 배치는 건드리지 말고" 2개, "건드리지 마세요" 0개가 모두 일치).

  | 입력 | 후보 수 | 앞쪽 | 대상 포함 |
  |---|---|---|---|
  | survey_admin "로그인 쪽 컨트롤러" | 0(교집합 없음) | 없음 | IpsController 아니오 |
  | survey_admin "로그인 쪽 컨트롤러는 건드리지 마세요"(발화 전체) | 12(부분 매칭, 저신뢰) | AuthDto, UserDto, LoginUserDto, UserServiceImpl, AlimtalkTemplateBatchRunner, AuthVal, ApiService, ApiServiceImpl, IbCenterApiService, IbCenterApiServiceImpl | IpsController 11번째 |
  | survey_admin "알림톡 배치" | 2 | AlimtalkTemplateBatchJob, AlimtalkTemplateBatchRunner | AlimtalkTemplateBatchRepository 아니오 |
  | APC "관리 화면" | 12 | APCADSpacdTskCtlMngtBIZ, APCCOSpacdLgnBIZ, APCCOMbotSvProcsBIZ 등 | APCADUpdtVerMngtBIZ 아니오(증거 "관리 화면은 그대로 두세요."일 때는 294개 중 5번째) |
  | member-market "관리자 화면의 조직 목록" | 81 | AdminManageResponse, AdminService, AdminRole, LoginRequest 등 | AdminService 2번째, AdminOrganizationResponse 20번째, OrganizationService(required)도 38번째로 포함 |

  이 결과로 후보 상한 10개가 필요하다는 점이 확인됐다(81개 제안이면 required 파일을 제외 후보로 보여 주게 된다).
- **0단계(e6858c8, 문구만)**: ADD_CONSTRAINT 안내는 모델의 message를 쓰지 않고 실제 상태를 말한다("제약 조건으로 기록했습니다: …. 이 제약만으로는 파일이 분석에서 제외되지 않습니다. 특정 파일을 빼려면 파일명을 지정해 주세요."; 제외 단어가 없는 제약에는 뒷문장이 없다). 위치 `ClarifyUtteranceTranslator.kt:254` 근방. 미확정 제외 경고(`ClarifyIntent.kt` `exclusionSummaryLines`)는 "다음 분석에서는 분석 중 나타나는 Stage 3 후보 확인 카드에서 해당 파일의 체크를 해제하거나, 새 세션(/clarify)에서 파일명을 지정해 다시 요청해 주세요."로 바꾸고 후보 이름을 5개까지만 쓴다("외 N개"). 단위 테스트 `ExclusionHonestMessageTest`(3개)와 `ExclusionDisplayTest.e5`가 문구를 고정한다.
- **1단계(60ebdf1, 안 A)**: `Stage0ClarificationEngine.kt`에서 processTurn의 제외 답변 처리와 제안을 함수로 추출(processTurn은 기존 정확 일치 판정·무제한 후보·기존 질문 그대로)하고 `processUtterance`와 `initSession`(SR 원문)에서 호출한다. 제약 추출은 제외 단어(`ExclusionWords`)가 있는 발화에서만 1회 추가하고, SR 원문은 init의 기존 제약 추출 결과를 그대로 쓴다(호출 수 불변). 후보 탐색은 증거 구문, 후보가 없으면 발화 전체로 한 번 더. 후보 1~10개면 제안과 확인 질문, 10개 초과는 "N개라 특정하지 못했습니다. 파일명을 알려 주시면 제외합니다", 0개는 "대상을 찾지 못했습니다"를 알린다. 증거 구문에 파일명을 직접 적으면 바로 제외한다. 같은 증거 구문으로는 한 번만 묻는다(대화 기록으로 판단). 답은 긍정어 포함+부정어 없음=확정, 부정어만=취소, 섞이면(예: "네 근데 Runner는 빼지 마세요") 확정하지 않고 다시 묻지도 않고 미확정으로 남긴다. 답으로 읽는 발화는 6단어·30자 이하로 제한한다(긴 제외 요청이 "아니요"로 오인되는 것을 막기 위해). 답 없이 확정하면 제외하지 않고 미확정 제외로 표시한다(제안 항목에도 반영). 확정된 파일만 `excludedFiles`에 들어간다. 저장 형식(`ClarifyIntent` v1.2)과 analyze는 바꾸지 않았다. 단위 테스트 `ExclusionProposalFlowTest` 13개.
- **검증**(`run_c4_accept.sh`): 전체 486/42/17(470에서 +3 ExclusionHonestMessageTest, +13 ExclusionProposalFlowTest), clarify 패키지 193/1/3, 실패 메시지 diff 0, 하네스 EXACT_MATCH 5종·MATCH_EXCLUDING 2종 일치, selftest 24/24. 0단계 직후 실행에서 `ExclusionDisplayTest.e5`의 신규 단언 오류(문구 "제외 요청"에 "외 "가 들어 있음)를 고친 뒤 통과했다.
- **평가**(`live_eval_20261020_175239`, 사내 Qwen, UI 경로, 1회, 사전 예측 `pred_excl_a.md`, 총 1,374초): D5X는 D5N과 같은 대본이다(두 번째 발화 "네"). SR-05X는 SR-05에 예/아니요 질문이 없어(후보 81개) 안내에 파일명 두 개를 적어 답하는 변형으로 바꿨다.

  | 시나리오 | 총(초) | clarify 시간 새 / 기준선 3회 | clarify 호출 새 / 기준선 | 엔진 질문 새 / 기준선 | Recall(uncertain 제외) | 제외 대상 노출 | excludedFiles |
  |---|---|---|---|---|---|---|---|
  | D5 | 236 | 117 / 97, 98, 83 | 8 / 7, 7, 7 | 2 / 2, 2, 2 | 1/1 (1/1) | 0/3 | [] |
  | D5X | 238 | 141 / 97, 98, 83 | 9 / 7, 7, 7 | 3 / 2, 2, 2 | 1/1 (1/1) | 0/3 | Runner, Job |
  | S3 | 201 | 110 / 84, 55, 53 | 8 / 6, 5, 5 | 1 / 0, 0, 0 | 2/4 (2/4) | - | [] |
  | SR-05 | 155 | 94 / 75, 102, 102 | 6 / 5, 6, 6 | 1 / 0, 0, 0 | **1/2** (1/2) | 0/2 | [] |
  | SR-05X | 184 | 143 / 75, 102, 102 | 8 / 5, 6, 6 | 1 / 0, 0, 0 | **1/2** (1/2) | 0/2 | AdminOrganizationResponse |
  | SR-12 | 358 | 170 / 113, 119, 95 | 8 / 6, 6, 5 | 1 / 0, 0, 0 | 2/2 (1/1) | 1/1 | [] |

  서버 오류 0건, 300초 넘는 호출 0건, 분석 실패 0건.
- **합격 기준 판정**:
  - D5X에서 Job과 Runner가 최종 목록에 없다 → 충족(excludedFiles에 둘 다 들어갔고 최종 목록은 SurveyServiceImpl 하나).
  - 제외 요청 하나당 추가 질문 1회 이하 → 충족(D5 제외 확인 1회, S3·SR-05·SR-05X·SR-12 안내 1회).
  - 제외 단어가 없는 시나리오의 clarify 호출 수 → 대상 6개는 모두 제외 단어가 있어 평가로는 해당 없음. 단위 테스트(f9)로 고정했다. 제외 단어가 있는 6개는 호출이 1~3건 늘었다(제약 추출 1회, 확인 질문이 남아 쓰인 대체 답변의 번역 호출 등).
  - **대상 6개의 required 유지 수 → 미충족.** 합 8, B-50 3회 시나리오별 최솟값의 합 9. SR-05와 SR-05X가 각각 1/2(기준선 2/2 x3)다. 두 run 모두 Stage 3이 OrganizationRepository를 제거했다(사유 "이미 findByStatus 메서드가 존재하므로 추가 수정 불필요"). 후보 목록(Service, Controller, Repository, Status)과 요구사항 문장은 기준선과 거의 같고(정제문 표현만 조금 다름), 제외 흐름이 Stage 3 입력에 들어가는 경로는 없다(init 안내와 미확정 제외 표시는 화면용). 1회 실행이라 변경의 효과인지 Stage 3 판정의 변동인지 가를 수 없다(기준선 3회는 모두 유지).
- **예측 대조(다른 점만)**: SR-05 Recall 2/2 예측에 1/2. SR-05X의 excludedFiles 두 파일 예측(약 70%)에 한 파일만(아래 결함). SR-12 clarify 시간 증가 +15~35초 예측에 +51~75초(확인 질문이 남아 대체 답변 경로의 번역 호출과 요약 호출이 더 쓰인 영향이 크다고 보이나 호출별로 분리하지는 않았다). 그 밖(D5, D5X, S3의 노출과 excludedFiles, SR-12 노출 1/1, 안내 문구)은 예측대로.
- **관찰된 결함(수정하지 않음)**:
  - SR-05X: 번역기가 두 번째 발화를 EXCLUDE로 분류해 파일 하나(AdminOrganizationResponse)만 제외하고 "AdminService.java는 현재 후보 목록에 존재하지 않아 제외할 수 없습니다"라고 안내했다. 이 발화가 EXCLUDE이면 제외 흐름을 건너뛰기 때문에 파일명을 직접 적은 AdminService는 처리되지 않았고, 안내문도 그래프에 있는 파일에 대해 사실과 다르다(후보 목록에 없을 뿐).
  - 평가 대본은 제외 질문에 이름이나 "네"로 답하지 않으면 대체 답변("잘 모르겠어요…")이 쓰인다. 이 답은 답으로 읽히지 않아(OTHER) 번역기를 거친다.
  - SR-12(후보 294개)와 S3(후보 12개)는 파일명을 알려 주지 않으면 노출이 그대로다. 클래스명을 모르는 사용자는 이 흐름으로 해결되지 않는다.
- **다음 단계 기록**: 안 C(Stage 3 후보 확인 카드에서 제외 요청에 해당하는 후보를 사전 해제 표시)는 후보 294개를 실제 후보 9개와 교집합해 하나로 좁히므로, 대상 클래스명을 모르는 사용자(SR-12 유형)에 맞다 **[정정(B-54): 교집합은 하나가 아니라 6개이고 required인 APCCSAppVerMngtBIZ를 3회 모두 포함한다. 파일명 없는 제외 요청의 자동 사전 해제는 required를 해제할 수 있어 하지 않는다]**. 웹뷰 변경이 필요해 이번 범위에서 제외했다. 안 A의 보완으로는 번역기가 EXCLUDE를 내도 증거 구문에 다른 파일명이 있으면 제외 흐름을 이어 가는 것을 후보로 둔다.

- **추가(2026-10-21): SR-05의 OrganizationRepository 제거는 Stage 3 변동으로 판정, 합격 처리하고 push함.**
  - **입력 비교(읽기)**: 기준선 3회(110533, 155718, 165435)와 안 A 실행(175239)의 SR-05, SR-05X Stage 3 입력을 덤프에서 비교했다.
    - 요구사항 문장은 거의 같다. 기준선1 "관리자 화면의 조직 목록은 유지하되, 일반 조직 목록에서는 활성 상태인 운영 중 조직만 표시하고…", 기준선2 "…은 그대로 유지하되, … 활성 상태인 운영 중인 조직만…", 기준선3 "…은 유지하되, … 활성 상태인 조직만 표시하고 대기 중인 조직은 숨기도록 수정해 주세요.", 안 A SR-05 "…은 그대로 유지하되, … 활성 상태인 조직만 표시하고 대기 중인 조직은 숨기도록 수정해 주세요."(기준선2와 같은 "그대로 유지하되" 표현, 기준선3과 같은 "활성 상태인 조직만" 표현의 조합). SR-05X 문장에는 "AdminService.java와 AdminOrganizationResponse.java는 수정에서 제외하세요."가 더해졌고 이것은 변형 대본의 효과다.
    - 후보 목록: 안 A SR-05는 기준선1·3과 같다(Service, Controller, Repository, Status). 기준선2는 Controller 대신 OrganizationResponse가 있었다. SR-05X는 Service, Repository 2개뿐이다.
    - 기준선3의 Stage 3 사유에도 "Repository는 상태별 조회 메서드가 이미 존재"라는 같은 설명이 있었지만 Repository는 유지됐다(requiredFiles n=3 "findByStatus 메서드 활용 또는 추가"). 같은 근거를 두고 결정이 다르게 나온 것이다.
  - **경로 확인(읽기): 경로 없음.** 안 A의 안내·확인 질문 텍스트는 대화 기록(`dialogueHistory`)과 `openQuestion`에만 들어가고, `dialogueHistory`는 화면 payload(`WebviewActionRouter.kt:174, 253, 309`)와 같은 질문을 다시 하지 않는지 보는 판단(`Stage0ClarificationEngine.kt`, `proposeExclusionsForUtterance`)에만 쓰인다. 정제문(`refineRequirement`, 입력은 원문과 `userStatements`)과 Stage 3 요구사항(`AnalyzeSections.buildStage3Base`, 본문 + 미확정 식별자 + 빠진 식별자)에는 들어가지 않는다. SR-05는 제안 항목이 만들어지지 않아(후보 81개) 미확정 식별자 경로(`PROPOSED_EXCLUSION` 항목의 `utteredIdentifier`)도 없다. 대본 발화(`userStatements`)는 기준선과 같다.
  - **측정(`live_eval_20261021_110134`, 현재 HEAD, `-Deval.only=SR-05 -Deval.runs=3`, 사내 Qwen, 순차)**: 3회 모두 init에서 "'관리자 화면의 조직 목록은 그대로 두세요' 관련 파일이 81개라 특정하지 못했습니다. 파일명을 알려 주시면 제외합니다." 안내가 나온 상태(안 A 활성)에서 OrganizationRepository가 유지됐다.

    | 회차 | 총(초) | Recall | OrganizationRepository | Stage 3 제거 사유 | 정제문 요약 |
    |---|---|---|---|---|---|
    | 1 | 168 | 2/2 | 유지 | 제거는 OrganizationStatus만("기존 상태값만 참조") | "…은 유지하되, … 활성 상태인 조직만 표시하고 대기 중인 조직은 숨기도록 수정하세요." |
    | 2 | 202 | 2/2 | 유지 | 제거는 Controller, Status | 기준선3과 같은 문장 |
    | 3 | 201 | 2/2 | 유지 | 제거는 Status | 기준선3과 같은 문장 |

    제외 대상 노출 0/2 x3, 서버 오류 0건, 분석 실패 0건. 엔진 질문은 안내 1회.
  - **판정**: 3회 중 3회 유지(합격 기준: 2회 이상 유지)이므로 안 A 실행 175239의 SR-05·SR-05X 제거는 Stage 3 판정의 변동이다. 이 판정으로 B-51의 "required 유지 수 미충족"은 해소된 것으로 본다(175239의 SR-05X는 기준선 비교 대상이 아닌 변형이다). SR-05X에서 번역기가 EXCLUDE를 내면 파일명 직접 제외 흐름이 건너뛰어지는 결함은 그대로 남아 있다(다음 수정 후보).

### [B-52] 발화에 적힌 파일명을 번역기 분류와 상관없이 제외하는 보완((나) 수정)과 SR-05X 판정 (2026-10-21)
- **상태: 지정한 합격 기준 3개는 모두 충족했지만, 수정의 목적(명시한 파일을 모두 제외하고 안내를 사실대로)은 실측에서 달성되지 않아 push하지 않았다.** 로컬 커밋: 1d52f97(수정과 테스트), 이 문서. 원격은 271f3d0 그대로.
- **1번 위치(읽기, 수정 전 기준)**:
  - a. 번역기가 EXCLUDE를 낼 때 파일명 직접 제외 흐름을 건너뛰는 분기: `Stage0ClarificationEngine.kt:1186`(`processUtterance`의 `translated.kind != UserActionKind.EXCLUDE` 조건).
  - b. "후보 목록에 존재하지 않아" 안내를 만드는 곳: 코드가 만드는 고정 문구는 `ClarifyUtteranceTranslator.kt:216`(EXCLUDE인데 targetId가 후보에 없을 때 `message ?: "지정하신 파일은 현재 후보 목록에 존재하지 않습니다."`)뿐이고, SR-05X에서 본 "AdminService.java는 현재 후보 목록에 존재하지 않아 제외할 수 없습니다"는 모델이 번역 응답의 `message`에 쓴 문장이다. EXCLUDE 분기가 그 `message`를 그대로 안내로 쓴다(`ClarifyUtteranceTranslator.kt:205-210`). 이 분기를 고정 문구로 바꾸려 했으나 `Stage0UtteranceProcessTest.testPhase33_…`가 모델 message를 그대로 확인해서 되돌렸고, 대신 엔진이 파일명 안내를 만들면 번역기 안내를 뺀다.
  - c. 그래프 전체에서 파일명을 찾는 기존 함수: 있다. `explicitExistingTargets`(`Stage0ClarificationEngine.kt:785`, `graph.files`와 `graph.resourceNodes` 검색, 안 A에서 만듦)와 B-12의 `resolveUserUtteredIdentifiers`(`:100`, `:135`). 새 함수는 만들지 않고 이것을 `explicitFileMentions`로 확장했다(그래프에 없는 파일명도 돌려줌, 약 +30줄).
- **변경(1d52f97, `Stage0ClarificationEngine.kt`만, 번역기는 수정 없음)**: 번역기가 EXCLUDE(또는 UNKNOWN, ADD_CONSTRAINT)를 내도, 번역기가 처리한 파일 말고 다른 파일명이 발화에 더 적혀 있으면 제약 추출 1회를 하고(`:1222` `needExtraction`), 제약 추출이 EXCLUDE_COMPONENT로 분류한 증거 구문 안의 파일명을 처리한다. 후보 항목에 있으면 제외 확정("다음 파일을 분석 대상에서 제외했습니다"), 후보에는 없지만 그래프에 있으면 REJECTED 항목으로 제외 목록에 넣고 "후보에는 없었지만 제외 목록에 넣었습니다", 그래프에 없으면 "<이름>을(를) 찾지 못했습니다"(`:855-886`, 안내 조합 `:920`). 확인 질문은 하지 않는다. 안내를 만들면 번역기 안내를 빼는 조건은 `:1238`.
- **검증**: 전체 490/42/17(486에서 +4, 새 테스트 g1~g4), clarify 197/1/3, 실패 메시지 diff 0, 하네스 EXACT_MATCH 5종·MATCH_EXCLUDING 2종 일치, selftest 24/24.
- **2-a 지시 항목과 테스트 대응**(`ExclusionProposalFlowTest`):

  | 지시 항목 | 테스트 |
  |---|---|
  | 파일명 2개 + EXCLUDE 분류 → 2개 모두 제외 | `g1_twoFileNamesWithAnExcludeClassificationExcludeBoth` |
  | 후보에는 없지만 그래프에 있는 파일 → 제외, 안내가 사실과 맞음 | `g2_fileNotInTheCandidatesButInTheGraphIsExcludedAndSaidSo` |
  | 그래프에 없는 파일명 → excludedFiles 변화 없음, "찾지 못했습니다" | `g3_fileNameMissingFromTheGraphIsReportedAndNothingIsExcluded` |
  | 파일명이 없는 발화 → 기존 결과와 같음 | `g4_utteranceWithoutFileNamesFollowsTheExistingFlow`(추가로 f1, f6, f7) |
  | 기존 단일 EXCLUDE → 결과 변화 없음 | `f11_singleExcludeByTheTranslatorIsUnchanged`와 기존 `Stage0UtteranceProcessTest.testInvariantA_ExcludeStatusReflectionAndIntentExtraction`(12개 모두 통과) |

  빠진 항목은 없다. 다만 g1~g3은 제약 추출 응답을 EXCLUDE_COMPONENT로 고정해 두었다. 실측에서는 모델이 같은 발화를 SCOPE_LIMIT으로 분류했는데(아래), 이 경우를 다루는 테스트가 없었다(테스트 공백).
- **2-b 번역기 안내를 빼는 조건(`:1238`)**: "파일명 안내(`exclusionNote`)를 하나라도 만들었을 때"다(`exclusionNote != null`, 그리고 번역기가 EXCLUDE이거나 UNKNOWN+후보 외). 추출한 파일명을 모두 처리했는지는 보지 않는다. "찾지 못했습니다" 줄도 안내에 포함된다.
- **2-c 파일명 2개 중 1개만 그래프에 있을 때(코드 `:920-924`)**: 엔진 안내에 두 파일의 결과가 모두 나온다(찾은 파일은 "다음 파일을…제외했습니다" 또는 "후보에는 없었지만…" 줄, 못 찾은 파일은 "…을(를) 찾지 못했습니다." 줄이 줄바꿈으로 이어짐). 테스트 `g3`가 못 찾은 줄과 제외 목록을 확인하지만 찾은 파일의 안내 줄 문구 자체는 단언하지 않았다(코드로 확인).
- **측정(`live_eval_20261021_131050`, SR-05X 3회, 사내 Qwen, 사전 예측 `pred_excl_b.md`)**:

  | 회차 | 총(초) | clarify(초/호출) | excludedFiles | 제외 대상 노출 | required 유지 | 엔진 질문 |
  |---|---|---|---|---|---|---|
  | 1 | 209 | 158 / 9 | AdminOrganizationResponse만 | 0/2 | 2/2 | 1 |
  | 2 | 193 | 139 / 9 | AdminOrganizationResponse만 | 0/2 | 2/2 | 1 |
  | 3 | 193 | 147 / 9 | AdminOrganizationResponse만 | 0/2 | 2/2 | 1 |

  화면 안내(발화 2): 1회차 "AdminOrganizationResponse.java 파일을 분석 후보에서 제외했습니다. AdminService.java는 현재 후보 목록에 존재하지 않아 제외할 수 없습니다.", 2회차 "…파일을 후보 목록에서 제외했습니다. AdminService.java는 현재 후보 목록에 존재하지 않아 제외할 수 없습니다.", 3회차 1회차와 같은 문장. 모두 번역기(모델) 안내 하나뿐이고 엔진 안내는 나오지 않았다. 서버 오류와 분석 실패는 0건.
- **판정**:
  - 합격 기준(제외 대상 노출 0/2 x3, 엔진 질문 기존보다 늘지 않음(1회, 안 A와 같음), required 유지 3회 중 2회 이상(3/3)) 3개는 모두 충족한다. 다만 노출 0/2는 이번 수정의 효과가 아니다. Admin 파일은 수정 전에도 결과에 나오지 않았다(SR-05X 175239도 0/2).
  - **수정의 목적은 달성되지 않았다.** AdminService는 3회 모두 제외되지 않았고(excludedFiles에 없음), 사실과 다른 안내("존재하지 않아 제외할 수 없습니다", AdminService는 그래프에 있다)가 3회 모두 나왔다.
  - **원인**: 발화 2의 제약 추출(호출 c6, c21, c36)이 이 문장을 EXCLUDE_COMPONENT가 아니라 SCOPE_LIMIT("AdminService.java와 AdminOrganizationResponse.java 파일 수정 제외")으로 3회 모두 분류했고, `proposeExclusionsForUtterance`가 EXCLUDE_COMPONENT인 증거 구문만 처리해서(`:847`) 파일명 처리 흐름이 시작되지 않았다. 추가된 제약 추출 호출(회차당 +1, clarify 호출 8 -> 9)은 효과 없이 시간만 늘렸다. 번역기 안내를 빼는 조건도 안내를 만들었을 때만 켜져서 모델 문장이 그대로 남았다.
  - Stage 3 변동 가림은 필요 없다(required 유지 3/3).
- **push 보류 사유**: 2-c의 의도(두 파일의 결과가 모두 화면에 나옴)가 실측에서 충족되지 않았다. 기준 3개는 충족이라 규칙상 push할 수 있으나, 동작하지 않는 보완을 올리는 것이라 보류하고 사용자 판단에 맡긴다.
- **다음 수정 후보(수정하지 않음)**:
  - 파일명 처리 대상을 EXCLUDE_COMPONENT 증거 구문으로만 한정하지 말고, 번역기가 EXCLUDE를 낸 발화(번역기가 제외 의도로 분류했다는 신호)에서는 SCOPE_LIMIT로 분류된 증거 구문의 파일명도 제외 대상으로 읽는다. SCOPE_LIMIT은 원래 "OrderServiceImpl만 수정" 같은 한정이라 파일명이 "수정할 파일"일 수 있으므로, 번역기가 EXCLUDE를 낸 경우나 증거 구문에 "제외, 빼"가 함께 있는 경우로 좁혀야 한다.
  - 번역기가 EXCLUDE를 낼 때 모델이 쓴 `message`를 쓰지 않고 고정 문구로 바꾸기(현재 `Stage0UtteranceProcessTest`가 모델 message를 확인하고 있어 그 테스트도 함께 바꿔야 한다).
  - 제약 추출이 SCOPE_LIMIT/EXCLUDE_COMPONENT로 갈리는 응답을 모두 담는 테스트 추가.
- **B-51 마무리**: B-51의 "SR-05X는 (나) 이후 판정" 항목은 이 문서의 판정으로 닫는다. SR-05X는 required 유지 3/3, 제외 대상 노출 0/2 x3으로 기준은 충족하지만 AdminService 제외와 안내의 정정은 되지 않았다.

### [B-53] 파일명을 발화 원문에서 결정적으로 읽어 제외하는 보완과 SR-05X 합격 (2026-10-22)
- **B-52 기준의 결함**: B-52의 합격 기준(제외 대상 노출 0/2, 엔진 질문 수, required 유지)은 수정 전에도 충족되는 값이라(Admin 파일은 원래 결과에 나오지 않는다) 수정의 효과를 가려내지 못했다. 실제로 B-52 측정에서는 기준을 모두 충족했지만 AdminService가 3회 모두 제외되지 않았고 사실과 다른 안내가 남았다.
- **새 합격 기준(모두 충족)**: 3회 모두 excludedFiles에 AdminService와 AdminOrganizationResponse가 둘 다 있음 / 3회 모두 화면 안내에 두 파일의 제외 사실이 나오고 "존재하지 않아" 같은 사실과 다른 문장이 없음 / clarify 호출 수 회차당 8 이하 / 엔진 질문 1 이하 / 제외 대상 노출 0/2, required 유지 3회 중 2회 이상.
- **1번 위치(읽기, 5d9e018 기준)**:
  - a. 1d52f97이 더한 제약 추출 호출은 `Stage0ClarificationEngine.kt:1229`(`extractConstraints(listOf(trimmed))`)이고, 호출 조건은 `:1222` `needExtraction`이다(번역기가 EXCLUDE일 때는 다른 파일명이 더 적힌 경우). 결과는 `proposeExclusionsForUtterance`(`:835`)가 EXCLUDE_COMPONENT인 증거 구문만 골라 쓴다(`:847`). 번역기가 EXCLUDE를 낸 경우의 이 호출은 파일명 처리에만 쓰였다.
  - b. 호출 순서: `needExtraction`(`:1222`) -> 제약 추출(`:1229`) -> `proposeExclusionsForUtterance`(`:1231`)의 파일명 분기(`:855-886`) -> 안내 조합(`:920-924`) -> 번역기 안내 억제(`:1238`) -> 화면 안내(`:1239`). 파일명 처리가 제약 추출의 분류 결과를 앞단에서 받는 구조라, 분류가 SCOPE_LIMIT이면 처음부터 건너뛰어졌다.
- **변경(8627fe5, `Stage0ClarificationEngine.kt`만, 번역기 코드와 프롬프트는 수정 없음)**:
  - 파일명은 발화 원문에서 결정적으로 읽는다(`explicitExclusionMentions`, `explicitFileMentions`). 파일명 형태는 확장자가 있는 토큰(.java, .kt, .xml, .properties, .yml, .yaml, .jsp, .js, .sql, .html, .css, .json)과 그래프 노드의 클래스명과 정확히 일치하는 이름이다. 그래프에 없는 이름은 확장자가 있는 것만 "찾지 못했습니다"로 알린다.
  - 처리 조건은 같은 절(문장 끝, 말고/하고/하되/이고/지만/면서/그리고에서 끊음)에 제외 표현이 있을 때뿐이다. "SurveyServiceImpl만"처럼 "만"이 붙은 이름은 수정 대상 한정이라 읽지 않는다. 번역기 분류와 제약 추출 분류는 조건에 쓰지 않는다.
  - 파일명을 처리한 경우 제약 추출 호출은 하지 않는다(제거). 같은 호출은 파일명이 없는 제외 발화의 안 A 흐름(후보 탐색, 제안, 안내)에서 계속 쓰이므로 그 경로에는 남겼다. 결과와 안내 문구는 1d52f97 규칙 그대로다(후보에 있음 / 후보 외 그래프에 있음 + "후보에는 없었지만" / 그래프에 없음 "찾지 못했습니다"). 엔진 안내가 만들어지면 번역기 안내는 쓰지 않고 안내에 추출한 파일 전부의 결과가 나온다. 확인 질문과 저장 형식 변경은 없다.
  - SR 원문(initSession)의 제외 요청은 기존대로 제약 추출 증거 구문 기반이다(이번 범위 밖).
- **테스트와 검증**: 새 테스트 `h1_scopeLimitClassificationStillExcludesBothFiles`, `h2_emptyOrFailedConstraintExtractionStillExcludesBothFiles`, `h3_noExclusionWordOrDifferentClauseMeansNoExclusion`(제외 표현 없음, D5 문장, 앞 절의 수정 대상), `h4_translatorMessageIsNotShownWhenTheEngineReportsTheFiles`. 전체 494/42/17(490에서 +4), clarify 201/1/3, 실패 메시지 diff 0, 하네스 EXACT_MATCH 5종·MATCH_EXCLUDING 2종 일치, selftest 24/24.
  - 기대가 바뀐 기존 테스트: `ExclusionProposalFlowTest.g1_…`(제약 추출을 더 부르지 않으므로 호출 수 기대 base + 1 -> base. 이유: 파일명 처리가 제약 추출에 의존하지 않는다), `Stage0UtteranceProcessTest.testInvariantE_NotInCandidatesAndUnknownNoOpInvariance`(안내 "존재하지 않습니다" -> "test_mapper.xml을(를) 찾지 못했습니다". 이유: 발화에 파일명이 있으면 엔진이 직접 그래프에서 찾아 안내하고 번역기 안내는 쓰지 않는다. 항목 불변 단언은 그대로), `Stage0UtteranceProcessTest.testPhase33_ThreeLiveUtteranceTypesAndContractNonPersistenceDiskGrep`(모델 안내문 marker1 -> "제외했습니다: order_write.jsp". 이유 같음. 제외 결과 단언은 그대로). g2~g4, f11을 포함한 나머지는 변경 없이 통과.
- **측정(`live_eval_20261022_140119`, SR-05X 3회, 사내 Qwen, 사전 예측 `pred_excl_c.md`)**:

  | 회차 | 총(초) | clarify(초/호출) | excludedFiles | 제외 대상 노출 | required 유지 | 엔진 질문 |
  |---|---|---|---|---|---|---|
  | 1 | 184 | 144 / 8 | AdminOrganizationResponse, AdminService | 0/2 | 2/2 | 1 |
  | 2 | 180 | 144 / 8 | AdminOrganizationResponse, AdminService | 0/2 | 2/2 | 1 |
  | 3 | 178 | 119 / 8 | AdminOrganizationResponse, AdminService | 0/2 | 2/2 | 1 |

  화면 안내(발화 2, 3회 모두 같음): "다음 파일을 분석 대상에서 제외했습니다: AdminOrganizationResponse.java" 줄과 "후보에는 없었지만 제외 목록에 넣었습니다: AdminService.java" 줄. 번역기 안내와 "존재하지 않아" 문장은 나오지 않았다. 서버 오류와 분석 실패는 0건. 최종 목록은 3회 모두 OrganizationService, OrganizationRepository.
- **판정**: 새 합격 기준 모두 충족(둘 다 제외 3/3, 안내 사실 3/3, clarify 호출 8 이하 3/3, 엔진 질문 1 이하 3/3, 노출 0/2 3/3, required 유지 3/3). 예측과 모두 일치했다. SR-05X는 합격이다.
- **남은 한계**: 파일명 규칙은 확장자가 있는 이름과 그래프의 클래스명 정확 일치만 읽는다(한글 이름, 패키지 경로만 적은 경우는 안 읽음). 절 나누기는 연결어(말고, 하고 등) 기준이라 "A는 빼고, B도 제외해 주세요"처럼 쉼표로만 이어진 두 이름은 같은 절로 본다. SR 원문의 제외 요청은 아직 제약 추출 분류에 의존한다.

### [B-54] 파일명 제외 규칙의 과잉 제외, 부정형 오인, 과소 제외 보완과 안 1 결정 (2026-10-22)
- **배경**: B-53 규칙을 기존 시나리오 문장 38개(기본 16 + holdout 4의 SR와 발화)와 합성 문장 6개로 읽기 전용 점검했다(`regress_rule.js`, LLM 없음). 실제 시나리오에서 파일명을 쓴 문장은 **0개**였다(제외 표현이 있는 8문장도 파일명이 없다). 그래서 "과잉 제외 0건"은 규칙을 검증한 값이 아니라 규칙이 작동할 기회가 없었다는 관찰이다. 합성 문장에서 과잉 제외 1건(#1), 과소 제외 1건(#3)이 나왔다.
- **변경(`Stage0ClarificationEngine.kt`만, cf53fe2)**:
  - a. `clauseConnective`에 `빼고|두고|놔두고|제외하고`를 추가했다. 연결어는 앞 절에 남긴다(원인: "A는 빼고, B는 수정"이 한 절이라 B도 제외됨).
  - b. "X만"은 같은 절에 수정 동사(수정|변경|고쳐|고치|추가|개발, 단 "수정하지"와 "변경하지"는 제외 표현이라 세지 않음)가 있을 때만 건너뛴다. 없으면 제외 대상이다("AdminService만 빼 주세요").
  - c. 같은 절에 `빼지 말|제외하지 말|빼지 마|제외하지 마`가 있으면 그 절은 제외하지 않는다.
  - d. 한 절에 제외 표현과 수정 동사가 있고 이름이 2개 이상이면 자동 제외하지 않고 "<이름들>은(는) 제외 요청인지 분명하지 않아 제외하지 않았습니다." 한 줄만 안내한다. 확인 질문과 안 A 흐름은 타지 않는다.
- **1-e 결과(새 테스트 `ExclusionFileNameRuleTest.i1_…`, 기대값 변경 없음, 13/13 일치)**:

  | 문장 | 제외된 파일 |
  |---|---|
  | AdminService는 빼고, OrganizationService는 수정해 주세요 | AdminService |
  | AdminService는 빼고 OrganizationService는 수정해 주세요 | AdminService |
  | AdminService, OrganizationService는 수정하고 AdminController는 빼 주세요 | AdminController |
  | AdminService만 빼 주세요 | AdminService |
  | AdminService.java 말고 나머지는 다 봐 주세요 | AdminService |
  | OrganizationRepository는 그대로 두고 OrganizationService만 수정 | OrganizationRepository |
  | AdminService 건드리지 마세요. OrganizationService는 수정 | AdminService |
  | AdminService랑 AdminController는 빼 주세요 | AdminService, AdminController |
  | AdminService는 빼지 말고 같이 봐 주세요 | 없음 |
  | AdminService는 수정하고 AdminController는 그대로 두세요 | AdminController |
  | 알림톡 배치는 건드리지 말고 SurveyServiceImpl만 수정 | 없음 |
  | OrganizationService만 수정하고 나머지는 그대로 | 없음 |
  | SR-05X 발화 2 원문 | AdminService, AdminOrganizationResponse |

  테스트 그래프는 member_market의 클래스명을 본뜬 작은 그래프다(외부 그래프 파일에 의존하지 않는다). 추가 테스트 `i2_ambiguousClauseIsNotExcludedAndGetsOneNoticeLine`(판단 불가 안내), `i3_singleNameWithModifyVerbIsStillExcluded`(이름 하나면 판단 불가가 아님).
- **1-f**: 갱신한 `regress_rule.js`로 실제 문장 38개를 다시 돌렸다. 결과는 이전과 같다(전부 "추출된 파일명 없음", 과잉 제외 0). 합성 6문장은 #1 AdminService만, #3 AdminService로 바뀌고 나머지는 그대로다.
- **검증**: 전체 497/42/17(494에서 +3: i1~i3), clarify 204/1/3(201에서 +3), 실패 메시지 diff 0, 하네스 EXACT_MATCH 5종·MATCH_EXCLUDING 2종 일치, selftest 24/24, 기존 h1~h4·g1~g4·f11 통과.
- **남은 한계**: 부정형 문장("A는 빼지 말고 같이 봐 주세요")은 파일명 제외는 하지 않지만, 제외 단어가 있으므로 파일명이 없는 경우의 안 A 흐름(제약 추출 후 제안 질문)은 그대로 탈 수 있다. 수정 동사 목록과 연결어는 고정 목록이다.
- **B-53 정정**: B-53에서 "기대가 바뀐 기존 테스트"는 3개다(`ExclusionProposalFlowTest.g1_…`, `Stage0UtteranceProcessTest.testInvariantE_NotInCandidatesAndUnknownNoOpInvariance`, `Stage0UtteranceProcessTest.testPhase33_ThreeLiveUtteranceTypesAndContractNonPersistenceDiskGrep`). 세션 중 "2개"로 말한 것은 틀렸다.
- **B-51 정정**: 안 C 근거였던 "후보 294개와 실제 후보 9개의 교집합이 하나로 좁혀진다"는 틀렸다. SR-12의 교집합은 6개(APCCSAppVerMngtBIZ, APCADUpdtVerMngtBIZ, APCCSAppVerMngtSVCImpl, APCCTArsCtfBIZ, APCPYSpacdStlmBIZ, APCCTMphCtfBIZ)이고 required인 APCCSAppVerMngtBIZ를 3회 모두 포함한다. S3의 제외 후보에는 required인 KmcisController가 있다. D5는 AlimtalkTemplateBatchRunner 하나, SR-05는 0개다. 파일명 없는 제외 요청의 자동 사전 해제는 required를 해제할 수 있다.
- **안 1 결정 사항 5개(사용자 지정 원문)**:
  1. 명시된 파일명을 카드에서 미리 해제하는 기능은 만들지 않는다. 그런 파일은 사전 필터(:227-231)에서 이미 빠져 카드에 도달하지 않는다. 대신 사용자 요청으로 제외된 파일을 접힌 목록에 "제외 요청" 배지와 함께 보여 주고 되살릴 수 있게 한다. 규칙이 잘못 제외했을 때의 안전장치이기도 하다.
  2. 되살리기는 LLM을 부르지 않는다. 고른 파일을 최종 목록과 lastResult 캐시(/implement 입력)에 바로 추가한다. 되살린 파일은 pinnedFiles로 같은 세션의 다음 분석에서만 유지하고 디스크에 쓰지 않는다.
  3. 접힌 목록은 removedFiles가 아니라 "카드 후보 − 최종 유지"로 만들어 암묵적 탈락도 포함한다. 정렬은 Stage 1-2 순위이고, required가 몇 번째에 있는지 기록한다.
  4. 파일명 없는 제외 요청에 대한 배너는 다음 단계로 미룬다. 자동 해제는 하지 않는다.
  5. 커밋은 A(Kotlin 데이터와 평가 측정, 화면 없음)와 B(WebView)로 나눈다.
- **합격 기준표(사용자 지정 원문)**:

  | 기준 | 현재 | 합격선 |
  |---|---|---|
  | Stage 3에서 빠진 required가 접힌 목록에 있는 비율 | 0/17 (목록 없음) | 17/17 |
  | 그중 상위 5위 안의 비율 | 없음 | 첫 측정값을 기준선으로 기록 |
  | 사용자 요청 제외 파일이 배지와 함께 보이는 비율 | 0 | 100% |
  | 되살린 파일이 /implement 입력에 포함 | 기능 없음 | 3/3 |
  | 같은 세션의 다음 분석에서 되살린 파일 유지 | 기능 없음 | 3/3 |
  | 되살릴 때 LLM 호출 수 | 해당 없음 | 0 |
  | 되살리기 클릭 수 | 불가 | 3 이하 (펼침, 체크, 확정). B에서 측정 |
  | 기존 동작 불변 | - | pinned·USER_EXCLUDED가 비어 있을 때 Stage 3 입력 불변 (B-55 재판정에서 변경) |

### [B-55] 빠진 후보 목록(removedCandidates)과 되살리기(restore)의 Kotlin 데이터·경로와 측정 (2026-10-22)
- **상태: 합격 기준 5개 중 4개 충족, 1개(복원 전 Recall·제외 대상 노출의 기준선 범위)는 문자 그대로는 미충족이라 push하지 않았다.** 로컬 커밋: 6cf0ad7(B-54 정정 docs), da4ddfd(src와 테스트).
- **2번 위치(읽기, 변경 전 코드 기준)**:
  - a. 카드 후보, 최종 유지, 순위: `RequirementAnalysisPipeline.kt`의 `targetFiles`(Stage 1 순서, `:166-219`)와 사전 필터 `:227-231`, `correctedFiles`(`:234`, 카드 후보와 Stage 3 입력, 순서가 카드 번호), 검증 결과 `verificationOutput`(`:283`), 최종 유지 `validatedTargetFiles`(`:294`), 결과 생성(`:414`). 이 다섯 값이 `:297`~`:414` 사이에서 함께 보인다. 변경 후 계산 위치는 같은 구간(`removedCandidates`, 현재 `:507` 부근).
  - b. 사전 필터에서 빠진 사용자 제외 파일: `allExcludedPaths`(`:225`)가 필터(`:227-231`)와 계약 생산(`:447-453`, 디스크 저장 `:473`)에서만 쓰였고 결과로는 나가지 않았다. 후보에 없던 제외 파일은 `targetFiles`에도 없으므로 결과에 실으려면 `allExcludedPaths` 자체를 써야 한다(실제로 그렇게 했다).
  - c. lastResult: 갱신은 `RequirementAnalysisPipeline.kt:480`(`lastResult = result`, 동반 객체 `:31`), 읽는 곳은 `WebviewActionRouter.kt:397`(`/implement`, `cachedResult.targetFiles`와 `summary`를 `ImplementService`에 전달).
  - d. pinnedFiles 위치: 디스크에 쓰는 객체는 `ClarificationContractStore.saveContract`(`:473`)와 `ClarifyIntentStore.saveIntent`(라우터 `/analyze-confirm`)이고 둘 다 pinned를 참조하지 않는다. 그래서 같은 파일의 별도 `object AnalysisSessionMemory`(메모리 전용)에 두었다. 세션 경계는 `begin(primaryReq)`의 요구사항 키 불일치로 비우고 `reset()`을 제공한다.
- **변경(da4ddfd, 허용 파일: `RequirementAnalysisPipeline.kt`, `FileRelevanceVerifier.kt`, `WebviewActionRouter.kt`(라우트 추가만), 평가 test)**:
  - 결과에 선택 필드 `removedCandidates: List<RemovedCandidate>?`(path, source, stageRank, reason). source는 STAGE3_EXPLICIT(모델 removedFiles)와 STAGE3_IMPLICIT(removedFiles에 없는데 최종 목록에서 빠짐: REQUIRED 사유가 짧아 코드가 제거한 경우, 카드에서 해제한 경우), USER_EXCLUDED. 목록은 (카드 후보 - 최종 유지) + 사용자 제외 파일(후보에 없던 것 포함)이고 USER_EXCLUDED가 먼저, 나머지는 카드 순위 순이다. USER_EXCLUDED의 순위는 사전 필터 전 목록의 순위(없으면 null).
  - `RequirementAnalysisPipeline.restore(paths)`: LLM 호출 없음, lastResult에 추가(순번 이어서 부여)하고 `AnalysisSessionMemory`에 pin, 목록에 없는 경로는 `ignored`로 반환. 디스크와 clarifyIntent는 건드리지 않는다. `/restore` 라우트는 `{"paths": [...]}` 또는 배열을 받아 `analyze_restore` 메시지로 결과를 보낸다(화면은 B).
  - 같은 세션의 다음 분석: pinned는 사전 필터(`activeExcludedPaths`)와 Stage 3 입력에서 빠지고 검증 뒤 최종 목록에 더한다. pinned가 비어 있으면 기존 경로와 동일하다.
  - `FileRelevanceVerifier.VerificationOutput.removedReasons`와 `WEAK_REASON`(모델이 준 제거 사유, 근거 미제시 제거). noticeLines, 마크다운, 저장 형식은 변경 없음.
  - 평가: 실행마다 세션 메모리 초기화, "목록에 있는 required를 전부 되살린다" 정책, 보고서에 "빠진 후보 목록과 되살리기" 절(목록 크기, USER_EXCLUDED, 빠진 required의 목록 포함·순위·source, 복원 LLM 호출, 복원 전후 Recall, /implement 입력 포함).
- **테스트**: `RemovedCandidatesRestoreTest` r1~r7(암묵적 탈락 STAGE3_IMPLICIT, USER_EXCLUDED 맨 앞, restore 후 lastResult 포함과 LLM 호출 0, 같은 세션 다음 분석에서 유지, USER_EXCLUDED 복원 후 사전 필터 통과와 디스크 불변, 새 세션 pinned 비어 있음, 필드 없는 옛 결과), `EvalReportSplitTest.reportShowsTheRemovedListAndRestoreMeasurement`. 전체 505/42/17(497에서 +8), clarify 211/1/3(204에서 +7), 실패 메시지 diff 0, 하네스 EXACT_MATCH 5종·MATCH_EXCLUDING 2종 일치, selftest 24/24, 기존 h·g·i·f11 통과.
- **4-a 오프라인 재생(기준선 3회 덤프, LLM 없음, `replay_a.js`)**: 제거된 required 17건이 모두 목록에 있다(**17/17**). 목록 크기는 중앙값 8, 최대 24(시나리오×회차 47건). 상위 5위 이내 11/17(기록만). source는 17건 모두 STAGE3_EXPLICIT.

  | 회차 | 시나리오 | 제거된 required | 카드 순위/후보 수 |
  |---|---|---|---|
  | 110533 | SR-07 | PrFaqMapper.xml | 13/30 |
  | 110533 | D1 | AlimtalkTmplDto.java | 6/27 |
  | 110533 | D2 | APCMMTrcdIsSVC / APCMMTrcdIsSVCImpl / APCMMTrcdIsBIZ | 7/18, 3/18, 2/18 |
  | 110533 | D3 | ACMBTBAPC024DEM | 2/9 |
  | 155718 | SR-07 | prFaqInfo.xml | 5/30 |
  | 155718 | D1 | AlimtalkTmplDto.java | 6/27 |
  | 155718 | D2 | APCMMTrcdIsBIZ | 2/16 |
  | 155718 | S3 | KmcisService.java / CallbackInfoDto.java | 3/11, 1/11 |
  | 165435 | SR-07 | PrFaqMapper.xml | 13/30 |
  | 165435 | D1 | sql_survey.xml / AlimtalkTmplDto.java | 9/21, 5/21 |
  | 165435 | D2 | APCMMTrcdIsBIZ | 2/16 |
  | 165435 | S3 | KmcisService.java / CallbackInfoDto.java | 3/11, 1/11 |
- **정정(2026-10-22 설계 보고와 B-54 이전 판본의 수치)**: "제거 required 17건 중 6건이 removedFiles에 없다"와 "화면에 이름이 보이는 것 11/17"은 틀렸다. 덤프를 이름으로 맞출 때 GT 이름(확장자 없음)과 경로 파일명이 달라 6건을 놓친 집계 오류다. 올바른 값은 17건 모두 모델의 removedFiles에 있었고(IMPLICIT 0), 결과 요약의 "Stage 3에서 제외된 후보 N개" 줄(앞 10개 이름)에도 17/17이 이름으로 나온다. 따라서 이번 변경이 새로 주는 것은 "이름이 보이는가"가 아니라 "되살릴 수 있는가"(0/17 -> 17/17)이다. 목록을 removedFiles가 아니라 "후보 - 유지"로 만드는 이유는 REQUIRED 사유가 짧아 코드가 제거하는 경우(WEAK_REASON)와 카드 해제를 같이 담기 위해서다.
- **4-c 실측(사전 예측 `pred_restore_a.md`, 사내 Qwen, `live_eval_20261022_160038`(기본 16개 1회), `live_eval_20261022_160437`(SR-05X 1회))**:
  - 기본 16개: 성공 15, **SR-08 실패(clarify 단계 서버 HTTP 524, 분석 미실행)**. 실패는 처음 3개가 아니므로 중단 조건에는 해당하지 않는다(기준선 1회차의 SR-08 실패와 같은 종류).
  - Stage 3가 뺀 required 3건(D1 AlimtalkTmplDto 순위 6, D2 APCMMTrcdIsBIZ 순위 2, SR-07 PrFaqMapper.xml 순위 13)이 모두 목록에 있다. 되살림 3건, **복원 LLM 호출 0**, /implement 입력(lastResult)에 되살린 파일 모두 포함 3/3, required 적중 합 37 -> 40.
  - 목록에 없는 빠진 required(후보 밖): D1 5건(survey 화면 파일과 AlimtalkChnlDto), D2 3건, S2 AdminController, S3 KmcisController, SR-01 ips_common.js. 모두 Stage 1~2에서 후보로 오르지 못한 것이다.
  - USER_EXCLUDED는 기본 16개에서 모두 0이다(제외가 확정된 시나리오가 없었다: excludedFiles 비어 있음. D5, S3, SR-01, SR-05, SR-07, SR-11, SR-12는 "제외 요청이 확정되지 않았습니다" 상태).
  - SR-05X: excludedFiles와 목록의 USER_EXCLUDED에 AdminService, AdminOrganizationResponse **2/2**, 제외 대상 노출 0/2, required 2/2 유지, 엔진 질문 1, clarify 호출 8, 목록 크기 3(USER_EXCLUDED 2 + Stage 3 제거 1), 복원 대상 없음.
- **합격 판정**:

  | 기준 | 결과 |
  |---|---|
  | 4-a 17/17 | 충족 |
  | 4-c에서 Stage 3가 뺀 required 전부 목록에 있음 | 충족 3/3 |
  | SR-05X USER_EXCLUDED 2/2 | 충족 |
  | 되살릴 때 LLM 호출 0, /implement 입력에 되살린 파일 전부 포함 | 충족 |
  | 복원 전 Recall과 제외 대상 노출이 기준선 3회 최소~최대 범위 안 | **미충족(문자 그대로)**: (1) SR-08은 clarify 단계 서버 524로 분석이 실행되지 않아 Recall을 측정하지 못했다. (2) SR-12 제외 대상 노출이 0/1로 기준선 범위 1~1 밖이다. 기준선 3회는 APCADUpdtVerMngtBIZ가 최종 목록에 나왔지만 이번에는 Stage 3 모델이 removedFiles에 넣어(후보 번호 4) 빠졌고 그 파일은 목록(USER_EXCLUDED 아님, STAGE3_EXPLICIT)에 있다. 방향은 개선이고 pinned가 비어 있어 이번 변경이 선택에 영향을 주지 않은 경로이므로 모델 변동으로 보지만, 지정한 기준은 범위 안이라서 충족으로 세지 않는다. 나머지 14개 시나리오의 Recall과 D5·SR-05 노출은 모두 범위 안이다. |
- **예측과 다른 점**: (1) 예측 5(복원 전 Recall이 범위 안인 시나리오 13개 이상 70%)는 맞았지만(15개 중 15개가 범위 안), 16개 모두 범위 안이라는 35% 시나리오는 SR-08의 서버 오류로 깨졌다. (2) 예측 6(노출이 기준선과 같음 85%)은 SR-12에서 어긋났다(Stage 3 변동). (3) 예측 7의 "USER_EXCLUDED는 제외가 확정된 시나리오 5곳 이상"은 틀렸다: 실제로는 확정된 곳이 SR-05X뿐이었다(D5, S3, SR-05, SR-12 등은 안 A에서 미확정으로 남는 것이 현재 동작이다). (4) 예측 2(3~9건)는 3건(실측 1회차)으로 하한에 걸렸다. (5) 4-a의 source 17건이 모두 EXPLICIT인 것은 예측하지 않았다(정정 참조).
- **남은 일(범위 밖, 손대지 않음)**: WebView의 접힌 "제외된 후보 N개" 컴포넌트와 복원 버튼(B), 카드의 사전 해제·배너. 세션 경계는 요구사항 문자열 키와 `reset()`뿐이라, 같은 요구사항 문자열로 새 `/clarify` 세션을 시작하면 pinned가 남는다. `/clarify` 시작 분기에 `AnalysisSessionMemory.reset()` 호출을 한 줄 넣어야 하며 이는 라우터의 "라우트 추가만" 범위 밖이라 하지 않았다.

#### [B-55 보충] 판정 기준 변경, 기존 동작 불변 확인, /clarify 리셋 (2026-10-23)
- **기준 변경 이유**: "복원 전 Recall·제외 대상 노출이 기준선 3회의 최소~최대 범위 안"은 1회 측정으로는 모델 변동을 가려내지 못한다. 이번 A가 선택에 영향을 주지 않았다는 근거는 범위 비교가 아니라 코드 경로의 불변 확인(아래 3-a 표와 테스트 r9)으로 대신한다. 이에 따라 합격 기준표의 마지막 행을 "pinned·USER_EXCLUDED가 비어 있을 때 Stage 3 입력 불변"으로 바꿨다(B-54 원문대로).
- **실측 결과 그대로**(`live_eval_20261022_160038`, `160437`): SR-12의 제외 대상 노출은 0/1이다(기준선 1~1). 이번 실행에서 Stage 3 모델이 APCADUpdtVerMngtBIZ(후보 번호 4)를 removedFiles에 넣었고, 그 파일은 STAGE3_EXPLICIT로 목록에 있다. SR-08은 clarify 단계에서 서버 HTTP 524로 분석이 실행되지 않았다. **SR-08은 서버 오류로 판정 제외**한다(Recall을 측정하지 못했으므로 범위 비교 대상이 아니다). 나머지 14개 시나리오의 Recall과 D5·SR-05 노출은 기준선 범위 안이었다.
- **정정 재확인**: 제거된 required 17건은 모두 모델 removedFiles에 있었고 결과 요약의 "Stage 3에서 제외된 후보 N개" 줄(앞 10개 이름)에도 17/17이 나온다(IMPLICIT 0). 앞서 적은 "6건이 removedFiles에 없다"와 "화면에 이름이 보이는 것 11/17"은 이름 맞춤 오류였다.
- **/clarify 리셋(src 커밋)**: `WebviewActionRouter.kt`의 `/clarify` 분기에서 새 세션을 시작할 때(`saveSession` 전, previousContract 격리 바로 아래) `AnalysisSessionMemory.reset()`을 한 줄 호출한다. 같은 요구사항 문자열로 restore한 뒤 새 `/clarify`를 시작해도 pinned가 남지 않는다. 테스트 `r8_newClarifySessionWithTheSameRequirementClearsPinned`(라우터 소스에서 호출 위치 확인 + 리셋 뒤 같은 요구사항 분석에서 되살렸던 파일이 Stage 3 후보로 다시 감).
- **기존 동작 불변 확인(3-a, `git diff b7dbaa4 da4ddfd -- src/main`, 줄 번호는 da4ddfd 기준)**: pinned가 비어 있으면(그리고 사용자 제외가 없으면) 아래 줄은 모두 no-op이다. 사용자 제외가 있어도 사전 필터 집합이 기존과 같아서 Stage 3 입력은 같다.

  | 줄 | 영향 대상 | no-op? | 근거 |
  |---|---|---|---|
  | `RequirementAnalysisPipeline.kt:299` `allExcludedPaths` | 사전 필터 입력 | 예 | 변경 없는 줄 |
  | `:302` `AnalysisSessionMemory.begin(primaryReq)` | 상태만 | 예 | 키 기록과 pinned 정리뿐, 출력에 쓰이지 않음 |
  | `:303-304` `pinnedPaths`, `activeExcludedPaths = allExcludedPaths - pinnedPaths` | 사전 필터 입력 | 예 | pinned가 비면 `allExcludedPaths`와 같은 집합 |
  | `:306-311` `eligibleTargetFiles` | 사전 필터 결과 | 예 | 같은 집합으로 같은 조건(isNotEmpty)과 같은 필터 |
  | `:313` `correctedFiles` | 카드 후보와 Stage 3 후보 | 예 | 입력이 같고 줄은 변경 없음 |
  | `:328` `stage3Candidates` | Stage 3 후보 목록 | 예 | pinned가 비면 `correctedFiles` 그 객체 |
  | `:330` `requestConfirmation(stage3Candidates)` | 카드 입력 | 예 | 같은 목록(기존은 `correctedFiles`) |
  | `:335` `stage3Candidates.filter { it.path in pathSet }` | 사용자 선택 | 예 | 같은 목록에서 같은 필터 |
  | `:340` `filesToVerify = userSelection ?: stage3Candidates` | 검증기 후보 입력 | 예 | 같은 목록 |
  | `:364` `verifier.verify(fullRequirement, filesToVerify)` | 검증기 요구사항 문장·후보·프롬프트 | 예 | `fullRequirement` 조립 줄(:344-363)과 `FileRelevanceVerifier`의 프롬프트·도구 정의는 변경 없음 |
  | `:375-379` `withPinned`, `sortByDependency(withPinned, ...)` | 최종 목록 계산 | 예 | pinned가 비면 `verifiedFiles` 그대로 |
  | `:449` `noticeLines(...)`와 `:427-` 마크다운 | noticeLines, 마크다운 출력 | 예 | 변경 없는 줄이고, `removedCandidates` 계산(`:500-520`)은 마크다운 생성 뒤에 있으며 출력에 쓰이지 않음 |
  | `:500-520`, `:521-527` 결과 필드 | 결과 객체 | 예(출력 불변) | 새 필드 `removedCandidates`만 추가. 목록·요약·경고·신규 제안 값은 같음 |
  | `:46-60`, `:69`, `:76-105`(`AnalysisSessionMemory`, 필드, `restore`) | 새 코드 | 예 | `/restore`와 테스트만 호출 |
  | `FileRelevanceVerifier.kt:56`, `:80`, `:230`, `:251`, `:271`, `:294`, `:346`, `:377`, `:394` | 검증 결과 | 예 | `removedReasons`는 추가 필드이고 `removed`·`files`·`unjudged`·`candidatePaths`·`noticeLines` 값은 같음. `:271`의 `WEAK_REASON`은 이전 리터럴과 같은 문자열. `:297`은 공백만 바뀜 |
  | `WebviewActionRouter.kt:392-415` `/restore` 분기(추가 23줄, 삭제 0줄) | 새 라우트 | 예 | 기존 분기에 닿지 않음 |

  "아니오"는 없다.
- **3-b 테스트 `r9_stage3InputAndOutputsAreUnchangedWhenNothingIsPinnedOrExcluded`**: 모의 검증기가 받은 후보 목록(번호·순서)이 파이프라인 줄을 거치지 않고 같은 공개 함수(Stage 1 탐색, `correctPaths`)로 만든 기대 correctedFiles와 같고, 요구사항 구간이 요구사항 문장과 같다. 최종 목록은 `sortByDependency`로 따로 만든 기대 목록과 같고, 마크다운에 제외 안내 줄과 파일 표가 기존 형식으로 나오며 새 필드 어휘(`removedCandidates`, `STAGE3_`, `USER_EXCLUDED`, `되살`)가 없다. 마크다운 전체 문자열의 동일성은 검사하지 않았다(안내 줄·표 행·헤더와 어휘 부재로 확인).

### [B-56] SR-08 HTTP 524 원인 특정(읽기)과 접힌 목록 정렬 규칙 오프라인 비교 (2026-10-23)
- **범위**: 읽기와 오프라인 계산만(LLM 호출·평가 실행·`src/main` 수정 없음). 스크립트와 출력은 `build/`(`sort_rules.md`, `sort_compare.js`, `sort_compare.out`)에 있고 커밋하지 않는다.

#### 1. SR-08의 524
- **1-a 발생 건수**: live_eval 보고서 20개(호출 1,825건) 중 200이 아닌 응답은 **524 8건뿐**이고 다른 5xx와 연결 끊김은 0건이다. 8건은 모두 SR-08의 clarify 단계, 비스트림, 경과 125.0~125.5초다. SR-08은 6개 보고서에 있고 그중 3개(110533, 145305, 160038)에서 발생했다(50%).

  | 보고서 | 시나리오 | 단계(호출 번호) | 요청 문자 수 | 경과 |
  |---|---|---|---|---|
  | 20261020_110533 | SR-08 | 작업 요약(164) | 118,194 | 125.5초 |
  | 20261020_110533 | SR-08 | 발화 번역(165) | 197,588 | 125.3초 |
  | 20261020_145305 | SR-08 | 작업 요약(2) | 118,194 | 125.3초 |
  | 20261020_145305 | SR-08 | 작업 요약(4) | 118,408 | 125.1초 |
  | 20261020_145305 | SR-08 | 제약 추출(5) | 2,214 | 125.1초 |
  | 20261022_160038 | SR-08 | 작업 요약(155) | 118,194 | 125.5초 |
  | 20261022_160038 | SR-08 | 작업 요약(157) | 118,408 | 125.1초 |
  | 20261022_160038 | SR-08 | 제약 추출(158) | 2,214 | 125.2초 |
- **1-b 실패한 호출의 코드와 값**:
  - 작업 요약: `Stage0ClarificationEngine.kt:1047-1051`(`client.chat`, `maxTokens = 500`), 후보 설명 `:1029`(활성 후보 전부, 상한 없음). 발화 번역: `ClarifyUtteranceTranslator.kt:114-118`(`maxTokens = 300`), 후보 문맥 `:148`(전부). 제약 추출: `Stage0ClarificationEngine.kt:1528-1532`(`maxTokens = 500`).
  - 셋 다 `onChunk`를 넘기지 않아 **stream=false**다(`OpenAIClient.kt:91` `"stream" to (onChunk != null)`, `:93` `max_tokens`). 클라이언트 타임아웃은 연결 30초, 읽기 `settings.timeoutSeconds * 1000`(기본 300초, `SettingsState.kt:30`)이다(`OpenAIClient.kt:114-116`). **재시도는 없다**(재시도는 `chatWithTools`의 도구 인자 절단 1회뿐, `:253-270`).
  - 프롬프트 길이(덤프): 작업 요약 118,194·118,408자(서버 prompt 토큰 33.7K), 발화 번역 197,588자(53.4K 토큰), 제약 추출 2,214자. 후보가 약 630개 줄로 들어간다(요약 프롬프트 635줄, 번역 654줄).
- **1-c 성공 호출 분포**: 비스트림 200 929건은 최소 0.5초, 중앙 14.1초, 최대 124.8초다. 단계별 최대는 제약 추출 58.8초, 작업 요약 61.4초(D4의 105K자 호출), 정제문 생성 11.7초, 발화 번역 124.8초다. SR-08의 같은 요약·번역 호출은 정상일 때 18~31초에 끝난다(6회 중 3회). 100초를 넘은 비스트림 성공은 **1건**(160038의 발화 번역 124.8초, 125초 직전에 응답)이고, 100초를 넘은 스트림 성공은 Stage 3에서 40건(107~315초)이다. **스트림 888건은 5xx가 0건**이다(단, 스트림 요청은 최대 53K자이고 TTFB 최대 7.8초라 118K~197K자 프롬프트에서의 스트림은 측정된 적이 없다).
- **1-d 프록시 흔적**: **확인 불가**. 프록시와 덤프는 응답 헤더를 저장하지 않고(`UsageProxy`의 `ProxyCall`에 헤더 필드 없음), 524 응답 본문은 16자(해석 실패)로만 남아 있다. 다만 524가 모두 125.0~125.5초에 몰리고 같은 호출이 124.8초에 성공한 사실은 **첫 응답 바이트 없이 약 125초가 지나면 중간 계층이 끊는다**는 것과 맞는다.
- **1-e 사용자 화면의 실패 표시**: 운영에서는 524가 예외로 번지지 않고 `OpenAIClient.kt:215-223`이 `"[Error] OpenAI 서버 통신 실패: …"`를 돌려주며 호출한 쪽이 폴백한다. 작업 요약은 `TaskSummaryScheduler.kt:52-58`에서 FAILED가 되어 `App.tsx:434`가 "요약을 만들지 못했습니다"를 보인다. 발화 번역은 `ClarifyUtteranceTranslator.kt:121-126`의 "발화 의도를 파악하는 중 오류가 발생했습니다. 다시 말씀해 주세요."가 안내로 나간다. **제약 추출은 `Stage0ClarificationEngine.kt:1533-1535`에서 조용히 기본 제약(OTHER)으로 대체되어 사용자에게 아무 표시가 없고, 제외 단서와 제약이 사라질 수 있다.**
- **평가에서 "실패"로 보인 이유(평가 도구 쪽 사정)**: `OpenAIClient.kt:224`의 `logger.error`가 IntelliJ 테스트 환경에서 `AssertionError`를 던져 clarify 단계 예외로 기록된다(`SeedRationaleReplayTest.kt:153`의 주석이 같은 현상을 적고 있다). 운영에서는 로그만 남고 위 폴백으로 진행한다.
- **원인 요약**: (1) 후보 약 630개를 상한 없이 담은 118K~197K자 비스트림 요청이 (2) 서버 쪽에서 가끔 정체되어(같은 호출이 보통 18~31초), (3) 첫 바이트 없이 약 125초가 지나 중간 계층이 524로 끊는다. 작은 제약 추출 요청(2.2K자)이 524가 난 두 건은 같은 서버 대기열에서 큰 요청 뒤에 막힌 것으로 보이며, 이 대기열 가설은 서버 지표가 없어 확인하지 못했다.
- **1-f 해결안**:

  | 안 | 수정 파일 | 예상 줄 수 | 사용자가 겪는 변화 | 출력 영향 |
  |---|---|---|---|---|
  | A 비스트림 `chat`에 읽기 타임아웃 90초와 524/502/503/504 또는 타임아웃 시 1회 재시도 | `OpenAIClient.kt`(`:114-116`, `:215-223`) | 25~30 | 정체 시 90초 뒤 재시도(최악 약 180초, 지금은 125초에 실패 후 폴백). 둘 다 실패하면 지금과 같은 메시지 | 없음(같은 프롬프트, 성공하면 폴백 대신 정상 결과). 정체된 서버에는 같은 큰 요청이 한 번 더 간다 |
  | B 세 호출(요약, 번역, 제약 추출)에 no-op `onChunk`를 넘겨 stream=true | `Stage0ClarificationEngine.kt`(`:1047`, `:1467`, `:1528`), `ClarifyUtteranceTranslator.kt`(`:114`) | 4~8 | 첫 바이트가 일찍 오면 524 유형이 사라져 100초 넘게 걸려도 성공한다. 대기 시간은 그대로 | 없음(같은 디코딩). 스트림 경로는 `reasoning_content`를 읽지 않는다. **118K자 이상에서는 효과가 측정된 적이 없다**(헤더가 첫 토큰 뒤에 오면 효과 없음) |
  | C 후보 줄을 상한(예: 상위 60개와 사용자가 언급·확정한 항목)으로 줄임 | `Stage0ClarificationEngine.kt`(`:1029` 부근), `ClarifyUtteranceTranslator.kt`(`:148`) | 25~40 | 호출당 18~31초가 수 초로 줄 것으로 예상(프롬프트가 약 1/10, 실측 아님). 정체 확률도 낮아진다 | **있음**: 번역이 프롬프트에 없는 후보를 고를 수 없고 요약 문장이 달라진다. 측정이 필요 |
- **추천: A.** 출력에 영향이 없고 비스트림 clarify 호출 전부(제약 추출 포함, 지금 침묵 폴백되는 것)를 한 곳에서 덮으며, 정체가 일시적이었다는 증거(같은 호출이 다른 회차에 18~31초)가 재시도의 효과를 뒷받침한다. B는 효과가 측정된 적 없는 크기에서 가설이고 C는 품질 측정이 먼저라서 A 뒤에 따로 다룬다.

#### 2. 접힌 목록 정렬 규칙 오프라인 비교
- **규칙 정의(비교 전에 `build/sort_rules.md`에 고정)**: 입력에 GT를 쓰지 않는다. 연결(R1)은 그래프 `relationships`의 1단계 연결(종류·방향 무관)과 `resourceNodes[].linkedTo`다. 같은 묶음(R2)은 같은 패키지(디렉터리)이거나, 확장자와 역할 접미사(serviceimpl, controller, dto 등 고정 목록)와 끝 숫자를 뗀 이름이 같거나 한쪽이 다른 쪽의 접두어(짧은 쪽 4자 이상)인 경우다. R1은 유지 파일과 연결된 후보 먼저, R2는 같은 묶음인 후보 먼저, R3는 둘 중 하나라도 해당하는 후보 먼저이며 모두 그 안과 나머지는 R0 순서다. R0는 현재(USER_EXCLUDED 먼저, 나머지는 카드 순위)다.
- **대상**: 기준선 3회의 제거 required 17건 + A 측정(`live_eval_20261022_160038`)의 3건 = 20건(D1 5, D2 6, D3 1, S3 4, SR-07 4). 목록은 da4ddfd 로직(카드 후보 − 최종 유지)과 같고, 순위는 그 목록 안의 위치다. 같은 시나리오의 회차 반복은 독립 표본이 아니다. R0의 이 정의에서 5위 이내는 16/20이고, 카드 번호(stageRank) 기준으로는 17건 중 11건이다(B-55의 11/17은 후자).

  | 규칙 | 5위 이내 | 평균 순위 | 최대 순위 | R0보다 나빠진 건수 | 5칸 넘게 나빠진 건수 |
  |---|---|---|---|---|---|
  | R0 | 16/20 | 3.60 | 9 | 0 | 0 |
  | R1 | 15/20 | 3.90 | 11 | 5 | 0 |
  | R2 | 16/20 | 4.00 | 15 | 2 | 1 |
  | R3 | 16/20 | 4.00 | 15 | 2 | 1 |

  | 시나리오 | 건수 | R0 5위 이내 / 평균 | R1 | R2 | R3 |
  |---|---|---|---|---|---|
  | SR-07 | 4 | 1/4 / 6.3 | 0/4 / 10.3 | 1/4 / 6.3 | 1/4 / 6.3 |
  | D1 | 5 | 4/5 / 5.8 | 4/5 / 3.4 | 4/5 / 7.0 | 4/5 / 7.0 |
  | D2 | 6 | 6/6 / 1.8 | 6/6 / 2.2 | 6/6 / 1.8 | 6/6 / 2.2 |
  | D3 | 1 | 1/1 / 1.0 | 1/1 / 1.0 | 1/1 / 3.0 | 1/1 / 1.0 |
  | S3 | 4 | 4/4 / 1.5 | 4/4 / 1.5 | 4/4 / 1.5 | 4/4 / 1.5 |

  건별 순위와 규칙 퇴화 진단은 `build/sort_compare.out`에 있다. 규칙은 퇴화하지 않았다(R1은 목록의 0~9개, R2는 0~24개를 앞으로 올렸고 required 자신은 20건 중 12건이 R1에, 12건이 R2에 해당). R2는 같은 패키지 조건 때문에 SR-07에서 목록 24개 전부가 해당해 순서를 바꾸지 못했다. 규칙은 결과를 본 뒤 조정하지 않았다.
- **판정(사전 고정 규칙)**: R1은 5위 이내가 1건 줄었고(기준 +3 미충족), R2·R3는 변화가 없고(+0) R0보다 5칸 넘게 나빠진 건이 1건(D1 sql_survey.xml 9위 → 15위)이며, 세 규칙 모두 5위 이내로 새로 들어온 건이 없다. **채택 후보 없음 → R0 유지**. 구현 위치를 적을 대상이 없다.
- **의미**: R0가 이미 20건 중 16건을 5위 이내에 두고, 나머지 4건(SR-07 3건, D1 sql_survey.xml)은 목록이 24개인 SR-07처럼 같은 묶음 후보가 많은 경우다. 이름과 그래프 근접만으로는 이를 앞으로 끌어올리지 못했다. B 커밋은 정렬 변경 없이 진행한다.

### [B-57] 비스트림 호출 1회 재시도와 제약 추출 실패 안내, SR-08 3회 측정 (2026-10-23)
- **상태: 합격, push함.** 커밋: 9af7e0c(재시도), 2b346ca(제약 추출 실패 안내), 이 docs 커밋.
- **1-a 타임아웃 값**: 비스트림 성공 929건 중 경과가 60초를 넘은 것은 2건(D4 61.4초, SR-08 124.8초)이고, **70초, 80초, 85초, 90초, 95초, 100초, 105초, 110초, 115초, 120초를 넘은 건수는 모두 1건**(SR-08 124.8초, 정체로 보이는 호출)이다. 규칙("정상 호출을 끊는 건수가 0~1건인 가장 작은 값, 단 120초 이하")을 지정된 후보 값(90, 100, 110, 115)에 적용하면 **90초이고 그때 끊기는 건수는 1건**이다. 모든 값에 문자 그대로 적용하면 62초(D4의 61.4초에 0.6초 여유)가 되는데, 정상 호출의 여유가 너무 작아 택하지 않았다(90초도 끊는 건수가 같다).
- **1-b 비스트림 `chat` 호출처(onChunk 없음)**: `ClarifyUtteranceTranslator.kt:114`, `RequirementClarifier.kt:21,28`, `Stage0ClarificationEngine.kt:1047,1467,1528`, `ContractResolver.kt:440`, `DocGenerateAgent.kt:293`, `ImplementService.kt:133`, `IntentAnalyzer.kt:57`, `DomainDictionaryBuilder.kt:232`, `LlmCandidateSelector.kt:203`(max_tokens 8192), `LlmSeedSelector.kt:57`, `RelevanceFilter.kt:343,399`. 스트림 호출(`BaseAgent`, `TaskPipeline`, `ImpactAgent`, `ImplementationPipeline`, `TestGenerationPipeline`)은 `onChunk`가 있어 이번 변경이 닿지 않는다. **오래 걸릴 수 있는 호출**(문서 생성 `DocGenerateAgent`, 변경 지시서 `ImplementService`, `LlmCandidateSelector`)이 있어, 90초 타임아웃은 짧은 호출(max_tokens <= 1000)에만 적용하고 나머지는 설정 타임아웃(기본 300초)을 유지하며 상태 코드(502/503/504/524) 오류에만 재시도하도록 했다(지시한 "타임아웃이면 재시도"에서 벗어난 안전장치).
- **1-c 제약 추출 폴백 뒤 안내 위치**: `initSession`은 `Stage0ClarificationEngine.kt:331`(추출)과 `:382`(`initEcho`, 화면 안내와 대화 기록), `processUtterance`는 `:1282`와 `:1288`(`exclusionNote`, 이후 `dropTranslatorMessage`로 번역기 안내 대신 쓰임), `processTurn`(구 경로, UI는 도달하지 않음)은 `:438-440`(`echoMsg`)이다. UI 경로의 `processUtterance`는 제외 표현이 있고 파일명 직접 제외가 아닐 때만 제약을 추출하므로, 제외 표현이 없는 발화에서는 추출 자체가 일어나지 않는다. **`buildClarifyIntent`(`:1330` 부근)의 최종 추출은 화면 메시지 통로가 없고 `ClarifyIntent`(허용 범위 밖)에 필드를 더해야 해서 이번에 다루지 않았다.**
- **변경 1: 재시도(`OpenAIClient.kt`, 9af7e0c)**: 비스트림 `chat`에만 적용(`postWithRetry`, `:123`, `:269-295`). 짧은 호출(max_tokens <= 1000)의 읽기 타임아웃 90초, 상태 코드 502/503/504/524(`HttpURLConnection`이 "Server returned HTTP response code: N" IOException으로 던지므로 메시지에서 읽는다)이거나 짧은 호출의 타임아웃이면 1초 대기 후 1회 재시도. 첫 실패는 warn, 재시도까지 실패하면 기존 error 경로. 취소 중에는 재시도하지 않는다. 400 등은 재시도하지 않고 요청 본문·max_tokens·응답 처리는 변경 없음. 새 테스트 `OpenAIClientNonStreamRetryTest` n1~n6(524 후 200, 524 두 번, 타임아웃 후 200, 400, 스트림, 긴 호출). 생성자 파라미터(`shortReadTimeoutSec`, `retryWaitMs`, 기본값 있음)를 앞에 두어 기존 `OpenAIClient { … }` 호출은 그대로 컴파일된다.
- **변경 2: 제약 추출 실패 안내(`Stage0ClarificationEngine.kt`, 2b346ca)**: `extractConstraintsChecked`가 LLM 호출이 실패해(응답 없음, `[Error]`, 예외, 해석 불가) 기본 제약으로 바뀐 경우를 알리고(모델이 빈 목록을 준 경우는 실패 아님), 위 세 위치의 화면 안내에 "요청하신 조건을 해석하지 못했습니다. 제외하거나 한정할 파일이 있다면 파일명으로 다시 말씀해 주세요." 한 줄을 붙인다. 확인 질문과 저장 형식은 변경 없음. 실패한 경우 제외는 처리되지 않으며(기본 제약은 EXCLUDE_COMPONENT가 아님) 번역기의 등록 안내도 나가지 않는다. 파일명 직접 제외(8627fe5 규칙)는 추출과 무관하게 그대로 동작한다. 새 테스트 `ConstraintExtractionFailureNoticeTest` f1~f4.
- **검증**: 전체 517/42/17(507에서 +10: 재시도 6, 실패 안내 4), clarify 217/1/3(213에서 +4), 실패 메시지 diff 0, 하네스 EXACT_MATCH 5종·MATCH_EXCLUDING 2종 일치, selftest 24/24, 기존 r·h·g·i·f11 통과. 기대가 바뀐 기존 테스트는 없다.
- **4번 측정(사전 예측 `pred_retry.md`, 사내 Qwen, `live_eval_20261023_090830`, SR-08 3회)**:

  | 회차 | 총(초) | clarify 구간(초) | 재시도 발생 | 분석 | Recall | 비스트림 최대 경과(초) |
  |---|---|---|---|---|---|---|
  | 1 | 198 | 78 | 없음 | 성공 | 3/3 | 31.3 |
  | 2 | 227 | 90 | 없음 | 성공 | 3/3 | 31.2 |
  | 3 | 207 | 89 | 없음 | 성공 | 3/3 | 31.9 |

  524와 정체는 이번에 **재현되지 않았다**(3회 모두 비200 응답과 클라이언트 끊김 0건, 재시도 후보 0건). 그래서 재시도 경로의 효과는 실측으로 확인하지 못했고, 모의 서버 테스트(n1~n3)로만 확인했다. 재시도가 일어나지 않은 3회의 clarify 구간 78~90초는 기존 SR-08 성공 범위 87~92초 안이고 +10% 상한(101초)보다 작다. 실패 안내 줄은 어느 회차에도 나오지 않았다.
- **예측과 다른 점**: 예측 2("3회 중 한 번 이상 정체 55%")는 맞지 않았다(0회). 예측 1(clarify 구간 85~100초)은 78초(회차 1)가 범위 아래였으나 상한 판정에는 영향이 없다. 나머지(Recall 3/3, 안내 줄 없음, 스트림 불변)는 예측과 같다.
- **판정**: 테스트·검증 통과, 재시도 없는 회차의 clarify 시간이 기존 범위 +10% 이내, 재시도 뒤 실패한 회차 없음(재시도 자체가 없었음). 모두 충족.
- **기록(기준선)**: "상위 5위 기준선"은 **목록 위치 기준 16/20**이다(B-56, 카드 번호 기준 11/17이 아님). 접힌 목록 B 단계의 첫 측정값을 이 정의로 비교한다.
- **다음 과제(범위 밖, 손대지 않음)**: (1) 후보 줄 상한(B-56 안 C): 작업 요약 33.7K 토큰, 발화 번역 53.4K 토큰의 프롬프트가 후보 약 630개를 상한 없이 담고 있다. 서버의 `max_model_len`에 가깝다는 점은 사용자 지적이며 이 저장소에서는 값을 확인하지 못했으므로 서버 설정 확인이 필요하다. 출력에 영향이 있어 품질 측정이 필요하다. (2) `buildClarifyIntent`의 최종 제약 추출 실패는 화면에 알릴 통로가 없다(`ClarifyIntent` 변경 필요). (3) 정렬 변경 없음(R0 유지), WebView(B) 미착수.

### [B-58] 접힌 "제외된 후보 N개" 목록과 되살리기 화면(안 1의 B) (2026-10-23)
- **상태: 구현·자동 검증 통과, push함. 브라우저 확인 완료 / 실제 IDE 종단 확인 1회 대기(아래 체크리스트와 B-58 보충).** 커밋: 887cc87(src와 웹뷰 산출물), 이 docs 커밋.
- **1번 위치(읽기)**:
  - a. 분석 결과 경로: `WebviewActionRouter.kt:1534` `executeAnalyzePipeline` → `:1565` `pipeline.analyze(...) { chunk -> invokeLater { bridge.sendMessageChunk } }`로 마크다운이 스트리밍되고, 끝난 뒤 `invokeLater` 블록이 `extraText`를 보내고 `:1606` `bridge.sendMessage("chat", "", messageId)`(스트리밍 종료 신호)를 보낸다. 별도 메시지는 이 종료 신호 바로 뒤(`:1608`)에 보낸다.
  - b. 웹뷰의 메시지 종류별 처리는 `App.tsx`의 `window` message 핸들러에서 `data.subType`별 분기다(예: `analyze_clarify_summary`는 같은 id의 카드만 갱신, 변경 후 `:1122` `analyze_removed`, `:1131` `analyze_restore`). 렌더는 `MessageItem`(`:550`)이다. `Stage3ConfirmationForm.tsx`의 체크박스는 네이티브 input이 아니라 `useState<Set<string>>`과 행 전체 `onClick={() => handleTogglePath(path)}`, 아이콘 `CheckSquare`/`Square`(lucide)다. 새 패널도 같은 방식을 썼다.
  - c. 웹뷰 테스트 도구는 **없다**: `package.json`의 scripts는 dev/build/lint/preview뿐이고 vitest·jest 등이 없으며 기존 컴포넌트 테스트 파일도 없다. 의존성을 추가하지 않고, 패널 로직을 순수 함수 파일로 빼 node 내장 테스트 러너(타입 제거 실행)로 검증했다: `node --experimental-strip-types --test webview/test/removedCandidatesLogic.test.ts`(Gradle 빌드에는 연결하지 않았다. 테스트는 `tsconfig`의 `include: ["src"]` 밖이라 `tsc -b`에 영향이 없다).
  - d. 웹뷰 빌드 산출물 `src/main/resources/webview/index.html`과 `webview/tsconfig.tsbuildinfo`는 **커밋되는 파일**이고, 빌드 명령은 `webview`에서 `npm run build`(`tsc -b && vite build`)이며 Gradle의 `installWebviewDependencies` → `buildWebview` → `copyWebviewToResources` → `processResources`가 테스트와 빌드 때 자동으로 수행한다(`dist/`, `build/`는 무시).
- **변경**:
  - Kotlin: 스트리밍 종료 신호 뒤에 `analyze_removed` 메시지로 `removedCandidates`(path, source, stageRank, reason)를 보낸다. 목록이 없거나 비면 보내지 않고, 마크다운 출력은 그대로다. 본문 생성은 `RemovedCandidatesMessages`(라우터 파일 끝)로 분리했다. `/restore` 응답(`analyze_restore`)은 웹뷰가 준 `requestId`(결과 메시지 id)를 메시지 id로 써서 같은 결과 메시지를 갱신하게 하고, 본문은 되살린 경로·무시한 경로·남은 목록이다(이전에 있던 `targetFiles` 필드는 화면이 쓰지 않아 뺐다).
  - 웹뷰: `RemovedCandidatesPanel`(새 컴포넌트)과 `removedCandidatesLogic.ts`(순수 로직), `App.tsx`(메시지 두 종류 처리, `Message`에 선택 필드 `removedCandidates`, `restoreNotice`, 결과 아래에 패널 렌더).
    - 결과 아래에 접힌 "제외된 후보 N개". 펼치면 항목마다 체크박스, 파일명, reason(있을 때만 한 줄), USER_EXCLUDED에는 "제외 요청" 배지. 순서는 받은 그대로이고 화면에서 정렬하지 않는다. 10개를 넘으면 목록 영역을 스크롤(`maxHeight`)로 처리한다.
    - 하나 이상 체크하면 "선택한 파일 되살리기"가 켜지고, 누르면 `/restore`를 호출한다(경로는 목록 순서). 응답이 오면 되살린 항목이 목록에서 빠지고 N이 줄며 "N개를 되살렸습니다. 구현 대상에 포함됩니다."와, 무시된 경로가 있으면 "<파일명>은(는) 되살릴 수 없었습니다" 한 줄을 보인다.
    - 다 비면 패널은 숨기고 안내 문구만 남긴다(지시한 "패널을 숨긴다"에 더해, 되살렸다는 확인이 사라지지 않게 한 선택). `analyze_removed` 메시지가 없는 예전 결과에는 패널이 없다.
  - **클릭 수: 파일 1개를 되살리는 데 3회**(펼침, 체크, "선택한 파일 되살리기").
- **테스트와 검증**: 새 Kotlin 테스트 `RemovedCandidatesMessagesTest` m1~m5(직렬화·순서·생략 필드, 빈 목록과 null이면 메시지 없음, 필드 없는 옛 결과 호환, 되살리기 응답, 라우터가 종료 신호 뒤에 보내고 requestId로 응답하는지 소스 확인). 웹뷰 로직 테스트 12개(제목, 배지, reason, 파일명, 스크롤 기준, 체크와 버튼 활성 조건, 요청 내용과 목록 순서, 되살린 뒤 N 감소와 안내, 무시된 경로 안내, 다 비었을 때, 본문 해석)가 모두 통과했다. 웹뷰 빌드(`tsc -b && vite build`)가 성공했고 산출물에 새 문구가 들어갔다. 전체 522/42/17(517에서 +5), clarify 217/1/3(변경 없음), 실패 메시지 diff 0, 하네스 EXACT_MATCH 5종·MATCH_EXCLUDING 2종 일치, selftest 24/24, 기존 r·h·g·i·f11과 재시도(n1~n6)·실패 안내(f1~f4) 테스트 통과.
- **IDE 확인 체크리스트**(처음에는 IDE 확인 대기 항목이었고, 화면 동작은 아래 B-58 보충의 브라우저 확인으로 확인했다. 실제 IDE 종단 확인은 1회 대기): (1) 분석 결과 아래에 패널이 **접힌 채** 나온다. (2) 펼치면 체크박스·파일명·reason이 보이고 USER_EXCLUDED에만 "제외 요청" 배지가 있다. (3) 순서가 백엔드 순서(USER_EXCLUDED 먼저, 그다음 카드 순위)와 같다. (4) 11개 이상이면 목록 영역에 스크롤이 생긴다. (5) 체크 전에는 버튼이 꺼져 있고 체크하면 켜진다. (6) 누르면 N이 줄고 안내가 나오며 `/implement`가 되살린 파일을 포함한다. (7) 한 번 되살린 파일을 새 분석(같은 요구사항)에서 유지한다. (8) 목록을 모두 되살리면 패널이 사라지고 안내만 남는다. (9) 이 변경 이전의 채팅 기록(필드 없음)에는 패널이 없다. (10) 클릭 3회로 1개를 되살릴 수 있다.
- **r8**: 이번에 라우터를 고칠 수 있었지만 `/clarify` 분기는 IntelliJ `Project`와 브리지가 있어야 호출되어 단위 테스트로 호출할 수 없다. 리셋을 별도 함수로 빼도 "라우터가 그 함수를 부르는가"는 여전히 소스 확인이라, r8은 그대로 둔다(소스에서 호출 위치 확인 + 리셋 뒤 동작).
- **다음 과제**: (1) 재시도의 실측 검증: 524가 재현될 때까지 측정마다 재시도 건수(같은 단계·같은 요청 크기의 연속 호출)를 기록한다. (2) `max_model_len` 65,536 대비 번역 프롬프트 약 53.7K 토큰(약 82%): 후보 상한(B-56 안 C)을 정할 때 실제 토크나이저로 다시 잰다. (3) `buildClarifyIntent`의 최종 제약 추출 실패가 화면에 표시되지 않는다. (4) 부정형 발화가 안 A 흐름을 타는 문제(B-54 남은 한계). (5) 파일명 없는 제외 요청에 띄우는 배너(B-54 결정 4). (6) 웹뷰 컴포넌트 테스트 도구가 없어 접힘 기본값과 클릭 흐름은 IDE 확인에 의존한다(렌더 테스트 도구 도입은 의존성 추가라 별도 결정).

#### [B-58 보충] 브라우저 자동화로 화면 동작 확인 (2026-10-23)
- **0번 grep(`/restore` 응답에서 `targetFiles`를 뺀 것의 소비처)**: `analyze_restore`를 읽는 곳은 웹뷰 `App.tsx:1131`뿐이고 `restored`, `ignored`, `removedCandidates`만 쓴다(`removedCandidatesLogic.ts`의 `RestoreReply` 타입에도 `targetFiles`가 없다). 응답을 만드는 `WebviewActionRouter.kt:407`·`:1631`과 테스트 `RemovedCandidatesMessagesTest.kt`는 읽지 않는다. 평가 코드 `LiveEvalTest.kt:281`과 `RemovedCandidatesRestoreTest.kt`의 `targetFiles`는 응답이 아니라 `RequirementAnalysisPipeline.lastResult`와 분석 결과 객체의 필드를 읽는 것이다. 다른 라우트에도 `/restore` 응답의 `targetFiles` 소비처는 없다.
- **1번 위치**: 웹뷰가 Kotlin에서 메시지를 받는 곳은 `App.tsx:1057` `handleMessage`(`window`의 `message` 이벤트, `:1059`에서 `data.type === 'ai_message'`만 처리, `:1509` 등록)이고, Kotlin이 보내는 쪽은 `JcefBridge.kt:39-55` `sendMessage`(`window.postMessage` 실행)와 `:60` `sendMessageChunk`다. 웹뷰가 Kotlin으로 보내는 함수는 `window.sendToIde(JSON 문자열)`이고 `JcefMessageHandler.kt:40-46` `getInjectScript`가 주입하며 `:14-32`가 받아 `router.handleCommand`로 넘긴다. 도구: Chrome(`C:/Program Files/Google/Chrome/Application/chrome.exe`), Edge도 설치됨, Node v22.21.1.
- **확인 방법**: `build/ui-check/run.mjs`(커밋하지 않음, Node 내장 `http`와 `WebSocket`만 사용, 의존성 추가 없음). `src/main/resources/webview`의 **빌드 산출물**을 로컬 정적 서버로 띄우고, headless Chrome(`--remote-debugging-port`)에 CDP로 붙어 `Page.addScriptToEvaluateOnNewDocument`로 **모의 브리지**를 페이지 로드 전에 주입했다(운영 코드와 산출물은 바꾸지 않음). 모의 브리지는 `window.sendToIde` 요청을 기록하고 `/restore`에는 백엔드와 같은 형식(`restored`, `ignored`, `removedCandidates`)으로 `analyze_restore`를 같은 `requestId`로 응답한다. 결과 메시지는 Kotlin이 보내는 순서(`analyze_start` → `task_chunk` → `chat` → `analyze_removed`)로 `window.postMessage`했고, 클릭은 CDP `Input.dispatchMouseEvent`(실제 마우스 이벤트)다. **백엔드(Kotlin)와의 연결은 검증하지 않았다**(모의 브리지). 목록 14개는 A 측정(`live_eval_20261022_160038`)의 D2 덤프로 만들었고 D2에는 사용자 제외가 없어 카드 순위가 앞선 2개를 USER_EXCLUDED로 지정해 맨 앞에 두었다(백엔드 정렬 규칙대로, 한 항목에만 reason을 넣음). 스크린샷은 `build/ui-check/*.png`(00~08)에 있다.
- **2-d 결과(10/10 됨)**:

| # | 항목 | 결과 | 근거 |
|---|---|---|---|
| 1 | "제외된 후보 14개"가 접힌 상태로 표시 | 됨 | {"has":true,"expanded":"false","list":false,"text":"제외된 후보 14개"} |
| 2 | 체크박스·파일명이 받은 순서대로, USER_EXCLUDED 2개가 맨 앞에서 "제외 요청" 배지 | 됨 | 항목 14개, 순서 일치=true, 배지=[true,true,false,false,false,false,false,false,false,false,false,false,false,false], 앞 3개 이름=APCMMTrcdIsBIZ.java,APCTRTrcdIsBIZ.java,APCTRTrcdExstBIZ.java |
| 3 | 10개를 넘어 목록 영역이 스크롤됨(scrollHeight > clientHeight) | 됨 | {"sh":402,"ch":240,"ov":"auto"} |
| 4 | 체크 전 버튼 비활성, 1개 체크하면 활성 | 됨 | 체크 전 disabled=true, 체크 후 disabled=false, 아이콘 class=lucide lucide-check-square  |
| 5 | /restore 요청에 경로 1개와 결과 메시지 id | 됨 | {"command":"/restore","text":"{\"paths\":[\"src/main/java/sc/chn/aps/apc/ad/ad03/biz/APCADSspyDlngHistMngtBIZ.java\"]}","requestId":"analyze_UI1"} |
| 6 | "1개를 되살렸습니다. 구현 대상에 포함됩니다." 안내와 제목 13개 | 됨 | 제목=제외된 후보 13개, 항목 13개, 되살린 항목 목록에 남음=false |
| 7 | 펼침·체크·확정 3번의 클릭으로 끝남 | 됨 | 접힌 상태에서 되살림까지 dispatch한 클릭 3회 |
| 8 | "<파일명>은(는) 되살릴 수 없었습니다" 안내 | 됨 | ["LLM 호출 없이 구현 대상에 바로 추가됩니다.","1개를 되살렸습니다. 구현 대상에 포함됩니다.","Old.java은(는) 되살릴 수 없었습니다"] |
| 9 | 모두 되살리면 패널이 사라지고 안내 줄만 남음 | 됨 | 패널 있음=false, 안내="12개를 되살렸습니다. 구현 대상에 포함됩니다." (마지막 되살림 12개) |
| 10 | analyze_removed가 없는 예전 결과에는 패널이 없음 | 됨 | {"msg":true,"text":"요구사항 분석 요약\n분석 결과 본문(자동 확인용)\nCopy\nSave .md","panel":0} |

- **참고(관찰, 고치지 않음)**: 목록 영역의 스크롤바가 브라우저 기본 스타일(밝은 막대)이라 어두운 테마와 어울리지 않는다(`02-expanded.png`). 기능에는 영향이 없다.
- **웹뷰 로직 테스트의 위치**: `webview/test/removedCandidatesLogic.test.ts`는 **Gradle에 연결되어 있지 않고** `node --experimental-strip-types --test webview/test/removedCandidatesLogic.test.ts`로 직접 실행해야 하며 **Node 22.6 이상**이 필요하다(Gradle이 내려받는 Node 20.11.1에서는 이 플래그가 없다). 이 브라우저 확인 스크립트도 Gradle에 연결하지 않았다.
- **상태**: 브라우저 확인 완료 / 실제 IDE 종단 확인 1회 대기(실제 분석 → `analyze_removed` 수신 → 되살리기 → `/implement` 입력 반영을 JCEF에서 한 번 확인). 종단 확인 때 볼 항목은 위 체크리스트의 (6), (7), (9)이다(백엔드 연동이 필요한 항목).

### [B-59] 최종 제약 추출 실패 안내 (buildClarifyIntent) (2026-10-23)
- **상태: 합격, push함.** 커밋: 0aa7bf5(src와 테스트), 이 docs 커밋.
- **1번 위치(읽기)**:
  - a. `buildClarifyIntent`는 `Stage0ClarificationEngine.kt`의 정의(변경 전 `:1331`)이고 호출처는 라우터 두 곳이다: `WebviewActionRouter.kt:264`(`/clarify-confirm`, UI는 도달하지 않는 구 경로)와 `:321`(`/clarify-utterance`에서 완료 상태 전이). 테스트가 부르는 곳은 별도다. 최종 추출은 이 함수 안의 `extractConstraints(state.userStatements)`(변경 전 `:1335`)이고, 발화가 없으면(LLM 호출 없음) 실패하지 않으며, 호출이 응답 없음·`[Error]`·예외·해석 불가로 끝나면 실패다(`extractConstraintsChecked`, B-57).
  - b. 실패하면 발화마다 `ConstraintKind.OTHER`(evidence 없음) 기본 제약으로 대체된다. 이 제약은 `ClarifyIntent.constraints`에 들어가고, **분석(Stage 1~3)은 `constraints`를 읽지 않는다**(`src/main`에서 `ConstraintKind`를 쓰는 곳은 저장소·번역기·엔진뿐). 영향은 정제문 생성 프롬프트에 제약 정보가 빠지는 것과, 발화에서 온 제외 요청이 `unconfirmedExclusions`(미확정 제외 안내)에 기록되지 않는 것이다. `excludedFiles`는 대화 중 확정된 거부 항목에서 오므로 실패와 무관하게 그대로다.
  - c. `buildClarifyIntent`의 결과는 곧바로 `executeAnalyzePipeline`으로 넘어가 분석 스트리밍 마크다운(결과 메시지)으로 이어진다. `ClarifyIntent`를 바꾸지 않고 안내를 실을 수 있는 통로는 **반환 래퍼**(엔진 `buildClarifyIntentChecked`)와 **라우터 응답**(`executeAnalyzePipeline`에 안내 인자를 더해 분석 메시지의 첫 청크로 전달)이다. 세션 메모리 대신 엔진 인스턴스(세션마다 새로 만들어짐)의 플래그로 중복을 막았다.
  - d. `ClarifyIntentStore`는 Gson으로 직렬화/역직렬화하고(`saveIntent` `:60` 이후, `loadIntent`는 `ClarifyIntentJson` DTO로 읽음) 모르는 필드는 무시되고 빠진 선택 필드는 널 안전하게 기본값으로 처리된다. 실패하는 경우는 버전·`originalRequirement`·`refinedRequirement` 누락, 지원하지 않는 버전, 그래프 해시 불일치뿐이다(Fail-Fast). `loadIntent`의 `src/main` 호출처는 없다(ADR-001).
- **고른 통로: 2-a(ClarifyIntent를 바꾸지 않는 통로).** 이유: 통로가 있어 `ClarifyIntent`에 필드를 더할 필요가 없고, 그러면 저장 형식과 예전 의도 데이터의 읽기 호환(필드 없음 → false 같은 마이그레이션)을 건드리지 않는다. 안내는 화면 전용이라 저장하지 않는다. 그래서 2-b용 "예전 의도 JSON 읽기" 테스트는 만들지 않았다(`ClarifyIntent` 정의 변경 없음).
- **변경**:
  - `Stage0ClarificationEngine.kt`: `buildClarifyIntentChecked(state)`가 `ClarifyIntentBuild(intent, constraintNotice)`를 돌려준다. 최종 추출이 실패했고 이 세션에서 앞 단계(`initSession`, `processUtterance`, 구 `processTurn`)가 같은 안내를 아직 붙이지 않았을 때만 B-57 문구를 준다(엔진 인스턴스의 `constraintFailureNoticeShown` 플래그, 안내를 붙이는 세 곳이 `constraintFailureLine()`로 기록). `buildClarifyIntent(state)`는 시그니처와 반환값을 유지한다(`buildClarifyIntentChecked(state).intent`).
  - `WebviewActionRouter.kt`(안내 전달만): 두 호출처가 `buildClarifyIntentChecked`를 쓰고 `executeAnalyzePipeline(..., constraintNotice)`로 넘기며, 이 함수가 `analyze_start` 직후 안내를 첫 청크로 보낸다(분석 마크다운 앞에 한 줄).
  - 실패해도 제외·제약이 반영된 것처럼 보이는 문구는 나가지 않는다(`excludedFiles` 변화 없음, `exclusionSummaryLines`에 제외 확정 줄 없음). 확인 질문은 없다.
- **테스트**: 새 `FinalConstraintFailureNoticeTest` g1~g4(최종 실패 → 안내 1줄·제외 변화 없음·제외 문구 없음, 앞 단계 안내가 있으면 되풀이하지 않음(initSession과 processUtterance 두 경우), 성공 → 안내 없음·기존과 같은 intent, 라우터가 두 호출처에서 안내를 분석 스트림 첫 청크로 넘기는 소스 확인). 전체 526/42/17(522에서 +4), clarify 221/1/3(217에서 +4), 실패 메시지 diff 0, 하네스 EXACT_MATCH 5종·MATCH_EXCLUDING 2종 일치, selftest 24/24, 기존 r·h·g·i·f11과 재시도 n1~n6·실패 안내 f1~f4·m1~m5 통과, 웹뷰 로직 테스트 12개 통과. 기대가 바뀐 기존 테스트는 없다.
- **한계(기록)**: (1) 안내는 한 세션에 한 번만 나오므로, 앞 단계 안내(초기 요구사항 또는 한 발화)가 있었다면 최종 추출의 실패는 따로 알리지 않는다. (2) 평문 `buildClarifyIntent`를 부르는 곳(테스트)은 실패 시에도 중복 방지 플래그를 세운다(운영 경로는 항상 `Checked`를 쓴다). (3) 이 안내는 분석 결과 메시지의 첫 줄로만 보이고 별도 알림이 아니다. (4) 정제문 생성(`refineRequirement`) 호출의 실패는 여전히 조용히 원문으로 대체된다(이번 범위 밖).
