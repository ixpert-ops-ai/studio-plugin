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
- **상태**: Open (우선순위 P3)

---

### [B-25] 정제문(Refinement) 문구 확장으로 인한 시드 편향 및 GT 탈락 (`SurveyServiceImpl`)
- **현상**: `survey_admin` 시나리오에서 LlmOff 시 GT 2/8(`SurveyServiceImpl.java`, `sql_survey.xml`)이 생존하였으나, LlmOn 시 1/8(`sql_survey.xml`)로 하락함.
- **원인 규명 (C-11/C-12 실측 증명)**:
  - C-11 실측 결과 태그 부착 버전과 식별자 리터럴 줄바꿈 버전(Simulated 2b)의 Top-30 산출 목록이 14개 전 항목 바이트 단위로 100% 일치함(`EXACT_MATCH`). 즉, `(명시된 식별자: ...)` 태그 자체나 괄호 문구는 결정론적 탐색 결과에 아무런 영향을 미치지 않음.
  - C-12 점수 분해 결과, 정제 LLM이 요구사항에 새로 덧붙인 `"Bizgo 연동 API 신규 개발"` 문구의 영문 토큰(`API` 등)으로 인해 휴리스틱 시드가 `SurveyDaoImpl`에서 `ApiService`, `IbCenterApiService` 등 API 계층으로 편향됨.
  - 이로 인해 `SurveyServiceImpl`의 확장 홉이 Hop 1(`DEPENDED_BY`, 30점)에서 Hop 3(`REPO_TO_SERVICE`, 0점)으로 밀려나며 총점이 65점 $\rightarrow$ 35점으로 급락하여 minScore(55) 미달 탈락함.
- **해결 방안**: 정제 프롬프트가 기존 시스템의 맥락을 희석시키지 않도록 하거나, 시드 선택 단계에서 도메인 고유 명사 가중치 방어 로직 강화.
- **상태**: Open (우선순위 P2)

---

### [B-26] RequirementAnalysisPipeline 완주 계약 생성 시 enrichedRequirementText 불일치 (`effectiveReq` vs `clarifyIntent.refinedRequirement`)
- **현상**: 커밋 2a에서 `RequirementAnalysisPipeline.kt:55`의 `clarifyIntent?.refinedRequirement?.ifBlank { null }` fallback을 제거하고 `primaryReq`를 직접 소비하도록 변경함.
- **영향**: `RequirementAnalysisPipelineIntegrationTest:413`에서 `clarifyIntent`가 주입된 채 `primaryReq`로 `originalRequirement`("서비스 수정 요청")를 넘길 경우, 파이프라인 완주 후 생성되는 계약의 `enrichedRequirementText`(:446)가 `effectiveReq`(즉 `primaryReq`)로 기록되어, 기존 테스트의 기대값(`clarifyIntent.refinedRequirement`)과 불일치 발생.
- **해결 방향**: 계약의 `enrichedRequirementText` 기록 시 `clarifyIntent?.refinedRequirement?.ifBlank { null } ?: effectiveReq`를 참조하도록 명확히 하거나, 계약 스키마 생성 시의 정제문 처리 규칙을 일원화.
- **상태**: Open (우선순위 P2)



