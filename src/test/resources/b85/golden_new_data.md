> 📦 **프로젝트 규모가 큽니다 (132개 파일). 관련 패키지 선택을 요청합니다.**
> 💡 **Tip:** 전체 132개 파일을 대상으로 탐색 중입니다. Project 뷰에서 관련 패키지를 선택 후 실행하시면 분석의 정확도와 속도가 크게 향상됩니다.

> > **(Stage 1) Discovery 시작**
요구사항 텍스트 기반 Seed 식별 시작...
> 식별된 Seed 바탕으로 그래프 탐색 중... (4개 발견)
> 탐색된 파일 연관성 평가 중... (30개 평가 대상)
> 최종 13개 파일 선정 완료.

> **(Stage 2) Trimming** - 불필요한 파일 경로 보정 및 필터링...

> **(Stage 3) LLM Verification** - 최종 연관성 검증...

### 요구사항 분석 요약
요약입니다.
> ℹ️ 응답자 이메일: 분석 단계에서 survey/service·survey/dao·survey/dto 폴더를 읽어 확인했으나 기존 데이터가 없습니다. 사용자 답변(출처: 설문 응답 테이블에 이메일 컬럼 신설·저장)에 따라 새 컬럼 추가는 구현 범위에 포함됩니다.
>
> 📌 **구현 전 결정할 것**
> - 응답 테이블(ISSW_SURVEY_R)에 값을 쓰는 쿼리가 읽은 매퍼 1개(sql_survey.xml)에 없습니다. 응답자 이메일 저장을 이 프로젝트에서 구현할지, 응답을 저장하는 다른 시스템과 연계할지 정해야 합니다.
> - 응답자가 이메일을 입력·전달하는 경로(컨트롤러·화면)는 분석 대상에 없습니다. 이 분석은 파일을 추가하지 않았습니다. 필요하면 요청을 받는 컨트롤러나 화면 파일을 @로 추가해 다시 분석하세요.
> - 응답자 이메일 저장용 새 컬럼은 DB 스키마 변경(ALTER TABLE … ADD COLUMN)이 먼저 필요합니다. 이 분석은 DDL 파일을 만들지 않았습니다.

- Stage 3에서 제외된 후보 12개: SurveyDaoImpl.java, sql_survey.xml, SurveyService.java, SurveyDao.java, AddressDaoImpl.java, AddressDao.java, sql_address.xml, SurveyQImgDto.java, UserServiceImpl.java, ReviewDto.java 외 2개

**(작업유형: 수정) 관련된 초기 핵심 파일(Seed) 식별:** SurveyServiceImpl, SurveyDaoImpl, SurveyDto, sql_survey.xml

### 🎯 분석된 대상 파일 목록
| 순번 | 파일 경로 | 작업유형 | 수정 사유 |
|:---:|:---|:---:|:---|
| 1 | src/main/java/net/infobank/iss/survey/service/SurveyServiceImpl.java | 수정 | **필수** · SR이 값을 가공(마스킹)하므로 그 처리가 들어갈 비즈니스 로직 위치입니다 / LLM 사유: 수정이 필요한 구체적인 사유 |
| 2 | src/main/java/net/infobank/iss/survey/dto/SurveyDto.java | 수정 | 수정이 필요한 구체적인 사유 |

### 📊 탐색 메타데이터
- **선정된 초기 파일(Seed)**: SurveyServiceImpl, SurveyDaoImpl, SurveyDto, sql_survey.xml
- **파일 필터링 결과**: 평가 30개 → 검증 후보 14개 → 최종 2개 (신규 제안 1개는 별도)

