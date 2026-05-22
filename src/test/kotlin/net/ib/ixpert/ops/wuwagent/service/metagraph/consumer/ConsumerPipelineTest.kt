package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer

import com.intellij.openapi.project.Project
import net.ib.ixpert.ops.wuwagent.agent.AgentContext
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*
import org.junit.Assert.*
import org.junit.Test

class ConsumerPipelineTest {

    private fun createAdvancedGraph(): ProjectGraph {
        val files = mutableMapOf<String, FileNode>()
        
        // 1. 공통 구조
        files["src/UserDaoImpl.java"] = FileNode(
            path = "src/UserDaoImpl.java",
            packageName = "com.test",
            className = "UserDaoImpl",
            fileType = SpringFileType.REPOSITORY,
            layer = ArchitectureLayer.PERSISTENCE,
            isInterface = false,
            isAbstract = false,
            implementedInterfaces = listOf("com.test.UserDao"),
            dependedBy = mutableListOf("src/UserServiceImpl.java", "src/ApiServiceImpl.java") // 분기
        )
        
        files["src/UserServiceImpl.java"] = FileNode(
            path = "src/UserServiceImpl.java",
            packageName = "com.test",
            className = "UserServiceImpl",
            fileType = SpringFileType.SERVICE,
            layer = ArchitectureLayer.BUSINESS,
            isInterface = false,
            isAbstract = false,
            dependsOn = mutableListOf("src/UserDaoImpl.java"),
            dependedBy = mutableListOf("src/IpsController.java"),
            riskAssessment = RiskAssessment(0, ChangeRisk.HIGH, emptyList()),
            injections = listOf(
                DependencyInjection("com.test.UserDao", "userDao", InjectionMethod.FIELD, "com.test.UserDaoImpl")
            )
        )

        files["src/ApiServiceImpl.java"] = FileNode(
            path = "src/ApiServiceImpl.java",
            packageName = "com.test",
            className = "ApiServiceImpl",
            fileType = SpringFileType.SERVICE,
            layer = ArchitectureLayer.BUSINESS,
            isInterface = false,
            isAbstract = false,
            dependsOn = mutableListOf("src/UserDaoImpl.java"),
            dependedBy = mutableListOf("src/ApiController.java")
        )

        files["src/ApiController.java"] = FileNode(
            path = "src/ApiController.java",
            packageName = "com.test",
            className = "ApiController",
            fileType = SpringFileType.REST_CONTROLLER,
            layer = ArchitectureLayer.PRESENTATION,
            isInterface = false,
            isAbstract = false,
            dependsOn = mutableListOf("src/ApiServiceImpl.java"),
            injections = listOf(
                DependencyInjection("com.test.ApiService", "apiService", InjectionMethod.FIELD, "com.test.ApiServiceImpl")
            )
        )

        files["src/SurveyServiceImpl.java"] = FileNode(
            path = "src/SurveyServiceImpl.java",
            packageName = "com.test",
            className = "SurveyServiceImpl",
            fileType = SpringFileType.SERVICE,
            layer = ArchitectureLayer.BUSINESS,
            isInterface = false,
            isAbstract = false,
            dependedBy = mutableListOf("src/IpsController.java")
        )

        files["src/IpsController.java"] = FileNode(
            path = "src/IpsController.java",
            packageName = "com.test",
            className = "IpsController",
            fileType = SpringFileType.REST_CONTROLLER,
            layer = ArchitectureLayer.PRESENTATION,
            isInterface = false,
            isAbstract = false,
            apiEndpoints = listOf(ApiEndpoint("GET", "/api/survey", "getSurvey", "SurveyDto")),
            dependsOn = mutableListOf("src/UserServiceImpl.java", "src/SurveyServiceImpl.java") // 합류
        )

        // 2. 허브 노드
        val commonUtilDependedBy = mutableListOf<String>()
        for (i in 1..15) {
            val path = "src/Client$i.java"
            commonUtilDependedBy.add(path)
            files[path] = FileNode(
                path = path,
                packageName = "com.test",
                className = "Client$i",
                fileType = SpringFileType.COMPONENT,
                layer = ArchitectureLayer.COMMON,
                isInterface = false,
                isAbstract = false,
                dependsOn = mutableListOf("src/CommonUtil.java")
            )
        }
        files["src/CommonUtil.java"] = FileNode(
            path = "src/CommonUtil.java",
            packageName = "com.test",
            className = "CommonUtil",
            fileType = SpringFileType.COMPONENT,
            layer = ArchitectureLayer.COMMON,
            isInterface = false,
            isAbstract = false,
            dependedBy = commonUtilDependedBy
        )

        // 3. 순환 의존성
        files["src/CycleA.java"] = FileNode(
            path = "src/CycleA.java",
            packageName = "com.test",
            className = "CycleA",
            fileType = SpringFileType.SERVICE,
            layer = ArchitectureLayer.BUSINESS,
            isInterface = false,
            isAbstract = false,
            dependsOn = mutableListOf("src/CycleB.java"),
            dependedBy = mutableListOf("src/CycleB.java")
        )
        files["src/CycleB.java"] = FileNode(
            path = "src/CycleB.java",
            packageName = "com.test",
            className = "CycleB",
            fileType = SpringFileType.SERVICE,
            layer = ArchitectureLayer.BUSINESS,
            isInterface = false,
            isAbstract = false,
            dependsOn = mutableListOf("src/CycleA.java"),
            dependedBy = mutableListOf("src/CycleA.java")
        )

        // 4. 고립 노드
        files["src/IsolatedDto.java"] = FileNode(
            path = "src/IsolatedDto.java",
            packageName = "com.test",
            className = "IsolatedDto",
            fileType = SpringFileType.DTO,
            layer = ArchitectureLayer.COMMON,
            isInterface = false,
            isAbstract = false
        )

        // 5. 다중 구현체 인터페이스 병합 테스트용
        files["src/PaymentService.java"] = FileNode(
            path = "src/PaymentService.java",
            packageName = "com.test",
            className = "PaymentService",
            fileType = SpringFileType.INTERFACE,
            layer = ArchitectureLayer.BUSINESS,
            isInterface = true,
            isAbstract = true
        )
        files["src/CardPayment.java"] = FileNode(
            path = "src/CardPayment.java",
            packageName = "com.test",
            className = "CardPayment",
            fileType = SpringFileType.SERVICE,
            layer = ArchitectureLayer.BUSINESS,
            isInterface = false,
            isAbstract = false,
            implementedInterfaces = listOf("com.test.PaymentService")
        )
        files["src/BankPayment.java"] = FileNode(
            path = "src/BankPayment.java",
            packageName = "com.test",
            className = "BankPayment",
            fileType = SpringFileType.SERVICE,
            layer = ArchitectureLayer.BUSINESS,
            isInterface = false,
            isAbstract = false,
            implementedInterfaces = listOf("com.test.PaymentService")
        )

        return ProjectGraph(
            generatedAt = "2026-04-28",
            projectRoot = "/test",
            framework = "Spring Boot",
            files = files,
            relationships = emptyList(),
            statistics = GraphStatistics(totalFiles = files.size)
        )
    }

