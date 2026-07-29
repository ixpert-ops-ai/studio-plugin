# Phase 3 점수압축 실측 리포트 (member-market SR-01)

## 1. 테스트 목적
`AdaptiveFileDiscovery`의 Phase 3(`RelevanceScorer`) 과정에서 실제 정답 파일들이 어떤 점수대(score)를 받는지, 그리고 컷라인(limit) 근처에서 파일들이 어떻게 분포하는지를 확인하여 "조용한 누락(Silent Omission)"의 위험성을 실측합니다.

## 2. 테스트 대상
- **프로젝트**: `member-market`
- **시나리오**: `[SR-01] 상품 원산지 필드 추가`
- **정답 파일 (Ground Truth)**:
  - `Product.java`, `ProductController.java`, `ProductCreateRequest.java`, `ProductResponse.java`, `ProductService.java`, `ProductRepository.java`

## 3. 실측 결과 (점수 분포)

전체 16개 파일이 확장(Hop 0~1)되었으며, 점수 분포는 다음과 같습니다.

| 점수 | Hop | 파일명 (클래스) | 비고 |
|:---:|:---:|:---|:---|
| **155** | 0 | `Product` | **정답 (Seed)** |
| **155** | 0 | `ProductController` | **정답 (Seed)** |
| **135** | 0 | `ProductCreateRequest` | **정답 (Seed)** |
| **135** | 0 | `ProductResponse` | **정답 (Seed)** |
| **122** | 1 | `ProductService` | **정답** |
| 115 | 1 | `ProductCreateView.vue` | 관련 화면 |
| 115 | 1 | `ProductDetailView.vue` | 관련 화면 |
| 115 | 1 | `ProductListView.vue` | 관련 화면 |
| 115 | 1 | `LoginView.vue` | 무관 노이즈 |
| 115 | 1 | `SignupView.vue` | 무관 노이즈 |
| 115 | 1 | `HomeView.vue` | 무관 노이즈 |
| 115 | 1 | `MyPageView.vue` | 무관 노이즈 |
| 115 | 1 | `MyProductListView.vue` | 무관 노이즈 |
| **110** | 1 | `ProductRepository` | **정답 (최하점)** |
| 105 | 1 | `ProductImage` | 무관 (노이즈) |
| 105 | 1 | `ProductListResponse` | 무관 (노이즈) |

## 4. 핵심 인사이트 및 결론

**① 컷라인의 극단적 위험성 (조용한 누락 입증)**
가장 충격적인 발견은 **무관한 노이즈인 `LoginView.vue`(115점)가 실제 정답인 `ProductRepository`(110점)보다 높은 점수를 받았다**는 점입니다.
만약 이 SR에서 확장된 파일이 30개를 넘어 컷라인이 110점 위에서 형성되었다면, 스코어링 로직은 **무관한 화면 파일들을 살리고, 진짜 고쳐야 할 `ProductRepository`를 조용히 누락(Drop)**시켰을 것입니다.

**② "촘촘한 점수대 = 능동적 개입" 가설의 완벽한 증명**
점수대를 보면 155~122점(확실 구간)과 115~105점(경계/애매 구간)으로 극명하게 나뉩니다.
이처럼 **컷라인 근처(115~105점)에 정답과 오답이 촘촘히 섞여 있는 경우, 도구가 기계적으로 자르는 것은 매우 위험**합니다. 이 데이터는 문서 6번에 정의한 `점수분포로 자기신뢰도 판단 → 뚜렷하면 가볍게, 촘촘하면 적극개입` 및 `3구간 분류(고신뢰/중/경계). 경계만 질문` 아키텍처가 선택이 아닌 필수임을 수치로 증명합니다.
