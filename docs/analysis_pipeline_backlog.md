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

### [B-60] clarify 프롬프트 토큰 실측, 후보 상한(안 C) 설계 근거, refineRequirement 실패 영향 (2026-10-23)
- **범위**: 읽기와 서버 `/tokenize`(생성 호출 아님)만. `src` 수정, 생성 호출, 평가 실행 없음. 스크립트와 출력은 `build/prompt_tokens.js`, `build/prompt_tokens.json`(커밋하지 않음).

#### 1. 프롬프트 길이 실측
- **방법**: 덤프 폴더 19개의 요청 1,727건에서 고유 프롬프트 782개를 추려(같은 내용 중복 제거) `POST /tokenize`(`model=Qwen/Qwen3.8-27B-FP8`, 요청의 `messages` 그대로 = 채팅 템플릿 포함)로 센 결과다. 782개 모두 성공했다(추정 없음). 서버 응답이 `max_model_len = 65536`을 직접 확인해 주었다. `/tokenize` 값은 같은 호출의 서버 usage와 일치한다(SR-08 번역 53,400 = 53,400, 요약 33,715 = 33,715).
- **1-c 표(호출 종류별)**:

  | 호출 종류 | 고유 프롬프트 | 최소 | 중앙 | 최대 | max_tokens | 최대+max_tokens | 65,536 대비 | 최대 호출 |
  |---|---|---|---|---|---|---|---|---|
  | 발화 번역(UI) | 24 | 561 | 1,892 | **53,400** | 300 | **53,700** | **81.9%** | SR-08 |
  | 작업 요약 등 | 50 | 250 | 1,027 | **33,715** | 500 | 34,215 | 52.2% | SR-08 |
  | 시드 선정(tool) | 551 | 890 | 3,915 | 14,286 | 2,048 | 16,334 | 24.9% | SR-11 |
  | Stage 3 검증 | 74 | 1,000 | 3,104 | 6,355 | 4,000 | 10,355 | 15.8% | SR-11 |
  | 제약 추출 | 41 | 456 | 473 | 559 | 500 | 1,059 | 1.6% | D3 |
  | 신규 파일 탐지 | 19 | 410 | 521 | 542 | 1,500 | 2,042 | 3.1% | D3 |
  | 정제문 생성 | 23 | 303 | 325 | 398 | 300 | 698 | 1.1% | D3 |

  B-57의 추정(요약 33.7K, 번역 53.4K)은 실측과 같다(번역은 max_tokens까지 더하면 53.7K, 82%). 한도의 80%를 넘는 호출은 **SR-08 번역 1건뿐**이다. 시드 선정(tool)은 덤프에 도구 정의가 없어 토큰이 약간 적게 잡혔다(상한 24.9%도 하한 값). 번역·요약 외 호출은 모두 25% 이하이고, 번역·요약도 SR-08과 오래된 D4 요약(30,095 토큰)을 빼면 시나리오별 최대가 3K 토큰 이하(요약 1.6K 이하, 번역 3.0K 이하)다.
- **1-d 구성 분해(최대 호출, SR-08)**:
  - 발화 번역 53,400 토큰 = 후보 625줄 52,810 토큰(**줄당 평균 84.5**) + 후보 외(지시문·발화) 572 토큰. 한도(max_tokens 300 포함)에 닿는 후보 수는 **765개**이고 지금은 625개(여유 후보 약 140줄)다.
  - 작업 요약 33,715 토큰 = 후보 목록 625줄 33,463 토큰(**줄당 평균 53.5**) + 지시문 165 + 원 요구사항·사용자 지시 71. 한도에 닿는 후보 수는 **1,209개**다.
  - 번역 프롬프트에는 대화 이력이 없고(발화 1건과 후보 목록뿐), 한도를 넘는 후보 수는 현재 0이다(두 호출 모두 한도 이내). 후보가 765개를 넘는 요구사항이 나오면 번역 호출이 한도를 넘어 실패한다.
- **1-e 그래프와 후보 목록 크기**: 노드 수는 ISM 3,552, APC 2,419, member_market 90, survey_admin 59(그 밖의 그래프는 없다). 후보 줄 수(번역·요약 프롬프트의 시나리오별 최대)는 survey_admin 계열 D1·D5·S3·SR-02가 18~29, SR-01이 1, member_market 계열 S1·S2·SR-04·SR-05가 15~36, APC 계열 D2·D3·SR-11·SR-12가 10~16, ISM 계열 SR-07 28, **SR-08 625, 오래된 D4 요약 560**이다. **그래프 크기에 비례해 늘지 않는다**(같은 ISM 그래프에서 SR-07은 28개, SR-08은 625개). 후보 수는 요구사항의 토큰이 그래프에서 얼마나 넓게 일치하느냐에 따른다(SR-08은 "register, regist" 같은 흔한 토큰이 수백 개 클래스에 일치).

#### 2. 안 C(후보 상한) 설계(읽기)
- **2-a 위치와 정렬**: 발화 번역은 `ClarifyUtteranceTranslator.kt:75`에서 `buildCandidatesContext(currentItems)`(`:148-158`, 줄 형식 `- ID: … | 파일: … | 설명: …`)를 시스템 프롬프트에 넣는다(`currentItems`는 REJECTED까지 포함한 전체 항목). 작업 요약은 `Stage0ClarificationEngine.kt:1029` `activeItems`(REJECTED 제외)를 `- 경로 (근거)` 줄로 만든다. 정렬은 항목의 현재 순서이고, 초기 항목은 사용자 확정·미확인 항목, 스캐너 후보군, 이전 세션 거부 동결 순이다. 스캐너 후보군은 범주별 큐(뷰·스크립트·비즈니스·DTO·데이터 접근)를 점수(+핵심 동작 가중치) 내림차순으로 정렬한 것이다(`Stage0GraphScanner.kt:536-554`).
- **2-b 상한 방식(2개)**:
  1. **상위 K + 항상 포함**: 앞에서 K개(점수순)와, 항상 포함할 항목(확정·거부된 항목, 사용자 발화 출처·제외 제안 항목, 발화에 이름이 언급된 항목)만 프롬프트에 넣고 "외 N개 생략" 한 줄을 붙인다. `targetId` 검증은 전체 `currentItems`로 그대로 한다.
  2. **토큰 예산**: 줄 길이로 추정한 토큰이 예산(예: 번역 12K, 요약 8K)에 닿을 때까지 앞에서부터 넣고 항상 포함 항목은 예산 밖에서 더한다. K를 고정하지 않으므로 줄이 긴 후보가 많아도 프롬프트 크기가 일정하다. 구현이 1번보다 복잡하다.
- **2-c 품질 위험(기존 덤프)**: 번역기가 `targetId`를 고른 호출은 고유 24건 중 **1건**(SR-05X의 EXCLUDE, 후보 15개 중 14번째)뿐이라 K=60, 120, 200에서 상한 밖으로 밀리는 `targetId`는 **0건**이다(이 건수는 표본이 1건이라 근거가 약하다). 후보가 K를 넘는 번역 호출은 고유 1건(SR-08, 625개)이다. SR-08(번역·요약)과 D4(요약)의 정답 파일(GT)은 후보 목록에 **한 개도 들어 있지 않다**(0/5, 0/4): clarify 후보 목록 625개와 560개는 흔한 토큰 일치로 부풀려진 것이라, 앞 K개로 줄여도 잃는 GT는 0건이다. 제외 후보(제안 항목)가 상한 밖으로 밀리는 건은 시나리오별 후보가 최대 36개라 K≥60에서 0건이다. 줄인 뒤 SR-08 프롬프트는 계산상 번역 약 5.6K(K=60), 10.7K(K=120), 17.5K(K=200) 토큰, 요약 약 3.4K, 6.7K, 11.0K 토큰이다. 위험은 "GT가 후보에 든 큰 목록에서 K 밖으로 밀리는 경우"이고 이는 현재 덤프에 없어 실측으로 확인할 수 없다(이후 측정에서 확인).
- **2-d 합격 기준 초안**(수정 전에는 충족되지 않는 값. 회귀 방지 기준은 이미 충족되어 개선 기준이 아니다):

  | 구분 | 기준 | 현재 값 | 합격선 |
  |---|---|---|---|
  | 개선 | SR-08 번역 프롬프트 최대 토큰 | 53,400(max_tokens 포함 82%) | 16,000 이하(25% 이하) |
  | 개선 | SR-08 작업 요약 프롬프트 최대 토큰 | 33,715 | 10,000 이하 |
  | 개선 | 후보 수 5,000개일 때 번역·요약 프롬프트 | 한도 초과(계산값: 번역 약 42만 토큰) | 16,000 이하(상한 때문에 후보 수와 무관) |
  | 개선 | SR-08 clarify 구간 | 87~92초(큰 호출 정상 18~31초) | 60초 이하 |
  | 회귀 | 후보가 K 이하인 시나리오의 프롬프트 | 해당 없음(변경 전) | 변경 전과 바이트 동일 |
  | 회귀 | 확정·제외 제안·발화 언급 항목이 목록 밖으로 밀리는 건수 | 0 | 0 |
  | 회귀 | SR-08 Recall, 번역 결과 action | Recall 3/3 | 변경 전과 같음 |

#### 3. refineRequirement 실패 영향(읽기)
- **3-a 위치**: `Stage0ClarificationEngine.kt:1452` `refineRequirement`. 발화가 없으면 원문(`:1456` 부근), LLM 클라이언트가 없으면 원문, LLM 응답이 비었거나 `[Error]`로 시작하면(`:1484-1488`) 원문, 예외가 나도 원문(`:1489-1491`)을 돌려준다. 실패 판정은 응답 없음·빈 응답·`[Error]`·예외이고, 안내는 없다.
- **3-b 정제문이 쓰이는 곳**: `buildClarifyIntent`가 `rawRefined`로 받고 `IdentifierRetentionChecker.checkRetention`을 거쳐 `ClarifyIntent.refinedRequirement`(저장, 계약 입력)가 되며, `AnalyzeInputResolver.resolve`가 이것을 분석 입력(`effectiveRequirement`)으로 삼는다. Stage 1 시드 선정과 Stage 3 요구사항(`AnalyzeSections.buildStage3Base`)이 이 값(`rawRefined` + 누락 식별자 줄)을 쓴다. 화면에는 정제문 자체가 보이지 않는다(분석 요약의 근거 문장만 보임).
- **3-c 실패 시 발화가 닿는 길**: 정제문이 원문(SR)으로 대체되면 **발화의 문장 내용(예: "활성 상태인 조직만요")은 Stage 3에 닿지 않는다.** 닿는 경로는 (1) 식별자: `checkRetention`이 SR과 발화에서 식별자를 뽑아 정제문에 없는 것을 `missingBeforeFix`로 붙여(`AnalyzeInputResolver.kt:58-67`) Stage 3 요구사항 끝에 줄로 더한다, (2) 제외 파일: `excludedFiles`는 대화 중 확정된 거부 항목에서 오므로 별도로 사전 필터에 닿는다. `ClarifyIntent.userStatements`와 대화 이력은 분석이 읽지 않는다(`src/main`에서 `userStatements`를 읽는 곳은 엔진·저장소·모델뿐). 즉 한정·범위 지시("~만", "~는 그대로 두세요")는 실패하면 식별자가 들어 있지 않은 한 분석에서 사라진다.
- **3-d 실패 사례 수**: live_eval 보고서의 정제문 호출 141건 중 비200 응답 0건, 덤프 131건 중 실패·빈 응답 0건이다(보고서 7건은 `contentChars` 필드가 없던 옛 형식으로 completionTokens가 26~96이라 빈 응답이 아니다). 관찰된 실패는 없고, 서버 정체(B-56 524)가 이 호출에 생기면 위 경로로 번질 수 있다. 정제문 호출은 입력이 작아(최대 398 토큰) 실제로 정체될 가능성은 낮다.
- **3-e 해결안(2개)**:
  1. **실패 시 원문 + 발화를 이어 붙여 넘기기**: 실패 분기(`:1484-1491`)가 원문 뒤에 `[사용자 추가 지시]`와 발화 줄을 붙여 돌려준다. `Stage0ClarificationEngine.kt`만, 약 8줄. 성공 경로는 같고 실패 경로에서만 분석 입력이 바뀐다(발화 내용이 Stage 1·3에 닿는다).
  2. **실패 안내 한 줄 추가**: 실패를 `ConstraintExtraction`처럼 알려 B-57·B-59의 한 줄 안내(세션당 1회 메커니즘 공유)에 "요구사항 정제에 실패해 원문과 발화를 그대로 사용합니다"를 더한다. `Stage0ClarificationEngine.kt`(`buildClarifyIntentChecked` 확장)와 `WebviewActionRouter.kt`는 이미 안내를 전달하므로 엔진만, 약 20줄. 출력은 안 바뀌고 사용자가 알게 된다.
- **추천**: **1번을 먼저, 2번은 선택.** 관찰된 실패가 0건이라 긴급하지 않지만 1번은 8줄로 "실패해도 사용자 지시가 분석에서 사라지지 않는다"를 보장하고, 안내가 없어도 결과가 사실대로(원문+발화)이므로 조용한 손실이 없다. 안내(2번)는 세션당 1회 안내의 한계(아래 다음 과제)를 먼저 풀고 같이 넣는 편이 낫다.

#### 4. 종합 추천과 다음 과제
- **안 C 추천**: 방식 1(상위 K + 항상 포함)을 번역·요약에 같이 적용하고 K는 120(번역 약 10.7K 토큰, 요약 약 6.7K 토큰, 한도의 16%와 10%)으로 시작한다. 기존 덤프에서 GT가 후보에 든 큰 목록이 없어 K를 실측으로 고를 수 없으므로, 구현 전에 GT가 후보에 드는 큰 목록 시나리오(요구사항 토큰이 넓게 일치하는 ISM)를 1~2개 더 확보해 품질 위험을 측정해야 한다.
- **다음 과제(추가)**: (1) **세션당 1회 안내로 두 번째 추출 실패는 표시되지 않음**(B-59 한계): 앞 단계 안내가 있으면 최종 추출의 실패를 알리지 않는다. (2) 안 C 구현과 K 선택(GT가 후보에 든 큰 목록 시나리오 확보 후). (3) refineRequirement 실패 시 원문+발화 이어붙이기(3-e 1번)와 안내(3-e 2번). (4) 기존 과제(재시도 실측, 부정형 발화, 배너, IDE 종단 확인)는 B-58, B-59와 같다.

### [B-61] 번역·요약 프롬프트 후보 상한(K=120)과 SR-08 3회 측정 (2026-10-23)
- **상태: 불합격(clarify 구간 중앙값 기준 1개), push하지 않았다.** 로컬 커밋: 495803d(src와 테스트, 미push), 이 docs 커밋. 프롬프트 길이 목표와 Recall은 충족했다.
- **1번 위치(읽기)**:
  - a. 번역과 요약은 같은 후보 집합(`state.items`)에서 온다. 번역은 `Stage0ClarificationEngine.kt:1221`이 `state.items` 전체를 `ClarifyUtteranceTranslator.translate`의 `currentItems`로 넘기고(REJECTED 포함, 목록은 `buildCandidatesContext`), 요약은 같은 항목에서 REJECTED를 뺀 `activeItems`다(`:1029` 부근). 항목은 `initSession`의 `combinedItems`와 `mergeCandidates`(`:1072`)로 만들어지고, 스캐너 후보군의 순서는 범주별 큐의 점수(+핵심 동작 가중치) 내림차순이다(`Stage0GraphScanner.kt:536-554`).
  - b. 같은 집합을 프롬프트 밖에서 쓰는 곳(이번에 바꾸지 않음): 번역의 targetId 검증 `ClarifyUtteranceTranslator.kt`의 `currentItems.find { it.id == rawTargetId }`(EXCLUDE의 "후보 목록에 있음"), `Stage0ClarificationEngine.kt:885` `excludeMentionedFiles`의 `items.indexOfFirst`(파일명 직접 제외의 후보 구분), `:658`·`:751` `applyPendingExclusionAnswer`, `:1348`·`:1384` `buildClarifyIntent`(excludedFiles, unresolved), `:1442-1446` `exclusionView`, `:1072` `mergeCandidates`. `resolveExclusionCandidates`(`:563`)는 항목이 아니라 그래프·사전을 쓴다.
  - c. "항상 포함" 판별 데이터: `RequirementItem.verdict`(확정·거부), `source`(`HintSource`: USER_CONFIRMED/USER_UTTERED/PROPOSED_EXCLUSION이 SYSTEM_UNCONFIRMED가 아닌 것), `utteredIdentifier`(`Stage0Models.kt:91-107`), `hint.ExistingRef.filePath`의 파일명(확장자 뗀 이름)이 현재 발화와 SR 원문(번역은 새 인자 `requirementText`, 요약은 `originalRequirement`와 `userStatements`)의 식별자 토큰에 정확히 있는지.
- **변경(495803d)**: `ClarifyUtteranceTranslator.kt`에 `CandidateCap`(`:14`)을 추가했다. 선택 = 기존 순서의 앞 120개 ∪ 항상 포함 항목(확정·거부 판정 항목, SYSTEM_UNCONFIRMED가 아닌 출처, 발화 식별자가 있는 항목, 이름이 발화·SR 원문에 정확히 나온 항목), 순서는 기존 순서 유지, 생략이 있을 때만 끝에 "외 N개 생략" 한 줄. 번역(`:182-190`)과 요약(`Stage0ClarificationEngine.kt:1033`)의 **후보 목록 부분에만** 적용했고 지시문은 한 글자도 바꾸지 않았다. 후보가 120개 이하이면 프롬프트가 같다. 엔진 내부 후보 집합과 1-b의 사용처, 저장 형식은 그대로다. `translate`에 기본값이 있는 인자 `requirementText`(SR 원문)만 추가했다.
- **테스트**: 새 `CandidateCapTest` c1~c5(120개 이하에서 후보 줄이 기존 형식 그대로이고 생략 줄이 없으며 지시문이 5개일 때와 120개일 때 같음, 625개에서 표시 줄 수 123 = 120 + 상위 밖 항상 포함 3(600위 확정, 601위 제외 제안, 500위 발화 언급)이고 생략 N=502와 순서·요약 동일 규칙, 5,000개에서 표시 줄이 121로 제한(줄당 85토큰 기준 16K 이하), 선택 규칙 순서와 항상 포함, 프롬프트에 안 보인 500위 항목의 targetId도 625개 전체 집합 기준으로 EXCLUDE 해석되고 없는 id는 후보 밖으로 강등). 전체 531/42/17(526에서 +5), clarify 226/1/3(221에서 +5), 실패 메시지 diff 0, 하네스 EXACT_MATCH 5종·MATCH_EXCLUDING 2종 일치, selftest 24/24, 기존 r·h·g·i·f11·n·f1~f4·g·m 테스트와 웹뷰 로직 테스트 12개 통과.
- **3번 오프라인 재측정(`build/cap_remeasure.js`, /tokenize만)**: SR-08 번역 53,400 → **10,422**(65,536 대비 max_tokens 포함 16.4%), 요약 33,715 → **6,514**(10.7%)와 33,667 → 6,466, D4 요약 30,095 → 6,708. 후보가 120개 이하인 고유 프롬프트 70개(SR-08·D4 외)는 상한이 적용되지 않아 같다(상한 적용은 위 4개). 한계: 덤프에 항목의 verdict·source가 없어 "항상 포함"은 이름 일치만 시뮬레이션했다.
- **4번 실측(사전 예측 `pred_cap.md`, 사내 Qwen, `live_eval_20261023_145309`, SR-08 3회)**:

  | 회차 | 번역 prompt 토큰(usage) | 요약 prompt 토큰 | clarify 구간 | 번역·요약 호출 경과 | 재시도·524 | 분석 | Recall |
  |---|---|---|---|---|---|---|---|
  | 1 | 10,422 | 6,466 / 6,514 | **117초** | 57.0초 / 55.7초, 24.8초 | 0 | 성공 | 3/3 |
  | 2 | 10,422 | 6,466 / 6,514 | 69초 | 17.8초 / 19.8초, 18.0초 | 0 | 성공 | 3/3 |
  | 3 | 10,422 | 6,466 / 6,514 | 66초 | 15.2초 / 16.0초, 15.3초 | 0 | 성공 | 3/3 |

  비교(캡 전, B-57 측정 같은 정의): clarify 구간 78, 90, 89초(중앙값 89초), 번역 18초, 요약 21~31초.
- **판정**:

  | 기준 | 결과 |
  |---|---|
  | 2-d, 2-e 테스트·검증 | 충족 |
  | 3번 오프라인(번역 16,000 이하, 요약 10,000 이하, 나머지 바이트 동일) | 충족(10,422 / 6,514, 70개 동일) |
  | 4번 3회 모두 번역 16,000 이하·요약 10,000 이하(usage) | 충족(10,422 / 6,514) |
  | Recall 3회 모두 3/3 | 충족 |
  | **4번 clarify 구간 중앙값 60초 이하** | **미충족: 중앙값 69초(117, 69, 66초)** |

  **한 줄 원인**: 호출 시간의 대부분이 프리필이 아니라 생성(디코드)이라 프롬프트를 1/5로 줄여도 호출당 3~10초만 줄었고(요약 18~25초 → 15~20초, 번역 18초 → 15~18초: 번역은 완료 토큰 약 120~150개, 요약 약 125~156개, 최종 제약 추출 약 230개를 8~9 tok/s로 생성), 회차 1은 같은 프롬프트가 56~57초 걸린 서버 쪽 느린 구간(524나 재시도는 없음)이라 구간이 117초가 되었다. 구간의 고정 비용은 제약 추출 10초 + 요약 두 번 순차(각 15~20초) + 최종 제약 추출 28초 + 정제문 6초로 약 66~69초다.
- **예측과 다른 점**: 예측 2(중앙값 60초 이하 70%)는 틀렸다. 근거로 삼은 "큰 호출의 정상 소요는 대부분 프리필"이 틀렸고 디코드가 지배적이었다. 예측 1(프롬프트 토큰), 3(재시도·524 0건), 4(Recall 3/3), 6(생략 줄)은 맞았다. 회차 1의 57초 호출은 예측하지 않은 서버 변동이다.
- **판단 요청(지정 기준 미충족이라 push하지 않았다)**: 후보 상한은 목표(프롬프트 길이 여유와 한도 82% → 16%)를 달성했고 품질(Recall, 번역 action)에 영향이 없었으나, clarify 시간 기준은 "프롬프트 크기"가 시간의 주된 원인이 아니라서 상한만으로는 60초에 닿지 않는다. 선택지: (a) 기준을 "중앙값 75초 이하" 등으로 바꾸고 push, (b) 시간 기준은 별도 과제(요약 2회 순차 호출을 합치거나 출력 길이·호출 수 줄이기)로 두고 상한은 길이 여유 목적으로 push, (c) 보류. 이 기록은 선택을 하지 않고 사실만 적는다.
- **다음 과제(추가)**: (1) **clarify 스캐너 후보가 흔한 토큰에 끌려감(SR-08 625개 중 정답 파일 GT 0개)**: 요구사항 토큰("register, regist" 등)이 수백 개 클래스에 일치해 후보 목록이 부풀고, 후보 상한은 증상(프롬프트 길이)만 줄인다. (2) clarify 시간은 디코드가 지배적이므로 요약 2회 순차 호출과 최종 제약 추출의 출력 길이(약 230토큰)를 줄이는 방안을 다음에 읽기로 확인한다. (3) 서버 느린 구간(회차 1)이 재시도 없이 지나간 것은 90초 타임아웃에 걸리지 않은 느림이다(재시도는 정체에만 작동). (4) B-60에서 이어진 과제(refineRequirement 실패 처리, 세션당 1회 안내 한계, 재시도 실측 검증)는 그대로다.

#### [B-61 보충] 판정 변경과 채택, 미확인 사항 (2026-10-23)
- **판정 변경(결과를 본 뒤에 한 것이다)**: B-61의 시간 기준("clarify 구간 중앙값 60초 이하")은 "큰 호출의 소요는 대부분 프리필"이라는 가정에서 나왔는데, 실측에서 그 가정이 틀렸다(호출 시간은 디코드가 지배, 프롬프트를 1/5로 줄여도 호출당 3~10초만 감소). 그래서 **시간 기준은 이번 판정에서 뺀다.** 사전에 정한 기준을 실측 결과(중앙값 69초)를 본 뒤에 바꾼 것이며, 기준을 충족해서가 아니라 기준의 전제가 틀렸다는 이유로 뺀다는 점을 그대로 기록한다. **후보 상한(K=120)은 프롬프트 길이 여유 목적으로 채택한다**(번역 53,400 → 10,422 토큰, 한도 대비 82% → 16%, 후보가 765개를 넘어도 한도에 닿지 않음, Recall 3/3 유지, 후보 120개 이하 프롬프트 불변).
- **미확인 사항(채택이 입증한 것이 아니다)**: (1) **524 감소는 입증되지 않았다.** 상한 뒤 3회에서 524가 0건이었지만 상한 전에도 재시도 도입 뒤 3회가 0건이었고(B-57), 524는 가끔만 나므로 3회 0건은 근거가 안 된다. (2) **"항상 포함" 중 판정·출처 기준의 실측 확인이 없다.** 오프라인 재측정은 덤프에 항목의 판정·출처가 없어 이름 일치만 시뮬레이션했고, 단위 테스트(`CandidateCapTest` c2·c4)는 판정·출처 기준이 맞게 동작함을 확인했지만 실제 대화에서 그 항목이 120개 밖에 있었던 사례는 아직 없다.
- **서버 느린 구간(다음 과제 추가)**: 회차 1에서 같은 프롬프트(번역 10,422 토큰, 요약 6,466 토큰)가 56~57초 걸렸다(회차 2·3은 15~20초). 524나 재시도 없이 지나갔고 **90초 읽기 타임아웃에는 걸리지 않는다**(재시도는 정체에만 작동하고 느림에는 작동하지 않는다). 이런 느린 구간의 빈도와 원인(서버 대기열, 동시 요청)은 측정하지 못했다.

### [B-62] clarify 구간 66~69초 분해와 줄이는 방법 설계 (2026-10-23)
- **범위**: 읽기만(LLM 호출, `/tokenize` 없음, `src` 수정 없음). 기존 보고서(`live_eval_2026102*`, 비200 없는 clarify 85회)와 덤프, 코드만 썼다. 스크립트는 `build/clarify_breakdown.js`(커밋하지 않음).
- **2-a clarify 호출 표**(모두 비스트림, `onChunk` 없음):

  | 호출 종류 | 코드 위치 | max_tokens | prompt 토큰 중앙/최대 | completion 토큰 중앙/최대 | 소요 중앙/최대(초) | 출력 속도(tok/s) | 순차·병렬 |
  |---|---|---|---|---|---|---|---|
  | 제약 추출 | \`Stage0ClarificationEngine.kt:1557\` | 500 | 473 / 559 | 125 / 496 | 15.9 / 58.8 | 7.9 | init(SR 1회)은 단독, 최종(발화들)은 작업 요약과 병렬(49%가 다른 호출과 겹침) |
  | 작업 요약 | \`:1053\`(\`generateTaskSummary\`) | 500 | 970 / 33,715 | 160 / 248 | 21.2 / 55.7 | 7.6 | 항상 다른 호출과 병렬(번역, 최종 제약 추출) |
  | 발화 번역 | \`ClarifyUtteranceTranslator.kt:148\` | 300 | 1,892 / 53,400 | 105 / 158 | 14.1 / 57.0 | 7.5 | 작업 요약과 병렬 |
  | 정제문 생성 | \`:1482\`(\`refineRequirement\`) | 300 | 325 / 398 | 44 / 96 | 5.5 / 11.7 | 8.0 | 최종 제약 추출 뒤 단독(37%만 겹침) |

  **호출 시간 ≈ completion 토큰 / 약 8 tok/s**이고 고정 비용이 보이지 않는다(네 종류 모두 7.5~8.0 tok/s). 시나리오별 clarify 구간 중앙/최대(초, 실행 수): SR-07 54/56(4), SR-11 68/70(4), D1 102/105(4), D2 77/79(4), D3 110/117(4), D4 2/7(4), D5 65/81(5), S1 52/52(4), S2 37/37(4), S3 48/63(5), SR-01 65/91(4), SR-02 44/56(4), SR-04 45/51(4), SR-05 55/69(8), SR-12 119/170(5), SR-08 89/117(9, 상한 전후 포함), SR-05X 75/87(8). SR-08만의 문제가 아니라 clarify 구간은 시나리오 대부분에서 40~120초다.
- **SR-08 구간의 임계 경로(상한 뒤 회차 3, 66초)**: 초기 제약 추출 10초 → (LLM 아닌 구간 약 7초, **원인 미확인**) → 발화 번역 15초(작업 요약 1과 병렬) → 최종 제약 추출 28초(작업 요약 2와 병렬) → 정제문 6초. 합 10 + 7 + 15 + 28 + 6 = 66초. **작업 요약은 임계 경로에 없다**(번역과, 최종 제약 추출과 각각 병렬).
- **2-b 작업 요약이 두 번 불리는 이유**: 라우터가 턴마다 \`scheduleTaskSummary\`(\`WebviewActionRouter.kt:164\` init, \`:243\` 구 확인 경로, \`:299\` 발화 처리 뒤)로 \`TaskSummaryScheduler.schedule\`을 부르고(\`engine.buildTaskSummary\` → \`generateTaskSummary\`, \`Stage0ClarificationEngine.kt:23\`, \`:1014\`), 스케줄러는 캐시드 스레드 풀이라 호출이 병렬이다. 입력 차이: 1회는 init 상태(후보 항목과 SR), 2회는 발화 뒤 상태(발화가 \`userStatements\`로 더해지고 항목이 갱신됨). **결과의 쓰임은 화면 한 곳뿐**이다: \`analyze_clarify_summary\`로 카드의 \`taskSummary\`가 된다(\`App.tsx:1156\`, 카드의 턴 번호와 같을 때만). 정제문, 의도, 저장에는 쓰이지 않는다. 새 턴이 시작되거나 확정(\`invalidate\`, \`WebviewActionRouter.kt:261\`, \`:319\`)되면 이전 요약은 시작 전이면 호출이 취소되고, 만드는 중이면 **LLM 호출은 계속되고 결과만 버려진다**(SR-08 회차에서 요약 1은 발화 처리가 끝나고 1초 뒤에 끝나 결과가 버려진 정황).
- **2-c 최종 제약 추출(28초)**: 입력은 \`state.userStatements\`(발화들)이고 init 추출의 입력(SR 원문)과는 다른 텍스트라 겹치지 않는다. 발화에 제외 표현이 있으면 \`processUtterance\`(\`:1285\`)가 같은 발화를 이미 한 번 추출하므로 그 경우에만 중복이다. 결과(\`extraction.constraints\`)의 쓰임: (1) \`ClarifyIntent.constraints\`(저장되지만 **분석은 읽지 않는다**, B-59), (2) \`unconfirmedExclusions\`(EXCLUDE_COMPONENT의 evidence로 "제외 요청이 확정되지 않았습니다" 안내를 만듦). **\`refineRequirement\`는 \`constraints\` 인자를 프롬프트에 쓰지 않는다**(\`:1455\`, 프롬프트는 SR과 발화만). 그래서 정제문 호출은 최종 제약 추출과 순차일 이유가 없다. 앞 결과 재사용: 발화별 결과를 캐시하면 제외 표현이 있는 발화는 재사용할 수 있지만(그 경우만 \`processUtterance\`가 추출함), 제외 표현이 없는 발화(SR-08)는 앞에서 추출한 적이 없어 재사용할 결과가 없다.
- **2-d 출력 길이와 사용 비율**(덤프의 응답 JSON 필드별 문자 비중으로 추정):
  - 제약 추출(고유 응답 39건): \`rawStatement\` 39%, \`value\` 25%, \`evidence\` 24%, \`kind\` 11%. 파서는 \`rawStatement\`가 없으면 \`userStatements\`(인덱스)나 \`value\`로 대체한다(\`:1568\` 부근 \`rawStmt\`). \`rawStatement\`는 발화 원문을 되풀이하는 필드라 출력의 약 39%가 대체 가능하다.
  - 발화 번역(고유 응답 24건, action은 ADD_CONSTRAINT 12, COMPLETE 7, UNKNOWN 4, EXCLUDE 1): \`message\` 29%, \`constraintValue\` 23%, \`constraintKind\` 11%, \`notInCandidates\` 10%, \`action\` 10%, \`targetId\` 9%, \`tokenValue\` 8%. ADD_CONSTRAINT의 화면 안내는 고정 문구이고 모델의 \`message\`는 EXCLUDE·UNKNOWN에서만 쓰인다(번역기 코드와 테스트가 고정).
  - 작업 요약: 출력 전부(2~4줄)가 화면에 보인다(카드의 \`taskSummary\`).
  - 정제문: 출력 전부가 정제문으로 쓰인다(약 44 토큰).
- **2-e 줄이는 방법 후보(3개)**:

  | 후보 | 예상 절감(근거) | 바뀌는 파일 | 화면·저장 형식 | 품질 위험 |
  |---|---|---|---|---|
  | A. 정제문 호출을 최종 제약 추출과 병렬화(정제문이 제약을 쓰지 않음) | 약 5.5초(정제문 중앙 5.5초, SR-08 6초)를 28초 추출 뒤에서 앞으로 숨김: 66 → 약 60초 | \`Stage0ClarificationEngine.kt\`(\`buildClarifyIntentChecked\`, 약 10줄) | 변경 없음 | 없음(같은 호출, 같은 입력). 병렬로 LLM이 두 건 동시에 간다 |
  | B. 최종 제약 추출을 건너뛰거나 재사용: 발화별 추출 결과를 캐시해 \`processUtterance\`가 이미 추출한 발화는 재사용하고, 제외 표현이 없는 발화만 있으면 호출하지 않음 | SR-08은 28초 전부(66 → 약 38초). 제외 표현 발화가 있는 시나리오는 중복 16초(제약 추출 중앙 15.9초) | \`Stage0ClarificationEngine.kt\`(약 25줄) | 화면 변경 없음. **저장되는 \`ClarifyIntent.constraints\`의 내용이 달라진다**(제외 표현이 없는 발화는 분류 없이 기본값 OTHER) | 분석은 \`constraints\`를 읽지 않아 결과 영향 없음. 모델이 제외 표현 목록(건드·빼·제외·말고·그대로·배제·손대·수정하지·변경하지) 밖의 표현을 EXCLUDE_COMPONENT로 분류했을 때 "제외 요청이 확정되지 않았습니다" 안내가 사라질 수 있다. B-59의 최종 실패 안내도 건너뛴 경우에는 나오지 않는다 |
  | C. 출력 축소(운영 프롬프트와 응답 형식 변경): 제약 추출에서 \`rawStatement\` 제거(출력 39%), 번역에서 쓰이지 않는 필드 정리 | 최종 추출 230 → 약 140토큰이면 약 11초, init 추출 79 → 약 48토큰이면 약 4초: 합 약 15초(66 → 약 51초). 번역 105 → 약 60토큰이면 발화마다 약 5초 | 운영 프롬프트(제약 추출, 번역)와 \`Stage0ClarificationEngine.kt\`·\`ClarifyUtteranceTranslator.kt\`의 파서 | 화면 변경 없음, \`rawStatement\` 저장 값이 인덱스 대체값이 될 수 있음 | **높음**: 프롬프트 변경은 분류 품질 측정이 필요하고 \`rawStatement\` 대체가 발화와 제약의 인덱스 대응에 의존해 다중 발화에서 틀릴 수 있다 |

  **추천: B를 먼저**(절감이 가장 크고 분석 입력에 영향이 없다. 단 저장되는 \`constraints\` 내용 변경과 안내 소실 위험을 테스트로 고정하고 제외 표현 판정 기준을 \`ExclusionWords\`로 통일), 그다음 A는 B 뒤에도 제외 표현이 있는 경우의 추출과 정제문 병렬에 쓸 수 있다. C는 프롬프트 변경이라 별도 측정이 필요해 마지막이다. 작업 요약(화면 전용)을 줄이는 것은 구간이 아니라 요약이 화면에 보이는 시간(약 21초 → 더 짧게)에만 영향이 있어 이번 후보에서 뺐다.
- **2-f 사용자 화면의 진행 표시**: (1) 초기 /clarify: \`WebviewActionRouter.kt:135\`의 \`analyze_start\` 메시지("프로젝트 메타그래프 기반으로 요구사항을 구체화하고 있습니다...")가 로딩 점과 상태 문구로 보인다(\`App.tsx:785-789\`). (2) **발화를 보낸 뒤(번역 15~18초)에는 진행 표시가 없다**: \`handleSendUtterance\`(\`App.tsx:296-309\`)는 입력창을 비우고 600ms만 전송 중 상태를 두며, 요약 영역의 "요약 갱신 중…"(\`App.tsx:439\`)이 있을 뿐이다. (3) 확인 버튼 뒤(최종 제약 추출 28초 + 정제문 6초)에는 카드의 "확인이 제출되었습니다. 분석을 진행 중입니다..."(\`App.tsx:537\`)만 보이고, 분석 메시지("영향도 분석을 시작합니다", \`executeAnalyzePipeline\`의 \`analyze_start\`)는 의도 생성이 끝난 뒤에야 나온다.
- **예상과 다른 점**: (1) 요약이 "순차로 두 번"이라 구간에 두 번 더해진다고 봤지만 실제로는 둘 다 다른 호출과 병렬이라 **작업 요약은 임계 경로에 없다**(요약 2회를 합쳐도 구간은 줄지 않는다). (2) 정제문이 최종 제약 추출의 결과를 쓸 것이라 봤지만 **프롬프트가 제약 인자를 쓰지 않아** 병렬화할 수 있다. (3) 호출 시간에 고정 비용이 보일 것이라 봤지만 네 호출 모두 7.5~8.0 tok/s로 **시간 ≈ 출력 토큰 / 8**이었다. (4) 구간의 7초가 LLM 호출 밖에 있고 원인을 알 수 없다(초기 추출 뒤 스캐너·제외 후보 탐색 등 비LLM 구간으로 추정하지만 로그가 없다). (5) 최종 제약 추출이 앞 추출과 많이 겹칠 것이라 봤지만 입력이 달라(SR 대 발화) 겹치지 않고, 제외 표현이 없는 발화는 앞에서 추출된 적이 없다.

### [B-63] clarify 대기 진행 표시, 정제문 병렬화(후보 A), 발화 시점 선추출(B′) 설계 (2026-10-23)
- **상태: 커밋 1·2 합격, push함.** 커밋: 9df7807(웹뷰 진행 표시), 0352f96(후보 A), 이 docs 커밋. LLM 호출은 하지 않았다(테스트의 모의 LLM만).
- **커밋 1(웹뷰, 백엔드 diff 0)**: 변경 파일은 `App.tsx`(+61), 새 `clarifyWaitLogic.ts`, 새 `webview/test/clarifyWaitLogic.test.ts`, 빌드 산출물(`index.html`, `tsbuildinfo`)뿐이고 `src/main/kotlin`은 0이다.
  - 발화 전송 뒤(`App.tsx:322` 부근): 600ms 전송 중 상태를 없애고 응답(새 대화 카드 `analyze_clarify`), 분석 시작(`analyze_start`), `error`, `task_cancelled` 중 하나가 올 때까지 로딩 점과 "요청을 해석하는 중입니다"를 보인다. 그동안 입력과 버튼은 비활성이고 버튼 문구는 "해석 중..."이며 확인 버튼도 비활성이다(해석 중에 확인이 가서 세션이 경합하는 것을 막기 위해 더한 한 가지, 지시에 없던 변경). 연속 전송은 막힌다.
  - 확인 뒤(`:576` 부근): 기존 문구는 그대로 두고 로딩 점과 "경과 N초"를 붙이며 `analyze_start`가 오면 내린다. 퍼센트, 단계, 예상 시간은 없다. 백엔드 메시지와 새 subType은 추가하지 않았다(웹뷰가 이미 받는 메시지만 사용).
  - 응답이 끝내 오지 않을 때를 위한 안전 상한 600초(운영 타임아웃 기본 300초의 두 배)를 두었다. 경과를 알리는 용도가 아니라 표시가 영영 남지 않게 하는 용도다.
  - 테스트: 웹뷰 로직 9개(시작, 응답 시 종료, 오류·취소 시 종료, 요약 도착은 종료하지 않음, 연속 전송 거절과 시작 시각 유지, 확인 뒤 경과 초와 분석 시작 시 종료, 안전 상한)가 통과했고 기존 12개와 합쳐 21개가 통과했다(`node --experimental-strip-types --test webview/test/removedCandidatesLogic.test.ts webview/test/clarifyWaitLogic.test.ts`). `build/ui-check/run.mjs`에 대기 구간 시나리오 11~16을 더했고 **16/16 됨**(모의 브리지에서 응답을 2.5초, 확인을 4초 지연): 11 전송 1.2초 뒤에도 "요청을 해석하는 중입니다"와 입력 비활성, 12 연속 전송 차단(서버로 간 요청 1건), 13 응답 시 표시 해제, 14 오류 시 표시 해제와 입력 재활성, 15 확인 뒤 기존 문구 유지와 경과 1초 → 3초 증가(퍼센트·예상 없음), 16 분석 시작 시 표시 해제와 제출 문구 유지. 기존 1~10도 그대로 됨.
  - **관찰(기존 동작, 이번에 고치지 않음)**: 발화 처리 오류의 문구가 화면에 보이지 않는다. 라우터는 오류를 `clarify_utt_<시각>` 같은 id로 보내는데(`WebviewActionRouter.kt:288`, `:339`) 그 id의 메시지가 웹뷰에 없고, `App.tsx`는 새 메시지를 `_start`, `task_code`, `analyze_clarify`에서만 만든다(`:1340`, `:1487` 부근). 그래서 이번 변경으로 오류 시 로딩이 내려가지만 오류 문구는 여전히 보이지 않는다. 별도 과제.
- **커밋 2(후보 A, `Stage0ClarificationEngine.kt`만)**: `buildClarifyIntentChecked`(`:1349` 부근)가 최종 제약 추출과 정제문 호출을 `CompletableFuture`와 데몬 스레드 풀(`refineExecutor`, 파일 맨 위)로 동시에 시작하고 둘 다 끝나면 합친다. 정제문 프롬프트는 SR과 발화만 써서(B-62) 쓰이지 않던 `constraints` 인자를 제거했다. 정제문을 별도 스레드로 보내지 못하거나 결과를 받지 못하면 같은 입력으로 이 스레드에서 다시 부르므로 결과 `ClarifyIntent`는 순차 실행과 같다. `ClarifyIntent`와 저장 형식, B-59 안내 문구와 세션당 1회 규칙, 90초 타임아웃과 재시도는 그대로다.
  - **2-c 테스트별 측정 시간(모의 LLM 지연)**: p1 추출 3000ms + 정제문 1000ms → `buildClarifyIntent` **3010ms**(4000ms가 아님, 합격선 2900~3800ms), p2 추출만 실패(안내 있음, 정제문 반영) 9ms, p3 정제문만 실패(원문 대체, 추출 안내 없음)와 둘 다 실패(안내 있음, 원문) 33ms, p4 지연 700ms + 300ms에서 같은 `ClarifyIntent`(지연 없는 실행과 `equals`)이고 두 호출이 한 번씩만 불림 858ms(순차였다면 1000ms 이상). 순차 코드와의 동치는 같은 코드로 지연 유무를 비교한 것이다(옛 코드를 되돌려 실행하지 않았다).
  - 검증: 전체 535/42/17(531에서 +4), clarify 230/1/3(226에서 +4), 실패 메시지 diff 0, 하네스 EXACT_MATCH 5종·MATCH_EXCLUDING 2종 일치, selftest 24/24, 웹뷰 로직 테스트 21개, ui-check 16/16. 기대가 바뀐 기존 테스트는 없다.
  - **예상 효과(미측정)**: 최종 제약 추출 28초 + 정제문 6초가 28초로 줄어 SR-08 구간 약 6초 감소(B-62 후보 A). 실측은 하지 않았다.
- **3-a ClarifyIntent.constraints를 읽는 곳(`src/main`)**: 읽는 곳이 **없다**. 정의 `model/ClarifyIntent.kt:21`, 쓰는 곳 `Stage0ClarificationEngine.kt:1438`, 저장소 DTO 매핑 `ClarifyIntentStore.kt:156`, `:228`(그 `loadIntent`는 `src/main` 호출처가 없다, ADR-001)뿐이다. `/implement`(`ImplementService`는 `lastResult`의 요약과 대상 파일만 읽음), 다음 세션(디스크 의도 로드 없음), 문서 생성, 분석(`AnalyzeInputResolver`)은 읽지 않는다. 엔진 안에서 추출 결과(지역 변수 `constraints`)를 쓰는 곳은 `:1375`(`unconfirmedExclusions`: EXCLUDE_COMPONENT의 evidence로 "제외 요청이 확정되지 않았습니다" 안내를 만듦)와 `:1438`(저장)뿐이다. 테스트만 `intent.constraints`를 읽는다.
- **3-b 덤프의 제약 추출 kind 분포**(고유 프롬프트 41건, `build/kind_dist.js`):

  | 입력 | kind별 건수 |
  |---|---|
  | SR 원문, 제외 표현 없음 | OTHER 12, NEW_MODULE 3, SCOPE_LIMIT 2, INCLUDE_CHANNEL 1, 빈 목록 1 |
  | SR 원문, 제외 표현 있음 | SCOPE_LIMIT 1, EXCLUDE_COMPONENT 1 |
  | 발화, 제외 표현 없음 | NEW_MODULE 4, OTHER 4, 빈 목록 3, SCOPE_LIMIT 3, **EXCLUDE_COMPONENT 3** |
  | 발화, 제외 표현 있음 | OTHER 13, EXCLUDE_COMPONENT 10, SCOPE_LIMIT 7 |

  `ExclusionWords`(건드, 빼, 제외, 말고, 그대로, 배제, 손대, 수정하지, 변경하지)에 걸리지 않는 입력에서 EXCLUDE·SCOPE_LIMIT가 나온 건: 발화 3건은 모두 한정 발화다 — SR-05 "활성 상태인 조직만요. 대기 중인 조직도 숨겨 주세요."(SCOPE_LIMIT, EXCLUDE_COMPONENT), SR-07 "게시하지 않은 것만 숨기면 됩니다. 목록 화면에서 다시 보는 버튼은 없어도 돼요."(SCOPE_LIMIT, EXCLUDE_COMPONENT), SR-11 "배너를 하나씩 삭제하는 것만요. 삭제 전에 확인창을 띄우는 건 화면 쪽에서 합니다."(SCOPE_LIMIT, EXCLUDE_COMPONENT). SR 원문 2건(D3 신규서비스 개발 SCOPE_LIMIT, S3 "결과 처리를 손봐야 해요" SCOPE_LIMIT)도 있다. **키워드로 최종 제약 추출을 건너뛰면 이 3건의 EXCLUDE_COMPONENT가 사라져 현재 화면에 나오는 "제외 요청이 확정되지 않았습니다" 안내(SR-05, SR-07, SR-11에서 미확정 제외로 기록됨)가 바뀐다.** 이 안내가 사용자에게 옳은지(데이터 필터링 표현을 파일 제외로 오인한 것일 수 있음)는 별개 문제이고, 키워드 차단을 하지 않는 근거로 쓴다.
- **3-c B′(발화 시점 선추출) 설계(구현하지 않음)**:
  - **시작 위치**: 엔진 `processUtterance`가 번역기를 부르기 전(`Stage0ClarificationEngine.kt:1221` 부근)에 발화 하나의 제약 추출을 `CompletableFuture`로 시작한다(번역기와 병렬, 사용자가 발화를 쓰고 읽는 시간에 숨음). 제외 표현이 있는 발화는 지금도 `:1285`에서 추출하므로 그 결과를 같은 캐시에 넣어 중복을 없앤다.
  - **결과를 담을 곳**: 엔진 인스턴스의 메모리 맵(발화 순번+원문 키 → 추출 Future). 세션 상태(`Stage0State`)나 디스크에는 쓰지 않는다(`ClarifyIntent` 저장 형식도 변경 없음).
  - **수정·취소·무효화**: 발화는 추가만 된다(UI에 수정·취소 없음). 새 `/clarify`는 새 엔진이라 캐시가 사라지고(`AnalysisSessionMemory.reset`과 같은 시점), 진행 중이던 선추출 LLM 호출은 취소되지 않고 결과만 버려진다(요약과 같은 성질).
  - **확인 시점에 선추출이 끝나지 않았을 때**: `buildClarifyIntentChecked`가 발화별 Future를 순서대로 기다리고(남은 시간만 대기), 캐시에 없거나 실패한 발화는 지금처럼 이 스레드에서 추출한다. 최악의 경우도 현재와 같다.
  - **실패 시 B-59 연결**: 발화별 실패 플래그를 합쳐 최종 `ConstraintExtraction.failed`로 쓰고 같은 안내 경로(세션당 1회)를 탄다.
  - **입력 차이(품질 위험)**: 지금 최종 추출은 발화들을 한 번에 넣고(`- 발화1\n- 발화2`) 응답의 `rawStatement`/`evidence`를 묶음 기준 인덱스로 맞춘다. B′는 발화 하나씩 추출해 이어 붙이므로 여러 발화에 걸친 맥락(2번째 발화가 1번째를 가리키는 경우)이 분류에 반영되지 않고 인덱스 대체(`rawStmt` 폴백)가 발화 안에서만 맞는다. 환각 가드(`evidence`가 발화 원문에 있는지)는 더 엄격해진다. 분석은 `constraints`를 읽지 않으므로 결과 영향은 저장되는 `constraints` 내용과 `unconfirmedExclusions` 안내에 한정된다.
  - **서버 부하**: 아래 3-d처럼 동시 호출은 호출당 속도를 거의 떨어뜨리지 않아 추가 호출 비용은 작다.
- **3-d 동시 호출 속도와 버려지는 요약**(clarify 실행 88회, `build/concurrency.js`):

  | 동시 진행 호출 수(호출 구간 중 최대) | 호출 수 | tok/s 중앙 | 최소~최대 |
  |---|---|---|---|
  | 1 | 146 | 8.1 | 1.0~8.4 |
  | 2 | 232 | 7.8 | 2.5~8.4 |
  | 3 | 133 | 7.7 | 1.6~8.3 |
  | 4 | 4 | 7.9 | 6.9~8.0 |

  동시 호출 수가 늘어도 호출당 속도가 거의 줄지 않는다(8.1 → 7.7). 작업 요약 호출 148건 중 **다음 요약이 끝나기 전에 시작해 결과가 버려진 호출이 44건(30%)**, 그 completion 토큰은 **7,780**(전체 요약 completion 23,624의 33%)이다.
- **3-e 비LLM 약 7초(읽기로 추정)**: 초기 제약 추출(`Stage0ClarificationEngine.kt:331`) 뒤 `initSession`이 도는 함수는 `resolveUserUtteredIdentifiers`(`:339`, 그래프에서 발화 식별자를 찾음), `scanner.rescanUnverified`(`:346`, 3,552 노드 그래프에서 후보 625개를 점수 계산), 제외 표현이 있을 때만 `resolveExclusionCandidates`, `checkOpenQuestionTrigger`(`:1126`)와 항목 조립이다. 가장 무거운 것은 `rescanUnverified`로 추정되지만 측정한 값은 없다. **재려면** `initSession` 안에서 `:331` 직후, `:339` 직후, `:346` 직후, `checkOpenQuestionTrigger` 직후에 `System.nanoTime()` 로그를 한 줄씩 넣고, 평가 하네스(`LiveEvalTest.kt:140` `initSession` 호출 앞뒤와 `:151` `processUtterance` 앞뒤)에도 같은 로그를 넣으면 7초가 엔진 안인지 하네스 쪽인지 나뉜다(이번에는 넣지 않았다).
- **예상과 다른 점**: (1) ui-check에서 오류 시 로딩은 내려갔지만 **오류 문구가 화면에 안 나왔다**(기존 동작, 위 관찰). (2) 동시 호출이 서로 느리게 만들 것이라 봤지만 호출당 속도가 8.1 → 7.7 tok/s로 거의 같아 **병렬화 비용이 사실상 없다**. (3) 요약 낭비가 클 것이라 봤고 실제로 **요약 호출의 30%, 토큰의 33%가 버려진다**(B-62에서 임계 경로 밖이라 후보에서 뺐지만 서버 부하와 비용 측면에서는 의미가 있다). (4) 키워드 규칙으로 걸러도 될 만큼 한정 발화가 EXCLUDE로 분류되지 않을 것이라 봤지만 **한정 발화 3건이 EXCLUDE_COMPONENT로 분류**돼 키워드 차단은 현재 화면 출력을 바꾼다. (5) 저장된 `constraints`를 읽는 곳이 있을 것이라 봤지만 **`src/main`에는 읽는 곳이 없다**.

### [B-64] 버려지던 오류 메시지 표시(웹뷰), 미확정 제외 안내의 사실 확인, 최종 제약 추출을 임계 경로에서 빼는 D안 설계 (2026-10-26)
- **범위**: 웹뷰만 수정(커밋 `ec2f205`). Kotlin(`src/main`, `src/test`), 운영 프롬프트, LLM 호출 없음. 3번은 읽기와 기존 보고서 분석(`build/` 스크립트, 커밋하지 않음).
- **1-a 라우터가 보내는 오류와 웹뷰 처리(변경 전)**: 라우터의 `sendMessage("error", …)` 42곳 중 **18곳이 화면에서 버려졌다**(24곳은 표시). 웹뷰는 메시지 id에 해당하는 메시지가 있을 때만 오류를 붙이고(`App.tsx` 공통 처리), 없으면 "빈 말풍선 방지"로 무시했으며(`:1487` 부근), messageId가 없는 오류는 `if (messageId)` 바깥이라 아무 처리도 없었다.

  | id 접두어 | 라우터 위치 | 오류 전송 위치(라인) | 앞서 메시지가 만들어졌나 | 변경 전 웹뷰 |
  |---|---|---|---|---|
  | `msg_`(explain, chat, find) | `:94`, `:742`, `:772` | `:116`, `:763`, `:793` | explain_start·chat_start 뒤 | 표시 |
  | `clarify_` | `:127` | `:131`(빈 요구사항) | analyze_start **전** | **버려짐** |
  | `clarify_` | `:127` | `:184` | analyze_start 뒤 | 표시 |
  | `analyze_` | `:193`, `:201` | `:205`(세션 만료) | start **전** | **버려짐** |
  | `analyze_` | `:1534`~ | `:1618`(분석 오류) | analyze_start 뒤 | 표시 |
  | `clarify_utt_` | `:283` | `:288`(세션 만료), `:339`(처리 오류) | 없음(analyze_clarify는 성공 때만 만들어짐) | **버려짐 2곳** |
  | `restore_` / requestId | `:399` | 오류 전송 없음 | - | - |
  | `implement_`, `test_` | `:415`, `:458` | `:420`, `:449`, `:466`, `:491` | chat_start 뒤 | 표시 |
  | `doc_`, `rag_` | `:500`, `:561` | `:551`, `:608` | explain_start 뒤 | 표시 |
  | `doc_`, `rag_` | 같음 | `:519`, `:578`(소스 파일 없음) | **id 없음** | **버려짐 2곳** |
  | `impact_` | `:618` | `:655` | explain_start 뒤 | 표시 |
  | `impact_` | `:618` | `:627`(대상 미지정) | **id 없음** | **버려짐** |
  | `metagraph_` | `:664` | `:722` | explain_start 뒤 | 표시 |
  | `metagraph_` | `:664` | `:677`("분석이 취소되었습니다.") | start **전** | **버려짐** |
  | `task_` | `:803`, 오류 id `:948` | `:948`, `:1013`~`:1142` 7곳 | task_start 뒤 | 표시 |
  | 웹뷰가 준 id(apply, undo 등) | `:1154`, `:1255` | `:1271`(undo 내역 없음) | 기존 메시지 id | 표시 |
  | 같은 분기들(id 없음) | `:1154`, `:1255` 등 | `:1177`, `:1184`, `:1213`, `:1219`, `:1274`, `:1283`, `:1308`, `:1441`, `:1470`, `:1511` | **id 없음** | **버려짐 10곳** |

  요약: id가 있는데 짝이 없는 오류 5곳(`:131`, `:205`, `:288`, `:339`, `:677`)과 id가 없는 오류 13곳(`:519`, `:578`, `:627`과 위 10곳)이 버려졌다. clarify_utt_ 말고도 버려지는 것이 더 있었다.
- **1-b 오류 본문 형식**: `:288`은 고정 안내 문장("분석 세션이 만료되었거나 유효하지 않습니다. `/clarify`를 다시 실행해주세요.")이라 사용자에게 보여도 된다. `:339`는 `"발화 처리 중 오류가 발생했습니다: ${e.message}"`로 **예외 메시지 원문이 붙는다.** 스택트레이스는 아니지만 예외 메시지에는 내부 정보가 섞일 수 있다(엔진의 `error("USER_UTTERED PENDING 항목(id=…)…")` 같은 항목 id와 내부 상태, Gson 파싱 오류의 JSON 조각, NPE의 `null`, 서버 주소가 든 통신 오류 등). `${e.message}` 형식은 `:184`, `:449`, `:491`, `:655`, `:1308`, `:1618`에도 있다.
- **2번 변경(커밋 `ec2f205`, 웹뷰만)**:
  - 짝이 없는 오류와 id가 없는 오류를 버리지 않고 대화창에 **오류 말풍선**(ERROR 표시)으로 보인다. 짝이 있는 오류와 오류가 아닌 메시지(짝 없는 `task_success` 등) 처리는 그대로다(ui-check 19로 확인).
  - 본문에 내부 정보가 섞였을 수 있으면(`…오류가 발생했습니다: <내용>` 형식, 주소, 예외 이름, 경로, 스택 프레임) 고정 문구 **"요청을 처리하지 못했습니다. 다시 시도해 주세요."**로 보이고 원문은 화면에 내지 않는다. 고정 안내 문장은 원문 그대로 보인다. 로직은 `webview/src/errorDisplayLogic.ts`다.
  - 백엔드 메시지, subType, Kotlin 파일은 바꾸지 않았다(`git diff --stat -- src/main/kotlin src/test` 빈 출력).
  - 테스트: 웹뷰 로직 6개 추가(고정 문장 원문, 내부 표지의 고정 문구 치환, 빈 본문, 말풍선 모양과 입력 불변, 연속 오류, 오류 뒤 정상 발화)로 기존 21개와 합쳐 **27개 통과**. ui-check는 14를 "오류 문구(고정 문구)가 보이고 예외 원문은 안 보임"까지 확인하도록 바꾸고 17(고정 안내 오류와 id 없는 오류가 원문으로 보임), 18(연속 오류 2건과 내부 상세 비노출), 19(오류 아닌 짝 없는 메시지는 말풍선 없음)를 더해 **19/19 됨**.
  - **부수 효과**: 지금까지 보이지 않던 "분석이 취소되었습니다."(`:677`, 메타그래프 디렉터리 선택을 취소하면 `error`로 보냄)도 이제 오류 말풍선으로 보인다. 취소는 오류가 아니므로 거슬릴 수 있다(라우터가 `error` subType으로 보내는 문제라 Kotlin 수정 과제).
- **3-a 미확정 제외 안내가 나온 경우**(live_eval 보고서 22개, 안내가 나온 실행 54건, 고유 근거 구문 14개, `build/` 스크립트):

  | 시나리오(실행 수) | 발화 원문(안내가 인용한 근거) | 안내가 가리킨 대상 | 분류 | 근거 |
  |---|---|---|---|---|
  | D5·D5E·D5N(15) | "알림톡 배치는 건드리지 말고 SurveyServiceImpl만 수정할 거야" | AlimtalkTemplateBatchJob, AlimtalkTemplateBatchRunner | 파일 제외 요청이 맞음 | "건드리지 말고", 시나리오의 제외 대상(mustNotAppear)이 알림톡 배치 3개 |
  | S3(6, 옛 형식) | "로그인 쪽 컨트롤러는 건드리지 마세요." → 안내는 '건드리지 마세요'(대상 파일을 특정하지 못했습니다) | 대상 없음 | 파일 제외 요청이 맞음 | 발화가 컨트롤러 제외를 말했고 후보를 못 찾음 |
  | S3(2) | 같은 발화 | AuthDto, UserDto, LoginUserDto, UserServiceImpl, AlimtalkTemplateBatchRunner 외 7 | 파일 제외 요청이 맞음(후보는 맞지 않음) | 발화는 로그인 컨트롤러인데 후보는 DTO·서비스와 무관한 배치 파일 |
  | SR-12(5) | "…관리 화면은 그대로 두세요."(4문장 전체) | APCADPrvMngtBIZ 등 관리 화면 후보(약 294개 중 앞 일부) | 파일 제외 요청이 맞음 | 마지막 문장이 관리 화면 제외, 시나리오 제외 대상 APCADUpdtVerMngtBIZ. 근거 구문이 4문장이라 범위가 넓음 |
  | SR-05(4), SR-05X(8) | SR 원문 "…관리자 화면의 조직 목록은 그대로 두세요." | AdminManageResponse, AdminService, AdminRole, LoginRequest, SignUpRequest 외 76개 | 파일 제외 요청이 맞음 | 제외 대상 AdminService, AdminOrganizationResponse. 단 **SR-05X는 사용자가 두 파일을 이어서 명시해 "✅ 제외 확정"이 났는데도 이 안내가 남고 후보에 AdminService가 들어 있다** |
  | SR-05(3) | "활성 상태인 조직만요. 대기 중인 조직도 숨겨 주세요." | ShopStatus, WebSocketEventListener | 데이터 필터링을 오인함 | 조직 상태로 목록을 거르라는 뜻이고 파일 제외가 아님 |
  | SR-07(4) | "게시하지 않은 것만 숨기면 됩니다. 목록 화면에서 다시 보는 버튼은 없어도 돼요." | CashReceiptServiceImpl, MainServiceImpl 등 | 데이터 필터링을 오인함 | 게시 여부 필터와 화면 버튼 이야기라 파일 제외가 아님 |
  | SR-01(4) | "3회로 바꾸고, 잠금 해제 방식은 지금 그대로 두세요." | AlimtalkTemplateBatchRepository | 판단 불가 | "그대로 두세요"는 기능 유지라 파일 제외일 수도 있으나 대상 지정이 없고 후보는 무관 |
  | SR-11(3) | "배너를 하나씩 삭제하는 것만요. 삭제 전에 확인창을 띄우는 건 화면 쪽에서 합니다." | APCADUpdtVerMngtBIZ 등 | 판단 불가 | 범위 한정과 책임 구분 발화로 보이고 파일 제외 요청은 아님 |

  실행 기준 분류: 파일 제외 요청이 맞음 40건(74%), 데이터 필터링을 오인함 7건, 판단 불가 7건. **사용자가 할 수 있는 일(응답 경로)**: 현재 안내는 "다음 분석에서는 Stage 3 후보 확인 카드에서 해당 파일의 체크를 해제하거나, 새 세션(/clarify)에서 파일명을 지정해 다시 요청해 주세요"라고 하고 옛 형식은 "/clarify 단계에서 목록의 체크를 해제하세요"였다. 경로는 (1) 운영의 Stage 3 카드(후보가 안내에 보인 파일들이라 SR-05·SR-07·S3처럼 후보가 의도와 무관하면 해제할 대상이 없다), (2) 새 `/clarify`에서 파일명 지정(8627fe5 규칙이 즉시 제외)이다. 오인 사례(SR-05, SR-07)와 판단 불가 사례에서는 사용자가 할 일이 없는데도 "제외하려면…" 안내가 나와 혼란을 줄 수 있다.
- **3-b D안 설계(최종 제약 추출을 임계 경로에서 빼기, 구현하지 않음)**:
  - **분석 시작이 지금 기다리는 것**: `/analyze-confirm`은 `processTurn`(`WebviewActionRouter.kt:236`) → `buildClarifyIntentChecked`(`:264`, 최종 제약 추출과 정제문, 추출이 더 길어 약 28초) → 세션 제거 → `ClarifyIntentStore.saveIntent`(`:270`) → `executeAnalyzePipeline`(`:277`) → `analyze_start` → 그래프 로드 → `AnalyzeInputResolver.resolve`(`:1556`) → `pipeline.analyze`(`:1570`) 순이다. `/clarify-utterance`의 완료 경로도 같다(`:322`~`:335`). 분석 입력(정제문, `retentionAudit`, 식별자)은 정제문에서만 나오고 제약 추출 결과를 쓰지 않으며, 추출 결과가 화면에 닿는 곳은 `unconfirmedExclusions`가 만드는 "제외 요청이 확정되지 않았습니다" 줄(`RequirementAnalysisPipeline.kt:430`의 `exclusionSummaryLines()`, 분석이 끝난 뒤 결과를 조립할 때)과 B-59 실패 안내뿐이다. 그 줄은 분석 시작 뒤 약 100초 이후에야 쓰이므로 추출은 분석과 병행해도 늦지 않는다.
  - **안내를 화면에 보내는 법**: 새 subType 없이 기존 `sendMessageChunk`(task_chunk)로 가능하다. (1) 분석 스트리밍 중간에 끼우면 진행 줄("> (Stage 2) …") 사이에 낀 한 줄로 보이고 마크다운 구조(헤더 앞)가 어색해진다. (2) 권장은 결과 마지막(`executeAnalyzePipeline`의 완료 블록, `/implement` 안내 직전, 종료 신호 `chat` 앞)에 붙이는 것이다. 위치가 고정이고 스트림과 섞이지 않는다. 미확정 제외 줄은 지금 결과 맨 위(요약 헤더 바로 아래)에 나오는데, D안에서는 파이프라인에 늦게 결정되는 값(예: `() -> List<UnconfirmedExclusion>`)을 넘겨 줄 조립 시점에 읽거나, 줄을 결과 끝으로 옮겨야 한다(화면 위치가 달라진다).
  - **저장 시점**: (a) 추출 없이 먼저 저장하고 끝난 뒤 덮어쓰기: 중간 상태 파일이 `constraints` 없이 남는다(저장 형식은 같음, 빈 목록은 유효). (b) 추출이 끝난 뒤 한 번만 저장(권장): 중간 상태가 없고 분석은 이미 돌고 있다. ADR-001은 의도 파일을 자동으로 읽지 않으므로(`findIntentFile`, `loadIntent` 호출처 0) 두 방식 모두 읽기 쪽 영향은 없다. 위험은 쓰기끼리의 경합이다: 같은 키(`default`)의 파일을 새 `/clarify` 세션이 저장한 뒤 이전 세션의 늦은 쓰기가 덮어쓸 수 있으므로, 완료 시점에 세션 세대(엔진 인스턴스 id)가 현재와 같을 때만 쓰고 아니면 버려야 한다.
  - **B-59 실패 안내**: 같은 경로로 보낼 수 있다(추출 완료 콜백이 결과 끝에 안내를 붙이고 세션당 1회 규칙은 엔진 플래그 그대로).
  - **추출이 끝나기 전에 새 /clarify나 /restore가 오면**: 새 `/clarify`는 새 엔진과 `AnalysisSessionMemory.reset()`이라 이전 추출의 완료 콜백은 세대 불일치로 저장과 안내를 모두 버린다(LLM 호출은 취소되지 않음). `/restore`는 `lastResult`와 pinned만 바꾸고 의도·추출과 무관해 영향이 없다. 분석이 먼저 끝나고 추출이 아직이면 결과 끝 안내만 늦게 붙거나 생략된다.
  - **바뀌는 파일과 줄 수**: `Stage0ClarificationEngine.kt`(최종 추출을 Future로 분리, 빠른 부분과 늦은 부분 나누기, 약 40줄), `WebviewActionRouter.kt`(두 완료 경로에서 부분 의도로 분석 시작, 완료 콜백의 저장과 안내와 세대 확인, 약 50줄), `RequirementAnalysisPipeline.kt`(늦게 결정되는 미확정 제외 값을 받는 인자와 줄 조립, 약 20줄), 테스트 약 100줄. 합계 약 200~250줄.
- **3-c 현행, B′, D 비교**(근거 수치는 B-62·B-63의 측정):

  | 항목 | 현행(후보 A 적용 후) | B′(발화 시점 선추출) | D(추출을 분석과 병행) |
  |---|---|---|---|
  | 확인 뒤 사용자 대기 | 최종 추출과 정제문의 긴 쪽: SR-08 28초, 전체 제약 추출 중앙 15.9초 | 발화~확인 사이에 선추출이 가려진 만큼 감소. 사용자가 28초 이상 뒤에 확인하면 정제문만 남아 약 6초(−22초). 하네스처럼 번역(15초)과 겹칠 때는 남은 약 13초(−15초) | **항상 정제문 시간만**: SR-08 6초(−22초), 중앙 5.5초(−10초). 사용자 입력 시각과 무관 |
  | 추출 입력 | 발화들을 한 번에 | **발화별**(맥락 손실 위험) | 현행과 같음(묶음) |
  | 화면 변화 | 안내는 분석 시작 직후 첫 줄 | 변화 없음 | 미확정 제외 줄과 B-59 안내가 결과 **끝**(또는 늦은 위치)으로 이동 |
  | 저장 변화 | 분석 전에 한 번 저장 | 변화 없음 | 추출이 끝난 뒤 한 번 저장(형식 동일, 시점만 늦음) |
  | 품질 위험 | 없음 | 저장되는 제약과 미확정 안내가 발화별 분류로 달라질 수 있음 | 세션 경합(이전 세션의 늦은 쓰기), 안내 위치 변화, 추출 실패 안내가 분석 뒤에야 나옴. 분석 입력은 영향 없음 |
  | 추가 호출 | 없음 | 발화마다 추출 1회(동시 호출은 속도 영향 거의 없음) | 없음 |

  **의견(사실에 근거)**: 확인 뒤 대기를 줄이는 효과는 D가 가장 일정하고 추출 입력이 바뀌지 않는다. B′는 사용자 입력 시각에 의존하고 입력이 달라진다. D는 화면 위치와 세션 경합 처리가 필요하다. 두 안은 함께 쓸 수 있지만 지금은 선택하지 않는다.
- **3-d 버려지는 요약 취소 가능성**: `TaskSummaryScheduler.schedule`은 `executor.execute`(Runnable)라 취소 가능한 Future를 돌려주지 않고 버전 번호만 바꾼다(`TaskSummaryScheduler.kt:46`~`:66`). 진행 중인 요약의 HTTP 호출을 끊으려면 (1) Future 취소가 필요하고 (2) `HttpURLConnection` 블로킹 읽기는 `Thread.interrupt`로 끊기지 않는다. 기존 취소 장치는 `TaskCancellationToken.cancel`이 `activeHttpConnection?.disconnect()`(`TaskCancellationToken.kt:58`)와 `activeInputStream?.close()`(`:57`)를 하는 것인데, `OpenAIClient.kt:191`이 이 **전역 슬롯 하나**에 현재 비스트림 연결을 넣고(`:218`에서 비움) 동시 호출이 서로 덮어쓰며, `cancel`은 `isCancelled`(전역 플래그)도 세워 다른 호출까지 중단시킨다. 그래서 특정 요약만 끊을 수 없다(호출별 연결 핸들을 `OpenAIClient`에 도입해야 함). 연결을 끊었을 때 서버(vLLM)가 요청을 중단하는지는 **미확인**이다.
- **예상과 다른 점**: (1) 버려지던 오류가 clarify_utt_뿐이 아니라 **42곳 중 18곳**(id가 있는데 짝이 없는 5곳, id 자체가 없는 13곳)이었고, 세션 만료 안내(`:205`, `:288`)도 사용자에게 보이지 않았다. (2) 미확정 제외 안내 54건 중 파일 제외 요청이 맞는 것은 40건(74%)이고 14건(26%)은 오인 또는 판단 불가다. (3) SR-05X에서는 사용자가 파일을 명시해 확정까지 됐는데도 같은 SR 문장에 대한 "확정되지 않았습니다" 안내가 남고 후보에 확정된 AdminService가 들어 있어 부분적으로 사실과 다르다. (4) 오류 표시 수정의 부수 효과로 "분석이 취소되었습니다."가 오류 말풍선으로 보이게 되었다. (5) 요약 취소는 `OpenAIClient`의 전역 연결 슬롯 때문에 요약 하나만 안전하게 끊을 수 없다. (6) D안에서 분석 입력은 제약 추출 결과를 전혀 쓰지 않아 분석 시작을 막는 것은 정제문뿐이다(B-62에서 정제문이 임계 경로의 6초임을 이미 보았다).

### [B-65] clarify의 요구사항 구체화 진단(기존 자료만)과 새 산출물 형식 초안 (2026-10-26)

읽기만 했다. 코드·웹뷰·프롬프트·LLM·/tokenize·외부 접속은 하지 않았다. 사전 확인: `git status -s` 비어 있음, HEAD = origin = 192bf70. 판정은 SR 원문과 스크립트 발화만 보고 했고 정답 파일 목록(GT)은 보지 않았다. 판정은 사람 판단이며(애매하면 "판단 불가") 기준 틀은 사용자가 정한 것이다(빈칸 분류표 9개 범주, 질문 규칙 최대 5개, 산출물 "현재 동작/바뀐 뒤 동작").

#### 1-b 기능 비교표

| 항목 | 우리 clarify(현재) | spec-kit /clarify 방식(기준) | Copilot Workspace 방식(기준) |
|---|---|---|---|
| 질문을 만드는 근거 | 그래프 사실만. 생성형 질문 규칙은 하나뿐이다: 개념 토큰이 그래프에 없고 `브랜드/brand/api/연동`이 들어 있으면 고정 문장("외부 연동 API(예: Bizgo 등) 또는 별도 신규 모듈…")(`Stage0ClarificationEngine.kt:1126-1144`, 문장 :1140). 그 밖은 "프로젝트에 없는 이름 → 새로 만들 파일인가요?"(:192), 제외 질문(:614/:627/:641) | 빈칸 분류표(9개 범주)에서 "부분/없음"인 범주 | SR을 "현재 동작/바뀐 뒤 동작"으로 바꿔 보고 비는 곳 |
| 질문 수와 형식 | 턴당 문자열 1개(`Stage0TurnResult.openQuestion: String?`, :44-51). 객체/선택지 없음 | 최대 5개, 한 번에 하나, 선택지 2~5개 또는 짧은 답 | 질문보다 목록 편집 |
| 추천 답 | 없음(clarify 패키지에서 "추천/recommended" 문자열 없음) | 있음 | 있음(목록의 초안 자체가 제안) |
| 질문 상한 | 없음(상한 상수/카운터 없음) | 5 | 해당 없음 |
| 산출물 | `ClarifyIntent`(정제문, 사용자 발화, 앵커 토큰, constraints, excludedFiles, unresolvedItems, unconfirmedExclusions …) (`buildClarifyIntentChecked` :1344) | 명세 문서에 명확화 기록 | "현재 동작/바뀐 뒤 동작" 업무 언어 목록 |
| 산출물을 읽는 곳 | 정제문은 Stage 1/3, excludedFiles는 후보 사전 제외. `constraints`는 읽는 곳이 없다(정의 `model/ClarifyIntent.kt:21`, 기록 :1438, 저장 DTO `ClarifyIntentStore.kt:156/:228`; `src/main`에 reader 0) | 계획/구현 단계 | 다음 단계(파일 선택)가 입력으로 씀 |
| 코드 지식의 쓰임 | 대화에서 후보 목록(경로+근거)을 번역 프롬프트와 요약 프롬프트에 넣음(`ClarifyUtteranceTranslator.kt` 후보 컨텍스트, `generateTaskSummary` :1019). 정제 프롬프트(`refineRequirement` :1471)는 SR+발화만 | 코드 지식을 질문 근거로 안 씀(명세 수준) | 코드로 "현재 동작"을 채움 |
| 파일 선택이 일어나는 단계 | 대화 단계 안(Stage 0): 후보 카드 체크박스, 제외 제안 카드. 이후 Stage 1~3도 다시 거름 | 파일 선택 없음 | 산출물 확정 뒤 다음 단계 |

화면 카드 분류: 후보 항목 확인 카드(CONFIRMED/REJECTED 체크) = **파일 범위 조작**. 제외 제안 카드("…분석 대상에서 제외할까요?") = **파일 범위 조작**. 자연어 발화 입력 = **둘 다**(제약 기록은 구체화, 제외 발화는 범위 조작). 작업 요약 표시 = 구체화 표시(읽기 전용). 결론: 사용자에게 가는 질문 10건(아래) 중 구체화 질문은 2건뿐이고 나머지는 범위 조작·존재 확인이다.

#### 1-c 시나리오 대입 (21개: 기본 16 + holdout 4 + SR-05X)

범주 표기: A 기능범위·동작, B 데이터·도메인, C 화면·흐름, D 비기능, E 외부연동, F 예외·실패, G 제약·범위밖, H 용어, I 완료 판정. "물을 것"은 SR만 보고 정한 수(최대 5), "스크립트 답"은 그중 스크립트가 이미 답한 수. 실제 질문은 엔진 질문(`openQuestion`)과 화면 카드/에코를 나눠 적었다. holdout(SR-03, 06, 09, 10)은 기존 실행 기록이 없어 실제 질문은 "기록 없음".

| SR | 부분/없음 판정(원문 인용) | 물을 것/스크립트 답 | expectedQuestions | 실제: 엔진 질문 | 실제: 카드·에코 | B-64 3-a |
|---|---|---|---|---|---|---|
| D1 | F 없음("브랜드메시지 추가"에 실패 처리 없음), I 없음 | 2/1 | 없음 | 2(외부 연동 API 템플릿, 초기+발화 뒤 반복) | "제약 조건으로 기록…", "현재 후보 목록을 기준으로…" | - |
| D2 | A 부분("최근이력 1건": 기준), B 부분(두 회원ID 관계), F 없음 | 2/0 | 없음 | 0 | "구체적인 수정 사항…더 자세히 설명" | - |
| D3 | F 없음, I 없음, H 판단 불가("SAPACMM0802S01 참고") | 1/0 | 없음 | 1("SAPACMM0802S01은 프로젝트에 존재하지 않습니다…새로 생성?") | "현재 정의된 후보 범위와…" | - |
| D4 | A 부분, B 없음, C 부분("관리 화면"), D 없음, F 없음, G 없음, I 없음 | 5/0 | 없음 | 0 | - | - |
| D5 | F 없음, I 없음 (제약은 명확: "알림톡 배치는 건드리지 말고") | 2/0 | 없음 | 2(외부 연동 템플릿; 알림톡 파일 제외 확인) | "이 제약만으로는 파일이 분석에서 제외되지 않습니다" | 맞음(15) |
| S1 | A 부분("이상하게 나와서"), I 없음 | 2/0 | 없음 | 0 | "어떤 목록(채팅방, 메시지, 회원…)인지" (스크립트가 이미 "목록 조회하는 쪽"이라 답함) | - |
| S2 | A 부분("이용 정지": 기간·해제), B 부분(상태 값) | 3/1 | 없음 | 0 | "제약 조건으로 기록: 정지된 회원 로그인 차단" | - |
| S3 | A 부분("결과 처리를 손봐야"), I 없음 | 2/0 | 없음 | 1("'건드리지 마세요' 관련 파일이 12개라 특정하지 못했습니다") | "제약 조건으로 기록…" | 맞음(8) |
| SR-01 | C 부분("안내 문구도 그에 맞게": 새 문구) | 1/0 | 없음 | 1(알림톡 저장소 파일을 "관련될 수 있는 파일"로 제시) | - | 판단 불가(4) |
| SR-02 | B 부분(표시 항목), F 없음(기록 없는 사용자) | 2/1 | 없음 | 0 | "제약 조건으로 기록…" | - |
| SR-04 | I 부분(검색 밖 경로) | 1/0 | 없음 | 0 | "제약 조건으로 기록…" | - |
| SR-05 | C 부분("조직 목록": 어느 화면), H 부분("운영 중"↔"활성") | 2/1 | 없음 | 1("관리자 화면의 조직 목록은 그대로 두세요 관련 파일이 81개라…") | "제약 조건으로 기록… 이 제약만으로는…" | 오인(3, "대기 조직") + 맞음(SR 문장) |
| SR-07 | B 부분("게시하지 않은": 예약·임시 포함 여부) | 2/1 | 없음 | 0 | "제약 조건으로 기록…" | 오인(4, "미게시") |
| SR-08 | (SR만) A 부분("비슷한 단어" 기준); 스크립트 뒤 모두 명확 | 3/3 | 3개 | 0 | "제약 조건으로 기록…" | - |
| SR-11 | A 부분("삭제": 물리/논리) | 2/1 | 없음 | 0 | "제약 조건으로 기록… 이 제약만으로는…" | 판단 불가(3) |
| SR-12 | (SR만) A 부분("최신인지 판단하는 기준"); 스크립트 뒤 명확 | 2/2 | 2개 | 1("'관리 화면은 그대로 두세요' 관련 파일이 294개라…") | - | 맞음(5) |
| SR-03* | A 부분("실패 건수 대비 비율": 분모) | 2/1 | 없음 | 기록 없음 | 기록 없음 | - |
| SR-06* | (SR만) A 없음("더 안전하게"); 스크립트 뒤 명확 | 2/2 | 2개 | 기록 없음 | 기록 없음 | - |
| SR-09* | A 부분("사용을 잠시 중지": 중지 중 동작 범위) | 2/1 | 없음 | 기록 없음 | 기록 없음 | - |
| SR-10* | (SR만) 안내 문구·적용 범위 없음; 스크립트 뒤 명확 | 2/2 | 없음 | 기록 없음 | 기록 없음 | - |
| SR-05X | C 부분, H 부분(SR-05와 같음) | 2/1 | 없음 | 1(SR-05와 같은 초기 질문) | "다음 파일을 분석 대상에서 제외했습니다…" | 맞음(일부 낡음) |

**요약 줄**
- 물어야 할 질문 합계 44개(21개 시나리오, SR 기준). 스크립트가 이미 답한 것 18개, 스크립트 뒤에도 남는 것 26개.
- 실제로 던진 엔진 질문 10개(기록이 있는 17개 시나리오). 이 중 규칙상 물어야 할 질문과 겹침 2개(D1, D5의 외부 연동 템플릿 질문이 "발송 방식" 질문과 겹침. D1의 반복 1건은 중복). 나머지: 파일 범위 조작 6개(D5의 제외 확인, S3, SR-01, SR-05, SR-12, SR-05X), 파일 존재 확인 1개(D3), 중복 1개(D1). 질문 규칙 "파일 선택은 묻지 않는다"에 어긋나는 것이 6/10.
- expectedQuestions 적중: SR-08 0/3, SR-12 0/2(실제 질문은 파일 특정 요청이었다), SR-06은 실행 기록이 없어 판단 불가. 기록 있는 것 기준 0/5.
- 범주별 "부분+없음" 건수(스크립트 반영 뒤, 시나리오×범주 34칸): A 8, B 5, C 4, D 1, E 0, F 6, G 1, H 2, I 7. 판단 불가 1(D3 H).

#### 1-d 정제문 진단 (기존 보고서 `live_eval_*.json`의 `rawRefined`)

반영 판정(스크립트 조건을 조건 단위로 쪼갬, 대표 정제문=첫 번째 고유 정제문 기준, 정제문이 4~5개 달라진 시나리오는 보이는 3개만 비교):
- 스크립트 조건 30개 중 **반영 28, 일부 2, 누락 0**.
- 일부 2건은 의미가 바뀐 것이다: D2 SR "aCMBTBAPC024DEM.selTrcdIsInf **참조**하여" → 정제문 "aCMBTBAPC024DEM**에 추가**한다"(참조→추가 위치 변경). SR-07 스크립트 "다시 보는 버튼은 **없어도 돼요**"(선택) → 정제문 "버튼을 **제공하지 않습니다**"(금지).
- 정제문에서 조건이 더해진 것: S3 "요청된 범위 내에서만 진행"(정제문 2, 3), SR-05 "일반 조직 목록"(SR에 없던 말, 보이는 3개 모두), SR-08 정제문2 "동일하거나 **유사한** 단어"(스크립트는 "정규화 뒤 같으면"), SR-01 정제문3 "안내 문구**만**".
- 원문 SR 조건 누락: D3 정제문1에서 "SAPACMM0802S01 참고"가 빠짐(`missingBeforeFix`로 기록, 정제문2는 포함). SR-05X 정제문2는 "대기 중인 조직 숨김"을 표현하지 않음(활성만 표시이므로 논리적으론 포함, 조건 단위로는 일부).
- D4는 스크립트가 없어 정제문이 SR 그대로("케어회원 관리 화면 조회"), 구체화가 일어나지 않는다.

실행 간 차이: **조건 단위** = D3(참고 서비스 유무), SR-05X(대기 숨김 표현), SR-08(유사 단어 추가), S3(범위 문구 추가), SR-01("만"), SR-05("운영 중" 유무). **문구 단위** = D1, S1, S2, SR-02, SR-04, SR-11("화면(UI)/화면 쪽/프론트엔드"), SR-12(판정/설정/변경, 스토어별·OS별 표기). 정제 단계는 병목이 아니다: 스크립트 조건은 대부분 반영되고, 부족한 것은 "무엇을 물을 것인가"(1-c)다.

프롬프트 구성(B-60 측정값만 사용, 새 측정 아님; 후보 줄 ≈84.5 토큰(번역)/53.5(요약)에 후보 수 625(SR-08)를 곱한 값과 B-60 총량의 비):
| 프롬프트 | SR-08 총 토큰(B-60) | 후보 목록 몫(곱셈) | 요구사항·발화 몫 |
|---|---|---|---|
| 번역(상한 전) | 53,400 | ≈52,800 (≈99%) | ≈600 (≈1%) |
| 번역(K=120 뒤) | 10,422 | ≈10,140 (≈97%) | ≈280 (≈3%) |
| 요약(상한 전) | 33,715 | ≈33,400 (≈99%) | ≈300 (≈1%) |
| 요약(K=120 뒤) | 6,514 | ≈6,420 (≈99%) | ≈90 (≈1%) |
| 정제 | 중앙 ≈325 / 최대 398 | 0 (후보 목록 없음, `refineRequirement`는 SR+발화만) | 100% |
다른 시나리오: 번역 ≤ ~3.0K, 요약 ≤ ~1.6K. 후보 목록이 대화 프롬프트의 대부분(≈97~99%)이고 사용자 말(요구사항·발화)은 1~3%다.

Stage 입력 표(B-64 3-b "분석 입력은 정제문에서 나온다" 확인):
| 단계 | 읽는 텍스트 | 위치 |
|---|---|---|
| 입력 조립 | `retentionAudit.rawRefinedRequirement`(없으면 `refinedRequirement`, 비면 원문/rawInput 대체) + 정제문에서 빠진 식별자 줄 | `AnalyzeInputResolver.kt:52-85`(:58-62, :68-72) |
| Stage 1 후보 발견 | `effectiveReq = stage0Contract?.enrichedRequirementText ?: primaryReq` → `AdaptiveFileDiscovery.filter(primaryReq = effectiveReq)` | `RequirementAnalysisPipeline.kt:135-136`, :228-229 (`primaryReq == analyzeInput.effectiveRequirement` 강제: :129) |
| 사전 제외 | `clarifyIntent.excludedFiles`(파일 목록) | `RequirementAnalysisPipeline.kt:295-298` |
| Stage 3 검증 | `AnalyzeSections.buildStage3Base`: 본문 = `enrichedRequirementText` 또는 `input.rawRefined`(+2차 요구) + 미확정/누락 식별자 줄 | `AnalyzeSections.kt:22-47`, 호출 `RequirementAnalysisPipeline.kt:345-350` |
| 원문 SR | 폴백(정제문이 비었을 때)과 호출 인자(rawInput)로만 | `AnalyzeInputResolver.kt:60-62`, 라우터 `executeAnalyzePipeline` 인자 |

판정: B-64 3-b는 **맞다**. 단 보태야 할 사실: (1) `ClarifyIntent.constraints`는 어느 단계도 읽지 않으므로 사용자의 발화는 **정제문 문장과 excludedFiles로만** 분석에 전달된다. 정제문이 조건을 빠뜨리거나 바꾸면(D2, SR-07) 그대로 분석 입력이 된다. (2) `stage0Contract.enrichedRequirementText`가 우선하지만 clarify→analyze 경로에서 stage0Contract가 채워지는지는 이번에 확인하지 않았다(미확인).

#### 1-e 산출물 형식 초안 (구현 안 함)

"현재 동작"은 그래프/코드 사실이 있을 때만 쓰고 출처를 적는다. 이번 작업은 그래프를 읽지 않았으므로 아래 "현재 동작"은 모두 **미확인**이다(B-64 기록에 있는 사실은 SR-05의 "조직 목록 관련 파일 81개가 후보"뿐이며 동작 정보가 아니다).

**SR-05**
| 현재 동작 | 바뀐 뒤 동작 | 범위 밖 |
|---|---|---|
| 미확인(조직 목록 조회가 상태 조건 없이 전부 돌려주는지 코드 확인 필요) | 조직 목록에는 활성 상태(운영 중)인 조직만 보인다. 대기 중인 조직은 보이지 않는다. (SR "운영 중인 조직만", 스크립트 "활성 상태인 조직만요. 대기 중인 조직도 숨겨 주세요") | 관리자 화면의 조직 목록은 지금 그대로(SR "그대로 두세요") |
미결: "조직 목록"이 일반 화면인지 API인지(정제문이 "일반 조직 목록"을 SR에 없이 더했다).

**SR-12**
| 현재 동작 | 바뀐 뒤 동작 | 범위 밖 |
|---|---|---|
| 미확인(앱이 "최신 버전"을 어떻게 판단하는지) | 업데이트 안내 판단에서 최신 버전은 같은 OS 안에서, 스토어별·OS별로 따로, 노출 대상으로 표시된 버전 중 버전 번호가 가장 높은 것이다 | 관리 화면(스크립트 "관리 화면은 그대로 두세요") |

**D4 (모호)**
| 현재 동작 | 바뀐 뒤 동작 | 범위 밖 |
|---|---|---|
| 미확인 | 미정(SR에 "케어회원 관리 화면 조회"뿐) | 미정 |
D4에서 새 형식이 만드는 질문(5개 상한, 구현 방법·파일은 묻지 않음, 추천 답 포함): (1) 조회 대상 항목은? 추천: 목록(이름·상태·가입일)+상세 (2) 검색 조건은? 추천: 이름/회원번호 (3) 누가 볼 수 있나? 추천: 기존 관리자 권한 그대로 (4) 새 화면인가, 기존 화면 수정인가? 선택지 2개 (5) 결과가 없으면? 추천: "조회 결과가 없습니다" 표시. 현재 clarify는 D4에서 질문 0개다.

"범위 밖" 칸이 미확정 제외 안내를 대체할 수 있는가: **부분적으로 그렇다.** 대체되는 것: 안내가 맞았던 40건은 업무 언어 "범위 밖" 문장이 같은 정보를 준다(D5 "알림톡 배치", S3 "로그인 컨트롤러", SR-12 "관리 화면", SR-05 "관리자 화면 목록"). 대체되지 않는 것: 파일 단위 제외 반영은 여전히 다음 단계(파일 선택)가 "범위 밖" 문장을 파일로 옮겨야 한다. B-64 3-a의 오인·판단 불가 사례 위치: SR-05 "대기 중인 조직 숨김" → **바뀐 뒤 동작**(데이터 필터링이라 범위 밖이 아니다, 오인 3건이 사라짐). SR-07 "게시하지 않은 FAQ 숨김" → **바뀐 뒤 동작**("다시 보는 버튼은 없어도 돼요"는 선택 사항으로 별도 표기, 오인 4건이 사라짐). SR-01 "잠금 해제 방식은 지금 그대로" → **범위 밖**(판단 불가 4건이 해소). SR-11 "삭제 전 확인창은 화면 쪽에서 합니다" → **범위 밖**(판단 불가 3건이 해소).

삽입 지점:
| 지점 | 위치 | 내용 |
|---|---|---|
| 정제문 생성 | `Stage0ClarificationEngine.refineRequirement` :1471, `buildClarifyIntentChecked` :1344 | 정제문과 함께(또는 대신) 3열 목록을 생성 |
| Stage 1 입력 | `AnalyzeInputResolver.kt:58-72` → `RequirementAnalysisPipeline.kt:135-136`, :228-229 | "바뀐 뒤 동작" 문장을 발견 입력으로, "범위 밖"은 사전 제외 힌트로 |
| Stage 3 입력 | `AnalyzeSections.kt:22-47`, 호출 `RequirementAnalysisPipeline.kt:345-350` | 3열 목록을 본문으로 |

저장 형식과 ADR-001: `ClarifyIntent`에 3열 필드를 더하고 `contractVersion`을 올리면 된다(저장 DTO `ClarifyIntentStore.kt:156/:228`도 필드 추가). ADR-001(디스크의 intent/contract를 자동으로 읽지 않음, 새 세션은 `previousContract = null`)은 필드 추가만으로는 영향이 없고 새 필드도 자동 로드하지 않는 한 위반이 아니다. `/analyze`가 `/clarify` 없이도 도는 불변식은 3열이 없을 때 지금의 정제문/원문 경로로 돌아가면 유지된다. 지금 읽는 곳이 없는 `constraints`는 "범위 밖"이 첫 소비자가 되는 셈이다.

#### 1-f 영향 (1-e를 채택한다고 가정)

| 항목 | 판정 | 이유 한 줄 |
|---|---|---|
| D안(최종 제약 추출을 임계 경로에서 빼기) | 형태가 바뀜 | 추출이 3열 생성으로 바뀌고 읽는 곳이 생기며, 병렬/뒤로 빼는 설계 자체는 그대로 적용 가능하다 |
| 버려지는 요약 취소 | 형태가 바뀜 | 요약을 3열 갱신 호출로 대체하면 취소 대상이 그 호출이 된다(요약을 유지하면 그대로 필요) |
| SR-05X 안내 모순 | 불필요 | 미확정 제외 안내가 "범위 밖" 문장으로 대체되면 낡은 안내가 사라진다(파일 반영 단계는 이미 제외된 파일과 중복 제거 필요) |
| 최종 제약 추출 호출 | 형태가 바뀜 | `constraints`를 쓰는 곳이 없으므로 같은 호출이 3열(범위 밖·바뀐 뒤)을 내는 호출로 바뀌어야 의미가 생긴다 |

#### 예상과 다른 점
1. `ClarifyIntent.constraints`를 읽는 곳이 없다 — 최종 제약 추출(호출 1회, 약 28초 임계 경로 포함)의 결과가 분석에 전달되지 않는다. 사용자의 말은 정제문과 excludedFiles로만 전달된다.
2. 엔진 질문 10개 중 6개가 파일 범위 질문이고 expectedQuestions 적중은 0/5다. 생성형 질문 규칙은 `브랜드/api/연동` 키워드 템플릿 하나뿐이다.
3. 정제문은 스크립트 조건을 대부분 반영한다(28/30). 부족한 쪽은 질문이다. 다만 정제문이 뜻을 바꾼 2건(D2 참조→추가, SR-07 선택→금지)이 그대로 분석 입력이 된다.
4. holdout 4개와 SR-06은 실행 기록이 없어 1-c의 실제 질문 열이 비어 있다. 판정은 SR 원문과 스크립트에 대한 사람 판단이며 GT와 대조하지 않았다.

### [B-66] clarify 분리를 위한 출력 계약 초안과 분리 지도, 정제문 확인 경로, "현재 동작" 그래프 검증 (2026-10-26)

읽기만 했다(코드, 웹뷰, 프롬프트, LLM, /tokenize, 외부 접속 없음). 사전 확인: `git status -s` 비어 있음, HEAD = origin = 53c4ea9. 그래프/소스는 로컬 평가 프로젝트 파일을 읽기만 했고, 이 문서에는 파일명과 메서드명만 적었다(코드 본문 없음).

#### 1-a 정제문 확인 경로

- **정제문을 사용자가 보는 곳: 없음.** 정제문(`refineRequirement` 결과)은 확정 뒤 `buildClarifyIntentChecked`에서 만들어져(`WebviewActionRouter.kt:264`, 후보 :322) 곧바로 `executeAnalyzePipeline`(:277, :335)으로 간다. 화면 메시지에 `refinedRequirement`를 싣는 곳이 라우터와 웹뷰에 없다(`webview/src`에 `refined` 0건, `정제` 0건). 화면에 보이는 것은 작업 요약(`App.tsx:455-470`의 `taskSummary`)인데 이것은 후보 범위 요약 호출(`generateTaskSummary`)의 결과이며 정제문이 아니다. 정제문은 디스크 기록(`ClarifyIntentStore.saveIntent`, 라우터 :270, :328, "기록만")에만 남는다.
- **사용자가 고치는 곳: 없음.** 확정 버튼(`App.tsx:336-357` `handleSendResponse`)이 보내는 것은 `verdictUpdates`(후보 체크 상태)와 발화뿐이다. 정제문 편집 입력은 없다.
- **`stage0Contract.enrichedRequirementText`는 clarify→analyze 경로에서 채워지지 않는다.** 채우는 곳은 `transitionToStage1`(`Stage0ClarificationEngine.kt:1152-1215`, 필드 :1202)뿐인데 `src/main`에 호출자가 없다. 라우터의 `pipeline.analyze(...)` 호출(`WebviewActionRouter.kt:1570-1577`)은 `stage0Contract`를 넘기지 않아 기본값 null이다. 그래서 `RequirementAnalysisPipeline.kt:135-136`은 `primaryReq`(=정제문+식별자 줄)로, :345-350은 `input.rawRefined`로 떨어진다. (B-65에서 "미확인"이라 쓴 항목의 확인 결과.)
- **`refineRequirement` 프롬프트 원문**(`Stage0ClarificationEngine.kt:1471` 부근, 고치지 않음):
  - 첫 지시: "최초 요구사항과 대화 중 사용자가 제공한 추가 발화/답변을 종합하여, 명확하고 구체화된 단일 요구사항 정제문(1~2문장)을 작성하세요."
  - 규율 1: "원 요구사항의 핵심 목표와 사용자의 세부 의도/제약사항을 자연스럽게 결합하세요."
  - 규율 2: "사용자가 언급한 특정 클래스/파일명(…) 및 식별자는 누락 없이 온전히 포함하세요."
  - 규율 3: 배타적 한정 표현('~만 수정')은 "흐리거나 일반화하지 말고 … '만'을 분명하게 유지하세요."(우선순위 표현 '~위주로'는 왜곡하지 않기)
  - 규율 4: "불필요한 서론/결론/인사말 없이 오직 정제된 요구사항 본문만 출력하세요."
  - 의미 변경 유도 가능 지시문: **"명확하고 구체화된", "1~2문장", "자연스럽게 결합"**. 이 세 표현은 문장을 다시 쓰고 합치도록 요구하며, 보존 규칙은 식별자(규율 2)와 '만'(규율 3) 둘뿐이다. SR-07의 "없어도 돼요"(선택)는 어떤 보존 규칙에도 해당하지 않고(단정형으로 쓰도록 압축되기 쉽다), D2의 "참조"는 규율 2가 식별자만 지키고 식별자 사이의 관계 표현은 지키지 않는다. 이것은 지시문과 결과의 대응 관찰이며 인과를 확인한 것은 아니다.
- **조건 수준으로 갈린 6개 시나리오: 회차별 정제문 조건과 Stage 3 결과**(`live_eval_*.json`의 `rawRefined`, `targets`, `gtHit30/gtTotal`; 파일은 날짜별이고 **코드 버전이 서로 달라 회차 간 비교가 섞인다**). 조건 O/X는 정제문에 해당 조건이 있는가.

| 시나리오 | 회차(날짜_시각#회) | 정제문 조건 | Stage 3 결과(대상 수, GT 적중, 제거된 GT) |
|---|---|---|---|
| D3 | 1020_1105#1 / 1020_1557#1 / 1020_1654#1 / 1022_1600#1 | SAPACMM0802S01 참고: X / X / X / O | 4개 4/5(제거 1) / 5개 5/5 / 5개 5/5 / 5개 5/5 |
| SR-05X | 1020_1752#1 | 대기 숨김 O | 1개 1/2(제거 1) |
| SR-05X | 1021_1310#1, #2, #3 | 대기 숨김 X, O, O | 2개 2/2 ×3 |
| SR-05X | 1022_1401#1~#3, 1022_1604#1 | 대기 숨김 O ×4(운영 중 O, X, X, O) | 2개 2/2 ×4 |
| SR-08 | 1020_1457, 1020_1557, 1020_1654 | 유사 추가: X, O, X | 4개 3/3(오탐 1) / 4개 3/3(오탐 1) / 3개 3/3 |
| SR-08 | 1023_0908#1~#3 | 유사 추가: X, O, X | 3개 / 4개(오탐 1) / 4개(오탐 1), 모두 3/3 |
| SR-08 | 1023_1453#1~#3 | 유사 추가: X ×3 | 3개 3/3 ×3 |
| S3 | 1020_1105 / 1020_1557 / 1020_1654 / 1020_1752 / 1022_1600 | "범위" 문구: X / X / X / O / O | 4개 3/4 / 2개 1/4(제거 2) / 2개 1/4(제거 2) / 3개 2/4(제거 2) / 4개 3/4 |
| SR-01 | 1020_1105 / 1020_1557 / 1020_1654 / 1022_1600 | "문구만": X / X / X / O | 2개 1/2 / 1개 1/2 / 2개 1/2 / 2개 1/2 (모두 같은 GT 1건 누락) |
| SR-05 | 1020_1105~1022_1600 8회 | "일반 조직 목록" O ×8, "대기 숨김" O ×8, "운영 중" O ×2(앞 2회)·X ×6 | 대상 2~3개, 2/2 ×7, 1/2 ×1(1020_1752, 제거 1) |

읽는 법: (1) **같은 날(같은 코드) 같은 정제문 조건에서도 Stage 3 결과가 달라진다**(SR-05 1021_1101 #1~#3: 조건 동일, 대상 2/2/3개. SR-08 1023_0908: 조건 X, O, X에서 대상 3, 4, 4개로 X에서도 4개가 나옴). (2) 같은 날 조건이 갈린 묶음(SR-05X 1021_1310의 X, O, O; SR-08 1023_0908)에서 정제문 조건 차이와 Stage 3 결과 차이는 대응하지 않았다. (3) 날짜가 다른 회차의 차이(D3, S3)는 코드 변경(B-48~B-54)과 섞여 정제문 효과로 분리할 수 없다. 결론: 이 자료로는 "정제문의 조건 차이가 Stage 3 결과를 바꾼다"를 확인하지도 부정하지도 못한다(같은 조건에서도 결과가 갈리는 변동이 있음은 확인됨). 정정: B-65에서 SR-05의 "일반 조직 목록"을 "보이는 3개 모두"라고 썼는데 8회 모두에 있었다.

#### 1-b "현재 동작" 그래프 검증 (그래프 JSON + 소스만 읽음)

**SR-05** (graphKey member_market, `member-market/.meta/project-graph.json`, 소스 `member-market-api/src/main/java/com/membermarket/` 기준 상대 경로)
| 현재 동작(문장) | 근거 |
|---|---|
| 사용자용 조직 목록은 `GET /api/organizations`이고 `type` 파라미터가 있으면 유형으로 거르고 없으면 전부 돌려준다. **상태(status) 조건은 없다.** | 그래프 apiEndpoints(`OrganizationController`), `api/organization/OrganizationController.java:18-23`, `api/organization/OrganizationService.java:23-36`(`findByType` 또는 `findAll`) |
| 조직 상태는 ACTIVE, INACTIVE, PENDING이고 새 조직의 기본값은 ACTIVE다. "운영 중"이라는 값은 없고 스크립트의 "활성"에 대응하는 것은 ACTIVE다. | `domain/organization/OrganizationStatus.java`, `domain/organization/Organization.java:46` |
| 상태로 거르는 저장소 메서드 `findByStatus`는 선언만 있고 호출하는 곳이 없다. | `domain/organization/OrganizationRepository.java:14`, 저장소 전체 grep 결과 선언 1건 |
| 관리자 목록은 `GET /api/admin/organizations`이며 마스터는 전부(`findAll(pageable)`), 기관 관리자는 자기 기관 1건이다. 상태 조건 없음(대기 조직도 보인다). | `api/admin/AdminService.java:99-107`, 그래프 apiEndpoints(`AdminController`) |
| 관리자 화면 두 곳이 같은 관리자 목록 API를 쓴다. | `member-market-web/src/views/admin/AdminOrganizationListView.vue:75`, `AdminAccountListView.vue:193` |
| 사용자용 목록 `/api/organizations`를 부르는 화면은 웹 소스에서 찾지 못했다. | `member-market-web/src` grep 0건 → **미확인**(호출처) |

질문 예: "현재 사용자용 조직 목록(`GET /api/organizations`)은 대기 중인 조직도 모두 돌려줍니다. 바뀐 뒤에는 활성 상태만 보이게 하고, 관리자 목록(`/api/admin/organizations`, 관리자 화면 두 곳이 사용)은 대기 조직도 계속 보이게 둡니까?" 이 사실은 정제문이 SR에 없던 "일반 조직 목록"을 더한 것이 코드의 두 목록 구분과 맞는다는 근거도 된다(구분은 SR이 아니라 코드에서 나왔다). 조회 단계 **5**(그래프 검색 1, 서비스 노드 조회 1, 소스 읽기 1, 소스 읽기+enum/entity 1, grep 1: `findByStatus`와 웹 호출처). 그래프만으로 얻은 것: 서비스의 `dependsOn`에 `OrganizationType`은 있고 `OrganizationStatus`는 없어 "상태 미사용"이 간접적으로 보이나 확정은 소스 읽기가 필요했다.

**SR-12** (graphKey apc, `graph/project-graph-a/project-graph.json`; **소스 폴더 `C:/scrad/workspace/apc-pjt`가 비어 있어 소스는 못 읽음**, 그래프의 한글 주석만 사용)
| 현재 동작(문장) | 근거 |
|---|---|
| 서비스 쪽 최신 앱버전 판단은 사용자 앱정보(모바일OS구분코드, 앱스토어구분코드, 모바일앱버전명)로 "최신앱버전 정보"를 조회한다. 즉 OS와 스토어를 이미 입력으로 받는다. | 그래프 노드 `APCCSAppVerMngtBIZ`(메서드 `selAppVerUpdtInf`, 한글 주석 "사용자 앱정보로 최신앱버전 정보조회", "모바일OS구분코드", "앱스토어구분코드", "모바일앱버전명") |
| 최신 버전 조회는 `ACAOTBAPC003DEM.selNewSappVer`, 중간 버전은 `selMidlSappVer`다. 주석상 중간 버전 쿼리는 최신 쿼리와 비슷하되 노출 여부(`SPACD_DLSTM_USE_YN='Y'`)를 조건으로 쓴다고 되어 있어, 최신 쿼리는 이 조건을 쓰지 않는 것으로 읽힌다(확정 아님). | `ACAOTBAPC003DEM` 메서드 목록, `APCCSAppVerMngtBIZ` 한글 주석 → **SQL 본문 미확인** |
| 최종앱버전이 중간앱버전보다 크고 중간앱버전의 업데이트 유형이 강제(3)나 강제_결제구동 제외(4)이면 최신앱버전의 업데이트 유형과 메시지를 중간앱버전 내용으로 바꾼다. | `APCCSAppVerMngtBIZ` 한글 주석 |
| 관리 화면 쪽에도 "최신 앱버전 조회", "최신중간 앱버전 조회"가 있다(`selLtSappVer`, `selLtMidlSappVer`). 서비스 쪽과 같은 쿼리를 쓰는지는 모른다. | `APCADUpdtVerMngtBIZ` 메서드와 한글 주석, 화면 `webapps/js/ad/appVerMngt/UAPCAD0301M0.js` → 쿼리 공유 **미확인** |
| "버전 번호가 가장 큰 것을 최신으로 본다"는 현재 규칙인지 | **미확인**(정렬 조건은 SQL 안) |

질문 예: "현재 앱 업데이트 안내는 사용자 앱의 OS·스토어·버전으로 최신 버전을 조회하고, 중간 버전이 강제 유형이면 그 내용으로 덮어씁니다(`APCCSAppVerMngtBIZ.selAppVerUpdtInf`). 바뀐 뒤에도 중간 버전 덮어쓰기는 유지합니까?" 조회 단계 **4**(그래프 검색 1, 노드 조회 3: BIZ, DEM, 관리 BIZ) + 소스 읽기 시도 2회 실패.

**D4** (graphKey ism, `graph/project-graph-i/project-graph.json`; **소스 폴더 `ism-pjt/ism-api-bo`가 비어 있어 소스 못 읽음**)
| 현재 동작(문장) | 근거 |
|---|---|
| 케어회원 목록, 개수, 상세 조회와 저장, 수정 API가 이미 있다: `GET /me/careMember/getCareMemberMgmtList`, `getCareMemberMgmtListCount`, `getCareMemberInfo`, `PUT saveCareMember`, `updateCareMember`. | 그래프 apiEndpoints(`CareMemberMgmtController`) |
| 목록 조회 쿼리는 `ECMBTBISM006Mapper.xml`의 `getCareMemberMgmtList`이고 테이블 `ECBCTBISM001`, `ECBCTBISM002`, `ECMBTBISM001`, `ECMBTBISM007`, `ECMBTBISM006`를 조인하며 조건절은 `getCareMemberMgmtListWhere`를 포함한다. | 그래프 resourceNode(MYBATIS_MAPPER) `sql_id`, `table_name`, `include_ref` → **조건 내용 미확인** |
| 요청/응답은 유의고객 등록·해제 정보(등록일자, 해제일자, 사유, 고객명 등)를 다룬다. | `CareMemberMgmtRequest/Response`의 한글 주석 |
| "관리 화면"에 해당하는 화면 파일은 그래프에 없다(이 그래프는 API 모듈이다). | resourceNodes 중 화면 0건 → **미확인** |

질문 예: "케어회원 목록·상세 조회 API(`/me/careMember/getCareMemberMgmtList`)가 이미 있습니다. 새로 만들 것은 화면입니까, 아니면 기존 API의 조회 조건이나 항목을 바꾸는 것입니까?" 조회 단계 **3**(그래프 검색 1, 컨트롤러 노드 조회 1, 매퍼 노드 조회 1) + 소스 읽기 시도 1회 실패.

도구 설계 근거: 세 건 모두 **노드 검색 → 노드 상세(메서드, 한글 주석, 엔드포인트) → 리소스 노드(SQL id, 테이블)** 순서가 쓰였고, 소스 본문이 필요한 것은 SR-05 하나뿐이었다(SR-12, D4는 소스가 없어 SQL 본문이 "미확인"으로 남았다). 소스가 없는 환경에서도 그래프의 `sql_id`, 테이블, 한글 주석까지는 얻을 수 있고 그 밖은 "미확인"이 된다.

#### 1-c 분리 지도

`Stage0ClarificationEngine.kt`(1,636줄). 줄 수는 선언 시작 줄 간 간격이며 주석과 중첩 함수를 포함한 근사값이다. 분류: 구(구체화), 파(파일 범위 조작), 공(공용).

| 함수(시작 줄, 줄 수) | 분류 | 호출하는 것 → |
|---|---|---|
| 클래스 머리와 데이터 클래스 (17-67, ~50) | 공 | - |
| `buildTaskSummary` :28 (6), `generateTaskSummary` :1019 (61) | 구 | 번역/요약 프롬프트(후보 목록 사용) |
| `checkOpenQuestionTrigger` :1126 (26) | 구(그래프 사실 사용 :1137) | - |
| `refineRequirement` :1471 (43) | 구 | LLM |
| `extractConstraintsChecked` :1527 (~110), `ConstraintExtraction`, `constraintFailureLine` | 구 | LLM |
| `buildClarifyIntent` :1339 (3), `buildClarifyIntentChecked` :1344 (114) | 공(정제문+제약은 구, excludedFiles·미확정 제외는 파) | `refineRequirement` :1350/:1360, `extractConstraintsChecked` :1354, `resolveExclusionCandidates` :1380, `unconfirmedFromPendingProposals` :1392 |
| `initSession` :267 (144) | 공 | 추출 :336, `resolveUserUtteredIdentifiers` :338, `scanner.rescanUnverified` :345, `proposeExclusionsForUtterance` :358, `checkOpenQuestionTrigger` :361, 요약 :396 |
| `processTurn` :411 (144, 구형 턴) | 공 | `applyPendingExclusionAnswer` :440, 추출 :443, `proposeExclusionsLegacy` :446, `resolveUserUtteredIdentifiers` :452, `rescanUnverified` :509, `mergeCandidates` :512, 질문 :516, 요약 :540 |
| `processUtterance` :1216 (123) | 공 | `applyPendingExclusionAnswer` :1222, `rescanUnverified` :1263, `mergeCandidates` :1264, `explicitExclusionMentions` :1283, `excludeMentionedFiles` :1286, 추출 :1290, `proposeExclusionsForUtterance` :1292, 질문 :1320, 요약 :1321 |
| `resolveUserUtteredIdentifiers` :68 (199) | 파 | 그래프 파일 찾기 :105, :140, 신규 생성 질문 :192 |
| `resolveExclusionCandidates` :568 (86, `findFilesForWord` :593 포함) | 파 | 그래프 :595 |
| `applyPendingExclusionAnswer` :663 (73) | 파 | `ExclusionWords.judgeAnswer` :668 |
| `buildProposedExclusionItem` :736 (19), `upsertProposedExclusion` :755 (13) | 파 | - |
| `proposeExclusionsLegacy` :768 (22) | 파 | `resolveExclusionCandidates` :772 |
| `explicitFileMentions` :821 (35), `explicitExclusionMentions` :856 (29), `excludeMentionedFiles` :885 (35) | 파 | `ExclusionWords.containsExclusionWord` :857, :863 |
| `proposeExclusionsForUtterance` :929 (60) | 파 | `explicitFileMentions` :946, `excludeMentionedFiles` :948, `resolveExclusionCandidates` :952/:955 |
| `unconfirmedFromPendingProposals` :989 (20) | 파 | - |
| `findExclusionCandidates` :1009 (10) | 파(`src/main` 호출자 없음) | `resolveExclusionCandidates` :1010 |
| `mergeCandidates` :1080 (46) | 파 | - |
| `transitionToStage1` :1152 (64) | 파(`src/main` 호출자 없음) | - |
| `exclusionView` :1460 (11) | 파 | - |

줄 수 합계(근사): 구 ~265, 파 ~790, 공 ~575(나머지는 import와 주석). 구체화 함수는 파일 범위 함수를 직접 부르지 않는다(공용 함수 세 개와 `buildClarifyIntentChecked`가 둘을 이어 준다). 엔진 밖 호출자는 `WebviewActionRouter.kt`의 `initSession` :153, `processTurn` :237, `processUtterance` :294, `exclusionView` :173-174/:252-253/:308-309뿐이다.

**clarify 밖으로 옮길 것과 받을 곳**(결정성 표시: ●=모델 판단 밖에 남음, ○=모델 판단 포함)
| 옮길 것 | 지금 위치 | 받을 곳 | 결정성 |
|---|---|---|---|
| 스캐너 후보(토큰 추출, 재스캔, 후보 병합) | `Stage0ClarificationEngine.kt:271/:448/:1258`(`extractTokens`), :345/:509/:1263(`rescanUnverified`), :1080-1125(`mergeCandidates`), `Stage0GraphScanner.kt` 전체 | Stage 1 앞: `RequirementAnalysisPipeline.kt:221-231`(사전 제외)와 :228(`AdaptiveFileDiscovery.filter`) 사이. 시드 선정(`AgenticSeedSelector`)이 이미 자체 탐색을 하므로 계약에는 후보 목록을 싣지 않는다 | ● 규칙 기반 |
| 제외 후보 탐색 | `resolveExclusionCandidates` :568-653, `proposeExclusionsForUtterance` :929-988, `proposeExclusionsLegacy` :768, `applyPendingExclusionAnswer` :663, `buildProposedExclusionItem` :736, `upsertProposedExclusion` :755, `findExclusionCandidates` :1009(미사용) | `RequirementAnalysisPipeline.kt:295-298`(제외 경로 집합) 앞의 입력 준비 단계, 카드는 `WebviewActionRouter.kt:173-174/:252-253/:308-309`가 쓰는 `exclusionView` 대응물 | ● 키워드와 그래프 이름 매칭(번역기가 EXCLUDE로 분류한 발화만 ○) |
| 파일명 직접 제외 규칙(B-53, B-54) | `explicitFileMentions` :821, `explicitExclusionMentions` :856, `excludeMentionedFiles` :885, `ExclusionWords.kt:17-36`(`containsExclusionWord` :20, `judgeAnswer` :36), 호출 :946-948, :1283-1292 | 같은 사전 제외 단계(`RequirementAnalysisPipeline.kt:295-298`). 입력은 계약의 "범위 밖" 원문 문장 | ● 정규식과 이름 매칭. 단 :1288의 분기는 번역기 분류(○)와 `ExclusionWords` 검사(●)가 섞여 있어 분리 시 둘을 나눠야 함 |
| `excludedFiles` | 만드는 곳 `buildClarifyIntentChecked` :1344-1457, `exclusionView` :1460 | 읽는 곳은 이미 분석 쪽: `AnalyzeInputResolver.kt`(`isExcludedIdentifier`), `RequirementAnalysisPipeline.kt:298`, :558-561. 계약 필드 "파일명 직접 제외"로 유지 | ● |
| 미확정 제외 안내 | 만드는 곳 :1392(`unconfirmedExclusions`), `unconfirmedFromPendingProposals` :989; 보이는 곳 `RequirementAnalysisPipeline.kt:430`(`exclusionSummaryLines`) | "범위 밖" 필드 표시로 대체(B-65 1-e). 파일 반영은 사전 제외 단계 | ○ 제약 추출 결과를 쓰는 부분, ● 이름 매칭 부분 |

모델 판단 밖에 남는 결정적 규칙: B-53/B-54 파일명 제외(위 표), `ExclusionWords` 정규식과 응답 판정(`judgeAnswer` :36), 세션 리셋(`AnalyzeSessionManager.kt:41/:52`, 라우터가 확정 시 `removeSession` 호출, 새 세션은 `previousContract = null`, ADR-001), 저장(`ClarifyIntentStore.saveIntent` :60, "기록만", `findIntentFile`/`loadIntentByKey` 호출자 0), 후보 상한(`CandidateCap`, `ClarifyUtteranceTranslator.kt:14`). 모델 판단: 번역기 분류, 제약 추출, 정제문, 요약, (새로 생길) 질문과 현재 동작 서술.

**하네스와 기존 테스트가 clarify 내부에 의존하는 곳**
- `LiveEvalTest.kt`: `Stage0GraphScanner(graph, minSpecificityScore = 1.0, proposalBudget = 10)` :130, `Stage0ClarificationEngine(scanner, graph, metered, deferTaskSummary = …)` :131, `summaryScheduler` :134-137/:192-193, `initSession` :140, `processUtterance` :151, `exclusionView` :160-161, `processTurn`(확정 시뮬레이션 `isCompletionDeclared = true`) :197, `buildClarifyIntent` :201, `AnalyzeInputResolver.resolve` :220, 결과 필드 `rawRefined`, `engineQuestions`, `UtteranceLog`(echo/openQuestion/rejected 수/seed 수). 즉 엔진 생성자 인자, 턴 결과 필드(`openQuestion`, `echoBackMessage`, `isReadyForStage1`, `state.items`, `state.seedSet`), 요약 스케줄러에 의존한다.
- `Stage0EngineRoutedBaselineHarnessTest.kt`: `Stage0ClarificationEngine(scanner, graph, llmClient = null | mockLlm)` :256/:311/:368/:419, `initSession` :259, `processTurn` :314, `buildClarifyIntent` :260/:320/:372, `AnalyzeInputResolver.resolve` :262/:322/:374. 이 테스트의 콘솔 출력이 베이스라인 비교 도구(저장소 밖 `$TEMP/bl/a55_accept.out` 계열)의 **EXACT_MATCH 5종**(survey_admin LlmOff, APC Transit Card LlmOff, ISM Core LlmOff, Ablation-a, Ablation-b)과 **MATCH_EXCLUDING 2종**(survey_admin LlmOn, APC DEM Isolated LlmOn, 접두 `RefinedReq:` 한 줄 제외)의 입력이다. LLM을 쓰지 않는 5종은 분석 입력 줄이 한 글자도 바뀌면 안 되므로 `AnalyzeInputResolver`/`buildClarifyIntent`의 출력 형식이 계약이 된다. LLM을 쓰는 2종은 정제문 줄만 제외하고 비교한다(정제문이 모델 산출물이기 때문).
- `clarify` 테스트 패키지가 엔진/스캐너를 직접 생성하는 파일 22개: `AnalyzeSessionManagerTest`, `CandidateCapTest`, `ClarificationContractStoreTest`, `ConstraintExtractionFailureNoticeTest`, `ExcludeComponentDialogueTest`, `ExcludedIdentifierSubtractionTest`, `ExclusionDisplayTest`, `ExclusionFileNameRuleTest`, `ExclusionProposalFlowTest`, `FinalConstraintFailureNoticeTest`, `ParallelRefineTest`, `Stage0B12ReAskLiveLlmObservationTest`, `Stage0EngineRoutedBaselineHarnessTest`, `Stage0GraphScannerTest`, `Stage0RouterAndContractTest`, `Stage0SurveyAdminLiveLlmVerificationTest`, `Stage0SurveyAdminSnapshotTest`, `Stage0ThreeScenarioLiveLlmObservationTest`, `Stage0ThreeScenarioObservationTest`, `Stage0UserIdentifierDisambiguationTest`, `Stage0UtteranceProcessTest`, `TaskSummaryDeferTest` + `agent/integration/ClarifyToAnalyzeIntegrationBaselineTest.kt`(:208-209) + `agent/eval/LiveEvalTest.kt`. 분리하면 이 중 파일 범위/제외 관련(`Exclusion*`, `ExcludeComponentDialogueTest`, `ExcludedIdentifierSubtractionTest`, `Stage0UserIdentifierDisambiguationTest`, `Stage0GraphScannerTest`)은 새 단계 쪽으로 옮겨야 하고, 구체화 쪽(`ParallelRefineTest`, `*FailureNotice*`, `CandidateCapTest`, `TaskSummaryDeferTest`)은 그대로 남는다.

#### 1-d 에이전트 루프 전제

도구 호출(tool calling)을 쓰는 곳:
| 곳 | 위치 | 요청 형식 | 실패·잘못된 호출 처리 |
|---|---|---|---|
| 시드 선정(다중 턴) | `AgenticSeedSelector.kt:22-` (`maxTurns = 10` :71, 호출 :83-91), 도구 정의 `DEFINITIONS` :414-(`search_graph_nodes` :418, `inspect_node_detail` :439, `expand_connected_nodes` :456, `confirm_final_seeds` :482) | `chatWithTools(maxTokens = 2048, tools, toolChoice = "auto", temperature = 0.0)` | 호출 예외 → 루프 종료(:91-94) → 가드 2 누적 상위 3개 채택(:310-327) → `fallbackSelection` 키워드 매칭(:329-333, :371). 도구 호출 없이 끝나면 종료(:112-116). 알 수 없는 도구 이름은 오류 JSON 반환(:211-213). 중복 검색어는 경고 반환(가드 1). 인자 JSON은 `gson.fromJson` 직접 해석(:~133)이라 해석 실패 예외가 루프 밖으로 번질 수 있음(B-34에서 D4 절단 때 확인된 위치) |
| Stage 3 검증 | `FileRelevanceVerifier.kt:220-221` (`submit_verification` 강제 선택 `toolChoice = {type: function, function.name}`) | 단일 호출, 도구 강제 | 예외 :223, :322, :362, 유효한 도구 호출이 없으면 "keeping all files as fallback"(:330) |
| 신규 파일 탐지 | `NewFileDetector.kt:146-147` (`propose_n…` 강제) | 단일 호출, 도구 강제 | 예외 :165 |
| 시드 선정(1회형) | `LlmSeedSelector.kt:246-247` (`submit_se…` 강제) | 단일 호출 | 예외 :261/:278, `fallbackSelection` :282/:285, JSON 배열 폴백 :272 |
| 후보 선택(다중 턴) | `LlmCandidateSelector.kt:99-100`(`search_files`, `get_file_summary`, `get_dependencies`, 마지막 턴은 도구 없음) | 도구 `auto` | `fallbackToStage1` :106/:116/:152/:174/:198, 예외 :178 → `fallbackToLegacyPrompting` :187/:201 |
| 클라이언트 | `OpenAIClient.kt:297-315`(`chatWithTools`), 요청 본문 :340-355(`tools`, `tool_choice`, 스트림), `OllamaClient.kt:253-321` | - | 스트림 절단(도구 인자 JSON 해석 불가) 감지 시 1회 재시도, 재시도도 절단이면 실패 계수 증가(:305-313) |

기존 덤프 1,760개(`build/eval-reports/dumps*`)에서 센 도구 호출: 시드 선정 655회 중 인자 해석 성공 654, 해석 불가 1(D4 스트림 절단, `finish_reason` 없음, 인자 728자 — B-34의 현상). Stage 3 검증 143/143, 신규 파일 탐지 57/57 성공. 시드 선정 턴의 구성: 한 턴에 도구 호출이 여러 개(병렬)인 턴 436, 단일 inspect 55, 단일 search 12, expand 8, 최종 확정(`confirm_final_seeds`) 143. 덤프에는 도구 이름과 잘못된 이름 호출 여부가 남지 않아 "알 수 없는 도구" 횟수는 **미확인**. 스트림 덤프에는 완료 토큰이 기록되지 않아 시간으로만 비교했다(시드 선정 턴 시간 중앙: 병렬 호출 턴 16.5초, inspect 14.9초, search 9.0초, expand 19.3초, 확정 52.6초).

도구 후보(3개 이하, 1-b에서 실제로 쓴 조회 기준):
1. `search_graph_nodes(query)` — 기존(`AgenticSeedSelector.kt:418`, 구현 :514). 세 건 모두 첫 단계.
2. `inspect_node_detail(node)` — 기존(:439, 구현 :650). 메서드, 한글 주석, 엔드포인트, `dependsOn/dependedBy`. 리소스 노드(`sql_id`, 테이블, `include_ref`)까지 돌려주도록 확장이 필요한지는 현재 출력에 포함되는지 **미확인**(1-b에서는 JSON을 직접 읽어 얻음).
3. `read_method_source(path, method)` — 신규. SR-05에서만 필요했고(서비스 본문), 소스가 없는 환경(SR-12, D4)에서는 "미확인"을 돌려주도록 해야 한다.

질문 1개당 예상 시간(기존 출력 토큰 ÷ 8 tok/s, `clarify` 덤프 중앙값): 번역 응답 103토큰 ≈ 13초, 정제 36토큰 ≈ 5초, 제약 추출 94토큰 ≈ 12초, 요약 153토큰(최대 265) ≈ 19초(최대 33초). 질문 하나에 선택지 2~5개와 추천 답을 쓰면 요약 호출과 비슷한 150~250토큰으로 가정해 **19~31초**. 조회 턴은 위 시드 선정 턴 시간(중앙 15~19초, 한 턴 안 병렬 호출 포함)을 쓰면 **조회 3단계 ≈ 50초**. 따라서 첫 질문까지 약 70~80초, 조회를 한 번만 하고 질문 5개를 연속으로 만들면 질문당 19~31초가 더해져 5개 합 145~205초(사용자 입력 시간 제외). 이 값은 추정이며 질문 생성 호출은 아직 측정하지 않았다.

#### 1-e 출력 계약 초안 (문서만)

읽는 곳이 없는 필드는 만들지 않는다. `ClarifyIntent.CURRENT_INTENT_VERSION = "1.2"`(`model/ClarifyIntent.kt:42`)를 "1.3"으로 올리는 것을 기준으로 적었다(필드 추가, 기본값 있음).

| 필드 | 생산자 | 읽는 곳 | 저장 | contractVersion |
|---|---|---|---|---|
| `currentBehavior` (사실, 근거(노드·메서드·SQL id·파일:라인), 확인/미확인) | 에이전트 루프(도구 조회) | 화면(질문 근거 표시), Stage 1 입력(시드 선정 힌트: 사실에 나온 클래스명 `AgenticSeedSelector.kt:59` 입력), Stage 3 본문(`AnalyzeSections.kt:22-47`) | 세션 메모리 + 기록 저장(`saveIntent` "기록만") | 1.3 추가 |
| `afterBehavior` (바뀐 뒤 동작 문장 목록, 출처: SR/발화/답) | 사용자 답과 SR에서 모델이 구성하고 사용자가 확인 | Stage 1 입력(`RequirementAnalysisPipeline.kt:135-136`), Stage 3 본문, `/implement`(저장 계약 `enrichedRequirementText = effectiveReq`, :578 경유), 화면 | 저장 | 1.3 추가 |
| `outOfScope` (범위 밖 문장 목록, 원문 인용) | 모델 추출(SR, 발화) | 사전 제외 단계(파일명 규칙이 읽음, `RequirementAnalysisPipeline.kt:295-298`), Stage 3 본문, 화면(미확정 제외 안내 대체) | 저장 | 1.3 추가 |
| `openItems` (미정: 질문했으나 답이 없거나 모른다고 한 항목) | 질문 규칙 엔진(질문 5개 상한 뒤 남은 것) | Stage 3 본문(불확실 표시. 지금의 `unresolvedItems` 줄 `AnalyzeSections.kt` 자리), `/implement`(가정으로 명시), 화면 | 저장 | 1.3 추가(`unresolvedItems`와 통합 시 1.3에서 대체) |
| `qaLog` (질문, 선택지, 추천 답, 사용자 답) | 질문 규칙 엔진, 사용자 | 화면(대화 이력 재표시), 평가 하네스(B-65식 판정). 분석 단계의 reader는 없음 → **화면과 평가 도구만 읽으므로 분석 입력에는 싣지 않는다**. `userStatements`를 대체 | 기록 저장(분석 계약에는 제외) | 1.3 추가 |
| `excludedFilesByRule` (파일명 직접 제외: 결정적 규칙 결과 + 미확정 제안) | 결정적 규칙(`explicitFileMentions` 등, 모델 아님) | `RequirementAnalysisPipeline.kt:298`, :558-561, 화면 카드 | 저장(기존 `excludedFiles` 유지) | 변경 없음(기존 필드 이름만 유지) |

만들지 않는 필드: 후보 목록(시드 선정이 자체 탐색), `constraints`(읽는 곳이 없었다. `outOfScope`와 `afterBehavior`가 대체), `anchorTokens`(읽는 곳 없음, B-65 1-d 참고).

**정제문 문자열의 생성**: `refinedRequirement`는 호환용 파생 필드로 남기고, LLM 호출 없이 계약에서 결정적으로 렌더링한다: `afterBehavior`를 순서대로 문장으로 잇고, `outOfScope`를 "단, …은 바꾸지 않는다"로 잇고, `openItems`를 "미정: …"으로 잇는다(`AnalyzeInputResolver.kt:58-72`가 읽는 값). 이렇게 하면 모델이 문장을 다시 쓰는 단계가 없어져 D2("참조"→"추가")와 SR-07("없어도 돼요"→"제공하지 않습니다")류 변경이 생길 자리가 없다(문장은 사용자가 확인한 `afterBehavior` 그대로). 대가: 각 문장을 사용자가 보고 고칠 수 있게 하는 화면이 필요하다(지금은 정제문 확인 경로 없음).
ADR-001과의 관계: 새 필드도 디스크에서 자동으로 읽지 않으면 위반이 아니다(`findIntentFile`, `loadIntentByKey` 호출자 0 유지). `ClarifyIntentStore.kt:29`(`isSupportedVersion`)와 `loadIntent` :88-121이 "1.3"을 받아들이도록 바뀌어야 하며, `/analyze`가 `/clarify` 없이 도는 불변식은 새 필드가 모두 선택(기본값)이면 유지된다.

#### 1-f clarify 평가 기준 고정 (B-65 판정 재현용)

**빈칸 판정**(범주 9개별, 입력은 SR 원문 + 스크립트 발화만, GT와 그래프는 보지 않음)
| 판정 | 정의 | 예 |
|---|---|---|
| 명확 | 구현이나 검증을 바꾸는 결정을 원문에서 하나로 읽을 수 있다 | SR-01 "현재 5회에서 3회로" (기능범위) |
| 부분 | 결정의 일부만 있거나 둘 이상으로 읽힌다 | SR-07 "게시하지 않은"(예약·임시저장 포함 여부), D2 "최근이력 1건"(최근의 기준) |
| 없음 | 그 범주의 결정이 원문에 흔적도 없다 | D4 "케어회원 관리 화면 조회"의 권한, 예외 처리 |
| 판단 불가 | 원문만으로 둘 중 어느 쪽인지 정하기 어렵다. 이 판정은 요약 줄에서 따로 센다 | D3 "SAPACMM0802S01 참고"(기존 서비스 참고인지 신규인지) |
범주에 해당하는 결정이 애초에 필요 없으면 "해당 없음"(세지 않는다).

**물어야 할 질문 인정 규칙**: (1) 답이 구현이나 검증(테스트 기대값)을 바꾼다. (2) 구현 방법, 파일 선택, 클래스 이름은 묻지 않는다. (3) 한 질문에 결정 하나. (4) 상한 5개, 같은 결정은 한 번. (5) 스크립트가 이미 답한 결정은 "물을 것"에는 세고 "스크립트 답"에도 센다(B-65의 44개 중 18개). (6) 원문에 명시된 제약을 확인하려는 질문은 인정하지 않는다.

**반영 판정**(스크립트 조건을 조건 하나씩 쪼개 정제문과 대조)
| 판정 | 정의 | 예 |
|---|---|---|
| 반영 | 조건이 같은 뜻으로 있다(어순, 어미 차이는 무관) | D5 "SurveyServiceImpl만 수정" |
| 일부 | 조건의 일부 요소만 있거나, 같은 뜻이지만 범위가 좁아졌다 | SR-05X 정제문에 "대기 중인 조직 숨김"이 없고 "활성만 표시"만 있음 |
| 누락 | 조건이 정제문에 없다 | D3 정제문1의 "SAPACMM0802S01 참고" |
| 의미 변경 | 조건의 뜻이 달라졌다(선택→금지, 참조→추가 위치) | SR-07 "없어도 돼요"→"제공하지 않습니다", D2 "참조"→"추가" |
추가된 조건(정제문에만 있는 것)은 별도로 "추가" 열에 세고 반영률에는 넣지 않는다(S3 "요청된 범위 내에서만", SR-08 "유사한"). B-65에서는 의미 변경 2건을 "일부"로 묶었고, 이 기준에서는 "의미 변경"으로 분리한다(28 반영 / 일부 0 / 누락 0 / 의미 변경 2, SR-05X 정제문2의 대기 숨김은 회차에 따라 "일부"가 추가됨).

**스크립트 개선안(질문을 받기 전에 답을 넣지 않는 대본, 예시만; 기존 스크립트는 고치지 않음)**: 지금 스크립트는 첫 발화에 답을 한꺼번에 넣어 질문 적중을 볼 수 없다(B-65: 스크립트가 44개 질문 중 18개에 미리 답함). 개선안은 "질문 주제 → 답" 표와 모르겠다 기본 답으로 구성하고, 엔진이 그 주제를 물을 때만 답한다.
| SR | 질문 주제 → 답 | 기본 답(주제가 안 맞을 때) |
|---|---|---|
| SR-08 | "비슷한 단어 기준" → "띄어쓰기를 없앤 뒤 대소문자 구분 없이 같으면 같은 단어" / "수정할 때도 검사하나" → "수정할 때도 똑같이" / "걸리면 막나 경고만 하나" → "등록을 막아 주세요" | "잘 모르겠어요. 지금 방식 그대로면 됩니다." |
| SR-05 | "어떤 상태를 보이게 하나" → "활성 상태만" / "대기 중 조직은" → "숨겨 주세요" / "어느 목록인가" → "사용자가 보는 조직 목록" | 같음 |
| SR-07 | "게시하지 않은 범위" → "임시저장과 예약 상태 모두" / "숨긴 것을 다시 볼 수단" → "없어도 돼요(필요 없음)" | 같음 |
이렇게 하면 "정제문 반영" 판정은 질문과 답이 오간 경우에만 생기고, "물어야 할 질문 적중"은 엔진이 주제를 실제로 물었는지로 측정된다.

#### 예상과 다른 점
1. **정제문을 사용자가 보거나 고치는 경로가 없다**(화면, 라우터, 웹뷰 모두 0). `enrichedRequirementText`는 호출자가 없는 `transitionToStage1`에서만 채워져 clarify→analyze 경로에서는 항상 비어 있다(B-65의 "미확인"을 "채워지지 않음"으로 정정).
2. 정제문 조건 차이와 Stage 3 결과 차이는 이 자료로 분리되지 않는다. 같은 날, 같은 정제문 조건에서도 대상 수가 갈린다(SR-05 2/2/3개, SR-08 3/4/4개).
3. 그래프만으로 "현재 동작"을 채우는 데는 한계가 분명했다: 소스 폴더가 비어 있는 두 프로젝트(apc, ism)에서는 SQL 조건과 정렬 규칙이 "미확인"이고, 소스가 있는 SR-05만 상태 조건 없음이라는 핵심 사실이 소스 읽기(`OrganizationService.java:23-36`)로 확정됐다. 그래프의 `dependsOn`(Status 미의존)은 그 사실의 간접 단서였다.
4. 기존 코드에 이미 "도구를 쓰는 다중 턴 루프"와 "턴 사이 사용자 피드백 브리지"(`AgenticSeedSelector.kt:17`, :234 `clarificationBridge.requestClarification`)가 있다. 새 에이전트 루프를 처음부터 만들 필요는 없다.
5. `findExclusionCandidates`(:1009)와 `transitionToStage1`(:1152)은 `src/main`에 호출자가 없다(분리 대상에서 삭제 후보).
6. 시드 선정의 도구 호출 해석 성공률은 654/655이고 실패 1건이 스트림 절단 한 건(B-34 현상)이다. 도구 호출 자체는 안정적이고 시간이 병목이다(확정 턴 중앙 52.6초).

### [B-67] 분석 시작 전 정제문 확인 카드, apc/ism 소스 폴더 기록, 호출자 없는 함수 기록, 에이전트 루프 보류 결정 (2026-10-26)

사전 확인: `git status -s` 비어 있음, HEAD = origin = f6525b5. 구현 커밋은 0551784(Kotlin, 웹뷰, `npm run build` 산출물 `src/main/resources/webview/index.html` 포함).

#### 1 확인 전 코드 위치(읽기, 변경 전 줄 번호)
- **정제문이 만들어지고 Stage 1에 가는 길**: 라우터 `/clarify-confirm`은 `buildClarifyIntentChecked`(`WebviewActionRouter.kt:264`) → `removeSession` → `saveIntent`(:270) → `executeAnalyzePipeline`(:277), 발화 완료 경로는 :322 → :328 → :335로 같은 순서다. 정제문은 `Stage0ClarificationEngine.kt:1344-1457`(`refineRequirement` :1350/:1360 → `IdentifierRetentionChecker.checkRetention` → `ClarifyIntent`)에서 만들어지고, `executeAnalyzePipeline`이 `AnalyzeInputResolver.resolve`(`AnalyzeInputResolver.kt:52-85`: 본문 = `retentionAudit.rawRefinedRequirement ?: refinedRequirement`(:58), 비면 원문(:60-62), 빠진 식별자 줄 덧붙임(:68-72))로 `effectiveRequirement`를 만들어 `pipeline.analyze(primaryReq = effectiveRequirement, clarifyIntent, analyzeInput)`(:1570-1577)에 넘긴다.
- **Stage3ConfirmationBridge**(`agent/Stage3ConfirmationBridge.kt`): 인터페이스 `requestConfirmation(candidates): List<String>?`(null = 건너뛰기, 시간 초과, Headless)와 `hideConfirmation()`. 구현 `JcefStage3ConfirmationBridge(project, timeoutSeconds = 300)`는 `project.locationHash`를 키로 `CompletableFuture`를 `pendingConfirmations`에 등록하고, `invokeLater`로 `stage3Confirmation/request`(messageId "system")를 웹뷰에 보낸 뒤 `future.get(timeout)`으로 기다린다. **`project == null`이면 곧바로 null**(카드 없이 진행). 전송 예외나 시간 초과는 항목을 지우고 null. 응답은 라우터 `stage3Confirmation/submit`/`cancel`(`WebviewActionRouter.kt:382-393`, 취소도 null 완료 = 건너뛰기)이 `completeConfirmation`/`cancelConfirmation`으로 되돌린다. 웹뷰는 `App.tsx:656-678`이 `Stage3ConfirmationForm`을 그리고 :1275-1291이 request/hide 메시지로 카드를 넣고 뺀다. 파이프라인 쪽 사용은 `RequirementAnalysisPipeline.kt:329`.
- **정제문을 담는 필드와 저장 형식**: `ClarifyIntent.refinedRequirement`(`model/ClarifyIntent.kt:12`)와 `RetentionAudit.rawRefinedRequirement`(:107, Stage 1~3가 읽는 값). 저장은 `ClarifyIntentStore.saveIntent`(:60)가 `gson.toJson(intent)`를 `.wuwagent`의 인텐트 파일에 쓴다. `contractVersion` = "1.2".

#### 2 구현(커밋 0551784)
- **흐름**: 라우터 두 호출 지점 모두 `buildClarifyIntentChecked` → `removeSession` → `continueAfterClarify`(`WebviewActionRouter.kt:1546`, 호출 :270, :325)로 이어진다. 도우미는 `RefinedRequirementGate.handoff`(확인 → 저장 → 분석)를 부른다. 확인 카드는 응답을 기다리므로 `/clarify-confirm` 경로(원래 EDT)는 도우미를 `executeOnPooledThread`에서 부른다.
- **게이트**(`agent/clarify/RefinedRequirementGate.kt`): 카드에 보이는 정제문은 `AnalyzeInputResolver`가 Stage 1~3 본문으로 읽는 값과 같다(`shownRefined`). 결정은 이대로(`Proceed`), 고침(`ProceedEdited`), 취소(`Cancel`), 결정 없음(null). 브리지가 null이거나 결정이 null이거나 예외면 받은 `ClarifyIntent`와 **같은 객체**로 진행한다. 고친 문장이 비어 있거나 보여 준 문장과 같으면 고치지 않은 것으로 본다. 고친 문장은 `refinedRequirement`와 `retentionAudit.rawRefinedRequirement`에 넣고, 식별자 보존 검사를 LLM 없이 다시 계산해 `missingBeforeFix` 등을 갱신한다(저장 형식과 `contractVersion` 1.2는 그대로).
- **브리지**(`agent/RefinedRequirementConfirmationBridge.kt`): Stage 3 브리지와 같은 방식(`locationHash` 키, `CompletableFuture`, 300초, `project == null`이면 null, 전송 예외도 null). 응답 대기는 IDE 없이 테스트할 수 있게 `RefinedConfirmationRegistry`로 분리했다. 메시지는 `refinedConfirmation/request|hide`(화면으로), `refinedConfirmation/submit|cancel`(라우터로, `editedText`가 있으면 "수정한 내용으로 분석"). 취소 안내는 `analyze_notice` "분석을 취소했습니다."이며 error 형식을 쓰지 않는다.
- **웹뷰**: `RefinedConfirmationForm.tsx`(원문과 정제문 나란히, 정제문은 편집 칸, 버튼 취소/이대로 분석/수정한 내용으로 분석), 순수 로직 `refinedConfirmLogic.ts`(고치기 전에는 수정 버튼 비활성, 빈 칸이면 진행 버튼 둘 다 비활성+경고+"원래대로 되돌리기"), `App.tsx`에서 카드와 `analyze_notice` 말풍선(오류 아님) 연결. 대기 표시(`clarifyWaitLogic.ts`)는 확인 카드가 오면 내려가도록 `END_SUBTYPES`에 `refinedConfirmation/request`, `analyze_notice`를 추가했다(사용자 응답을 기다리는 동안 "경과 N초"가 계속 늘어나는 것을 막기 위함).
- **바꾸지 않은 것**: 정제문 생성 프롬프트, 작업 요약, 제약 추출, Stage 3 확인 카드, 하네스 경로(`LiveEvalTest`와 베이스라인 하네스는 게이트를 거치지 않는다), build.gradle, 운영 프롬프트.
- **정직한 기록**: (1) 취소해도 대화 세션은 이미 닫혀 있다(`removeSession`은 카드 앞의 기존 순서 그대로). 취소 뒤에는 `/clarify`를 새로 시작해야 한다. (2) `FinalConstraintFailureNoticeTest#g4`가 라우터 소스 텍스트의 호출 횟수(분석 호출 2곳에 안내를 넘김)를 세고 있어, 구조가 도우미 한 곳으로 모이면서 실패했다. 동작이 아니라 소스 형태 검사이므로 기대를 "두 지점이 `continueAfterClarify`로 이어지고 안내는 그 안의 분석 호출 1곳이 넘긴다"로 고쳤다. (3) 확인 카드 대기 시간 초과(300초)는 정제문을 그대로 쓰고 진행한다(요청 사양). 단위 테스트는 레지스트리의 1초 대기로 검증했고 실제 300초는 기다리지 않았다. (4) IDE에서 실제 카드를 눌러 보는 끝단 확인은 하지 않았다(ui-check는 모의 브리지).

#### 테스트와 검증 결과(0551784 기준)
| 검증 | 결과 |
|---|---|
| 새 단위 테스트 `RefinedRequirementGateTest` | 13/13. g1~g2 브리지 없음·결정 없음은 같은 객체로 저장/분석(Stage 1 입력 문자열과 저장 JSON이 지금과 같음), g3~g5 고친 문장이 Stage 1 입력과 저장 JSON(`refinedRequirement`, `retentionAudit.rawRefinedRequirement`)에 들어가고 키 집합과 `contractVersion` 1.2는 그대로, g6~g7 취소는 저장·분석 0회, 안내는 `analyze_notice`이고 라우터에 error 전송 없음, g8~g9 빈 문장·같은 문장, g10~g12 대기 시간 초과·응답·예외, g13 식별자 보존 재계산 |
| 전체 테스트(서버 의존 14클래스 제외) | 548개, 실패 42, 건너뜀 17. 실패 42건은 기준(42건)과 판정 차이 0, 관찰 차이 0(첫 실행은 위 정직한 기록 (2) 때문에 43건이었고 테스트 기대 수정 뒤 재실행) |
| clarify 패키지 | 34개 스위트, 243개, 실패 1(기존 1건과 같음), 건너뜀 3 |
| 하네스 | EXACT_MATCH 5/5, MATCH_EXCLUDING 2/2(`RefinedReq:` 줄만 제외) |
| 실패 메시지 diff | 0 |
| selftest | 24/24 |
| 웹뷰 로직 테스트 | 34/34(`clarifyWaitLogic`, `errorDisplayLogic`, `removedCandidatesLogic`, 새 `refinedConfirmLogic`) |
| 전체 ui-check | 25/25(기존 19 + 새 6: 카드 표시, 수정 후 진행, 이대로 분석, 취소+안내(오류 말풍선 증가 없음), 빈 문장 차단, hide) |
| `npm run build` | 통과, 산출물 `src/main/resources/webview/index.html` 함께 커밋 |

#### 3 읽기 기록
- **3-a apc, ism 소스 폴더가 비어 있는 이유**: 확정은 못 했고 사실만 적는다. `C:/scrad/workspace/apc-pjt`(생성 2026-09-12)와 `C:/scrad/workspace/ism-pjt/ism-api-bo`(상위 생성 2026-08-06)에는 `.wuwagent`(`.gitignore`, `contracts`, apc에는 `shadow_logs.jsonl` 625KB, 최종 갱신 2026-10-23)만 있다. 그래프 JSON은 `C:/Workspace/graph/project-graph-a|i`에 있고 `projectRoot`가 위 경로, `generatedAt`이 2026-08-03(apc 05:13, ism 05:29)이다. 폴더 생성일(08-06, 09-12)이 그래프 생성일(08-03)보다 늦고 `.wuwagent`는 분석 실행이 `projectRoot`에 계약과 로그를 쓰는 곳(`ClarifyIntentStore.saveIntent`, `RequirementAnalysisPipeline.kt:331, :423-425`, `ShadowLogger.kt:34-37`, B 이전 기록 라인 760)이다. 따라서 그래프는 이 PC가 아닌 곳에서 만든 압축본(`project-graph-a.zip`, `project-graph-i.zip`이 같은 폴더에 있음)을 가져온 것으로 보이고, 이 PC에는 소스를 복사한 적이 없다고 **추정**한다. 원래 소스 경로의 기록: 저장소의 설정 파일과 문서에는 `scrad` 문자열이 `docs/analysis_pipeline_backlog.md`(위 관찰 기록)에만 있고, 그래프 `projectRoot` 외의 원본 위치 설정은 없다. 평가 시나리오의 그래프 경로는 `EvalScenarios.graphPaths()`(`EvalScenarios.kt:75-87`, `-Deval.graphs`로 바꿀 수 있음)에 고정돼 있다. 복사하지 않았다.
- **3-b 호출자가 없는 함수와 관련 테스트**: `findExclusionCandidates`(`Stage0ClarificationEngine.kt:1009`)는 `src/main`과 `src/test` 모두 호출 0건이다(정의 1건). `transitionToStage1`(:1152)은 `src/main` 호출 0건이고 테스트가 14곳에서 부른다: `ClarificationContractStoreTest.kt:583`, `Stage0RouterAndContractTest.kt:225, 332, 349, 367, 383, 403, 426, 498, 499`, `Stage0SurveyAdminSnapshotTest.kt:160, 262, 418`, `Stage0UtteranceProcessTest.kt:464`. 지우지 않았다. 이 함수만이 `enrichedRequirementText`(:1202)를 채운다.

#### 4 결정
- **에이전트 루프 보류.** 근거: 조회 3단계(약 50초, 기존 시드 선정 턴 시간 중앙값 15~19초 ×3)와 질문 생성(출력 150~250토큰 ÷ 8 tok/s = 19~31초)을 합친 첫 질문까지 70~80초 추정(B-66). 대안은 **결정적 그래프 조회 + 질문 생성 LLM 1회**(조회는 코드로 정해진 순서, 모델은 질문과 선택지만 쓴다)다. 질문 생성 시간은 **아직 실측하지 않았다**(추정은 요약 호출의 출력 토큰 중앙값에서 가져온 값).
- **출력 계약에서 의미 변경 위험은 `afterBehavior` 추출로 옮겨 간다.** B-66의 계약은 정제문 재작성 호출을 없애고 `afterBehavior` 문장을 사용자 확인으로 확정한다. 그러면 의미 변경(D2 "참조"→"추가", SR-07 "없어도 돼요"→"제공하지 않습니다")이 생길 자리는 모델이 `afterBehavior`를 뽑는 단계로 옮겨 가고, 이번 확인 카드가 그 위험에 대한 대응이다(현재는 정제문, 이후에는 `afterBehavior`를 같은 카드 방식으로 사용자가 보고 고친다).

#### 예상과 다른 점
1. `/clarify-confirm` 경로가 원래 EDT에서 정제문 LLM 호출과 분석 시작까지 하고 있었다. 응답을 기다리는 카드를 붙이려면 그 뒤를 백그라운드 스레드로 옮겨야 했다(옮기지 않으면 EDT에서 `future.get`이 교착한다).
2. 전체 테스트 첫 실행에서 새 실패 1건이 나왔다. 동작 변화가 아니라 라우터 소스 텍스트를 세는 테스트였다(위 (2)).
3. 확인 카드가 오면 clarify 카드의 "경과 N초" 대기 표시가 계속 올라가므로 대기 종료 신호에 카드 도착을 추가해야 했다.
4. 취소 시 세션은 이미 닫혀 있어 대화를 이어갈 수 없다(요청 사양대로 분석만 막았다).

### [B-68] 확인 카드의 백그라운드 전환 점검과 결함 수정, 그래프→소스 연결 확인 (2026-10-26)

사전 확인: `git status -s` 비어 있음, HEAD = origin = e705158. 수정 커밋은 16cb296. LLM 호출, 외부 접속, 소스 복사 없음. IDE에서 실제로 눌러 본 확인은 아니며 소스 읽기와 모의 브리지 테스트로 확인했다.

#### 1 읽기 결과와 결함 여부

| 항목 | 결함 | 조치 |
|---|---|---|
| 1-a 스레드 | **결함 없음**(정적 확인) | 없음 |
| 1-b 경합 | **결함 3건** | 수정 |
| 1-c 시간 초과 뒤 카드 | **결함 1건**(안내 없음) | 수정 |
| 1-d 식별자 덧붙이기 | **결함 1건** | 수정 |
| 1-e 진행 표시 | **결함 없음** | 없음 |

**1-a 스레드.** `continueAfterClarify`(`WebviewActionRouter.kt:1546`) 이후 백그라운드에서 실행되는 것: `RefinedRequirementGate.apply/handoff`(IDE API 없음), `JcefRefinedRequirementConfirmationBridge.requestConfirmation`(`invokeLater`로 카드를 보내고 `future.get`으로 대기), `ClarifyIntentStore.saveIntent`(`java.io.File`), `executeAnalyzePipeline`의 앞부분(`bridge.sendMessage("analyze_start")`, `sendMessageChunk`)과 그 안의 `executeOnPooledThread`. 뒤쪽(`GraphLoader.loadGraph`, `WuwLlmService.getClient`, `pipeline.analyze`)은 원래부터 백그라운드였다. PSI, VirtualFile, Document, FileEditorManager, ToolWindow, Notification 호출은 이 경로에 없다. **0551784 전에는 EDT였고 지금은 백그라운드인 것**은 `/clarify-confirm` 경로(원래 `invokeLater` 안)의 `saveIntent`와 `executeAnalyzePipeline` 앞부분뿐이다(`buildClarifyIntentChecked`는 여전히 EDT, 원래 동작). 그중 IDE API는 `JcefBridge.sendMessage` 하나다. 이 함수는 `executeJavaScript`를 부른 스레드에서 그대로 실행하고(`JcefBridge.kt:39-58`, 주석의 "같은 큐 순서 보장"은 EDT 호출을 가정), `sendMessageChunk`만 `invokeLater`를 쓴다. 같은 호출이 발화 완료 경로(`/clarify-utterance`)에서는 처음부터 `executeOnPooledThread` 안에서 불렸으므로 선례가 있고, 새 코드에서 EDT 전용 API를 새로 부르는 곳은 없다. 다만 `analyze_start`가 바로 실행되고 `hide`/`expired`는 `invokeLater`라서 두 메시지의 도착 순서는 보장되지 않는다(서로 영향이 없는 조합이라 결함으로 보지 않았다). 실제 IDE에서의 동작은 확인하지 못했고 아래 확인 목록에 넣었다.

**1-b 경합.**
- **새 /clarify**(결함, 수정): 카드가 응답을 기다리는 중 새 `/clarify`가 오면 기존 흐름은 `AnalysisSessionMemory.reset()`과 새 세션이 시작된 뒤에도 최대 300초 대기하다가 시간 초과로 **이전 요구사항의 분석을 시작**했다. 예전 카드가 남아 있으면 그 카드의 버튼이 새 흐름의 대기를 풀 수도 있었다(`/clarify` 분기는 확인 카드를 정리하지 않았다).
- **레지스트리 정리 경합**(결함, 수정): `RefinedConfirmationRegistry.await`는 시간 초과 때 `pending.remove(key)`로 키만 보고 지웠다. 같은 키(`project.locationHash`)로 새 요청이 이미 등록돼 있으면 그 사이에 새 요청의 항목이 지워져 새 카드의 응답이 버려졌다. `register`는 같은 키의 이전 Future를 끝내지 않고 덮어써 이전 스레드가 시간 초과까지 매달렸다.
- **두 번째 /clarify-confirm**(결함, 수정): 첫 확인에서 세션이 닫히므로 두 번째 확인은 "분석 세션이 만료되었거나 유효하지 않습니다" 오류 말풍선이 됐다.
- **/restore**: 이전 분석의 `lastResult`와 `AnalysisSessionMemory`에만 작용한다. 카드 대기 중에도 그 이전 결과의 되살리기는 동작하고 새 분석이 `AnalysisSessionMemory.begin`(`RequirementAnalysisPipeline.kt:302`)으로 바꿀 때까지 유효하다. 결함 없음.
- **/analyze**: 카드 대기와 무관하게 바로 독립 분석이 시작된다. 이후 카드 흐름도 분석을 시작하면 두 분석이 겹친다(`/analyze`를 연달아 두 번 보낸 것과 같은 기존 성질). 이번에는 고치지 않았다.
- **같은 레지스트리나 키 공유**: 정제문 카드는 자체 `RefinedConfirmationRegistry`, Stage 3 카드는 `JcefStage3ConfirmationBridge.pendingConfirmations`(둘 다 키는 `project.locationHash`지만 별개 맵)이고 웹뷰 subType도 다르다. 공유 없음.
- **Future 정리**: 응답, 취소, 시간 초과, 전송 예외 모두 항목을 지운다(위 정리 경합은 수정함).
- **`AnalysisSessionMemory.reset` 순서**: `reset`은 `/clarify` 분기의 백그라운드 시작 부분에 있다. 수정 후에는 `/clarify` 분기가 `reset`보다 앞에서 대기 카드를 정리하므로 이전 흐름이 reset 뒤에 분석을 시작할 수 없다.

**1-c 시간 초과 뒤 카드.** 300초 뒤 게이트가 `hideConfirmation`을 보내 카드는 **통째로 사라졌고** 안내는 없었다(`analyze_start`만 보임). 사라진 카드의 버튼은 누를 수 없다. 사라지기 직전에 눌린 응답은 `complete`가 항목을 못 찾아 아무 일도 없다. 결함: 왜 분석이 시작됐는지 알 수 없다.

**1-d 식별자 덧붙이기.** `AnalyzeInputResolver.kt:58-72`는 `retentionAudit.missingBeforeFix`로 "빠진 식별자 줄"을 `effectiveRequirement`에 붙인다. 0551784의 `withEditedRefined`가 고친 문장에 대해 식별자 검사를 다시 계산해 `missingBeforeFix`에 채웠으므로, 사용자가 문장에서 일부러 지운 식별자가 **다시 붙었다**(커밋 0551784의 테스트 g13이 그 동작을 정상으로 기록하고 있었음). 고치지 않은 경우(이대로 분석, 브리지 없음, 시간 초과)는 `withEditedRefined`를 거치지 않아 모델이 빠뜨린 식별자 줄이 지금처럼 붙는다.

**1-e 진행 표시.** 카드의 버튼을 누르면 해당 버튼이 "진행 중"으로 바뀌고 모두 비활성이 된다. 게이트가 응답 직후 `hide`를 보내 카드는 곧 사라지고, `saveIntent` 뒤 `analyze_start`("프로젝트 메타그래프 영향도 분석을 시작합니다")가 같은 호출 흐름에서 바로 나간다. 그 사이 공백은 파일 저장 시간(밀리초 단위 추정)이고 확정 직전의 "경과 N초" 표시는 카드 도착 때 내려가도록 B-67에서 처리했다. 결함 없음(체감 공백은 IDE에서 확인).

#### 2 수정(커밋 16cb296)
- **b**: 새 `/clarify`는 `supersedePending`으로 대기 카드를 정리한다(`RefinedConfirmationDecision.Superseded`: 저장, 분석, 안내 모두 없음, 이전 카드는 `hide`). `register`는 같은 키의 이전 요청을 `Superseded`로 끝내고, `await`의 정리는 `pending.remove(key, future)`로 자기 항목일 때만 지운다. 두 번째 확인은 `isHandoffActive`가 참이면 세션 조회 전에 무시한다(확정 두 지점이 세션을 닫기 전에 `beginHandoff`, `continueAfterClarify`가 `finally`에서 `endHandoff`, 새 `/clarify`가 표지도 푼다).
- **c**: 시간 초과를 `RefinedConfirmationDecision.TimedOut`으로 구분해 `expireConfirmation` → `refinedConfirmation/expired`를 보낸다. 웹뷰는 카드를 지우지 않고 비활성으로 두며 "응답이 없어 원래 문장으로 분석을 진행했습니다."를 보인다(`markExpired`, `RefinedConfirmationForm`의 `expired`). 그 밖의 결정은 지금처럼 카드를 내린다.
- **d**: 사용자가 고친 경우 `missingBeforeFix`를 비워 지운 식별자를 다시 붙이지 않는다(`withEditedRefined`). 고치지 않은 경우의 동작은 같다.
- 하네스 경로(브리지 없음)는 `RefinedRequirementGate.apply`가 받은 객체를 그대로 돌려주는 것이 그대로다(테스트 g1, g14).
- **정직한 기록**: (1) 레지스트리 교체 경로(`register`가 이전 요청을 `Superseded`로 끝내는 경우)에서는 이전 흐름의 `hide`가 새 카드를 지울 수 있는 경쟁이 이론상 남는다. 두 번째 확인을 막았으므로 정상 흐름에서는 일어나지 않는 경로라 고치지 않았다. (2) `/clarify-utterance`에 대기 중 발화가 오면 여전히 세션 만료 오류가 난다(요청 범위 밖). (3) 새 `/clarify`로 이전 흐름을 정리해도 이미 시작된 `/analyze`와 겹치는 문제는 그대로다.

#### 테스트와 검증(16cb296 기준)
| 검증 | 결과 |
|---|---|
| `RefinedRequirementGateTest` | 19/19(수정 항목별 추가: g10 시간 초과가 `TimedOut`, g13 지운 식별자 안 붙음, g14 고치지 않은 경우 지금과 같음, g15 만료 안내, g16 새 /clarify가 대신하면 분석 없음, g17 레지스트리 새 요청/정리 경합, g18 진행 표지, g19 라우터 소스 확인) |
| 전체 테스트(서버 의존 14클래스 제외) | 554개, 실패 42, 건너뜀 17. 실패 42건은 기준과 판정 차이 0, 관찰 차이 0 |
| clarify 패키지 | 249개, 실패 1(기존 1건), 건너뜀 3 |
| 하네스 | EXACT_MATCH 5/5, MATCH_EXCLUDING 2/2 |
| 실패 메시지 diff | 0 |
| selftest | 24/24 |
| 웹뷰 로직 테스트 | 35/35(만료 표시 1건 추가) |
| 전체 ui-check | 26/26(기존 25 + 만료 카드: 비활성, 안내 문구, 이후 전송 0건) |
| `npm run build` 산출물 | `src/main/resources/webview/index.html` 함께 커밋 |

#### 1-f 그래프→소스 연결 확인(읽기만)
- **노드가 가진 위치 정보**: `FileNode`(`MetaGraphModels.kt:135`)의 `path`는 **프로젝트 루트 기준 상대 경로**이고(apc 2,419개, ism 3,552개, member-market 90개 모두 상대, 절대 0), `methods`는 `MethodSignature(name, returnType, parameters, isInherited)`(:125)뿐이다. **줄 번호 필드는 없다**(`startLine`/`endLine` 0건). 리소스 노드는 `path`(상대), `type`, `layer`, `linkedTo`, `metadata`(`sql_id`, `table_name`, `include_ref`, `api_url` 등)이고 줄 번호가 없다. 관계(`relationships`)는 `source`/`target`(상대 경로), `type`, `strength`다.
- **실행 때 basePath와 합치는 방식**: 소스를 읽는 곳마다 다르다. `ContractResolver.kt:311`, `:464`은 `"${project.basePath}/${path}"`로 `LocalFileSystem.findFileByPath`를 부르고, `ImplementService`/`ImplementContextBuilder`는 `sourceRoot = Paths.get(project.basePath)`에 `sourceRoot.resolve(spec.path)`(`WebviewActionRouter.kt:446`, `ImplementContextBuilder.kt:64`, `:137`, `ImplementService.kt:121`)를 쓴다. **`PseudoCodeParser.kt:179`만 `Path.of(graph.projectRoot, filePath)`로 그래프가 만들어진 시점의 절대 경로(projectRoot)를 쓴다.** 그래프를 읽는 쪽 `GraphLoader.kt:172-190`은 서브 모듈 그래프의 `projectRoot`가 basePath 아래일 때만 `path` 앞에 상대 접두사를 붙이고, 그 외에는 그대로 둔다.
- **그래프 생성 시점의 정보**: 최상위 `generatedAt`(ISO 시각), `projectRoot`, `framework`, `statistics`뿐이다. **커밋 해시, 파일 해시, 파일 수정 시각은 없다**(`commit`, `mtime`, `lastModified`, `modifiedAt` 0건; "hash"/"sha" 검색 결과는 `hashCode`, `getHash` 같은 식별자 이름이다). `generatedAt`은 `ProjectGraphBuilder.kt:352` 등이 쓰고 `MetaGraphExporter.kt:76`이 다시 쓸 뿐 **읽는 곳이 없다**. `ClarificationContractStore.calculateGraphHash`(`:104`)는 파일 경로, 클래스명, 관계의 정렬된 문자열 해시로 소스 내용과 무관하다.
- **그래프와 소스가 어긋난 것을 알아챌 방법**: **지금은 없다.** `GraphLoader`의 캐시는 그래프 파일의 수정 시각만 본다(`GraphLoader.kt:40-50`). 소스 파일 존재나 변경은 검사하지 않는다.
- **소스가 없을 때, 메서드를 못 찾을 때의 동작과 사용자에게 보이는 것**:
  - `inspect_node_detail`(`AgenticSeedSelector.kt:650-712`): 그래프 안의 `methods`, `koreanComments`, `apiEndpoints`, 의존 관계를 돌려주며 **소스를 읽지 않는다**. 노드가 없으면 `{"error":"노드를 찾을 수 없습니다: …"}`를 모델에게 돌려줄 뿐 사용자에게는 안 보인다. **리소스 노드는 `metadata`를 그대로 돌려준다**(`sql_id`, `table_name`, `include_ref` 포함). 그래서 B-66에서 "미확인"으로 남긴 "리소스 노드까지 돌려주도록 확장이 필요한지"는 **확장이 필요 없다**로 정정한다(파일명, 예: `ECMBTBISM006Mapper.xml`로 조회).
  - Stage 3(`FileRelevanceVerifier.buildFileContext` :430-447): 소스가 아니라 `docs/<파일명>.md`를 읽고, 없으면 `buildGraphContext(node)`, 그것도 없으면 리소스 컨텍스트, 마지막에 "정보 없음"이다. 소스 폴더가 비어 있어도 영향을 받지 않고 사용자에게 아무 표시도 없다.
  - `/implement`: 소스가 없으면 `sourceContent = ""`로 조용히 넘어가고(`ImplementService.kt:121-122`, `ImplementContextBuilder.kt:138`), 유사 구현 탐색은 해당 파일을 건너뛴다(:64-66). `PseudoCodeParser.kt:177-186`은 `exists()`가 거짓이면 소스 기반 검증을 건너뛴다. `ContractResolver`는 `findFileByPath`가 null이면 `continue`한다. 사용자에게는 경고가 없고 컨텍스트만 줄어든다.
  - 메서드를 못 찾는 경우: 그래프의 메서드 목록은 소스와 대조하지 않는다. 소스가 있을 때 PSI(`ContractResolver`)가 읽은 실제 메서드와 그래프 메서드가 다른지를 비교하는 코드는 없다.
- **apc, ism 그래프에 남은 생성 환경 정보**: `projectRoot` 하나뿐이다(apc `C:/scrad/workspace/apc-pjt`, ism `C:/scrad/workspace/ism-pjt/ism-api-bo`, `generatedAt` 2026-08-03 05:13/05:29 UTC). 커밋 정보, 호스트명, 사용자 이름, 로컬 경로(`C:/Users`)는 두 그래프에 **0건**이다. 같은 필드를 가진 member-market 그래프는 이 PC에서 만들어졌다(`C:/Workspace/member-market`, 의존 jar 경로 `C:/Users/dffrp/.gradle/...`가 149건 남아 있음). 평가용 소스를 맞추려면 **`projectRoot` 경로(`C:/scrad/workspace/...`)와 `generatedAt`(2026-08-03)** 이 단서의 전부이고, 그 날짜 시점의 소스(커밋은 모름)를 해당 경로에 두는 것이 근거가 된다. 소스를 복사하지 않았다.
- **B-66 "현재 동작" 칸의 근거 등급**(확인 = 그래프 속성이나 소스를 직접 읽음, 추정 = 한글 주석이나 이름에서 읽은 동작, 미확인 = 근거 없음):

| 건 | 문장 | 등급 | 근거 노드·속성 |
|---|---|---|---|
| SR-12 | 서비스에 `selAppVerUpdtInf`가 있고 최신앱버전 정보를 조회한다 | 메서드 존재 **확인** / 조회 동작 **추정** | 노드 `APCCSAppVerMngtBIZ` `methods[selAppVerUpdtInf(APCCSAppInfSVO)]`(확인), `koreanComments` "사용자 앱정보로 최신앱버전 정보조회"(추정) |
| SR-12 | OS, 스토어, 버전을 입력으로 받는다 | **추정** | `koreanComments` "모바일OS구분코드", "앱스토어구분코드", "모바일앱버전명"(파라미터 타입 `APCCSAppInfSVO`의 필드는 미조회) |
| SR-12 | 최신은 `selNewSappVer`, 중간은 `selMidlSappVer` | 메서드 존재 **확인** | 노드 `ACAOTBAPC003DEM` `methods` |
| SR-12 | 중간 쿼리는 노출 여부('Y')를 조건으로 쓰고 최신 쿼리는 쓰지 않는 것으로 읽힌다 | **추정** | `APCCSAppVerMngtBIZ` `koreanComments`("selNewSappVer 쿼리와 유사하지만 … SPACD_DLSTM_USE_YN = 'Y' 사용"). SQL 본문 **미확인** |
| SR-12 | 중간이 강제 유형이면 최신의 업데이트 유형과 메시지를 덮어쓴다 | **추정** | 같은 노드 `koreanComments` |
| SR-12 | 관리 쪽에도 최신/중간 조회가 있고 서비스 쪽과 같은 쿼리를 쓰는지는 모른다 | 존재 **확인**, 쿼리 공유 **미확인** | 노드 `APCADUpdtVerMngtBIZ` `methods[selLtSappVer, selLtMidlSappVer]` |
| SR-12 | 버전 번호가 가장 큰 것이 최신이라는 현재 규칙 | **미확인** | 근거 노드 없음(정렬은 SQL 안) |
| D4 | 케어회원 목록, 개수, 상세, 저장, 수정 API가 있다 | **확인** | 노드 `CareMemberMgmtController` `apiEndpoints[httpMethod, path, handlerMethod]` |
| D4 | 목록 쿼리는 `getCareMemberMgmtList`이고 5개 테이블을 조인하며 `getCareMemberMgmtListWhere`를 포함한다 | **확인**(`sql_id`, 테이블 이름, 포함 참조의 존재) | 리소스 노드 `ECMBTBISM006Mapper.xml` `metadata[sql_id, table_name, include_ref]`. 조건절의 내용은 **미확인** |
| D4 | 요청/응답이 유의고객 등록·해제 정보를 다룬다 | **추정** | `CareMemberMgmtRequest`, `CareMemberMgmtResponse`의 `koreanComments`(필드 이름과의 대응은 미조회) |
| D4 | 관리 화면(화면 파일) | **미확인** | careMember 관련 `resourceNodes`는 매퍼 2개뿐(화면 노드 0건) |
| SR-05(참고) | 사용자용 조직 목록에 상태 조건이 없다 | **확인**(소스를 직접 읽음) | `OrganizationService.java:23-36`, `OrganizationRepository.java:14`(호출처 없음) |

#### IDE에서 확인할 항목
1. `/clarify` → 확정 뒤 확인 카드가 뜨고, "이대로 분석", "수정한 내용으로 분석", "취소"가 각각 의도대로 동작하는가(분석 시작 메시지가 뜨는가, 취소 안내가 오류 말풍선이 아닌가).
2. 카드가 떠 있는 동안 EDT가 멈추지 않는가(에디터 입력, 다른 창 조작이 막히지 않는가). `analyze_start`와 `hide`/`expired`가 순서에 상관없이 정상 표시되는가(`JcefBridge.sendMessage`를 백그라운드에서 부르는 것).
3. 카드 대기 중 새 `/clarify`를 보내면 이전 카드가 사라지고, 300초가 지나도 이전 요구사항의 분석이 시작되지 않는가.
4. 확인 카드 대기 중 확정을 다시 보내려 하면(Enter 연타 등) 오류 말풍선이 뜨지 않고 무시되는가.
5. 300초 무응답 뒤 카드가 남아 비활성이 되고 "응답이 없어 원래 문장으로 분석을 진행했습니다."가 보이며 분석이 시작되는가(대기 시간을 줄여 보는 것은 코드 변경이 필요하므로 실제 5분 대기로 확인).
6. 문장에서 식별자(예: `SurveyServiceImpl`)를 지우고 "수정한 내용으로 분석"을 눌렀을 때 Stage 3 입력에 그 식별자가 다시 붙지 않는가(분석 결과의 프롬프트나 로그로 확인).
7. 취소한 뒤 다시 `/clarify`를 시작해 정상 진행되는가(세션은 이미 닫혀 있음).
8. 채팅을 저장했다가 다시 불러올 때 확인 카드가 남아 눌러도 반응 없는 카드로 복원되지 않는가(Stage 3 카드와 같은 기존 성질일 수 있음).

#### 예상과 다른 점
1. 새 /clarify가 카드를 정리하지 않아 300초 뒤 이전 요구사항의 분석이 시작될 수 있었다(예상보다 사용자에게 보이는 영향이 큰 결함).
2. 정제문 카드 구현(0551784)의 테스트 g13이 사용자가 지운 식별자가 다시 붙는 동작을 정상으로 못 박고 있었다. 의도와 반대였고 이번에 기대를 바꿨다.
3. Stage 3은 소스를 읽지 않고 `docs/*.md`와 그래프만 읽으므로, 소스 폴더가 비어 있어도 Stage 3 결과에는 영향이 없다. 소스를 읽는 쪽은 `/implement` 계열이며 그 안에서 `PseudoCodeParser`만 basePath가 아닌 그래프의 `projectRoot`를 쓴다.
4. `inspect_node_detail`은 리소스 노드의 `metadata`(SQL id, 테이블)를 이미 돌려준다. B-66의 "확장이 필요한지 미확인"은 필요 없음으로 정정한다.
5. 그래프에는 커밋, 파일 해시, 수정 시각이 없고 `generatedAt`도 읽는 곳이 없어 그래프와 소스의 어긋남을 알아챌 수단이 지금 없다.

### [B-69] 질문 생성 실험: 결정적 그래프 조회 + 소스에서 현재 동작 추출 + 질문 생성 LLM 1회 (2026-10-26)

테스트 코드로만 실험했다(`src/main` diff 0줄). 코드: `agent/eval/QuestionGenExperiment.kt`(노드 선정, 현재 동작 문장, 검증기), `QuestionGenExperimentTest.kt`(단위 테스트 10개 + 실행 1개, 실행은 `-DrunLiveLlmTests=true`일 때만), 프롬프트 `src/test/resources/eval/qgen_prompt.txt`(운영 프롬프트 아님). 결과 파일은 `build/eval-reports/qgen_results.json`(저장소 밖)이고 기대값 파일은 `build/eval-reports/pred_qgen.md`(sha256 `1f0ff3645f2fb13bd7acb7b02f24be92d42891483592403818cb40b30b2ef6d1`, 실행 후 수정하지 않았다. 전문은 아래에 그대로 붙였다). 사전 확인: `git status -s` 비어 있음, HEAD = origin = 67a0fce, `/v1/models` HTTP 200(모델 `Qwen/Qwen3.8-27B-FP8` 포함). 아래 기준(운영 투입)은 참고이며 이번 push 판정과 무관하다.

#### 1-a 대상 시나리오(그래프의 소스 폴더가 실제로 있는 것, holdout 제외)
| 시나리오 | 그래프 | 소스 | generatedAt 뒤 수정된 소스 |
|---|---|---|---|
| D1, D5, S3, SR-01, SR-02 | survey_admin(generatedAt 2026-09-15T07:38Z) | 노드 132개 중 132개 있음 | 0개 |
| S1, S2, SR-04, SR-05, SR-05X | member_market(generatedAt 2026-08-20T00:27Z) | 노드 114개 중 114개 있음 | 0개 |
| D2, D3, SR-11, SR-12 | apc | 없음(2,670개 중 0개) | - |
| D4, SR-07, SR-08 | ism | 없음(4,396개 중 0개) | - |
| SR-03, SR-06, SR-09, SR-10 | holdout | 제외 | - |

실험 대상 10개(D1, D5, S1, S2, S3, SR-01, SR-02, SR-04, SR-05, SR-05X) × 3회 순차 = 30회. SR-05X는 probe 그룹이라 `-Deval.only`로 포함했다.

#### 1-b 노드 선정 규칙(구현 전에 적은 문장, 기대값 파일에 같은 문장)
1. 입력은 SR 원문만이다(스크립트와 GT는 쓰지 않는다). 어절에서 끝 조사와 어미를 떼어 용어로 삼고, 2글자 미만과 동작어·일반어 불용어(추가, 변경, 수정, 조회, 목록, 기능, 화면, 등록, 삭제 등)를 버린다. 영문 식별자는 그대로 용어로 쓴다.
2. 각 용어를 파일 노드와 리소스 노드에 대조해 점수를 매긴다: 한글 용어가 `koreanComments`나 `localName`에 있으면 3점, 영문 식별자가 `className`에 포함되면 3점, 한글 용어를 내장 사전(`DomainDictionary.translate`의 상위 3토큰)으로 번역한 영문 토큰이 `className`의 CamelCase 토큰과 같으면 2점, 메서드 이름 토큰과만 같으면 1점.
3. 전체 노드의 10%를 넘게 맞는 용어는 변별력이 없어 버린다.
4. 총점 2 이상 노드를 점수 내림차순(동점이면 SERVICE, CONTROLLER, REPOSITORY, ENTITY, MYBATIS_MAPPER 순)으로 최대 5개 고른다. 없으면 0개이고 현재 동작은 전부 미확인이다.
5. 소스는 `projectRoot` + 상대 경로로 읽는다. 메서드 본문은 SR 용어와 이름이 맞는 메서드를 우선해 노드당 최대 3개(값만 읽고 쓰는 getX()/setX와 Object 메서드는 제외), 매퍼 XML은 SQL id별로 읽는다.
구현 중 규칙을 바꾼 곳은 한 군데다(live 실행 전, 단위 테스트가 잡았다): 처음 구현은 이름이 `get`으로 시작하는 메서드를 전부 getter로 보고 제외해 `getOrganizations(String type)` 같은 서비스 메서드를 놓쳤다. 매개변수 없는 getX/isX와 매개변수 하나인 setX만 제외하도록 고쳤다.

#### 4 결과(30회, 전부 응답을 받았고 중단 조건에 해당하지 않음)
열: prompt/completion 토큰(`UsageProxy` 응답 usage), 호출 시간(서버 응답까지, 초), 질문 수, 파일 범위 질문(키워드 검출/수동 판정), 기대 질문과 겹친 B-65 빈칸 수(스크립트가 미리 답한 것/아닌 것), 추정 근거인데 "~로 보입니다. 맞습니까?" 형태가 아닌 질문 수, 입력에 없는 식별자를 말한 수. expectedQuestions는 이 10개 시나리오에 정의가 없다(해당 없음).

| 시나리오#회 | prompt | completion | 호출(초) | 질문 | 파일범위 키워드/수동 | 겹침 답함/아님 | 추정형태 위반 | 근거밖 식별자 |
|---|---|---|---|---|---|---|---|---|
| D1#1 | 1790 | 653 | 77.9 | 4 | 0/0 | 1/0 | 1 | 0 |
| D1#2 | 1790 | 559 | 66.0 | 4 | 0/1 | 0/0 | 1 | 0 |
| D1#3 | 1790 | 536 | 63.3 | 3 | 0/0 | 0/0 | 1 | 0 |
| D5#1 | 1790 | 537 | 63.4 | 3 | 0/0 | 0/0 | 1 | 0 |
| D5#2 | 1790 | 452 | 53.4 | 3 | 0/0 | 0/1 | 0 | 0 |
| D5#3 | 1790 | 626 | 73.9 | 3 | 0/0 | 0/0 | 2 | 1 |
| S1#1 | 1590 | 514 | 61.5 | 3 | 0/0 | 0/1 | 0 | 2 |
| S1#2 | 1590 | 396 | 46.8 | 2 | 0/1 | 0/1 | 0 | 0 |
| S1#3 | 1590 | 510 | 60.2 | 3 | 0/0 | 0/2 | 0 | 0 |
| S2#1 | 1044 | 424 | 50.4 | 3 | 0/0 | 1/0 | 1 | 2 |
| S2#2 | 1044 | 381 | 45.3 | 3 | 0/0 | 1/0 | 1 | 0 |
| S2#3 | 1044 | 373 | 44.4 | 3 | 0/0 | 1/0 | 0 | 0 |
| S3#1 | 1459 | 515 | 62.7 | 3 | 0/0 | 0/1 | 0 | 0 |
| S3#2 | 1459 | 362 | 44.7 | 2 | 0/0 | 0/1 | 0 | 0 |
| S3#3 | 1459 | 529 | 64.3 | 3 | 0/0 | 0/1 | 0 | 3 |
| SR-01#1 | 1371 | 10 | 3.1 | 0 | 0/0 | 0/0 | 0 | 0 |
| SR-01#2 | 1371 | 367 | 45.2 | 2 | 0/1 | 0/0 | 0 | 1 |
| SR-01#3 | 1371 | 10 | 3.1 | 0 | 0/0 | 0/0 | 0 | 0 |
| SR-02#1 | 1615 | 359 | 43.2 | 3 | 0/0 | 1/1 | 0 | 0 |
| SR-02#2 | 1615 | 608 | 71.8 | 4 | 0/1 | 1/1 | 0 | 1 |
| SR-02#3 | 1615 | 447 | 54.3 (재시도 1회, 전체 145초) | 5 | 0/0 | 1/1 | 0 | 0 |
| SR-04#1 | 1052 | 275 | 33.0 | 2 | 0/1 | 0/0 | 1 | 0 |
| SR-04#2 | 1052 | 339 | 40.4 | 2 | 0/1 | 0/0 | 1 | 1 |
| SR-04#3 | 1052 | 261 | 31.4 | 2 | 0/1 | 0/0 | 1 | 0 |
| SR-05#1 | 1556 | 318 | 38.3 | 2 | 0/1 | 1/0 | 0 | 8 |
| SR-05#2 | 1556 | 10 | 2.0 | 0 | 0/0 | 0/0 | 0 | 0 |
| SR-05#3 | 1556 | 10 | 2.1 | 0 | 0/0 | 0/0 | 0 | 0 |
| SR-05X#1 | 1556 | 504 | 60.1 | 3 | 0/1 | 1/1 | 0 | 11 |
| SR-05X#2 | 1556 | 316 | 38.1 | 2 | 0/1 | 1/0 | 0 | 7 |
| SR-05X#3 | 1556 | 453 | 54.1 | 3 | 0/1 | 1/1 | 0 | 7 |

수동 판정 기준(B-66 1-f 규칙): **겹침** = 질문이 B-65 "물어야 할 질문"의 같은 빈칸을 묻는 것(같은 답으로 답할 수 있으면 겹침, 요구사항 원문에 이미 적힌 것을 다시 묻는 질문은 인정하지 않음). **파일 범위 질문(수동)** = 구현 위치, 클래스, 메서드, 엔드포인트를 고르게 하는 질문. 키워드 검출은 "어느 파일", "클래스를 수정" 같은 문구와 파일 확장자 정규식이라 한 건도 잡지 못했고, 수동으로 읽으면 11건이다(D1 #2 Q1 "SurveyServiceImpl의 어떤 프로세스에", S1 #2 Q2 "ChatService 내부인지 별도 API인지", SR-01 #2 Q1 "코드 어디에 하드코딩", SR-02 #2 Q1 "참고할 대상이 UserServiceImpl의 특정 메서드", SR-04 세 회차 Q1 "구현 위치/대상 메서드나 엔드포인트", SR-05 #1 Q2와 SR-05X 세 회차 Q2 "조직 목록 조회 API는 어디에 위치"). 겹침 판정은 사람 판단이고 경계 사례가 있다(D1 #2 Q3 "동일한 배치 작업으로 템플릿 동기화"는 템플릿 동기화이지 발송 방식이 아니라 겹침으로 세지 않았고, D5 #2 Q3 "발송은 동일 배치 주기"는 발송 방식으로 세었다).

**요약 줄**
- 전체 질문 75개(응답 30회, 질문 0개인 응답 4회: SR-01 #1, #3, SR-05 #2, #3은 `{"questions": []}` 10토큰). 겹침: B-65 빈칸 57개(30회분) 중 24개를 물었다 = **42%**. 스크립트가 미리 답한 빈칸 15개 중 11개(73%), 스크립트가 답하지 않은 빈칸 42개 중 13개(**31%**). 질문 기준으로는 75개 중 27개(36%)가 겹침이고 SR-02 #3은 같은 빈칸(표시 항목)을 네 질문으로 쪼갰다.
- 파일 범위 질문 합계: 키워드 0, 수동 **11**.
- 거짓 "확인" 합계: **0**(확인 문장 67개(시나리오별 입력 기준), 문장마다 근거 파일의 줄 범위를 다시 읽어 말한 호출·조건·반환·테이블이 범위 안에 있는지 대조). generatedAt 뒤에 수정된 파일이 근거로 쓰인 경우 0(수정된 파일이 0개).
- 호출 시간: 30회 중앙 **46.8초**, 최대 77.9초(질문이 있는 26회만 중앙 53.4초). SR-02 #3은 90초 읽기 제한에 걸려 운영 클라이언트가 1회 재시도했고 전체 145초였다(재시도 호출 54.3초). completion 중앙 396토큰(질문이 있는 응답 447토큰), prompt 1,044~1,790토큰, 출력 속도 8.1~8.5 tok/s로 시간 ≈ completion ÷ 8 관계가 그대로다.
- 형식 검사: JSON 해석 30/30, 질문 수 상한(5) 위반 0(최대 5, SR-02 #3), 보기 2~5개/추천 답/근거 번호 범위 위반 0. 위반 15건은 두 종류다: 추정 근거 질문이 "~로 보입니다. 맞습니까?" 형태가 아닌 것 11건(D1 3, D5 3, S2 2, SR-04 3), `shortAnswer: true`인데 보기를 준 것 4건(SR-05 #1, SR-05X #2의 두 질문씩).
- 입력에 없는 식별자를 말한 수: 합계 44. 대부분 현재 동작에 관련 노드가 없을 때 모델이 후보를 지어낸 것이다(SR-05 #1 8개: `status`, `ACTIVE`, `deletedAt`, `isActive`, `OrganizationService`, `OrganizationController`, `MemberService`, `MemberController`; SR-05X 7~11개). 지시문 9번("입력에 없는 이름을 지어내지 않는다")을 어긴 것이다.

**현재 동작 문장(시나리오별, 3회 동일 입력)**

| 시나리오 | 노드(점수 순) | 문장 수(추정/확인/미확인) | 거짓 확인 | 10% 초과로 버려진 용어 |
|---|---|---|---|---|
| D1, D5 | AlimtalkTemplateBatchRepository, SurveyServiceImpl, SurveyDaoImpl, AlimtalkChnlDto, AlimtalkTemplateBatchJob | 10/9/0 | 0 | - |
| S1 | ProductService, ChatWebSocketController, MessageType, WebSocketConfig | 7/9/2 | 0 | 채팅방(21) |
| S2 | AdminService, AdminRole, AdminManageResponse | 4/4/2 | 0 | 회원(17) |
| S3 | KmcisServiceImpl, IpsController | 5/6/0 | 0 | 본인인증(16) |
| SR-01 | SurveyDto, UserDto, UserServiceImpl, AlimtalkTemplateBatchRunner, CallbackInfoDto | 10/4/0 | 0 | - |
| SR-02 | AlimtalkTemplateBatchRunner, CallbackInfoDto, UserDto, IbCenterApiServiceImpl, UserServiceImpl | 10/7/0 | 0 | 사용자(21), 로그인(18), 조회할(28) |
| SR-04 | ProductStatus, ProductService, TransactionStatus | 4/3/4 | 0 | 상품(23) |
| SR-05, SR-05X | AdminService, AdminRole, DataInitializer, OrganizationType, WebSocketEventListener | 7/8/4 | 0 | - |

노드 선정이 요구사항의 핵심 코드를 놓친 곳이 많다(GT는 보지 않았으므로 정답 재현율은 재지 않았고, SR 문장과 노드 이름을 읽은 판단이다): SR-05는 "조직" 용어가 내장 사전에도 한글 주석에도 없어 조직 조회 서비스(`OrganizationService`, B-66에서 소스로 확인한 `getOrganizations`)는 고르지 못했고 "관리자" 용어로 `AdminService`가 뽑혔다(기대값 파일은 노드 0개를 예측했는데 실제는 무관한 노드 5개가 뽑혔다). S1은 "채팅방"이, S2는 "회원"이, S3는 "본인인증"이, SR-02는 "사용자", "로그인"이 일반어로 버려졌다(`koreanComments`에서 맞는 노드가 10%를 넘었기 때문). 그 결과 SR-02는 알림톡 배치와 DTO가 뽑혔고, 질문 12개 중 11개가 근거 번호를 달지 못했다.

**회차 간 질문 차이**(질문을 빈칸 주제로 묶어 사람이 판단, 2회 이상 같은 주제가 나온 비율): D1 3/8(구조, 발신프로필키, 배치 동기화), D5 2/6(템플릿 유형 코드, 배치 주기), S1 3/3(증상, 계산 방식, markAsRead 연동), S2 3/5(접근 차단, 즉시/승인, 사유 기록), S3 2/3, SR-02 2/5(최근 범위, 표시 항목), SR-04 2/2(구현 위치, 제외 상태: 세 회차가 같은 두 질문), SR-05X 3/3, SR-01과 SR-05는 질문이 나온 회차가 1회뿐이라 비교 불가. 합계 20/35 = **57%**. D1, D5, S3, SR-02는 같은 SR인데 회차마다 다른 빈칸을 묻고, SR-01(2회 빈 응답)과 SR-05(2회 빈 응답)는 같은 입력에서 "질문 없음"과 질문 있음이 갈렸다.

**질문 원문 예시(시나리오당 1회차, 잘라 옮김)**

**D1 #1** (근거 번호는 현재 동작 문장 번호)
- Q1 [5,6] 설문 발송 시 '브랜드메시지' 채널을 선택할 수 있도록 해야 하는 대상은 무엇입니까? / 보기 4개 / 추천: 설문 생성/수정 화면의 채널 선택 목록
- Q2 [1,2] 현재 `AlimtalkTemplateBatchRepository`는 알림톡 템플릿 데이터를 처리하는 것으로 보입니다. 브랜드메시지 채널의 템플릿 데이터도 동일한 구조(버튼, 템플릿 코드 등)로 저장/관리합니까? / 보기 3개 / 추천: 아니요, 브랜드메시지 전용 다른 구조/필드가 필요합니다
- Q3 [15,16] 브랜드메시지 채널 추가 시, 기존 알림톡 채널과 동일한 발신프로필키(Sender Key)를 사용합니까? 아니면 별도의 발신프로필키가 필요합니까? / 보기 3개 / 추천: 브랜드메시지 전용 별도의 발신프로필키가 필요합니다
- Q4 [17,18] 설문 발송 채널에 브랜드메시지를 추가할 때, 기존 알림톡 발송 로직과 동일한 방식으로 발송을 처리합니까? (예: 동일한 배치 Job 또는 서비스 메서드 사용) / 보기 3개 / 추천: 채널에 따라 발송 로직을 분기 처리합니다

**D5 #1** (근거 번호는 현재 동작 문장 번호)
- Q1 [1] 현재 `AlimtalkTemplateBatchRepository`는 템플릿 유형(BA, EX, AD, MI)을 처리하는 것으로 보입니다. '브랜드메시지' 채널을 추가할 때, 이 채널에 해당하는 새로운 템플릿 유형 코드(예: BM)를 정의해야 하는지, 아니면 기존 유형 중 하나를 재사용하는지 어떻게 처리합니까? / 보기 3개 / 추천: 새로운 템플릿 유형 코드(예: BM)를 정의하고 추가한다
- Q2 [17,18] 현재 `AlimtalkTemplateBatchJob`은 매 시간 정각에 실행되어 카카오 알림톡 채널 및 템플릿 정보를 업데이트하는 것으로 보입니다. '브랜드메시지' 채널의 템플릿 정보도 동일한 배치 주기로 함께 업데이트되어야 합니까? / 보기 2개 / 추천: 네, 기존 알림톡 배치와 동일한 주기로 함께 실행된다
- Q3 [7,8] 현재 `SurveyServiceImpl`은 설문 이미지 업로드 및 삭제 기능을 제공하고 있습니다. '브랜드메시지' 채널을 통한 설문 발송 시, 기존 설문 이미지 업로드/삭제 로직(`uploadSurveyImg`, `deleteSurveyImg`)을 그대로 재사용해야 합니까? / 보기 2개 / 추천: 네, 기존 로직을 그대로 재사용한다

**S1 #1** (근거 번호는 현재 동작 문장 번호)
- Q1 [9] 채팅방 목록에서 안 읽은 메시지 개수가 '이상하게' 나온다는 것은 어떤 구체적인 증상을 의미합니까? / 보기 4개 / 추천: 안 읽은 메시지 개수가 실제보다 많음 (읽었는데도 안 읽음으로 유지)
- Q2 [9] 현재 `ChatWebSocketController.markAsRead` 메서드는 `chatService.markAsRead`를 호출하고 있습니다. 이 로직이 실행되었을 때 안 읽은 메시지 개수가 올바르게 감소하지 않는 것으로 추정됩니다. 맞습니까? / 보기 2개 / 추천: 네, markAsRead 호출 후에도 안 읽음 개수가 줄지 않음
- Q3 [0] 채팅방 목록 조회 시 안 읽은 메시지 개수는 어떻게 계산되고 있습니까? (현재 동작 문장에 채팅방 목록 조회 로직이 명시되어 있지 않아 추정이 필요합니다.) / 보기 3개 / 추천: 채팅방 엔티티에 저장된 unreadCount 필드를 직접 조회함

**S2 #1** (근거 번호는 현재 동작 문장 번호)
- Q1 [5] 관리자가 정지시킬 대상 '회원'의 범위는 무엇입니까? (현재 AdminService는 AdminRepository를 통해 관리자 계정을 조회하고 있으며, MemberRepository에 대한 직접적인 정지 로직은 확인되지 않았습니다.) / 보기 3개 / 추천: 일반 회원(Member)만
- Q2 [0] 이용 정지 시 회원 계정의 상태 변경 방식은 어떻게 처리합니까? / 보기 3개 / 추천: 회원 엔티티에 상태 필드(예: status, enabled)를 추가하여 변경
- Q3 [0] 정지된 회원이 시스템에 접근(로그인, API 호출)을 시도할 경우 어떤 동작을 기대합니까? / 보기 3개 / 추천: 로그인 시 '계정이 정지되었습니다' 에러 메시지 반환

**S3 #1** (근거 번호는 현재 동작 문장 번호)
- Q1 [4] 현재 `processAuthResult` 메서드는 `authCheckParam.getResult()`가 "Y"일 때만 성공으로 처리하고, `type`이 "regist"일 때 특정 로직을 수행하는 것으로 보입니다. 요구사항에서 말하는 '결과 처리'의 구체적인 변경 사항(예: 실패 코드 추가, 다른 type 처리, 반환값 구조 변경 등)은 무엇입니까? / 보기 없음(짧은 답) / 추천: 구체적인 변경 로직 설명
- Q2 [3,4] 현재 `verifyAuth` 메서드는 세션이 없거나 암호화 메시지 검증에 실패하면 `SecurityException`을 던지고, 성공 시 `null`을 반환하는 것으로 확인됩니다. 본인인증 요청이 끝난 뒤의 '결과 처리'가 이 `verifyAuth` 단계의 예외 처리나 반환값 변경을 포함합니까? / 보기 3개 / 추천: 아니오, processAuthResult 단계의 비즈니스 로직만 변경하면 됩니다.
- Q3 [4] 현재 `processAuthResult`는 `CallbackInfoDto`를 입력으로 받아 `resultDto`를 반환하는 것으로 확인됩니다. 이 메서드가 호출되는 시점(콜백 수신 시점)과 반환된 `resultDto`가 최종 사용자에게 전달되는 방식(예: 세션 저장, JSON 응답 등)에 대한 변경이 필요합니까? / 보기 2개 / 추천: 아니오, 메서드 내부 로직만 수정하면 됩니다.

**SR-01 #2** (근거 번호는 현재 동작 문장 번호)
- Q1 [6] 현재 코드에서 계정 잠금 로직(연속 실패 횟수 체크 및 잠금 처리)이 `UserServiceImpl`의 `proc` 메서드나 다른 메서드에서 어떻게 구현되어 있는지 확인이 필요합니다. 현재 5회 기준이 코드 어디에 하드코딩되어 있거나 설정값으로 존재하는지, 혹은 해당 로직이 코드에 존재하지 않는지 알려주실 수 있습니까? / 보기 4개 / 추천: 현재 코드에 계정 잠금 로직이 없음
- Q2 [6] 계정이 잠겼을 때 사용자에게 반환되는 안내 문구(에러 메시지)는 현재 어떤 형태로 정의되어 있습니까? (예: 하드코딩된 문자열, 리소스 번들, DB 저장 등) / 보기 4개 / 추천: 현재 잠금 안내 문구가 없음

**SR-02 #1** (근거 번호는 현재 동작 문장 번호)
- Q1 [0] 최근 로그인 기록 조회 시 '최근'의 범위를 어떻게 정의합니까? / 보기 4개 / 추천: 최근 10건
- Q2 [0] 로그인 기록 조회 시 반환할 데이터 항목에 '로그인 시도 시간'과 '성공/실패 여부' 외에 추가적으로 포함할 항목이 있습니까? / 보기 4개 / 추천: 없음 (시간, 성공/실패만)
- Q3 [0] 관리자가 사용자별 로그인 기록을 조회할 때, 사용자 ID를 직접 입력하여 조회하는 방식입니까, 아니면 사용자 목록에서 선택하여 조회하는 방식입니까? / 보기 3개 / 추천: 사용자 ID 직접 입력

**SR-04 #1** (근거 번호는 현재 동작 문장 번호)
- Q1 [0] 검색 기능의 현재 구현 위치나 로직이 소스에서 확인되지 않았습니다. 검색 시 판매 상태 필터링을 적용해야 하는 대상이 무엇입니까? / 보기 3개 / 추천: 기존 검색 로직이 없으므로 새로 구현
- Q2 [1] 요구사항에 '판매 중이 아닌 상품(예약 중·판매 완료)'이라고 명시되어 있습니다. 검색 결과에서 제외할 상태가 정확히 '예약중'과 '판매완료' 두 가지뿐입니까? / 보기 2개 / 추천: 예, 예약중과 판매완료만 제외

**SR-05 #1** (근거 번호는 현재 동작 문장 번호)
- Q1 [0] 요구사항에서 '운영 중인 조직'의 기준이 무엇입니까? 현재 코드에서 조직 상태(status) 필드나 관련 로직을 확인하지 못했습니다. / 보기 3개 / 추천: 조직 엔티티에 status 필드가 있고, 특정 값(예: ACTIVE)일 때만 표시
- Q2 [0] 일반 사용자에게 노출되는 조직 목록 조회 API는 어디에 위치합니까? 현재 제공된 동작 문장에는 관리자 로그인 및 초기화 로직만 포함되어 있어 일반 조직 목록 조회 메서드를 확인하지 못했습니다. / 보기 3개 / 추천: OrganizationService 또는 OrganizationController에 존재

**SR-05X #1** (근거 번호는 현재 동작 문장 번호)
- Q1 [0] 요구사항에서 '운영 중인 조직'의 기준이 무엇입니까? 현재 코드에서 조직의 상태(status) 필드나 운영 여부(active)를 확인하는 로직이 발견되지 않았습니다. / 보기 4개 / 추천: 조직 엔티티에 status 필드가 있고, 'ACTIVE' 또는 'OPERATING' 값일 때
- Q2 [0] 일반 사용자에게 보이는 '조직 목록' 조회 API는 어디에 위치합니까? 현재 제공된 동작 문장에는 관리자 로그인이나 초기화 로직만 포함되어 있어, 일반 조직 목록 조회 메서드를 특정할 수 없습니다. / 보기 3개 / 추천: OrganizationService의 getOrganizations 또는 listOrganizations 메
- Q3 [0] 관리자 화면의 조직 목록은 '그대로 두어야' 한다고 명시되었는데, 이는 운영 중이 아닌 조직(폐쇄/휴면 등)도 포함하여 모두 표시하라는 의미입니까? / 보기 2개 / 추천: 네, 상태와 무관하게 모든 조직을 표시

#### 기대값 파일과 대조(B-66 1-f 규칙)
| 항목 | 예측(구현 전) | 실제 | 판정 |
|---|---|---|---|
| 호출 성공 | 30회 중 28회 이상 | 30/30 응답(질문 0개인 응답 4회 포함) | 맞음 |
| 호출 시간 | 중앙 약 40초, 최대 65초 이하 | 중앙 46.8초(질문 있는 회차 53.4초), 최대 77.9초(재시도 포함 145초) | **틀림**(길다) |
| 시나리오별 시간 예측(질문 수 × 95토큰) | D1 약 43초 등 | D1 63~78초: 질문 하나가 약 150토큰(보기, 추천 답, 근거 번호 때문)이라 completion을 약 40% 적게 잡았다 | **틀림**(토큰 과소 추정) |
| 질문 수 | 평균 2.5~3, 5 초과 없음 | 평균 2.5(질문 있는 회차 2.9), 최대 5 | 맞음 |
| 파일 범위 질문 | 0 | 키워드 0, 수동 11 | **틀림**(키워드 검출은 못 잡음) |
| 거짓 "확인" | 0 | 0 | 맞음 |
| 겹침(스크립트가 답하지 않은 빈칸) | 40~60%, 노드 없는 SR-05/05X는 0~50% | 31%(전체 42%, 질문 기준 36%) | **틀림**(낮다) |
| 회차 간 같은 빈칸 | 70% 이상 | 57% | **틀림** |
| generatedAt 뒤 수정 근거 | 0 | 0 | 맞음 |
| 노드 선정 실패 시나리오 | SR-05, SR-05X(조직) | SR-05, SR-05X 외에 S1, S2, S3, SR-02도 핵심 용어가 일반어로 버려지거나 사전에 없어 핵심 노드를 놓침 | **부분적으로 틀림**(범위가 더 넓다) |
| SR-05의 노드 수 | 0개 | 무관한 노드 5개 | **틀림** |

#### 운영 투입 기준(참고, 이번 push 판정과 무관)
| 기준 | 결과 |
|---|---|
| 파일 범위 질문 0 | 미달(수동 11, 키워드 0) |
| 거짓 "확인" 0 | 충족(0) |
| 겹침 50% 이상 | 미달(빈칸 기준 42%, 스크립트가 답하지 않은 빈칸 31%, 질문 기준 36%) |
| 호출 시간 중앙 30초 이하 | 미달(46.8초, 질문 있는 회차 53.4초) |
4개 중 1개만 충족. 이 결과로는 운영에 넣을 구조가 아니다.

#### 과제 목록에 추가
- **Stage 3가 소스를 읽지 않는다**: `FileRelevanceVerifier.buildFileContext`가 `docs/<파일명>.md`, 없으면 그래프 컨텍스트, 마지막에 "정보 없음"만 쓴다(B-68). 소스 본문 근거가 필요하면 별도 단계가 필요하다.
- **generatedAt 기반 그래프와 소스 어긋남 경고**: `generatedAt`을 읽는 곳이 없고 파일 수정 시각 비교도 없다. 이번 실험의 검사(근거 파일의 수정 시각 대 `generatedAt`)처럼 로드 시점에 경고를 낼 수 있다.
- **`PseudoCodeParser.kt:179`의 `projectRoot` 경로 해석 불일치**: 다른 소스 읽기는 `basePath`에 상대 경로를 합치는데 이 한 곳만 그래프의 생성 시점 절대 경로를 쓴다(B-68).
- **B-68의 남은 경합 3건**: (1) 레지스트리가 같은 키의 이전 요청을 교체하는 경로에서 이전 흐름의 `hide`가 새 카드를 지울 수 있다(두 번째 확인을 막아 정상 흐름에서는 일어나지 않음), (2) `/clarify-utterance`가 확인 카드 대기 중에 오면 세션 만료 오류가 난다, (3) 이미 시작된 `/analyze`와 카드 뒤 분석이 겹칠 수 있다.
- **이번 실험에서 나온 과제**: 한글 요구사항 용어를 영문 클래스명에 잇는 결정적 사전이 없어 영어 이름 프로젝트(member_market)에서 핵심 노드를 놓친다(`DomainDictionary.BUILTIN_TERMS`에 "조직", "계정", "정지" 등이 없고 `.meta/dictionary.json`은 노이즈로 꺼져 있음). 현재 동작 문장에 관련 사실이 없을 때 질문 생성이 후보 이름을 지어내거나 구현 위치를 묻는 문제(지시문 3, 9번 위반)는 프롬프트 문장 개선으로 막을 수 있는지 따로 확인이 필요하다. 질문이 구현 방식을 묻는 쪽으로 흐르는 경향(S2 "상태 변경 방식", S1 "계산 방식")도 B-66 1-f의 "구현 방법을 묻지 않는다"에 어긋난다.

#### 예상과 다른 점
1. 시간이 예측보다 길다. 질문 하나의 completion이 약 150토큰(보기와 추천 답, 근거 번호를 쓰기 때문)이라 5개에 가까울수록 70초를 넘는다. 호출 시간 기준 30초는 질문 1~2개에서만 가능해 보인다.
2. 같은 입력에서 질문 0개와 질문 있음이 갈렸다(SR-01, SR-05). 질문 수 하한이나 "빈칸이 있으면 반드시 묻는다"는 지시가 없어서다.
3. 키워드 검출은 파일 범위 질문을 하나도 잡지 못했는데 수동으로는 11건이다. 모델은 "구현 위치"를 파일 이름 없이 에둘러 묻는다. 자동 판정은 별도 설계가 필요하다.
4. 확인 문장 67개 모두 근거 대조를 통과했다(거짓 확인 0). 그러나 문장이 소스를 정확히 옮겨도 요구사항과 무관한 노드(SR-02의 알림톡 배치 등)를 근거로 삼으면 질문이 쓸모없다. 거짓 "확인"이 없다는 것과 질문 품질은 별개다.
5. 질문 75개 중 32개(43%)가 근거 문장 번호를 달지 못했다(S1 3, S2 5, SR-02 11, SR-04 3, SR-05 2, SR-05X 8). 질문 0개로 끝난 4회와 함께 노드 선정이 실패하면 질문이 근거 없이 일반론으로 흐르는 모습이다.

#### 기대값 파일 전문(`pred_qgen.md`, 구현 전 작성, 수정 없음)
```markdown
# pred_qgen — 질문 생성 실험의 기대값 (구현 전 작성, 3번 이후 수정하지 않는다)

작성 시각: 2026-10-26 (구현·실행 전). 판정 규칙은 B-66 1-f를 그대로 쓴다(빈칸 명확/부분/없음, 질문 인정 규칙, 반영 판정).
GT 파일 목록은 보지 않았다. 질문 목록은 B-65의 "물어야 할 질문"(SR 원문과 스크립트만으로 정한 것)이다.

## 대상 시나리오 (그래프의 소스 폴더가 실제로 있는 것, holdout 제외)

| 시나리오 | 그래프 | 소스 | generatedAt 뒤 수정된 소스 |
|---|---|---|---|
| D1, D5, S3, SR-01, SR-02 | survey_admin (`C:/Workspace/HC_card_survey_admin/survey_admin`, generatedAt 2026-09-15T07:38Z) | 노드 132개 중 132개 있음 | 0개 |
| S1, S2, SR-04, SR-05, SR-05X | member_market (`C:/Workspace/member-market`, generatedAt 2026-08-20T00:27Z) | 노드 114개 중 114개 있음 | 0개 |
| (제외) D2, D3, SR-11, SR-12 | apc | 없음(2,670개 중 0개) | - |
| (제외) D4, SR-07, SR-08 | ism | 없음(4,396개 중 0개) | - |
| (제외, holdout) SR-03, SR-06, SR-09, SR-10 | - | - | - |

실험 대상 10개: D1, D5, S1, S2, S3, SR-01, SR-02, SR-04, SR-05, SR-05X (시나리오당 3회 순차 = 호출 30회).

## 노드 선정 규칙 (결정적, LLM 없음, 기존 스캐너를 쓰지 않는다)

1. 입력은 SR 원문만이다(스크립트 발화, GT는 쓰지 않는다). SR을 공백·문장부호로 나눈 어절에서 끝의 조사(을, 를, 이, 가, 은, 는, 의, 에, 에서, 로, 으로, 와, 과, 도, 만, 까지 등)와 어미를 떼어 용어로 삼고, 2글자 미만과 불용어(추가, 변경, 수정, 조회, 목록, 기능, 화면, 등록, 삭제, 해 주세요류 등 동작어)는 버린다. 영문 식별자(CamelCase, 점 표기)는 그대로 용어로 쓴다.
2. 각 용어를 그래프 노드(파일 노드와 리소스 노드)에 대조해 점수를 매긴다: 한글 용어가 노드의 `koreanComments`나 `localName`에 부분 문자열로 있으면 3점, 영문 식별자가 `className`에 포함되면 3점, 한글 용어를 내장 사전(`DomainDictionary.load(graph).translate(용어)`의 상위 토큰, 최대 3개)으로 번역한 영문 토큰이 `className`의 CamelCase 토큰과 같으면 2점, 메서드 이름 토큰과 같으면 1점이다.
3. 그래프 노드의 10% 넘게 맞는 용어는 변별력이 없는 용어로 보고 점수에서 뺀다(SR-08에서 625개를 끌어온 일반어 문제를 피한다).
4. 총점이 2 이상인 노드를 점수 내림차순으로 정렬한다. 동점이면 SERVICE, CONTROLLER/REST_CONTROLLER, REPOSITORY, ENTITY, MYBATIS_MAPPER, 그 밖의 순으로 앞세운다. 최대 5개를 고른다. 2점 이상인 노드가 없으면 노드는 0개이고 "현재 동작"은 전부 "미확인"이다.
5. 고른 노드의 소스는 `projectRoot` + 상대 경로로 읽는다(절대 경로가 아니라 `basePath` 대응 값). 메서드 본문은 SR 용어와 이름이 맞는 메서드를 우선해 노드당 최대 3개, 매퍼 XML은 SQL id별 문장을 읽는다.

## 현재 동작 문장의 등급
- 확인: 메서드·SQL 본문 텍스트에 직접 근거가 있는 문장(근거로 파일, 줄 범위, 본문 일부를 함께 단다).
- 추정: 이름·구조(클래스명, 한글 주석, 엔드포인트, 의존 관계)에서만 읽은 문장.
- 미확인: 질문에 필요한데 근거가 없는 것(소스에서 찾지 못함 등).

## 시나리오별 기대값

호출 시간 예상 = (40 + 95×질문 수) 토큰 ÷ 8 tok/s + 프리필 2초(B-60, B-62의 "시간 ≈ 출력 토큰 ÷ 8"과 clarify 덤프의 요약 호출 출력 중앙 153토큰에서 질문 하나를 95토큰으로 가정). 질문에는 보기 2~5개, 추천 답, 근거 문장 번호가 붙고 질문 하나가 조금 더 길어질 수 있어 상한은 +30%로 본다.

| 시나리오 | B-65 "물어야 할 질문"(스크립트가 미리 답한 것 표시) | expectedQuestions | 예상 노드 | 예상 질문 수 | 예상 호출 시간 |
|---|---|---|---|---|---|
| D1 | (1) 브랜드메시지 발송 방식은 기존 알림톡과 같은 방식인가 [스크립트가 답함] (2) 발송 실패 시 처리 | 없음 | 2~5개(설문·발송·알림톡 관련) | 3 | 약 43초 (상한 56) |
| D5 | (1) 브랜드메시지 발송 방식 (2) 발송 실패 시 처리 [스크립트 답 없음: 스크립트는 제외 지시뿐] | 없음 | D1과 같음(SR이 같다) | 3 | 약 43초 (상한 56) |
| S1 | (1) 어떻게 이상한가(항상 0, 너무 많음, 갱신 안 됨 등) (2) 정상일 때의 기준(읽음 처리 기준) | 없음 | 채팅 관련 2~5개 | 3 | 약 43초 (상한 56) |
| S2 | (1) 정지는 영구인가 기간 지정인가 (2) 해제 방법 (3) 정지 중 로그인 차단 [스크립트가 답함] | 없음 | 회원 관련 2~5개 | 3 | 약 43초 (상한 56) |
| S3 | (1) 현재 문제는 무엇인가(오류, 값 누락 등) (2) 바뀐 뒤 기대 동작 | 없음 | 인증 관련 1~3개 | 2 | 약 31초 (상한 40) |
| SR-01 | (1) 새 안내 문구를 정해 주시는가, 숫자만 3으로 바꾸는가 | 없음 | 로그인·사용자 관련 1~3개 | 2 | 약 31초 (상한 40) |
| SR-02 | (1) 조회 단위와 기간 [스크립트가 답함: 한 사용자, 30일] (2) 표시 항목 | 없음 | 사용자·로그인 관련 2~4개 | 3 | 약 43초 (상한 56) |
| SR-04 | (1) 검색 밖 경로(상세 직접 접근, 카테고리 목록)에서도 숨기는가 | 없음 | 상품 관련 2~4개 | 2 | 약 31초 (상한 40) |
| SR-05 | (1) 대기·비활성 상태 포함 여부 [스크립트가 답함: 대기도 숨김] (2) 어느 목록(사용자용, 관리자용) | 없음 | **0개 예상**(`조직`은 내장 사전에 없고 `koreanComments`가 비어 있다) | 3 | 약 43초 (상한 56) |
| SR-05X | SR-05와 같다 | 없음 | SR-05와 같음(0개 예상) | 3 | 약 43초 (상한 56) |

## 전체 예측
- 호출 성공: 30회 중 28회 이상(비스트림 90초 상한, 기존 재시도).
- 호출 시간: 중앙 약 40초, 최대 65초 이하.
- 질문 수: 시나리오·회차 평균 2.5~3, 상한 5를 넘지 않는다.
- 파일 범위 질문: 0(프롬프트가 파일, 클래스 선택을 묻지 않게 지시하고 입력에 후보 파일 목록이 없다).
- 거짓 "확인": 0(현재 동작 문장은 LLM 없이 본문에서 템플릿으로 만들고 출처를 검사한다).
- "물어야 할 질문"과 겹침: 스크립트가 답하지 않은 질문 기준 40~60%. 노드가 없는 SR-05, SR-05X는 사실 근거가 없어 겹침이 낮다(0~50%). 운영 기준 50%는 넘기 어렵다고 본다.
- 회차 간 차이: 같은 빈칸을 묻되 문장과 보기가 달라진다(질문 단위로 같은 빈칸 비율 70% 이상).
- generatedAt 뒤에 수정된 소스가 근거로 쓰인 경우: 0(수정된 파일이 0개).
- 노드 선정이 실패하는 시나리오: SR-05, SR-05X(조직 용어). 그 밖에는 노드가 1개 이상 선정된다.
```

#### 검증(커밋 04d9a80 기준, `src/main` diff 0줄)
| 검증 | 결과 |
|---|---|
| 전체 테스트(서버 의존 14클래스 제외) | 565개, 실패 42, 건너뜀 18(새 실행 테스트 1개가 건너뜀). 실패 42건은 기준과 판정 차이 0, 관찰 차이 0 |
| clarify 패키지 | 249개, 실패 1(기존 1건), 건너뜀 3 |
| 하네스 | EXACT_MATCH 5/5, MATCH_EXCLUDING 2/2 |
| 실패 메시지 diff | 0 |
| selftest | 24/24 |
| 새 단위 테스트 `QuestionGenExperimentTest` | 10개 통과(지시문 기대값 6, 결정적 단계 4), 실행 1개는 `-DrunLiveLlmTests` 없이는 건너뜀 |

### [B-69a] runIde 준비(빌드 상태 확인, [RefineGate] 진단 로그, 사용자 실행 안내문)와 이전 IDE 로그 분석 보류 (2026-10-26)

사전 확인: `git status -s` 비어 있음, HEAD = origin = 8715ff1. **`build/ide-logs/analyze_prev_20261026.log`가 없다**(`build/ide-logs` 폴더 자체가 없고, 저장소와 `C:/Workspace`, 다운로드, 바탕화면에서 `analyze_prev*`를 찾지 못했다). 그래서 **2부(4-a~4-i 이전 IDE 로그 분석)는 건너뛰었다.** 다만 아래 1-c에서 샌드박스 `idea.log`에 오늘(2026-10-26) 13:19~13:29의 `/clarify` → `/clarify-utterance` → `/clarify-confirm` 실행 기록이 남아 있는 것을 확인했다. 그 구간을 `build\ide-logs\analyze_prev_20261026.log`로 복사해 주면 2부를 이어서 할 수 있다(이번에는 복사하지 않았다). runIde, IDE 조작, LLM 호출은 하지 않았다.

#### 1-a 빌드 상태
| 대상 | 생성 시각 | 카드 문구 / 카드 클래스 |
|---|---|---|
| `src/main/resources/webview/index.html`, `build/webview/index.html` (3,584,274자) | 2026-10-26 13:08 | 웹뷰 문구 "이대로 분석", "수정한 내용으로 분석", "응답이 없어 원래 문장으로", "분석 시작 전 요구사항 확인", "원래대로 되돌리기"와 메시지 키 `refinedConfirmation/request|expired|submit`, `analyze_notice`가 **원문 그대로 있다**(유니코드 이스케이프 형태는 0건이라 디코딩이 필요 없다). |
| "분석을 취소했습니다" | - | **웹뷰 번들에는 없다(정상).** 이 문구는 웹뷰가 아니라 Kotlin `WebviewActionRouter.continueAfterClarify`가 `analyze_notice`로 보낸다. 컴파일된 클래스에는 있다(아래 `build/libs` jar 1줄). |
| `build/libs/iXpert_AI-Assistant-Intelli-1.0-SNAPSHOT.jar` | 2026-10-26 13:08 | 웹뷰 문구 모두 있음, 클래스 `RefinedRequirementGate`, `JcefRefinedRequirementConfirmationBridge`, `continueAfterClarify`, "분석을 취소했습니다", `refinedConfirmation/expired`, `supersedePending`, `isHandoffActive`, `expireConfirmation` 모두 있음(16cb296의 수정 내용 포함). 단 `[RefineGate]` 로그는 아직 없음(이번 커밋 전 빌드). |
| **샌드박스 플러그인 jar** `build/idea-sandbox/IC-2024.3.5/plugins/iXpert_AI-Assistant-Intelli/lib/…-1.0-SNAPSHOT.jar` | **2026-10-26 09:31** | **카드 문구와 카드 클래스가 전부 없다**(웹뷰 문구 0/6, 메시지 키 0/4, "분석을 취소했습니다", `continueAfterClarify` 0건). |
| `build/distributions/…zip` | 2026-09-18 | 카드 없음(옛 배포본) |

커밋 시각: 0551784(카드 구현) 2026-10-26 10:04, 16cb296(수정) 13:19. 샌드박스 jar(09:31)는 두 커밋보다 앞서 만들어졌다.

#### 1-b runIde 동작
- `./gradlew -m runIde`(dry-run, 실행 없이 작업 순서만 출력)로 확인한 실행 순서: `nodeSetup` → `npmSetup` → `installWebviewDependencies` → `buildWebview` → `copyWebviewToResources` → `processResources` → `classes` → `instrumentCode` → `jar` → `instrumentedJar` → `composedJar` → **`prepareSandbox`** → `runIde`. 즉 runIde는 실행 전에 웹뷰 빌드, 리소스 복사, 플러그인 jar 재생성, 샌드박스 플러그인 갱신을 **자동으로 한다**(`build.gradle.kts:57-83`의 `buildWebview`/`copyWebviewToResources`/`processResources` 의존, `prepareSandbox`는 IntelliJ Platform Gradle Plugin 2.11.0 제공). 소스가 바뀌었는데 입력이 같으면 UP-TO-DATE로 건너뛴다.
- 샌드박스 경로: `build/idea-sandbox/IC-2024.3.5/`(`config`, `plugins`, `system`, `log`, 테스트용 `config-test`/`plugins-test`/`system-test`/`log-test`는 별개). **`gradlew test`는 `plugins-test`만 갱신하고 `plugins`(runIde용)는 갱신하지 않는다**(그래서 이번 세션에서 테스트를 여러 번 돌렸어도 샌드박스 jar는 09:31 그대로다).
- 샌드박스 IDE 설정 저장 위치: `config/options/WuwAgentSettings.xml`(내용은 `enableLlmDebug`, `frameworkType`, `model`뿐이고 서버 주소는 기본값을 쓴다. API 키 값은 이 파일에 없다). 채팅 기록은 샌드박스가 아니라 `C:\Users\dffrp\AppData\Roaming\ixpert\chats\`에 저장된다(`/saveChat` 로그의 "채팅 저장 성공" 경로).
- **`gradlew clean`을 하면 `build/` 전체가 지워진다**: 샌드박스 설정(`config`의 모델 설정, 열어 둔 프로젝트 이력), 샌드박스 로그(`log/idea*.log`), `build/nodejs`(자동 다운로드한 Node 20.11.1, 다시 받아야 함), `build/webview`, `build/eval-reports`(이번 실험의 저장소 밖 결과 `qgen_results.json`, `pred_qgen.md`와 평가 보고서), `build/ui-check`, `build/ide-logs`. 저장소에 커밋된 것은 지워지지 않는다.

#### 1-c 이전 실행 판별
**카드 없는 빌드였다(확정).** 샌드박스 `log/idea.log`의 마지막 "IDE STARTED"는 **2026-10-26 09:31:42**이고(`Loaded custom plugins: iXpert AI Assistant (1.0-SNAPSHOT)`, 플러그인 경로 `build\idea-sandbox\…`), 샌드박스 jar는 같은 시각(09:31)에 만들어졌으며 카드 문구와 클래스가 없다. 이 IDE 세션은 0551784(10:04) 전에 시작해 지금까지 같은 jar로 돌았고, 로그의 13:19:32 `Router: /clarify 분기`, 13:21:16 `/clarify-utterance`, 13:22:26 `/clarify-confirm`은 옛 코드(카드 없음)로 실행된 것이다. `idea.log`에 `refinedConfirmation` 전송 줄도 없다. 따라서 이전 IDE 화면에서 카드를 볼 수 없었던 것은 코드 문제가 아니라 **옛 빌드를 쓰고 있었기 때문**이다. 지금 그 IDE가 떠 있다면 닫고 runIde를 다시 실행해야 새 jar가 샌드박스에 복사된다.

#### 1-d 로그 출력 위치
기존 `[AgenticGraphExplorer]` 로그는 **`println`(콘솔)**이다: `AgenticSeedSelector.kt:23-27`(시작 배너), `:92`, `:99`, `:107`, `:110` 등. 같은 `println`은 runIde 콘솔(Gradle 창)에 나오고, IDE가 stdout을 `log/idea.log`에 `INFO - STDOUT - …` 줄로도 옮긴다(샌드박스 `idea.log`에서 `STDOUT - saveChat 수신…` 형태로 확인). 반면 `WebviewActionRouter`의 `logger.info`(51곳)는 IntelliJ Logger라 `idea.log`에만 `#…WebviewActionRouter - …` 줄로 남는다. `[RefineGate]`는 1-d와 같은 `println`을 써서 **콘솔 로그 파일(`Tee-Object`)과 `idea.log` 둘 다**에서 찾을 수 있다.

#### 2 진단 로그 커밋 `6a60070`
`[RefineGate]` 한 줄 로그 14줄만 추가했다(삭제 0줄, 로그가 아닌 추가 줄 0줄). 요청 id `rid`는 SR 원문의 해시(`Integer.toHexString(원문.hashCode())`)이고 라우터, 게이트, 브리지가 모두 같은 값을 만들 수 있어서 쓴다(같은 SR로 연속 실행하면 같은 rid이므로 시각으로 구분한다). 지점: `/clarify` 수신과 대기 카드 정리 호출, `/clarify-confirm` 수신, 두 번째 확인 무시, 정제문 생성 완료(글자 수와 앞 80자, 확정 버튼 경로와 발화 완료 경로 각각), 브리지 판정(`project null=false, 브리지 null=false → 카드 요청`), 게이트의 `브리지 null → 카드 없이 진행`, 카드 요청 전송, 웹뷰 응답 수신(submit 이대로/수정 N자, cancel), 게이트의 응답 결과(이대로/수정 N자/취소/시간 초과/새 /clarify가 대신함/결정 없음), 취소 시 분석 미시작 안내, 새 /clarify로 대기 카드 정리(대기중, 확인진행중), 분석 시작 직전 SR(앞 80자). 동작, 메시지 형식, 화면 문구, 저장 형식은 바뀌지 않았고 로그 문구에 대한 테스트도 추가하지 않았다. 웹뷰는 고치지 않아 빌드 산출물은 커밋하지 않았다.

추가된 로그 줄 전체(`git diff -U0` 결과의 `+` 줄 14개, 들여쓰기 제거):
```
println("[RefineGate] /clarify 수신 messageId=$messageId → 대기 중인 정제문 카드 정리를 호출했습니다(이전 분석은 시작하지 않음)")
println("[RefineGate] /clarify-confirm 두 번째 확인을 무시했습니다(확인 진행 중) project=${project.locationHash}")
println("[RefineGate] /clarify-confirm 수신 messageId=$messageId")
println("[RefineGate] rid=${Integer.toHexString(intentBuild.intent.originalRequirement.hashCode())} 정제문 생성 완료(확정 버튼 경로) messageId=$messageId 원문 ${intentBuild.intent.originalRequirement.length}자, 정제문 ${net.ib.ixpert.ops.wuwagent.agent.clarify.RefinedRequirementGate.shownRefined(intentBuild.intent).length}자, 앞80자: ${net.ib.ixpert.ops.wuwagent.agent.clarify.RefinedRequirementGate.shownRefined(intentBuild.intent).take(80).replace("\n", " ")}")
println("[RefineGate] rid=${Integer.toHexString(intentBuild.intent.originalRequirement.hashCode())} 정제문 생성 완료(발화 완료 경로) messageId=$messageId 원문 ${intentBuild.intent.originalRequirement.length}자, 정제문 ${net.ib.ixpert.ops.wuwagent.agent.clarify.RefinedRequirementGate.shownRefined(intentBuild.intent).length}자, 앞80자: ${net.ib.ixpert.ops.wuwagent.agent.clarify.RefinedRequirementGate.shownRefined(intentBuild.intent).take(80).replace("\n", " ")}")
println("[RefineGate] 웹뷰 응답 수신: submit ${if (editedText == null) "이대로 분석" else "수정(" + editedText.length + "자)"} projectId=$projectId")
println("[RefineGate] 웹뷰 응답 수신: cancel projectId=$projectId")
println("[RefineGate] rid=${Integer.toHexString(intentBuild.intent.originalRequirement.hashCode())} 브리지 판정: project null=false, 브리지 null=false → 카드 요청 messageId=$messageId")
println("[RefineGate] rid=${Integer.toHexString(intentBuild.intent.originalRequirement.hashCode())} 취소 → 분석을 시작하지 않고 안내(analyze_notice)만 보냅니다")
println("[RefineGate] rid=${Integer.toHexString((inMemoryClarifyIntent?.originalRequirement ?: rawInput).hashCode())} 분석 시작 직전 SR(${initialRequirement.length}자, ${if (inMemoryClarifyIntent != null) "clarify 경유" else "/analyze 직접"}): ${initialRequirement.take(80).replace("\n", " ")}")
println("[RefineGate] 새 /clarify로 대기 카드 정리 projectId=$projectId 대기중=${registry.isPending(projectId)} 확인진행중=${activeHandoffs.contains(projectId)}")
println("[RefineGate] rid=${Integer.toHexString(originalRequirement.hashCode())} 카드 요청 전송 projectId=$projectId 정제문 ${refinedRequirement.length}자")
if (bridge == null) println("[RefineGate] rid=${Integer.toHexString(intent.originalRequirement.hashCode())} 브리지 null → 카드 없이 진행")
println("[RefineGate] rid=${Integer.toHexString(intent.originalRequirement.hashCode())} 응답 결과: ${when (decision) { null -> "결정 없음(정제문 그대로 진행)"; RefinedConfirmationDecision.Proceed -> "이대로 분석"; is RefinedConfirmationDecision.ProceedEdited -> "수정(" + decision.text.trim().length + "자)"; RefinedConfirmationDecision.Cancel -> "취소"; RefinedConfirmationDecision.Superseded -> "새 /clarify가 대신함(분석 안 함)"; RefinedConfirmationDecision.TimedOut -> "시간 초과(정제문 그대로 진행)" }}")
```

검증(커밋 6a60070 기준): 전체 테스트 565개, 실패 42, 건너뜀 18(실패 42건은 기준과 판정 차이 0, 관찰 차이 0), clarify 패키지 249개 중 실패 1(기존 1건), 하네스 EXACT_MATCH 5/5와 MATCH_EXCLUDING 2/2, 실패 메시지 diff 0, selftest 24/24. 컴파일 오류 없음.

#### 3 사용자 실행 안내문(PowerShell)
**먼저 닫을 것**: 샌드박스 IDE(09:31에 뜬 창)가 아직 떠 있으면 닫는다(`runIde`는 같은 샌드박스를 쓰므로 둘이 겹치면 시작이 막히거나 옛 jar를 계속 쓴다). `.\gradlew.bat clean`은 하지 않는다(샌드박스 설정, 로그, 실험 결과 `build\eval-reports`, Node 다운로드가 모두 지워진다).

```powershell
cd C:\Workspace\DEV-ASSISTANT\IDE-PLUGIN\intelliJ\ai-assistant-plugin

# 1) 실행 전 확인
git status -s                 # 비어 있어야 한다
git log --oneline -3          # 맨 위가 6a60070(진단 로그) 이후 커밋인지 확인
Get-Item build\idea-sandbox\IC-2024.3.5\plugins\iXpert_AI-Assistant-Intelli\lib\iXpert_AI-Assistant-Intelli-1.0-SNAPSHOT.jar | Select-Object LastWriteTime
                              # 지금은 2026-10-26 09:31이다(옛 jar). runIde가 끝까지 시작하면 이 시각이 바뀐다.

# 2) runIde 실행과 콘솔 로그 저장 (Gradle 창은 IDE를 닫을 때까지 열려 있다)
New-Item -ItemType Directory -Force build\ide-logs | Out-Null
.\gradlew.bat runIde 2>&1 | Tee-Object -FilePath build\ide-logs\runide_20261026.log

# 3) IDE가 뜬 뒤 새 jar인지 확인(다른 PowerShell 창)
Get-Item build\idea-sandbox\IC-2024.3.5\plugins\iXpert_AI-Assistant-Intelli\lib\iXpert_AI-Assistant-Intelli-1.0-SNAPSHOT.jar | Select-Object LastWriteTime
Add-Type -AssemblyName System.IO.Compression.FileSystem
$z = [IO.Compression.ZipFile]::OpenRead((Resolve-Path build\idea-sandbox\IC-2024.3.5\plugins\iXpert_AI-Assistant-Intelli\lib\iXpert_AI-Assistant-Intelli-1.0-SNAPSHOT.jar))
$r = New-Object IO.StreamReader($z.GetEntry('webview/index.html').Open(), [Text.Encoding]::UTF8)
$t = $r.ReadToEnd(); $r.Close(); $z.Dispose()
$t.Contains('수정한 내용으로 분석')     # True여야 한다

# 4) 확인을 마친 뒤 [RefineGate] 줄 모으기
Select-String -Path build\ide-logs\runide_20261026.log -Pattern 'RefineGate' | ForEach-Object { $_.Line }
Select-String -Path build\idea-sandbox\IC-2024.3.5\log\idea.log -Pattern 'RefineGate' | ForEach-Object { $_.Line }
```
로그 위치: 콘솔 로그 `build\ide-logs\runide_20261026.log`(Tee), IDE 로그 `build\idea-sandbox\IC-2024.3.5\log\idea.log`(`INFO - STDOUT - [RefineGate] …`). 웹뷰로 간 메시지는 `idea.log`의 `Bridge → Webview (subType=refinedConfirmation/request …)` 줄로도 보인다. 같은 실행의 줄은 `rid=`(SR 해시)와 시각으로 묶는다.

**확인 항목과 찾을 로그**(A~I는 요청 목록, J~L은 B-68의 IDE 확인 8개와 대조해 빠진 것을 더했다)

| 항목 | 화면에서 볼 것 | 로그에서 찾을 줄 |
|---|---|---|
| A 카드 표시, 경과 초 멈춤, IDE 멈춤과 오류 알림 없음 | 확정 뒤 카드가 뜨고 clarify 카드의 "경과 N초"가 멈춘다. 카드가 떠 있는 동안 에디터 입력이 막히지 않고 오류 알림이 없다 | `/clarify-confirm 수신` → `정제문 생성 완료(확정 버튼 경로)` → `브리지 판정 … → 카드 요청` → `카드 요청 전송` → `idea.log`의 `Bridge → Webview (subType=refinedConfirmation/request`. 줄 사이 시각 간격으로 멈춤 여부를 본다 |
| B 정제문 수정 후 진행 | "수정한 내용으로 분석"을 누르면 분석이 시작된다 | `웹뷰 응답 수신: submit 수정(N자)` → `응답 결과: 수정(N자)` → `분석 시작 직전 SR(…자, clarify 경유): …` — **SR: 줄의 앞 80자가 고친 문장이어야 한다** |
| C 빈 입력이면 진행 버튼 비활성 | 정제문을 모두 지우면 "이대로 분석"과 "수정한 내용으로 분석"이 모두 비활성이고 경고가 보인다 | 눌러도 `웹뷰 응답 수신` 줄이 **생기지 않아야 한다**(화면 확인) |
| D 취소 | "분석을 취소했습니다." 일반 안내 말풍선이 뜨고(오류 표시 아님) 분석이 시작되지 않는다 | `웹뷰 응답 수신: cancel` → `응답 결과: 취소` → `취소 → 분석을 시작하지 않고 안내…`. 이 rid의 `분석 시작 직전 SR` 줄은 **없어야 한다** |
| E 카드 대기 중 새 /clarify | 이전 카드가 사라지고, 5분이 지나도 이전 요구사항의 분석이 시작되지 않는다 | `/clarify 수신 … 대기 중인 정제문 카드 정리를 호출` → `새 /clarify로 대기 카드 정리 … 대기중=true` → `응답 결과: 새 /clarify가 대신함(분석 안 함)`. 이전 rid의 `분석 시작 직전 SR` 줄이 **없어야 한다** |
| F 진행 버튼 연타 | 분석이 한 번만 시작되고 "세션 만료" 오류 말풍선이 없다 | `분석 시작 직전 SR` 줄이 **1회**, 연타분은 `두 번째 확인을 무시했습니다(확인 진행 중)`. `idea.log`에 `분석 세션이 만료` 오류 전송이 없어야 한다 |
| G(선택) 5분 대기 | 카드가 남아 비활성이 되고 "응답이 없어 원래 문장으로 분석을 진행했습니다."가 보이며 분석이 시작된다 | `응답 결과: 시간 초과(정제문 그대로 진행)` → `분석 시작 직전 SR`(모델이 만든 원래 정제문) |
| H 제외된 후보 되살리기 | 결과 아래 "제외된 후보"에서 되살린 파일이 `/implement` 대상에 포함된다 | `[RefineGate]` 줄은 없다. `Router: /restore 분기`, `analyze_restore` 전송, 이어 `Router: /implement 분기`에서 대상 목록에 해당 파일이 있는지 본다 |
| I 새 /clarify → 제외된 후보 패널 초기화 | 새 `/clarify`를 보내면 이전 결과의 "제외된 후보" 패널이 사라진다(되살리기 상태 초기화) | `/clarify 수신 …` 줄 뒤에 `Router: /clarify 분기`. 패널은 화면으로 확인(`AnalysisSessionMemory.reset`은 로그가 없다) |
| J(추가, B-68 8번) 채팅 저장·불러오기 | 확인 카드가 떠 있을 때나 만료된 뒤 채팅을 저장하고 다시 불러오면 눌러도 반응 없는 카드가 살아 있지 않아야 한다 | `saveChat 수신`, `채팅 저장 성공`, 불러온 뒤 화면. 이 카드는 Stage 3 카드와 같은 기존 성질일 수 있다 |
| K(추가, B-68 6번) 지운 식별자 | 문장에서 식별자(예: 클래스 이름)를 지우고 "수정한 내용으로 분석"을 누르면 분석 입력에 그 식별자가 다시 붙지 않는다 | `분석 시작 직전 SR(…자, clarify 경유)` 줄의 글자 수가 고친 문장 길이와 같고(뒤에 식별자 줄이 붙으면 더 길다), 앞 80자가 고친 문장 |
| L(추가, B-68 7번) 취소 뒤 재시작 | 취소한 뒤 새 `/clarify`가 정상적으로 시작된다 | D의 줄들 뒤에 `/clarify 수신 …` → `정제문 생성 완료` … |

**카드가 안 뜰 때 원인 가르기**

| `[RefineGate]` 로그 | 뜻 |
|---|---|
| 줄이 하나도 없음 | **오래된 빌드**(샌드박스 jar가 09:31 그대로)이거나 stdout 수집 문제. 위 3)의 jar 시각과 `수정한 내용으로 분석` 확인이 `False`면 runIde가 새로 빌드하지 않은 것이다 |
| `정제문 생성 완료`까지만 있고 `브리지 판정`이 없음 | `/clarify-confirm` 뒤 백그라운드 전환에서 예외(`idea.log`의 `Clarify confirm error`) |
| `브리지 null → 카드 없이 진행` | 게이트가 브리지 없이 호출됨(라우터에서는 일어나지 않는 경로, 테스트나 하네스) |
| `브리지 판정 … → 카드 요청`은 있는데 `카드 요청 전송`이 없음 | 카드 전송 코드(EDT 큐 `invokeLater`)가 실행되지 않음(EDT가 막힘 또는 모달 대화상자) |
| `카드 요청 전송`은 있는데 화면에 카드가 없음 | **웹뷰 수신 문제**: `idea.log`의 `Bridge → Webview (subType=refinedConfirmation/request`가 있는지, 웹뷰가 옛 번들(문구 확인 `False`)인지, JCEF 개발자 도구 콘솔 오류를 본다 |
| `카드 요청 전송` 직후 `응답 결과: 결정 없음` | 전송 중 예외(`JcefBridge` 오류)로 곧바로 진행됨 |
| 카드를 눌렀는데 `웹뷰 응답 수신`이 없음 | 웹뷰 → IDE 전송 실패(`JcefMessageHandler`의 `JS → IDE` 줄과 JS 오류 확인) |
| `웹뷰 응답 수신`은 있는데 `응답 결과`가 `새 /clarify가 대신함` 또는 `시간 초과` | 그 사이 새 `/clarify`가 왔거나 300초가 지남 |

#### 4 (2부) 이전 IDE 로그 분석 — 건너뜀
`analyze_prev_20261026.log`가 없어 4-a~4-i는 하지 않았다. 위 1-c의 샌드박스 `idea.log`에 같은 날 13:19~13:29의 실행이 남아 있으므로 해당 구간을 파일로 저장해 주면 같은 방식으로 분석할 수 있다. 이전 IDE 화면 원문도 받지 못해 이 문서에 기록하지 못했다.

#### 결정(B-69 판단)
질문 생성은 운영에 넣지 않는다. 다음 후보: 질문 생성을 Stage 1 시드 탐색 뒤로 옮겨 시드 소스를 근거로 쓰는 방안이며, runIde로 확인 카드를 본 뒤 결정한다.

#### 예상과 다른 점
1. 이전 IDE 로그 파일은 없었지만, 샌드박스 `idea.log`에 오늘 실행 기록이 남아 있었다. 이 IDE 세션이 09:31에 시작해 옛 jar로 계속 돌았다는 것이 "카드가 안 보인 이유"의 확정 근거다.
2. `gradlew test`는 `plugins-test`만 갱신하고 runIde용 `plugins` 샌드박스는 건드리지 않아, 테스트를 반복해도 샌드박스 jar는 갱신되지 않는다.
3. "분석을 취소했습니다" 문구는 웹뷰 번들이 아니라 Kotlin 클래스에 있어서, 웹뷰 번들 검사만으로는 이 문구를 찾을 수 없다.
4. 요청한 "같은 출력 방식"을 기존 `println`으로 맞추면 `idea.log`(STDOUT)와 runIde 콘솔 로그 양쪽에 같은 줄이 남는다.
5. 로그 줄만 추가하라는 제한 때문에 요청 id를 인자로 넘기지 못하고 SR 원문 해시를 rid로 썼다(같은 SR로 반복 실행하면 같은 rid가 되므로 시각으로 구분해야 한다).

### [B-70] clarify 세션 만료 수정(마지막 활동 기준 30분, 다시 시작 버튼), 확인 제출 전 문구 정리, 후보 선정 원인 조사 (2026-10-26)

사전 확인: `git status -s` 비어 있음, HEAD = origin = b1f2e5f. 실서버 LLM 호출 없음(테스트는 모의만, 후보 재현은 LLM 없이 스캐너만 실행). 커밋: 1 = `2ad2b99`, 2 = `ed087bb`.

#### 0 세션 만료 원인(요약)
IDE의 14:47:19 `/clarify-confirm` 직후 오류는 **세션 유효 시간 초과**였다. `AnalyzeSessionManager`의 유효 시간은 5분 고정이었고 **생성 시각(`createdAtMs`)부터** 셌으며 발화나 확정으로 갱신하지 않았다(`getSession`이 만료면 세션을 지우고 null → 라우터 `:216`/`:302`가 같은 고정 오류 문구를 보냄). 세션 생성 14:41:50.3, 확정 수신 14:47:19.895로 329.6초(TTL 초과 29.6초)였고 오류는 수신 줄과 같은 밀리초에 나갔다(요약 응답 14:44:58 기준으로는 141.9초라 활동 기준 TTL이었다면 만료되지 않았을 시간). 이중 확인, 경합, 구 빌드는 로그로 배제했다. 웹뷰는 확정을 누르는 즉시 "✓ 확인이 제출되었습니다"를 띄우고(`App.tsx:341`) 오류가 와도 되돌리지 않아 모순된 화면이 남았다.

#### 1 커밋 1(2ad2b99): 세션 유효 시간과 다시 시작
- **TTL**: 기준을 마지막 활동 시각으로 바꾸고 30분으로 했다(`AnalyzeSessionManager.kt`: `TTL_MINUTES = 30`, `Stage0Session.lastActivityMs`, `isExpired(ttlMinutes, nowMs)`). 활동 = `getSession` 성공, 발화 처리 완료(`WebviewActionRouter.kt`의 `/clarify-utterance`에서 `touch`), 작업 요약 전송(`scheduleTaskSummary`에서 `touch`). 이미 만료된 세션은 `touch`로 되살리지 않는다. 시각은 주입 가능한 `AnalyzeSessionManager.clock`이다(테스트에서 실제로 기다리지 않음).
- **웹뷰**(`clarifySessionLogic.ts` 순수 로직, `App.tsx`의 `ClarifyForm`): 확정을 보낸 뒤 오류를 받으면 `isSubmitted`를 되돌려 "확인이 제출되었습니다" 문구를 걷는다. 세션 만료 오류(고정 문구 일치)면 확정 버튼과 발화 입력을 숨기고 "같은 요구사항으로 다시 시작" 버튼 1개와 안내(이전 발화 목록 표시, 자동 재전송 없음)를 보인다. 버튼은 `wuw:restart-clarify` 이벤트로 `App`이 사용자 말풍선 "/clarify 원문"을 붙이고 `{command: '/clarify', text: 원 요구사항}`을 보낸다. 분석이 이미 시작된 뒤의 오류는 건드리지 않는다.
- **기대값과 검증**(테스트는 이 기대에 맞췄다. **기존 테스트의 기대는 바뀐 것이 없다**: `AnalyzeSessionManagerTest`의 `testSessionExpiration`은 `isExpired(5)`를 명시해 그대로 통과한다):

| 기대 | 확인한 테스트 | 결과 |
|---|---|---|
| s1 생성 4분 뒤 발화 → 그 4분 뒤(생성 기준 8분) 확정 성공 | `AnalyzeSessionTtlTest` s1, s1b(생성 6분) | 통과 |
| s2 마지막 활동 후 29분 확정 성공, 31분이면 null | s2, s2c(경계: 30분 정확히 성공, 1ms 지나면 null) | 통과 |
| s3 만료된 세션은 발화로 되살아나지 않음 | s3(만료 뒤 요약 `touch`와 발화 조회 모두 null), `touch는_살아_있는_세션…`, r1(라우터 소스에 `touch` 2곳) | 통과 |
| s4 웹뷰: 확정 후 만료 오류 → 제출 문구 없음, 다시 시작 버튼 1개, 누르면 `/clarify`에 원문과 같은 문자열 | `clarifySessionLogic.test.ts` 8개, ui-check 27(문구 없음, 버튼 1개, 확정 버튼 없음), 28(이전 발화 목록 1개, 재전송 0), 29(`/clarify`에 원문 1회, 사용자 말풍선, 재클릭 무시) | 통과 |
| s5 정상 확정 흐름의 화면·메시지 순서 불변 | 기존 ui-check 1~26 그대로 통과 + 30(정상 확정: 제출 문구 유지, 분석 시작 도착, 다시 시작 버튼 없음) | 통과 |

#### 2 커밋 2(ed087bb): 확인 제출 전 문구
- **지시 2번 교체**(`Stage0ClarificationEngine.generateTaskSummary`의 시스템 프롬프트): "2. 근거 문구에 없는 역할·기능을 만들지 말 것. 근거가 이름/토큰 일치뿐인 파일은 '이름이 비슷해 포함됨(확인 필요)'으로만 쓸 것. 사용자 제약(범위·대상 화면)과 맞지 않는 파일을 '핵심 변경 대상'이라 부르지 말 것." 1번과 3번은 그대로다.
- **근거 문구 변환**(`agent/clarify/RationaleWording.kt` 새 파일, 호출 위치 `Stage0ClarificationEngine.kt`의 `generateTaskSummary` 후보 줄 `val rationale = RationaleWording.toUserWording(item.anchorRationale ?: "")`): `구조 식별자(...) 공유` → `같은 화면 변수(...) 사용`, `View-Script 페어` → `같은 화면의 스크립트`, `CALLS 연결` → `호출 관계`, `요구사항 토큰(...)과 일치` → `이름이 요구사항 단어(...)와 비슷함`(앞의 "클래스명/엔드포인트가 "는 함께 제거). 등록되지 않은 문구는 원문 유지. 저장된 `anchorRationale`과 후보 선정 규칙은 바뀌지 않는다.
- **진행 문구**(`WebviewActionRouter.kt`의 `analyze_start` 두 곳): "프로젝트 메타그래프 기반으로 요구사항을 구체화하고 있습니다..." → "프로젝트 코드 구조를 바탕으로 요구사항을 구체화하고 있습니다...", "프로젝트 메타그래프 영향도 분석을 시작합니다..." → "프로젝트 코드 구조를 바탕으로 영향받는 파일 분석을 시작합니다...".
- **입력칸 예시**(`App.tsx`): `예: "관리자 화면만 해당이에요", "이메일은 마스킹해서", "분석 시작해줘"`.
- **기대값과 검증**: `RationaleWordingTest` 11개 통과(변환 4종과 한 문장 복합 변환, 등록되지 않은 문구 원문 유지 5개 사례, 모의 클라이언트로 캡처한 요약 프롬프트의 system과 user에 '구조 식별자'·'View-Script'·'CALLS' 없음, 지시 2번 문장과 1·3번 유지, 진행 문구에 "메타그래프" 없음, 입력칸 예시). **하네스 영향 없음**: 하네스가 비교하는 줄(EXACT_MATCH 5, MATCH_EXCLUDING 2)에는 요약 문장이 없고 `CandidateCapTest`(요약 줄 형식), `TaskSummaryDeferTest`도 그대로 통과했다. 실측은 하지 않았다(사용자 IDE 재테스트로 확인).
- **남은 것**: 번역기 프롬프트(`ClarifyUtteranceTranslator`의 후보 컨텍스트)는 이번에 바꾸지 않아 근거 문구 원문이 그대로 들어간다. 등록되지 않은 내부 문구(`INJECTS 연결`, `IMPLEMENTS 연결`, `공유 타입 브릿지(...)를 통한 형제 클러스터 위상 회수`, `...에 바인딩된 MyBatis 매퍼`, `...의 URL(...)을 처리하는 Controller`)는 그대로 요약 프롬프트에 들어간다.

#### 3 읽기 조사 결과
**3-a SR "기존 설문조사 상세 조회 기능에 응답자 이메일 정보도 같이 내려주도록 백엔드를 수정해주세요"의 스캐너 토큰**(survey_admin 그래프, LLM 없이 `Stage0GraphScanner` 실행)
- `KeywordDecomposer.decompose`의 영문 토큰(사전 번역): `detail, info, view, search, list, information, data, update, edit, modify`. 한글 원형 `contentTokens`: `기존, 설문조사, 상세, 조회, 기능에, 응답자, 이메일, 정보도, 같이, 내려주도록, 백엔드를, 수정해주세요`.
- **빈도 필터는 Stage 0 스캐너에 없다**(`Stage0GraphScanner.kt`, `KeywordDecomposer.kt` 전체에 빈도 기준 없음). 토큰을 걸러내는 장치는 세 가지다: (1) 사전 번역 상위 3개 컷(`DomainDictionary.kt:41` `take(3)`): `상세`→detail, info, view(show, single, item 탈락), `조회`→search, list, view(read, select, get, find, lookup 탈락), `정보도`→info, information, data, `수정해주세요`→update, edit, modify(change, alter, patch 탈락). (2) 영문 일반어 불용어(`Stage0GraphScanner.kt:204-210` `genericTokenStopwords`)에 걸려 detail, info, view, search, list, data, update가 후보 매칭용 영문 토큰(`queryDomainTokens`, `:212`)에서 빠지고 **`information, edit, modify` 세 개만 남는다**. (3) 후보 수 상한 `proposalBudget = 8`(`:19`, 운영 라우터는 기본값으로 생성)과 `minSpecificityScore = 1.0`(`:18`).
- **survey·설문 토큰**: `설문조사`는 내장 사전에 번역이 없어(`translate("설문조사") = []`, 외부 `dictionary.json`은 `DomainDictionary.load`에서 꺼져 있음) 한글 개념 토큰으로만 남는다. 영문 `survey` 토큰이 만들어지지 않아 클래스명 경로(엣지 0-B)에서 설문 계열 클래스와 맞지 않고 한글 자연어명/주석 경로(엣지 0)에서만 쓰인다. 탈락이 아니라 번역 부재다.
- **재현**: 스캐너만으로 IDE의 후보 8개가 **8/8 재현되고 순서도 같다**(`engine.initSession`을 LLM 없이 돌려도 8/8). 후보와 근거: survey_list.jsp(구조 식별자 search_s_code, detail_s_idx), survey.list.js(View-Script 페어), IpsController(survey_result.jsp의 URL을 처리), **UserDto(요구사항 토큰 edit, modify와 일치)**, sql_user.xml(UserDao에 바인딩된 매퍼), survey_result.jsp(구조 식별자 search_word), survey.result.js(View-Script 페어), UserServiceImpl(CallbackInfoDto의 CALLS 연결).

**3-b 문구 생성과 번역 사전 위치**
- `요구사항 토큰(...)과 일치`: `Stage0GraphScanner.kt:251`(단독), `:253`(`+ 요구사항 토큰(...) 일치`). 매칭 규칙은 `:241` `matchCount >= 2 || (matchCount >= 1 && (isExactClassMatch || queryDomainTokens.size == 1))`, 점수 `:245`.
- 수정→edit/modify: `DomainDictionary.kt:119` `"수정" to setOf("update", "edit", "modify", "change", "alter", "patch")`(변경은 `:127`), 번역 함수 `translate`(`:24-43`)가 `koreanNoun.contains(key)`로 "수정해주세요"에 "수정"을 맞추고 빈도 동률이면 삽입 순서 상위 3개(update, edit, modify)를 돌려준다. 호출은 `KeywordDecomposer.kt:29`(번역), `:35`(영문 토큰 추가).
- **판단(원인)**: 한글 동사 하나("수정")가 영문 동의어 둘(edit, modify)로 풀려 **둘이 각각 "도메인 토큰 일치"로 세어져 정밀도 규칙 `matchCount >= 2`를 동시에 채운다**. 메서드 이름 토큰에 edit와 modify가 있는 `UserDto`가 후보에 오르고 `UserServiceImpl`, `sql_user.xml`로 번진다(후보 3개의 노이즈).

**3-c 동작어를 일치 토큰에서 뺐을 때의 오프라인 비교**(스캐너만, SR만 사용, 운영 기본값 `proposalBudget = 8`. 제외한 토큰 = edit, modify, update, add, change와 한글 수정, 변경, 추가, 삭제. 후보 수는 스캐너가 돌려준 전체(접힌 저신뢰 묶음 포함)이고 required는 그래프 안에 있는 정답 파일. 스크립트 발화는 쓰지 않았다)

| 시나리오 | 그래프 | required(그래프 내) | 기준 후보 | 기준 required 적중 | 제외 후보 | 제외 required 적중 | 빠진 required | 동작어로만 들어온 후보 | 사라진 후보 수 | 새 후보 수 |
|---|---|---|---|---|---|---|---|---|---|---|
| D1 | survey_admin | 8 | 16 | 7 | 16 | 7 | 0 | 0 | 0 | 0 |
| D2 | apc | 5 | 8 | 2 | 8 | 2 | 0 | 0 | 0 | 0 |
| D3 | apc | 5 | 8 | 1 | 8 | 1 | 0 | 0 | 0 | 0 |
| D4 | ism | 4 | 558 | 0 | 558 | 0 | 0 | 0 | 0 | 0 |
| D5 | survey_admin | 1 | 16 | 1 | 16 | 1 | 0 | 0 | 0 | 0 |
| S1 | member_market | 3 | 16 | 3 | 16 | 3 | 0 | 0 | 0 | 0 |
| S2 | member_market | 4 | 24 | 2 | 24 | 2 | 0 | 0 | 0 | 0 |
| S3 | survey_admin | 4 | 16 | 1 | 16 | 1 | 0 | 0 | 0 | 0 |
| SR-01 | survey_admin | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| SR-02 | survey_admin | 4 | 16 | 1 | 16 | 1 | 0 | 0 | 0 | 0 |
| SR-04 | member_market | 1 | 18 | 1 | 18 | 1 | 0 | 0 | 0 | 0 |
| SR-05 | member_market | 2 | 13 | 0 | 13 | 0 | 0 | 0 | 0 | 0 |
| SR-07 | ism | 2 | 26 | 0 | 26 | 0 | 0 | 0 | 0 | 0 |
| SR-08 | ism | 3 | 623 | 0 | 623 | 0 | 0 | 0 | 0 | 0 |
| SR-11 | apc | 4 | 8 | 0 | 8 | 0 | 0 | 0 | 0 | 0 |
| SR-12 | apc | 2 | 14 | 1 | 14 | 1 | 0 | 0 | 0 | 0 |
| SR-03 | survey_admin | 1 | 16 | 0 | 16 | 0 | 0 | 0 | 0 | 0 |
| SR-06 | member_market | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| SR-09 | ism | 3 | 264 | 1 | 212 | 1 | 0 | 0 | 54 | 2 |
| SR-10 | apc | 1 | 8 | 0 | 8 | 0 | 0 | 0 | 0 | 0 |
| SR-05X | member_market | 2 | 13 | 0 | 13 | 0 | 0 | 0 | 0 | 0 |

요약: **21개 시나리오 모두 required 파일이 빠지는 경우가 0건**이다(적중 수가 같다). 동작어 토큰만으로 들어온 후보도 이 21개에는 없다. 후보 구성이 바뀐 곳은 SR-09 한 곳(후보 264 → 212, 사라진 54개와 새로 생긴 2개, required 적중은 1건 그대로)이다. IDE SR(위 3-a)은 이 세트에 GT가 없어 따로 봤다: 동작어를 빼면 노이즈 4개(UserDto, sql_user.xml, UserServiceImpl)와 함께 **정당한 후보였던 `IpsController`도 사라지고**(예산 8개 경쟁 결과) 후보가 16개로 늘며 DateUtil, sql_address.xml, sql_survey.xml, AlimtalkTemplate* 계열 형제 클러스터가 올라온다. 따라서 동작어 제거는 이 평가 세트에서는 recall을 해치지 않지만 노이즈를 다른 노이즈로 바꾸는 효과이고 단독 해결책으로 보기 어렵다(구현하지 않았다). 한계: 스크립트 발화 이후의 재탐색과 기본 후보 수 8을 넘는 접힌 묶음의 쓰임은 반영하지 않았다.

**3-d `buildClarifyIntentChecked`(`WebviewActionRouter.kt:275`)는 EDT에서 돈다**
- `handleCommand` 전체가 `ApplicationManager.getApplication().invokeLater { … }`(`WebviewActionRouter.kt:62-63`) 안이라 `/clarify-confirm` 분기(`:203`)의 `processTurn`(`:248`)과 `buildClarifyIntentChecked`(`:275`)가 AWT 이벤트 스레드에서 실행된다(JCEF 쿼리 콜백 `JcefMessageHandler.kt:28`이 `router.handleCommand`를 부르고 라우터가 EDT로 넘긴다). `idea.log` 형식은 스레드 이름을 찍지 않아 스레드명은 없고, **스택이 증거**다: 13:22:26,575 `SEVERE - HttpRequests - Assertion failed: Network must not be accessed in EDT or inside read action …`의 스택이 `IdeEventQueue` → `InvocationEvent.dispatch` → `WebviewActionRouter.handleCommand$lambda`(당시 `:264`) → `Stage0ClarificationEngine.buildClarifyIntentChecked`(`:1354`) → `extractConstraintsChecked`(`:1572`) → `OpenAIClient.chat`(`:123`) → `HttpRequests.process`. 이 단언은 `idea.log`에 이번 세션 1회, `idea.1.log`에 12줄(약 6회) 남아 있고, 호출 자체는 그대로 진행되어 IDE 오류 알림이 뜰 수 있다. 같은 위치에서 정제문 호출은 별도 스레드(`refineExecutor`)에서 돈다.
- **IDE 응답 정지 시간 추정**: 확정 수신 13:22:26,564 → `analyze_start` 13:22:31,306 = **4.7초**(이 실행은 구 빌드이며 호출이 빨랐다). 구조상 EDT가 막히는 시간은 최종 제약 추출과 정제문 생성(병렬) 중 긴 쪽이다. B-62 측정의 최종 제약 추출은 호출 중앙 15.9초, 최대 58.8초(SR-08 구간 28초)이므로 **보통 10~30초, 길면 1분 가까이 IDE가 멈출 수 있다**고 추정한다(이번 실행 4.7초는 짧은 대화의 경우). `/clarify-utterance`, `/clarify` 초기화 등은 `executeOnPooledThread`라 해당하지 않는다. 수정하지 않았다.

#### 4 검증(커밋 ed087bb 기준)
| 검증 | 결과 |
|---|---|
| 전체 테스트(서버 의존 14클래스 제외) | 585개, 실패 42, 건너뜀 18. 실패 42건은 기준과 판정 차이 0, 관찰 차이 0 |
| clarify 패키지 | 269개(36 스위트), 실패 1(기존 1건), 건너뜀 3 |
| 하네스 | EXACT_MATCH 5/5, MATCH_EXCLUDING 2/2 |
| 실패 메시지 diff | 0 |
| selftest | 24/24 |
| 웹뷰 로직 테스트 | 43/43(새 `clarifySessionLogic` 8개 포함) |
| ui-check | 30/30(기존 26 + 새 27~30) |
| `npm run build` 산출물 | 커밋 1, 2에 `src/main/resources/webview/index.html`과 `tsconfig.tsbuildinfo` 포함 |
임시 조사용 테스트(`ScratchScannerProbeTest`)는 커밋하지 않고 지웠다.

#### 5 확인 제출 전 개선점 11개
(사용자가 정한 11개 목록은 이 세션에서 받지 못했다. 개선점 1·2는 지시문의 "질문 템플릿(개선점 1·2)"에서, 나머지는 이번 작업의 항목과 관찰에서 정리했다. 번호 대응이 다르면 알려 주면 맞춘다.)

| # | 개선점 | 상태 |
|---|---|---|
| 1 | 근거 없이 나가는 고정 질문(`브랜드/api/연동` 키워드 템플릿) 정리 | 보류(질문 템플릿, 구현 금지) |
| 2 | 질문 형식(보기, 추천 답, 한 번에 하나, 추정은 "~로 보입니다. 맞습니까?") | 보류(질문 템플릿, 구현 금지) |
| 3 | 세션 유효 시간을 마지막 활동 기준 30분으로 | 이번 반영(2ad2b99) |
| 4 | 확정 오류 때 "확인이 제출되었습니다" 문구가 남는 문제 | 이번 반영(2ad2b99) |
| 5 | 세션 만료 시 "같은 요구사항으로 다시 시작" 버튼과 이전 발화 안내 | 이번 반영(2ad2b99) |
| 6 | 작업 요약이 근거에 없는 역할을 지어내는 문제(지시 2번 교체) | 이번 반영(ed087bb) |
| 7 | 요약 근거 문구의 내부 용어(구조 식별자, View-Script, CALLS, 토큰 일치) | 이번 반영(ed087bb). 번역기 프롬프트와 미등록 문구(INJECTS 등)는 보류 |
| 8 | 진행 문구의 "메타그래프" | 이번 반영(ed087bb) |
| 9 | 입력칸 예시 문구 | 이번 반영(ed087bb) |
| 10 | 후보 선정: 한 동사의 동의어가 각각 일치 토큰으로 세어져 노이즈 후보(UserDto 등)가 생김 | 조사 중(원인 확인, 동작어 제거 효과 오프라인 비교 완료, 규칙 수정은 보류) |
| 11 | `buildClarifyIntentChecked`가 EDT에서 돌아 IDE가 멈춤 | 조사 중(원인과 정지 시간 추정 확인, 수정은 보류) |

#### 예상과 다른 점
1. 스캐너에는 "빈도 필터"가 없었다. 토큰을 줄이는 것은 번역 상위 3개 컷, 영문 일반어 불용어, 후보 수 상한 8이다.
2. 노이즈 후보의 직접 원인은 단어 하나가 동의어 둘로 풀려 "2개 일치" 규칙을 채우는 것이다.
3. 동작어를 빼도 21개 평가 시나리오에서는 required 손실이 없었지만 IDE SR에서는 정당했던 `IpsController`가 함께 사라진다.
4. 3-d의 EDT 문제는 단순 추정이 아니라 `idea.log`에 IntelliJ의 SEVERE 단언 스택으로 남아 있었다(이전 세션에도 여러 번).
5. 이번 TTL 수정은 만료 시각만 바꾼 것이 아니라 확정이 실패해도 화면이 "제출됨"으로 굳는 문제까지 같이 있었다.


### [B-71] 확정 경로 EDT 해제, IDE 재테스트(02ef82a) 결함 원인 조사, 후보 선정 규칙 오프라인 비교, 개선점 표 정정 (2026-10-26)

사전 확인: `git status -s` 비어 있음, HEAD = origin = 02ef82a. 실서버 LLM 호출 없음(후보 비교는 스캐너만, R4는 이미 남은 idea.log 덤프 재사용). 커밋: 1 = `c435a58`, 이 문서 = 2번째 커밋. 임시 조사 코드(스캐너 복사본 `ScratchScanner`, `ScratchRuleCompareTest`)는 커밋하지 않고 지웠다.

#### 1 커밋 1(c435a58): `/clarify-confirm`의 정제문 생성을 EDT에서 뺐다
**B-67 정리 정정**: B-67에서 "정제문 생성은 백그라운드"라고 정리한 것은 틀렸다. `handleCommand` 전체가 `invokeLater`(EDT)이고 `/clarify-confirm` 분기의 `buildClarifyIntentChecked`(최종 제약 추출 + 정제문 생성 LLM 호출)가 EDT에 남아 있었다. 이번에 `ClarifyConfirmHandoff`(새 파일)로 옮겼다. 가드(`isHandoffActive`), `getSession`, `processTurn`은 EDT에 남고, 확인 진행 표지(`beginHandoff`)는 백그라운드로 넘기기 **전에** 세운다.

| 기대 | 확인한 테스트(`ClarifyConfirmHandoffTest` 외) | 결과 |
|---|---|---|
| e1 모의 LLM 3000ms에서 EDT 200ms 안에 반환 | e1 | 통과 |
| e2 순서 | e2(실행 순서), e2b(라우터 소스 순서) | 통과. **기대와 다른 점**: 사전 등록 순서는 `buildIntent → beginHandoff`였으나 e4(정제문 생성 중 두 번째 확인 무시)를 지키려면 표지가 먼저여야 해서 `invalidate → beginHandoff → (반환) → buildIntent → removeSession → continueAfterClarify`로 했다 |
| e3 실패 시 같은 형식의 화면 오류 | e3, e3b(실행기 거절), e3c(소스: `error`/"분석을 시작하지 못했습니다: …") | 통과. **이전 동작과 다른 점**: 이전에는 EDT에서 정제문 생성이 예외를 내면 채팅 오류 없이 IDE 예외만 남았다. 이제 기존 `error` 형식 메시지가 나간다 |
| e4 두 번째 확인 무시(로그 1줄, 분석 1회) | e4, e4b | 통과 |
| e5 세션 TTL(s1~s3) 불변 | e5 + `AnalyzeSessionTtlTest` 9/9 | 통과 |
기존 테스트의 기대는 바뀐 것이 없다(`RefinedRequirementGateTest` 19/19, `FinalConstraintFailureNoticeTest` 4/4).

#### 2-a handleCommand EDT 동기 네트워크·대량 I/O 호출 전수
`handleCommand` 전 분기를 훑고(`WebviewActionRouter.kt:62-63`이 `invokeLater`) 비동기 래핑이 없는 LLM·HTTP·파일 호출만 골랐다. 스캔 결과의 가양성(에이전트는 `Task.Backgroundable`/`executeOnPooledThread` 안)은 뺐다.

| 분기 | file:line | 호출 | max_tokens | 시간 근거 | idea.log `Network must not be accessed in EDT` |
|---|---|---|---|---|---|
| `/clarify-confirm` (수정 전) | `WebviewActionRouter.kt:275`(수정 전 번호) → `Stage0ClarificationEngine.buildClarifyIntentChecked` → `extractConstraintsChecked`(`:1576`, 500), `refineRequirement`(`:1501`, 300) | OpenAIClient 동기 2회(병렬) | 500, 300 | **16:13:21.525 수신 → 16:13:39.655 정제문 생성 완료 = 18.1초 EDT 정지**(오늘 재테스트). 13:22:26 → 13:22:31 = 4.7초(짧은 대화) | 현재 `idea.log` 2건(13:22:26, 16:13:21). **이 수정 대상(해결됨)** |
| `/clarify-confirm` | `:248` `processTurn` | LLM은 `userStatement != null`일 때만(확정 버튼은 null) | 해당 시 500 | 호출 없음 | 해당 없음 |
| `/task`(편집기 없음 `:839`, 편집기 있음 `:1020`) | `IntentAnalyzer.analyze` → `IntentAnalyzer.kt:57` `client.chat(systemPrompt, userInput)` | 키워드 매핑이 실패하면 LLM 의도 분류(동기) | 지정 안 함 → 기본 4096(`OpenAIClient.kt:106`; 짧은 호출 임계 1000 초과라 타임아웃도 설정값) | 09-09 08:43:14.988 → 08:43:16.526 = 1.5초, 09-02 16:45:34.774 → 16:45:36.260 = 1.5초, 16:45:36.280 → 16:45:37.347 = 1.1초 | `idea.3.log` 1건(09-09, 스택 `:925`), `idea.8.log` 1건(09-02, 스택 `:897`)(당시 줄번호). 소스 주석 `:1017`의 "LLM 호출 없이 즉시 반환"은 사실이 아님. **고치지 않음** |
| `/saveMarkdown` | `:1308` 이하 `file.writeText` | 파일 저장 대화상자 뒤 소용량 쓰기 | 해당 없음 | 측정 없음(소용량) | 0 |
| `/loadLastChat`, `/loadChat`, `/listChats`, `/deleteChat`, `/saveChat` | `:1436-1495` | `executeOnPooledThread` 안 | 해당 없음 | 수신→라우터 진입이 4.5초(14:34:12.4→16.9), 1.6초(15:33:26.5→28.1)로 늦은 것은 IDE 시작 직후 EDT 대기, 파일 로드는 0.2초(18KB, 6KB) | 0 |
| `/explain`, `/chat`, `/find`, `/task` 에이전트, `/doc`, `/ragdoc`, ImpactAgent | `BaseAgent.execute` 등 | `Task.Backgroundable` | 각 에이전트 | EDT 아님 | 0 |
| `/clarify`, `/clarify-utterance`, `/analyze`, `/implement`, `/fetchModels`, `/testConnection` | 각 분기 | `executeOnPooledThread` | 각 호출 | EDT 아님 | 0 |
전체 샌드박스 로그(`idea.log`~`idea.8.log`)의 단언 발생: **`buildClarifyIntent*`/`extractConstraints*`/`refineRequirement` 경로 10건**(`idea.log` 2, `idea.1.log` 6, `idea.2.log` 2; 모두 `/clarify-confirm`의 정제문 생성, 줄번호는 빌드마다 다름: `:368`은 25d9305·c584460에서 `buildClarifyIntent` 줄로 확인, 나머지는 같은 호출 지점이나 줄번호만 검증하지 못함), **`IntentAnalyzer.analyze` 2건**(`/task`). 호출 한 번이 SEVERE 줄과 Throwable 줄 2줄로 찍혀 줄 수는 그 두 배다.

#### 2-b 사용자 발화 없이 첫 화면에 "요청하신 조건을 해석하지 못했습니다…"와 요약 전송 2회
- **판정: 2ad2b99 회귀는 배제. 문구가 나온 원인은 판단 불가(LLM 출력 문제로 보임).**
- (1) 문구 `Stage0ClarificationEngine.kt:1518`(`constraintFailureNote`), 첫 화면에는 `initSession`의 `:387`(`constraintFailureLine()`)이 대화 첫 턴으로 넣는다. 조건 = 초기 제약 추출 실패(응답이 비었거나 `[Error]`, 예외, JSON 파싱 실패). 사용자 발화가 필요 없다.
- (2) `idea.log`: /clarify 15:45:59, 15:46:51(새 채팅) 둘 다 뒤에 `/clarify-utterance`가 오지 않았다. 발화는 15:49:05에 하나(`{"command":"/clarify-utterance","text":"설문 상세 화면용이고, 이메일은 마스킹해서 내려줘를 보내 주세요."}`)가 두 번째 /clarify 뒤 2분 14초에 왔다. 저장된 채팅 JSON 두 첫 카드의 대화 이력이 모두 그 문구로 시작한다(초기 추출 실패 2회).
- (3) 웹뷰가 `/clarify-utterance`를 보내는 곳은 `handleSendUtterance`(버튼·Enter)뿐이다. 2ad2b99는 오류 수신 상태, 세션 만료 UI, 다시 시작 버튼(`wuw:restart-clarify`, 클릭에서만 `/clarify` 전송)을 더했을 뿐 자동 전송이 없고 일반 `handleSend` `/clarify` 경로는 그대로다. 라우터 변경은 `touch` 호출과 `scheduleTaskSummary`의 `project` 인자뿐이다.
- (4) 14:41(구 빌드): /clarify → analyze_clarify → 요약 1회, 발화 → 요약 1회. 현재 빌드: /clarify당 요약 1회(15:46:30, 15:47:22), 발화 뒤 1회(15:49:34). "두 번"은 /clarify 요청이 두 번이었기 때문이고 요청당 횟수는 두 빌드가 같다. 14:41 첫 카드에는 문구가 없었고(추출 성공) 15:45/15:46에는 있었는데 LLM 응답 본문이 로그에 없어 실패 이유는 알 수 없다.

#### 2-c 패키지 선택 카드
- **임계값**: `RequirementAnalysisPipeline.kt:139-140` `val threshold = 50`, `projectGraph.totalFileCount > threshold`이면 카드(`ScopeSelectionBridge.requestScopeSelection`, `:159`). survey_admin은 132개(재테스트 로그 "132개 파일")라 항상 뜬다. 트리 구성값 `ScopeConfig`(`ScopeSelector.kt:19-25`): `minFilesPerNode=2, maxDepth=15, maxSelections=15, maxFiles=500, warningThreshold=300`. 카드 응답 대기 5분(`ScopeSelectionBridge.kt:28`). 선택이 없거나 취소(null)면 `hardLimit = 10000`(`:197`) 이하는 안내 한 줄만 보이고 전체 그래프로 진행, 초과면 예외.
- **평가 하네스**: `project == null`이면 브리지를 부르지 않는다(`:146-157`). `E2E_TARGET_GT == "OrdrInqrListResponse"`일 때만 주문 도메인 패키지(`/or/`)를 모의 선택하고 그 외는 `null` → 전체 그래프. 즉 하네스는 카드를 보지 않고 전체 그래프로 돈다. (Stage 0 하네스는 엔진만 쓰므로 해당 없음)
- **Stage 1 범위 영향**: 선택하면 `ScopeSelector.buildSubMetaGraph`가 만든 `SubMetaGraph`가 `workingMetaGraph`(`:140`)가 되어 이후 탐색기에 `graph = workingMetaGraph`(`:231`)로만 전달된다. 즉 선택 밖 파일은 탐색기에 보이지 않는다. 하위 그래프가 비면 전체로 되돌린다(`:164-166`). 선택 패키지가 참조하는 외부 파일은 최대 50개를 자동 포함(`:178-186`, 유틸 제외). 오늘 재테스트는 카드를 취소(16:14:48.870 표시 → 16:16:18.896 취소, 90초)해 전체 132개로 진행했다.
- **Stage 0 후보 패키지 사전 체크 가능 위치**: Stage 0 후보는 이미 `domainPackage`를 갖는다(`Stage0ClarificationEngine.kt:128`, `:289`; `BrotherAnalogyScanner.extractDomainPackage` `:178`). `RequirementAnalysisPipeline.kt:143-159`에서 카드를 띄울 때 `clarifyIntent`(파라미터)의 후보 파일 디렉터리를 `buildDirectoryTree` 결과에 미리 체크하는 방식이 가능하다. 다만 지금 `ClarifyIntent`에는 후보 파일 경로 목록이 없다(`anchorTokens`, `excludedFiles` 등만). 구현하지 않았다.

#### 2-d 결과 화면
1. **"(사유: 제공하지 않음)"**: `FileRelevanceVerifier.kt:69` `noticeLines()`의 고정 문자열. 출력 축소 형식(V2)은 제거 후보 번호만 받고 사유를 받지 않는다(`CompactResponse.removedFiles: List<Int>`). 요약(`reasoning`)이 무관 판정 사유를 문장으로 담고 있으나 후보와 연결된 구조 데이터는 없다. 연결 가능한 데이터는 `removedReasons: Map<String,String>`(`:60` 부근, "모델이 준 것만")인데 V2에서는 근거 미제시 제거에만 채워진다.
2. **요약과 파일 표의 출처**: **같은 LLM 응답**이다. Stage 3 호출은 `16:20:35.312 → 16:21:02.954` 한 번(`toolCalls=1, finishReason=stop`)뿐이고, 요약은 `reasoning`, 표의 수정 사유는 같은 응답의 `requiredFiles[].reason`(`FileRelevanceVerifier.kt:291`, `:395`)이다. 한 응답 안의 모순이다.
3. **sql_survey.xml과 응답자 이메일**: `EMAIL`은 `sql_survey.xml`에서 4줄(`:670`, `:841`, `:857`, `:889`)뿐이고 모두 관리자 계정 테이블 `ISSW_ADMIN_USER`(`insertUser` `:830-857`, `updateUser` `:866-889`, 그리고 주석 처리된 `selectUser` `:654-712`)이다. 설문 결과 조회 `selectSurveyRAnsList`(`:1218`, `resultType="HashMap"`)와 `selectSurveyRAnsListCN`(`:1334`), `selectResultList`(`:5`)에는 이메일 컬럼이 없다. 요약의 "기존 select로 이메일을 조회"는 **근거가 없다**. `sql_address.xml`은 시도·시군·동 주소 테이블(`ISSW_ADDRESS_SIDO` 등, `:6-14`)로 이메일도 없고 연락처 주소록이 아니다. (이름 때문에 후보에 올랐을 뿐이다.)
4. **Stage 3에서 제외된 시드 `SurveyDaoImpl`이 메타데이터에 시드로 남는 위치**: `RequirementAnalysisPipeline.kt:460`과 `:482`가 `discoveryResult.metadata.seedClasses`(Stage 1 결과, Stage 3 이후에도 불변)를 그대로 출력한다.
5. **"총 30개 대상 중 3개"의 30**: `RequirementAnalysisPipeline.kt:483-484` `discoveryResult.metadata.totalCandidates + suggestedNewFiles.size`, `totalCandidates`는 `AdaptiveFileDiscovery.kt:61` `expandedFiles.size`(확장·평가 대상 수)다. 화면의 진행 문구 "30개 평가 대상"과 같은 값이며 최종 후보 14개와 다른 수다.

#### 2-e `/restore` 호출
`WebviewActionRouter.kt:425` `/restore` 분기가 있으나 오늘 재테스트 로그에 `/restore` 수신은 **없다**(14:34, 15:33의 `/loadLastChat`만 있음, LLM 호출 없음, 파일 로드 0.2초).

#### 3 후보 선정 규칙 오프라인 비교(스캐너 복사본, LLM 없음, 규칙 변경 아님)
규칙 정의(비교용 복사본): **R0** 현재(`:241`). **R1** 같은 한글 원어(`translate`가 돌려준 영문 동의어들)를 일치 **1개**로 센다. **R2** R1 + 한글 `설문*`을 영문 `survey` 토큰으로도 인정. **R3** R1 + 한글 원어가 후보 파일의 한글 주석·자연어명에 있으면 그 원어를 일치 1개로 인정(클래스 이름 토큰 매칭 보강). **R4** Stage 1 탐색기 Turn 1 검색어 기준 상위 결과(LLM이 필요해 기존 idea.log 덤프 재사용, 덤프가 있는 IDE SR 2건만). 운영 기본값 `proposalBudget = 8`. R0는 B-70 표와 후보 수가 같다(D1 16, D4 558, SR-08 623, SR-09 264 등), IDE SR-B는 IDE의 8개와 같다.

| 시나리오 | 그래프 | required | R0 적중/후보 | R1 적중/후보 | R2 적중/후보 | R3 적중/후보 | R0 대비 잃은 required (R1, R2, R3 공통) |
|---|---|---|---|---|---|---|---|
| D1 | survey_admin | 8 | 7/16 | 7/16 | **8**/16 | 7/16 | 0 |
| D2 | apc | 5 | 2/8 | 2/8 | 2/8 | 2/8 | 0 |
| D3 | apc | 5 | 1/8 | 1/8 | 1/8 | 1/8 | 0 |
| D4 | ism | 4 | 0/558 | 0/**8** | 0/8 | 0/8 | 0 |
| D5 | survey_admin | 1 | 1/16 | 1/16 | 1/16 | 1/16 | 0 |
| S1 | member_market | 3 | 3/16 | **1**/32 | 1/32 | 1/22 | **ChatService.java, ChatRoomResponse.java** |
| S2 | member_market | 4 | 2/24 | **3**/26 | 3/26 | 2/19 | 0 |
| S3 | survey_admin | 4 | 1/16 | 1/**8** | 1/8 | 1/8 | **KmcisServiceImpl.java**(다른 1건으로 대체) |
| SR-01 | survey_admin | 2 | 0/0 | 0/0 | 0/0 | 0/0 | 0 |
| SR-02 | survey_admin | 4 | 1/16 | 1/16 | 1/16 | 1/16 | 0 |
| SR-04 | member_market | 1 | 1/18 | 1/18 | 1/18 | 1/18 | 0 |
| SR-05 | member_market | 2 | 0/13 | 0/13 | 0/13 | 0/13 | 0 |
| SR-07 | ism | 2 | 0/26 | 0/26 | 0/26 | 0/26 | 0 |
| SR-08 | ism | 3 | 0/623 | 0/22 | 0/22 | 0/177 | 0 |
| SR-11 | apc | 4 | 0/8 | 0/8 | 0/8 | 0/8 | 0 |
| SR-12 | apc | 2 | 1/14 | 1/14 | 1/14 | 1/14 | 0 |
| SR-03 | survey_admin | 1 | 0/16 | 0/16 | 0/16 | 0/16 | 0 |
| SR-06 | member_market | 2 | 0/0 | 0/0 | 0/0 | 0/0 | 0 |
| SR-09 | ism | 3 | 1/264 | 1/264 | 1/264 | 1/258 | 0 |
| SR-10 | apc | 1 | 0/8 | 0/8 | 0/8 | 0/8 | 0 |
| SR-05X | member_market | 2 | 0/13 | 0/13 | 0/13 | 0/13 | 0 |
| **합계** | | | **21 / 후보 1681** | 20 / 540 | 21 / 540 | 19 / 672 | R1·R2·R3 모두 **required 3건 이탈**(S1 2건, S3 1건)이고 얻은 것은 S2 +1(R1·R2), D1 +1(R2) |

요약: **셋 모두 required 손실 0이라는 기준을 충족하지 못한다**(S1의 ChatService·ChatRoomResponse, S3의 KmcisServiceImpl이 빠진다). 후보 수는 ism 그래프에서 크게 줄지만(D4 558 → 8, SR-08 623 → 22) 그 일부는 정당한 후보의 손실과 같은 이유(일치 개수가 줄어 규칙 `matchCount >= 2`를 못 채움)이다. R2의 D1 +1은 `survey` 토큰 추가의 효과다. 규칙 수정은 하지 않았다.

**IDE SR 두 건 후보 전체**(survey_admin, 번호는 스캐너 출력 순서). 15번 뒤의 16번째 줄은 파일이 아닌 안내 문구("기존 형제 클러스터…")다.

| 규칙 | SR | 후보 수 | SurveyServiceImpl | SurveyDao | sql_survey.xml | IpsController 유지 | UserDto 제거 |
|---|---|---|---|---|---|---|---|
| R0 | A 설문 기간 연장 | 16 | 없음 | 없음 | 없음 | 유지 | 아니오(포함) |
| R1 | A | 7 | **있음** | 없음 | 없음 | 유지 | 예 |
| R2 | A | 7 | 있음 | 없음 | 없음 | 유지 | 예 |
| R3 | A | 16 | 없음 | 없음 | 없음 | 제거됨 | 아니오 |
| R4 | A(덤프, 정제 SR, 검색어 "Survey Period Valid") | 10 | 있음 | **있음** | **있음** | 없음 | 예(없음) |
| R0 | B 응답자 이메일 | 8 | 없음 | 없음 | 없음 | 유지 | 아니오 |
| R1 | B | 8 | **있음** | 없음 | **있음** | 유지 | 예 |
| R2 | B | 8 | 없음(예산 경쟁에서 `SurveyPartExportCli`가 대신 들어옴) | 없음 | 있음 | 유지 | 예 |
| R3 | B | 8 | 있음 | 없음 | 있음 | 유지 | 예 |
| R4 | B(덤프, 정제 SR, 검색어 "survey response email") | 10 | 있음 | 있음 | 있음 | 없음 | 예(없음) |

- R0 A(16): UserServiceImpl, UserDto, sql_user.xml(mssql), KmcisServiceImpl, CallbackInfoDto, sql_user.xml(mysql), IpsController, ReviewDto, AlimtalkTemplateBatchRunner/Repository/Job, IbCenterApiServiceImpl/IbCenterApiService, AlimtalkChnlDto, AlimtalkTmplDto, 안내 문구. R1·R2 A(7): IbCenterApiServiceImpl, ReviewDto, UserServiceImpl, SurveyDto, KmcisServiceImpl, SurveyServiceImpl, IpsController. R3 A는 R0와 거의 같은 16개(UserServiceImpl, ReviewDto, sql_user.xml, IbCenterApiServiceImpl(2회 중복), SurveyDto, KmcisServiceImpl, UserDto, Alimtalk* 등).
- R0 B(8): survey_list.jsp, survey.list.js, IpsController, UserDto, sql_user.xml, survey_result.jsp, survey.result.js, UserServiceImpl. R1·R3 B(8): survey_list.jsp, survey.list.js, IpsController, sql_address.xml, survey_result.jsp, survey.result.js, SurveyServiceImpl, sql_survey.xml. R2 B(8): R1·R3의 SurveyServiceImpl 자리에 SurveyPartExportCli.
- R4 B(10): SurveyDao, SurveyDaoImpl, SurveyServiceImpl, sql_survey.xml, survey.preview.js, SurveyPartExportCli/Job/Runner, SurveyDto, SurveyQImgDto. 두 번째 검색어 "설문 응답자 이메일"은 7건(ClosingLinkDto, SurveyPartExportRunner, SurveyDaoImpl, ReviewDto, SurveyDto, SurveyQImgDto, SurveyServiceImpl).
- R4의 한계: 덤프가 있는 2건뿐이고, 덤프의 SR은 정제 SR(75자)이며 LLM이 검색어를 만든 결과라 같은 SR이라도 재현을 보장하지 않는다. 21개 시나리오에는 덤프가 없어 R4 required 손실은 **측정하지 못했다**.

**IDE 응답자 이메일 SR의 단계별 교집합**(2026-10-26 16:13~16:21 `idea.log`; Stage 0 후보는 B-70에서 IDE 8개와 스캐너 8/8 일치 확인)

| 집합 | 파일 |
|---|---|
| Stage 0 후보 8 | survey_list.jsp, survey.list.js, IpsController, UserDto, sql_user.xml(mssql), survey_result.jsp, survey.result.js, UserServiceImpl |
| Stage 1 Turn 1 상위 10 | SurveyDao, SurveyDaoImpl, SurveyServiceImpl, sql_survey.xml, survey.preview.js, SurveyPartExportCli, SurveyPartExportJob, SurveyPartExportRunner, SurveyDto, SurveyQImgDto |
| Stage 3 카드 14 | SurveyServiceImpl, SurveyDto, SurveyDaoImpl, SurveyService, AddressDaoImpl, SurveyDao, AddressDao, sql_survey.xml, sql_address.xml, SurveyQImgDto, UserServiceImpl, ReviewDto, ClosingLinkDto, SearchHistRDto |
| 최종 3 | sql_survey.xml, SurveyDto, SurveyServiceImpl |

- Stage 0 ∩ Turn 1 = **0건**, Stage 0 ∩ 카드 14 = 1건(UserServiceImpl, 이름 유사로 포함된 노이즈), Stage 0 ∩ 최종 3 = **0건(0/8)**. 네 집합의 공통 파일은 없다.
- Turn 1 상위 10 ∩ 카드 14 = 6건(SurveyDao, SurveyDaoImpl, SurveyServiceImpl, sql_survey.xml, SurveyDto, SurveyQImgDto). **Turn 1 상위 10 ∩ 최종 3 = 3건(최종 3개가 모두 Turn 1 상위 10 안)**.
- 참고(규칙 비교): R1·R3의 Stage 0 후보는 최종 3 중 SurveyServiceImpl, sql_survey.xml 2건을 포함한다.

**사전 번역이 없는 한글 명사**(23개 SR = 평가 21개 + IDE 2건, 그래프 한글 주석·자연어명에서 부분 문자열로 찾음). 형태소 분석이 없어 `contentTokens`에는 조사 붙은 어절과 용언도 섞여 있어 아래는 사람이 도메인 명사만 고른 목록이다. 부분 문자열 검색이라 짧은 단어(이전, 현재 등)는 넓게 걸린다. 출처: 이 조사의 스캐너 비교 출력(`TEMP` 보관).
- 번역 없음 + 그래프 주석·자연어명에 **있음**: 설문(survey_admin), 발송(survey_admin, apc, ism), 메시지(전 그래프), 재발급(apc), 참여(survey_admin, apc, ism), 판매(ism, member_market), 예약(survey_admin, ism, member_market), 조직(ism), 학교(member_market), 회사(survey_admin, ism), 배너·공지·팝업·노출(apc, ism), 건수·비율(survey_admin/apc/ism), 이미지(전 그래프), 종료·시작·일시(apc, ism), 기간·검증·당일(survey_admin 등), 이메일(apc, ism, member_market), 브랜드(apc, ism), 안내(survey_admin, apc, ism).
- 번역 없음 + 주석에서 **못 찾음**: **설문조사**, 브랜드메시지, 발급업체, 응답자, 금칙어, 익일, 내보내기, 스토어(별), 자정까지(자정). ("비밀번호"는 apc·ism 주석에 있어 위 목록에 해당.)
- 위 두 목록에서 이번 IDE SR에 직접 걸리는 것: `설문조사`(번역 없음, 주석에도 없음 → survey 클래스로 연결할 길이 한글 경로에서도 막힘), `설문`(주석에는 있음, 번역 없음), `응답자`(번역·주석 모두 없음), `이메일`(번역 없음, survey_admin 주석에는 없고 다른 그래프에만 있음).

#### 4 개선점 표(확인 제출 전 1~11은 원래 번호를 유지, 12~23 추가)
상태 열의 "IDE 확인"은 2026-10-26 02ef82a 재테스트에서 화면 또는 로그로 확인했음을 뜻한다.

| # | 개선점 | 상태 |
|---|---|---|
| 1 | 개인정보 질문 템플릿(근거 없이 나가는 고정 질문) | 보류(질문 템플릿, 구현 금지) |
| 2 | 가정 한 줄 | 보류 |
| 3 | 지어낸 설명 차단(요약 지시 2번) | 반영(ed087bb), IDE 확인 |
| 4 | 내부 용어(구조 식별자·View-Script·CALLS·토큰 일치) | 반영(ed087bb), IDE 확인. 남은 것: 번역기 프롬프트, 미등록 문구(INJECTS 등), **Stage 1 탐색기의 "메타그래프"** |
| 5 | 입력칸 예시 | 반영(ed087bb), IDE 확인 |
| 6 | 동작어 이중 계산(한 원어 → 동의어 둘이 각각 일치) | 비교 중(R1: required 3건 이탈로 기준 미충족, 후보 수 1681 → 540) |
| 7 | 설문 계층 누락(사전 번역 없음) | 비교 중(R2: D1 +1이나 required 3건 이탈, 설문조사는 주석에도 없음) |
| 8 | 범위 표현 반영 | 구조 |
| 9 | SCOPE → Stage 1 | 구조 |
| 10 | 화면 재구성 | 구조 |
| 11 | 진행 문구 | 카드 대기 중에도 "분석 진행 중" 문구가 남음(사용자 보고, 로그로는 화면 문구를 확인할 수 없음) |
| 12 | 요약의 무관·확인필요 후보 접기 | 구조 |
| 13 | 정제문 카드에 반영 조건·가정 칸 | 구조 |
| 14 | 카드 대기 상태 문구 | 즉시 |
| 15 | 패키지 선택 임계값·사전 체크·버튼명 | 조사(2-c: 임계값 50, 사전 체크 위치 후보 `:143-159`, `ClarifyIntent`에 후보 경로 없음) |
| 16 | 탐색 힌트 카드 비차단 | 구조 |
| 17 | Stage 0 후보를 Stage 1 방식으로 또는 미표시 | 비교 중(R4: 덤프 2건, Stage 0 ∩ Turn 1 = 0, Turn 1 상위 10이 최종 3을 모두 포함) |
| 18 | Stage 1 "메타그래프"·"(주석 없음)" | 즉시 |
| 19 | Stage 3 카드 기본 끔·근거 표시 | 구조 |
| 20 | 이웃 관계 파일 포함 사유 | 구조 |
| 21 | 요약·표 모순(소스 미확인) | 조사(2-d: 같은 응답 안의 모순, sql_survey.xml에 응답자 이메일 select 없음) |
| 22 | "사유: 제공하지 않음" | 조사(2-d: `FileRelevanceVerifier.kt:69` 고정 문구, V2 형식이 제거 사유를 받지 않음) |
| 23 | 판단 지점을 정제문 카드 하나로 | 설계 |
| 확인 제출 뒤-a | 세션 유효 시간 마지막 활동 기준 30분 | 반영(2ad2b99), IDE 확인(5분 초과 확정 성공) |
| 확인 제출 뒤-b | 확정 오류 때 "확인이 제출되었습니다" 문구 | 반영(2ad2b99), ui-check 확인(IDE에서는 오류가 나지 않아 미관찰) |
| 확인 제출 뒤-c | 다시 시작 버튼·이전 발화 안내 | 반영(2ad2b99), ui-check 확인(IDE 미관찰) |
| 추가 | `/clarify-confirm`의 EDT LLM 호출 | 반영(c435a58), 실 IDE 재확인 필요 |

#### 5 IDE 재테스트(02ef82a) 기록
**판단 지점 5개**(사용자가 직접 결정하거나 기다린 곳, 시각은 `idea.log`)

| # | 지점 | 사용자 행동 | 결과 영향 |
|---|---|---|---|
| 1 | clarify 대화 | 발화 1회(15:49:05) 뒤 확정(16:13:21) | 확정까지 세션 활동 기준 23분 47초. 정제문 생성 중 EDT가 18.1초 멈춤(c435a58 이전 빌드) |
| 2 | 정제문 확인 카드(16:13:39.761 표시) | 69.0초 뒤 **수정 제출**(16:14:48.774, 원문 50자 → 75자) | 분석 SR이 정제문 수정본으로 바뀜(clarify 경유 SR 75자) |
| 3 | 패키지 선택 카드(16:14:48.870) | 90.0초 뒤 **취소**(16:16:18.896) | 전체 132개 그래프로 진행 |
| 4 | 탐색 힌트 카드(L1, 16:16:33.026) | 68.7초 뒤 **취소**(16:17:41.698) | Turn 2~5 계속, 시드 SurveyServiceImpl, SurveyDto, SurveyDaoImpl 확정(16:19:18.699) |
| 5 | Stage 3 확인 카드(16:19:18.883) | 76.4초 뒤 **취소**(16:20:35.302) | 14개 후보 그대로 LLM 검증(27.6초), 최종 3개 |

- Stage 0 후보 8개 ∩ 최종 결과 = **0/8**(위 표).
- **정제문 카드 IDE 첫 표시**: 확인(`refinedConfirmation/request` 16:13:39.761, 응답 `submit 수정(75자)`, `hide`).
- **5분 초과 확정 성공**: 확인(세션 생성 15:46:51 후 26분 30초, 마지막 활동(요약 15:49:34) 후 23분 47초 뒤의 확정이 성공해 오류 없음).
- **접힌 제외 목록 IDE 첫 표시**: `analyze_removed` 메시지가 16:21:03.130에 웹뷰로 전송된 것을 로그로 확인(화면 렌더링은 로그로 확인할 수 없어 사용자 확인 필요). 결과 본문에는 "Stage 3에서 제외된 후보 11개 … (사유: 제공하지 않음)"이 그대로 나왔다.
- 카드 대기 합계: 69.0 + 90.0 + 68.7 + 76.4 = 304.1초(5분 4초)의 사용자 대기 구간(카드 4개 + 대화 확정 전 대기는 제외).

#### 6 검증(커밋 c435a58 기준)
| 검증 | 결과 |
|---|---|
| 전체 테스트(서버 의존 14클래스 제외) | 594개(이전 585 + 새 9), 실패 42, 건너뜀 18. 실패 42건은 기준과 판정 차이 0, 관찰 차이 0 |
| clarify 패키지 | 278개(37 스위트), 실패 1(기존 1건), 건너뜀 3 |
| 하네스 | EXACT_MATCH 5/5, MATCH_EXCLUDING 2/2 |
| 실패 메시지 diff | 0 |
| selftest | 24/24 |
| 웹뷰 로직 테스트 | 43/43 |
| ui-check | 30/30 |
| 웹뷰 변경 | 없음(이번 작업은 라우터와 새 핸드오프 클래스만) |

#### 예상과 다른 점
1. 사전 등록한 e2 순서(`buildIntent → beginHandoff`)는 e4를 만족하지 못해 `beginHandoff`를 앞으로 옮겼다.
2. EDT 네트워크 호출은 `/clarify-confirm` 외에 `/task`의 `IntentAnalyzer` LLM 분류가 하나 더 있다(수정 안 함).
3. 후보 선정 대안 R1·R2·R3 모두 required 3건을 잃어 "손실 0" 기준을 못 채웠다. 후보 수는 크게 줄었다.
4. 요약의 "기존 select로 이메일 조회"는 소스에서 확인되지 않는다(`sql_survey.xml`의 EMAIL은 관리자 계정 쿼리뿐).
5. Stage 1 Turn 1 상위 10건이 최종 3개를 모두 담았지만 Stage 0 후보와는 겹침이 없다.
6. 확정 후 카드가 4개 연속(정제문, 패키지, 탐색 힌트, Stage 3)이라 사용자 대기만 약 5분이다.

#### [B-71 추가] 2-f ~ 2-k 읽기 조사, 개선점 24~26, 재테스트 관찰 (2026-10-26, 코드 변경 없음)

**2-f 16:13 freeze 덤프** (`threadDumps-freeze-20261026-161326-…-Net.poll-18sec`, 덤프 3개 16:13:26/31/36 모두 같은 스택. `report.txt`의 EDT 점유 10500ms)
- EDT 스택: `EdtInvocationEvent → …InvocationEvent.dispatch → WebviewActionRouter.handleCommand$lambda$160(WebviewActionRouter.kt:275)` → `Stage0ClarificationEngine.buildClarifyIntentChecked(:1355)` → `extractConstraintsChecked(:1573)` → `OpenAIClient.chat(OpenAIClient.kt:123)` → `postWithRetry(:271)` → `HttpRequests.process` → `OpenAIClient.chat$lambda$4$lambda$3(:193)`(응답 읽기) → `Socket$SocketInputStream.read` → `sun.nio.ch.Net.poll`(소켓 읽기 대기).
- 어떤 LLM 호출인가: **최종 제약 추출**(`extractConstraintsChecked`, `max_tokens = 500`, 비스트림 호출)이다. 줄번호는 덤프 빌드 기준이며 소스(02ef82a)와 1줄씩 다르다. 로그의 `정제문 생성 완료`가 16:13:39.655로 수신(16:13:21.525)에서 18.1초 뒤이고 덤프 이름의 18sec와 일치한다.
- 판단 근거: c435a58 이후 같은 조건의 freeze는 **사라질 것으로 판단하지만 실 IDE에서 확인하지 않았다**. 근거 = e1(모의 LLM 3000ms에서 핸들러가 200ms 안에 반환), e2b(라우터 소스에서 확정 분기의 `buildClarifyIntentChecked`가 `buildIntent = {` 람다 안 1곳뿐). EDT에 남는 것은 가드, `getSession`, `processTurn`(확정 버튼은 `userStatement == null`이라 LLM 없음)이다. IDE 재확인 항목으로 남긴다.

**2-g SurveyServiceImpl의 AddressDao**
- 위치: 필드 `SurveyServiceImpl.java:68`, 사용 `:97` `this.addressDao.selectAddressList(addressDto)`(`procType` 분기 안, `kind`·`admCode`로 시도/시군/동 목록을 JSON 배열에 담는 화면용 조회). `sql_address.xml`은 `ISSW_ADDRESS_SIDO/SIGOON/DONG`(행정구역)만 조회하고 이메일이 없다.
- 판정(2-d의 3번과 합침): 응답자 이메일은 **주소록 테이블에서 오지 않는다**(Address 계열은 행정구역). `sql_survey.xml`의 설문 결과 조회 쿼리(`selectResultList :5`, `selectSurveyRAnsList :1218`, `selectSurveyRAnsListCN :1334`)에도 EMAIL 컬럼이 없고 EMAIL은 관리자 계정 쿼리(`insertUser`, `updateUser`, 주석 처리된 `selectUser`)뿐이다. 응답자 이메일이 어디에 저장되는지는 이 조사로는 알 수 없다.
- 결론: **Stage 3의 "Address 계열 무관" 판정은 결과가 맞다**(근거는 제공되지 않아 검증 불가였을 뿐이다). **파일 표의 "응답자 이메일 조회 SQL 수정"은 소스와 모순되지 않는다**(현재 select에 이메일이 없으니 SQL 수정이 필요한 쪽이 자연스럽다). **요약의 "기존 select로 이메일 조회, DAO/MyBatis 변경 없이"는 소스로 뒷받침되지 않는다**(맞지 않을 가능성이 높다).

**2-h GUARD-SHADOW 로그 한글**
- 경고 원문(`CompletenessGuardIntegration.kt:114`, `idea.log` 16:21:03.079): `[GUARD-SHADOW] 차단 대상이었으나 shadow 모드라 통과시킴: [역할 누락/PERSISTENCE] DB 데이터 조회/저장 필요 → DAO_INTERFACE 가 REQUIRED 집합에 없습니다. 해당 역할 파일을 포함하세요.; [역할 누락/BUSINESS] 비즈니스 가공/변환 로직 필요 → SERVICE_INTERFACE 가 REQUIRED 집합에 없습니다. 해당 역할 파일을 포함하세요.`
- 원인: **`idea.log` 파일 자체는 정상 UTF-8**이다(UTF-8 검증 통과, 대체 문자 U+FFFD 0건, 위 원문이 그대로 있다). `shadow_logs.jsonl`도 `ShadowLogger.kt:44-48`이 UTF-8로 명시해 쓴다. IDE 시작 로그가 `locale=ko_KR JNU=MS949 file.encoding=UTF-8`(15:33:17)이라, JVM 파일 인코딩은 UTF-8이고 **콘솔·네이티브 인코딩만 MS949**다. 따라서 깨짐은 파일이 아니라 MS949로 해석하는 화면(runIde 콘솔, 또는 UTF-8 파일을 MS949로 여는 뷰어)에서 생긴 것으로 보인다. 사용자가 본 화면은 알 수 없어 그 부분은 판단 불가다. `gradle.properties`는 `-Dfile.encoding=UTF-8`만 주고 `stdout.encoding`은 없다(build.gradle 수정은 금지라 손대지 않았다).

**2-i 오늘 분석 단계별 소요(초, `idea.log`·덤프)**
| 구간 | 시각 | 초 | LLM 대기? |
|---|---|---|---|
| /clarify 수신 → 첫 카드(`analyze_clarify`) | 15:46:51.330 → 15:47:06.923 | 15.6 | 예(초기 제약 추출) |
| 〃 → 첫 요약(`analyze_clarify_summary`) | → 15:47:22.800 | 31.5 | 예(요약 호출 15.9초 추가) |
| 발화 수신 → 제약 응답 | 15:49:05.815 → 15:49:18.452 | 12.6 | 예 |
| 〃 → 발화 뒤 요약 | → 15:49:34.600 | 28.8 | 예 |
| 확정 → 정제문 카드 요청(**EDT freeze 포함**) | 16:13:21.525 → 16:13:39.761 | 18.2 | 예(최종 제약 추출, 소켓 대기) |
| 정제문 카드 대기(**사용자**) | 16:13:39.761 → 16:14:48.774 | 69.0 | – |
| 카드 응답 → 패키지 카드 표시 | → 16:14:48.870 | 0.1 | 아니오 |
| 패키지 카드 대기(**사용자**) | → 16:16:18.896 | 90.0 | – |
| 카드 응답 → Stage 1 시작 | → 16:16:18.931 | 0.04 | 아니오 |
| Stage 1 Turn 1 | 16:16:18.967 → 16:16:32.814(+검색 0.15) | 13.9 | 예 |
| 탐색 힌트 카드 대기(**사용자**) | 16:16:33.026 → 16:17:41.698 | 68.7 | – |
| Turn 2 / 3 / 4 / 5 | 16:17:41.700→58.023 / 58.030→18:15.815 / 18:15.823→38.308 / 18:38.314→19:18.692 | 16.3 / 17.8 / 22.5 / **40.4** | 예(스트리밍 도구 호출) |
| RelevanceScorer 등 후처리 | 16:19:18.705 → 16:19:18.861 | 0.16 | 아니오 |
| Stage 3 카드 대기(**사용자**) | 16:19:18.883 → 16:20:35.302 | 76.4 | – |
| Stage 3 검증 | 16:20:35.312 → 16:21:02.954 | 27.6 | 예 |
| 결과 전송 | → 16:21:03.127 | 0.17 | 아니오 |
확정 수신부터 결과까지 461.6초 중 사용자 대기 304.1초, 시스템 157.0초(그중 LLM 대기 약 156.6초). **가장 긴 구간(사용자 대기 제외)**: 1) Turn 5 40.4초, 2) Stage 3 검증 27.6초, 3) Turn 4 22.5초 — **셋 다 LLM 대기**다. Turn 5는 응답 청크가 189개(Turn 2~4는 70~98개)로 가장 길게 생성했고 중간에 `httpFileSystem` 경고 로그가 있으나 원인과 무관하다.

**2-j saveChat 호출**: 확정(16:13:21)부터 결과(16:21:03)까지 **20회**(같은 구간 직전 클라이언트 단계까지 합치면 이 채팅은 32회). 호출처: `webview/src/App.tsx:1692-1708`의 `useEffect([messages, chatId])`가 `messages`가 바뀔 때마다(스트리밍 청크, 카드 표시·숨김, 단계 메시지) `/saveChat`을 전송하고, `handleNewChat`(`:1716`)이 한 번 더 보낸다. 라우터는 `executeOnPooledThread`로 파일에 쓴다(`WebviewActionRouter.kt:1436-1442`). 같은 내용 반복 여부: 로그에는 메시지 본문이 잘려 있어 바이트 비교는 못 했지만, 16:14:48.796/.803/.896/.916처럼 **120ms 안에 4회**가 몰린 것이 있어 변경분 없는 연속 저장이 포함된 것으로 보인다(디바운스 없음). 제목은 `firstUser.content.slice(0, 20)`이라 첫 사용자 말풍선이 `/clarify 원문`이어서 **"/clarify " 접두어가 제목에 들어간다**(`"/clarify 기존 설문조사 상세 "`).

**2-k 탐색기 추정 → 단정**: `inspect_node_detail`(`AgenticSeedSelector.kt:650-696`)의 결과는 클래스명·경로·유형, `localName`, 한글 주석, **메서드 이름·반환형·파라미터 목록**, 의존·피의존 클래스 이름, API 엔드포인트뿐이다. **메서드 본문과 SQL은 들어가지 않는다**(`MethodSignature`에도 본문 필드가 없다). Turn 3의 "설문 응답(결과) 조회 메서드로 **보입니다**"는 이름(`getSurveyRAnsList`)으로 한 추정이고, Turn 4의 "상세 조회 메서드이며", Turn 5의 "수행하는 서비스 로직"/"처리됩니다"는 새 근거(본문·SQL) 없이 단정으로 바뀐 것이다(그 사이 호출은 `inspect_node_detail`과 `expand_connected_nodes`로 둘 다 이름만 반환).

**재테스트 관찰(사용자 보고 중 로그로 확인된 것)**: "수정한 내용으로 분석" 경로 — `분석 시작 직전 SR(75자, clarify 경유)`과 탐색기 `SR:` 줄이 사용자가 고친 75자 그대로이고 식별자 재부착은 없다(IDE 첫 확인). 사용자가 쓴 마스킹 형식("앞 2자리 + 도메인")이 시드 확정 근거(`confirm_final_seeds` rationale)까지 전달됐다. `sql_survey.xml`은 RelevanceScorer 점수 5로 `RESOURCE_REVERSE_LINK` 구제(VETTED BYPASS)로만 남았고, `AddressDaoImpl`·`AddressDao`는 점수 40에서 `Seed Direct Dependency` 구제로 들어왔다가 Stage 3에서 사유 없이 제외됐다.

**개선점 추가**: 24 탐색기 추정→단정 승격 차단(본문 미확인 시 "추정" 표시) / 구조. 25 로그 한글 인코딩 / 즉시(파일은 정상, 콘솔 MS949; 2-h). 26 saveChat 중복 호출(`messages` 변경마다 전송, 디바운스 없음)·제목의 "/clarify" 접두어 / 즉시.


### [B-72] 즉시 수정 묶음(초기 실패 안내 분리, [ExtractFail] 로그, 카드 대기 문구, 탐색 힌트 카드 표기), 남은 읽기 조사, 개선점 23 설계 초안 (2026-10-26)

사전 확인: `git status -s` 비어 있음. **HEAD = origin = 0db26b1**(지시문의 901dac8이 아니다: 901dac8 뒤에 B-71 추가 문서 커밋 0db26b1을 push했고 901dac8은 그 조상이다). 샌드박스 IDE는 꺼져 있다(`idea` 프로세스 없음, 실행 중인 java는 2개로 `--add-opens`로 시작하는 JVM과 Kotlin 컴파일러 데몬뿐). 실서버 LLM 호출 없음. 커밋: 1 = `8b9703f`, 이 문서 = 다음 커밋.

#### 1 커밋 1(8b9703f) 즉시 수정
- **1a 초기 실패 안내 분리**(`Stage0ClarificationEngine.kt`): `initSession`의 안내(`:387`)가 `initConstraintFailureLine()`의 새 문구 "요구사항에서 조건을 자동으로 정리하지 못했습니다. 분석에는 영향이 없으며, 범위나 조건이 있으면 말씀해 주세요."를 쓴다("파일명으로" 없음). 발화 단계(`:445`, `:1297`)와 최종 추출(`:1454`)의 문구 "요청하신 조건을 해석하지 못했습니다. …파일명으로 다시 말씀해 주세요."는 그대로다. 새 안내도 "이미 안내함" 표시(`constraintFailureNoticeShown`)를 세워 최종 추출 안내가 되풀이되지 않는 기존 동작은 같다.
- **1b `[ExtractFail]` 로그**: `extractFailLogLine(kind, body, error)`가 `[ExtractFail] 종류=<응답 없음|[Error]|예외|파싱 실패> 본문(<전체>자 중 앞 <n>자)=<앞 500자> 예외=…` 한 줄을 `println`(idea.log의 `STDOUT -`)으로 남긴다. 줄바꿈은 `\n` 글자로 바꾼다. 파싱 실패는 기존에는 바깥 `catch`에 섞여 있어 따로 갈랐다. 모델이 빈 목록(`[]`)을 준 경우와 정상 응답은 줄을 남기지 않는다.
- **1c 카드 대기 문구**(`cardWaitLogic.ts` 새 순수 로직, `App.tsx`): 정제문·패키지 선택·탐색 힌트·Stage 3 카드의 요청 메시지가 오면 진행 중 메시지의 상단 문구(`currentStatus`)와 확인 제출 뒤 문구가 "확인을 기다리고 있습니다"로, 카드가 내려가면(`*/hide`, `refinedConfirmation/expired`) "분석을 진행 중입니다"로 바뀐다. 스트리밍이 시작되어 문구가 없는 메시지는 건드리지 않는다.
- **1d 탐색 힌트 카드 표기**(`explorerDisplayLogic.ts`, `L1ClarificationForm.tsx`): 카드의 `message`·`explanation`에서 "메타그래프"를 "프로젝트 코드 구조"로 바꿔 보이고, 후보 옆 "(주석 없음)" 칸은 비운다(값이 있는 한글 이름은 그대로). **프롬프트와 탐색기 입력은 바꾸지 않았다.** 이 변경은 화면 출력(L1 카드)에만 있고 `idea.log`의 `STDOUT` 출력과 도구 결과(LLM 입력)에는 손대지 않았다. 남은 "메타그래프" 화면 문구: 라우터 오류 메시지 `WebviewActionRouter.kt:144, :461, :673, :1626`("메타그래프를 찾을 수 없습니다")와 `:1668`(/explain 쪽 교정 안내).
- **1e `beginHandoff` 뒤 `buildIntent` 실패**: **수정이 필요 없었다.** `ClarifyConfirmHandoff.start`가 실패하면 `endHandoff()`로 표지를 풀고 `onFailure`로 오류 1회를 보내며, `removeSession`은 `buildIntent` 성공 뒤라 **세션은 남는다**. 웹뷰는 확정 뒤 일반 오류(세션 만료 문구가 아닌 것)면 제출 상태를 되돌려 확정 버튼이 다시 보인다(2ad2b99). 다시 확정하면 가드가 통과(표지 해제됨)해 분석이 1회 시작되고 무시 로그는 없다(테스트 x5). **남은 위험**: `buildIntent` 성공 뒤 `continueAfterClarify`가 예외를 내면 세션은 이미 닫혀 있어 다시 확정하면 "세션 만료" 안내와 다시 시작 버튼이 나온다(고치지 않음).

| 기대 | 확인한 테스트 | 결과 |
|---|---|---|
| x1 초기 추출 실패 → 새 문구 1줄, 발화 없음, "파일명" 없음 | `ImmediateFixesB72Test.x1`(응답 [Error], 빈 응답, 해석 불가, 호출 예외 4종: 새 문구 정확히 1번, "파일명" 없음, 발화 실패 문구 없음, 대화 이력에 사용자 턴 없음, `userStatements` 빈 목록) | 통과 |
| x2 발화 추출 실패 → 기존 문구 | `x2`(초기 성공 뒤 발화 실패: 기존 문구 있음, 초기 문구 없음) + `ConstraintExtractionFailureNoticeTest` f1의 발화 부분, `FinalConstraintFailureNoticeTest` g2의 발화 부분 | 통과 |
| x3 `[ExtractFail]` 종류와 본문 앞 500자 | `x3`([Error], 응답 없음, 700자 파싱 실패(500자만, "700자 중 앞 500자"), 짧은 파싱 실패(전체), 호출 예외(`RuntimeException`), 빈 목록 응답은 줄 없음) | 통과 |
| x4 카드 대기/응답 뒤 문구 | `cardWaitLogic.test.ts` 5개(카드 4종 요청 → 대기, 응답 5종 → 진행, 다른 메시지 불변, 제출 뒤 문구, 탐색 힌트 표기), ui-check 31(대기 상단 문구와 제출 뒤 문구 "확인을 기다리고 있습니다", hide 뒤 "분석을 진행 중입니다"), 32(탐색 힌트 카드: "메타그래프" 없음, "프로젝트 코드 구조" 있음, "주석 없음" 없음, 한글 이름 유지) | 통과(ui-check는 탐색 힌트 카드로만 실제 화면 확인, 나머지 카드 3종은 단위 테스트) |
| x5 `buildIntent` 실패 → 오류 1회, 다시 확정하면 분석 1회, 무시 로그 없음 | `ClarifyConfirmHandoffTest.x5`(첫 시도 실패: 오류 1, 세션 안 닫힘, 표지 해제 → 다시 확정: 분석 1, 무시 0, 세션 닫기 1) | 통과 |
| x6 하네스 diff 0 | 하네스 EXACT_MATCH 5/5, MATCH_EXCLUDING 2/2, 실패 메시지 diff 0 | 통과 |

**기존 테스트 기대가 바뀐 것**(이름과 이유): `ConstraintExtractionFailureNoticeTest.f1_failureWithExclusionWordsShowsTheNoticeAndExcludesNothing`(SR 원문 `initSession` 부분)과 `f2_failureWithoutExclusionWordsStillShowsTheNotice`, `FinalConstraintFailureNoticeTest.g2_noticeIsNotRepeatedWhenAnEarlierStepAlreadyShowedIt`의 `t0`(`initSession`) 검사가 초기 안내의 새 문구를 기대하도록 바꿨다(1a로 `initSession` 문구가 분리됐기 때문). 발화·최종 안내를 검사하는 부분은 그대로다.

#### 2 읽기 조사
**2-f ~ 2-k**는 B-71 추가 절에 기록했다(freeze 스택, AddressDao, 로그 인코딩, 단계별 소요, saveChat, 탐색기 근거). 이 작업에서는 2-g를 확장하고(아래) 2-l, 2-m을 새로 조사했다.

**2-g (확장) 응답자 이메일의 출처**
- `SurveyServiceImpl.java:68` `AddressDao addressDao`, `:97` `addressDao.selectAddressList(addressDto)`: `kind`/`admCode`로 시도·시군·동 행정구역 목록을 JSON으로 돌려주는 화면용 조회다. `sql_address.xml:6-14`는 `ISSW_ADDRESS_SIDO/SIGOON/DONG`만 읽는다(연락처 주소록 아님, 이메일 컬럼 없음).
- 응답자를 가리키는 컬럼: 응답 테이블 `ISSW_SURVEY_R`는 `R_IDX`, `S_CODE`, `MT_PR`(발송 메시지 키)로 쓰이고, 응답 목록·상세 쿼리는 `SR.MT_PR = LA.MT_PR`(`sql_survey.xml:1108, 1231, 1347` 등)로 발송 로그 `ita_talk_log`/`em_mmt_log`에 이어진다. 상세 조회(`:1760-1782`)가 함께 고르는 응답자 관련 컬럼은 `h_code`, `h_code_key`, `h_batch_yn`, `mt_pr`, `privacy_yn`, `ip_agreement_yn`뿐이다. **전화번호·이메일·주소록 키 컬럼은 이 매퍼의 어느 쿼리도 고르지 않는다**(`CALLBACK`은 발신번호, `:109, :173, :218, :368, :551`). `EMAIL`은 관리자 계정 쿼리 4곳(`:670, :841, :857, :889`)뿐이다. `SurveyDto.java`에는 이메일·전화번호 필드가 없다(`privacy_display_yn`만).
- **결론: (다) 현재 스키마에 없음**(이 저장소의 매퍼와 DTO 기준). 단서: `ita_talk_log`/`em_mmt_log`는 외부(발송 시스템) 테이블이라 컬럼 정의(DDL)가 저장소에 없어, 발송 수신 번호 같은 컬럼이 있는지는 확인할 수 없다(이메일일 가능성은 낮다고 추정만 한다). (가) 기존 데이터에 있음과 (나) 주소록 조인은 근거가 없다.

**2-l Stage 0 후보 목록(`Stage0State.items`)을 쓰는 곳**

| 소비처 | file:line | 쓰는 것 | 화면에서 목록을 빼면 |
|---|---|---|---|
| 확인 카드(ClarifyForm) 후보 체크·거부 UI | 페이로드 `WebviewActionRouter.kt:172, :261, :328`, 화면 `App.tsx:255-339`(`payload.items`, verdict, 거부 사유) | 후보 전체, verdict, 거부 사유 | **깨짐**: 후보를 직접 거부하는 입력 경로(REJECTED → 제외 목록, `FILE_MISMATCH`/`CONCEPT_IRRELEVANT` 거부 사유 수집)가 사라진다 |
| 작업 요약 | `Stage0ClarificationEngine.kt:29, :396, :540, :1019`(`generateTaskSummary(…, state.items, …)`), 예약 `WebviewActionRouter.kt:1562` | 후보 줄과 근거 문구(요약 프롬프트) | 그대로(요약은 엔진 내부 items로 만든다. 요약 문장이 파일 이름을 인용하지 않게 바뀔 뿐) |
| 발화 번역기 | `Stage0ClarificationEngine.kt:1230` `ClarifyUtteranceTranslator.translate(trimmed, state.items, …)`, 후보 선택 `ClarifyUtteranceTranslator.kt:21-33`(`LIMIT`) | 발화를 후보 파일에 대응 | 그대로(내부 items 사용). 단 사용자가 이름을 몰라 "UserDto 빼줘" 같은 발화를 못 쓰게 된다 |
| 제외 매칭·제안 | `:923`(UI 경로 제외 제안), `:1221-1265`(발화 처리와 `mergeCandidates`), `exclusionView` `:1460-1470` → 라우터 페이로드 `excludedFiles`/`pendingExclusions` | PROPOSED_EXCLUSION, REJECTED | 그대로(발화 경로). "제외할까요?" 질문은 파일 이름이 질문에 보여야 의미가 있어 화면 표기는 필요 |
| ClarifyIntent | `:1368`(REJECTED → `excludedFiles`), `:1404`(PENDING 사용자 발화 → `unresolvedItems`), `:1347`·`:1438`(`seedSet` → `anchorTokens`) | 제외 파일, 미확정 항목, 앵커 | 그대로. `anchorTokens`는 `ClarifyIntentStore`/엔진 밖에서 읽는 곳이 없다(Stage 1에 쓰이지 않음) |
| Stage 1 입력 | `AnalyzeInputResolver.kt:63-75`, `RequirementAnalysisPipeline.kt:298`(`clarifyIntent.excludedFiles` 하드 필터), `:558` | **후보 목록은 넘어가지 않고** `excludedFiles`와 `refinedRequirement`만 | 그대로(후보 목록을 화면에서 빼도 Stage 1 입력은 `excludedFiles`가 비는 만큼만 달라진다) |
| Stage0Contract | `RequirementAnalysisPipeline.kt:293-296`(`rejectedExistingRefs`) | REJECTED 항목 | 그대로(거부가 없으면 비어 있음) |

정리: 화면에서 목록을 빼면 깨지는 것은 **체크박스 거부 입력 경로 하나**이고(발화 경로는 유지), Stage 1 입력·하네스(카드 없음)는 바뀌지 않는다.

**2-m 초기 제약 추출 실패 빈도**
- 이 수정 이전에는 실패 로그가 없었다(`[ExtractFail]`은 이번에 새로 생김). 그래서 직접 센 값은 두 곳뿐이다.
  - 샌드박스 `idea.log` 전체의 `/clarify` 수신: idea.log 4, idea.1.log 8, idea.2.log 13 = **25회**(다른 로그는 0). 실패 여부는 로그에 없어 **분자는 알 수 없다**.
  - 저장된 채팅(`AppData/Roaming/ixpert/chats`) 중 clarify 카드의 `dialogueHistory`가 있는 8개: B-57 이전 5개는 문구가 생기기 전이라 판단할 수 없고, 그 뒤 3개(14:41 채팅 1개 성공, 15:45~15:46 두 채팅 2개 실패 문구 있음) 중 **2/3**. 모두 같은 날 서로 다른 시각의 표본이라 빈도 추정으로는 약하다.
- live_eval 덤프(`build/eval-reports/dumps`, 제약 추출 요청 24건; `initSession`과 발화 추출을 구분하지 않음): 응답 24건 중 **1건이 파싱 실패**(D2, 모델이 `evidence`에 따옴표를 이스케이프하지 않은 깨진 JSON, `finishReason=stop`, 159토큰이라 잘림이 아님), 나머지 23건은 정상(그중 6건은 `[]`). 실패율 약 4%(1/24)다. IDE 15:45~15:46 두 번의 실패는 본문이 로그에 없어 종류를 알 수 없었고, 이제 `[ExtractFail]`이 남긴다.

#### 3 개선점 표(B-71 표에서 바뀐 것)
- **14 카드 대기 상태 문구: 반영**(1c, 8b9703f; IDE 확인 필요).
- **18 Stage 1 "메타그래프"·"(주석 없음)": 반영(탐색 힌트 카드 표기 한정)**(1d, 8b9703f; 라우터 오류 메시지의 "메타그래프"는 남음).
- **24 탐색기 추정→단정 승격 차단**(본문 미확인 시 "추정" 표시): 구조(2-k: `inspect_node_detail`은 이름·시그니처만, 프롬프트 변경이 필요해 이번엔 금지).
- **25 로그 한글 인코딩**: 조사 결과 `idea.log`와 `shadow_logs.jsonl`은 정상 UTF-8이고 JVM은 `file.encoding=UTF-8`, `JNU=MS949`(콘솔)라 **콘솔·화면 해석 문제**. 상태: 조사 완료, 콘솔 `stdout.encoding` 설정은 `build.gradle` 수정 금지로 보류.
- **26 saveChat 중복·제목 "/clarify" 접두어**: 조사 결과 `messages` 변경마다 전송(디바운스 없음, 분석 1회에 20회), 제목은 첫 사용자 말풍선 앞 20자라 접두어가 들어감. 상태: 즉시(미수정).

#### 4 개선점 23 설계 초안: 판단 지점을 정제문 카드 하나로 (구현하지 않음)
**현재 판단 지점 5개**(B-71 IDE 재테스트): clarify 대화·확정, 정제문 카드, 패키지 선택, 탐색 힌트, Stage 3 카드(사용자 대기 약 304초).

**없애는 카드와 정보의 이동처**

| 없앨 것 | 지금 하는 일 | 정보 이동처 |
|---|---|---|
| clarify 파일 요약(ClarifyForm 후보·체크박스) | 후보 파일을 보여 주고 거부 | 파일 목록은 카드에서 빼거나 접힌 "참고(추정)" 한 줄로. 거부는 발화(번역기)로, 이미 제외된 파일은 "반영한 조건" 줄에 이름으로 기록(2-l: 깨지는 것은 체크박스 거부 경로 하나) |
| 패키지 선택 카드(>50개) | 범위를 직접 고르게 함 | "범위" 가정 한 줄로 이동: 예 "설문(survey) 패키지에서 찾습니다. 맞습니까?"(추정). 사용자가 고치면 `ClarifyIntent`의 새 필드(soft 힌트)로 전달해 Stage 1 `workingMetaGraph`를 좁히고, 아니면 지금처럼 전체 그래프(하네스와 같음) |
| 탐색 힌트 카드(L1) | 탐색 중 모호할 때 힌트 요청 | 질문 최대 2개 중 하나(탐색이 모호할 때만). 힌트가 필요 없으면 카드 없이 진행하고 근거는 결과의 접힌 "탐색 근거"로 |
| Stage 3 카드 | 최종 검증 전 14개 후보 확인 | 결과 화면의 접힌 "제외된 후보와 사유"(이미 있는 `analyze_removed`/되살리기)로. 사유는 현재 V2 형식이 받지 않으므로(2-d) 사유 표시는 별도 프롬프트 변경이 필요하다(이번 범위 밖) |

**정제문 카드의 칸**
1. **반영한 조건(발화별)**: 발화 원문 → 처리 결과를 한 줄씩. 예 "'이메일은 마스킹해서 내려줘' → 정제문에 반영", "'알림톡 배치는 건드리지 말고' → 파일 2개 제외". 출처는 `ClarifyIntent.userStatements`, `constraints`(evidence), `excludedFiles`로 모두 이미 있다.
2. **가정**: 정제문 생성 때 모델이 채운 가정 문장. 소스 확인이 없으므로 등급은 항상 **추정**.
3. **질문 최대 2개**: 소스에서 확인한 사실로 만든다. 구조는 B-69 실험(결정적 그래프 조회 + 소스에서 현재 동작 추출 + 질문 생성 LLM 1회). 예(2-g 결론 (다)): "설문 응답 조회 SQL(`sql_survey.xml:1218` `selectSurveyRAnsList`)에 응답자 이메일 컬럼이 없습니다. 이메일을 어디서 가져올까요? (가) 다른 테이블 (나) 새로 저장 (다) 잘 모르겠음".

**근거 등급 표시**
- **확인**: 소스 `file:line`을 인용할 수 있는 결정론 조회 결과(컬럼·메서드·테이블의 있음/없음). 배지 "✓ 확인"과 인용 줄을 함께 보인다.
- **추정**: 이름·구조 유추나 LLM 판단. 배지 "추정", 단정 어미 금지("~로 보입니다. 맞습니까?"). 메서드 본문이나 SQL을 읽지 않은 사실(`inspect_node_detail` 수준의 시그니처 정보)은 항상 추정(2-k, 개선점 24와 같은 규칙).

**Stage 0 파일 목록을 화면에서 뺄 때의 영향**: 2-l 표대로 체크박스 거부 경로만 깨지고 요약·번역기·제외 제안·Intent·Stage 1 입력은 그대로다. 대신 제외하려는 사용자가 이름을 모르는 문제는 "반영한 조건"의 제외 파일 이름과 질문 보기로 메운다.

**평가 방법(기존 하네스(카드 없음)와 비교 가능한 지표, 사전 등록)**
1. 카드가 기본값(응답 없음)이면 현재 동작과 같아야 한다: 하네스 EXACT_MATCH 5 / MATCH_EXCLUDING 2, 실패 메시지 diff 0을 그대로 유지(성공 기준).
2. 카드 경로를 오프라인으로 재생한다(모의 사용자 정책 3종: 기본값 수락, 오라클 응답, 수정 입력). 비교 지표: 최종 파일의 required recall과 정밀도, 제외 정확도(required 손실 0 기준).
3. 판단 지점 수(5 → 1)와 사용자 대기 시간(기준선 304초), 질문 수(≤2).
4. 질문 품질: "확인" 등급 질문의 사실 정확도는 소스 인용과 대조해 자동 채점, "추정" 등급은 오류율을 따로 센다. 기준선 대비 Stage 0 ∩ 최종 교집합(IDE 응답자 이메일 SR의 0/8)이 개선되는지 본다.

#### 5 검증(커밋 8b9703f 기준)

| 검증 | 결과 |
|---|---|
| 전체 테스트(서버 의존 14클래스 제외) | 598개(이전 594 + 새 4), 실패 42, 건너뜀 18. 실패 42건은 기준과 판정 차이 0, 관찰 차이 0 |
| clarify 패키지 | 282개(38 스위트), 실패 1(기존 1건), 건너뜀 3 |
| 하네스 | EXACT_MATCH 5/5, MATCH_EXCLUDING 2/2 |
| 실패 메시지 diff | 0 |
| selftest | 24/24 |
| 웹뷰 로직 테스트 | 48/48(새 5개 포함) |
| ui-check | 32/32(기존 30 + 새 31, 32) |
| `npm run build` 산출물 | 커밋 1에 `src/main/resources/webview/index.html`, `webview/tsconfig.tsbuildinfo` 포함 |

#### 예상과 다른 점
1. 1e는 코드 수정이 필요 없었다(이미 표지 해제와 세션 유지). 대신 재확정 시나리오 테스트(x5)를 추가했고, `continueAfterClarify` 실패 때 세션이 이미 닫혀 있는 남은 위험을 적었다.
2. 지시한 "상단 문구"가 한 곳이 아니라 둘이라 둘 다 바꿨다(진행 중 메시지의 상태 문구, 확인 제출 뒤 문구).
3. 초기 실패 안내 분리로 기존 테스트 3개의 기대가 바뀌었다(위).
4. 2-m의 직접 분자가 없다(이전에는 실패 로그가 없었다). 채팅·덤프 표본으로만 보았고 그것도 `initSession`만 가려내지 못했다.


### [B-73] 제약 추출 구조화 출력, 분석 시작 실패 때 세션 복원과 다시 시도, 소스 사실 기반 질문 시제품 (2026-10-27)

#### 0 사전 확인과 실패율 차이(덤프 1/24 대 IDE 2/3) 원인 후보
- 사전 확인: `git status -s` 비어 있음, HEAD = origin = eed0278. 샌드박스 IDE와 runIde는 실행 중이 아니다(java/idea 프로세스 없음). 코드 커밋: 1 = `68592ec`, 2 = `3977ad2`, 3(실험 코드) = `4292986`, 이 문서 = 다음 커밋. IDE 로그 정리는 하지 않았다.
- **확인한 것**:
  - IDE의 두 실패와 한 성공은 **같은 SR**(기존 설문조사 상세 조회 기능에 응답자 이메일 …)이다(저장된 채팅 세 개의 첫 사용자 말풍선이 같다). 즉 SR이 달라서 생긴 차이가 아니라 같은 SR에서 3회 중 2회 실패다.
  - 성공 빌드(14:41)와 실패 빌드(15:45) 사이에 추출 코드 변경이 없다(`git diff b1f2e5f^ 02ef82a`: 엔진은 요약 프롬프트 5줄뿐, `client/`는 변경 없음). 요청 본문 모양도 같다(`OpenAIClient.kt:101-106` model, messages, stream=false, temperature, max_tokens; 덤프 요청도 temperature 0.1, max_tokens 500, stream false).
  - 덤프의 1/24는 `build/eval-reports/dumps` 한 폴더만 센 값이었다. **전체 덤프 폴더**(`dumps*` 11개)의 제약 추출 호출은 305건, 실패 10건(3.3%)이고 **시나리오별로 보면 D2가 8/21(38%), SR-08이 2/23(응답이 비어 있음)**이며 나머지 시나리오는 모두 0건(D1 0/21, D3 0/21, D5 0/26, S1 0/21, S3 0/26 등)이다. 요청 94개와 응답 94개가 짝으로 있어 실패 응답이 빠졌을 가능성은 낮다(`dumps` 기준).
  - **IDE SR(응답자 이메일)은 어느 덤프에도 없다**(요청 본문 검색 0건). 같은 SR의 기준 실패율을 잰 적이 없다.
  - 실패 본문은 당시 로그에 없었다(`[ExtractFail]`은 B-72에서 처음 생겼다). 그래서 IDE 실패 두 건의 종류(응답 없음, `[Error]`, 예외, 파싱 실패)는 확인할 수 없다.
- **추정(원인 후보)**:
  1. SR 의존: 실패가 D2(38%)와 SR-08에 몰려 있으므로 이 SR도 D2처럼 모델이 `evidence`에 인용문을 여러 개 나열해 따옴표가 깨지는 종류일 수 있다(D2 실패 본문은 `evidence` 값에 따옴표가 이스케이프되지 않은 JSON, `finishReason=stop`, 159토큰이라 잘림이 아니다). 근거는 D2 실패 본문뿐이고 SR-B의 본문은 없다.
  2. 서버 상태: 같은 프롬프트로 성공 1회·실패 2회가 났으므로 서버의 샘플링 비결정성이나 부하(`[Error]` 524 등, `OpenAIClient.kt`의 `[Error]` 반환 경로)일 수도 있다. 덤프에는 `[Error]` 응답이 0건이라 이 후보는 덤프로 비교할 수 없다.
  3. 표본 크기: 덤프 전체 평균 3.3%로 3회 중 2회 이상이 우연히 나올 확률은 약 0.3%라 우연으로 보기 어렵다(SR 의존이 더 그럴듯하다).

#### 1 커밋 1(68592ec) 제약 추출 구조화 출력
**1-a 서버 지원 확인(읽기, 실제 호출 1회)**
- `GET /v1/models`(HTTP 200, 키 없이): 모델 ID `qwen3.8-27b`, `Qwen/Qwen3.8-27B` 등, `max_model_len 65536`. 이 응답에는 구조화 출력 지원 여부 필드가 없다.
- 실제 호출 1회: `POST /v1/chat/completions`에 `response_format {type: json_schema, json_schema: {name, strict: true, schema(배열)}}`를 실어 짧은 SR로 호출 → **HTTP 200, 17.4초, 146토큰, `finish_reason=stop`**, 응답이 스키마 모양의 JSON 배열이고 파싱에 성공했다. 배열을 루트로 하는 스키마도 받아들였다. 응답의 공백이 이상했다(쉼표 앞 줄바꿈) 하지만 파싱에는 영향이 없다. 결론: **지원한다**.

**1-b 구현**: `LLMClient.chatJsonSchema`(기본은 지원 안 함 → null), `OpenAIClient.chatJsonSchema`가 `response_format json_schema`(strict)를 실어 비스트림으로 호출하고 오류·빈 응답·서버 거부면 null을 돌려준다(이 시도의 실패는 `logger.warn`으로만 남겨 IDE 오류로 보이지 않는다). `Stage0ClarificationEngine.extractConstraintsChecked`가 스키마 호출을 먼저 하고, null이면 `[ExtractSchema] 구조화 출력 응답 없음 → 기존 호출로 대체`를 남기며 기존 `chat`과 파싱 경로로 간다. 스키마(`CONSTRAINT_SCHEMA`): 배열, 항목 `kind`(`ConstraintKind` 값 enum), `value`, `rawStatement`, `evidence` 모두 필수, `additionalProperties false`. **제약 추출 호출에만** 적용했고 다른 호출은 바뀐 것이 없다. 환각 가드(evidence가 발화에 있어야 kind를 유지)와 파싱 경로는 그대로다.

**1-c 테스트**(`ConstraintStructuredOutputB73Test` 5개, 모의 클라이언트와 가짜 서버)
| 사례 | 확인 | 결과 |
|---|---|---|
| 이스케이프 안 된 따옴표 응답(B-72 2-m D2 실패 본문) | s1: 기존 경로는 같은 깨진 JSON을 주지만 구조화 응답이 먼저 쓰여 실패 안내·`[ExtractFail]` 없음, 기존 추출 호출 0회. s2: 서버가 스키마를 받지 않으면(null) 기존과 같이 `종류=파싱 실패`와 초기 안내(불변) | 통과 |
| 정상 응답 | s3: 구조화 정상 응답 성공, `[ExtractFail]` 없음 | 통과 |
| 서버 거부 응답 | s3: 거부(null) 뒤 기존 호출이 정상이면 성공(기존 추출 호출 1회), s5: 가짜 서버 HTTP 400 → `chatJsonSchema`가 null(error 로그 없음) | 통과 |
| 스키마 내용·요청 본문 | s4: 배열·enum·필수 필드·additionalProperties false, s5: 요청에 `response_format json_schema`(name, strict, schema) 있고 기존 `chat`에는 없음 | 통과 |

**1-d 실측(실행 전 예측은 `build/eval-reports/pred_b73.md`에 먼저 적었다. 실행 시각 08:02:41 ~ 08:04:14, 이 작업 중 runIde는 없었다)**: B-72 2-m 실패 사례(D2_run1_c13)의 프롬프트에 `json_schema`를 붙여 5회 호출.
| 회차 | HTTP | 시간(초) | 토큰 | 파싱 | 제약 수 | evidence가 발화에 있음 |
|---|---|---|---|---|---|---|
| 1 | 200 | 18.8 | 155 | 성공 | 1 (OTHER) | 1/1 |
| 2 | 200 | 18.5 | 155 | 성공 | 1 | 1/1 |
| 3 | 200 | 18.5 | 155 | 성공 | 1 | 1/1 |
| 4 | 200 | 18.7 | 156 | 성공 | 1 | 1/1 |
| 5 | 200 | 18.1 | 151 | 성공 | 1 | 0/1 (환각 가드가 OTHER로 강등할 사례, 이미 OTHER) |
**파싱 성공 5/5, 호출 시간 18.1~18.8초(중앙 18.5초)**. 비교: 같은 프롬프트의 기존 호출은 덤프에서 19.2초였고 D2는 전체 덤프에서 8/21이 실패했다. **스키마 없는 호출을 같은 시각에 다시 재지는 않았다**(지시가 5회였고 기준은 덤프 기록이다). 사전 예측과의 대조: P1(5/5) 맞음, P2(10~25초, 60초 미만, 거부·타임아웃 0) 맞음(18.1~18.8초), P3(제약 1개 이상, evidence 4/5 이상) 맞음(4/5), P4(공백이 이상해도 파싱 가능) 맞음, P5(5회가 서로 다른 내용일 수 있음)는 **틀림 쪽에 가깝다**: 4회는 길이 299자의 같은 응답이고 1회만 짧았다.

#### 2 커밋 2(3977ad2) 분석 시작 실패 때 세션 복원과 다시 시도
- 정정: 세션 제거는 백그라운드 전환 전이 아니라 정제문 생성 뒤(`buildIntent` 다음)에 있었고, 그 뒤 `continueAfterClarify`(정제문 확인 카드와 분석 시작)가 실패하면 세션이 이미 닫혀 있어 재확정이 "세션 만료"가 되었다.
- 변경: 세션 제거 위치는 그대로 두고(카드 대기 중 발화가 닫힌 세션을 건드리지 않게 하는 기존 동작 유지) **실패하면 세션을 되돌린다**. `ClarifyConfirmHandoff.Steps.restoreSession`(기본 아무 것도 안 함)을 `removeSession` 뒤 실패에서만 부르고, 순서는 세션 복원 → 표지 해제 → 오류 알림이다. `AnalyzeSessionManager.restoreSession(project, session)`은 같은 객체를 되돌리되 **이미 만료됐거나 그 사이 새 세션이 생겼으면 되돌리지 않고**, 되돌리면 활동 시각을 갱신한다. 라우터는 `restoreSession = { …restoreSession(project, session) }`를 넘긴다.
- 웹뷰: 확정 뒤 오류가 오고 세션 만료가 아니면(`isRetryableConfirmFailure`) 확정 버튼이 "다시 시도"(`data-clarify-retry`)가 되어 같은 정제문과 판정으로 `/analyze-confirm`을 다시 보낸다. 발화는 자동 재전송하지 않는다.
- 테스트(모두 통과): `ClarifyConfirmHandoffTest.x7`(분석 시작 실패 → `removeSession, restoreSession, endHandoff, onFailure` 순서, 표지 해제, 다시 확정하면 분석 1회, 무시 0), `x7b`(정제문 생성 실패는 복원 없음), `x7c`(라우터 소스에 `restoreSession`), 기존 `e4`(두 번째 확인 무시) 불변, `AnalyzeSessionTtlTest.r2/r3`(복원 뒤 활동 시각 갱신, 만료·새 세션이면 복원 안 함) + 기존 TTL 테스트 불변, 웹뷰 로직 테스트 2개, ui-check 33(실패 뒤 "다시 시도" 1개, 제출 문구 없음, 다시 시작 버튼 없음, 재확정 2회째 전송, 발화 재전송 0, 다시 제출 문구).
- **남은 위험**: (1) 분석이 시작된 뒤 비동기 파이프라인(`executeAnalyzePipeline`의 백그라운드)이 실패하면 세션은 이미 닫혀 있고 "다시 시도" 대상이 아니다(B-70의 "분석이 이미 시작된 뒤의 오류는 건드리지 않는다"). (2) `continueAfterClarify`의 동기 실패를 실제 IDE에서 일부러 만들 방법이 없어 단위 테스트와 ui-check로만 확인했다.

#### 3 커밋 3(4292986) 개선점 23 1단계: 소스 사실 기반 질문 시제품(실험 코드, 운영 연결 없음)
- 위치와 범위: `src/test/kotlin/.../agent/eval/SourceFactQuestionProbe.kt`와 `SourceFactQuestionProbeTest.kt`(테스트 소스에만 있음, LLM 호출 없음, 로컬 소스가 없으면 건너뜀).
- 방식: SR에서 사전(이메일, 전화번호, 생년월일, 비밀번호, 계좌번호, 카드번호)의 데이터 명사와 그 앞 단어(예 "응답자")를 찾고, **시드 파일의 패키지 디렉터리의 자바 파일과 도메인 토큰(시드 클래스 이름의 camelCase 토큰)이 이름에 있는 리소스(매퍼)를 디스크에서 실제로 읽어** 명사 패턴이 있는 줄을 모은다. MyBatis 매퍼는 문장(select/insert/update/delete) 단위로 쓰는 테이블과 주석 처리 여부를 분류하고 도메인 테이블(이름에 도메인 토큰)에 있으면 "이미 있음"으로 질문하지 않는다. 화면 스크립트처럼 업무를 줄만으로 가릴 수 없는 일치는 "분류하지 못함(추정)" 참고로만 붙이고 근거에는 넣지 않는다. **"✓ 확인" 등급은 질문이 기대는 범위 파일을 모두 읽었을 때만** 붙고 하나라도 못 읽으면 "추정"이다. 질문에는 근거 `file:line`만 붙고 후보 파일 목록은 넣지 않으며 정답 출처는 판정하지 않는다(선택지 (가) 다른 테이블 조인 (나) 새로 저장 (다) 잘 모르겠음).
- **R-EMAIL(survey_admin, IDE에서 쓴 SR, 시드 = Stage 1 확정 시드 SurveyServiceImpl, SurveyDto, SurveyDaoImpl)**: 범위 파일 26개를 모두 읽고 질문 1개: "[✓ 확인] 응답자 이메일 정보는 이 기능 코드에서는 확인되지 않고, 다른 업무(ISSW_ADMIN_GRADE, ISSW_ADMIN_USER, ISSW_COMPANY) 코드에만 있습니다. 응답자 이메일을(를) 어디서 가져올까요? (가) 다른 테이블에서 조인 (나) 새로 저장 (다) 잘 모르겠음 [근거: sql_survey.xml:670(주석 처리됨), sql_survey.xml:841, sql_survey.xml:857, sql_survey.xml:888, sql_survey.xml:889] (참고, 추정: 화면 스크립트에도 같은 명사가 있으나 어느 업무인지 분류하지 못했습니다: survey.list.js:1484, survey.list.js:1795, survey.list.js:1902)".
| 합격 기준 | 결과 |
|---|---|
| (a) 응답자 이메일 출처를 묻는 질문 1개 | 통과(질문 1개, 명사 이메일, 앞 단어 응답자, 등급 ✓ 확인, 범위 파일 미독 0) |
| (b) 근거로 sql_survey.xml의 EMAIL이 ISSW_ADMIN_USER 쿼리에만 있다는 사실 인용 | 통과(EMAIL 줄 670 주석 처리된 selectUser, 841·857 insertUser, 888·889 updateUser 모두 ISSW_ADMIN_USER 문장, 설문 쿼리의 일치 0) |
| (c) 정답 출처는 판정하지 않음 | 통과("잘 모르겠음" 선택지, 단정 표현과 "주소록" 없음, 질문 본문에 파일 이름 없음) |
- **기존 시나리오**(survey_admin과 member_market의 11개 시나리오): **질문 0개, 거짓 확인 0건**. 다만 **11개 중 데이터 명사가 사전에 있는 SR은 1개(SR-01의 "비밀번호")뿐이어서 이 점검은 거의 비어 있다**(SR-01은 정답 시드 범위에 비밀번호가 이미 있어 질문하지 않음, Stage 0 후보 범위는 시드가 없어 건너뜀). 그래서 사전 명사 6개를 각 시나리오 범위에 모두 물어 보는 반사실 점검도 했다: 정답 시드 범위 11개 × 6명사 = 66쌍 중 질문 49(모두 ✓ 확인, 추정 0), **거짓 확인 0**(정답 파일의 활성 줄에 그 명사가 있는데 없다고 확인한 경우), Stage 0 후보 범위 9개 × 6 = 54쌍 중 질문 32(모두 ✓ 확인), 거짓 확인 0.
- **참고(합격 기준 아님)**: 시드를 clarify 시점에 쓸 수 있는 Stage 0 후보(IDE 8개 중 자바 3개: IpsController, UserDto, UserServiceImpl)로 바꾸면 이메일이 UserDto에 있어 **질문이 0개가 된다**. 이 시제품은 도메인 범위를 시드로 받으므로 clarify 시점의 Stage 0 후보로는 R-EMAIL 질문을 만들 수 없다.
- **운영 투입 판단(연결하지 않음)**: 지금은 **투입하지 않는다**. 이유: (1) 범위를 정하는 시드가 필요한데 clarify 시점에는 Stage 0 후보뿐이고 그것으로는 질문이 사라진다(Stage 1의 Turn 1 검색 같은 도메인 판별 단계가 먼저 필요, 약 14초 LLM). (2) 데이터 명사 사전이 좁아 기존 SR 11개 중 1개에만 걸린다. (3) 거짓 확인 0건은 "정답 파일에 그 명사가 있는데 없다고 말했는가"라는 한 가지 기준이고 SR-B류 질문의 유용성은 IDE에서 사람이 판단해야 한다. (4) 화면 스크립트의 일치는 분류하지 못한다.

#### 4 검증(커밋 4292986 기준)
| 검증 | 결과 |
|---|---|
| 전체 테스트(서버 의존 14클래스 제외) | 611개(이전 598 + 새 13), 실패 42, 건너뜀 18. 실패 42건은 기준과 판정 차이 0, 관찰 차이 0 |
| clarify 패키지 | 292개(39 스위트), 실패 1(기존 1건), 건너뜀 3 |
| 하네스 | EXACT_MATCH 5/5, MATCH_EXCLUDING 2/2 |
| 실패 메시지 diff | 0 |
| selftest | 24/24 |
| 웹뷰 로직 테스트 | 50/50(이전 48 + 새 2) |
| ui-check | 33/33(이전 32 + 새 33) |
| `npm run build` 산출물 | 커밋 2에 `index.html`, `tsconfig.tsbuildinfo` 포함 |
**기존 테스트 기대값 변경: 없음**(새 테스트만 추가, 기존 클래스에는 테스트를 더했을 뿐 기존 단언을 바꾸지 않았다). 개선점 표: 27 제약 추출 구조화 출력 — 반영(68592ec, IDE 확인 필요), 28 분석 시작 실패 뒤 세션 복원과 다시 시도 — 반영(3977ad2, IDE 재현 불가), 29 소스 사실 기반 질문 — 실험(투입 보류).

#### 사전 예측과 맞은 것, 틀린 것
- 맞음: 1-d의 P1~P4. 지원 확인(1-a)에서 배열 루트 스키마가 거부될 수 있다고 걱정했으나 받아들여졌다.
- 틀림: P5(응답 내용 변동)는 거의 변동이 없었다. 사전에 없던 발견: 실패는 소수 SR(D2 8/21, SR-08 2/23)에 몰려 있고 덤프 1폴더 기준 1/24는 표본이 좁았다(전체 덤프 평균은 3.3%).
- 지시문 기대와 다른 점: 0번의 "덤프 실패율 1/24"는 한 폴더만 센 값이라 위처럼 정정했다. 2번은 "세션 제거를 성공 뒤로 옮기거나 복원" 중 복원을 택했다(카드 대기 중 발화가 닫힌 세션을 건드리지 않게 하는 기존 동작을 유지하려고).

#### 미확인 사항
- IDE에서의 실제 동작 전부(구조화 출력, 새 안내 문구, 카드 대기 문구, 다시 시도 버튼).
- IDE 응답자 이메일 SR의 추출 실패 종류(본문 로그 없음)와 구조화 출력 뒤 이 SR의 실패율.
- 스키마 호출이 서버 부하나 모델 변경에서도 같은 효과를 내는지(5회 표본, 같은 프롬프트).
- 소스 사실 질문의 업무 유용성(사람 판단), 사전 밖 명사, 다른 그래프(apc, ism)에서의 동작.
- `ita_talk_log`/`em_mmt_log` 외부 테이블의 컬럼(B-72 2-g의 단서).

#### IDE에서 직접 확인할 항목
1. 같은 SR(응답자 이메일)로 `/clarify`를 3회 이상: 첫 화면에 초기 실패 안내가 나오는지(이전 3회 중 2회), `idea.log`에 `[ExtractSchema]`(대체) 또는 `[ExtractFail]` 줄이 남는지와 그 본문.
2. 첫 화면의 새 초기 실패 안내 문구("요구사항에서 조건을 자동으로 정리하지 못했습니다…")와 카드 4종 대기 중 상단 문구("확인을 기다리고 있습니다"/"분석을 진행 중입니다"), 탐색 힌트 카드의 "프로젝트 코드 구조" 표기와 빈 "(주석 없음)" 칸(B-72).
3. 확정 직후 EDT 정지가 사라졌는지(정제문 생성 중 IDE 조작 가능 여부, c435a58).
4. 분석 시작 실패 뒤 "다시 시도"(동기 실패를 일부러 만들 방법이 없어 IDE 재현은 어렵다).
5. 소스 사실 질문 시제품은 연결하지 않았으므로 IDE 확인 대상이 아니다.


### [B-74] 도구 호출 실패 원인 조사와 빈 응답 처리, 대체 경로 화면 처리, 탐색기 점수·화면 확장 조사, R-EMAIL-PIPE (2026-10-27)

#### 0 사전 확인과 IDE 로그
- 사전 확인: `git status -s` 비어 있음, HEAD = origin = 7b62a0a. 샌드박스 `idea.log`를 `build/ide-logs/b73_ide_20261027.log`(8.28MB, 커밋하지 않음)로 복사했다. 이 로그는 2026-10-26 이어서 쌓인 누적 파일이라 오늘 구간은 `2026-10-27 08:26:09` 시작부터다. 이번 IDE 테스트(08:26~08:42)의 채팅은 `chat_1793057322417`이다. 로그를 복사한 뒤에도 IDE가 계속 쓰여(08:48:39에 IDE 프로세스를 다시 시작, 09:14~09:16에 `/clarify`와 분석 1회) 그 구간은 현재 `idea.log`에서 읽었다.
- **커밋 순서 이탈**: 지시한 순서는 1, 2, 4, docs인데 "커밋 1에 포함"하라는 보충 지시가 커밋 2와 4를 만든 뒤에 왔다. 이미 만든 로컬 커밋을 고칠 수 없어(`--amend`·force 금지) 순서가 2(`36b1a19`), 4(`cb4ac89`), 1(`b4c7d88`), docs가 되었다. 내용은 지시대로 나눴다.
- **오늘 `[ExtractSchema]`, `[ExtractFail]`, `UI was frozen` 줄(회차별)**

| 회차 | 시각 | 내용 | `[ExtractSchema]`(대체) | `[ExtractFail]` | `UI was frozen` | LLM 시간 |
|---|---|---|---|---|---|---|
| 1 | 08:28:52 `/clarify` | 초기 제약 추출(구조화 호출) | 0 | 0 | 0 | 11.7초(08:28:53.066~08:29:04.804) |
| 2 | 08:33:01 `/clarify` | 〃 | 0 | 0 | 0 | 11.3초 |
| 3 | 08:34:54 `/clarify` | 〃 | 0 | 0 | 0 | 11.2초 |
| 3-발화 | 08:36:18 `/clarify-utterance` | 발화 제약 추출 | 0 | 0 | 0 | 14.2초 |
| 3-확정 | 08:37:40 `/clarify-confirm` | 정제문 생성 → 카드 요청(08:38:02.479) | 0 | 0 | **0**(EDT 해제 후 22.4초 대기에도 정지 경고 없음) | 22.4초 |
| 분석 | 08:39:45~08:41:53 | 탐색기 Turn 1, 검증기 | – | – | 0 | 아래 1 |
| 별도 프로세스 | 09:14:52 `/clarify` | 초기 추출 | 0 | 0 | 0 | 12.2초 |
`[ExtractSchema]`가 없다는 것은 구조화 호출이 매번 null이 아닌 응답을 받았다는 뜻이고(실패하면 이 줄이 찍힌다), `[ExtractFail]`이 없다는 것은 세 회 모두 초기 추출이 실패하지 않았다는 뜻이다(실패 경로는 모두 이 줄을 먼저 찍는다). 전날 같은 SR이 3회 중 2회 실패했던 것과 대조된다. 이 로그 전체에서 `UI was frozen`은 어제 16:13:39의 18131ms 한 줄뿐이다. 분석 단계의 도구 호출 실패(`chunks=0`)는 아래 1에서 다룬다.

#### 1 도구 호출 실패 원인(최우선)
**오늘 로그에서 확인한 사실**: 도구 호출 응답이 `chunks=0, toolCalls=0, finishReason=null`로 끝난 것이 **세 번**이다. (1) 08:40:50.592 → 08:40:51.134(0.54초) 탐색기 Turn 1, (2) 08:41:47.868 → 08:41:53.281(5.4초) `FileRelevanceVerifier`, (3) 09:16:09.656 → 09:16:10.048(0.39초) 탐색기 Turn 1(다른 IDE 프로세스, 원문 SR). 어제 16:13~16:21 분석은 탐색기 Turn 1~5와 검증기가 모두 정상이었다. `chunks=0`은 서버가 SSE 데이터 줄을 하나도 보내지 않았다는 뜻으로 모델이 "도구 없이 끝낸" 것(그 경우 내용 청크가 있고 `finish_reason=stop`)과 다르다.

**1-a 읽기: 68592ec가 공용 요청 생성을 바꿨는가** — 아니다.
- `git diff eed0278 HEAD -- OpenAIClient.kt`에는 도구 호출 함수가 없다. 68592ec는 `chat`을 `chatInternal`로 옮기고 `chatJsonSchema`를 더했을 뿐이다. `response_format`은 `OpenAIClient.kt:139`(`chatInternal`, `responseFormat != null`일 때만)에서만 붙고 그 호출자는 `chatJsonSchema` 하나이며 `chatJsonSchema`의 호출자는 `Stage0ClarificationEngine.kt:1607`(제약 추출) 하나뿐이다(`grep chatJsonSchema`). 도구 호출 본문은 `chatWithToolsOnce`(`:357` 이하)에서 따로 만들고 `tools`/`tool_choice`는 호출부 인자로만 들어간다(`:392`, `:394`). 즉 제약 추출 밖으로 새는 `response_format`·`tool_choice` 변화 경로는 없다.

**1-b 재현**(탐색기 Turn 1, `AgenticSeedSelector.selectSeeds`의 첫 `chatWithTools`, 가짜 중계 서버로 요청·응답 기록, 정제문은 지시문의 것, 실행 08:53~08:57, 사전 예측은 `build/eval-reports/pred_b74.md`)
- HEAD(7b62a0a)의 `OpenAIClient`와 eed0278의 `OpenAIClient`(`git show`로 가져온 테스트용 복사본) 각 5회: **10/10 모두 도구 호출 3개(`search_graph_nodes`), finish `tool_calls`, HTTP 200, 응답 10937바이트, 13.8~14.0초**. 요청 본문은 HEAD끼리, eed0278끼리, HEAD와 eed0278 모두 **바이트까지 같다**(4161자). 응답 원문 앞부분은 `data: {"id":"chatcmpl-…","object":"chat.completion.chunk",…,"delta":{"role":"assistant","content":""}…`(role만 있는 첫 청크)로 시작했다.
- 사전 예측과 대조: P1(요청 본문 동일) 맞음, P2(10/10 도구 호출) 맞음, P3(실패가 나오면 같은 비율)은 실패가 없어 판정 불가, P4(10~30초) 맞음(13.8~14.0), P5(첫 청크에 도구 호출 또는 reasoning) **틀림**: 첫 청크는 role만 있고 빈 content였다.
- **stream=true와 stream=false 비교**(보충 지시, 같은 요청 본문을 캡처해 각 3회, 08시 이후 9:27~9:28): stream=true는 3/3 정상(9.6~9.8초, `data:` 줄 36개, 도구 호출 델타 8개, finish `tool_calls`), stream=false도 3/3 정상(9.5~9.7초, JSON 913바이트, `id`와 `usage` 있음, 도구 호출 2개, finish `tool_calls`). 두 방식의 결과는 같고 서버 속도는 어제 14초에서 9.7초로 빨라졌다. 이 재현에서 빈 응답은 한 번도 나오지 않았다(총 16/16 정상).

**1-c 판정: 회귀가 아니다**(요청이 같고 같은 요청이 16/16 성공). 원인 후보(추정)만 적는다.
- (후보 1) 서버·전송의 일시 현상: 세 번의 실패는 0.4~5초 안에 SSE 데이터 없이 끝났다. 어제와 오늘 재현에서 같은 요청이 성공하므로 요청 내용 문제가 아니다. 다만 오늘 IDE 프로세스에서는 도구 호출 3건이 모두 비었고(08:2x 프로세스 2/2, 08:48 프로세스 1/1) 같은 IDE에서 `chat`(스트림 아님) 호출은 정상이었다. IDE 프로세스와 재현(같은 `OpenAIClient` 코드, 중계 서버 경유, 키 없음)의 차이로 설명되는지는 확인하지 못했다.
- (후보 2) 서버가 SSE가 아닌 본문(오류 JSON 등)을 보냈고 스트림 읽기가 `data: `로 시작하지 않는 줄을 조용히 버렸다(`OpenAIClient.kt`의 이전 `startsWith("data: ")` 조건). 이번 수정 전에는 서버 원문이 어디에도 남지 않아 **확인할 수 없었다**.
- (후보 3) `data:` 뒤 공백이 없는 SSE: 이전 코드는 `data: `(공백 포함)만 읽어 `data:{...}` 응답을 통째로 버렸다. vLLM이 그렇게 보냈다는 근거는 없다.
- 이전의 `retry`는 "도구 인자 JSON 절단"(`isTruncatedToolCall`)만 다시 호출했고 빈 응답(청크 0개)은 다시 호출하지 않았다.

**사용자 덤프(req-3)의 위치와 시각**: 덤프는 **디스크에 없다**. `DebugManager`(`service/DebugManager.kt`)의 메모리 목록(최대 50개, IDE 프로세스마다 `req-N`이 1부터)으로 LLM 디버그 도구창이 읽는다. `req-3`은 08:48:39에 다시 시작한 IDE 프로세스의 세 번째 호출이다: req-1 = 09:14:52.900 초기 제약 추출(원본 응답 797자), req-2 = 09:15:05.220(원본 응답 837자), **req-3 = 09:16:09.656 탐색기 Turn 1, 완료 09:16:10.048(392ms), `chunks=0, toolCalls=0, finishReason=null`**. 그 분석의 SR은 정제 없이 원문 51자("기존 설문조사 상세 조회 기능에 응답자 이메일 정보도 같이 내려주도록 백엔드를 수정해주세요.")였다(`분석 시작 직전 SR(51자, clarify 경유)`).

**(b) 덤프의 응답 원문은 서버 원문이 아니라 스트림 조립 결과다**: `OpenAIClient.kt:581`(수정 전 `debugEntry.responseText = gson.toJson(response)`)은 `:568`의 `finishReason = finishReason ?: "stop"`으로 만든 조립 객체를 직렬화한 것이다. 그래서 (가) 서버가 `finish_reason`을 주지 않았는데 덤프에는 `stop`으로 보이고(로그는 `finishReason=null`), (나) `id`(응답에서 못 읽음), `model`, `usage`가 조립 객체에 없어 덤프에 없고, (다) 요청이 `stream=true`인데 메타가 `streaming=false`인 것은 `newEntry(..., false)`로 상수 `false`를 넘긴 것(`:405`, 이제 `true`)이다. 즉 덤프의 세 모순은 모두 조립·메타 쪽 사정이고 서버가 준 것이 아니다. 조립 중 `tool_calls` 델타를 버리는 결함은 확인되지 않았다(델타는 `index`별로 누적한다). 실제로 버려지던 것은 (i) `data:`(공백 없음), (ii) SSE가 아닌 본문, (iii) 오류 본문이었다.

**커밋 1(b4c7d88) 수정**(1-c의 "회귀면 즉시 수정"과 무관하게 보충 지시로 포함)
- (a) `content`와 `tool_calls`가 모두 빈 응답은 오류로 분류(`OpenAIClient.isEmptyToolResponse`, `:41`), `chatWithTools`(`:331`)가 `[EmptyResponse]` 경고와 함께 **1회 다시 호출**하고 그래도 비면 그대로 돌려준다. 호출부: `AgenticSeedSelector`는 `[EmptyResponse] LLM 응답이 비어 있음`과 `LLM finished without tool calls`를 구분해 기록하고(`:117` 부근), `FileRelevanceVerifier`는 `[EmptyResponse] FileRelevanceVerifier: …`와 기존 `No valid tool call found`를 구분한다(`:334`). 둘 다 `usedFallback`이 되어 B-74 2번 화면 처리로 간다.
- (b) 스트림 읽기: `data:`(공백 없음)도 읽고(`:454`), 청크가 0개일 때 버려지던 본문을 JSON으로 해석해 일반 완료 응답이면 도구 호출·내용을 읽고 오류 JSON이면 `서버 오류 응답`으로 예외를 던진다(오류 본문은 재시도하지 않는다). 청크 0개이면 서버 본문 앞부분 300자를 `[EmptyStream]` 경고로 남긴다(`:510`). 디버그 항목은 "스트림 조립 결과, 서버 원문 아님" 머리말을 달고(`:581`), 빈 응답이면 서버 원문 앞부분을 덧붙이며, 도구 호출 항목을 스트리밍으로 표시한다.
- 테스트(`EmptyToolResponseB74Test` 8개, 가짜 서버): 빈 응답 → 1회 재시도 뒤 정상이면 사용(서버 2회 호출), 계속 빈 응답이면 2회 호출 뒤 빈 응답 반환, 정상·"내용만 있는 응답"은 재시도 없음, `data:` 공백 없음 파싱, 일반 JSON 본문의 도구 호출 파싱, 오류 본문은 오류(재시도 없음), 탐색기와 검증기의 빈 응답·도구 없음 구분과 대체 경로 표시. 기존 `OpenAIClientNonStreamRetryTest`(6), `OpenAIClientStreamRetryTest`(4) 불변.

#### 2 커밋 2(36b1a19) 대체 경로 화면 처리
- `SeedSelectionResult`/`DiscoveryMetadata`/`VerificationOutput`에 `usedFallback`이 생겼다(탐색기 대체 경로, 검증기 도구 호출 없음). `RequirementAnalysisPipeline(project, client, incompleteOnFallback = project != null)`: 켜져 있고 둘 중 하나가 대체 경로면 결과 표와 `/implement` 안내, 계약 저장, 가드 기록, 마지막 결과 갱신 없이 안내("파일 분석을 끝까지 하지 못했습니다")만 내고 `analyze_incomplete`와 "다시 시도" 버튼이 같은 정제문을 `/analyze`로 다시 보낸다(발화 재전송 없음, 버튼은 한 번 누르면 비활성). `/analyze`는 인텐트 없이 시작하므로 이미 제외한 파일(`excludedFiles`)은 다시 시도에서 빠진다. 기본값은 IDE(project 있음)에서만 켜져 평가 하네스·단위 테스트(project 없음)는 대체 경로 결과를 그대로 쓴다.
- 결과 화면의 내부 문구: `AnalysisResultWording`이 표시용으로 `Score: N, Via: …`("근거를 확인하지 못해 후보로만 남았습니다"), `[Bypass: …]`(제거), `Fallback: Keyword matching…`(제거)를 바꾼다(저장 값은 그대로).
- 테스트(`IncompleteAnalysisB74Test` i1~i6 + 웹뷰 3 + ui-check 34): 탐색기 대체 경로(표·`/implement`·내부 문구 없음, 마지막 결과 없음), 검증기 대체 경로, 정상 경로(표 그대로, 안내 없음), 기본값(대체 경로 결과 그대로), 시드 선정의 `usedFallback`, 문구 변환. ui-check 34: 안내만 있고 표·`/implement`·`Score`/`Fallback`/`Bypass` 없음, 다시 시도 1개, 눌러도 `/analyze`는 같은 요구사항으로 1회, 비활성, 정상 결과에는 버튼 없음.

#### 3 탐색기 대체 경로와 화면 확장(읽기만)
**3-a 도구 없이 끝나면 바로 휴리스틱으로 가는 것 vs 재시도 1회**: 재시도 1회가 낫다고 판단하며 **빈 응답(청크 0개)에 한해** 커밋 1에서 이미 넣었다. 근거: (1) 오늘 실패 세 번은 모두 `chunks=0`·`finishReason=null`로 0.4~5초 안에 끝나 모델의 판단이 아니라 전송 단계의 결과로 보이고, 같은 요청이 16/16 성공했다. (2) 대체 경로의 결과는 엉뚱한 시드다(오늘 CallbackInfoDto, 아래 3-b). (3) 비용은 실패 때만 호출 1회(0.4초 응답 후 재호출이면 10~14초 추가)다. 단 모델이 내용을 쓰고 도구 없이 끝낸(`content` 있음) 경우는 재시도하지 않는다(모델 판단일 수 있어 같은 응답이 되풀이될 가능성이 크다). 재시도 뒤에도 비면 2번 화면 처리로 간다.

**3-b CallbackInfoDto 95점과 범용 번역의 영향**
- 95점 내역(`RelevanceScorer.kt:61-130`의 FileNode 점수): hop 점수 40(시드 hop 0) + 이름 일치 20.1(`NAMEMATCH-DIAG: Direct [], Trans [info], Weak [info, info]`; `Trans` 일치는 `30 × IDF 가중치`, 즉 번역 토큰 `info` 하나로 20.1점) + 나머지 34.9(레이어 일치 15와 주석 일치 20으로 합이 맞지만 두 값은 로그에 없어 **추정**). 정확히는 CallbackInfoDto가 Seed가 된 이유가 점수 이전의 키워드 대체 선정이다: `AgenticSeedSelector.fallbackSelection`(`:334-360`)이 `정보 → info`(`translate("정보") = [info, information, data]`, 확인)로 영문 토큰을 만들고 `className.contains("info")`인 클래스를 시드(최대 5개)로 뽑았다. 시드가 되면 hop 40이 붙고 이름 일치 20.1이 더해진다.
- **기존 시나리오 16개에서 오프라인 집계**(스캐너 복사 없이 대체 선정 규칙을 그대로 복제, 범용 단어 = 스캐너 불용어 + information/edit/modify/change/add/remove/show/display; 점수가 아니라 시드 선정에 대한 집계): 범용 번역만으로 뽑힌 시드가 있는 시나리오 3개(S2, SR-05, SR-07), 전체 시드 77개 중 범용 번역만으로 뽑힌 것 5개, 첫 시드가 범용 번역만인 시나리오 3개, 범용 번역을 빼면 시드가 달라지는 시나리오 3개, **정답 필수 파일과 겹친 시드는 포함 8개/제외 8개로 같다**(recall 영향 없음). 즉 이 평가 세트에서는 범용 번역의 영향이 시나리오의 약 19%에서 시드 일부(약 6.5%)에 있고 정답 파일에는 영향이 없었다. 한계: 점수 단계(`RelevanceScorer`)의 Trans 기여 전체는 집계하지 않았다.

**3-c `frontendRelevant`가 true가 되는 단어와 위치**: `AgenticSeedSelector.kt:271-278`(LLM이 시드를 확정한 경로)과 `:363`(대체 경로)에서 SR 문장에 **"어드민", "화면", "UI", "프론트", "vue", "jsp", "페이지", "웹", "frontend", "admin"** 중 하나라도 부분 문자열로 들어 있으면 true다(`"UI"`와 `"웹"`은 다른 단어 안에도 걸린다). **"백엔드"가 명시돼도 부정하지 않는다**: 정제문 "관리자 설문 상세 **화면**용 … 백엔드를 수정하여"는 "화면" 때문에 true였다(로그 `[DEBUG-EXPANDER] frontendRelevant: true`). 이전 정제문(화면 단어 없음)은 단어 규칙으로는 false이고 `isFrontendByStructure`(시드가 컨트롤러·화면 리소스인지)로만 true가 될 수 있다. true이면 `GraphExpander.kt:334-336`의 Step D가 화면(Vue/JS/JSP) 연결을 확장하고 `RelevanceScorer.kt:23`의 프런트 힌트 보호가 켜진다(오늘 `survey.list.js`, `survey.preview.js`가 "Frontend Resource" 구제로 들어왔다).

**3-d "관리자→manager"가 manager_* 37점의 원인인가**: **정확히는 아니다.** `translate("관리자") = [manage, management, mgr]`(확인; manager가 아님)이고, 리소스 이름 일치가 `fileName.contains(eng)`(`RelevanceScorer.kt` 리소스 점수부 `:226` 부근)의 부분 문자열이라 `manage`가 `manager`를 포함한 `manager.list.js`(37점, `LINKED_TO`), `approve_manager.jsp`, `manager_list.jsp`, `manager_list_old.jsp`와 `authority_manage.jsp`, `company_manage.jsp`에 걸린다. survey_admin에는 클래스명에 manager가 없고 리소스 파일명에 manager가 4개 있다(`admin` 포함 클래스는 0개).

#### 4 회귀 시나리오 R-EMAIL-PIPE(모의 LLM, `cb4ac89`)
- 정제문 둘: (새) "관리자 설문 상세 화면용 기존 설문조사 상세 조회 기능의 백엔드를 수정하여, 응답자 이메일 정보를 앞 2자리와 도메인만 보이도록 마스킹 처리한 상태로 함께 반환하도록 하세요."(오늘 IDE의 정제문), (이전) "기존(관리자) 설문조사 상세 조회 기능의 백엔드를 수정하여 응답자 이메일 정보를 마스킹 처리한 상태로 함께 반환하도록 하세요."(지시문의 앞부분 + 어제 로그의 정제문 뒷부분을 이어 만든 문장).
- `IncompleteAnalysisB74Test`의 `r_email_pipe_*` 2개(survey_admin 그래프, 탐색기는 IDE 성공 실행을 재생): **도구 호출이 성공하면** 두 정제문 모두 시드에 `SurveyServiceImpl`이 들어가고(`usedFallback` false) 파이프라인 결과가 완료(표 있음, 안내 없음)이며 대상 파일에 `SurveyServiceImpl.java`가 있다. **탐색기 또는 검증기 도구 호출이 실패하면** 두 정제문 모두 결과 표와 `/implement` 없이 안내만 나오고 `retryRequirement`가 같은 정제문이다. 통과.
- 한계: 모의 LLM으로 탐색기 응답을 재생한 것이라 실제 모델이 항상 설문 서비스를 시드로 고른다는 뜻이 아니다.

#### 5 기록만(수정 없음)
- **GUARD-SHADOW 한글**: 이번 로그도 파일은 정상 UTF-8(대체 문자 U+FFFD 0건), IDE 시작 로그가 `locale=ko_KR JNU=MS949 file.encoding=UTF-8`이다(B-71 추가 2-h 결론 그대로). 경고 원문은 `[GUARD-SHADOW] 차단 대상이었으나 shadow 모드라 통과시킴: [역할 누락/PERSISTENCE] DB 데이터 조회/저장 필요 → DAO_INTERFACE 가 REQUIRED 집합에 없습니다. …; [역할 누락/BUSINESS] …`로 파일에 정상 기록돼 있다.
- **`saveChat` 반복 호출**: 이번 IDE 세션(08:26~08:49) 전체 246회, 분석 단계(08:37:40~08:41:54) 19회, 그 앞 clarify·일반 채팅 구간 226회(08:34:23의 일반 채팅 스트리밍이 청크마다 저장해 대부분을 차지). 원인과 호출처는 B-71 추가 2-j와 같다(`App.tsx`의 `useEffect([messages, chatId])`가 `messages`가 바뀔 때마다 전송).

#### 6 검증(커밋 b4c7d88 기준)
| 검증 | 결과 |
|---|---|
| 전체 테스트(서버 의존 14클래스 제외) | 627개(이전 611 + 새 16), 실패 42, 건너뜀 18. 실패 42건은 기준과 판정 차이 0, 관찰 차이 0 |
| clarify 패키지 | 292개(39 스위트), 실패 1(기존 1건), 건너뜀 3 |
| 하네스 | EXACT_MATCH 5/5, MATCH_EXCLUDING 2/2 |
| 실패 메시지 diff | 0 |
| selftest | 24/24 |
| 웹뷰 로직 테스트 | 53/53(이전 50 + 새 3) |
| ui-check | 34/34(이전 33 + 새 34) |
| `npm run build` 산출물 | 커밋 2에 `index.html`, `tsconfig.tsbuildinfo` 포함 |
**기존 테스트 기대값 변경: 없음**(새 테스트와 새 필드만 추가, 기본값은 기존 동작). 확인: `RequirementPipelineHardLimitTest`(`N=201 should throw Hard Limit exception`)의 실패는 baseline(`msgs_baseline.json`)에 있는 기존 실패다.

#### 예측과 맞은 것·틀린 것, 미확인 사항
- 맞음: 1-b의 P1(요청 동일), P2(10/10), P4(시간). 틀림: P5(첫 청크에 도구 호출 또는 reasoning; 실제는 role만 있는 빈 content 청크).
- 예상과 달랐던 것: 회귀가 아니었고 재현 16/16 성공이라 오늘 실패의 직접 원인(서버 본문)을 알 수 없다. "관리자→manager"가 아니라 `manage`의 부분 문자열 일치였다. 덤프의 모순은 서버가 아니라 조립·메타 쪽 사정이었다.
- 미확인: 오늘 세 번의 빈 스트림에서 서버가 실제로 보낸 본문(수정 전에는 남지 않았다), IDE 프로세스에서만 빈 응답이 나는 이유, 점수 단계의 Trans 기여 전체, `/analyze` 다시 시도에서 제외 파일이 빠지는 영향의 크기.

#### IDE에서 직접 볼 항목
1. 같은 SR로 분석을 여러 번: 탐색기 Turn 1이 빈 스트림으로 끝나면 `idea.log`에 `[EmptyStream]`(서버 본문 앞부분)과 `[EmptyResponse]`가 남고 자동으로 1회 다시 호출하는지, 그래도 비면 결과 표 대신 "파일 분석을 끝까지 하지 못했습니다"와 "다시 시도"가 나오는지(눌러서 같은 요구사항으로 `/analyze`가 시작되는지).
2. 검증기(Stage 3)에서 빈 응답이면 같은 화면이 나오는지.
3. 정상 분석의 결과 표 화면이 이전과 같은지(내부 문구 없음).
4. LLM 디버그 도구창의 빈 응답 항목에 "스트림 조립 결과, 서버 원문 아님"과 서버 원문 앞부분이 보이는지, 스트리밍 표시가 맞는지.
