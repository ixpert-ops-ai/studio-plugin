package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*

/**
 * 프로젝트 전체 파일 구조를 고밀도 트리(Repo Map) 형태로 포맷팅합니다.
 * Aider의 Repo Map 방식을 차용하여 컨텍스트 토큰을 최소화하고, LLM의 구조 파악 능력을 높입니다.
 */
object RepoMapFormatter {

    fun format(graph: ProjectGraph): String {
        if (graph.files.isEmpty()) return "분석 대상 파일이 없습니다."

        val isAnyframe = graph.frameworkType == FrameworkType.ANYFRAME || graph.framework == "anyframe"

        val sb = StringBuilder()
        
        sb.append("## Contextual Analysis\n")
        sb.append("### 🕸️ Key Architecture Flow\n")
        sb.append(buildRelationshipSummary(graph))
        sb.append("\n")

        sb.append("## Project Structure (${graph.files.size} files)\n")
        
        // 1. Layer 기준으로 그룹화
        val layeredFiles = graph.files.values.groupBy { it.layer }

        val layerOrder = listOf(
            ArchitectureLayer.PERSISTENCE,
            ArchitectureLayer.BUSINESS,
            ArchitectureLayer.PRESENTATION,
            ArchitectureLayer.COMMON,
            ArchitectureLayer.TEST
        )

        for (layer in layerOrder) {
            val filesInLayer = layeredFiles[layer] ?: continue
            if (filesInLayer.isEmpty()) continue

            sb.append("\n[${layer.name}]\n")

            // 2. Package 기준으로 그룹화
            val packageGroups = filesInLayer.groupBy { it.packageName ?: "default.package" }
                .toSortedMap()

            for ((pkg, files) in packageGroups) {
                sb.append("$pkg/\n")
                
                // Utils, DTOs 등을 한 줄로 모으기 위한 리스트
                val utilClasses = mutableListOf<String>()
                val dtoClasses = mutableListOf<String>()
                val otherCommonClasses = mutableListOf<String>()

                for (file in files.sortedBy { it.className }) {
                    // 위험도 아이콘 추가
                    val riskIcon = if (file.riskAssessment.changeRisk == ChangeRisk.HIGH || file.riskAssessment.changeRisk == ChangeRisk.CRITICAL) " ⚠️" else ""

                    if (!isAnyframe) {
                        if (file.fileType == SpringFileType.UTIL || file.fileType == SpringFileType.CONFIG) {
                            utilClasses.add(file.className + riskIcon)
                            continue
                        }
                        if (file.fileType == SpringFileType.DTO || file.fileType == SpringFileType.VO) {
                            dtoClasses.add(file.className + riskIcon)
                            continue
                        }
                        if (layer == ArchitectureLayer.COMMON && file.fileType != SpringFileType.SERVICE && file.fileType != SpringFileType.REPOSITORY && file.fileType != SpringFileType.REST_CONTROLLER && file.fileType != SpringFileType.CONTROLLER) {
                            otherCommonClasses.add(file.className + riskIcon)
                            continue
                        }
                    } else {
                        if (file.anyframeRole == AnyframeRole.BIZ_UTIL || file.fileType == SpringFileType.UTIL || file.fileType == SpringFileType.CONFIG) {
                            utilClasses.add(file.className + riskIcon)
                            continue
                        }
                    }

                    // 일반 클래스 라인
                    val classLine = buildString {
                        append("  ${file.className}$riskIcon")
                        
                        val tags = mutableListOf<String>()
                        if (isAnyframe) {
                            if (file.anyframeRole != null) {
                                tags.add("role: ${file.anyframeRole}")
                            }
                            if (file.localName != null) {
                                tags.add("Local: ${file.localName}")
                            }
                            if (file.datasource != null) {
                                tags.add("datasource: ${file.datasource}")
                            }
                        } else {
                            if (file.fileType == SpringFileType.REST_CONTROLLER) tags.add("@RestController")
                            if (file.fileType == SpringFileType.CONTROLLER) tags.add("@Controller")
                        }
                        
                        if (file.implementedInterfaces.isNotEmpty()) {
                            tags.add("implements ${file.implementedInterfaces.joinToString(", ")}")
                        }
                        
                        if (tags.isNotEmpty()) {
                            append(" (${tags.joinToString(", ")})")
                        }
                    }
                    sb.append(classLine).append("\n")

                    if (isAnyframe) {
                        // Anyframe: Service Endpoints
                        if (file.anyframeRole == AnyframeRole.SVC && !file.serviceEndpoints.isNullOrEmpty()) {
                            for (endpoint in file.serviceEndpoints) {
                                val localStr = if (endpoint.localName != null) " [Local: ${endpoint.localName}]" else ""
                                sb.append("    ServiceId: ${endpoint.serviceId}$localStr → ${endpoint.methodName}(Input: ${endpoint.inputSvo ?: "void"}, Output: ${endpoint.outputSvo ?: "void"})\n")
                            }
                        }

                        // Anyframe: DEM/DQM Methods
                        if ((file.anyframeRole == AnyframeRole.DEM || file.anyframeRole == AnyframeRole.DQM) && !file.demMethods.isNullOrEmpty()) {
                            for (method in file.demMethods) {
                                val tablesStr = if (method.tables.isNotEmpty()) " (tables: ${method.tables.joinToString(", ")})" else ""
                                val localStr = if (method.localName != null) " [Local: ${method.localName}]" else ""
                                sb.append("    - ${method.methodName} [${method.operationType}]$tablesStr → Input: ${method.inputDvoClass ?: "void"}, Return: ${method.returnDvoClass ?: "void"}$localStr\n")
                            }
                        }

                        // Anyframe: Relationships
                        val fileRels = graph.relationships.filter { it.source == file.path }
                        for (rel in fileRels) {
                            val targetName = graph.files[rel.target]?.className ?: rel.target.substringAfterLast("/").substringBefore(".")
                            when (rel.type) {
                                RelationshipType.CALLS_BIZ -> {
                                    sb.append("    → calls BIZ: $targetName\n")
                                }
                                RelationshipType.CALLS_DEM_METHOD -> {
                                    val detailStr = if (rel.detail != null) ".${rel.detail}" else ""
                                    val fdCallStr = if (rel.metadata?.containsKey("fdCallId") == true) " (FD-CALL: ${rel.metadata["fdCallId"]})" else ""
                                    sb.append("    → calls DEM: $targetName$detailStr$fdCallStr\n")
                                }
                                RelationshipType.TRANSFORMS_VO -> {
                                    val direction = rel.metadata?.get("direction") ?: "INBOUND"
                                    val arrow = if (direction == "INBOUND") "──►" else "◄──"
                                    sb.append("    → transforms: ${file.className} $arrow $targetName\n")
                                }
                                else -> {}
                            }
                        }
                    } else {
                        // Controller: API Endpoints
                        if (file.apiEndpoints.isNotEmpty()) {
                            for (api in file.apiEndpoints) {
                                val method = api.httpMethod.padEnd(5)
                                sb.append("    $method ${api.path} → ${api.handlerMethod}\n")
                            }
                        }

                        // Service / Repository: Injects
                        if (file.layer == ArchitectureLayer.BUSINESS || file.layer == ArchitectureLayer.PERSISTENCE || file.fileType == SpringFileType.SERVICE) {
                            if (file.injections.isNotEmpty()) {
                                val injectedNames = file.injections.map { TypeResolver.toSimpleName(TypeResolver.unwrapGenericType(it.targetType)) }.distinct()
                                sb.append("    injects: ${injectedNames.joinToString(", ")}\n")
                            }
                        }
                    }
                }

                // 모아둔 DTO/Util 한 줄로 추가
                if (utilClasses.isNotEmpty()) {
                    sb.append("  Utils: ${utilClasses.joinToString(", ")}\n")
                }
                if (dtoClasses.isNotEmpty()) {
                    sb.append("  DTOs: ${dtoClasses.joinToString(", ")}\n")
                }
                if (otherCommonClasses.isNotEmpty()) {
                    sb.append("  Classes: ${otherCommonClasses.joinToString(", ")}\n")
                }
                sb.append("\n") // 패키지 간격
            }
        }

        return sb.toString().trim()
    }

