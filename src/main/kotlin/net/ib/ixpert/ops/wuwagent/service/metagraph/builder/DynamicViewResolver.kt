package net.ib.ixpert.ops.wuwagent.service.metagraph.builder

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ApiEndpoint
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.DynamicBinding
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FileNode
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ResourceNode
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.SpringFileType

/**
 * P5-B: Front Controller 동적 뷰 역추적 처리기
 */
class DynamicViewResolver {

    data class PathSegments(
        val folder: String,
        val fileName: String,
        val variants: Set<String>
    ) {
        companion object {
            val EMPTY = PathSegments("", "", emptySet())
        }
    }

    data class FrontControllerInfo(
        val controllerPath: String,
        val wildcardEndpoints: List<ApiEndpoint>,
        val viewBasePaths: List<String> = emptyList()
    )

    /**
     * Front Controller 패턴 감지
     * PathVariable 2개 이상이거나, 1개 이상이면서 커맨드 패턴(proc 등)을 쓰는 경우 동적 라우터로 판단합니다.
     */
    fun detectFrontControllers(controllerNodes: List<FileNode>): List<FrontControllerInfo> {
        return controllerNodes.filter { it.fileType == SpringFileType.CONTROLLER || it.fileType == SpringFileType.REST_CONTROLLER }
            .mapNotNull { controller ->
                val wildcardEndpoints = controller.apiEndpoints.filter { endpoint ->
                    val pathVarsCount = countPathVariables(endpoint.path)
                    val isCommandPattern = endpoint.handlerMethod.lowercase() == "proc" || endpoint.handlerMethod.lowercase().contains("proc")
                    
                    pathVarsCount >= 2 || (pathVarsCount >= 1 && isCommandPattern)
                }

                if (wildcardEndpoints.isNotEmpty()) {
                    FrontControllerInfo(
                        controllerPath = controller.path,
                        wildcardEndpoints = wildcardEndpoints
                    )
                } else {
                    null
                }
            }
    }

    private fun countPathVariables(url: String): Int {
        return Regex("\\{[^/]+\\}").findAll(url).count()
    }

    /**
     * 리소스 역바인딩 실행
     */
    fun resolveViewBindings(
        frontControllers: List<FrontControllerInfo>,
        resourceNodes: List<ResourceNode>
    ): List<DynamicBinding> {
        val bindings = mutableListOf<DynamicBinding>()

        resourceNodes.forEach { resource ->
            val segments = extractPathSegments(resource.path)
            if (segments == PathSegments.EMPTY) return@forEach

            frontControllers.forEach { fc ->
                fc.wildcardEndpoints.forEach { endpoint ->
                    val confidence = calculateConfidence(segments, endpoint)
                    if (confidence > 0.0) {
                        bindings.add(
                            DynamicBinding(
                                resourcePath = resource.path,
                                controllerPath = fc.controllerPath,
                                matchedUrl = reconstructUrl(endpoint, segments),
                                confidence = confidence
                            )
                        )
                    }
                }
            }
        }

        return bindings
    }

    /**
     * 경로 세그먼트 추출
     * WEB-INF/views/{folder}/{file}.jsp -> PathSegments(folder, file)
     * resources/js/{folder}/{file}.js -> PathSegments(folder, file)
     */
    private fun extractPathSegments(path: String): PathSegments {
        val normalized = path.replace("\\", "/")

        // JSP: .../views/{folder}/{filename}.jsp
        val jspPattern = Regex(".*/views/([^/]+)/([^/]+)\\.jsp$")
        // JS: .../js/{folder}/{filename}.js
        val jsPattern = Regex(".*/js/([^/]+)/([^/]+)\\.js$")

        var match = jspPattern.find(normalized)
        if (match == null) {
            match = jsPattern.find(normalized)
        }

        return match?.let {
            PathSegments(
                folder = it.groupValues[1],
                fileName = it.groupValues[2],
                variants = generateNameVariants(it.groupValues[2])
            )
        } ?: PathSegments.EMPTY
    }

