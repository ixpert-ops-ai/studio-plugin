package net.ib.ixpert.ops.wuwagent.agent

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ResourceType
import java.io.File

/**
 * 결과 표의 필수/선택 표시(B-83 5). 결정적 규칙으로만 판정하고, 판정하지 못한 파일은 표시를 붙이지 않는다(LLM 사유만 보인다).
 * - 선택(경로 통과): 최종 목록의 DTO를 기존 Dao 메서드가 그대로 받거나 반환하면 그 Dao 인터페이스와 구현체는 시그니처가 바뀌지 않는 한 수정할 필요가 없다.
 * - 필수: 매퍼 resultType/parameterType이 그 DTO이면 매퍼(SELECT 컬럼 추가)와 그 DTO(필드 추가), SR이 값을 가공(마스킹 등)하면 ServiceImpl(가공 위치).
 * - SR이 새 조회·저장 메서드를 요구하면 선택 판정을 적용하지 않는다(Dao 메서드 선언이 실제로 필요하다).
 */
object PathRoles {
    enum class Role(val label: String) { REQUIRED("필수"), OPTIONAL("선택(경로 통과)") }
    data class Hint(val role: Role, val reason: String)

    private val NEW_METHOD = Regex("(신규|새로운|새)\\s*(조회|저장|메서드|쿼리|API|엔드포인트)|메서드를?\\s*(추가|신설)|조회\\s*기능을?\\s*(추가|신설)")
    private val PROCESSING = Regex("마스킹|가공|변환|계산|조합|암호화|복호화")

    fun requestsNewMethod(srText: String): Boolean = NEW_METHOD.containsMatchIn(srText)

    /** finalPaths: 결과 표의 파일 경로. 반환: 경로 → 힌트(판정된 파일만). */
    fun classify(graph: ProjectGraph, projectRoot: File?, finalPaths: List<String>, srText: String): Map<String, Hint> {
        val out = linkedMapOf<String, Hint>()
        val nodes = finalPaths.mapNotNull { p -> graph.files[p]?.let { p to it } }
        val dtos = nodes.filter { (_, n) -> !n.isInterface && Regex("(Dto|VO|Vo)$").containsMatchIn(n.className) }.map { it.second }
        if (dtos.isEmpty()) return out

        // 필수: 매퍼 resultType/parameterType이 최종 목록의 DTO
        val requiredDtos = mutableSetOf<String>()
        if (projectRoot != null) {
            for (p in finalPaths) {
                val res = graph.resourceNodes.firstOrNull { it.path == p && it.type == ResourceType.MYBATIS_MAPPER } ?: continue
                val f = File(projectRoot, p).takeIf { it.isFile } ?: continue
                val text = f.readText(Charsets.UTF_8)
                val used = dtos.filter { d -> Regex("(resultType|parameterType)=\"[^\"]*\\b${Regex.escape(d.className)}\"").containsMatchIn(text) }
                if (used.isNotEmpty()) {
                    out[p] = Hint(Role.REQUIRED, "매퍼 resultType/parameterType이 ${used.joinToString(", ") { it.className }}라서 응답 항목 추가는 SELECT 컬럼 추가가 필요합니다")
                    requiredDtos += used.map { it.className }
                }
            }
        }
        for ((p, n) in nodes) if (n.className in requiredDtos) out[p] = Hint(Role.REQUIRED, "매퍼가 ${n.className}로 결과를 받으므로 응답 항목은 이 DTO의 필드로 추가됩니다")

        // 필수: 값을 가공하는 SR이면 ServiceImpl
        if (PROCESSING.containsMatchIn(srText)) {
            for ((p, n) in nodes) if (!n.isInterface && Regex("Service(Impl)?$|SVCImpl$").containsMatchIn(n.className)) {
                out[p] = Hint(Role.REQUIRED, "SR이 값을 가공(${PROCESSING.find(srText)!!.value})하므로 그 처리가 들어갈 비즈니스 로직 위치입니다")
            }
        }

        // 선택: 기존 Dao 메서드가 DTO를 그대로 받거나 반환(경로 통과). SR이 새 조회·저장 메서드를 요구하면 적용하지 않는다.
        if (!requestsNewMethod(srText)) {
            for ((p, n) in nodes) {
                if (!Regex("(Dao|DaoImpl|Mapper|Repository)$").containsMatchIn(n.className)) continue
                val iface = if (n.isInterface) n else graph.files.values.firstOrNull { it.isInterface && (it.className in n.implementedInterfaces.map { i -> i.substringAfterLast('.') } || it.className == n.className.removeSuffix("Impl")) } ?: continue
                val pass = iface.methods.filter { m -> dtos.any { d -> m.parameters.any { it.contains(d.className) } || m.returnType.contains(d.className) } }
                if (pass.isNotEmpty()) {
                    out[p] = Hint(Role.OPTIONAL, "기존 메서드(${pass.take(2).joinToString(", ") { it.name }} 등)가 ${dtos.first().className}를 그대로 받거나 반환합니다. 시그니처가 바뀌지 않으면 수정할 필요가 없습니다")
                }
            }
        }
        return out
    }

    /** 표의 수정 사유: 결정적 사유를 먼저, LLM 사유를 뒤에 둔다. 힌트가 없으면 LLM 사유 그대로. */
    fun describe(hint: Hint?, llmReason: String): String =
        if (hint == null) llmReason else "**${hint.role.label}** · ${hint.reason}" + if (llmReason.isBlank()) "" else " / LLM 사유: $llmReason"
}
