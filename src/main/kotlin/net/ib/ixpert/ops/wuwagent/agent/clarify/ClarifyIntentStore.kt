package net.ib.ixpert.ops.wuwagent.agent.clarify

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.*
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import java.io.File

/**
 * Clarification 인텐트 계약 아티팩트(.wuwagent/contracts/clarify-intent-<key>.json) 영속 저장 및 로드 스토어.
 * - 정본화된 그래프 해시(canonical graphHash)로 노드/엣지 무결성 보장
 * - 무조건 Fail-Fast 계약 검증 (버전/해시 불일치 시 ContractValidationException 발생)
 * - SR ID / 세션 키 단위 파일 격리
 */
object ClarifyIntentStore {

    const val CURRENT_INTENT_VERSION = ClarifyIntent.CURRENT_INTENT_VERSION
    const val MIN_SUPPORTED_VERSION = "1.0"
    const val MAX_SUPPORTED_MINOR = 2
    const val SUPPORTED_MAJOR = 1

    private val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .create()

    /**
     * 버전 유효성 검증 (Fail-Fast)
     */
    fun isSupportedVersion(versionStr: String): Boolean {
        val parts = versionStr.trim().split('.')
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return false
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        if (major != SUPPORTED_MAJOR) return false

        val minParts = MIN_SUPPORTED_VERSION.split('.')
        val minMinor = minParts.getOrNull(1)?.toIntOrNull() ?: 0
        return minor in minMinor..MAX_SUPPORTED_MINOR
    }

    /**
     * 인텐트 파일 경로 결정 (.wuwagent/contracts/clarify-intent-<key>.json)
     */
    fun getIntentFile(projectRoot: File, key: String = "default"): File {
        val contractsDir = File(projectRoot, ".wuwagent/contracts")
        val sanitizedKey = key.trim().replace(Regex("[^a-zA-Z0-9._-]"), "_").ifEmpty { "default" }
        return File(contractsDir, "clarify-intent-$sanitizedKey.json")
    }

    /**
     * 인텐트 파일 존재 여부 확인
     */
    fun findIntentFile(projectRoot: File, key: String = "default"): File? {
        val file = getIntentFile(projectRoot, key)
        return if (file.exists() && file.isFile) file else null
    }

    /**
     * 인텐트 아티팩트 디스크 저장
     */
    fun saveIntent(
        projectRoot: File,
        intent: ClarifyIntent,
        key: String = "default"
    ): File {
        val targetFile = getIntentFile(projectRoot, key)
        targetFile.parentFile?.mkdirs()

        // .wuwagent 디렉토리 내부 gitignore 자동 구성 (요구사항 원문 금융 저장소 유출 방지)
        val wuwDir = File(projectRoot, ".wuwagent")
        val gitignore = File(wuwDir, ".gitignore")
        if (!gitignore.exists()) {
            gitignore.writeText("*\n", Charsets.UTF_8)
        } else {
            val existingLines = gitignore.readLines(Charsets.UTF_8).map { it.trim() }
            if ("*" !in existingLines && "**" !in existingLines) {
                gitignore.appendText("\n*\n", Charsets.UTF_8)
            }
        }

        val json = gson.toJson(intent)
        targetFile.writeText(json, Charsets.UTF_8)
        return targetFile
    }

