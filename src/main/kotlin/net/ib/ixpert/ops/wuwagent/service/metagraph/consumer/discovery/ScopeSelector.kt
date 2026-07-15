package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph

data class DirectoryNode(
    val path: String,
    val displayName: String,
    val fileCount: Int,
    val children: List<DirectoryNode>,
    val depth: Int
)

data class ScopeSelectionResult(
    val selectedPaths: List<String>,
    val totalFileCount: Int,
    val warnings: List<String>
)

data class ScopeConfig(
    val minFilesPerNode: Int = 2,
    val maxDepth: Int = 15,
    val maxSelections: Int = 15,
    val maxFiles: Int = 500,
    val warningThreshold: Int = 300
)

object ScopeSelector {
    
    fun buildDirectoryTree(
        metaGraph: ProjectGraph,
        config: ScopeConfig = ScopeConfig()
    ): List<DirectoryNode> {
        val paths = metaGraph.files.values.map { it.path } + metaGraph.resourceNodes.map { it.path }
        val rootNodes = mutableMapOf<String, MutableList<String>>() // prefix -> list of paths

        // Group paths to build hierarchy
        for (path in paths) {
            val abbreviated = abbreviatePath(path)
            val parts = abbreviated.split("/")
            if (parts.isNotEmpty()) {
                val topLevel = parts[0]
                rootNodes.computeIfAbsent(topLevel) { mutableListOf() }.add(abbreviated)
            }
        }
        
        return rootNodes.map { (prefix, groupPaths) ->
            buildNode(prefix, prefix, groupPaths, 1, config)
        }.filter { it.fileCount >= config.minFilesPerNode }
    }
    
    private fun buildNode(
        currentPath: String,
        displayName: String,
        paths: List<String>,
        depth: Int,
        config: ScopeConfig
    ): DirectoryNode {
        if (depth >= config.maxDepth) {
            return DirectoryNode(currentPath, displayName, paths.size, emptyList(), depth)
        }

        // Find children
        val prefixLen = currentPath.length + 1 // +1 for '/'
        val childrenGroups = mutableMapOf<String, MutableList<String>>()
        
        for (p in paths) {
            if (p.length > prefixLen) {
                val remainder = p.substring(prefixLen)
                val nextPart = remainder.substringBefore("/")
                if (nextPart.isNotEmpty() && nextPart != remainder) {
                    val childPath = "$currentPath/$nextPart"
                    childrenGroups.computeIfAbsent(childPath) { mutableListOf() }.add(p)
                }
            }
        }
        
        val children = childrenGroups.map { (childPath, groupPaths) ->
            val childDisplayName = childPath.substringAfterLast("/")
            buildNode(childPath, childDisplayName, groupPaths, depth + 1, config)
        }.filter { it.fileCount >= config.minFilesPerNode }
        
        return DirectoryNode(currentPath, displayName, paths.size, children, depth)
    }

    private fun abbreviatePath(fullPath: String): String {
        val patterns = listOf(
            "src/main/java/",
            "src/main/kotlin/",
            "src/main/resources/",
            "src/test/java/",
            "src/test/kotlin/",
            "src/main/webapp/",
            "frontend/src/"
        )
        var result = fullPath
        patterns.forEach { pattern ->
            if (result.contains(pattern)) {
                result = result.substringAfter(pattern)
            }
        }
        return result
    }

    fun validateSelection(
        selectedPaths: List<String>,
        metaGraph: ProjectGraph,
        config: ScopeConfig = ScopeConfig()
    ): ScopeSelectionResult {
        // Compute total files matching selected paths
        val matchingPaths = selectedPaths.map { abbreviatePath(it) }
        val totalFiles = metaGraph.files.values.count { file ->
            val abbr = abbreviatePath(file.path)
            matchingPaths.any { path -> abbr.startsWith(path) }
        } + metaGraph.resourceNodes.count { rNode ->
            val abbr = abbreviatePath(rNode.path)
            matchingPaths.any { path -> abbr.startsWith(path) }
        }

        val warnings = mutableListOf<String>()
        if (totalFiles > config.warningThreshold) {
            warnings.add("선택 범위가 넓습니다 (${totalFiles}개 파일). 더 좁힐 수 있으면 정확도가 향상됩니다.")
        }
        if (totalFiles > config.maxFiles) {
            warnings.add("최대 허용 파일 수(${config.maxFiles}개)를 초과합니다. 일부 디렉토리를 제외해 주세요.")
        }
        if (selectedPaths.isEmpty()) {
            warnings.add("최소 1개의 디렉토리를 선택해야 합니다.")
        }

        return ScopeSelectionResult(selectedPaths, totalFiles, warnings)
    }

    fun buildSubMetaGraph(
        fullMetaGraph: ProjectGraph,
        selectedPaths: List<String>
    ): SubMetaGraph {
        val matchingPaths = selectedPaths.map { abbreviatePath(it) }
        
        // 1. Filter files in scope
        val selectedFiles = fullMetaGraph.files.filterValues { file ->
            val abbr = abbreviatePath(file.path)
            matchingPaths.any { path -> abbr.startsWith(path) }
        }
        val selectedPathsSet = selectedFiles.keys

        // 2. Filter internal edges
        val internalEdges = fullMetaGraph.relationships.filter { edge ->
            edge.source in selectedPathsSet && edge.target in selectedPathsSet
        }

        // 3. Extract external dependencies
        val outgoingEdges = fullMetaGraph.relationships.filter { edge ->
            edge.source in selectedPathsSet && edge.target !in selectedPathsSet && fullMetaGraph.files.containsKey(edge.target)
        }
        
        val externalDependencies = outgoingEdges
            .groupBy { it.target }
            .map { (targetPath, edges) ->
                ExternalDependency(
                    sourceFile = selectedFiles[edges.first().source]!!,
                    targetFile = fullMetaGraph.files[targetPath]!!,
                    relationTypes = edges.map { it.type.name }.toSet(),
                    count = edges.size
                )
            }
            .sortedByDescending { it.count }
            
        // 4. ResourceNodes are fully included for simplicity (or can be filtered similarly)
        val selectedResourceNodes = fullMetaGraph.resourceNodes.filter { rNode ->
            val abbr = abbreviatePath(rNode.path)
            matchingPaths.any { path -> abbr.startsWith(path) }
        }

        return SubMetaGraph(
            files = selectedFiles,
            resourceNodes = selectedResourceNodes,
            internalEdges = internalEdges,
            externalDependencies = externalDependencies,
            sourceSelections = selectedPaths,
            frameworkDisplayName = fullMetaGraph.frameworkDisplayName,
            resolvedFrameworkType = fullMetaGraph.resolvedFrameworkType
        )
    }
}
