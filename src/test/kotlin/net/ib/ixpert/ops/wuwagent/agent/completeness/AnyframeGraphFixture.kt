package net.ib.ixpert.ops.wuwagent.agent.completeness

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*

object AnyframeGraphFixture {
    val svc = fileNode("src/main/java/sc/chn/aps/apc/ad/ad03/svc/APCADSbodrMngtSVC.java", "APCADSbodrMngtSVC", SpringFileType.UNKNOWN, isInterface = true)
    val svcImpl = fileNode(
        "src/main/java/sc/chn/aps/apc/ad/ad03/svc/impl/APCADSbodrMngtSVCImpl.java", "APCADSbodrMngtSVCImpl", SpringFileType.UNKNOWN, isInterface = false,
        implementedInterfaces = listOf("APCADSbodrMngtSVC")
    )
    val biz = fileNode("src/main/java/sc/chn/aps/apc/ad/ad03/biz/APCADSbodrMngtBIZ.java", "APCADSbodrMngtBIZ", SpringFileType.UNKNOWN, isInterface = false, dependsOn = mutableListOf("src/main/java/sc/chn/aps/apc/zz/dem/ACMBTBAPC026DEM.java"))
    val svo = fileNode("src/main/java/sc/chn/aps/apc/ad/ad03/svc/svo/APCADSbodrMngtSVO.java", "APCADSbodrMngtSVO", SpringFileType.UNKNOWN, isInterface = false)
    val bvo = fileNode("src/main/java/sc/chn/aps/apc/ad/ad03/biz/bvo/APCADSbodrMngtBVO.java", "APCADSbodrMngtBVO", SpringFileType.UNKNOWN, isInterface = false)
    val dem = fileNode(
        "src/main/java/sc/chn/aps/apc/zz/dem/ACMBTBAPC026DEM.java", "ACMBTBAPC026DEM", SpringFileType.UNKNOWN, isInterface = false,
        dependsOn = mutableListOf("src/main/java/sc/chn/aps/apc/zz/dem/dvo/ACMBTBAPC026DVO.java")
    )
    val dvo = fileNode("src/main/java/sc/chn/aps/apc/zz/dem/dvo/ACMBTBAPC026DVO.java", "ACMBTBAPC026DVO", SpringFileType.UNKNOWN, isInterface = false)

    // BIZ-only chain
    val bizOnlySvc = fileNode("src/main/java/sc/chn/aps/apc/dv/dv01/svc/APCDv01SVC.java", "APCDv01SVC", SpringFileType.UNKNOWN, isInterface = true)
    val bizOnlySvcImpl = fileNode(
        "src/main/java/sc/chn/aps/apc/dv/dv01/svc/impl/APCDv01SVCImpl.java", "APCDv01SVCImpl", SpringFileType.UNKNOWN, isInterface = false,
        implementedInterfaces = listOf("APCDv01SVC")
    )
    val bizOnlyBiz = fileNode("src/main/java/sc/chn/aps/apc/dv/dv01/biz/APCDv01BIZ.java", "APCDv01BIZ", SpringFileType.UNKNOWN, isInterface = false, dependsOn = mutableListOf())
    val bizOnlySvo = fileNode("src/main/java/sc/chn/aps/apc/dv/dv01/svc/svo/APCDv01SVO.java", "APCDv01SVO", SpringFileType.UNKNOWN, isInterface = false)
    val bizOnlyBvo = fileNode("src/main/java/sc/chn/aps/apc/dv/dv01/biz/bvo/APCDv01BVO.java", "APCDv01BVO", SpringFileType.UNKNOWN, isInterface = false)

    fun graph() = ProjectGraph(
        frameworkType = FrameworkType.ANYFRAME_AP,
        generatedAt = "2026-06-30T00:00:00Z",
        projectRoot = "C:/fake/path",
        files = listOf(svc, svcImpl, biz, svo, bvo, dem, dvo, bizOnlySvc, bizOnlySvcImpl, bizOnlyBiz, bizOnlySvo, bizOnlyBvo).associateBy { it.path },
        resourceNodes = emptyList(),
        relationships = emptyList(),
        statistics = GraphStatistics()
    )

    private fun fileNode(
        path: String, name: String, type: SpringFileType, isInterface: Boolean,
        dependsOn: MutableList<String> = mutableListOf(),
        implementedInterfaces: List<String> = emptyList()
    ) = FileNode(
            path = path, packageName = "sc.chn.aps.apc.dummy",
            className = name, fileType = type,
            layer = ArchitectureLayer.UNKNOWN, isInterface = isInterface,
            dependsOn = dependsOn,
            implementedInterfaces = implementedInterfaces
        )
}
