package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Adaptive File Discovery 시스템 통합 테스트.
 * 7가지 입력 시나리오를 통해 QueryAnalyzer → SubCollector → CandidateCollector 파이프라인을 검증합니다.
 */
class AdaptiveFileDiscoveryTest {

    private lateinit var graph: ProjectGraph

    @Before
    fun setup() {
        // 테스트용 ProjectGraph 구축
        val files = mapOf(
            "src/main/java/com/example/controller/MemberCardLimitController.java" to FileNode(
                path = "src/main/java/com/example/controller/MemberCardLimitController.java",
                packageName = "com.example.controller",
                className = "MemberCardLimitController",
                fileType = SpringFileType.REST_CONTROLLER,
                layer = ArchitectureLayer.PRESENTATION,
                apiEndpoints = listOf(
                    ApiEndpoint(httpMethod = "GET", path = "/api/v1/members", handlerMethod = "getMembers"),
                    ApiEndpoint(httpMethod = "POST", path = "/api/v1/members/card-limit", handlerMethod = "updateCardLimit")
                ),
                methodNames = listOf("getMembers", "updateCardLimit"),
                koreanComments = listOf("회원 카드 한도 관리 컨트롤러"),
                dependsOn = mutableListOf("src/main/java/com/example/service/MemberCardLimitService.java")
            ),
            "src/main/java/com/example/service/MemberCardLimitService.java" to FileNode(
                path = "src/main/java/com/example/service/MemberCardLimitService.java",
                packageName = "com.example.service",
                className = "MemberCardLimitService",
                fileType = SpringFileType.SERVICE,
                layer = ArchitectureLayer.BUSINESS,
                localName = "회원카드한도",
                methodNames = listOf("selectPsnzInf", "updateCardLimit", "processLimit"),
                koreanComments = listOf("개인화정보 조회 서비스", "카드 한도 변경 처리"),
                dependsOn = mutableListOf("src/main/java/com/example/dao/ACAMTBAPC001DEM.java"),
                dependedBy = mutableListOf("src/main/java/com/example/controller/MemberCardLimitController.java")
            ),
            "src/main/java/com/example/dao/ACAMTBAPC001DEM.java" to FileNode(
                path = "src/main/java/com/example/dao/ACAMTBAPC001DEM.java",
                packageName = "com.example.dao",
                className = "ACAMTBAPC001DEM",
                fileType = SpringFileType.MAPPER,
                layer = ArchitectureLayer.PERSISTENCE,
                anyframeRole = AnyframeRole.DEM,
                demMethods = listOf(
                    DemMethodInfo(
                        methodName = "selectPsnzInf",
                        sqlId = "selectPsnzInf",
                        inputDvoClass = "PsnzInfDVO",
                        returnDvoClass = "PsnzInfDVO",
                        tables = listOf("TB_PSNZ_INF"),
                        operationType = SqlOpType.SELECT,
                        localName = "개인화정보조회"
                    )
                ),
                methodNames = listOf("selectPsnzInf", "insertPsnzInf"),
                dependedBy = mutableListOf("src/main/java/com/example/service/MemberCardLimitService.java")
            ),
            "src/main/java/com/example/svc/SAPCMM0204SVC.java" to FileNode(
                path = "src/main/java/com/example/svc/SAPCMM0204SVC.java",
                packageName = "com.example.svc",
                className = "SAPCMM0204SVC",
                fileType = SpringFileType.SERVICE,
                layer = ArchitectureLayer.BUSINESS,
                anyframeRole = AnyframeRole.SVC,
                serviceEndpoints = listOf(
                    ServiceEndpoint(
                        serviceId = "SAPCMM0204S01",
                        methodName = "executePsnzInfSel",
                        localName = "개인화정보조회",
                        inputSvo = "PsnzInfSVO",
                        outputSvo = "PsnzInfSVO"
                    )
                ),
                methodNames = listOf("executePsnzInfSel")
            ),
            "src/main/java/com/example/entity/CardLimit.java" to FileNode(
                path = "src/main/java/com/example/entity/CardLimit.java",
                packageName = "com.example.entity",
                className = "CardLimit",
                fileType = SpringFileType.ENTITY,
                layer = ArchitectureLayer.PERSISTENCE,
                methodNames = listOf("getLimit", "setLimit", "getCardNumber"),
                koreanComments = listOf("카드 한도 엔티티")
            )
        )

        val relationships = listOf(
            Relationship(
                source = "src/main/java/com/example/controller/MemberCardLimitController.java",
                target = "src/main/java/com/example/service/MemberCardLimitService.java",
                type = RelationshipType.INJECTS
            ),
            Relationship(
                source = "src/main/java/com/example/service/MemberCardLimitService.java",
                target = "src/main/java/com/example/dao/ACAMTBAPC001DEM.java",
                type = RelationshipType.CALLS
            )
        )

        graph = ProjectGraph(
            generatedAt = Instant.now().toString(),
            projectRoot = "/test-project",
            files = files,
            relationships = relationships,
            statistics = GraphStatistics(
                totalFiles = files.size,
                controllers = 1, services = 2, repositories = 1,
                entities = 1, configs = 0, dtos = 0, utils = 0,
                views = 0, components = 0, others = 0,
                totalRelationships = relationships.size
            )
        )
    }

