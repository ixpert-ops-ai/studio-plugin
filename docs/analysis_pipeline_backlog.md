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
  5. **커밋 2a (`63af698`) Before 보충 (운영 경로)**: 운영 경로의 `primaryReq`(`WebviewActionRouter.kt:1514`)에는 2a 전후 모두 `AnalyzeInputResolver.resolve` 출력이 들어가며 실질 동작은 같다. 2a가 바꾼 것은 `analyze()`가 `clarifyIntent`에서 정제문을 스스로 고르지 않게 된 계약이고, 이 때문에 `analyze()`를 직접 부르던 테스트가 깨졌다(B-26). 줄 번호는 `pipeline.analyze(` 호출이 `:1513`, `primaryReq` 인자가 `:1514`, `clarifyIntent` 인자가 `:1517`이다(기존 서술의 `:1513`은 호출 줄 기준으로 유효하며, `primaryReq` 인자를 가리킬 때는 `:1514`). `:1517`의 `clarifyIntent`는 `:1503`의 `resolvedInput.effectiveIntent`이고 Resolver는 `inMemoryIntent`를 복사 없이 그대로 돌려주므로(`AnalyzeInputResolver.kt:27`) 같은 객체다.
  6. **`srKeyHex` 단절 시점 정정 (커밋 2b `b397975`)**: 3번의 "태그가 제거될 때 해시값이 변경"은 커밋 4가 아니라 **커밋 2b 시점**으로 정정한다. `srKeyHex`(`RequirementAnalysisPipeline.kt:313`, `String.format("SR-%08x", primaryReq.hashCode())`; 3번의 `:314`는 현재 `:313`)는 코드를 바꾸지 않았고, 2b에서 `primaryReq`(= `ResolvedAnalyzeInput.effectiveRequirement`)가 태그 형식에서 줄 형식으로 바뀌므로 **`missingBeforeFix`가 비어 있지 않은 인텐트에 한해** 해시가 바뀐다. `missingBeforeFix`가 비어 있는 인텐트는 입력이 바이트 단위로 같아 해시가 같다(5종 중 LlmOff 3종은 불변, LlmOn 2종은 변경). 섀도 로그는 `srKey`로 묶으므로(`ShadowLogAggregator.kt:43`, `:57`) 2b 전후의 같은 SR은 다른 키가 된다. 커밋 4(태그 제거)는 `primaryReq`가 이미 `rawRefined` 기반이라 해시를 다시 바꾸지 않는다. 이 단절을 합쳐 보는 운영 절차가 있는지는 확인하지 않았다. `src/test`의 `srKey`는 고정 문자열뿐이라(예: `MemberMarketJpaRealDataTest.kt:245`) 해시 값에 의존하는 테스트는 찾지 못했다.
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
- **영향**: 전체 테스트 결과가 외부 서버 상태에 따라 달라져 결정적 기준선(B-27)에 포함할 수 없고, 서버 장애 시 실행 시간이 길어진다.
- **해결 방안**: `-DrunLiveLlmTests=true` 또는 `RUN_LIVE_LLM_TESTS=true` 게이트(`Assume`)를 적용.
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
- **2b 상태**: `b397975`(`src/main`: `AnalyzeInputResolver.kt` 1개 파일)와 `02f0db5`(테스트 전용: `AnalyzeInputStructureTest` 5건, 하네스 단언 1건) 완료. 조립식은 `missingBeforeFix`가 비면 `rawRefined`, 아니면 `"$rawRefined\n" + missing.joinToString("") { "\n$it" }`이고 `retentionAudit == null`이면 `refinedRequirement`를 그대로 쓴다. 하네스 Simulated 2b의 입력 조립 줄(`Stage0EngineRoutedBaselineHarnessTest.kt:718-720`, `:775-777`)과 같은 식이며, `missingBeforeFix`가 빈 경우만 하네스 쪽이 끝에 `\n`이 하나 더 붙는다(하네스는 LlmOff에 이 식을 쓰지 않음). 측정: 기존 5종 `EXACT_MATCH`(sha256이 B-32의 값과 같음), `b397975` run1 = run2 7종 `EXACT_MATCH`, `AnalyzeInputStructureTest`와 하네스 단언 포함 clarify 패키지 127건 중 실패 1건(B-10)·skip 3건, `RequirementAnalysisPipelineIntegrationTest` 8건과 `AnalyzeRefinedSeedPromptTest` 1건 통과. 새 테스트가 옛 `Resolver`에서 실제로 실패하는지는 실행해 보지 않았다(하네스 단언의 `assertNotEquals`가 새 조립을 요구한다는 논리상의 확인뿐).
- **2b 적용만으로 생기는 변화**: Stage 3 입력(`RequirementAnalysisPipeline.kt:258-271`의 `fullRequirement`는 `effectiveReq` 기반)이 태그 형식에서 줄 형식으로 바뀐다. 실제 LLM에 주는 영향은 **미검증**이다(하네스의 strict mock은 Stage 3에서 null을 반환해 전원 보존 폴백).
- **2c 추가 조건**: Stage 3 입력에는 `rawRefined`와 두 섹션을 넣고, 식별자 줄이 붙은 `effectiveReq`는 넣지 않는다(그대로 두면 식별자가 중복됨). 두 섹션은 겹치지 않게 한다: 미확정 섹션(`[미확정(확정 아님) 식별자]`)에는 `unresolvedItems` 전체(항목마다 `UnresolvedKind` 표기), 빠진 식별자 섹션(`[원문에 있었으나 정제문에서 빠진 식별자]`)에는 `missingIdentifiers − unresolvedItems`(사전순)를 넣는다.
- **2d 테스트 예시**: B-22의 `aCMBTBAPC024DEM`과 `ACMBTBAPC024DEM`(대소문자 불일치), `selTrcdIsInf`를 포함한다.
- **도구 옵션(커밋 4 직전)**: `RefinedReq:` 줄만 비교에서 빼고 그 줄은 따로 보고한다. `Top-30 TargetFiles Count`, `GT Survived`, `GT Missing`은 비교 범위에 남긴다.
- **커밋 4 불변식 (합격 기준에 단언으로 포함)**: 태그를 지운 뒤에도 (1) `missingBeforeFix`는 지금처럼 채워져야 하고, (2) `rawRefinedRequirement == refinedRequirement`가 성립해야 한다.
- **상태**: 진행 중 (우선순위 P2, 2b 완료, 2c 이후 미착수)

