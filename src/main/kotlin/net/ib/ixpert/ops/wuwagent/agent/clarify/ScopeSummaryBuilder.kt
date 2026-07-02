package net.ib.ixpert.ops.wuwagent.agent.clarify

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph

object ScopeSummaryBuilder {
    private const val SCOPE_SUMMARY_TOKEN_LIMIT = 500

    fun buildScopeSummary(graph: ProjectGraph?): String {
        if (graph == null || graph.files.isEmpty()) {
            return ""
        }

        val totalFiles = graph.files.size
        
        // Group files by their package name (e.g., net.infobank.iss.controller)
        val packageMap = graph.files.values.groupBy { fileNode ->
            val pkg = fileNode.path.substringBeforeLast("/", "").replace("/", ".")
            if (pkg.isBlank()) "root" else pkg
        }.mapValues { entry ->
            entry.value.map { it.className }
        }.entries.sortedBy { it.key }

        var summary = when {
            totalFiles <= 100 -> buildFullSummary(packageMap, totalFiles)
            totalFiles <= 500 -> buildCondensedSummary(packageMap, totalFiles)
            totalFiles <= 2000 -> buildPackageOnlySummary(packageMap, totalFiles)
            else -> buildModuleOnlySummary(packageMap, totalFiles)
        }

        if (estimateTokens(summary) > SCOPE_SUMMARY_TOKEN_LIMIT) {
            summary = truncateToTokenLimit(summary, SCOPE_SUMMARY_TOKEN_LIMIT)
            summary += "\n... (일부 생략, 총 ${totalFiles}개 파일)"
        }

        return summary
    }

    private fun buildFullSummary(packageMap: List<Map.Entry<String, List<String>>>, totalFiles: Int): String {
        val sb = java.lang.StringBuilder()
        sb.appendLine("## 프로젝트 구조 ($totalFiles 개 파일)")
        for ((pkg, classes) in packageMap) {
            sb.appendLine("- $pkg: ${classes.joinToString(", ")}")
        }
        return sb.toString().trimEnd()
    }

    private fun buildCondensedSummary(packageMap: List<Map.Entry<String, List<String>>>, totalFiles: Int): String {
        val sb = java.lang.StringBuilder()
        val displayPackages = packageMap.take(15) // Limit number of packages to show
        sb.appendLine("## 프로젝트 구조 ($totalFiles 개 파일, ${packageMap.size} 개 패키지)")
        
        for ((pkg, classes) in displayPackages) {
            val displayClasses = classes.take(3)
            val remainder = classes.size - 3
            if (remainder > 0) {
                sb.appendLine("- $pkg: ${displayClasses.joinToString(", ")} 외 ${remainder}개")
            } else {
                sb.appendLine("- $pkg: ${displayClasses.joinToString(", ")}")
            }
        }
        
        val skippedPkgs = packageMap.size - displayPackages.size
        if (skippedPkgs > 0) {
            sb.appendLine("... (상위 ${displayPackages.size}개 패키지만 표시, 나머지 ${skippedPkgs}개 생략)")
        }
        return sb.toString().trimEnd()
    }

    private fun buildPackageOnlySummary(packageMap: List<Map.Entry<String, List<String>>>, totalFiles: Int): String {
        val sb = java.lang.StringBuilder()
        val displayPackages = packageMap.take(30).map { it.key }
        sb.appendLine("## 프로젝트 구조 ($totalFiles 개 파일, ${packageMap.size} 개 패키지)")
        sb.appendLine("주요 패키지: ${displayPackages.joinToString(", ")}")
        
        val skippedPkgs = packageMap.size - displayPackages.size
        if (skippedPkgs > 0) {
            sb.appendLine("... (${packageMap.size}개 패키지 중 상위 ${displayPackages.size}개 표시)")
        }
        return sb.toString().trimEnd()
    }

    private fun buildModuleOnlySummary(packageMap: List<Map.Entry<String, List<String>>>, totalFiles: Int): String {
        val sb = java.lang.StringBuilder()
        // Extract top-level module/directories
        val modules = packageMap.map { it.key.substringBefore(".") }.distinct().take(20)
        
        sb.appendLine("## 프로젝트 구조 ($totalFiles 개 파일)")
        sb.appendLine("모듈: ${modules.joinToString(", ")}")
        return sb.toString().trimEnd()
    }

    private fun estimateTokens(text: String): Int {
        return (text.length * 0.7).toInt()
    }

    private fun truncateToTokenLimit(text: String, tokenLimit: Int): String {
        val maxChars = (tokenLimit / 0.7).toInt()
        return if (text.length > maxChars) text.substring(0, maxChars) else text
    }
}