    @Test
    fun testSubGraphExtractor_Branching() {
        val graph = createAdvancedGraph()
        val subGraph = SubGraphExtractor.extract(graph, listOf("UserDaoImpl"))
        
        val up1 = subGraph.upwardDependencies[1]
        assertNotNull(up1)
        assertEquals(2, up1!!.size)
        val classNames = up1.map { it.className }
        assertTrue(classNames.contains("UserServiceImpl"))
        assertTrue(classNames.contains("ApiServiceImpl"))
    }

    @Test
    fun testSubGraphExtractor_Merging() {
        val graph = createAdvancedGraph()
        val subGraph = SubGraphExtractor.extract(graph, listOf("IpsController"))
        
        val down1 = subGraph.downwardDependencies[1]
        assertNotNull(down1)
        assertEquals(2, down1!!.size)
        val classNames = down1.map { it.className }
        assertTrue(classNames.contains("UserServiceImpl"))
        assertTrue(classNames.contains("SurveyServiceImpl"))
    }

    @Test
    fun testSubGraphExtractor_HubNode_MaxNodesLimit() {
        val graph = createAdvancedGraph()
        // maxNodes를 5로 설정하여 15개의 dependedBy를 가진 CommonUtil 테스트
        val subGraph = SubGraphExtractor.extract(graph, listOf("CommonUtil"), maxNodes = 5)
        
        assertTrue(subGraph.isTruncated)
        assertEquals(5, subGraph.totalExtractedNodes)
    }

