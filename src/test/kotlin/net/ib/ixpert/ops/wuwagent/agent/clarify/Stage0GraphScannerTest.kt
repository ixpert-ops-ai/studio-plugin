package net.ib.ixpert.ops.wuwagent.agent.clarify

import net.ib.ixpert.ops.wuwagent.agent.clarify.model.*
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/**
 * Stage 0 규칙 불변식(Invariant) 단위 테스트.
 * 특정 프로젝트의 파일명 리터럴에 의존하지 않고, 인메모리 합성 그래프를 통해 5대 핵심 메커니즘을 검증합니다.
 */
class Stage0GraphScannerTest {

    private fun createSyntheticGraph(): ProjectGraph {
        val files = mapOf(
            "com/example/OrderDto.java" to FileNode(
                path = "com/example/OrderDto.java",
                packageName = "com.example",
                className = "OrderDto",
                fileType = SpringFileType.DTO,
                layer = ArchitectureLayer.PERSISTENCE,
                methods = listOf(
                    MethodSignature("getPay_type", "String", emptyList()),
                    MethodSignature("setPay_type", "void", listOf("String")),
                    MethodSignature("getReg_user_id", "String", emptyList()), // generic audit
                    MethodSignature("getPage_start", "int", emptyList())      // generic pagination
                )
            ),
            "com/example/OrderService.java" to FileNode(
                path = "com/example/OrderService.java",
                packageName = "com.example",
                className = "OrderService",
                fileType = SpringFileType.SERVICE,
                layer = ArchitectureLayer.BUSINESS,
                methods = listOf(
                    MethodSignature("processOrder", "void", listOf("OrderDto"))
                )
            )
        )

        val resourceNodes = listOf(
            // JSP 1: 도메인 필드 test_pay_type 보유
            ResourceNode(
                path = "webapp/views/order_list.jsp",
                type = ResourceType.VIEW,
                layer = "PRESENTATION",
                linkedTo = listOf("com/example/OrderController.java"),
                linkType = "url_binding",
                metadata = mapOf(
                    "input_field" to listOf("test_pay_type", "search_keyword")
                ),
                dynamicBindings = listOf(
                    DynamicBinding("webapp/views/order_list.jsp", "com/example/OrderController.java", "/order/orderList", 80.0)
                )
            ),
            // JS 1: JSP 1과 동일 URL (/order/orderList) 공유하는 페어 스크립트 (메타데이터에 pay_type 직접 없음)
            ResourceNode(
                path = "webapp/js/order.list.js",
                type = ResourceType.SCRIPT,
                layer = "PRESENTATION",
                linkedTo = listOf("com/example/OrderController.java"),
                linkType = "url_binding",
                metadata = mapOf(
                    "methods" to listOf("initView", "renderList")
                ),
                dynamicBindings = listOf(
                    DynamicBinding("webapp/js/order.list.js", "com/example/OrderController.java", "/order/orderList", 80.0)
                )
            ),
            // 무관 파일: audit/pagination 필드만 보유
            ResourceNode(
                path = "webapp/views/login.jsp",
                type = ResourceType.VIEW,
                layer = "PRESENTATION",
                linkedTo = listOf("com/example/LoginController.java"),
                linkType = "url_binding",
                metadata = mapOf(
                    "input_field" to listOf("reg_user_id", "login_id")
                ),
                dynamicBindings = listOf(
                    DynamicBinding("webapp/views/login.jsp", "com/example/LoginController.java", "/login", 80.0)
                )
            ),
            // 경계 매칭 검증용 파일: "sender" 보유 (pay_type과는 무관)
            ResourceNode(
                path = "webapp/views/sender_list.jsp",
                type = ResourceType.VIEW,
                layer = "PRESENTATION",
                linkedTo = listOf("com/example/SenderController.java"),
                linkType = "url_binding",
                metadata = mapOf(
                    "input_field" to listOf("sender_name")
                ),
                dynamicBindings = listOf(
                    DynamicBinding("webapp/views/sender_list.jsp", "com/example/SenderController.java", "/sender/list", 80.0)
                )
            )
        )

        val relationships = listOf(
            Relationship(
                source = "com/example/OrderService.java",
                target = "com/example/OrderDto.java",
                type = RelationshipType.CALLS,
                strength = RelationshipStrength.DIRECT
            )
        )

        return ProjectGraph(
            generatedAt = Instant.now().toString(),
            projectRoot = "/test/root",
            files = files,
            resourceNodes = resourceNodes,
            relationships = relationships,
            statistics = GraphStatistics()
        )
    }