    /**
     * 파일명 변형 생성
     */
    private fun generateNameVariants(fileName: String): Set<String> {
        val variants = mutableSetOf(fileName.lowercase())

        // snake_case 처리
        if (fileName.contains("_")) {
            val parts = fileName.split("_")
            variants.add(parts.joinToString("") { p -> p.replaceFirstChar { it.uppercase() } }
                .replaceFirstChar { it.lowercase() }) // camelCase
            variants.add(parts.joinToString(".")) // dot notation
        }

        // dot notation 처리
        if (fileName.contains(".") && !fileName.endsWith(".jsp") && !fileName.endsWith(".js")) {
            val parts = fileName.split(".")
            variants.add(parts.joinToString("_")) // snake_case
            variants.add(parts.joinToString("") { p -> p.replaceFirstChar { it.uppercase() } }
                .replaceFirstChar { it.lowercase() }) // camelCase
        }

        // camelCase 처리
        val camelParts = fileName.split(Regex("(?=[A-Z])"))
        if (camelParts.size > 1) {
            variants.add(camelParts.joinToString("_") { it.lowercase() }) // snake_case
            variants.add(camelParts.joinToString(".") { it.lowercase() }) // dot notation
        }

        variants.add(fileName) // 원본
        return variants
    }

    /**
     * 신뢰도 계산
     */
    private fun calculateConfidence(segments: PathSegments, endpoint: ApiEndpoint): Double {
        var score = 0.0
        val urlLower = endpoint.path.lowercase()
        val hasTwoPathVars = countPathVariables(endpoint.path) >= 2

        if (hasTwoPathVars) {
            // /{folderName}/{pageName} 패턴
            // 폴더명 일치 여부는 URL에 고정 경로가 남아있다면 검사
            val staticParts = endpoint.path.split("/").filter { !it.contains("{") && it.isNotBlank() }
            if (staticParts.contains(segments.folder.lowercase())) {
                score += 40.0
            } else {
                // 고정된 폴더명이 URL에 없으므로 (전체가 와일드카드)
                // 폴더명 자체를 검사할 수 없으니 기본 신뢰도 부여
                score += 30.0
            }

            if (segments.variants.any { urlLower.contains(it.lowercase()) }) {
                score += 50.0
            } else {
                // URL 자체에 파일명이 들어가지 않음 (전부 와일드카드)
                score += 50.0 // 와일드카드로 매칭된다고 가정
            }

        } else {
            // /ajax/{pageName} 등 PathVariable 1개 패턴
            val staticParts = endpoint.path.split("/").filter { !it.contains("{") && it.isNotBlank() }
            val folderMatch = staticParts.contains(segments.folder.lowercase())

            if (folderMatch) {
                score += 50.0 // 폴더 정확히 일치
                score += 35.0 // 파일명 와일드카드 매칭
            } else {
                // 폴더가 일치하지 않으면 오탐지 방지
                // 단축 URL (예: /surveyWrite.do 인데 파일은 /survey/surveyWrite.jsp 인 경우)
                // 현재 알고리즘에서는 variant만 일치해도 낮은 신뢰도(50.0)로 인정
                // 하지만 endpoint.path가 "/ajax/{pageName}"이고 파일이 "views/survey/survey_write.jsp"라면?
                // 이런 오탐지를 막기 위해, 고정된 staticPart가 있는데 segments.folder와 다르면 스킵.
                if (staticParts.isNotEmpty() && !staticParts.contains(segments.folder.lowercase())) {
                    // 단, staticPart가 모듈명 등이고 실제 폴더가 아닐 수 있음.
                    score += 50.0 // 사용자가 제안한 대로 confidence 낮춤
                } else {
                    score += 60.0
                }
            }
        }
        
        // 100을 넘지 않도록 제한
        return Math.min(score, 100.0)
    }

    /**
     * 재구성된 URL (로깅/참고용)
     */
    private fun reconstructUrl(endpoint: ApiEndpoint, segments: PathSegments): String {
        var reconstructed = endpoint.path
        val matches = Regex("\\{[^/]+\\}").findAll(endpoint.path).toList()
        
        if (matches.size >= 2) {
            reconstructed = reconstructed.replaceFirst(matches[0].value, segments.folder)
            reconstructed = reconstructed.replaceFirst(matches[1].value, segments.variants.firstOrNull { !it.contains("_") && !it.contains(".") } ?: segments.fileName)
        } else if (matches.size == 1) {
            reconstructed = reconstructed.replaceFirst(matches[0].value, segments.variants.firstOrNull { !it.contains("_") && !it.contains(".") } ?: segments.fileName)
        }
        
        return reconstructed
    }
}
