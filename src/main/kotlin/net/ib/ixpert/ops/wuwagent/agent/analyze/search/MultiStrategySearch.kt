package net.ib.ixpert.ops.wuwagent.agent.analyze.search

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FileNode
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph

class MultiStrategySearch(
    private val metaGraph: ProjectGraph,
    private val frameworkType: FrameworkType
) {
    data class SearchHit(
        val fileNode: FileNode,
        val matchType: MatchType,
        val matchedTerm: String,
        val score: Double
    )

    enum class MatchType { FILENAME, CLASSNAME, METHOD, CONTENT }

    fun search(keywords: KeywordDecomposer.Keywords): List<SearchHit> {
        val hits = mutableListOf<SearchHit>()

        hits += searchByFileName(keywords)
        hits += searchByClassName(keywords)
        hits += searchByMethodName(keywords)
        hits += searchByContent(keywords)

        // 동일 파일 합산 후 상위 15개 반환
        return hits.groupBy { it.fileNode.path }
            .map { (_, group) -> mergeHits(group) }
            .sortedByDescending { it.score }
            .take(15)
    }

    private fun searchByFileName(keywords: KeywordDecomposer.Keywords): List<SearchHit> {
        return keywords.matchedFileNames.flatMap { fileName ->
            metaGraph.files.values.filter { it.path.substringAfterLast("/") == fileName }.map { node ->
                SearchHit(node, MatchType.FILENAME, fileName, baseScore(MatchType.FILENAME))
            }
        }
    }

    private fun searchByClassName(keywords: KeywordDecomposer.Keywords): List<SearchHit> {
        return keywords.matchedClassNames.flatMap { className ->
            metaGraph.files.values.filter { it.className == className }.map { node ->
                SearchHit(node, MatchType.CLASSNAME, className, baseScore(MatchType.CLASSNAME))
            }
        }
    }

    private fun searchByMethodName(keywords: KeywordDecomposer.Keywords): List<SearchHit> {
        return keywords.matchedMethodNames.flatMap { methodName ->
            metaGraph.files.values.filter { it.methodNames.contains(methodName) }.map { node ->
                SearchHit(node, MatchType.METHOD, methodName, baseScore(MatchType.METHOD))
            }
        }
    }

    private fun searchByContent(keywords: KeywordDecomposer.Keywords): List<SearchHit> {
        // 메타그래프의 localName, koreanComments만 검색하는 가벼운 인메모리 방식
        return keywords.contentTokens.filter { it.length >= 2 }.flatMap { token ->
            metaGraph.files.values.filter { node ->
                val inLocalName = node.localName?.contains(token, ignoreCase = true) == true
                val inComments = node.koreanComments.any { it.contains(token, ignoreCase = true) }
                inLocalName || inComments
            }.map { node ->
                SearchHit(node, MatchType.CONTENT, token, baseScore(MatchType.CONTENT))
            }
        }
    }

    /**
     * 프레임워크 타입별 매칭 가중치
     */
    private fun baseScore(type: MatchType): Double {
        return when (frameworkType) {
            FrameworkType.ANYFRAME_AP, FrameworkType.ANYFRAME_JAP -> when (type) {
                MatchType.FILENAME -> 80.0
                MatchType.CLASSNAME -> 100.0
                MatchType.METHOD -> 60.0
                MatchType.CONTENT -> 30.0
            }
            FrameworkType.SPRING_BOOT_JPA -> when (type) {
                MatchType.FILENAME -> 90.0
                MatchType.CLASSNAME -> 90.0
                MatchType.METHOD -> 80.0
                MatchType.CONTENT -> 50.0
            }
            FrameworkType.SPRING_BOOT_MYBATIS -> when (type) {
                MatchType.FILENAME -> 90.0
                MatchType.CLASSNAME -> 85.0
                MatchType.METHOD -> 75.0
                MatchType.CONTENT -> 40.0
            }
            FrameworkType.SPRING_BOOT_JDBC -> when (type) {
                MatchType.FILENAME -> 90.0
                MatchType.CLASSNAME -> 85.0
                MatchType.METHOD -> 80.0
                MatchType.CONTENT -> 40.0
            }
            FrameworkType.SPRING_MVC_MYBATIS -> when (type) {
                MatchType.FILENAME -> 85.0
                MatchType.CLASSNAME -> 95.0
                MatchType.METHOD -> 70.0
                MatchType.CONTENT -> 35.0
            }
            FrameworkType.CUSTOM, FrameworkType.SPRING_BOOT, FrameworkType.ANYFRAME, FrameworkType.ANYFRAME_JAP -> when (type) {
                MatchType.FILENAME -> 90.0
                MatchType.CLASSNAME -> 90.0
                MatchType.METHOD -> 70.0
                MatchType.CONTENT -> 50.0
            }
        }
    }

    private fun mergeHits(hits: List<SearchHit>): SearchHit {
        val best = hits.maxByOrNull { it.score }!!
        return best.copy(score = hits.sumOf { it.score })
    }
}
