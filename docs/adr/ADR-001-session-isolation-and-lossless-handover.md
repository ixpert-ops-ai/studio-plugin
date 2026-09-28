# ADR-001: 세션 간 거절 정보 승계 전면 폐지 및 메모리 인계 단일화

## 상태 (Status)
**채택됨 (Accepted)** - 2026-09-28

## 문맥 (Context)
과거 시스템은 세션 종료 시 디스크의 `.wuwagent/clarification-contract-default.json`에 거절된 컴포넌트 목록을 저장하고, 다음 세션(`/clarify` 또는 단독 `/analyze`) 시작 시 `previousContract`로 이를 다시 읽어왔습니다.
그러나 이 방식은 서로 다른 요구사항(SR) 간에 이전 세션의 거절 항목이 새로운 요구사항의 유효 후보군을 조용히 탈락시키는 **유령 오염(Ghost Contamination)** 결함을 유발했습니다.
또한 Clarify에서 Analyze로 넘어갈 때 디스크를 경유함으로써 불필요한 IO 지연 및 해시 검증 실패 위험이 존재했습니다.

## 결정 (Decision)
1. **세션 간 거절 정보 승계 전면 폐지**:
   - 신규 `/clarify` 세션 진입 및 단독 `/analyze` 실행 시 `previousContract = null`로 완전히 격리합니다.
   - 디스크의 이전 계약 파일은 단순 감사/디버깅 로그로만 보존되며, 분석 파이프라인의 입력으로 자동 로드되지 않습니다.
2. **동일 세션 내 메모리 인계 단일화**:
   - Clarify 대화 중 사용자가 거절(`Verdict.REJECTED`)한 파일 목록은 `ClarifyIntent.excludedFiles` 필드를 통해 **메모리로만** Analyze 파이프라인에 1급 결정으로 인계됩니다.
   - Stage 2 Trimming 단계에서 `excludedFiles`에 포함된 파일은 100% 원천 배제됩니다.
3. **디스크 로드 호출 0건 아키텍처 가드**:
   - `src/main` 전역에서 `findIntentFile`, `findContractFile`, `loadIntentByKey`, `loadContractByKey` 호출을 0건으로 통제하며, 이를 검증하는 정적 분석 단위 테스트(`AnalyzeInputResolverTest`)를 유지합니다.

## 결과 및 파급 효과 (Consequences)
- **긍정적 효과**: 서로 다른 요구사항 분석 시 이전 세션의 잔존 파일로 인한 precision/recall 왜곡 원천 차단.
- **주의 사항**: 세션을 새로 시작하면 이전 세션의 거절 내역이 승계되지 않으므로, 동일 요건에 대한 재분석은 단일 세션 대화 내에서 수행해야 함.
