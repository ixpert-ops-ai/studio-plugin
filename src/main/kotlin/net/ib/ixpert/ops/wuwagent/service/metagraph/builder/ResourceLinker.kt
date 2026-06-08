package net.ib.ixpert.ops.wuwagent.service.metagraph.builder

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FileNode
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ResourceNode
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ResourceType

class ResourceLinker(private val javaNodes: Map<String, FileNode>) {

    // URL to FileNode map for quick lookup
    // Assuming apiEndpoints are populated
    private val endpointIndex: Map<String, FileNode> by lazy {
        val index = mutableMapOf<String, FileNode>()
        javaNodes.values.forEach { node ->
            node.apiEndpoints.forEach { ep ->
                index[ep.path] = node
            }
        }
        index
    }

    // ClassName to FileNode map
    private val classIndex: Map<String, FileNode> by lazy {
        val index = mutableMapOf<String, FileNode>()
        javaNodes.values.forEach { node ->
            index[node.className] = node
            // Also index by FQN
            if (node.packageName != null) {
                index["${node.packageName}.${node.className}"] = node
            }
        }
        index
    }

    private val viewReturnIndex: Map<String, List<FileNode>> by lazy {
        val index = mutableMapOf<String, MutableList<FileNode>>()
        javaNodes.values.forEach { node ->
            node.apiEndpoints.forEach { ep ->
                ep.returnedViewNames.forEach { viewName ->
                    index.getOrPut(viewName) { mutableListOf() }.add(node)
                }
            }
        }
        index
    }

    fun link(resourceNodes: List<ResourceNode>): List<ResourceNode> {
        val viewResolutionCache = mutableMapOf<String, List<ResourceNode>>()
        val viewNodes = resourceNodes.filter { it.type == ResourceType.VIEW }
        
        viewReturnIndex.keys.forEach { viewName ->
            val expectedSuffix = "/" + viewName.trimStart('/').lowercase() + ".jsp"
            var matches = viewNodes.filter { it.path.lowercase().endsWith(expectedSuffix) || it.path.lowercase().endsWith(expectedSuffix.replace(".jsp", ".html")) }
            
            if (matches.isEmpty()) {
                val lastSegment = viewName.substringAfterLast("/")
                if (lastSegment.isNotBlank()) {
                    val fallbackSuffix = "/${lastSegment.lowercase()}.jsp"
                    matches = viewNodes.filter { it.path.lowercase().endsWith(fallbackSuffix) || it.path.lowercase().endsWith(fallbackSuffix.replace(".jsp", ".html")) }
                    if (matches.size > 1) {
                        matches = emptyList() // discard due to ambiguity
                    }
                }
            }
            viewResolutionCache[viewName] = matches
        }

        // [P1] 전역 baseURL 수집
        val rawBaseUrls = resourceNodes.flatMap { (it.metadata["base_url"] as? List<*>)?.map { obj -> obj.toString() } ?: emptyList() }
        val globalBaseUrls = rawBaseUrls.mapNotNull { url ->
            val noDomain = url.replace(Regex("^https?://[^/]+"), "")
            val clean = noDomain.trimEnd('/')
            if (clean.isNotEmpty()) clean else null
        }.distinct()

        return resourceNodes.map { node ->
            var updatedNode = when (node.type) {
                ResourceType.MYBATIS_MAPPER -> linkMyBatis(node)
                ResourceType.SCRIPT, ResourceType.VIEW -> linkUrlBased(node, globalBaseUrls)
                else -> node
            }
            
            if (updatedNode.type == ResourceType.VIEW) {
                val addedLinks = mutableListOf<String>()
                viewReturnIndex.forEach { (viewName, controllers) ->
                    val resolvedResources = viewResolutionCache[viewName] ?: emptyList()
                    if (resolvedResources.any { it.path == updatedNode.path }) {
                        addedLinks.addAll(controllers.map { it.path })
                    }
                }
                if (addedLinks.isNotEmpty()) {
                    val combinedLinks = (updatedNode.linkedTo + addedLinks).distinct()
                    updatedNode = updatedNode.copy(linkedTo = combinedLinks, linkType = "view_binding")
                }
            }
            updatedNode
        }
    }

