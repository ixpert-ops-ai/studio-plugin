package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

/**
 * Phase 1: Seed Selection 시 LLM이 반환하는 JSON 형태의 데이터 (또는 Mock Fixture)
 */
data class SeedSelectionResult(
    val seedClasses: List<String>,
    val changeIntent: ChangeIntent,
    val layerHint: List<String>,
    val frontendRelevant: Boolean,
    val reasoning: String,
    val frontendFileHints: List<String>? = null
)

enum class ChangeIntent {
    MODIFY, CREATE, DELETE
}

/**
 * Graph Expansion 중의 경로 추적용 데이터
 */
data class ExpansionStep(
    val hop: Int,
    val via: String, // "SEED", "DEPENDS_ON", "DEPENDED_BY", "LINKED_TO", "SAME_PACKAGE", "SERVICE_TO_REPO" 등
    val from: String? = null
)

/**
 * 최종 필터링된 파일 모델
 */
data class ScoredFile(
    val path: String,
    val className: String,
    val fileType: String,
    val layer: String,
    val score: Int,
    val discoveryReason: String,
    val hopDistance: Int,
    val fromPath: String?
)

/**
 * 신규 파일 생성 제안 모델
 */
data class NewFileProposal(
    val suggestedPath: String,
    val suggestedFileType: String,
    val reason: String,
    val referencePattern: String
)

/**
 * 디버깅/추적용 메타데이터
 */
data class DiscoveryMetadata(
    val seedClasses: List<String>,
    val changeIntent: ChangeIntent,
    val layerHint: List<String>,
    val frontendRelevant: Boolean,
    val totalCandidates: Int,
    val filteredTo: Int,
    val llmTokensUsed: Int,
    val expansionTrace: Map<String, ExpansionStep>,
    val reasoning: String = ""
)

/**
 * 파이프라인의 최종 결과
 */
data class DiscoveryResult(
    val relevantFiles: List<ScoredFile>,
    val suggestedNewFiles: List<NewFileProposal>,
    val metadata: DiscoveryMetadata
)

/**
 * File Discovery 관련 설정
 */
data class DiscoveryConfig(
    val maxHop: Int = 2,
    val infrastructurePercentile: Int = 90,
    val minInfraThreshold: Int = 3,
    val domainFilterEnabled: Boolean = true,
    val defaultFileLimit: Int = 10,
    val maxFileLimit: Int = 15,
    val minScore: Int = 55
)
