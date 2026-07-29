package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraphQueryable
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FileNode
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ResourceNode

class GraphExpander(
    private val graph: ProjectGraphQueryable,
    private val domainExtractor: DomainExtractor,
    private val config: DiscoveryConfig = DiscoveryConfig()
) {
    private val infraThreshold: Int = calculateInfrastructureThreshold(
        graph.files.values,
        config.infrastructurePercentile,
        config.minInfraThreshold
    )

    companion object {
        // SR 키워드 → Vue 파일명 패턴 매핑 (뷰 필터링용)
        private val VUE_KEYWORD_PATTERNS = mapOf(
            "가입" to listOf("Signup", "Register", "Join"),
            "회원가입" to listOf("Signup", "Register", "Join"),
            "등록" to listOf("Create", "Register", "Form"),
            "생성" to listOf("Create", "Register", "Form"),
            "상세" to listOf("Detail"),
            "수정" to listOf("Edit", "Update", "Form"),
            "목록" to listOf("List"),
            "조회" to listOf("List", "Detail", "View"),
            "검색" to listOf("List", "Search"),
            "삭제" to listOf("List", "Detail"),
            "로그인" to listOf("Login"),
            "마이페이지" to listOf("My", "Profile", "Mypage"),
            "프로필" to listOf("Profile", "My"),
            "채팅" to listOf("Chat"),
            "대시보드" to listOf("Dashboard"),
            "홈" to listOf("Home"),
        )

        // Enum/값 타입으로 간주하는 fileType들
        private val ENUM_FILE_TYPES = setOf("ENUM", "CONSTANT", "VALUE_TYPE")
    }

    fun expand(seedResult: SeedSelectionResult, srText: String = ""): Map<String, ExpansionStep> {
        val visited = mutableMapOf<String, ExpansionStep>()
        val queue = mutableListOf<FileNode>()

        // Fix D: Seed 도메인 집합 계산 — DEPENDED_BY 크로스 도메인 필터링에 사용
        val seedDomains = seedResult.seedClasses.mapNotNull { className ->
            val node = graph.files.values.find { it.className == className }
            node?.let { domainExtractor.getDomain(it.path) }
        }.toSet()

        val hop1Queue = mutableListOf<FileNode>()
        
        // Step A: Seed 파일 해소
        for (className in seedResult.seedClasses) {
            val fileNode = graph.files.values.find { it.className == className }
            if (fileNode != null) {
                visited[fileNode.path] = ExpansionStep(hop = 0, via = "SEED")
                queue.add(fileNode)
                
                // 동일 패키지 보너스 확장 (Hop 1로 간주)
                // Enum/값 타입 파일은 seed에 직접 포함된 경우에만 허용하고, SAME_PACKAGE 간접 확장에서는 제외
                val packageName = fileNode.packageName
                if (packageName != null) {
                    graph.files.values.filter { it.packageName == packageName && it.path != fileNode.path }.forEach { sibling ->
                        if (!visited.containsKey(sibling.path) && !isEnumOrValueType(sibling)) {
                            visited[sibling.path] = ExpansionStep(hop = 1, via = "SAME_PACKAGE", from = fileNode.path)
                            // [Fix] SAME_PACKAGE 노드도 큐에 넣어 하향 탐색이 이어지도록 수정
                            // (과거 APC SAME_PACKAGE 관찰 결과와의 교차 확인 메모)
                            hop1Queue.add(sibling)
                        }
                    }
                }
            } else {
                // 프론트엔드 파일(ResourceNode)인지 확인
                val resourceNode = graph.resourceNodes.find { it.path.substringAfterLast('/') == className || it.path == className }
                if (resourceNode != null) {
                    visited[resourceNode.path] = ExpansionStep(hop = 0, via = "SEED")
                    // ResourceNode는 FileNode가 아니므로 queue에 추가할 수 없으나, 
                    // 연결된 Java 파일을 hop1Queue에 넣어 확장을 이어간다.
                    resourceNode.linkedTo.forEach { linkedPath ->
                        val linkedJavaNode = graph.files[linkedPath]
                        if (linkedJavaNode != null && !visited.containsKey(linkedPath)) {
                            visited[linkedPath] = ExpansionStep(hop = 1, via = "RESOURCE_LINK", from = resourceNode.path)
                            hop1Queue.add(linkedJavaNode)
                        }
                    }
                }
            }
        }

        // Step B: Hop 1 확장 (직접 의존)
        for (node in queue) { // hop 0
            // 상향 탐색: 이 파일을 사용하는 곳 (도메인 제한 적용)
            for (depPath in node.dependedBy) {
                if (!visited.containsKey(depPath)) {
                    val depNode = graph.files[depPath]
                    if (depNode != null && isCommonInfrastructure(depNode).not()
                        && isDomainAllowed(depPath, node.path, seedDomains)) {
                        visited[depPath] = ExpansionStep(hop = 1, via = "DEPENDED_BY", from = node.path)
                        hop1Queue.add(depNode)
                    }
                }
            }

            // 하향 탐색: 이 파일이 의존하는 곳
            for (depPath in node.dependsOn) {
                if (!visited.containsKey(depPath)) {
                    val depNode = graph.files[depPath]
                    if (depNode != null && isCommonInfrastructure(depNode).not()
                        && isDomainAllowed(depPath, node.path, seedDomains)) {
                        visited[depPath] = ExpansionStep(hop = 1, via = "DEPENDS_ON", from = node.path)
                        hop1Queue.add(depNode)
                    }
                }
            }

            // 하향 탐색 (타입 참조): 이 파일이 시그니처 등으로 참조하는 곳
            for (depPath in node.usesTypes) {
                if (!visited.containsKey(depPath)) {
                    val depNode = graph.files[depPath]
                    if (depNode != null && isCommonInfrastructure(depNode).not()
                        && isDomainAllowed(depPath, node.path, seedDomains)) {
                        visited[depPath] = ExpansionStep(hop = 1, via = "USES_TYPE", from = node.path)
                        hop1Queue.add(depNode)
                    }
                }
            }
            
            // TODO: [Backlog] usedByTypes(상향) 순회는 과도한 상향 확산을 막기 위해 당분간 연결 보류 (미검증)
            // for (depPath in node.usedByTypes) { ... }

            // DTO/Entity의 경우, Controller의 apiEndpoints에 파라미터/반환타입으로 존재하는지 확인 (그래프 파서 누락 보정)
            if (node.fileType.name == "DTO" || node.fileType.name == "ENTITY") {
                for (otherNode in graph.files.values) {
                    if (otherNode.fileType.name == "REST_CONTROLLER" || otherNode.fileType.name == "CONTROLLER") {
                        val usesDto = otherNode.apiEndpoints.any { ep ->
                            ep.params.any { it.contains(node.className) } || 
                            ep.returnType?.contains(node.className) == true
                        }
                        if (usesDto) {
                            if (!visited.containsKey(otherNode.path) && isDomainAllowed(otherNode.path, node.path, seedDomains)) {
                                visited[otherNode.path] = ExpansionStep(hop = 1, via = "API_ENDPOINT_FALLBACK", from = node.path)
                                hop1Queue.add(otherNode)
                            }
                        }
                    }
                }
            }
        }

        // Step C: Hop 2+ 확장 (간접 의존, 조건부)
        var currentQueue = hop1Queue
        for (hop in 2..config.maxHop) {
            val nextQueue = mutableListOf<FileNode>()
            for (node in currentQueue) {
                val nodePath = node.path
                // Controller는 추가 확장 안 함
                if (node.fileType.name == "REST_CONTROLLER" || node.fileType.name == "CONTROLLER") {
                    continue
                } else if (node.fileType.name == "SERVICE") {
                    // Service -> Repository/BIZ/DataAccess (하향), Service -> Controller (상향)
                    for (depPath in node.dependsOn) {
                        if (!visited.containsKey(depPath)) {
                            val depNode = graph.files[depPath]
                            if (depNode != null && (depNode.fileType.name == "REPOSITORY" || depNode.fileType.name == "BIZ" || depNode.fileType.name == "DATA_ACCESS") && isCommonInfrastructure(depNode).not()
                                && isDomainAllowed(depPath, nodePath, seedDomains)) {
                                visited[depPath] = ExpansionStep(hop = hop, via = "SERVICE_TO_REPO_OR_BIZ", from = nodePath)
                                nextQueue.add(depNode)
                            }
                        }
                    }
                    for (depPath in node.usesTypes) {
                        if (!visited.containsKey(depPath)) {
                            val depNode = graph.files[depPath]
                            // [안전 측 기본값] Rule 2처럼 하향 탐색을 REPOSITORY/BIZ/DATA_ACCESS로만 엄격히 제한 (APC 회귀 미검증)
                            if (depNode != null && (depNode.fileType.name == "REPOSITORY" || depNode.fileType.name == "BIZ" || depNode.fileType.name == "DATA_ACCESS") && isCommonInfrastructure(depNode).not()
                                && isDomainAllowed(depPath, nodePath, seedDomains)) {
                                visited[depPath] = ExpansionStep(hop = hop, via = "USES_TYPE", from = nodePath)
                                nextQueue.add(depNode)
                            }
                        }
                    }
                    for (depPath in node.dependedBy) {
                        if (!visited.containsKey(depPath)) {
                            val depNode = graph.files[depPath]
                            if (depNode != null && (depNode.fileType.name == "CONTROLLER" || depNode.fileType.name == "REST_CONTROLLER")
                                && isCommonInfrastructure(depNode).not()
                                && isDomainAllowed(depPath, nodePath, seedDomains)) {
                                visited[depPath] = ExpansionStep(hop = hop, via = "SERVICE_TO_CONTROLLER", from = nodePath)
                                nextQueue.add(depNode)
                            }
                        }
                    }
                    // Controller에서 Service를 주입받는지(사용하는지) 확인 (그래프 파서 누락 보정)
                    for (otherNode in graph.files.values) {
                        if (otherNode.fileType.name == "REST_CONTROLLER" || otherNode.fileType.name == "CONTROLLER") {
                            val usesService = otherNode.injections.any { it.targetType == node.className }
                            if (usesService) {
                                if (!visited.containsKey(otherNode.path) && isDomainAllowed(otherNode.path, nodePath, seedDomains)) {
                                    visited[otherNode.path] = ExpansionStep(hop = hop, via = "INJECTION_FALLBACK", from = nodePath)
                                    nextQueue.add(otherNode)
                                }
                            }
                        }
                    }
                } else if (node.fileType.name == "BIZ") {
                    // BIZ -> DATA_ACCESS (하향)
                    for (depPath in node.dependsOn) {
                        if (!visited.containsKey(depPath)) {
                            val depNode = graph.files[depPath]
                            if (depNode != null && (depNode.fileType.name == "DATA_ACCESS" || depNode.fileType.name == "REPOSITORY")) {
                                // 하향(BIZ -> DATA_ACCESS) 엣지는 인프라 및 도메인 필터 우회 (Track 2 완화책)
                                visited[depPath] = ExpansionStep(hop = hop, via = "BIZ_TO_DATA_ACCESS", from = nodePath)
                                nextQueue.add(depNode)
                            }
                        }
                    }
                    for (depPath in node.usesTypes) {
                        if (!visited.containsKey(depPath)) {
                            val depNode = graph.files[depPath]
                            // [안전 측 기본값] Rule 2처럼 하향(BIZ -> DATA_ACCESS) 엄격 제한 (APC 회귀 미검증)
                            if (depNode != null && (depNode.fileType.name == "DATA_ACCESS" || depNode.fileType.name == "REPOSITORY")) {
                                visited[depPath] = ExpansionStep(hop = hop, via = "USES_TYPE", from = nodePath)
                                nextQueue.add(depNode)
                            }
                        }
                    }
                    // BIZ -> SERVICE (상향)
                    for (depPath in node.dependedBy) {
                        if (!visited.containsKey(depPath)) {
                            val depNode = graph.files[depPath]
                            if (depNode != null && depNode.fileType.name == "SERVICE"
                                && isCommonInfrastructure(depNode).not()
                                && isDomainAllowed(depPath, nodePath, seedDomains)) {
                                visited[depPath] = ExpansionStep(hop = hop, via = "BIZ_TO_SERVICE", from = nodePath)
                                nextQueue.add(depNode)
                            }
                        }
                    }
                } else if (node.fileType.name == "REPOSITORY" || node.fileType.name == "DATA_ACCESS") {
                    // Rule 2: 하향 탐색 중 도달한 데이터 액세스 노드에서는 상향 전파 금지
                    if (hop > 0) {
                        if (node.className.contains("Product")) {
                            println("[DEBUG] REPOSITORY continue blocked expansion for ${node.className} at hop $hop")
                        }
                        continue
                    }

                    // Repository -> Service (상향, 도메인 제한 적용)
                    for (depPath in node.dependedBy) {
                        if (!visited.containsKey(depPath)) {
                            val depNode = graph.files[depPath]
                            if (depNode != null && depNode.fileType.name == "SERVICE"
                                && isCommonInfrastructure(depNode).not()
                                && isDomainAllowed(depPath, nodePath, seedDomains)) {
                                visited[depPath] = ExpansionStep(hop = hop, via = "REPO_TO_SERVICE", from = nodePath)
                                nextQueue.add(depNode)
                            }
                        }
                    }
                    // Service에서 Repository를 주입받는지 확인 (그래프 파서 누락 보정)
                    for (otherNode in graph.files.values) {
                        if (otherNode.fileType.name == "SERVICE") {
                            val usesRepo = otherNode.injections.any { it.targetType == node.className }
                            if (usesRepo) {
                                if (!visited.containsKey(otherNode.path) && isDomainAllowed(otherNode.path, nodePath, seedDomains)) {
                                    visited[otherNode.path] = ExpansionStep(hop = hop, via = "INJECTION_FALLBACK", from = nodePath)
                                    nextQueue.add(otherNode)
                                }
                            }
                        }
                    }
                } else {
                    // Rule 2: 하향 탐색 중 도달한 데이터 액세스 노드에서는 무관 도메인으로의 상향 전파를 원천 금지 (Track 1 상향 시드는 hop 0에서 처리됨)
                    if ((node.fileType.name == "DATA_ACCESS" || node.fileType.name == "REPOSITORY") && hop > 0) {
                        continue
                    }
                    
                    // Entity 등 기타 노드의 상위 의존성 확장
                    for (depPath in node.dependedBy) {
                        if (!visited.containsKey(depPath)) {
                            val depNode = graph.files[depPath]
                            if (depNode != null && isCommonInfrastructure(depNode).not()
                                && isDomainAllowed(depPath, nodePath, seedDomains)) {
                                visited[depPath] = ExpansionStep(hop = hop, via = "DEPENDED_BY", from = nodePath)
                                nextQueue.add(depNode)
                            }
                        }
                    }
                }
            }
            currentQueue = nextQueue
            if (currentQueue.isEmpty()) break
        }

        // Step D: Vue 연결 (frontendRelevant == true일 때만)
        println("[DEBUG-EXPANDER] frontendRelevant: ${seedResult.frontendRelevant}")
        if (seedResult.frontendRelevant) {
            // API_ENDPOINT_FALLBACK으로 들어온 Controller는 Vue 확장 대상에서 제외
            val directControllerPaths = visited.entries
                .filter { (_, step) -> step.via != "API_ENDPOINT_FALLBACK" && step.via != "INJECTION_FALLBACK" }
                .map { it.key }
                .filter { 
                    graph.files[it]?.fileType?.name == "REST_CONTROLLER" || graph.files[it]?.fileType?.name == "CONTROLLER" 
                }.toSet()

            // SR 텍스트에서 Vue 필터링 키워드 추출 및 frontendFileHints 병합
            val vueFilterPatterns = buildVueFilterPatterns(srText, seedResult.frontendFileHints ?: emptyList())

            for (resource in graph.resourceNodes) {
                if (resource.path.contains("ProductCreateView.vue")) {
                    println("[DEBUG-VUE] Considering ${resource.path}")
                    println("[DEBUG-VUE] resource.linkedTo: ${resource.linkedTo}")
                    println("[DEBUG-VUE] directControllerPaths: $directControllerPaths")
                }
                // resource.linkedTo 와 directControllerPaths 교집합 확인
                val intersection = resource.linkedTo.intersect(directControllerPaths)
                if (intersection.isNotEmpty()) {
                    if (resource.path.contains("ProductCreateView.vue")) {
                        println("[DEBUG-VUE] intersection is not empty: $intersection")
                    }
                    if (!visited.containsKey(resource.path)) {
                        val pathToMatch = resource.path.substringAfter("src/views/").ifEmpty { resource.path.substringAfterLast("/") }
                        // Vue 파일 경로/이름이 SR 키워드 패턴과 매칭되는 경우에만 포함
                        val isPatternMatch = vueFilterPatterns.isEmpty() || vueFilterPatterns.any { pattern ->
                            pathToMatch.contains(pattern, ignoreCase = true)
                        }
                        if (resource.path.contains("ProductCreateView.vue")) {
                            println("[DEBUG-VUE] isPatternMatch: $isPatternMatch (pathToMatch: $pathToMatch, vueFilterPatterns: $vueFilterPatterns)")
                        }
                        if (isPatternMatch) {
                            // Vue 파일 도메인 필터링
                            val isAllowed = isVueAllowed(resource, seedDomains)
                            if (resource.path.contains("ProductCreateView.vue")) {
                                println("[DEBUG-VUE] isVueAllowed: $isAllowed (seedDomains: $seedDomains)")
                            }
                            if (isAllowed) {
                                visited[resource.path] = ExpansionStep(hop = 1, via = "LINKED_TO", from = intersection.first())
                            }
                        }
                    } else {
                        if (resource.path.contains("ProductCreateView.vue")) {
                            println("[DEBUG-VUE] already visited!")
                        }
                    }
                } else if (vueFilterPatterns.isNotEmpty()) {
                    // Fallback: If not directly linked (e.g. using Vuex/Pinia), but matches the strong SR keyword
                    val pathToMatch = resource.path.substringAfter("src/views/").ifEmpty { resource.path.substringAfterLast("/") }
                    val isFallbackMatch = vueFilterPatterns.any { pattern -> pathToMatch.contains(pattern, ignoreCase = true) }
                    if (resource.path.contains("ProductCreateView.vue")) {
                        println("[DEBUG-VUE] fallback match: $isFallbackMatch (pathToMatch: $pathToMatch)")
                    }
                    if (isFallbackMatch) {
                        if (!visited.containsKey(resource.path) && isVueAllowed(resource, seedDomains)) {
                            // Find the first relevant controller to mark as 'from' for graph context, or just use a generic via
                            val fallbackFrom = directControllerPaths.firstOrNull() ?: "SR_KEYWORD"
                            visited[resource.path] = ExpansionStep(hop = 1, via = "KEYWORD_FALLBACK", from = fallbackFrom)
                        }
                    }
                }
            }
        }

        return visited
    }

    private fun isCommonInfrastructure(node: FileNode): Boolean {
        // BaseEntity, 공통 Utils 등 지나치게 참조가 많은 파일 제외
        val totalDependedBy = node.dependedBy.size + node.usedByTypes.size
        val isInfra = totalDependedBy >= infraThreshold
        if (isInfra && node.className.contains("Product")) {
            println("[DEBUG] isCommonInfrastructure blocked ${node.className} (totalDependedBy: $totalDependedBy >= $infraThreshold)")
        }
        return isInfra
    }

    /**
     * Enum이나 상수 정의 등 "값 타입" 파일인지 판별.
     * 이들은 SR에서 명시적으로 언급(seed에 직접 포함)하지 않는 한 SAME_PACKAGE 확장에서 제외.
     */
    private fun isEnumOrValueType(node: FileNode): Boolean {
        // fileType으로 판별
        if (ENUM_FILE_TYPES.contains(node.fileType.name)) return true
        // annotations에 @Enum이 없더라도 파일 내용이 Enum 패턴인 경우 (클래스명 + 짧은 라인수로 추정)
        // 보수적으로 fileType만 체크 (이미 메타그래프에서 ENUM으로 분류됨)
        return false
    }

    private fun buildVueFilterPatterns(srText: String, frontendFileHints: List<String>): Set<String> {
        val patterns = extractVueFilterPatterns(srText).toMutableSet()
        patterns.addAll(frontendFileHints)
        return patterns
    }

    /**
     * SR 텍스트에서 Vue 파일 필터링에 사용할 키워드 패턴들을 추출.
     * 예: "등록 화면"이 SR에 있으면 ["Create", "Register", "Form"]을 반환.
     */
    private fun extractVueFilterPatterns(srText: String): Set<String> {
        if (srText.isBlank()) return emptySet()
        val patterns = mutableSetOf<String>()
        for ((keyword, vuePatterns) in VUE_KEYWORD_PATTERNS) {
            if (srText.contains(keyword)) {
                patterns.addAll(vuePatterns)
            }
        }
        // 영문 키워드 직접 매칭 (SR에 "ProductDetail"이 있으면 Detail 패턴 추가)
        val englishTokens = Regex("[A-Z][a-z]+").findAll(srText).map { it.value }.toSet()
        patterns.addAll(englishTokens.filter { it.length >= 4 })
        return patterns
    }

    // ===== Fix D: 크로스 도메인 필터링 =====

    private fun isValuableCommonFile(node: FileNode?): Boolean {
        if (node == null) return true
        return when (node.fileType.name) {
            "ABSTRACT_CLASS" -> true
            "INTERFACE" -> true
            "DTO" -> true
            "DATA_ACCESS" -> true
            else -> false
        }
    }

    /**
     * DEPENDED_BY 등 확장 시 크로스 도메인 파일을 제외할지 판별.
     * - 대상 도메인이 null(COMMON) → 가치 있는 공통 파일(DTO, Interface 등)만 면제 (항상 포함)
     * - 대상 도메인 == 소스 도메인 → 포함
     * - 대상 도메인 ∈ seedDomains → 포함
     * - 그 외 크로스 도메인 → 제외
     */
    private fun isDomainAllowed(
        targetPath: String,
        sourcePath: String,
        seedDomains: Set<String>
    ): Boolean {
        if (!config.domainFilterEnabled) return true

        val targetDomain = domainExtractor.getDomain(targetPath)
        // COMMON(domain == null) → 가치 있는 공통 파일만 면제
        if (targetDomain == null) {
            val targetNode = graph.files[targetPath]
            if (isValuableCommonFile(targetNode)) return true
        }
        
        // 동일 도메인
        val sourceDomain = domainExtractor.getDomain(sourcePath)
        if (targetDomain == sourceDomain) return true
        // seed 도메인 집합에 포함
        if (targetDomain in seedDomains) return true
        // 크로스 도메인 → 제외
        return false
    }

    private fun getVueDomains(resourceNode: ResourceNode): Set<String> {
        return resourceNode.linkedTo
            .mapNotNull { domainExtractor.getDomain(it) }
            .toSet()
    }

    private fun isVueAllowed(
        resourceNode: ResourceNode,
        seedDomains: Set<String>
    ): Boolean {
        if (!config.domainFilterEnabled) return true

        val vueDomains = getVueDomains(resourceNode)
        
        // 고립 Vue (linkedTo가 없거나 모든 연결이 COMMON) → 포함
        if (vueDomains.isEmpty()) return true
        
        // seedDomains와 하나라도 교집합이 있으면 포함
        return vueDomains.any { it in seedDomains }
    }
}
