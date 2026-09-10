package net.ib.ixpert.ops.wuwagent.agent.clarify

import com.google.gson.*
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.LinkHint
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.Stage0TransitionContract
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import java.io.File
import java.lang.reflect.Type
import java.security.MessageDigest

/**
 * 전이 계약 아티팩트 유효성 검증 실패 사유
 */
enum class ContractValidationReason {
    VERSION_MISMATCH,
    GRAPH_HASH_MISMATCH,
    FILE_NOT_FOUND,
    CORRUPT_JSON
}

/**
 * 전이 계약 아티팩트 유효성 검증 실패 예외 (Fail-Fast 전용)
 */
class ContractValidationException(
    message: String,
    val reason: ContractValidationReason
) : RuntimeException(message)

/**
 * LinkHint sealed class 다형성 Gson 어댑터
 */
private class LinkHintTypeAdapter : JsonSerializer<LinkHint>, JsonDeserializer<LinkHint> {
    override fun serialize(src: LinkHint, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
        val obj = JsonObject()
        when (src) {
            is LinkHint.ExistingRef -> {
                obj.addProperty("type", "EXISTING_REF")
                obj.addProperty("filePath", src.filePath)
                val symbolsArr = JsonArray()
                src.symbols.forEach { symbolsArr.add(it) }
                obj.add("symbols", symbolsArr)
            }
            is LinkHint.NewCreation -> {
                obj.addProperty("type", "NEW_CREATION")
            }
        }
        return obj
    }

    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): LinkHint {
        val obj = json.asJsonObject
        val type = obj.get("type")?.asString ?: if (obj.has("filePath")) "EXISTING_REF" else "NEW_CREATION"
        return when (type) {
            "EXISTING_REF" -> {
                val filePath = obj.get("filePath")?.asString ?: ""
                val symbols = obj.getAsJsonArray("symbols")?.map { it.asString } ?: emptyList()
                LinkHint.ExistingRef(filePath, symbols)
            }
            "NEW_CREATION" -> LinkHint.NewCreation
            else -> LinkHint.NewCreation
        }
    }
}

/**
 * Clarification 전이 계약 아티팩트(.wuwagent/contracts/clarification-contract-<key>.json) 영속 저장 및 로드 스토어.
 * - 정본화된 그래프 해시(canonical graphHash)로 노드/엣지 순서 무관 해시 안정성 보장
 * - 플래그 없는 무조건 Fail-Fast 계약 검증
 * - SR ID / 세션 키 단위 파일 격리
 */
object ClarificationContractStore {

    private val gson: Gson = GsonBuilder()
        .registerTypeHierarchyAdapter(LinkHint::class.java, LinkHintTypeAdapter())
        .setPrettyPrinting()
        .create()

    /**
     * 메타그래프의 정본화(Canonical) SHA-256 해시 계산.
     * 파일 노드, 리소스 노드, 릴레이션십을 정렬(sorted)한 후 직렬화하여 해시를 계산하므로
     * 그래프 내부의 컬렉션 순서나 셔플 여부와 무관하게 동일한 위상 구조에 대해 항상 100% 동일한 해시를 보장합니다.
     */
    fun calculateGraphHash(graph: ProjectGraph): String {
        val sortedNodes = graph.files.values.map { 
            "${it.path}:${it.className}" 
        }.sorted()

        val sortedResources = graph.resourceNodes.map { 
            "${it.path}:${it.type}" 
        }.sorted()

        val sortedRels = graph.relationships.map { 
            "${it.source}->${it.target}:${it.type}" 
        }.sorted()

        val canonicalRepresentation = buildString {
            append("FW:").append(graph.frameworkType.name).append("|")
            append("NODES:").append(sortedNodes.joinToString(";")).append("|")
            append("RESOURCES:").append(sortedResources.joinToString(";")).append("|")
            append("RELS:").append(sortedRels.joinToString(";"))
        }

        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(canonicalRepresentation.toByteArray(Charsets.UTF_8))
        return digest.fold("") { str, it -> str + "%02x".format(it) }
    }