    private fun linkMyBatis(node: ResourceNode): ResourceNode {
        val namespaces = node.metadata["namespace"] as? List<*> ?: emptyList<String>()
        val linked = mutableListOf<String>()

        namespaces.forEach { ns ->
            val nsStr = ns.toString()
            val fileNode = classIndex[nsStr]
            if (fileNode != null) {
                linked.add(fileNode.path)
            } else {
                // Try simple name matching if FQN fails
                val simpleName = nsStr.substringAfterLast(".")
                val exactMatch = classIndex[simpleName]
                if (exactMatch != null) {
                    linked.add(exactMatch.path)
                } else {
                    // Heuristic matching (e.g. namespace="user" -> UserDaoImpl)
                    val capitalized = simpleName.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                    val possibleNames = listOf(
                        "${capitalized}Dao",
                        "${capitalized}DaoImpl",
                        "${capitalized}Mapper",
                        "${capitalized}Repository",
                        "${capitalized}ServiceImpl"
                    )
                    
                    for (possibleName in possibleNames) {
                        val match = classIndex[possibleName]
                        if (match != null) {
                            linked.add(match.path)
                            break
                        }
                    }
                }
            }
        }

        // Also handle anyframe_query_id -> linked to DAO/Service calling queryService.find(id)
        // For Phase 1, we just extract them. If javaNodes had the anyframe query ID, we would link it.
        // As per the architect, "queryService.find(queryId) should be extracted from Java source".
        // Let's assume Java nodes don't have this yet or they do.
        // We will just return the namespace links for now.
        
        if (linked.isNotEmpty()) {
            return node.copy(linkedTo = linked.distinct(), linkType = "namespace_binding")
        }

        return node
    }

    private fun linkUrlBased(node: ResourceNode, globalBaseUrls: List<String>): ResourceNode {
        val linked = mutableListOf<String>()
        
        val urlFields = listOf("api_url", "form_action")
        val candidateUrls = urlFields.flatMap { 
            (node.metadata[it] as? List<*>)?.map { obj -> obj.toString() } ?: emptyList() 
        }

        candidateUrls.forEach { url ->
            val matchedNode = findMatchingController(url, globalBaseUrls)
            if (matchedNode != null) {
                linked.add(matchedNode.path)
            }
        }

        if (linked.isNotEmpty()) {
            return node.copy(linkedTo = linked.distinct(), linkType = "url_binding")
        }

        return node
    }

    private fun cleanUrl(url: String): String {
        return url.split("?").first()
            .replace("'", "")
            .replace("\"", "")
            .replace("`", "")
            .trim()
    }

    private fun findMatchingController(vueUrl: String, globalBaseUrls: List<String>): FileNode? {
        val cleanVueUrl = normalizePathVariables(cleanUrl(vueUrl))
        val vueSegments = cleanVueUrl.split("/").filter { it.isNotEmpty() }
        
        if (vueSegments.isEmpty()) return null

        val candidatesBySuffix = mutableListOf<FileNode>()

        for ((epUrl, controllerNode) in endpointIndex) {
            val cleanEpUrl = normalizePathVariables(epUrl)
            val epSegments = cleanEpUrl.split("/").filter { it.isNotEmpty() }

            // 1. Exact Match
            if (vueSegments == epSegments) {
                return controllerNode
            }

            // 2. Suffix Match check
            if (epSegments.size > vueSegments.size) {
                val epSuffix = epSegments.takeLast(vueSegments.size)
                if (epSuffix == vueSegments) {
                    val epPrefixSegments = epSegments.take(epSegments.size - vueSegments.size)
                    val epPrefix = "/" + epPrefixSegments.joinToString("/")
                    
                    // 3. Prefix BaseURL Match
                    if (globalBaseUrls.contains(epPrefix)) {
                        return controllerNode
                    }
                    
                    // Add to fallback candidates
                    if (vueSegments.size >= 2) {
                        candidatesBySuffix.add(controllerNode)
                    }
                }
            }
        }

        // 4. Fuzzy Suffix Match (최소 2세그먼트 이상)
        if (candidatesBySuffix.size == 1) {
            return candidatesBySuffix.first()
        }

        // 5. Legacy 1-segment Fallback (기존 동작 호환성 유지)
        if (vueSegments.size == 1) {
            val fallbackCandidates = endpointIndex.entries.filter { (epUrl, _) ->
                epUrl.contains(vueSegments.first(), ignoreCase = true)
            }
            if (fallbackCandidates.size == 1) {
                return fallbackCandidates.first().value
            }
        }

        return null
    }

    private fun normalizePathVariables(url: String): String {
        return url.replace(Regex("\\$\\{[^}]+\\}"), "{*}")
                  .replace(Regex("\\{[^}]+\\}"), "{*}")
    }
}
