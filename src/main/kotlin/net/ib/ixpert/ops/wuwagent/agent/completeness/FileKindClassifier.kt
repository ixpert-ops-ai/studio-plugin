package net.ib.ixpert.ops.wuwagent.agent.completeness

import net.ib.ixpert.ops.wuwagent.agent.completeness.model.*
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.SpringFileType
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FileNode
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ResourceNode

object FileKindClassifier {

    fun classify(path: String, ctx: GraphMatchContext): FileKind? {
        ctx.getFileNode(path)?.let { return classifyJava(it, ctx) }
        ctx.getResourceNode(path)?.let { return classifyResource(it) }
        return null
    }

    private fun classifyJava(node: FileNode, ctx: GraphMatchContext): FileKind? {
        if (ctx.frameworkType == net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType.ANYFRAME_AP) {
            when {
                node.className.endsWith("SVCImpl") -> return FileKind.SERVICE_IMPL
                node.className.endsWith("SVC") -> return FileKind.SERVICE_INTERFACE
                node.className.endsWith("BIZ") -> return FileKind.BIZ
                node.className.endsWith("DEM") -> return FileKind.DEM
                node.className.endsWith("DQM") -> return FileKind.DQM
                node.className.endsWith("SVO") -> return FileKind.SVO
                node.className.endsWith("BVO") -> return FileKind.BVO
                node.className.endsWith("DVO") -> return FileKind.DVO
            }
        }

        return when (node.fileType) {
        SpringFileType.SERVICE ->
            if (node.isInterface) FileKind.SERVICE_INTERFACE else FileKind.SERVICE_IMPL

        SpringFileType.REPOSITORY ->
            if (node.isInterface) {
                if (ctx.frameworkType == net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType.SPRING_BOOT_JPA) FileKind.JPA_REPOSITORY
                else FileKind.DAO_INTERFACE
            } else FileKind.DAO_IMPL

        SpringFileType.MAPPER ->
            if (node.isInterface) FileKind.DAO_INTERFACE else FileKind.DAO_IMPL

        SpringFileType.CONTROLLER, SpringFileType.REST_CONTROLLER -> FileKind.CONTROLLER

        SpringFileType.ENTITY -> FileKind.ENTITY
        SpringFileType.DTO -> FileKind.POJO_DTO
        else -> {
            // Fallback for legacy DAOs (e.g., SqlSessionDaoSupport) missed by the graph extractor
            // [KNOWN DEBT] 추출기의 버그성 특징으로 인해, 부모 클래스인 SqlSessionDaoSupport가 
            // implementedInterfaces 배열에 평면화(merge)되어 들어가 있습니다. 
            // 만약 추후 추출기가 이를 수정해 superclass 필드로 분리한다면, 아래 로직은 false가 되어 
            // survey_admin 등의 레거시 DAO가 다시 누락될 수 있으므로 주의가 필요합니다.
            val isLegacyDaoInterface = node.isInterface && node.className.endsWith("Dao") && 
                ctx.allNodes().any { impl -> 
                    impl.implementedInterfaces.contains(node.className) && 
                    impl.implementedInterfaces.contains("SqlSessionDaoSupport") 
                }
            val isLegacyDaoImpl = !node.isInterface && node.implementedInterfaces.contains("SqlSessionDaoSupport")
            
            if (isLegacyDaoInterface) FileKind.DAO_INTERFACE
            else if (isLegacyDaoImpl) FileKind.DAO_IMPL
            else null
        }
    }
    }

    private fun classifyResource(node: ResourceNode): FileKind? {
        val p = node.path.lowercase()
        return when {
            p.endsWith(".jsp") -> FileKind.JSP_VIEW
            p.endsWith(".js")  -> FileKind.JS_SCRIPT
            p.endsWith(".xml") && isMyBatisMapper(node) -> FileKind.MYBATIS_XML
            else -> null
        }
    }

    private fun isMyBatisMapper(node: ResourceNode): Boolean =
        node.linkType == "namespace_binding" || node.linkedTo.isNotEmpty()
}
