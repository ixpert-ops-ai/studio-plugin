package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ArchitectureLayer
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FileNode
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.SpringFileType

/**
 * 메타그래프 기반 도메인 자동 추출기.
 * 하드코딩 없이 ENTITY와 REST_CONTROLLER 앵커 및 패키지 구조만으로 도메인을 식별한다.
 *
 * 프로젝트가 바뀌어도 코드 수정 불필요 — 메타그래프만 있으면 동작.
 */
class DomainExtractor(
    private val javaNodes: Map<String, FileNode>
) {

    private val domainSegments: Set<String> by lazy { identifyDomainSegments() }
    private val domainMap: Map<String, String?> by lazy { buildDomainMap() }

    /**
     * 파일 경로에 대한 도메인을 반환한다.
     * null이면 COMMON(어디에도 속하지 않음).
     */
    fun getDomain(filePath: String): String? = domainMap[filePath]

    /**
     * 프로젝트에서 탐지된 전체 도메인 목록.
     */
    fun getAllDomains(): Set<String> = domainSegments

    companion object {
        private val STRUCTURAL_LAYERS = setOf(
            "api", "domain", "service", "repository", "controller", "infra",
            "infrastructure", "web", "ui", "common", "config", "security",
            "batch", "impl", "entity", "dto", "vo", "mapper", "exception"
        )
    }

    /**
     * Step 1: ENTITY와 REST_CONTROLLER를 도메인 앵커로 수집하고,
     *         패키지 공통 prefix 이후의 첫 세그먼트를 도메인으로 추출.
     */
    private fun identifyDomainSegments(): Set<String> {
        // 앵커 후보: ENTITY (1차), REST_CONTROLLER (2차 보강)
        val anchors = javaNodes.values.filter {
            it.fileType == SpringFileType.ENTITY || it.fileType == SpringFileType.REST_CONTROLLER
        }

        if (anchors.isEmpty()) return emptySet()

        // 전체 앵커 기준으로 공통 prefix 산출 (예: com.membermarket)
        val allPackages = anchors.mapNotNull { it.packageName }
        val commonPrefix = findCommonPackagePrefix(allPackages)

        val segments = mutableSetOf<String>()
        for (anchor in anchors) {
            val packageName = anchor.packageName ?: continue
            val suffix = packageName
                .removePrefix(commonPrefix)
                .removePrefix(".")
            
            // 공통 prefix 이후의 패키지 경로에서 구조적 레이어명(api, domain 등)을 제외한 첫 번째 세그먼트가 도메인
            val domainName = suffix.split(".").firstOrNull { it !in STRUCTURAL_LAYERS }
            if (!domainName.isNullOrBlank()) {
                segments.add(domainName)
            }
        }

        return segments
    }

    /**
     * Step 2: 모든 파일의 패키지 세그먼트를 도메인 목록과 대조하여 할당.
     */
    private fun buildDomainMap(): Map<String, String?> {
        val result = mutableMapOf<String, String?>()

        for ((path, node) in javaNodes) {
            result[path] = assignDomain(node)
        }

        return result
    }

    private fun assignDomain(node: FileNode): String? {
        // 메타그래프 태깅 기반: COMMON이면 무조건 null
        if (node.layer == ArchitectureLayer.COMMON) return null
        if (node.fileType == SpringFileType.CONFIG) return null

        // 패키지 세그먼트에서 도메인 매칭
        val packageName = node.packageName
        if (packageName != null) {
            val packageSegments = packageName.split(".")
            for (segment in packageSegments) {
                if (segment in domainSegments) {
                    return segment
                }
            }
        }

        return null  // 어디에도 매칭되지 않음 → COMMON 취급
    }

    /**
     * 패키지 목록의 최장 공통 prefix 계산.
     *
     * 예시 입력: [com.membermarket.domain.product, com.membermarket.domain.member]
     * 결과: com.membermarket.domain
     */
    private fun findCommonPackagePrefix(packages: List<String>): String {
        if (packages.isEmpty()) return ""
        val split = packages.map { it.split(".") }
        val minLength = split.minOf { it.size }
        val common = mutableListOf<String>()
        for (i in 0 until minLength) {
            val segment = split[0][i]
            if (split.all { it[i] == segment }) {
                common.add(segment)
            } else break
        }
        return common.joinToString(".")
    }
}

/**
 * 공통 인프라 판별 임계값을 프로젝트 통계에서 자동 계산한다.
 * 고정값 대신 상위 N 퍼센타일 기반으로 결정.
 *
 * @param percentile 상위 몇 %를 인프라로 볼지 (기본 90)
 * @param minThreshold 최소 임계값 (너무 낮아지는 것 방지)
 */
fun calculateInfrastructureThreshold(
    nodes: Collection<FileNode>,
    percentile: Int = 90,
    minThreshold: Int = 3
): Int {
    val dependedByCounts = nodes
        .map { it.dependedBy.size }
        .filter { it > 0 }
        .sorted()

    if (dependedByCounts.isEmpty()) return minThreshold

    val percentileIndex = (dependedByCounts.size * percentile / 100).coerceAtMost(dependedByCounts.size - 1)
    val calculated = dependedByCounts[percentileIndex]

    return maxOf(calculated, minThreshold)
}
