package net.ib.ixpert.ops.wuwagent.agent.completeness

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*

object SurveyGraphFixture {
    val surveyDao = fileNode("src/main/java/net/infobank/iss/survey/dao/SurveyDao.java", "SurveyDao", SpringFileType.INTERFACE, isInterface = true, implementedInterfaces = emptyList())
    val surveyDaoImpl = fileNode("src/main/java/net/infobank/iss/survey/dao/SurveyDaoImpl.java", "SurveyDaoImpl", SpringFileType.REPOSITORY, isInterface = false, implementedInterfaces = listOf("SurveyDao", "SqlSessionDaoSupport"))
    val surveyService = fileNode("src/main/java/net/infobank/iss/survey/service/SurveyService.java", "SurveyService", SpringFileType.INTERFACE, isInterface = true, implementedInterfaces = emptyList())
    val surveyServiceImpl = fileNode("src/main/java/net/infobank/iss/survey/service/SurveyServiceImpl.java", "SurveyServiceImpl", SpringFileType.SERVICE, isInterface = false, implementedInterfaces = listOf("SurveyService"))
    val ipsController = fileNode("src/main/java/net/infobank/iss/controller/IpsController.java", "IpsController", SpringFileType.CONTROLLER, isInterface = false, implementedInterfaces = emptyList())

    val surveyXml = ResourceNode(
        path = "src/main/resources/mapper/SurveyDao.xml", type = ResourceType.MYBATIS_MAPPER, layer = "DATA_ACCESS",
        linkType = "namespace_binding", linkedTo = listOf(surveyDao.path), metadata = emptyMap()
    )
    val surveyListJsp = ResourceNode(path = "src/main/webapp/WEB-INF/views/survey/survey_list.jsp", type = ResourceType.VIEW, layer = "PRESENTATION", linkType = "", linkedTo = emptyList(), metadata = emptyMap())
    val surveyListJs = ResourceNode(path = "src/main/webapp/resources/js/survey/survey.list.js", type = ResourceType.SCRIPT, layer = "PRESENTATION", linkType = "", linkedTo = emptyList(), metadata = emptyMap())

    val ismMapper = fileNode("src/main/java/net/infobank/ism/api/bo/mapper/UserMapper.java", "UserMapper", SpringFileType.MAPPER, isInterface = true, implementedInterfaces = emptyList())
    val fakeDao = fileNode("src/main/java/net/infobank/iss/util/dao/FakeDao.java", "FakeDao", SpringFileType.INTERFACE, isInterface = true, implementedInterfaces = emptyList())
    val fakeDaoImpl = fileNode("src/main/java/net/infobank/iss/util/dao/FakeDaoImpl.java", "FakeDaoImpl", SpringFileType.INTERFACE, isInterface = false, implementedInterfaces = listOf("FakeDao")) // no SqlSessionDaoSupport

    val fakeService = fileNode("src/main/java/net/infobank/iss/util/service/FakeService.java", "FakeService", SpringFileType.INTERFACE, isInterface = true, implementedInterfaces = emptyList())
    val fakeServiceImpl = fileNode("src/main/java/net/infobank/iss/util/service/FakeServiceImpl.java", "FakeServiceImpl", SpringFileType.INTERFACE, isInterface = false, implementedInterfaces = listOf("FakeService")) // Not a SpringFileType.SERVICE

    fun graph() = ProjectGraph(
        frameworkType = FrameworkType.SPRING_MVC_MYBATIS,
        generatedAt = "2026-06-29T00:00:00Z",
        projectRoot = "C:/fake/path",
        files = listOf(surveyDao, surveyDaoImpl, surveyService, surveyServiceImpl, ipsController, ismMapper, fakeDao, fakeDaoImpl, fakeService, fakeServiceImpl)
                  .associateBy { it.path },
        resourceNodes = listOf(surveyXml, surveyListJsp, surveyListJs),
        relationships = emptyList(),
        statistics = GraphStatistics()
    )

    private fun fileNode(path: String, name: String, type: SpringFileType, isInterface: Boolean, implementedInterfaces: List<String>) =
        FileNode(
            path = path, packageName = "net.infobank.iss.survey",
            className = name, fileType = type,
            layer = ArchitectureLayer.UNKNOWN, isInterface = isInterface,
            implementedInterfaces = implementedInterfaces
        )
}
