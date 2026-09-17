package net.ib.ixpert.ops.wuwagent.agent.clarify

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.ClarifyIntent
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import java.io.File

/**
 * Clarification 인텐트 계약 아티팩트(.wuwagent/contracts/clarify-intent-<key>.json) 영속 저장 및 로드 스토어.
 * - 정본화된 그래프 해시(canonical graphHash)로 노드/엣지 무결성 보장
 * - 무조건 Fail-Fast 계약 검증 (버전/해시 불일치 시 ContractValidationException 발생)
 * - SR ID / 세션 키 단위 파일 격리
 */
object ClarifyIntentStore {

    const val CURRENT_INTENT_VERSION = "1.0"
    const val MIN_SUPPORTED_VERSION = "1.0"
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
        return minor >= minMinor
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

        val intent = try {
            gson.fromJson(json, ClarifyIntent::class.java)
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
        if (!isSupportedVersion(intent.contractVersion)) {
            throw ContractValidationException(
                "지원하지 않는 인텐트 스키마 버전입니다: '${intent.contractVersion}' (지원 버전: 1.x). /clarify를 다시 실행해주세요.",
                ContractValidationReason.VERSION_MISMATCH
            )
        }

        // 2. 그래프 해시 검증 (Fail-Fast)
        val currentHash = ClarificationContractStore.calculateGraphHash(currentGraph)
        if (intent.graphHash != currentHash) {
            throw ContractValidationException(
                "그래프 무결성 불일치: 인텐트가 생성된 시점의 그래프 해시(${intent.graphHash})와 현재 프로젝트 메타그래프 해시($currentHash)가 상이합니다. 프로젝트 코드가 변경되었으므로 /clarify를 다시 실행해야 합니다.",
                ContractValidationReason.GRAPH_HASH_MISMATCH
            )
        }

        return intent
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