    /**
     * 계약 파일 경로 결정 (.wuwagent/contracts/clarification-contract-<key>.json)
     */
    fun getContractFile(projectRoot: File, key: String = "default"): File {
        val contractsDir = File(projectRoot, ".wuwagent/contracts")
        val sanitizedKey = key.trim().replace(Regex("[^a-zA-Z0-9._-]"), "_").ifEmpty { "default" }
        return File(contractsDir, "clarification-contract-$sanitizedKey.json")
    }

    /**
     * 계약 파일 존재 여부 확인
     */
    fun findContractFile(projectRoot: File, key: String = "default"): File? {
        val file = getContractFile(projectRoot, key)
        return if (file.exists() && file.isFile) file else null
    }

    /**
     * 계약 아티팩트 디스크 저장
     */
    fun saveContract(
        projectRoot: File,
        contract: Stage0TransitionContract,
        key: String = "default"
    ): File {
        val targetFile = getContractFile(projectRoot, key)
        targetFile.parentFile?.mkdirs()

        // .wuwagent 디렉토리 내부 gitignore 자동 구성 (요구사항 원문/계약 금융 저장소 유출 방지)
        val wuwDir = File(projectRoot, ".wuwagent")
        val gitignore = File(wuwDir, ".gitignore")
        if (!gitignore.exists()) {
            gitignore.writeText("*\n", Charsets.UTF_8)
        }

        val json = gson.toJson(contract)
        targetFile.writeText(json, Charsets.UTF_8)
        return targetFile
    }

    /**
     * 계약 아티팩트 로드 및 무조건 Fail-Fast 검증.
     * - 플래그(strictGraphValidation 등)를 일체 두지 않고, 버전/해시 불일치 시 즉시 ContractValidationException 발생.
     */
    fun loadContract(
        contractFile: File,
        currentGraph: ProjectGraph
    ): Stage0TransitionContract {
        if (!contractFile.exists() || !contractFile.isFile) {
            throw ContractValidationException(
                "계약 아티팩트 파일을 찾을 수 없습니다: ${contractFile.absolutePath}",
                ContractValidationReason.FILE_NOT_FOUND
            )
        }

        val json = try {
            contractFile.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            throw ContractValidationException(
                "계약 아티팩트 파일 읽기 실패: ${e.message}",
                ContractValidationReason.CORRUPT_JSON
            )
        }

        val contract = try {
            gson.fromJson(json, Stage0TransitionContract::class.java)
        } catch (e: Exception) {
            throw ContractValidationException(
                "계약 아티팩트 JSON 파싱 실패: ${e.message}",
                ContractValidationReason.CORRUPT_JSON
            )
        } ?: throw ContractValidationException(
            "계약 아티팩트 내용이 비어있습니다.",
            ContractValidationReason.CORRUPT_JSON
        )

        // 1. 버전 검증 (Fail-Fast)
        if (contract.contractVersion != "1.0") {
            throw ContractValidationException(
                "지원하지 않는 계약 스키마 버전입니다: '${contract.contractVersion}' (기대 버전: '1.0'). /clarify를 다시 실행해주세요.",
                ContractValidationReason.VERSION_MISMATCH
            )
        }

        // 2. 그래프 해시 검증 (Fail-Fast)
        val currentHash = calculateGraphHash(currentGraph)
        if (contract.graphHash != currentHash) {
            throw ContractValidationException(
                "그래프 무결성 불일치: 계약이 생성된 시점의 그래프 해시(${contract.graphHash})와 현재 프로젝트 메타그래프 해시($currentHash)가 상이합니다. 프로젝트 코드가 변경되었으므로 /clarify를 다시 실행하여 요구사항을 재검증해야 합니다.",
                ContractValidationReason.GRAPH_HASH_MISMATCH
            )
        }

        return contract
    }

    /**
     * 키 기반 로드 편의 함수 (파일 부존재 시 null, 불일치 시 Fail-Fast 예외)
     */
    fun loadContractByKey(
        projectRoot: File,
        currentGraph: ProjectGraph,
        key: String = "default"
    ): Stage0TransitionContract? {
        val file = findContractFile(projectRoot, key) ?: return null
        return loadContract(file, currentGraph)
    }
}
