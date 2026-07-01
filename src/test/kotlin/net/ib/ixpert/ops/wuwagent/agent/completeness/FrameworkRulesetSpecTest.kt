package net.ib.ixpert.ops.wuwagent.agent.completeness

import net.ib.ixpert.ops.wuwagent.agent.completeness.model.*
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType
import org.junit.Assert.*
import org.junit.Test
import net.ib.ixpert.ops.wuwagent.agent.completeness.RulesetTestFixtures.jpaFixtureCtx
import net.ib.ixpert.ops.wuwagent.agent.completeness.RulesetTestFixtures.bootMybatisFixtureCtx
import net.ib.ixpert.ops.wuwagent.agent.completeness.RulesetTestFixtures.jdbcFixtureCtx
import net.ib.ixpert.ops.wuwagent.agent.completeness.RulesetTestFixtures.anyframeFixtureCtx
import net.ib.ixpert.ops.wuwagent.agent.completeness.RulesetTestFixtures.customFixtureCtx

class FrameworkRulesetSpecTest {
    private val csvSr = SrFacts(hasUserAction = true, readsOrWritesData = true, hasBusinessLogic = true, touchesUi = true, addsNewMethod = true)

    @Test fun `JPA - Repository만 있으면 PERSISTENCE 통과하고 Impl을 요구하지 않는다`() {
        val ctx = jpaFixtureCtx()
        val REPO = "src/main/java/OrderRepository.java"
        val SVC_IMPL = "src/main/java/OrderServiceImpl.java"
        val CTRL = "src/main/java/OrderController.java"
        
        val report = CompletenessEngine(ctx).evaluate(
            FrameworkType.SPRING_BOOT_JPA, setOf(REPO, SVC_IMPL, CTRL), csvSr)
        
        assertTrue(report.companionViolations.none { it.companionKind == FileKind.DAO_IMPL })
    }

    @Test fun `Boot-MyBatis - XML 누락은 차단하지만 DaoImpl은 절대 요구하지 않는다`() {
        val ctx = bootMybatisFixtureCtx()
        val MAPPER = "src/main/java/OrderMapper.java"
        val SVC_IMPL = "src/main/java/OrderServiceImpl.java"
        val CTRL = "src/main/java/OrderController.java"
        
        val report = CompletenessEngine(ctx).evaluate(
            FrameworkType.SPRING_BOOT_MYBATIS, setOf(MAPPER, SVC_IMPL, CTRL), csvSr)
        
        assertTrue(report.companionViolations.any { it.companionKind == FileKind.MYBATIS_XML }) // XML 잡음
        assertTrue(report.companionViolations.none { it.companionKind == FileKind.DAO_IMPL })   // Impl 안 잡음 ★
    }

    @Test fun `JDBC - DAO 클래스 단독으로 PERSISTENCE 통과`() {
        val ctx = jdbcFixtureCtx()
        val DAO_CLASS = "src/main/java/OrderDao.java"
        val SVC_IMPL = "src/main/java/OrderServiceImpl.java"
        val CTRL = "src/main/java/OrderController.java"

        val report = CompletenessEngine(ctx).evaluate(
            FrameworkType.SPRING_BOOT_JDBC, setOf(DAO_CLASS, SVC_IMPL, CTRL), csvSr)
        assertTrue(report.roleViolations.none { it.role == ArchRole.PERSISTENCE })
    }

    @org.junit.Ignore("Replaced by AnyframeApRulesetSpecTest")
    @Test fun `Anyframe - DVO만 바꿔도 BVO SVO는 RECOMMENDED라 차단되지 않는다`() {
        val ctx = anyframeFixtureCtx()
        val DAO_IF = "src/main/java/OrderDao.java"
        val DAO_IMPL = "src/main/java/OrderDaoImpl.java"
        val XML = "src/main/resources/mapper/OrderDao.xml"
        val SVC_IF = "src/main/java/OrderService.java"
        val SVC_IMPL = "src/main/java/OrderServiceImpl.java"
        val CTRL = "src/main/java/OrderController.java"
        val DVO = "src/main/java/OrderDvo.java"

        val report = CompletenessEngine(ctx).evaluate(
            FrameworkType.ANYFRAME_AP, setOf(DAO_IF, DAO_IMPL, XML, SVC_IF, SVC_IMPL, CTRL, DVO), csvSr)
        assertEquals(GuardOutcome.Pass, CompletenessGuard.check(report))  // BVO/SVO 누락은 경고만
    }

    @Test fun `Custom - 컨트롤러 없어도 사용자액션 케이스만 약하게 경고`() {
        val ctx = customFixtureCtx()
        val DAO_CLASS = "src/main/java/OrderDao.java"
        val SVC_IMPL = "src/main/java/OrderServiceImpl.java"

        val report = CompletenessEngine(ctx).evaluate(
            FrameworkType.CUSTOM, setOf(DAO_CLASS, SVC_IMPL), csvSr)
        // 진입점 누락은 잡되, 동반 강제는 없음
        assertTrue(report.roleViolations.any { it.role == ArchRole.ENTRYPOINT })
    }
}
