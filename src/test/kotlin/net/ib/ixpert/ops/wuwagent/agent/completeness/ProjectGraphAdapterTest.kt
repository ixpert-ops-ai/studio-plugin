package net.ib.ixpert.ops.wuwagent.agent.completeness

import net.ib.ixpert.ops.wuwagent.agent.completeness.model.GraphCapability
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectGraphAdapterTest {
    private val ctx = ProjectGraphAdapter(SurveyGraphFixture.graph())

    @Test fun `DAO FQCN으로 연결된 XML 경로를 역조회한다`() {
        // className matching
        val xmls = ctx.linkedByNamespace("SurveyDao")
        assertTrue(xmls.any { it.endsWith("SurveyDao.xml") })
    }

    @Test fun `존재하지 않는 DAO는 빈 리스트를 반환한다`() {
        assertTrue(ctx.linkedByNamespace("net.infobank.iss.NoSuchDao").isEmpty())
    }

    @Test fun `기존 컨트롤러 인덱스에 IpsController가 포함된다`() {
        assertTrue(ctx.existingControllers().any { it.endsWith("IpsController.java") })
    }

    @Test fun `XML_NAMESPACE_INDEX 능력을 보유하고 STATIC_RESOURCE_LINK는 미보유다`() {
        assertTrue(ctx.capabilities.contains(GraphCapability.XML_NAMESPACE_INDEX))
        assertFalse(ctx.capabilities.contains(GraphCapability.STATIC_RESOURCE_LINK))
    }
}
