# 아키텍처 유형별 평가 SR 초안 (사용자 확인용, 2026-10-16)

작성 원칙: 정답을 먼저 고르고 SR을 나중에 썼다. 정답은 사용자 승인 전까지 어떤 실행에도 쓰지 않는다. 이 문서를 만드는 데 LLM을 쓰지 않았다(메타그래프 조회, 소스 읽기, grep만).
- 소스를 확인한 프로젝트: survey_admin, member-market. 정답 근거에 소스의 파일:줄을 적었다.
- **그래프만 있는 프로젝트(ISM, APC)는 소스로 확인할 수 없어 정답 근거가 그래프 관계(의존·메서드 이름)뿐이다.** 해당 블록에 표시했고 확신이 낮은 파일에는 "?"를 붙였다.
- 미측정 유형: SPRING_BOOT_MYBATIS, SPRING_BOOT_JDBC, CUSTOM(프로젝트 없음, 가짜 그래프를 만들지 않았다).
- 기존 시나리오(D1~D5: 설문 발송 채널·알림톡, 케어회원 조회, 앱카드 최근 이력 / S1~S3: 채팅 안읽음 개수, 회원 이용 정지, 본인인증 결과)의 기능 묶음과 겹치는 SR은 쓰지 않았다.
- 정답 표의 "역할": required(고쳐야 하는 파일), reference(읽거나 흐름상 거치지만 고치지 않는 파일), mustNotAppear(사용자 지시로 결과에 나오면 안 되는 파일). "사용자 확인" 칸은 비워 두었다. 근거 앞의 "?"는 확신이 낮다는 표시다.
- holdout 4개(프로젝트당 1개, 유형이 겹치지 않음)는 개선 작업의 판단 근거로 쓰지 않고 개선 확정 시점에만 실행한다.

## 요약 (12개)
| 번호 | 프로젝트(유형) | SR 유형 | holdout | required 수 | mustNotAppear 수 |
|---|---|---|---|---|---|
| SR-01 | survey_admin (SPRING_MVC_MYBATIS) | 기존 기능 수정 | | 2 | 0 |
| SR-02 | survey_admin | 기존 패턴 따른 신규 추가 | | 4 | 0 |
| SR-03 | survey_admin | 제외 지시 포함 | **holdout** | 1 | 1 |
| SR-04 | member-market (SPRING_BOOT_JPA) | 기존 기능 수정 | | 1 | 0 |
| SR-05 | member-market | 제외 지시 포함 | | 2 | 2 |
| SR-06 | member-market | 모호(clarify 질문 필요) | **holdout** | 2 | 0 |
| SR-07 | ISM (SPRING_MVC_MYBATIS, 그래프만) | 기존 기능 수정 | | 2 | 0 |
| SR-08 | ISM (그래프만) | 모호(clarify 질문 필요) | | 3 | 0 |
| SR-09 | ISM (그래프만) | 기존 패턴 따른 신규 추가 | **holdout** | 5 | 0 |
| SR-10 | APC (ANYFRAME_AP, 그래프만) | 기존 기능 수정 | **holdout** | 1 | 0 |
| SR-11 | APC (그래프만) | 기존 패턴 따른 신규 추가 | | 4 | 0 |
| SR-12 | APC (그래프만) | 모호(clarify 질문 필요) | | 2 | 1 |

holdout: SR-03(제외 지시), SR-06(모호), SR-09(신규 추가), SR-10(수정). 프로젝트당 1개, 유형 4가지가 겹치지 않는다.

---

## SR-01 · survey_admin · 기존 기능 수정 · holdout 아님
**SR 원문:** 비밀번호를 연속으로 틀렸을 때 계정이 잠기는 기준을 지금보다 엄격하게(현재 5회에서 3회로) 바꾸고, 잠겼다는 안내 문구도 그에 맞게 고쳐 주세요.

