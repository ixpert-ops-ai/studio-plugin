package net.ib.ixpert.ops.wuwagent.agent.completeness

import net.ib.ixpert.ops.wuwagent.agent.completeness.model.SrFacts
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType
import org.junit.Test

/**
 * 섀도우 모드 가드 판정 지표 수집용 시나리오 스크립트.
 * 이 클래스는 실제 CI 파이프라인에서 실행될 필요는 없으나,
 * 섀도우 로직의 WOULD_BLOCK, False Positive, 미분류 건수를
 * 분석하기 위해 직접 실행해볼 수 있는 진입점입니다.
 */
class CompletenessGuardScenarioScriptTest {

    @Test
    fun `시나리오 1 - Anyframe 정상적인 누락 없는 3계층 변경`() {
        println("=== [Scenario 1] Anyframe 3계층 정상 반영 (누락 없음) ===")
        val ctx = RulesetTestFixtures.anyframeFixtureCtx()
        val guard = CompletenessGuardIntegration(GuardMode.SHADOW)
        
        // Controller, Service, ServiceImpl, Dao, DaoImpl, XML 모두 포함
        val targetFiles = setOf(
            "src/main/java/OrderController.java",
            "src/main/java/OrderService.java",
            "src/main/java/OrderServiceImpl.java",
            "src/main/java/OrderDao.java",
            "src/main/java/OrderDaoImpl.java",
            "src/main/resources/mapper/OrderDao.xml"
        )
        val srFacts = SrFacts(true, true, true, true, true)
        
        guard.evaluateAfterVerifier(FrameworkType.ANYFRAME_AP, targetFiles, srFacts, ctx)
        println("========================================================\n")
    }

    @Test
    fun `시나리오 2 - Anyframe XML 매퍼 누락 (흔한 실수)`() {
        println("=== [Scenario 2] Anyframe XML 매퍼 누락 (WOULD_BLOCK 예상) ===")
        val ctx = RulesetTestFixtures.anyframeFixtureCtx()
        val guard = CompletenessGuardIntegration(GuardMode.SHADOW)
        
        // DAO와 Impl은 있지만 XML이 빠짐
        val targetFiles = setOf(
            "src/main/java/OrderController.java",
            "src/main/java/OrderService.java",
            "src/main/java/OrderServiceImpl.java",
            "src/main/java/OrderDao.java",
            "src/main/java/OrderDaoImpl.java"
        )
        val srFacts = SrFacts(true, true, true, true, true)
        
        guard.evaluateAfterVerifier(FrameworkType.ANYFRAME_AP, targetFiles, srFacts, ctx)
        println("========================================================\n")
    }

    @Test
    fun `시나리오 3 - Anyframe addsNewMethod=false인 경우 (스킵 예상)`() {
        println("=== [Scenario 3] Anyframe 단순 로직 변경 (addsNewMethod=false) ===")
        val ctx = RulesetTestFixtures.anyframeFixtureCtx()
        val guard = CompletenessGuardIntegration(GuardMode.SHADOW)
        
        // Controller, ServiceImpl만 수정됨. 새 메서드가 없으므로 인터페이스 변경 불필요
        val targetFiles = setOf(
            "src/main/java/OrderController.java",
            "src/main/java/OrderServiceImpl.java"
        )
        val srFacts = SrFacts(true, true, true, true, false)
        
        guard.evaluateAfterVerifier(FrameworkType.ANYFRAME_AP, targetFiles, srFacts, ctx)
        println("========================================================\n")
    }

    @Test
    fun `시나리오 4 - Spring Boot JPA Repository 누락`() {
        println("=== [Scenario 4] Boot JPA Repository 누락 (WOULD_BLOCK 예상) ===")
        val ctx = RulesetTestFixtures.jpaFixtureCtx()
        val guard = CompletenessGuardIntegration(GuardMode.SHADOW)
        
        val targetFiles = setOf(
            "src/main/java/OrderController.java",
            "src/main/java/OrderServiceImpl.java"
        )
        val srFacts = SrFacts(true, true, true, true, true)
        
        guard.evaluateAfterVerifier(FrameworkType.SPRING_BOOT_JPA, targetFiles, srFacts, ctx)
        println("========================================================\n")
    }
    @Test
    fun `시나리오 5 - 실제 Survey CSV 프로젝트 그래프를 이용한 누락 검증`() {
        println("=== [Scenario 5] 실제 Survey CSV 프로젝트 (DaoImpl, ServiceImpl만 존재) ===")
        val file = java.io.File("C:/Workspace/HC_card_survey_admin/survey_admin/.meta/project-graph.json")
        if (!file.exists()) {
            println("실제 그래프 파일이 존재하지 않아 건너뜁니다.")
            return
        }

        val jsonContent = file.readText(Charsets.UTF_8)
        val gson = com.google.gson.Gson()
        val parsedGraph = gson.fromJson(jsonContent, net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph::class.java)
        
        val ctx = ProjectGraphAdapter(parsedGraph)
        val guard = CompletenessGuardIntegration(GuardMode.SHADOW)
        
        // Survey CSV에서 누락된 케이스: Controller, Service Interface, DAO Interface, XML이 빠져있음
        val targetFiles = setOf(
            "src/main/java/net/infobank/iss/survey/dao/SurveyDaoImpl.java",
            "src/main/java/net/infobank/iss/survey/service/SurveyServiceImpl.java"
        )
        val srFacts = SrFacts(
            hasUserAction = true, // Excel 다운로드는 화면(Controller/뷰) 액션 수반
            touchesUi = true,
            readsOrWritesData = true,
            hasBusinessLogic = true,
            addsNewMethod = true
        )
        
        guard.evaluateAfterVerifier(parsedGraph.frameworkType, targetFiles, srFacts, ctx)
        println("========================================================\n")
    }
}
