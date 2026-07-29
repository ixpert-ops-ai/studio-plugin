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
}
