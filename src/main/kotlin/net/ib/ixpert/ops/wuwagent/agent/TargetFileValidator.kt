package net.ib.ixpert.ops.wuwagent.agent

import com.intellij.openapi.diagnostic.Logger
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FileNode
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.AnyframeRole
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.SpringFileType

object TargetFileValidator {
    private val logger = Logger.getInstance(TargetFileValidator::class.java)

    /**
     * LLM이 추출한 TargetFileSpec 목록을 MetaGraph와 대조하여 환각을 자동 교정합니다.
     * 하위 호환성을 위해 유지되는 메서드로, correctPaths와 sortByDependency를 순차 호출합니다.
     */
    fun validate(specs: List<TargetFileSpec>, graph: ProjectGraph): List<TargetFileSpec> {
        val corrected = correctPaths(specs, graph)
        return sortByDependency(corrected, graph)
    }

    /**
     * 경로 및 신규/수정 여부를 보정합니다. (정렬 수행 안 함)
     */
    fun correctPaths(specs: List<TargetFileSpec>, graph: ProjectGraph): List<TargetFileSpec> {
        if (graph.files.isEmpty()) return specs

        var correctionCount = 0
        val correctionsLog = mutableListOf<String>()

        val correctedSpecs = specs.map { spec ->
            val className = extractClassName(spec.path)
            val candidates = findCandidates(spec.path, className, graph)

            var correctedPath = spec.path
            var correctedType = spec.type
            var correctedDesc = spec.description
            var hasCorrection = false

            when {
                // 1순위: 후보군이 1개이고, 경로가 다름 (경로 보정 - 검증 3)
                candidates.size == 1 && spec.path != candidates[0].path -> {
                    correctedPath = candidates[0].path
                    correctedDesc += " [AI 교정: 경로 보정]"
                    hasCorrection = true
                }
                // 동명이인이 여러 개이고 경로가 완전 불일치 (경고만)
                candidates.size > 1 && candidates.none { it.path == spec.path } -> {
                    correctedDesc += " [경고: 동일 클래스명 ${candidates.size}건 발견, 수동 확인 필요]"
                    hasCorrection = true
                }
            }

            val finalCandidates = findCandidates(correctedPath, className, graph)
            val existsInGraph = finalCandidates.any { it.path == correctedPath } || finalCandidates.size == 1

            when {
                // 검증 1: 신규 -> 수정
                correctedType == "신규" && existsInGraph -> {
                    correctedType = "수정"
                    correctedDesc += " [AI 교정: 기존 파일 존재]"
                    if (finalCandidates.size == 1 && correctedPath != finalCandidates[0].path) {
                         correctedPath = finalCandidates[0].path
                    }
                    hasCorrection = true
                }
                // 검증 2: 수정 -> 신규
                correctedType == "수정" && !existsInGraph -> {
                    correctedType = "신규"
                    correctedPath = suggestPath(className, spec.path, graph)
                    correctedDesc += " [AI 교정: 파일 없음, 신규 생성]"
                    hasCorrection = true
                }
            }

            if (hasCorrection) {
                correctionCount++
                correctionsLog.add("${spec.path}(${spec.type}) -> $correctedPath($correctedType)")
            }

            spec.copy(path = correctedPath, type = correctedType, description = correctedDesc)
        }

        if (correctionCount > 0) {
            logger.warn("TargetFileValidator 교정 $correctionCount 건: ${correctionsLog.joinToString(", ")}")
        } else {
            logger.info("TargetFileValidator: LLM 응답 모두 정상 (교정 없음)")
        }

        return correctedSpecs
    }

    /**
     * 보정된 목록을 프레임워크별 의존성(가중치) 기준으로 정렬하고 order를 재부여합니다.
     */
    fun sortByDependency(specs: List<TargetFileSpec>, graph: ProjectGraph): List<TargetFileSpec> {
        return specs.sortedWith(
            compareBy<TargetFileSpec> { getSortWeight(it.path, graph) }
                .thenBy { it.order }
        ).mapIndexed { index, spec ->
            spec.copy(order = index + 1)
        }
    }

