package net.ib.ixpert.ops.wuwagent.agent.stage0

data class TestCase(
    val srId: String,
    val input: SrInput,
    val expectedFiles: Set<String>,         // 반드시 포함되어야 할 파일 (상대 경로 기준 일부 매칭)
    val unexpectedFiles: Set<String> = emptySet(),
    val expectedSrType: SrType,
    val expectedDeficiencyTypes: Set<DeficiencyType> = emptySet()
)

object TestCaseDefinitions {
    val cases = listOf(
        // TC-01: FIELD_ADD
        TestCase(
            srId = "TC-01",
            input = SrInput("TC-01", "상품에 할인율 필드 추가",
                "Product 엔티티에 discountRate(Integer) 필드를 추가하고 상품 등록 화면에서 입력 가능하도록 한다. 상품 상세에도 표시되어야 한다."),
            expectedFiles = setOf("Product.java", "ProductCreateRequest.java", "ProductCreateView.vue", "ProductDetailView.vue"),
            expectedSrType = SrType.FIELD_ADD
        ),
        // TC-02: FIELD_ADD
        TestCase(
            srId = "TC-02",
            input = SrInput("TC-02", "회원 프로필에 자기소개 필드 추가",
                "Member 엔티티에 description(String) 추가. 마이페이지에서 자기소개를 수정하고 볼 수 있어야 한다."),
            expectedFiles = setOf("Member.java", "MemberResponse.java", "MyPageView.vue"),
            expectedSrType = SrType.FIELD_ADD
        ),
        // TC-03: CONDITION_CHANGE
        TestCase(
            srId = "TC-03",
            input = SrInput("TC-03", "상품 목록 정렬 조건 변경",
                "상품 목록 조회 시 기본 정렬을 최신순에서 인기순(조회수 기준)으로 변경한다."),
            expectedFiles = setOf("ProductRepository.java", "ProductListView.vue"),
            expectedSrType = SrType.CONDITION_CHANGE
        ),
        // TC-04: CONDITION_CHANGE
        TestCase(
            srId = "TC-04",
            input = SrInput("TC-04", "가격 표시 천 단위 콤마 적용",
                "상품 상세 화면 및 상품 등록 화면에서 가격을 표시할 때 천 단위마다 콤마를 찍어서 보여주도록 수정한다."),
            expectedFiles = setOf("ProductDetailView.vue", "ProductCreateView.vue"),
            expectedSrType = SrType.CONDITION_CHANGE
        ),
        // TC-05: CONDITION_CHANGE
        TestCase(
            srId = "TC-05",
            input = SrInput("TC-05", "상품 상태 SOLD_OUT 추가",
                "상품 상태를 나타내는 Enum에 SOLD_OUT 상태를 추가하고, 상품 서비스에서 재고가 0이 되면 SOLD_OUT으로 변경하도록 로직 추가."),
            expectedFiles = setOf("ProductStatus.java", "ProductService.java"),
            expectedSrType = SrType.CONDITION_CHANGE
        ),
        // TC-06: BATCH_MODIFY
        TestCase(
            srId = "TC-06",
            input = SrInput("TC-06", "30일 미접속 휴면 회원 처리 배치",
                "매일 자정에 30일 이상 로그인하지 않은 회원을 찾아 휴면 상태로 변경하는 배치 로직을 추가한다."),
            expectedFiles = setOf("MemberRepository.java"), // Scheduler 파일은 새로 만들어질 것이므로 기존 파일 탐색에만 집중
            expectedSrType = SrType.BATCH_MODIFY
        ),
        // TC-07: INTERFACE_CHANGE
        TestCase(
            srId = "TC-07",
            input = SrInput("TC-07", "채팅 메시지 응답에 읽음 여부 필드 추가",
                "채팅방에서 메시지를 가져올 때 응답 DTO에 isRead 필드를 추가하고 뷰에서 읽음 처리를 표시한다."),
            expectedFiles = setOf("ChatMessageResponse.java", "ChatRoomView.vue"),
            expectedSrType = SrType.INTERFACE_CHANGE
        ),
        // TC-08: FIELD_ADD
        TestCase(
            srId = "TC-08",
            input = SrInput("TC-08", "채팅방 목록에 마지막 메시지 미리보기 표시",
                "채팅방 엔티티에 마지막 메시지 필드를 추가하고 채팅방 목록 화면에 미리보기를 표시한다."),
            expectedFiles = setOf("ChatRoom.java", "ChatRoomListView.vue"),
            expectedSrType = SrType.FIELD_ADD
        ),
        // TC-09: CONDITION_CHANGE
        TestCase(
            srId = "TC-09",
            input = SrInput("TC-09", "GlobalExceptionHandler 응답 포맷 변경",
                "Validation 실패 시 발생하는 에러의 응답 포맷을 기존 문자열에서 필드명과 에러메시지를 포함하는 JSON 객체 배열로 변경한다."),
            expectedFiles = setOf("GlobalExceptionHandler.java"),
            expectedSrType = SrType.CONDITION_CHANGE
        ),
        // TC-10: CONDITION_CHANGE
        TestCase(
            srId = "TC-10",
            input = SrInput("TC-10", "상품 검색 N+1 해결",
                "상품 목록 조회 시 판매자 정보 조회를 위해 Fetch Join을 적용하여 N+1 쿼리 문제를 해결한다."),
            expectedFiles = setOf("ProductRepository.java"),
            expectedSrType = SrType.CONDITION_CHANGE
        )
    )
}