- **기능 묶음:** IpsController(`/ajax/{pageName}`) → UserServiceImpl(`getLoginStatus` `:664-717`, `checkUserPwdError` `:925-944`, 실패 5회 비교 `:716`, `:943`) → UserDaoImpl → UserDao → sql_user.xml(LOGIN_FAIL_CNT 갱신). 화면: login.js(4003 처리 `:190`), common/ips_common.js(4003 안내문 `:142` "5회 이상 로그인 실패하여…").
- **근거 출처:** 소스 확인(survey_admin).

| 파일명 | 역할 | 근거(20자 이내) | 사용자 확인 |
|---|---|---|---|
| UserServiceImpl.java | required | 실패 5회 비교(:716,:943) | |
| src/main/webapp/resources/js/common/ips_common.js | required | 4003 안내문에 "5회" | |
| login.js | reference | 4003 응답 화면 처리 | |
| ? UserDto.java | reference | ? 실패 횟수 값 보관 | |
| mysql/sql_user.xml | reference | 실패 횟수 갱신, 변경 불필요 | |

- **대본(clarify 질문에 답할 내용):** "3회로 바꾸고, 잠금 해제 방식은 지금 그대로 두세요."
- **사용자 확인 반영:** SR 원문과 대본을 "5회 → 3회(엄격하게)"로 바꿨다. 실패 횟수 SQL(`updatePasswordFail`, `LOGIN_FAIL_CNT`)은 mysql/sql_user.xml에만 있어 reference를 그 1개로 줄였다(mssql·oracle 쪽 sql_user.xml에는 해당 id와 컬럼이 없음, 소스 확인).
- **다른 기능 묶음과 겹침:** 없음(S3 본인인증 결과와 다른 기능, 같은 파일을 쓰지만 required가 다름).

이 SR이 실제 업무에서 나올 법한가?  [  ] 예  [  ] 아니오  [  ] 고쳐야 함: ____________

---

## SR-02 · survey_admin · 기존 패턴 따른 신규 추가 · holdout 아님
**SR 원문:** 관리자가 사용자별로 최근 로그인 기록(성공·실패 모두)을 조회할 수 있게 해 주세요. 지금 있는 사용자 목록 조회와 같은 방식으로 만들면 됩니다.

- **기능 묶음:** IpsController → UserServiceImpl.`proc`의 procType 분기 패턴(`getManagerList` `:195`, `getCompanyList` `:250` 등) → UserDao(`insertLoginHistory` `:68`만 있고 조회 없음) → UserDaoImpl → sql_user.xml(`insertLoginHistory` `:421` ISSW_LOGIN_HISTORY, 조회 SQL 없음). 이력 DTO는 LoginUserDto. 이 프로젝트는 DB 종류별 폴더(mssql·mysql·oracle)의 sql_user.xml을 쓴다(`mybatis-context.xml:97` mapperLocations).
- **근거 출처:** 소스 확인(survey_admin).

| 파일명 | 역할 | 근거(20자 이내) | 사용자 확인 |
|---|---|---|---|
| UserServiceImpl.java | required | procType 분기 추가 필요 | |
| UserDao.java | required | 조회 메서드 선언 추가 | |
| UserDaoImpl.java | required | DAO 구현 추가 | |
| sql_user.xml (mssql·mysql·oracle 3종) | required | ? 조회 SQL을 3종 모두 | |
| LoginUserDto.java | reference | 이력 필드를 이미 보유 | |
| IpsController.java | reference | ? 요청 진입점만 거침 | |

- **대본:** "한 사용자를 정해서 최근 30일 기록을 보고 싶어요."
- **겹침:** 없음.

이 SR이 실제 업무에서 나올 법한가?  [  ] 예  [  ] 아니오  [  ] 고쳐야 함: ____________

---

## SR-03 · survey_admin · 제외 지시 포함 · **holdout**
**SR 원문:** 설문 참여 결과 내보내기 파일에 실패 건수 대비 비율(실패율)이 함께 나오게 해 주세요. 내보내기를 실행하는 화면 쪽은 그대로 두세요.