    // ═════════════════════════════════════════════
    // QueryAnalyzer 테스트
    // ═════════════════════════════════════════════

    @Test
    fun `QueryAnalyzer - 순수 한글 입력 분석`() {
        val dictionary = DomainDictionary.load(graph)
        val analyzer = QueryAnalyzer(dictionary)
        val result = analyzer.analyze("개인화정보 조회 오류")

        assertTrue("한글 명사가 추출되어야 합니다", result.koreanNouns.isNotEmpty())
        assertTrue("개인화/조회가 추출되어야 합니다", result.koreanNouns.any { "개인화" in it || "조회" in it })
        assertTrue("정확 식별자가 없어야 합니다", result.exactIdentifiers.isEmpty())
    }

    @Test
    fun `QueryAnalyzer - 클래스명 직접 입력`() {
        val dictionary = DomainDictionary.load(graph)
        val analyzer = QueryAnalyzer(dictionary)
        val result = analyzer.analyze("ACAMTBAPC001DEM selectPsnzInf timeout issue")

        assertTrue("식별자가 추출되어야 합니다", result.exactIdentifiers.isNotEmpty())
        assertTrue("ACAMTBAPC001DEM이 serviceId/식별자로 추출되어야 합니다", result.serviceIds.any { "ACAMTBAPC001DEM" in it })
    }

    @Test
    fun `QueryAnalyzer - ServiceId 입력`() {
        val dictionary = DomainDictionary.load(graph)
        val analyzer = QueryAnalyzer(dictionary)
        val result = analyzer.analyze("SAPCMM0204S01 에러")

        assertTrue("ServiceId가 추출되어야 합니다", result.serviceIds.any { it.contains("SAPCMM0204") })
        assertTrue("한글 명사 '에러'가 추출되어야 합니다", result.koreanNouns.any { "에러" in it })
    }

    @Test
    fun `QueryAnalyzer - URL 패턴 입력`() {
        val dictionary = DomainDictionary.load(graph)
        val analyzer = QueryAnalyzer(dictionary)
        val result = analyzer.analyze("/api/v1/members 500 에러")

        assertTrue("URL 패턴이 추출되어야 합니다", result.urlPatterns.isNotEmpty())
        assertTrue("/api/v1/members가 추출되어야 합니다", result.urlPatterns.any { "/api/v1/members" in it })
    }

    @Test
    fun `QueryAnalyzer - 한영 혼합 입력`() {
        val dictionary = DomainDictionary.load(graph)
        val analyzer = QueryAnalyzer(dictionary)
        val result = analyzer.analyze("MemberCardLimit 조회 수정")

        assertTrue("MemberCardLimit이 식별자로 추출되어야 합니다", result.exactIdentifiers.any { "MemberCardLimit" in it })
        assertTrue("한글 명사 '조회'가 추출되어야 합니다", result.koreanNouns.any { "조회" in it })
        assertTrue("영어 토큰이 존재해야 합니다", result.englishTokens.isNotEmpty())
    }

    // ═════════════════════════════════════════════
    // CandidateCollector 통합 테스트
    // ═════════════════════════════════════════════

    @Test
    fun `CandidateCollector - 클래스명 정확 매칭 시 최고 점수`() {
        val collector = CandidateCollector(graph)
        val results = collector.collect("ACAMTBAPC001DEM timeout")

        assertTrue("결과가 있어야 합니다", results.isNotEmpty())
        val topResult = results.first()
        assertTrue("ACAMTBAPC001DEM이 최상위여야 합니다", topResult.filePath.contains("ACAMTBAPC001DEM"))
        assertTrue("정확 매칭 점수는 150 이상이어야 합니다: ${topResult.score}", topResult.score >= 150.0)
    }

