package net.ib.ixpert.ops.wuwagent.agent.completeness

import net.ib.ixpert.ops.wuwagent.agent.completeness.model.*
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType
import org.junit.Assert.*
import org.junit.Test

class CompletenessGuardRegressionTest {
    private val ctx = ProjectGraphAdapter(SurveyGraphFixture.graph())
    private val engine = CompletenessEngine(ctx)
    private val csvSr = SrFacts(hasUserAction = true, readsOrWritesData = true,
                               hasBusinessLogic = true, touchesUi = true, addsNewMethod = true)
    
    private fun p(name: String) = SurveyGraphFixture.run {
        mapOf("dao" to surveyDao.path, "daoImpl" to surveyDaoImpl.path,
              "svc" to surveyService.path, "svcImpl" to surveyServiceImpl.path,
              "ctrl" to ipsController.path, "xml" to surveyXml.path,
              "jsp" to surveyListJsp.path, "js" to surveyListJs.path)[name]!!
    }

    @Test fun `버그재현 - 컨트롤러 누락 시 차단된다`() {
        val required = setOf(p("dao"), p("daoImpl"), p("xml"), p("svc"), p("svcImpl"))
        val report = engine.evaluate(FrameworkType.SPRING_MVC_MYBATIS, required, csvSr)
        val outcome = CompletenessGuard.check(report)
        assertTrue(outcome is GuardOutcome.Block)
        assertTrue((outcome as GuardOutcome.Block).messages.any { it.contains("ENTRYPOINT") || it.contains("컨트롤러") })
    }

    @Test fun `버그재현 - DaoImpl과 XML 누락 시 차단된다`() {
        val required = setOf(p("dao"), p("svc"), p("svcImpl"), p("ctrl"))
        val outcome = CompletenessGuard.check(engine.evaluate(FrameworkType.SPRING_MVC_MYBATIS, required, csvSr))
        assertTrue(outcome is GuardOutcome.Block)
        val msgs = (outcome as GuardOutcome.Block).messages
        assertTrue(msgs.any { it.contains("DAO_IMPL") })
        assertTrue(msgs.any { it.contains("MYBATIS_XML") })
    }

    @Test fun `엣지1 - DAO 계층 통째 누락도 RoleNecessity로 차단된다`() {
        val required = setOf(p("svc"), p("svcImpl"), p("ctrl"))   // PERSISTENCE 전체 없음
        val outcome = CompletenessGuard.check(engine.evaluate(FrameworkType.SPRING_MVC_MYBATIS, required, csvSr))
        assertTrue(outcome is GuardOutcome.Block)
        assertTrue((outcome as GuardOutcome.Block).messages.any { it.contains("PERSISTENCE") })
    }

    @Test fun `엣지3 - 새 메서드 추가 아닐 때 DaoImpl 누락은 차단하지 않는다`() {
        val sortSr = csvSr.copy(addsNewMethod = false, hasUserAction = false, touchesUi = false)
        val required = setOf(p("dao"), p("xml"))   // DaoImpl 없음, 하지만 ON_NEW_METHOD라 스킵
        val outcome = CompletenessGuard.check(engine.evaluate(FrameworkType.SPRING_MVC_MYBATIS, required, sortSr))
        
        if (outcome is GuardOutcome.Block) {
            assertFalse(outcome.messages.any { it.contains("DAO_IMPL") })
        }
    }

    @Test fun `사각지대 해결 - DaoImpl만 수정되어도 XML 누락을 차단한다`() {
        val required = setOf(p("daoImpl"), p("svc"), p("svcImpl"), p("ctrl")) // dao, xml 누락
        val outcome = CompletenessGuard.check(engine.evaluate(FrameworkType.SPRING_MVC_MYBATIS, required, csvSr))
        assertTrue(outcome is GuardOutcome.Block)
        val msgs = (outcome as GuardOutcome.Block).messages
        assertTrue(msgs.any { it.contains("MYBATIS_XML") })
    }

    @Test fun `정상 - 모든 필수 파일 포함 시 통과한다`() {
        val required = setOf(p("dao"), p("daoImpl"), p("xml"),
                             p("svc"), p("svcImpl"), p("ctrl"), p("jsp"), p("js"))
        val outcome = CompletenessGuard.check(engine.evaluate(FrameworkType.SPRING_MVC_MYBATIS, required, csvSr))
        assertTrue(outcome is GuardOutcome.Pass || outcome is GuardOutcome.Warn)
    }
}