- **기능 묶음:** SurveyPartExportJob(스케줄) → SurveyPartExportRunner(`run` `:39`, `batch.selectSurveyPartBatchList` `:48`, 비율 계산 도구 `formatPercent`) → sql_batch.xml(`selectSurveyPartBatchList` `:6-26`, CNT_FAILURE·CNT_SUCCESS 이미 조회). 실행 화면 쪽: IpsController(`:482` `surveyPartExportJob.run()`).
- **근거 출처:** 소스 확인(survey_admin).

| 파일명 | 역할 | 근거(20자 이내) | 사용자 확인 |
|---|---|---|---|
| SurveyPartExportRunner.java | required | 내보내는 항목 구성 | |
| SurveyPartExportJob.java | reference | 스케줄에서 호출만 | |
| SurveyPartExportCli.java | reference | ? 실행 인자 처리 | |
| sql_batch.xml | reference | 실패 건수 이미 조회 | |
| IpsController.java | mustNotAppear | 실행 화면은 그대로(:482) | |

- **대본:** "실패율은 백분율로 소수점 한 자리까지 보여 주세요."
- **겹침:** 없음(D1·D5의 알림톡 배치와 다른 내보내기 배치).

이 SR이 실제 업무에서 나올 법한가?  [  ] 예  [  ] 아니오  [  ] 고쳐야 함: ____________

---

## SR-04 · member-market · 기존 기능 수정 · holdout 아님
**SR 원문:** 상품을 검색어로 찾을 때 판매 중이 아닌 상품(예약 중·판매 완료)은 결과에 나오지 않게 해 주세요.

- **기능 묶음:** ProductController(GET `/api/products`) → ProductService.`getProducts`(`:33-46`, 검색어가 있으면 `searchByKeyword`) → ProductRepository.`searchByKeyword`(JPQL: 제목·설명 LIKE만, 상태 조건 없음) → ProductStatus. 같은 파일의 `findActiveProductsByOrganization`·`findByCategory`는 `status = 'SALE'` 조건을 이미 가짐.
- **근거 출처:** 소스 확인(member-market).

| 파일명 | 역할 | 근거(20자 이내) | 사용자 확인 |
|---|---|---|---|
| ProductRepository.java | required | 검색 쿼리에 상태 조건 없음 | |
| ProductService.java | reference | 검색 분기만 호출 | |
| ProductStatus.java | reference | SALE 값 정의 | |
| ProductController.java | reference | 요청 진입점 | |

- **대본:** "판매 중인 상품만 보이면 됩니다."
- **겹침:** 없음(S1 채팅, S2 관리자 이용 정지와 다름).

이 SR이 실제 업무에서 나올 법한가?  [  ] 예  [  ] 아니오  [  ] 고쳐야 함: ____________

---

## SR-05 · member-market · 제외 지시 포함 · holdout 아님
**SR 원문:** 조직(학교·회사 등) 목록에는 운영 중인 조직만 보이게 해 주세요. 관리자 화면의 조직 목록은 그대로 두세요.

- **기능 묶음:** OrganizationController(GET `/api/organizations`) → OrganizationService.`getOrganizations`(`findAll`/`findByType`, 상태 필터 없음) → OrganizationRepository(`findByType`, `findByStatus`) → Organization·OrganizationStatus(ACTIVE/INACTIVE/PENDING) → OrganizationResponse. 관리자 쪽: AdminService → OrganizationRepository(dependedBy), AdminOrganizationResponse.
- **근거 출처:** 소스 확인(member-market).

| 파일명 | 역할 | 근거(20자 이내) | 사용자 확인 |
|---|---|---|---|
| OrganizationService.java | required | 목록 조회에 상태 필터 필요 | |
| OrganizationRepository.java | required | ? 유형+상태 조회 메서드 | |
| OrganizationStatus.java | reference | ACTIVE 값 정의 | |
| OrganizationController.java | reference | 요청 진입점 | |
| AdminService.java | mustNotAppear | 관리자 목록은 그대로 | |
| AdminOrganizationResponse.java | mustNotAppear | 관리자 화면용 응답 | |

