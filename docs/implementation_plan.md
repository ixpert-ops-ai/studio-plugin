# Implementation Plan: Source-Based Keyword Weighting

## Goal Description
`RelevanceScorer`가 도메인 사전의 파생어(Translated English)와 원문 직접어(Direct English)를 동일한 가중치로 취급하여 발생하는 노이즈 오탐(SR-01의 `SignupView` 115점 동점 현상)을 해결합니다. 키워드의 출처를 분리하고, 파생어에는 낮은 가중치를 부여하여 정답군과 노이즈군 사이에 명확한 점수 골(Gap)을 형성합니다.

## User Review Required
- **[별도 관찰 항목] DomainDictionary의 부분 일치 과잉 문제**: 
  현재 파생어 오염의 근본 원인은 `DomainDictionary.translate()`가 `key.contains(koreanNoun)`라는 부분 일치 로직을 사용하기 때문입니다. ("등록" -> "카드 등록" -> `card` 파생). 이 문제는 다른 SR의 정상 번역을 깰 위험이 있으므로 이번 커밋에서는 수정하지 않고, 구조적 리스크로 기록만 해둡니다.

## Open Questions
- (None) 설계 방향과 제약 사항이 모두 합의되었습니다.

## Proposed Changes

### 1. `ExtractedKeywords` 자료구조 변경 (출처 태그 분리)
현재 `english`라는 단일 리스트로 섞여 있는 영단어 풀을 두 개로 분리합니다.
#### [MODIFY] `RelevanceScorer.kt`
- `ExtractedKeywords` 클래스를 수정: `val directEnglish: List<String>`, `val translatedEnglish: List<String>`
- `extractKeywords` 메서드 수정: 정규식으로 추출한 단어는 `directEnglish`에, `dictionary.translate`로 얻은 단어는 `translatedEnglish`에 담아 반환.

### 2. 출처 기반 가중치 차등 로직 적용
프론트엔드(`ResourceNode`)와 백엔드(`FileNode`)의 이름 매칭 점수(`nameMatchScore`)를 출처에 따라 다르게 부여합니다.
#### [MODIFY] `RelevanceScorer.kt`
- **ResourceNode**: `directEnglish` 매칭 시 +30점, 매칭되지 않고 `translatedEnglish` 매칭 시 +15점 (파생어 감점 효과)
- **FileNode**: `directEnglish` 매칭 시 +20점, 매칭되지 않고 `translatedEnglish` 매칭 시 +10점 (파생어 감점 효과)

## Verification Plan
### Automated Tests
SR-01과 SR-03을 하나의 테스트 스위트에서 동시 실행하여 회귀(Regression)를 방지합니다.
- 실행 명령: `./gradlew cleanTest test --tests "*Phase3ScoreMeasurementTest*" -i`
- **합격 기준**:
  1. **SR-01**: 정답 최하위(`ProductCreateView`) 115점, 노이즈 최상위(`SignupView`) 100점으로 **Gap ≥ 10점** 확보. (Mock은 수정하지 않은 상태 유지)
  2. **SR-03**: 기존 정답군(`ProductListView` 등 115점 이상)의 점수와 순위가 파생어 감점 로직에 의해 훼손되지 않고 **불변**할 것.
