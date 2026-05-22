package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*

/**
 * 프로젝트 전체 파일 구조를 고밀도 트리(Repo Map) 형태로 포맷팅합니다.
 * Aider의 Repo Map 방식을 차용하여 컨텍스트 토큰을 최소화하고, LLM의 구조 파악 능력을 높입니다.
 */
object RepoMapFormatter {

    fun format(graph: ProjectGraph): String {
        if (graph.files.isEmpty()) return "분석 대상 파일이 없습니다."

        val sb = StringBuilder()
        
        sb.append("## Contextual Analysis\n")
        sb.append("### \uD83D\uDD78\uFE0F Key Architecture Flow\n")
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

                    // 일반 Service, Controller, Repository 등
                    val classLine = buildString {
                        append("  ${file.className}$riskIcon")
                        
                        val tags = mutableListOf<String>()
                        if (file.fileType == SpringFileType.REST_CONTROLLER) tags.add("@RestController")
                        if (file.fileType == SpringFileType.CONTROLLER) tags.add("@Controller")
                        
                        if (file.implementedInterfaces.isNotEmpty()) {
                            tags.add("implements ${file.implementedInterfaces.joinToString(", ")}")
                        }
                        
                        if (tags.isNotEmpty()) {
                            append(" (${tags.joinToString(", ")})")
                        }
                    }
                    sb.append(classLine).append("\n")

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
        
        return summary.toString()
    }
}
