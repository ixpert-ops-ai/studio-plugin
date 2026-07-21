package net.ib.ixpert.ops.wuwagent.agent.completeness

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*

object RulesetTestFixtures {

    fun jpaFixtureCtx(): ProjectGraphAdapter {
        val repo = fileNode("src/main/java/OrderRepository.java", "OrderRepository", SpringFileType.REPOSITORY, true)
        val svcImpl = fileNode("src/main/java/OrderServiceImpl.java", "OrderServiceImpl", SpringFileType.SERVICE, false)
        val ctrl = fileNode("src/main/java/OrderController.java", "OrderController", SpringFileType.CONTROLLER, false)
        return ctx(FrameworkType.SPRING_BOOT_JPA, listOf(repo, svcImpl, ctrl))
    }

    fun bootMybatisFixtureCtx(): ProjectGraphAdapter {
        val mapper = fileNode("src/main/java/OrderMapper.java", "OrderMapper", SpringFileType.MAPPER, true)
        val svcImpl = fileNode("src/main/java/OrderServiceImpl.java", "OrderServiceImpl", SpringFileType.SERVICE, false)
        val ctrl = fileNode("src/main/java/OrderController.java", "OrderController", SpringFileType.CONTROLLER, false)
        return ctx(FrameworkType.SPRING_BOOT_MYBATIS, listOf(mapper, svcImpl, ctrl))
    }

    fun jdbcFixtureCtx(): ProjectGraphAdapter {
        val daoClass = fileNode("src/main/java/OrderDao.java", "OrderDao", SpringFileType.REPOSITORY, false) // Not interface
        val svcImpl = fileNode("src/main/java/OrderServiceImpl.java", "OrderServiceImpl", SpringFileType.SERVICE, false)
        val ctrl = fileNode("src/main/java/OrderController.java", "OrderController", SpringFileType.CONTROLLER, false)
        return ctx(FrameworkType.SPRING_BOOT_JDBC, listOf(daoClass, svcImpl, ctrl))
    }

    fun anyframeFixtureCtx(): ProjectGraphAdapter {
        val daoIf = fileNode("src/main/java/OrderDao.java", "OrderDao", SpringFileType.REPOSITORY, true)
        val daoImpl = fileNode("src/main/java/OrderDaoImpl.java", "OrderDaoImpl", SpringFileType.REPOSITORY, false)
        val xml = ResourceNode(path = "src/main/resources/mapper/OrderDao.xml", type = ResourceType.MYBATIS_MAPPER, layer = "DATA_ACCESS", linkType = "namespace_binding", linkedTo = listOf(daoIf.path), metadata = emptyMap())
        val svcIf = fileNode("src/main/java/OrderService.java", "OrderService", SpringFileType.SERVICE, true)
        val svcImpl = fileNode("src/main/java/OrderServiceImpl.java", "OrderServiceImpl", SpringFileType.SERVICE, false)
        val ctrl = fileNode("src/main/java/OrderController.java", "OrderController", SpringFileType.CONTROLLER, false)
        val dvo = fileNode("src/main/java/OrderDvo.java", "OrderDvo", SpringFileType.DTO, false)
        return ProjectGraphAdapter(
            ProjectGraph(
                frameworkType = FrameworkType.ANYFRAME_AP,
                generatedAt = "2026-06-29T00:00:00Z",
                projectRoot = "C:/fake/path",
                files = listOf(daoIf, daoImpl, svcIf, svcImpl, ctrl, dvo).associateBy { it.path },
                resourceNodes = listOf(xml),
                relationships = emptyList(),
                statistics = GraphStatistics()
            )
        )
    }

    fun customFixtureCtx(): ProjectGraphAdapter {
        val daoClass = fileNode("src/main/java/OrderDao.java", "OrderDao", SpringFileType.REPOSITORY, false)
        val svcImpl = fileNode("src/main/java/OrderServiceImpl.java", "OrderServiceImpl", SpringFileType.SERVICE, false)
        return ctx(FrameworkType.CUSTOM, listOf(daoClass, svcImpl))
    }

    private fun ctx(type: FrameworkType, files: List<FileNode>): ProjectGraphAdapter {
        return ProjectGraphAdapter(
            ProjectGraph(
                frameworkType = type,
                generatedAt = "2026-06-29T00:00:00Z",
                projectRoot = "C:/fake/path",
                files = files.associateBy { it.path },
                resourceNodes = emptyList(),
                relationships = emptyList(),
                statistics = GraphStatistics()
            )
        )
    }

    private fun fileNode(path: String, name: String, type: SpringFileType, isInterface: Boolean) =
        FileNode(
            path = path, packageName = "net.infobank.iss",
            className = name, fileType = type,
            layer = ArchitectureLayer.UNKNOWN, isInterface = isInterface
        )
}