- **대본:** "활성 상태인 조직만요. 대기 중인 조직도 숨겨 주세요."
- **겹침:** 없음. (AdminService는 S2의 required이지만 이 SR에서는 건드리면 안 되는 파일이다.)

이 SR이 실제 업무에서 나올 법한가?  [  ] 예  [  ] 아니오  [  ] 고쳐야 함: ____________

---

## SR-06 · member-market · 모호(clarify 질문 필요) · **holdout**
**SR 원문:** 이미지 올리기를 더 안전하게 해 주세요.

- **기대 질문:** (1) 무엇을 제한할까요(용량, 파일 형식, 개수)? (2) 제한을 넘으면 사용자에게 어떻게 안내할까요?
- **기능 묶음:** ImageController(POST `/api/images/upload`) → FileService.`storeFile`(빈 파일 검사뿐, 용량·형식 검사 없음, 확장자는 파일명에서 그대로 추출) → ErrorCode·BusinessException·GlobalExceptionHandler(오류 응답).
- **근거 출처:** 소스 확인(member-market). 아래 정답은 대본의 답을 받은 뒤의 정의다(답에 따라 달라짐).

| 파일명 | 역할 | 근거(20자 이내) | 사용자 확인 |
|---|---|---|---|
| FileService.java | required | 저장 전 검사가 없음 | |
| ? ErrorCode.java | required | ? 새 오류 코드 필요 | |
| ImageController.java | reference | 업로드 진입점 | |
| ? GlobalExceptionHandler.java | reference | ? 오류 응답으로 변환 | |

- **대본(질문에 대한 답):** "용량은 5MB까지, 파일 형식은 jpg와 png만요. 넘으면 '이미지는 5MB 이하 jpg/png만 가능합니다'라고 알려 주세요. 개수 제한은 없습니다."
- **겹침:** 없음.

이 SR이 실제 업무에서 나올 법한가?  [  ] 예  [  ] 아니오  [  ] 고쳐야 함: ____________

---

## SR-07 · ISM · 기존 기능 수정 · holdout 아님 · **[그래프 관계만 근거]**
**SR 원문:** FAQ 관리 화면의 목록에서 사용을 중지한 FAQ는 기본으로 숨겨 주세요.

- **기능 묶음:** 목록 조회 경로가 둘이다. FaqMgmtController(GET `/system/faqMgmt/getFaqList`) → FaqMgmtServiceImpl.`getFaqList` → PrFaqInfoMapper(rwdb, `getFaqList`·`getFaqListCount`) → prFaqInfo.xml(`getFaqList`, `getFaqListCount`). PrFaqController(GET `/system/prFaq/getFaqList`) → PrFaqServiceImpl.`getFaqList` → PrFaqMapper(rodb) → PrFaqMapper.xml(`getFaqList`, `getFaqListCount`). 두 경로 모두 테이블 PR_FAQ_INFO를 읽는다.
- **근거 출처:** 그래프의 의존·메서드 이름과 사용자 확정. 소스는 확인하지 못했고, FAQ 쪽 엔티티·DTO의 한글 주석에는 "사용여부"가 없고 "게시여부"가 있다.

| 파일명 | 역할 | 근거(20자 이내) | 사용자 확인 |
|---|---|---|---|
| PrFaqMapper.xml | required | 목록 SQL, PR_FAQ_INFO 조회 | 확정(2026-10-20) |
| prFaqInfo.xml | required | 목록 SQL, PR_FAQ_INFO 조회 | 확정(2026-10-20) |
| PrFaqController.java | reference | 목록 진입점(/system/prFaq) | |
| PrFaqServiceImpl.java | reference | 목록 서비스(prFaq) | |
| PrFaqMapper.java | reference | 목록 매퍼(rodb) | |
| PrFaqRequest.java | reference | 목록 요청 값 | |
| FaqMgmtController.java | reference | 목록 진입점(/system/faqMgmt) | |
| FaqMgmtServiceImpl.java | reference | 목록 서비스(faqMgmt) | |
| PrFaqInfoMapper.java | reference | 목록 매퍼(rwdb) | |

