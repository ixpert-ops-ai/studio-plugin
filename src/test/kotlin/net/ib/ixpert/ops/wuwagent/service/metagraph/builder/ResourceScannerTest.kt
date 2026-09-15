package net.ib.ixpert.ops.wuwagent.service.metagraph.builder

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ResourceType
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Paths

class ResourceScannerTest {

    @Test
    fun testScanner() {
        // Copy to a tmp dir to avoid ScanExclusionUtil filtering out 'test' from path
        val sourceDir = Paths.get("src/test/resources/metagraph_resource_samples").toAbsolutePath().toFile()
        val tmpDir = Paths.get(".agent_tmp_metagraph_resource_samples").toAbsolutePath().toFile()
        if (!tmpDir.exists() || tmpDir.list()?.isEmpty() == true) {
            sourceDir.copyRecursively(tmpDir, overwrite = true)
        }
        val projectRoot = tmpDir.toPath()
        val scanner = ResourceScanner(projectRoot)
        val nodes = scanner.scan()

        // 5 files are in the directory. sample_library.js should be skipped (UNKNOWN)
        // so we expect 4 nodes.
        assertEquals("Should scan 4 resource nodes (library.js is skipped)", 4, nodes.size)

        val mybatisNode = nodes.find { it.path.endsWith("sample_mybatis_mapper.xml") }
        assertNotNull(mybatisNode)
        assertEquals(ResourceType.MYBATIS_MAPPER, mybatisNode?.type)
        assertEquals("DATA_ACCESS", mybatisNode?.layer)
        // Check tables extraction (should have both TB_SURVEY and TB_USER)
        val tables = mybatisNode?.metadata?.get("table_name") as? List<*>
        assertTrue("Should contain TB_SURVEY", tables?.contains("TB_SURVEY") == true)
        assertTrue("Should contain TB_USER", tables?.contains("TB_USER") == true)

        val anyframeNode = nodes.find { it.path.endsWith("sample_anyframe_query.xml") }
        assertNotNull(anyframeNode)
        assertEquals(ResourceType.MYBATIS_MAPPER, anyframeNode?.type)
        val queryIds = anyframeNode?.metadata?.get("anyframe_query_id") as? List<*>
        assertTrue(queryIds?.contains("AnyframeSurvey.selectList") == true)

        val businessJsNode = nodes.find { it.path.endsWith("sample_business.js") }
        assertNotNull(businessJsNode)
        assertEquals(ResourceType.SCRIPT, businessJsNode?.type)
        val apiUrls = businessJsNode?.metadata?.get("api_url") as? List<*>
        // Circuit breaker limit checks (should not exceed 20 for this category)
        assertTrue("Category hints should not exceed 20", apiUrls!!.size <= 20)

        val viewNode = nodes.find { it.path.endsWith("sample_view.jsp") }
        assertNotNull(viewNode)
        assertEquals(ResourceType.VIEW, viewNode?.type)
        val scripts = viewNode?.metadata?.get("script_src") as? List<*>
        assertTrue(scripts?.contains("/resources/js/survey/surveyRegist.js") == true)
    }

    @Test
    fun testViewLocalNameExtractionRules() {
        val tmpDir = java.nio.file.Files.createTempDirectory("jsp_local_name_test").toFile()
        try {
            // 1. Regular View with <h2>
            val surveyWriteJsp = java.io.File(tmpDir, "views/survey/survey_write.jsp").apply {
                parentFile.mkdirs()
                writeText("""
                    <%@ page contentType="text/html;charset=UTF-8" %>
                    <h2 class="blind">설문 관리</h2>
                    <h3 class="title_left">고객 설문 신규등록</h3>
                    <form action="/survey/save.do" method="post">
                        <input name="surveyTitle" />
                    </form>
                    <script src="/resources/js/survey/survey.write.js"></script>
                """.trimIndent())
            }

            // 2. Common Layout View (should be guarded and return null)
            val commonHeaderJsp = java.io.File(tmpDir, "views/common/header.jsp").apply {
                parentFile.mkdirs()
                writeText("""
                    <%@ page contentType="text/html;charset=UTF-8" %>
                    <h2>설문조사 서비스</h2>
                    <script src="/resources/js/common.js"></script>
                """.trimIndent())
            }

            // 3. View with dynamic template in title and clean <h3>
            val surveyResultJsp = java.io.File(tmpDir, "views/survey/survey_result.jsp").apply {
                parentFile.mkdirs()
                writeText("""
                    <%@ page contentType="text/html;charset=UTF-8" %>
                    <title>${"$"}{HEADER_TITLE}</title>
                    <h3 class="title">설문 결과 분석</h3>
                    <script src="/resources/js/survey/survey.result.js"></script>
                """.trimIndent())
            }

            val scanner = ResourceScanner(tmpDir.toPath())
            val nodes = scanner.scan()

            val writeNode = nodes.find { it.path.replace("\\", "/").endsWith("views/survey/survey_write.jsp") }
            assertNotNull("survey_write.jsp should be scanned", writeNode)
            assertEquals("First <h2> should be extracted as localName", "설문 관리", writeNode?.localName)

            val headerNode = nodes.find { it.path.replace("\\", "/").endsWith("views/common/header.jsp") }
            assertNotNull("header.jsp should be scanned", headerNode)
            assertNull("Common layout should have null localName to prevent noise", headerNode?.localName)

            val resultNode = nodes.find { it.path.replace("\\", "/").endsWith("views/survey/survey_result.jsp") }
            assertNotNull("survey_result.jsp should be scanned", resultNode)
            assertEquals("Clean <h3> heading should be extracted when title is template expression", "설문 결과 분석", resultNode?.localName)

            // 4. Determinism Test
            val secondScanNodes = scanner.scan()
            val secondWriteNode = secondScanNodes.find { it.path.replace("\\", "/").endsWith("views/survey/survey_write.jsp") }
            assertEquals("Repeated scan must produce identical localName deterministically", writeNode?.localName, secondWriteNode?.localName)
        } finally {
            tmpDir.deleteRecursively()
        }
    }
}
