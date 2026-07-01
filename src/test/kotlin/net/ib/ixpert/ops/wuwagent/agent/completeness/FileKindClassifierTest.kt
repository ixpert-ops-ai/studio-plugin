package net.ib.ixpert.ops.wuwagent.agent.completeness

import net.ib.ixpert.ops.wuwagent.agent.completeness.model.FileKind
import org.junit.Assert.*
import org.junit.Test

class FileKindClassifierTest {
    private val ctx = ProjectGraphAdapter(SurveyGraphFixture.graph())

    @Test fun `DAO 인터페이스와 Impl을 isInterface로 구분한다`() {
        assertEquals(FileKind.DAO_INTERFACE,
            FileKindClassifier.classify(SurveyGraphFixture.surveyDao.path, ctx))
        assertEquals(FileKind.DAO_IMPL,
            FileKindClassifier.classify(SurveyGraphFixture.surveyDaoImpl.path, ctx))
    }

    @Test fun `Service 인터페이스와 Impl을 구분한다`() {
        assertEquals(FileKind.SERVICE_INTERFACE,
            FileKindClassifier.classify(SurveyGraphFixture.surveyService.path, ctx))
        assertEquals(FileKind.SERVICE_IMPL,
            FileKindClassifier.classify(SurveyGraphFixture.surveyServiceImpl.path, ctx))
    }

    @Test fun `컨트롤러는 CONTROLLER로 분류된다`() {
        assertEquals(FileKind.CONTROLLER,
            FileKindClassifier.classify(SurveyGraphFixture.ipsController.path, ctx))
    }

    @Test fun `namespace_binding 있는 xml만 MYBATIS_XML로 분류된다`() {
        assertEquals(FileKind.MYBATIS_XML,
            FileKindClassifier.classify(SurveyGraphFixture.surveyXml.path, ctx))
    }

    @Test fun `jsp와 js는 각각 VIEW SCRIPT로 분류된다`() {
        assertEquals(FileKind.JSP_VIEW,  FileKindClassifier.classify(SurveyGraphFixture.surveyListJsp.path, ctx))
        assertEquals(FileKind.JS_SCRIPT, FileKindClassifier.classify(SurveyGraphFixture.surveyListJs.path, ctx))
    }

    @Test fun `ISM의 Mapper는 DAO_INTERFACE로 분류된다`() {
        assertEquals(FileKind.DAO_INTERFACE, FileKindClassifier.classify(SurveyGraphFixture.ismMapper.path, ctx))
    }

    @Test fun `SqlSessionDaoSupport 상속이 없는 가짜 Dao는 DAO_INTERFACE로 분류되지 않는다`() {
        assertNull(FileKindClassifier.classify(SurveyGraphFixture.fakeDao.path, ctx))
        assertNull(FileKindClassifier.classify(SurveyGraphFixture.fakeDaoImpl.path, ctx))
    }

    @Test fun `그래프에 없는 경로는 null을 반환한다`() {
        assertNull(FileKindClassifier.classify("unknown/Foo.java", ctx))
    }
}