- **대본:** "사용 여부가 '중지'인 것만 숨기면 됩니다. 목록 화면에서 다시 보는 버튼은 없어도 돼요."
- **사용자 확정(2026-10-20):** /system/prFaq는 관리자 화면의 FAQ 관리 API 경로. 호출 화면 미확정, 두 목록 경로 모두 PR_FAQ_INFO를 읽음. 그래서 required는 두 목록 SQL XML이다. 제외 지시는 없다(mustNotAppear 비움).
- **겹침:** 없음(D4 케어회원 조회와 다른 기능).

이 SR이 실제 업무에서 나올 법한가?  [  ] 예  [  ] 아니오  [  ] 고쳐야 함: ____________

---

## SR-08 · ISM · 모호(clarify 질문 필요) · holdout 아님 · **[그래프 관계만 근거]**
**SR 원문:** 금칙어를 등록할 때 비슷한 단어도 막아 주세요.

- **기대 질문:** (1) "비슷한 단어"의 기준은 무엇인가요(띄어쓰기, 대소문자, 일부 포함, 발음)? (2) 수정할 때도 같은 검사를 하나요? (3) 걸리면 등록을 막나요, 경고만 하나요?
- **기능 묶음:** BadWordMgmtController(POST `/system/badWordMgmt/saveBadWord`) → BadWordMgmtServiceImpl(`validateBadWordInsertData`, `getCountOfExistsStBwInfoByBwNm`) → StBwInfoMapper(`getCountOfExistsStBwInfoByBwNm`) → stBwInfo.xml. 메시지: badWordMgmt_ko/en.properties.
- **근거 출처:** 그래프의 의존·메서드 이름뿐(소스 미확인). 아래 정답은 대본의 답을 받은 뒤의 정의다.

| 파일명 | 역할 | 근거(20자 이내) | 사용자 확인 |
|---|---|---|---|
| BadWordMgmtServiceImpl.java | required | 중복 검사 서비스 | |
| ? StBwInfoMapper.java | required | ? 존재 여부 조회 매퍼 | |
| ? stBwInfo.xml | required | ? 비교 SQL | |
| BadWordMgmtController.java | reference | 저장 요청 진입점 | |
| ? badWordMgmt_ko.properties | reference | ? 중복 안내 문구 | |

- **대본(질문에 대한 답):** "띄어쓰기를 없앤 뒤 대소문자 구분 없이 같으면 같은 단어로 보세요. 수정할 때도 똑같이 하고, 걸리면 등록을 막아 주세요."
- **겹침:** 없음.

이 SR이 실제 업무에서 나올 법한가?  [  ] 예  [  ] 아니오  [  ] 고쳐야 함: ____________

---

## SR-09 · ISM · 기존 패턴 따른 신규 추가 · **holdout** · **[그래프 관계만 근거]**
**SR 원문:** 브랜드 관리에서 브랜드를 삭제하는 것처럼, 삭제하지 않고 사용을 잠시 중지했다가 다시 쓸 수 있는 기능을 추가해 주세요.

- **기능 묶음:** BrandController(`/pd/brandMgmt/…`) → BrandServiceImpl(`modifyBrand`, `deleteBrand`) / BrandMgmtServiceImpl(`saveBrand`) → ECPDTBISM002TrxMapper(`insertBrand`, `updateBrand`, `deleteBrand`, `updateBrandInfo`) → ECPDTBISM002Trx.xml. 읽기: ECPDTBISM002Mapper → ECPDTBISM002.xml.
- **근거 출처:** 그래프의 의존·메서드 이름뿐(소스 미확인). 사용 여부 컬럼이 있는지 확인하지 못했다.

