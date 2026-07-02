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

    @Test fun `@Service 구현체가 없는 가짜 Service 인터페이스는 SERVICE_INTERFACE로 분류되지 않는다`() {
        assertNull(FileKindClassifier.classify(SurveyGraphFixture.fakeService.path, ctx))
        assertNull(FileKindClassifier.classify(SurveyGraphFixture.fakeServiceImpl.path, ctx))
    }

    @Test fun `그래프에 없는 신규 파일은 이름 기반 폴백으로 올바르게 분류된다`() {
        // These paths are NOT in the GraphFixture, so ctx.getFileNode(path) == null
        assertEquals(FileKind.CONTROLLER, FileKindClassifier.classify("src/main/java/new/MyController.java", ctx))
        assertEquals(FileKind.SERVICE_INTERFACE, FileKindClassifier.classify("src/main/java/new/MyService.java", ctx))
        assertEquals(FileKind.SERVICE_IMPL, FileKindClassifier.classify("src/main/java/new/MyServiceImpl.java", ctx))
        assertEquals(FileKind.DAO_INTERFACE, FileKindClassifier.classify("src/main/java/new/MyDao.java", ctx))
        assertEquals(FileKind.DAO_IMPL, FileKindClassifier.classify("src/main/java/new/MyDaoImpl.java", ctx))
    }

    @Test fun `MyBatis XML 폴백은 sql이나 mapper 경로 하위에 있을 때만 작동한다`() {
        // Non-MyBatis XML (e.g. spring context, web.xml) -> null
        assertNull(FileKindClassifier.classify("src/main/resources/spring/context-common.xml", ctx))
        
        // MyBatis XML -> MYBATIS_XML
        assertEquals(FileKind.MYBATIS_XML, FileKindClassifier.classify("src/main/java/sql/mysql/new_query.xml", ctx))
        assertEquals(FileKind.MYBATIS_XML, FileKindClassifier.classify("src/main/resources/mapper/new_query.xml", ctx))
    }

    @Test fun `폴백 로직은 기존 그래프 노드의 분류 결과를 덮어쓰지 않는다`() {
        // fakeService is named "FakeService.java" but it lacks @Service impl in the graph, so the graph logic returns null.
        // Even though its name ends with "Service.java", the graph lookup happens FIRST. 
        // Wait, if graph lookup returns null, does it fall back to guessFromPath?
        // YES! If `classifyJava` returns null, `let` returns null, and it continues to `guessFromPath`.
        // Let's check `FileKindClassifier.kt`:
        // ctx.getFileNode(path)?.let { return classifyJava(it, ctx) }
        // Wait! If `classifyJava` returns `null`, the `return classifyJava` returns `null` from the `classify` method itself!
        // Because it's `return classifyJava(...)`. `let` scope returns, meaning the whole function returns!
        // So `guessFromPath` is ONLY executed if `ctx.getFileNode(path)` is NULL.
        // Therefore, existing nodes perfectly bypass `guessFromPath`!
        assertNull(FileKindClassifier.classify(SurveyGraphFixture.fakeService.path, ctx))
    }

    @Test fun `그래프에도 없고 폴백 규칙에도 안 맞는 경로는 null을 반환한다`() {
        assertNull(FileKindClassifier.classify("unknown/Foo.java", ctx))
    }
}
