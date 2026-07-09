package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*
import org.junit.Test
import org.junit.Assert.assertTrue

class ToolServiceTest {

    @Test
    fun testSearchFilesIncludesResourceNodes() {
        val graph = ProjectGraph(
            generatedAt = "now",
            projectRoot = "root",
            files = mapOf(
                "src/main/java/net/infobank/iss/survey/dto/SurveyDto.java" to FileNode(
                    path = "src/main/java/net/infobank/iss/survey/dto/SurveyDto.java",
                    className = "SurveyDto",
                    packageName = "net.infobank.iss.survey.dto",
                    fileType = SpringFileType.DTO,
                    layer = ArchitectureLayer.PRESENTATION,
                    methods = listOf(net.ib.ixpert.ops.wuwagent.service.metagraph.model.MethodSignature("getExpireDate", "void", emptyList())),
                    apiEndpoints = emptyList(),
                    dependsOn = mutableListOf(),
                    dependedBy = mutableListOf(),
                    injections = emptyList()
                )
            ),
            resourceNodes = listOf(
                ResourceNode(
                    path = "src/main/webapp/WEB-INF/views/survey/surveyRegist.jsp",
                    type = ResourceType.VIEW,
                    layer = "PRESENTATION",
                    metadata = mapOf("form_action" to listOf("/survey/regist")),
                    linkedTo = emptyList(),
                    linkType = "url_binding"
                ),
                ResourceNode(
                    path = "src/main/resources/mapper/sql_survey.xml",
                    type = ResourceType.MYBATIS_MAPPER,
                    layer = "DATA_ACCESS",
                    metadata = emptyMap(),
                    linkedTo = emptyList(),
                    linkType = "namespace_binding"
                )
            ),
            relationships = emptyList(),
            statistics = GraphStatistics()
        )

        // scope="all", keyword="survey"
        val resultJson = ToolService.execute("search_files", "{\"keyword\": \"survey\", \"scope\": \"all\"}", graph)

        println("Result: $resultJson")

        assertTrue("Result should contain the JSP file path", resultJson.contains("surveyRegist.jsp"))
        assertTrue("Result should have type=RESOURCE for resource nodes", resultJson.contains("\"type\":\"RESOURCE\""))
        assertTrue("Result should have type=JAVA for java files", resultJson.contains("\"type\":\"JAVA\""))
    }
}