    /**
     * 인텐트 아티팩트 로드 및 무조건 Fail-Fast 검증
     */
    fun loadIntent(
        intentFile: File,
        currentGraph: ProjectGraph
    ): ClarifyIntent {
        if (!intentFile.exists() || !intentFile.isFile) {
            throw ContractValidationException(
                "인텐트 계약 아티팩트 파일을 찾을 수 없습니다: ${intentFile.absolutePath}",
                ContractValidationReason.FILE_NOT_FOUND
            )
        }

        val json = try {
            intentFile.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            throw ContractValidationException(
                "인텐트 계약 아티팩트 파일 읽기 실패: ${e.message}",
                ContractValidationReason.CORRUPT_JSON
            )
        }

        val dto = try {
            gson.fromJson(json, ClarifyIntentJson::class.java)
        } catch (e: Exception) {
            throw ContractValidationException(
                "인텐트 계약 아티팩트 JSON 파싱 실패: ${e.message}",
                ContractValidationReason.CORRUPT_JSON
            )
        } ?: throw ContractValidationException(
            "인텐트 계약 아티팩트 내용이 비어있습니다.",
            ContractValidationReason.CORRUPT_JSON
        )

        // 1. 버전 검증 (Fail-Fast)
        val versionStr = dto.contractVersion ?: throw ContractValidationException(
            "인텐트 계약 버전 필드가 누락되었습니다.",
            ContractValidationReason.CORRUPT_JSON
        )
        if (!isSupportedVersion(versionStr)) {
            throw ContractValidationException(
                "지원하지 않는 인텐트 스키마 버전입니다: '$versionStr' (지원 버전: 1.0 ~ 1.$MAX_SUPPORTED_MINOR). /clarify를 다시 실행해주세요.",
                ContractValidationReason.VERSION_MISMATCH
            )
        }

        // 2. 필수 필드 검증 (Fail-Fast)
        val originalReq = dto.originalRequirement ?: throw ContractValidationException(
            "originalRequirement 필드가 누락되었습니다.",
            ContractValidationReason.CORRUPT_JSON
        )
        val refinedReq = dto.refinedRequirement ?: throw ContractValidationException(
            "refinedRequirement 필드가 누락되었습니다.",
            ContractValidationReason.CORRUPT_JSON
        )
        val graphHash = dto.graphHash ?: throw ContractValidationException(
            "graphHash 필드가 누락되었습니다.",
            ContractValidationReason.CORRUPT_JSON
        )

        // 3. 그래프 해시 검증 (Fail-Fast)
        val currentHash = ClarificationContractStore.calculateGraphHash(currentGraph)
        if (graphHash != currentHash) {
            throw ContractValidationException(
                "그래프 무결성 불일치: 인텐트가 생성된 시점의 그래프 해시($graphHash)와 현재 프로젝트 메타그래프 해시($currentHash)가 상이합니다. 프로젝트 코드가 변경되었으므로 /clarify를 다시 실행해야 합니다.",
                ContractValidationReason.GRAPH_HASH_MISMATCH
            )
        }

        // 4. DTO -> Domain 매핑 (레거시 null 방어 포함)
        val domainConstraints = dto.constraints?.map { cDto ->
            val kind = cDto.kind?.let { kStr ->
                ConstraintKind.entries.firstOrNull { it.name == kStr }
                    ?: throw ContractValidationException("알 수 없는 ConstraintKind: $kStr", ContractValidationReason.CORRUPT_JSON)
            } ?: throw ContractValidationException("Constraint kind 필드가 누락되었습니다.", ContractValidationReason.CORRUPT_JSON)
            val rawKind = cDto.rawKind?.let { kStr ->
                ConstraintKind.entries.firstOrNull { it.name == kStr } ?: kind
            } ?: kind
            val value = cDto.value ?: throw ContractValidationException("Constraint value 필드가 누락되었습니다.", ContractValidationReason.CORRUPT_JSON)
            val rawStatement = cDto.rawStatement ?: throw ContractValidationException("Constraint rawStatement 필드가 누락되었습니다.", ContractValidationReason.CORRUPT_JSON)
            IntentConstraint(
                kind = kind,
                rawKind = rawKind,
                value = value,
                rawStatement = rawStatement,
                evidence = cDto.evidence
            )
        } ?: emptyList()

        val domainUnresolvedItems = dto.unresolvedItems?.map { uDto ->
            val identifier = uDto.identifier ?: throw ContractValidationException(
                "UnresolvedItem identifier 필드가 누락되었습니다.",
                ContractValidationReason.CORRUPT_JSON
            )
            val kindStr = uDto.kind ?: throw ContractValidationException(
                "UnresolvedItem kind 필드가 누락되었습니다.",
                ContractValidationReason.CORRUPT_JSON
            )
            val kind = UnresolvedKind.entries.firstOrNull { it.name == kindStr } ?: throw ContractValidationException(
                "알 수 없는 UnresolvedKind: $kindStr",
                ContractValidationReason.CORRUPT_JSON
            )
            val sourceStr = uDto.source ?: throw ContractValidationException(
                "UnresolvedItem source 필드가 누락되었습니다.",
                ContractValidationReason.CORRUPT_JSON
            )
            val source = HintSource.entries.firstOrNull { it.name == sourceStr } ?: throw ContractValidationException(
                "알 수 없는 HintSource: $sourceStr",
                ContractValidationReason.CORRUPT_JSON
            )
            val utteredTurn = uDto.utteredTurn ?: throw ContractValidationException(
                "UnresolvedItem utteredTurn 필드가 누락되었습니다.",
                ContractValidationReason.CORRUPT_JSON
            )

            UnresolvedItem(
                identifier = identifier,
                kind = kind,
                filePath = uDto.filePath,
                source = source,
                utteredTurn = utteredTurn,
                lastQuestion = uDto.lastQuestion,
                lastAskedTurn = uDto.lastAskedTurn
            )
        } ?: emptyList()

        val domainRetentionAudit = dto.retentionAudit?.let { aDto ->
            RetentionAudit(
                rawRefinedRequirement = aDto.rawRefinedRequirement ?: "",
                expectedIdentifiers = aDto.expectedIdentifiers ?: emptyList(),
                missingBeforeFix = aDto.missingBeforeFix ?: emptyList(),
                auxiliaryIdentifiers = aDto.auxiliaryIdentifiers ?: emptyList(),
                wasRetainedWithoutModification = aDto.wasRetainedWithoutModification ?: false,
                scopeModifierDropped = aDto.scopeModifierDropped ?: false
            )
        }

        return ClarifyIntent(
            originalRequirement = originalReq,
            refinedRequirement = refinedReq,
            userStatements = dto.userStatements ?: emptyList(),
            anchorTokens = dto.anchorTokens ?: emptyList(),
            constraints = domainConstraints,
            excludedFiles = dto.excludedFiles ?: emptyList(),
            graphHash = graphHash,
            retentionAudit = domainRetentionAudit,
            unresolvedItems = domainUnresolvedItems,
            contractVersion = versionStr
        )
    }