    @Test
    fun testSubGraphExtractor_Cycle() {
        val graph = createAdvancedGraph()
        // CycleA <-> CycleB 무한 루프 검증 (TimeOut 안 나면 통과)
        val subGraph = SubGraphExtractor.extract(graph, listOf("CycleA"))
        
        val up1 = subGraph.upwardDependencies[1]
        assertNotNull(up1)
        assertEquals(1, up1!!.size)
        assertEquals("CycleB", up1[0].className)
        
        // CycleB의 상향은 다시 CycleA지만, 추출된 상태이므로 up2는 비어야 함
        val up2 = subGraph.upwardDependencies[2]
        assertTrue(up2 == null || up2.isEmpty())
    }

    @Test
    fun testSubGraphExtractor_IsolatedNode() {
        val graph = createAdvancedGraph()
        val subGraph = SubGraphExtractor.extract(graph, listOf("IsolatedDto"))
        
        assertEquals(1, subGraph.targets.size)
        assertTrue(subGraph.upwardDependencies.isEmpty() || subGraph.upwardDependencies.values.all { it.isEmpty() })
        assertTrue(subGraph.downwardDependencies.isEmpty() || subGraph.downwardDependencies.values.all { it.isEmpty() })
    }

    @Test
    fun testSubGraphFormatter_MultipleImplMergePrevention() {
        val graph = createAdvancedGraph()
        val subGraph = SubGraphExtractor.extract(graph, listOf("CardPayment"))
        val markdown = SubGraphFormatter.format(graph, subGraph)
        
        // 2개의 구현체가 있으므로 병합 표기되지 않아야 함
        assertFalse(markdown.contains("PaymentService (impl:"))
        assertTrue(markdown.contains("CardPayment"))
    }

    @Test
    fun testSubGraphFormatter_Injections() {
        val graph = createAdvancedGraph()
        val subGraph = SubGraphExtractor.extract(graph, listOf("UserDaoImpl"))
        val markdown = SubGraphFormatter.format(graph, subGraph)
        
        println("DEBUG_MARKDOWN:\n" + markdown + "\nEND_DEBUG_MARKDOWN")
        // 상향 노드에 "UserDao 주입" 문구가 포함되었는지 확인
        assertTrue(markdown.contains("UserServiceImpl (SERVICE / BUSINESS) -> UserDao 주입"))
    }
    @Test
    fun testSubGraphFormatter_Phase1cFeatures() {
        val graph = createAdvancedGraph()
        
        // 1. Target with Endpoints and 1st degree dependency with HIGH RISK
        val subGraph = SubGraphExtractor.extract(graph, listOf("IpsController", "UserDaoImpl"))
        val markdown = SubGraphFormatter.format(graph, subGraph)
        
        // Endpoints should be visible for IpsController
        assertTrue(markdown.contains("✨ Endpoints: [GET] /api/survey"))
        
        // HIGH RISK should be visible for UserServiceImpl (which is HIGH risk and 1st degree of UserDaoImpl)
        assertTrue(markdown.contains("UserServiceImpl (SERVICE / BUSINESS) -> UserDao 주입 ⚠️ HIGH RISK"))
    }

    @Test
    fun testTargetExtractorStrategy2() {
        val graph = createAdvancedGraph()
        
        // 질문에서 클래스명 매칭 (조사 포함)
        val question = "UserDaoImpl을 수정하면 어떻게 될까요?"
        val classNames = graph.files.values.map { it.className }.toSet()
        
        // 내부 메서드 직접 호출
        val targets = TargetExtractor.extractFromQuestion(question, classNames)
        
        assertEquals(1, targets.size)
        assertEquals("UserDaoImpl", targets[0])
    }
}
