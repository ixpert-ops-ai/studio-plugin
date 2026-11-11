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
> ℹ️ 응답자 이메일: 분석 단계에서 survey/service·survey/dao·survey/dto 폴더를 읽어 확인했으나 기존 코드에는 없고, 사용자 답변(출처: 다른 테이블 조인으로 조회)으로 출처가 지정되었습니다(조인 대상 확인 필요).

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

