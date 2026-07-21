package net.ib.ixpert.ops.wuwagent.agent.completeness

import net.ib.ixpert.ops.wuwagent.agent.completeness.model.*
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType

object PromptRenderer {

    // ── A. 사전 렌더링: 검증자에게 주입할 프레임워크 프로파일 ──
    fun renderVerifierProfile(
        frameworkType: FrameworkType,
        ctx: GraphMatchContext
    ): String = buildString {
        val resolution = FrameworkRulesetRegistry.resolve(frameworkType, ctx)
        val rs = resolution.ruleset

        appendLine("## 프레임워크 프로파일 (${frameworkType.displayName}, 자동 생성 — 수정 금지)")
        appendLine()

        // 진입점 규칙
        val ep = rs.entrypointRule
        append("- 진입점: 사용자 액션이 있으면 ${kindLabel(ep.realizationKind)} 가 ")
        appendLine("REQUIRED 집합에 최소 1개 필요합니다.")
        if (ep.creationPolicy == CreationPolicy.PREFER_MODIFY_EXISTING)
            appendLine("  · 신규 생성보다 기존 ${kindLabel(ep.realizationKind)} 에 메서드 추가를 우선하세요.")
        else
            appendLine("  · 신규 ${kindLabel(ep.realizationKind)} 생성이 허용됩니다.")

        // 동반(세트) 규칙 — MANDATORY/RECOMMENDED + 트리거 함께 표기
        val companions = rs.roleRealizations.flatMap { rr -> rr.companions.map { rr to it } }
        if (companions.isNotEmpty()) {
            appendLine("- 세트 규칙 (앵커 수정 시 함께 REQUIRED 여부):")
            companions.forEach { (rr, c) ->
                val strength = if (c.pairing == PairingStrength.MANDATORY) "필수" else "권장"
                val trigger = when (c.trigger) {
                    CompanionTrigger.ON_NEW_METHOD -> "새 메서드 추가 시"
                    CompanionTrigger.ON_ANY_CHANGE -> "수정 시 항상"
                }
                appendLine("  · [$strength] ${kindLabel(rr.anchorKind)} $trigger → " +
                           "${kindLabel(c.kind)} 도 함께 포함")
            }
        }

        // 역할 필수성 안내 (RoleNecessity는 SR 기반이지만, 어떤 역할이 강제되는지 미리 고지)
        appendLine("- 이 작업이 데이터 조회/저장을 포함하면 영속성 역할 파일이, " +
                   "사용자 액션을 포함하면 진입점 파일이 반드시 REQUIRED 집합에 있어야 합니다.")

        // 강등 고지: 정밀도가 낮은 매칭이 있으면 LLM에게 보수적 판단 요청
        if (resolution.degraded.isNotEmpty()) {
            appendLine()
            appendLine("- ⚠️ 정밀도 주의 (메타그래프 능력 부족으로 일부 매칭이 휴리스틱입니다):")
            resolution.degraded.forEach {
                appendLine("  · ${roleLabel(it.role)}/${kindLabel(it.companionKind)}: " +
                           "정밀 매칭 불가(${it.missing.joinToString()}) → 누락 의심 시 보수적으로 REQUIRED 처리")
            }
        }
    }

    // ── B. 사후 피드백: 가드 차단 시 재판정 지시 ──
    fun renderViolationFeedback(block: GuardOutcome.Block): String = buildString {
        appendLine("## ⚠️ 직전 판정이 완결성 검사를 통과하지 못했습니다. 아래를 반영해 재판정하세요.")
        appendLine()
        block.messages.forEach { appendLine("- $it") }
        appendLine()
        appendLine("위 항목을 REQUIRED 집합에 반영하거나, 정말 수정이 불필요하다면 각 파일에 대해 " +
                   "구체적 사유를 명시하세요. 사유 없이 누락하면 다시 차단됩니다.")
    }

    // ── 라벨 매핑: FileKind/ArchRole → 사람이 읽는 한국어 ──
    private fun kindLabel(k: FileKind): String = when (k) {
        FileKind.DAO_INTERFACE -> "DAO 인터페이스"
        FileKind.DAO_IMPL -> "DAO 구현체(Impl)"
        FileKind.MYBATIS_XML -> "MyBatis XML 매퍼"
        FileKind.SERVICE_INTERFACE -> "Service 인터페이스"
        FileKind.SERVICE_IMPL -> "Service 구현체(Impl)"
        FileKind.CONTROLLER -> "Controller"
        FileKind.JPA_REPOSITORY -> "JPA Repository"
        FileKind.ENTITY -> "Entity"
        FileKind.RESPONSE_DTO -> "Response DTO"
        FileKind.JSP_VIEW -> "JSP 화면"
        FileKind.JS_SCRIPT -> "JS 스크립트"
        else -> k.name
    }
    private fun roleLabel(r: ArchRole): String = when (r) {
        ArchRole.ENTRYPOINT -> "진입점"; ArchRole.BUSINESS -> "비즈니스"
        ArchRole.PERSISTENCE -> "영속성"; ArchRole.VIEW -> "화면"; ArchRole.DATA -> "데이터"
    }
}
