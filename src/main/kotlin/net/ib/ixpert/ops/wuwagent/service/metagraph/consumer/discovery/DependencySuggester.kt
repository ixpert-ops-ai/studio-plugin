package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph

data class DependencySuggestion(
    val packagePath: String,
    val fileCount: Int,
    val referencedFiles: List<String>,
    val referenceCount: Int,
    val referenceTypes: Set<String>,
    val isUtility: Boolean,
    val reason: String
)

data class SuggestionConfig(
    val minReferenceCount: Int = 3,
    val maxSuggestions: Int = 5,
    val includeUtilities: Boolean = false,
    val utilityPatterns: List<String> = listOf(
        "common/util", "common/config", "shared/util",
        "core/util", "infra/config", "global/",
        "bizUtil", "/util", "constant"
    )
)

object DependencySuggester {
    fun suggestExternalDependencies(
        subMetaGraph: SubMetaGraph,
        fullMetaGraph: ProjectGraph,
        config: SuggestionConfig
    ): List<DependencySuggestion> {
        // Group external dependencies by package
        val packageGroups = subMetaGraph.externalDependencies.groupBy { extractPackagePath(it.targetFile.path) }

        return packageGroups.map { (pkg, deps) ->
            DependencySuggestion(
                packagePath = pkg,
                fileCount = countFilesInPackage(fullMetaGraph, pkg),
                referencedFiles = deps.map { it.targetFile.path }.distinct(),
                referenceCount = deps.sumOf { it.count },
                referenceTypes = deps.flatMap { it.relationTypes }.toSet(),
                isUtility = isUtilityPackage(pkg, config.utilityPatterns),
                reason = buildReason(deps, pkg)
            )
        }
        .filter { it.referenceCount >= config.minReferenceCount }
        .filter { !it.isUtility || config.includeUtilities }
        .sortedByDescending { it.referenceCount }
        .take(config.maxSuggestions)
    }

    private fun extractPackagePath(filePath: String): String {
        return filePath.substringBeforeLast("/", "")
    }

    private fun countFilesInPackage(fullMetaGraph: ProjectGraph, packagePath: String): Int {
        return fullMetaGraph.files.values.count { it.path.startsWith(packagePath) }
    }

    private fun isUtilityPackage(packagePath: String, patterns: List<String>): Boolean {
        return patterns.any { pattern -> packagePath.contains(pattern, ignoreCase = true) }
    }

    private fun buildReason(deps: List<ExternalDependency>, packagePath: String): String {
        val totalRefs = deps.sumOf { it.count }
        val typesStr = deps.flatMap { it.relationTypes }.toSet().joinToString(", ")
        
        // Find the most referencing source package
        val sourcePackages = deps.groupBy { extractPackagePath(it.sourceFile.path) }
        val topSourcePkg = sourcePackages.maxByOrNull { entry -> entry.value.sumOf { it.count } }?.key ?: "내부"
        
        return "${topSourcePkg}에서 ${totalRefs}회 참조 (${typesStr})"
    }
}