    /**
     * 프레임워크에 따른 파일 의존성 가중치(정렬 순서)를 반환합니다.
     * 값이 작을수록 먼저 생성/수정되어야 하는 하위 의존성(컴파일 의존성 기준)입니다.
     */
    private fun getSortWeight(path: String, graph: ProjectGraph): Int {
        val node = graph.files[path]
        val lowerPath = path.lowercase()

        if (graph.frameworkType == FrameworkType.ANYFRAME_AP) {
            val role = node?.anyframeRole
            return when {
                role == AnyframeRole.DVO || lowerPath.contains("dvo") -> 1
                role == AnyframeRole.DEM || role == AnyframeRole.DQM || lowerPath.contains("dem") || lowerPath.contains("dqm") -> 2
                role == AnyframeRole.BVO || lowerPath.contains("bvo") -> 3
                role == AnyframeRole.BIZ_UTIL || role == AnyframeRole.BIZ || lowerPath.contains("biz") -> 4
                role == AnyframeRole.SVO || lowerPath.contains("svo") -> 5
                role == AnyframeRole.SVC_IMPL || role == AnyframeRole.SVC || lowerPath.contains("svc") -> 6
                else -> 7
            }
        } else {
            val type = node?.fileType?.name
            return when {
                type in setOf("ENTITY", "REPOSITORY", "MAPPER", "DAO") || lowerPath.contains("entity") || lowerPath.contains("repository") || lowerPath.contains("dao") || lowerPath.contains("mapper") -> 1
                type == "DTO" || type == "VO" || type == "EXCEPTION" || lowerPath.contains("dto") || lowerPath.contains("request") || lowerPath.contains("response") || lowerPath.contains("exception") -> 2
                type == "SERVICE" || lowerPath.contains("service") || lowerPath.contains("impl") -> 3
                type in setOf("CONTROLLER", "REST_CONTROLLER") || lowerPath.contains("controller") || lowerPath.contains("api") -> 4
                else -> 5
            }
        }
    }

    private fun extractClassName(path: String): String {
        return path.substringAfterLast("/").substringBeforeLast(".")
    }

    /**
     * 경로 또는 클래스명으로 그래프 내 후보 노드를 찾습니다.
     * 1순위: path 완전 일치
     * 2순위: className + 패키지명 일치 (유사도)
     * 3순위: className 일치
     */
    private fun findCandidates(path: String, className: String, graph: ProjectGraph): List<FileNode> {
        val allNodes = graph.files.values

        // 1순위
        val exactMatch = allNodes.find { it.path == path }
        if (exactMatch != null) return listOf(exactMatch)

        // 2 & 3순위용 필터링
        val classMatches = allNodes.filter { it.className == className }
        if (classMatches.isEmpty()) return emptyList()
        if (classMatches.size == 1) return classMatches

        // 패키지가 유사한 것을 2순위로 찾기
        val requestedPackage = path.substringBeforeLast("/").replace("/", ".")
        val packageMatch = classMatches.filter { node ->
            val nodePkg = node.packageName ?: ""
            // 간단한 키워드 매칭
            requestedPackage.contains(nodePkg.substringAfterLast(".")) || nodePkg.contains(requestedPackage.substringAfterLast("."))
        }

        return if (packageMatch.isNotEmpty()) packageMatch else classMatches
    }

    /**
     * 신규 파일의 권장 경로를 추론합니다.
     * LLM이 제시한 경로가 너무 엉뚱할 경우, 동일 도메인의 다른 파일 경로를 참고합니다.
     */
    private fun suggestPath(className: String, originalPath: String, graph: ProjectGraph): String {
        // 이미 그럴싸한 전체 경로면 그대로 사용
        if (originalPath.contains("src/main/java")) return originalPath
        
        if (graph.files.isEmpty()) return originalPath

        // 패키지를 유추하기 위한 접미사 확인
        val typeSuffix = when {
            className.endsWith("Controller") -> "Controller"
            className.endsWith("ServiceImpl") || className.endsWith("Service") -> "Service"
            className.endsWith("Dto") -> "Dto"
            className.endsWith("DaoImpl") || className.endsWith("Dao") -> "Dao"
            else -> null
        }

        if (typeSuffix != null) {
            // 동일한 typeSuffix를 가진 파일을 찾기 (이름 앞부분이 유사한 순으로)
            val prefix = className.replace(Regex("(Controller|ServiceImpl|Service|Dto|DaoImpl|Dao)$"), "")
            
            // 1순위: 접두사가 일치하고 접미사가 같은 파일 (예: AddressDto 신규 -> AddressDao 기존 파일)
            var peerFile = graph.files.values.find { it.className.startsWith(prefix) && it.className != className }
            
            // 2순위: 그냥 접미사가 같은 파일 중 첫 번째
            if (peerFile == null) {
                peerFile = graph.files.values.find { it.className.endsWith(typeSuffix) }
            }
            
            if (peerFile != null) {
                val peerDir = peerFile.path.substringBeforeLast("/")
                val extension = originalPath.substringAfterLast(".", "java")
                // dto나 dao 등 서브 디렉토리를 보정
                val targetDir = if (!peerDir.endsWith(typeSuffix.lowercase()) && typeSuffix != "Controller") {
                    "$peerDir/${typeSuffix.lowercase()}"
                } else peerDir
                
                return "$targetDir/$className.$extension"
            }
        }

        return originalPath
    }
}