    /**
     * 불변식 1 & 2: 구조 식별자 우선권 및 스네이크케이스 경계 매칭 (\b, _)
     * - pay_type은 test_pay_type에 매칭되어야 함.
     * - sender_name 등 경계가 다른 단어는 절대 매칭되지 않아야 함.
     */
    @Test
    fun testInvariant_StructuralTokenExtractionAndBoundaryMatching() {
        val graph = createSyntheticGraph()
        val scanner = Stage0GraphScanner(
            graph = graph,
            minSpecificityScore = 1.0,
            proposalBudget = 5,
            localDomainOverrides = mapOf("주문" to setOf("order"))
        )

        val tokens = scanner.extractTokens("주문 결제수단 추가")
        val structuralValues = tokens.filter { it.kind == TokenKind.STRUCTURAL }.map { it.value.lowercase() }
        
        // OrderDto로부터 pay_type 추출 확인 (audit 필드 reg_user_id, page_start는 제외되어야 함)
        assertTrue(structuralValues.contains("pay_type"))
        assertFalse(structuralValues.contains("reg_user_id"))
        assertFalse(structuralValues.contains("page_start"))

        val candidates = scanner.rescanUnverified(tokens, emptyList())
        val candidatePaths = candidates.mapNotNull { (it.hint as? LinkHint.ExistingRef)?.filePath }

        // test_pay_type을 가진 order_list.jsp는 후보에 포함되어야 함
        assertTrue(candidatePaths.contains("webapp/views/order_list.jsp"))
        // sender_list.jsp는 매칭되지 않아야 함
        assertFalse(candidatePaths.contains("webapp/views/sender_list.jsp"))
    }

    /**
     * 불변식 3: View-Script URL 페어링 엣지 확장
     * - order_list.jsp가 매칭되면, 동일 URL(/order/orderList)을 공유하는 order.list.js도 자동으로 후보로 승격되어야 함.
     */
    @Test
    fun testInvariant_ViewScriptPairing() {
        val graph = createSyntheticGraph()
        val scanner = Stage0GraphScanner(
            graph = graph,
            minSpecificityScore = 1.0,
            proposalBudget = 5,
            localDomainOverrides = mapOf("주문" to setOf("order"))
        )

        val tokens = scanner.extractTokens("주문 결제수단 추가")
        val candidates = scanner.rescanUnverified(tokens, emptyList())
        val candidatePaths = candidates.mapNotNull { (it.hint as? LinkHint.ExistingRef)?.filePath }

        assertTrue(candidatePaths.contains("webapp/js/order.list.js"))
    }

    /**
     * 불변식 4: 저특이성/Audit 단독 매칭 차단
     * - login.jsp처럼 범용/audit 필드만 가진 파일은 minSpecificityScore에 의해 0건 차단되어야 함.
     */
    @Test
    fun testInvariant_GenericAuditCutoff() {
        val graph = createSyntheticGraph()
        val scanner = Stage0GraphScanner(
            graph = graph,
            minSpecificityScore = 1.0,
            proposalBudget = 5,
            localDomainOverrides = mapOf("주문" to setOf("order"))
        )

        val tokens = scanner.extractTokens("주문 결제수단 추가")
        val candidates = scanner.rescanUnverified(tokens, emptyList())
        val candidatePaths = candidates.mapNotNull { (it.hint as? LinkHint.ExistingRef)?.filePath }

        assertFalse(candidatePaths.contains("webapp/views/login.jsp"))
    }

    /**
     * 불변식 5: 동결 불변성 (mergeCandidates)
     * - 사용자가 CONFIRMED 또는 REJECTED로 확정한 단위는 이후 재탐색에서 절대 덮어써지거나 번복되지 않음.
     */
    @Test
    fun testInvariant_FrozenProtection() {
        val graph = createSyntheticGraph()
        val scanner = Stage0GraphScanner(
            graph = graph,
            minSpecificityScore = 1.0,
            proposalBudget = 5,
            localDomainOverrides = mapOf("주문" to setOf("order"))
        )
        val engine = Stage0ClarificationEngine(scanner, graph)

        val turn0 = engine.initSession("주문 결제수단 추가")
        val targetItem = turn0.state.items.first { (it.hint as? LinkHint.ExistingRef)?.filePath == "webapp/views/order_list.jsp" }

        // 사용자가 확정 (CONFIRMED)
        val turn1 = engine.processTurn(
            turn0.state,
            Stage0ClarificationEngine.UserInput(
                verdictUpdates = mapOf(targetItem.id to Verdict.CONFIRMED),
                userStatement = "추가 요구사항 발화"
            )
        )

        val confirmedItem = turn1.state.items.find { it.id == targetItem.id }
        assertNotNull(confirmedItem)
        assertEquals(Verdict.CONFIRMED, confirmedItem?.verdict)
        assertEquals(HintSource.USER_CONFIRMED, confirmedItem?.source)
    }

