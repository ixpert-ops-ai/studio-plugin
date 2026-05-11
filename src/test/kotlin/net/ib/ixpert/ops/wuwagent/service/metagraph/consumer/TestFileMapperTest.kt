package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import kotlin.test.*


class TestFileMapperTest : LightJavaCodeInsightFixtureTestCase() {

    private lateinit var mapper: TestFileMapper

    override fun setUp() {
        super.setUp()
        mapper = TestFileMapper(getProject())
    }

    // ──────────────────────────────────────────────
    // 경로 매핑 테스트
    // ──────────────────────────────────────────────

    fun testResolve_JavaServiceImpl() {
        val result = mapper.resolve(
            "src/main/java/net/infobank/iss/survey/service/SurveyServiceImpl.java"
        )
        assertNotNull(result)
        assertEquals(
            "src/test/java/net/infobank/iss/survey/service/SurveyServiceImplTest.java",
            result!!.testFilePath
        )
        assertEquals("SurveyServiceImplTest", result.testClassName)
        assertFalse(result.exists)  // testData에 실제 테스트 파일이 없으므로
    }

    fun testResolve_JavaController() {
        val result = mapper.resolve(
            "src/main/java/net/infobank/iss/controller/IpsController.java"
        )
        assertNotNull(result)
        assertEquals(
            "src/test/java/net/infobank/iss/controller/IpsControllerTest.java",
            result!!.testFilePath
        )
        assertEquals("IpsControllerTest", result.testClassName)
    }

    fun testResolve_KotlinFile() {
        val result = mapper.resolve(
            "src/main/kotlin/net/infobank/service/UserService.kt"
        )
        assertNotNull(result)
        assertEquals(
            "src/test/kotlin/net/infobank/service/UserServiceTest.kt",
            result!!.testFilePath
        )
    }

    fun testResolve_UnmappablePath() {
        val result = mapper.resolve("resources/application.yml")
        assertNull(result, "매핑 불가 경로는 null 반환")
    }

    fun testResolve_NestedPackage() {
        val result = mapper.resolve(
            "src/main/java/com/example/deep/nested/pkg/MyUtil.java"
        )
        assertNotNull(result)
        assertEquals(
            "src/test/java/com/example/deep/nested/pkg/MyUtilTest.java",
            result!!.testFilePath
        )
    }

    // ──────────────────────────────────────────────
    // 클래스명 추출 테스트
    // ──────────────────────────────────────────────

    fun testResolve_ClassNameExtraction() {
        val result = mapper.resolve(
            "src/main/java/net/infobank/iss/common/ExcelDownUtil.java"
        )
        assertNotNull(result)
        assertEquals("ExcelDownUtilTest", result!!.testClassName)
    }
}