    /**
     * 키 기반 로드 편의 함수 (파일 부존재 시 null, 불일치 시 Fail-Fast 예외)
     */
    fun loadIntentByKey(
        projectRoot: File,
        currentGraph: ProjectGraph,
        key: String = "default"
    ): ClarifyIntent? {
        val file = findIntentFile(projectRoot, key) ?: return null
        return loadIntent(file, currentGraph)
    }

    /**
     * 인텐트 파일 삭제 (세션 정리용)
     */
    fun deleteIntent(projectRoot: File, key: String = "default"): Boolean {
        val file = getIntentFile(projectRoot, key)
        return if (file.exists()) file.delete() else false
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// DTO 클래스 정의 (Gson 직렬화/역직렬화 및 널 안정성 분리)
// ─────────────────────────────────────────────────────────────────────────────

internal data class ClarifyIntentJson(
    val originalRequirement: String? = null,
    val refinedRequirement: String? = null,
    val userStatements: List<String>? = null,
    val anchorTokens: List<String>? = null,
    val constraints: List<IntentConstraintJson>? = null,
    val excludedFiles: List<String>? = null,
    val graphHash: String? = null,
    val retentionAudit: RetentionAuditJson? = null,
    val unresolvedItems: List<UnresolvedItemJson>? = null,
    val contractVersion: String? = null
)

internal data class IntentConstraintJson(
    val kind: String? = null,
    val rawKind: String? = null,
    val value: String? = null,
    val rawStatement: String? = null,
    val evidence: String? = null
)

internal data class UnresolvedItemJson(
    val identifier: String? = null,
    val kind: String? = null,
    val filePath: String? = null,
    val source: String? = null,
    val utteredTurn: Int? = null,
    val lastQuestion: String? = null,
    val lastAskedTurn: Int? = null
)

internal data class RetentionAuditJson(
    val rawRefinedRequirement: String? = null,
    val expectedIdentifiers: List<String>? = null,
    val missingBeforeFix: List<String>? = null,
    val auxiliaryIdentifiers: List<String>? = null,
    val wasRetainedWithoutModification: Boolean? = null,
    val scopeModifierDropped: Boolean? = null
)