    private fun buildRelationshipSummary(graph: ProjectGraph): String {
        val summary = StringBuilder()
        val isAnyframe = graph.frameworkType == FrameworkType.ANYFRAME || graph.framework == "anyframe"

        if (isAnyframe) {
            val coreRels = graph.relationships.filter { rel ->
                rel.type in listOf(RelationshipType.IMPLEMENTS, RelationshipType.CALLS_BIZ, RelationshipType.CALLS_DEM_METHOD, RelationshipType.TRANSFORMS_VO)
            }.distinctBy { "${it.source}-${it.type}-${it.target}-${it.detail}" }

            if (coreRels.isEmpty()) return "* (No Anyframe business flows identified in this subset)\n"

            val sortedRels = coreRels.sortedBy {
                when (it.type) {
                    RelationshipType.IMPLEMENTS -> 1
                    RelationshipType.CALLS_BIZ -> 2
                    RelationshipType.CALLS_DEM_METHOD -> 3
                    RelationshipType.TRANSFORMS_VO -> 4
                    else -> 5
                }
            }

            sortedRels.forEach { rel ->
                val sourceName = rel.source.substringAfterLast("/").substringBefore(".")
                val targetName = rel.target.substringAfterLast("/").substringBefore(".")
                val detailStr = if (rel.detail != null) " (${rel.detail})" else ""
                val fdCallStr = if (rel.metadata?.containsKey("fdCallId") == true) " [FD-CALL: ${rel.metadata["fdCallId"]}]" else ""
                summary.appendLine("* $sourceName -> ${rel.type.name}$detailStr$fdCallStr -> $targetName")
            }
        } else {
            val excludedTypes = setOf(SpringFileType.UTIL, SpringFileType.DTO, SpringFileType.ENTITY, SpringFileType.VO, SpringFileType.CONFIG)
            
            // 핵심 관계(IMPLEMENTS, INJECTS)만 추출하여 요약
            val coreRels = graph.relationships.filter { rel ->
                val sourceNode = graph.files[rel.source]
                val targetNode = graph.files[rel.target]
                
                // 유틸, DTO 등이 포함된 관계는 노이즈로 간주하여 제외
                sourceNode?.fileType !in excludedTypes && targetNode?.fileType !in excludedTypes &&
                rel.type in listOf(RelationshipType.INJECTS, RelationshipType.IMPLEMENTS)
            }.distinctBy { "${it.source}-${it.type}-${it.target}" } // 중복 제거

            // IMPLEMENTS 우선 출력 후 INJECTS 출력
            val sortedRels = coreRels.sortedBy { if (it.type == RelationshipType.IMPLEMENTS) 0 else 1 }

            if (sortedRels.isEmpty()) return "* (No core business flows identified in this subset)\n"

            sortedRels.forEach { rel ->
                val sourceName = rel.source.substringAfterLast("/").removeSuffix(".java").removeSuffix(".kt")
                val targetName = rel.target.substringAfterLast("/").removeSuffix(".java").removeSuffix(".kt")
                summary.appendLine("* $sourceName -> ${rel.type.name} -> $targetName")
            }
        }
        
        return summary.toString()
    }
}
