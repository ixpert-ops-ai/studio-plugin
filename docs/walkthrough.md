# Walkthrough: Source-Based Keyword Weighting

## Changes Made
- `RelevanceScorer.kt` 내의 키워드 추출 자료구조인 `ExtractedKeywords`를 개선했습니다.
  - 기존의 단일 `english` 리스트를 `directEnglish` (원문 직접 등장 영단어)와 `translatedEnglish` (도메인 사전 파생어)로 분리했습니다.
  - 외부나 다른 로직(Method Match)에서 쓰이던 `english` 프로퍼티는 `get() = directEnglish + translatedEnglish` 계산 프로퍼티로 남겨 호환성을 유지했습니다.
- 이름 매칭(NameMatchScore) 시 출처에 따라 가중치를 차등 적용했습니다.
  - ResourceNode (Frontend): `directEnglish` +30점, `translatedEnglish` +15점
  - FileNode (Backend): `directEnglish` +20점, `translatedEnglish` +10점

## Validation Results
SR-01과 SR-03을 하나의 테스트 스위트로 묶어 동시 회귀 검증을 통과했습니다.

- **SR-01 (상품 원산지 필드 추가 - "상품 등록" 포함, "Product" 직접 등장)**
  - 정답군(`ProductCreateView` 등): 직접어 "Product" 매칭으로 115점 유지.
  - 노이즈군(`SignupView`): "등록"에 의한 파생어 "signup" 매칭으로 인해 파생어 점수만 받아 115점 -> 100점으로 하락.
  - **결과**: 노이즈군과 정답군 사이에 15점 Gap 확보 성공.

- **SR-03 (가격대별 상품 검색 - "등록" 없음, "상품" 등장, 영어 미등장)**
  - 정답군(`ProductListView` 등): 원문에 영어가 없어 "상품"을 번역한 파생어 "product"로 매칭. 115점 -> 100점으로 하락.
  - 노이즈군(`LoginView`, `SignupView` 등): 파생어 매칭 없음. 85점.
  - **결과**: 절대 점수는 15점 낮아졌지만 노이즈 대비 15점 Gap은 완벽하게 유지되며 순위 보존.
