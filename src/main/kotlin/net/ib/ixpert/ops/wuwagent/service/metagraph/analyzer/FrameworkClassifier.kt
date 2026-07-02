package net.ib.ixpert.ops.wuwagent.service.metagraph.analyzer

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ArchitectureLayer
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FileNode
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.SpringFileType

object FrameworkClassifier {

    fun reclassify(nodes: MutableMap<String, FileNode>, framework: FrameworkType) {
        when (framework) {
            FrameworkType.ANYFRAME_AP -> reclassifyAnyframe(nodes)
            FrameworkType.SPRING_BOOT_JPA -> { /* 현재 분류 정확 - 패스 */ }
            FrameworkType.SPRING_MVC_MYBATIS -> { /* 필요시 추가 */ }
            FrameworkType.SPRING_BOOT_MYBATIS -> { /* 필요시 추가 */ }
            FrameworkType.SPRING_BOOT_JDBC -> { /* 필요시 추가 */ }
            FrameworkType.CUSTOM -> { /* 패스 */ }
            else -> { /* 추가 프레임워크 패스 */ }
        }
    }

    private fun reclassifyAnyframe(nodes: MutableMap<String, FileNode>) {
        val keys = nodes.keys.toList()
        for (key in keys) {
            val node = nodes[key] ?: continue
            val originalType = node.fileType
            val originalLayer = node.layer

            val (newType, newLayer) = classifyAnyframeNode(node)

            // fileType 또는 layer가 변경된 경우 riskScore 재계산
            if (newType != originalType || newLayer != originalLayer) {
                var updatedNode = node.copy(fileType = newType, layer = newLayer)
                updatedNode = updatedNode.copy(riskAssessment = RiskCalculator.calculate(updatedNode))
                nodes[key] = updatedNode
            }
        }
    }

    private fun classifyAnyframeNode(node: FileNode): Pair<SpringFileType, ArchitectureLayer> {
        return when {
            // 1. BIZ 계층: AbstractBusiness 상속 또는 클래스명 *BIZ (Util 제외)
            isAnyframeBiz(node) -> Pair(SpringFileType.BIZ, ArchitectureLayer.BUSINESS)
            
            // 2. SERVICE 구현: AbstractService 상속 또는 @Service + *SVCImpl
            isAnyframeSvcImpl(node) -> Pair(SpringFileType.SERVICE, ArchitectureLayer.SERVICE)
            
            // 3. SERVICE 인터페이스: *SVC (Interface)
            isAnyframeSvcInterface(node) -> Pair(SpringFileType.SERVICE_INTERFACE, ArchitectureLayer.SERVICE)
            
            // 4. 데이터 액세스 계층: *DEM 또는 *DQM
            node.className.matches(Regex(".*D[EQ]M$")) -> Pair(SpringFileType.DATA_ACCESS, ArchitectureLayer.DATA)
            
            // 5. BIZUtil: bizUtil 패키지 내 *Util 클래스
            isAnyframeBizUtil(node) -> Pair(SpringFileType.BIZ_UTIL, ArchitectureLayer.COMMON)
            
            // 6. VO 세분류
            node.className.endsWith("BVO") -> Pair(SpringFileType.VO, ArchitectureLayer.BUSINESS)
            node.className.endsWith("SVO") -> Pair(SpringFileType.VO, ArchitectureLayer.SERVICE)
            node.className.endsWith("DVO") -> Pair(SpringFileType.VO, ArchitectureLayer.DATA)
            
            else -> Pair(node.fileType, node.layer)
        }
    }

    private fun isAnyframeBiz(node: FileNode): Boolean {
        return node.implementedInterfaces.any { it.contains("AbstractBusiness") }
            || (node.className.endsWith("BIZ") && !node.className.contains("Util"))
    }

    private fun isAnyframeSvcImpl(node: FileNode): Boolean {
        return node.implementedInterfaces.any { it.contains("AbstractService") }
            || (node.className.endsWith("SVCImpl") && node.annotations.any { it.contains("Service") || it.contains("@Service") })
    }

    private fun isAnyframeSvcInterface(node: FileNode): Boolean {
        return node.isInterface
            && node.className.matches(Regex(".*SVC$"))
            && !node.className.endsWith("SVCImpl")
    }

    private fun isAnyframeBizUtil(node: FileNode): Boolean {
        return node.className.endsWith("Util")
            && !node.className.endsWith("BIZ")
            && !node.isInterface
    }
}
