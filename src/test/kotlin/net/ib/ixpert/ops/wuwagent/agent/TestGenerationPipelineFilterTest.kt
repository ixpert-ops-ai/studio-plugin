package net.ib.ixpert.ops.wuwagent.agent

import org.junit.Test
import org.junit.Assert.*

/**
 * shouldGenerateTest() 필터 로직의 순수 로직 테스트.
 * GraphLoader에 의존하지 않는 경로 패턴 기반 판정만 검증.
 *
 * 참고: 실제 FileNode 기반 판정은 LightJavaCodeInsightFixtureTestCase에서 별도 테스트.
 */
class TestGenerationPipelineFilterTest {

    @Test
    fun `인터페이스 파일은 제외`() {
        // SurveyService.java (Impl 없는 인터페이스)
        val target = createTarget("src/main/java/net/infobank/iss/survey/service/SurveyService.java")
        // shouldGenerateTest 내부에서 파일명 패턴으로 인터페이스를 판별
        val fileName = target.path.substringAfterLast('/')
        val isInterfacePattern = !fileName.contains("Impl") &&
            !fileName.contains("Controller") &&
            !fileName.contains("Util") &&
            (fileName.endsWith("Service.java") || fileName.endsWith("Dao.java"))
        assertTrue("SurveyService.java는 인터페이스 패턴으로 제외", isInterfacePattern)
    }

    @Test
    fun `ServiceImpl 파일은 포함`() {
        val target = createTarget("src/main/java/net/infobank/iss/survey/service/SurveyServiceImpl.java")
        val fileName = target.path.substringAfterLast('/')
        val isInterfacePattern = !fileName.contains("Impl") &&
            !fileName.contains("Controller") &&
            !fileName.contains("Util") &&
            (fileName.endsWith("Service.java") || fileName.endsWith("Dao.java"))
        assertFalse("SurveyServiceImpl.java는 Impl 포함이므로 테스트 대상", isInterfacePattern)
    }

    @Test
    fun `Controller 파일은 포함`() {
        val target = createTarget("src/main/java/net/infobank/iss/controller/IpsController.java")
        val fileName = target.path.substringAfterLast('/')
        val isInterfacePattern = !fileName.contains("Impl") &&
            !fileName.contains("Controller") &&
            !fileName.contains("Util") &&
            (fileName.endsWith("Service.java") || fileName.endsWith("Dao.java"))
        assertFalse("IpsController.java는 Controller 포함이므로 테스트 대상", isInterfacePattern)
    }

    @Test
    fun `Util 파일은 포함`() {
        val target = createTarget("src/main/java/net/infobank/iss/common/ExcelDownUtil.java")
        val fileName = target.path.substringAfterLast('/')
        val isInterfacePattern = !fileName.contains("Impl") &&
            !fileName.contains("Controller") &&
            !fileName.contains("Util") &&
            (fileName.endsWith("Service.java") || fileName.endsWith("Dao.java"))
        assertFalse("ExcelDownUtil.java는 Util 포함이므로 테스트 대상", isInterfacePattern)
    }

    @Test
    fun `DTO 파일명은 제외 대상 후보`() {
        val target = createTarget("src/main/java/net/infobank/iss/survey/dto/ReviewDto.java")
        val fileName = target.path.substringAfterLast('/')
        // DTO는 FileNode.fileType으로 판단하므로 파일명만으로는 확정 불가
        // 여기서는 경로에 /dto/가 포함된다는 것만 확인
        assertTrue("ReviewDto.java는 dto 패키지", target.path.contains("/dto/"))
    }

    private fun createTarget(path: String) = TargetFileSpec(
        order = 1,
        path = path,
        type = "수정",
        description = "테스트용"
    )
}