    @Test
    fun `CandidateCollector - ServiceId 정확 매칭`() {
        val collector = CandidateCollector(graph)
        val results = collector.collect("SAPCMM0204S01 에러")

        assertTrue("결과가 있어야 합니다", results.isNotEmpty())
        assertTrue(
            "SAPCMM0204SVC가 결과에 포함되어야 합니다",
            results.any { it.filePath.contains("SAPCMM0204SVC") }
        )
    }

    @Test
    fun `CandidateCollector - URL 패턴 매칭`() {
        val collector = CandidateCollector(graph)
        val results = collector.collect("/api/v1/members 500 에러")

        assertTrue("결과가 있어야 합니다", results.isNotEmpty())
        assertTrue(
            "MemberCardLimitController가 결과에 포함되어야 합니다",
            results.any { it.filePath.contains("MemberCardLimitController") }
        )
    }

    @Test
    fun `CandidateCollector - 한글 LocalName 매칭`() {
        val collector = CandidateCollector(graph)
        val results = collector.collect("개인화정보 조회 오류")

        assertTrue("결과가 있어야 합니다", results.isNotEmpty())
        // 개인화정보조회가 localName이나 DEM.localName에 있는 파일이 매칭되어야 함
        val matchedPaths = results.map { it.filePath }
        assertTrue(
            "개인화정보 관련 파일이 결과에 포함되어야 합니다: $matchedPaths",
            matchedPaths.any { it.contains("ACAMTBAPC001DEM") || it.contains("SAPCMM0204SVC") || it.contains("MemberCardLimitService") }
        )
    }

    @Test
    fun `CandidateCollector - 한영 혼합 시 점수 합산`() {
        val collector = CandidateCollector(graph)
        val results = collector.collect("MemberCardLimit 조회 수정")

        assertTrue("결과가 있어야 합니다", results.isNotEmpty())
        // 정확 식별자 매칭 + 한글 토큰 매칭으로 높은 점수
        val topResult = results.first()
        assertTrue(
            "MemberCardLimit 관련 파일이 최상위여야 합니다: ${topResult.filePath}",
            topResult.filePath.contains("MemberCardLimit")
        )
    }

    @Test
    fun `CandidateCollector - 메서드명 정확 매칭`() {
        val collector = CandidateCollector(graph)
        val results = collector.collect("selectPsnzInf method fix")

        assertTrue("결과가 있어야 합니다", results.isNotEmpty())
        assertTrue(
            "selectPsnzInf 메서드가 있는 파일이 결과에 포함되어야 합니다",
            results.any { it.filePath.contains("ACAMTBAPC001DEM") || it.filePath.contains("MemberCardLimitService") }
        )
    }

    @Test
    fun `CandidateCollector - BFS 확장으로 관련 파일 발견`() {
        val collector = CandidateCollector(graph)
        val results = collector.collect("MemberCardLimitController")

        // Controller를 직접 매칭한 뒤 BFS로 Service도 발견해야 함
        val matchedPaths = results.map { it.filePath }
        assertTrue("Controller가 포함되어야 합니다", matchedPaths.any { it.contains("Controller") })
        assertTrue("BFS 확장으로 Service도 포함되어야 합니다", matchedPaths.any { it.contains("Service") })
    }

    // ═════════════════════════════════════════════
    // DomainDictionary 테스트
    // ═════════════════════════════════════════════

    @Test
    fun `DomainDictionary - 내장 사전 번역`() {
        val dictionary = DomainDictionary.load(graph)
        val result = dictionary.translate("조회")
        assertTrue("조회 → search 번역이 되어야 합니다: $result", result.contains("search"))
    }

    @Test
    fun `DomainDictionary - 그래프 학습 번역`() {
        val dictionary = DomainDictionary.load(graph)
        // graph에서 localName="회원카드한도" → className="MemberCardLimitService" 학습
        val result = dictionary.translate("회원카드한도")
        assertTrue("그래프에서 학습한 번역이 있어야 합니다: $result", result.isNotEmpty())
    }

    @Test
    fun `DomainDictionary - CamelCase 토큰화`() {
        val tokens = DomainDictionary.tokenizeCamelCase("MemberCardLimitService")
        assertTrue("CamelCase가 올바르게 분해되어야 합니다: $tokens", 
            tokens.containsAll(listOf("member", "card", "limit", "service")))
    }
}
