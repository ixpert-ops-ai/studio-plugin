# 하네스 기준선 (Stage0EngineRoutedBaselineHarnessTest의 XML)

비교 도구: `scripts/compare-harness-blocks.js A.xml B.xml [--scenarios ... --exclude-line-prefix RefinedReq:]`

| 파일 | 의미 |
|---|---|
| `harness_58eb590_run1.xml` | 이전 기준선(58eb590, 2026-10 초). 엄격 그룹 5종 EXACT_MATCH 기준이었다. |
| `harness_a34c35a_m78.xml` | 직전 기준선: a34c35a(add/search 동작어를 이름 일치에서 제외) 이후, B-78 검증 `m78`(HEAD 248cd97)의 XML. |
| `harness_b79_scorer_dict.xml` | **현재 기준선**: 사전의 "설문" → survey 항목을 분석 점수기에만 적용한 뒤(B-79). 처음 올린 b2402bd 기준선(전역 적용)은 그 변경이 시험 5건을 깨서 되돌리며 지웠다. |

## a34c35a 기준선에서 달라진 블록(2026-10-29, B-79)
`survey_admin (Intent=LlmOff)`와 `Ablation-a (survey_admin LlmOff refined + 5 identifier lines)` 두 블록의 Top-30이 12개 → 11개이고, 차이는 한 파일뿐이다.

| 순번(전) | 파일 | 점수·경로(전) | 현재 |
|---|---|---|---|
| #5 | `address/dao/AddressDaoImpl.java` `[FP]` | 55, `SERVICE_TO_REPO_OR_BIZ` | 빠짐 |

나머지는 순번이 한 칸씩 당겨졌다(정답 `★[GT]` sql_survey.xml #4, SurveyServiceImpl #10 → #9). 정답 파일은 그대로다. 원인: `add`(추가의 번역)가 부분 문자열로 Address*에 약한 점수 10.7을 줘 점수 55로 살아 있던 것이 45로 내려가 탈락(a34c35a).
다른 5개 블록(APC Transit Card, ISM Core, Ablation-b, 제외 그룹 2종)은 이전 기준선과 같다.

## 직전(a34c35a) → 현재(B-79, 점수기 전용 설문 사전)에서 달라진 블록
survey_admin 4개 블록(`survey_admin (Intent=LlmOff)`, `Ablation-a`, `Ablation-b`, `survey_admin (Intent=LlmOn)`)의 **점수만** 달라졌고 후보 집합과 순서 규칙은 같다(후보 추가·삭제 0). 정답(★GT)은 모두 그대로다. APC Transit Card, ISM Core, APC DEM Isolated는 같다.
- LlmOff와 Ablation-a: Top-30 11개 그대로. 정답 점수 sql_survey.xml 5 → 15, SurveyServiceImpl 65 → 75. 같은 survey 패키지의 후보 점수 +10(SurveyDaoImpl 70 → 80, SurveyDto 75 → 85, SurveyDao 40 → 50, ReviewDto 65 → 75 등), 배치의 SurveyPartExportRunner 60 → 70(이름에 survey가 있어서).
- Ablation-b와 LlmOn: Top-30 14개 그대로. sql_survey.xml 0 → 10, SurveyDaoImpl 65 → 75, SurveyPartExportRunner 60 → 70.
- 원인: 사전에 "설문"이 없어 SR 이름 일치 근거가 비어 있던 것이 점수기에서 채워졌다(DomainDictionary.kt DOMAIN_TERMS, RelevanceScorer.kt).