| 파일명 | 역할 | 근거(20자 이내) | 사용자 확인 |
|---|---|---|---|
| ? BrandController.java | required | ? 새 요청 경로 필요 | |
| ? BrandService.java | required | ? 새 메서드 선언 | |
| BrandServiceImpl.java | required | 삭제와 같은 패턴 구현 | |
| ECPDTBISM002TrxMapper.java | required | 쓰기 매퍼에 상태 변경 추가 | |
| ? ECPDTBISM002Trx.xml | required | ? 상태 변경 SQL | |
| BrandMgmtServiceImpl.java | reference | ? 컨트롤러가 함께 사용 | |
| ECPDTBISM002Mapper.java | reference | 읽기 매퍼(변경 없음) | |

- **대본:** "중지된 브랜드는 목록에서 흐리게 보이고, 상품 등록 때는 고를 수 없게 해 주세요. 중지·재개는 관리자만 합니다."
- **겹침:** 없음.

이 SR이 실제 업무에서 나올 법한가?  [  ] 예  [  ] 아니오  [  ] 고쳐야 함: ____________

---

## SR-10 · APC · 기존 기능 수정 · **holdout** · **[그래프 관계만 근거]**
**SR 원문:** 앱카드 공지 팝업을 등록하거나 고칠 때, 노출 종료 일시가 시작 일시보다 앞서면 저장되지 않게 막아 주세요.

- **기능 묶음:** APCADNtccnPopMngtSVC(`insNtccnPop`, `updNtccnPop`) → APCADNtccnPopMngtSVCImpl → APCADNtccnPopMngtBIZ(`insNtccnPop`, `updNtccnPop`) → ACAOTBAPC018DEM / ACAOTBAPC018DVO(노출 시작·종료 일시 `getSpacdNtccnDstrbStrtDtm`, `…EndDtm`). ACAOTBAPC018DEM은 이 BIZ 말고도 BIZ 10개(APCCSPopMsgBIZ 등)가 사용한다.
- **근거 출처:** 그래프의 의존·메서드 이름뿐(소스 미확인).

| 파일명 | 역할 | 근거(20자 이내) | 사용자 확인 |
|---|---|---|---|
| APCADNtccnPopMngtBIZ.java | required | 등록·수정 업무 규칙 위치 | |
| APCADNtccnPopMngtSVCImpl.java | reference | ? 앞뒤 처리만 담당 | |
| ACAOTBAPC018DVO.java | reference | 시작·종료 일시 필드 | |
| ACAOTBAPC018DEM.java | reference | 저장 쿼리, 변경 없음 | |

- **대본:** "등록과 수정 둘 다요. 막을 때는 '종료 일시는 시작 일시 이후여야 합니다'라고 알려 주세요."
- **겹침:** 없음(D2·D3 앱카드 최근 이력 조회와 다른 기능).

이 SR이 실제 업무에서 나올 법한가?  [  ] 예  [  ] 아니오  [  ] 고쳐야 함: ____________

---

## SR-11 · APC · 기존 패턴 따른 신규 추가 · holdout 아님 · **[그래프 관계만 근거]**
**SR 원문:** 앱카드 배너 관리에 삭제 기능을 추가해 주세요. 공지 팝업 관리의 삭제와 같은 방식으로 하면 됩니다.

- **기능 묶음:** APCADSpacdBnnrMngtSVC(`selListSpacdBnnrMngtList`, `selSpacdBnnrMngtDtl`, `updSpacdBnnrMngt`만 있음) → APCADSpacdBnnrMngtSVCImpl → APCADSpacdBnnrMngtBIZ → ACAOTBAPC004DEM(`delAcaotbapc004` 없음). 본보기: APCADNtccnPopMngtSVC·SVCImpl·BIZ(`delNtccnPop`) → ACAOTBAPC018DEM(`delAcaotbapc018`). ACAOTBAPC004DEM은 APCBFBnnrBIZ도 사용한다.
- **근거 출처:** 그래프의 의존·메서드 이름뿐(소스 미확인).