    /**
     * 불변식 6: localName 결정론적 매칭 및 다채널 교차검증 신뢰도 버킷팅
     * - localDomainOverrides 없이 순수 localName만으로 진입점 시드 회수
     * - View-Script 페어링이나 Java 관계 등 그래프 엣지로 교차 검증된 노드는 HIGH_CONFIDENCE
     * - 그래프 엣지 없이 localName 텍스트 일치만 단독 존재하는 노드는 LOW_CONFIDENCE로 자동 격하
     */
    @Test
    fun testInvariant_LocalNameMatchingAndCrossValidationPromotion() {
        val files = mapOf(
            "com/example/OrderService.java" to FileNode(
                path = "com/example/OrderService.java",
                packageName = "com.example",
                className = "OrderService",
                localName = "주문 서비스",
                fileType = SpringFileType.SERVICE,
                layer = ArchitectureLayer.BUSINESS,
                methods = listOf(MethodSignature("processOrder", "void", emptyList()))
            ),
            "com/example/OrderDao.java" to FileNode(
                path = "com/example/OrderDao.java",
                packageName = "com.example",
                className = "OrderDao",
                fileType = SpringFileType.REPOSITORY,
                layer = ArchitectureLayer.PERSISTENCE,
                methods = listOf(MethodSignature("selectOrderList", "List", emptyList()))
            )
        )

        val resourceNodes = listOf(
            // JSP 1: localName "주문 관리", URL /order/list 보유
            ResourceNode(
                path = "webapp/views/order_list.jsp",
                type = ResourceType.VIEW,
                layer = "PRESENTATION",
                linkedTo = emptyList(),
                linkType = "url_binding",
                metadata = emptyMap(),
                localName = "주문 관리",
                dynamicBindings = listOf(
                    DynamicBinding("webapp/views/order_list.jsp", "com/example/OrderController.java", "/order/list", 80.0)
                )
            ),
            // JS 1: URL /order/list 공유 페어
            ResourceNode(
                path = "webapp/js/order.list.js",
                type = ResourceType.SCRIPT,
                layer = "PRESENTATION",
                linkedTo = emptyList(),
                linkType = "url_binding",
                metadata = emptyMap(),
                dynamicBindings = listOf(
                    DynamicBinding("webapp/js/order.list.js", "com/example/OrderController.java", "/order/list", 80.0)
                )
            ),
            // JSP 2: localName "주문 통계" 단독 보유 (페어링 및 연결 엣지 없음 -> 고립 노이즈 후보)
            ResourceNode(
                path = "webapp/views/order_stat.jsp",
                type = ResourceType.VIEW,
                layer = "PRESENTATION",
                linkedTo = emptyList(),
                linkType = "",
                metadata = emptyMap(),
                localName = "주문 통계",
                dynamicBindings = emptyList()
            )
        )

        val relationships = listOf(
            Relationship(
                source = "com/example/OrderService.java",
                target = "com/example/OrderDao.java",
                type = RelationshipType.INJECTS,
                strength = RelationshipStrength.DIRECT
            )
        )

        val graph = ProjectGraph(
            generatedAt = Instant.now().toString(),
            projectRoot = "/test/root",
            files = files,
            resourceNodes = resourceNodes,
            relationships = relationships,
            statistics = GraphStatistics()
        )

        // localDomainOverrides가 완전히 비어있는(emptyMap()) 상태로 스캔 실행
        val scanner = Stage0GraphScanner(
            graph = graph,
            minSpecificityScore = 1.0,
            proposalBudget = 10,
            localDomainOverrides = emptyMap()
        )

        val tokens = scanner.extractTokens("주문 관리 통계 기능 추가")
        val candidates = scanner.rescanUnverified(tokens, emptyList())

        val highConfidenceItems = candidates.filter { it.confidence == ConfidenceBucket.HIGH_CONFIDENCE }
        val lowConfidenceItems = candidates.filter { it.confidence == ConfidenceBucket.LOW_CONFIDENCE }

        val highPaths = highConfidenceItems.mapNotNull { (it.hint as? LinkHint.ExistingRef)?.filePath }
        val lowPaths = lowConfidenceItems.mapNotNull { (it.hint as? LinkHint.ExistingRef)?.filePath }

        // 1. URL 페어링으로 교차 검증된 order_list.jsp & order.list.js는 HIGH_CONFIDENCE
        assertTrue("order_list.jsp는 교차검증되어 HIGH_CONFIDENCE여야 함", highPaths.contains("webapp/views/order_list.jsp"))
        assertTrue("order.list.js는 교차검증되어 HIGH_CONFIDENCE여야 함", highPaths.contains("webapp/js/order.list.js"))

        // 2. INJECTS 관계로 연결된 OrderDao는 HIGH_CONFIDENCE
        assertTrue("OrderDao.java는 관계 전파로 HIGH_CONFIDENCE여야 함", highPaths.contains("com/example/OrderDao.java"))

        // 3. 엣지 없이 단독 localName("주문 통계")만 매칭된 order_stat.jsp는 LOW_CONFIDENCE로 자동 격하
        assertTrue("order_stat.jsp는 단독 텍스트 매칭으로 LOW_CONFIDENCE로 격하되어야 함", lowPaths.contains("webapp/views/order_stat.jsp"))
        assertFalse("order_stat.jsp는 HIGH_CONFIDENCE에 혼입되지 않아야 함", highPaths.contains("webapp/views/order_stat.jsp"))
    }
}
