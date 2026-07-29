# Spring Boot JPA Completeness Ruleset 개편 계획 (복원됨)

본 문서는 `implementation_plan.md` 덮어쓰기로 인해 유실된 과거의 Completeness Ruleset (Companion 추천) 설계 문서를 복원한 것입니다.

## 1. 배경 및 문제 상황
- **기존 MatchStrategy의 한계**: `MatchStrategy.match`가 단일 `MatchResult`만 반환하도록 설계되어 있어, 하나의 파일(예: `MemberRepository`)이 여러 개의 파일(예: 여러 개의 `Service`)에 주입(Injected)되는 1:N 관계를 표현하지 못했습니다.
- **Companion 추천(동반 수정 추천) 누락**: 이로 인해 Completeness Ruleset이 `JPA_REPOSITORY` 변경 시 영향을 받는 모든 의존 파일들을 완벽히 추천하지 못하는 조용한 누락(Silent Omission)이 발생했습니다.

## 2. 핵심 설계 방향 (하드 제약)

### A. 1:N 반환 지원 (InjectedByMatch)
- `MatchStrategy.match`의 시그니처를 `List<MatchResult>`를 반환하도록 리팩토링합니다.
- `InjectedByMatch` 전략을 도입하여, 대상 파일(Anchor Node)에 의존하는(dependedBy) 모든 파일 목록을 반환하도록 합니다.

### B. Dedup (중복 제거)
- 1:N 반환이 가능해짐에 따라, 동일한 파일이 여러 경로를 통해 추천될 수 있습니다.
- `CompletenessEngine`에서 `distinctBy`를 활용하여 동일한 파일 경로에 대한 `CompanionFinding`이 중복 생성되지 않도록 필터링 로직을 추가합니다.

### C. 고립 Entity False Positive 억제
- 다른 어떤 파일에도 주입되지 않고 단독으로 존재하는 Entity (예: `Shop`, `ProductImage`, `Transaction` 등)에 대해, 의존성이 없다고 해서 Completeness Violation(경고)을 발생시키면 안 됩니다.
- 이러한 고립 Entity들이 불필요한 노이즈(FP)를 만들지 않도록 예외 처리 혹은 `Negative Precision` 검증 로직을 포함합니다.

## 3. 검증 계획 (Verification Plan)
- **다중 반환 검증**: 하나의 `Repository` 변경 시, 그것을 주입받는 4개의 `Service`와 1개의 `Controller`가 모두 추천(Companion) 목록에 뜨는지 확인.
- **Dedup 검증**: 추천 목록에 중복된 항목이 없는지 확인.
- **FP 억제 검증**: 의존성이 없는 고립 Entity 변경 시 어떠한 Completeness Violation도 발생하지 않는지 확인.

> [!NOTE]
> 이 파일은 과거 트랙의 설계 논거를 보존하기 위해 작성되었습니다. 현재 1순위 작업(과거 CI 목록 수집 및 Stage 8 출력 재설계)과는 무관하며, 추후 Companion 추천 작업을 재개할 때 참고용으로 사용됩니다.