| 파일명 | 역할 | 근거(20자 이내) | 사용자 확인 |
|---|---|---|---|
| APCADSpacdBnnrMngtSVC.java | required | 삭제 메서드 선언 추가 | |
| APCADSpacdBnnrMngtSVCImpl.java | required | 삭제 구현 추가 | |
| APCADSpacdBnnrMngtBIZ.java | required | 삭제 업무 추가 | |
| ACAOTBAPC004DEM.java | required | 삭제 메서드가 없음 | |
| ? APCADSpacdBnnrMngtSVO.java | reference | ? 삭제 입력값 필요 시 | |
| APCADNtccnPopMngtBIZ.java | reference | 본보기(delNtccnPop) | |
| ACAOTBAPC018DEM.java | reference | 본보기 삭제 쿼리 | |

- **대본:** "배너를 하나씩 삭제하는 것만요. 삭제 전에 확인창을 띄우는 건 화면 쪽에서 합니다."
- **겹침:** 없음.

이 SR이 실제 업무에서 나올 법한가?  [  ] 예  [  ] 아니오  [  ] 고쳐야 함: ____________

---

## SR-12 · APC · 모호(clarify 질문 필요) · holdout 아님 · **[그래프 관계만 근거]**
**SR 원문:** 앱 버전이 최신인지 판단하는 기준을 바꿔 주세요. 스토어별로 구분하고, OS별로도 구분하며, 노출 대상으로 표시된 버전만 쓰도록 해 주세요.

- **기대 질문:** (1) 관리 화면에서 보이는 "최신 버전" 표시를 말씀하시나요, 앱이 업데이트 안내를 받을지 판단하는 쪽을 말씀하시나요? (2) 최신으로 보는 새 기준은 무엇인가요(버전 번호, 등록일, OS별)?
- **기능 묶음:** 앱 쪽 판단: APCCSAppVerMngtBIZ(`chkSappVerCmpr`, `chkChgePsbSappVer`, `selAppVerUpdtInf`) → ACAOTBAPC003DEM(`selNewSappVer`, `selMidlSappVer`) / ACAOTBAPC003DVO. 관리 화면 쪽: APCADUpdtVerMngtSVC·SVCImpl → APCADUpdtVerMngtBIZ(`selLtSappVer`, `selLtMidlSappVer`) → ACAOTBAPC003DEM.
- **근거 출처:** 그래프의 의존·메서드 이름뿐(소스 미확인). 아래 정답은 대본의 답("앱 쪽 판단")을 받은 뒤의 정의다. 답이 "관리 화면 쪽"이면 정답이 바뀐다.

| 파일명 | 역할 | 근거(20자 이내) | 사용자 확인 |
|---|---|---|---|
| APCCSAppVerMngtBIZ.java | required | 앱 쪽 최신 판단 규칙 | |
| ? ACAOTBAPC003DEM.java | required | ? 최신 버전 조회 쿼리. crtl_ver_expsr_yn='Y'(사용자 확정) | |
| ACAOTBAPC003DVO.java | reference | 버전 필드 보유 | |
| APCADUpdtVerMngtBIZ.java | mustNotAppear | 관리 화면 쪽은 이번 범위 밖 | |

- **대본(질문에 대한 답):** "앱이 업데이트 안내를 받을지 판단하는 쪽이에요. 같은 OS 안에서 버전 번호가 가장 높은 것을 최신으로 보세요. 스토어별로 따로 보고, OS별로도 따로 보고, 노출 대상으로 표시된 버전만 쓰세요. 관리 화면은 그대로 두세요."
- **근거 보충(그래프):** ACAOTBAPC003DEM·ACAOTBAPC003DVO는 앱 쪽 APCCSAppVerMngtBIZ와 관리 화면 쪽 APCADUpdtVerMngtBIZ가 함께 의존한다(DEM의 dependedBy는 이 두 BIZ뿐). OS 구분 필드는 DVO의 `getMblOsDvC`(모바일OS구분코드)로 확인했다.
- **사용자 확정(2026-10-20):** 스토어별 구분, OS별 구분, 노출 대상으로 표시된 버전만 사용(crtl_ver_expsr_yn='Y'). required/reference 구성은 바꾸지 않았다.
- **겹침:** 없음.

이 SR이 실제 업무에서 나올 법한가?  [  ] 예  [  ] 아니오  [  ] 고쳐야 함: ____________
