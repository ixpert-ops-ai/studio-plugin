package net.ib.ixpert.ops.wuwagent.agent.clarify

import net.ib.ixpert.ops.wuwagent.agent.clarify.model.ClarifyIntent
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.RetentionAudit

/** 정제문 확인 카드에서 사용자가 고른 결정. 카드가 없거나 응답이 없으면(null) 결정이 없는 것이다. */
sealed class RefinedConfirmationDecision {
    /** "이대로 분석": 보여 준 정제문을 그대로 쓴다. */
    object Proceed : RefinedConfirmationDecision()

    /** "수정한 내용으로 분석": 사용자가 고친 정제문을 쓴다. */
    data class ProceedEdited(val text: String) : RefinedConfirmationDecision()

    /** "취소": 분석을 시작하지 않는다. */
    object Cancel : RefinedConfirmationDecision()

    /** 새 /clarify나 새 확인 요청이 이 확인을 대신했다. 분석을 시작하지 않고 안내도 보내지 않는다. */
    object Superseded : RefinedConfirmationDecision()

    /** 대기 시간 안에 응답이 없었다. 정제문을 그대로 쓰고 진행한다(카드는 비활성으로 바꿔 안내한다). */
    object TimedOut : RefinedConfirmationDecision()
}

/** 분석을 시작하기 전에 정제문을 사용자에게 확인받는 중개 인터페이스(Stage3ConfirmationBridge와 같은 방식). */
fun interface RefinedRequirementConfirmationBridge {
    /**
     * @return 사용자의 결정. null이면 결정이 없는 것이다(브리지 없음, 대기 시간 초과, 전송 실패) → 정제문을 그대로 쓴다.
     */
    fun requestConfirmation(originalRequirement: String, refinedRequirement: String): RefinedConfirmationDecision?

    /** 확인 카드를 화면에서 내린다. */
    fun hideConfirmation() {}

    /** 응답 없이 시간이 지나 진행하는 경우: 카드를 내리지 않고 비활성으로 바꿔 "응답이 없어 …" 안내를 보인다. */
    fun expireConfirmation() {}
}

sealed class RefinedGateResult {
    /** 분석을 진행한다. intent는 사용자가 고쳤으면 고친 문장이 들어 있고, 아니면 받은 것과 같은 객체다. */
    data class Proceed(val intent: ClarifyIntent) : RefinedGateResult()

    /** 사용자가 취소했다. 분석을 시작하지 않는다. */
    object Cancelled : RefinedGateResult()

    /** 새 /clarify 등이 이 확인을 대신했다. 분석도 안내도 없다. */
    object Superseded : RefinedGateResult()
}

object RefinedRequirementGate {
    /** 카드에 보이는 정제문. [AnalyzeInputResolver.resolve]가 Stage 1~3의 본문으로 읽는 값과 같다. */
    fun shownRefined(intent: ClarifyIntent): String {
        val source = intent.retentionAudit?.rawRefinedRequirement ?: intent.refinedRequirement
        return source.ifBlank { intent.originalRequirement }
    }

    /**
     * 분석 시작 전 확인. bridge가 null이거나 결정이 없으면 intent를 그대로 돌려준다(지금 동작과 같다).
     * 고친 문장이 비어 있거나 보여 준 문장과 같으면 고치지 않은 것으로 본다.
     */
    fun apply(intent: ClarifyIntent, bridge: RefinedRequirementConfirmationBridge?): RefinedGateResult {
        if (bridge == null) println("[RefineGate] rid=${Integer.toHexString(intent.originalRequirement.hashCode())} 브리지 null → 카드 없이 진행")
        if (bridge == null) return RefinedGateResult.Proceed(intent)
        val shown = shownRefined(intent)
        val decision = try {
            bridge.requestConfirmation(intent.originalRequirement, shown)
        } catch (e: Exception) {
            null
        }
        println("[RefineGate] rid=${Integer.toHexString(intent.originalRequirement.hashCode())} 응답 결과: ${when (decision) { null -> "결정 없음(정제문 그대로 진행)"; RefinedConfirmationDecision.Proceed -> "이대로 분석"; is RefinedConfirmationDecision.ProceedEdited -> "수정(" + decision.text.trim().length + "자)"; RefinedConfirmationDecision.Cancel -> "취소"; RefinedConfirmationDecision.Superseded -> "새 /clarify가 대신함(분석 안 함)"; RefinedConfirmationDecision.TimedOut -> "시간 초과(정제문 그대로 진행)" }}")
        try {
            // 시간 초과로 진행하면 카드를 지우지 않고 비활성으로 바꿔 이유를 보인다. 그 밖에는 카드를 내린다.
            if (decision == RefinedConfirmationDecision.TimedOut) bridge.expireConfirmation() else bridge.hideConfirmation()
        } catch (e: Exception) {
            // 화면 정리 실패는 진행에 영향 없음
        }
        return when (decision) {
            null, RefinedConfirmationDecision.Proceed, RefinedConfirmationDecision.TimedOut -> RefinedGateResult.Proceed(intent)
            RefinedConfirmationDecision.Cancel -> RefinedGateResult.Cancelled
            RefinedConfirmationDecision.Superseded -> RefinedGateResult.Superseded
            is RefinedConfirmationDecision.ProceedEdited -> {
                val edited = decision.text.trim()
                if (edited.isEmpty() || edited == shown.trim()) RefinedGateResult.Proceed(intent)
                else RefinedGateResult.Proceed(withEditedRefined(intent, edited))
            }
        }
    }

    /**
     * 확인 → 저장 → 분석의 순서. 취소면 저장과 분석 없이 [onCancelled]만 부른다. 라우터가 쓰는 흐름이며 IDE 없이 테스트할 수 있게 동작을 인자로 받는다.
     * 저장과 분석에는 사용자가 고친 intent가 들어간다(고치지 않았으면 받은 intent 그대로).
     */
    fun handoff(
        intent: ClarifyIntent,
        bridge: RefinedRequirementConfirmationBridge?,
        save: (ClarifyIntent) -> Unit,
        analyze: (ClarifyIntent) -> Unit,
        onCancelled: () -> Unit,
        onSuperseded: () -> Unit = {}
    ) {
        when (val result = apply(intent, bridge)) {
            is RefinedGateResult.Proceed -> {
                save(result.intent)
                analyze(result.intent)
            }
            RefinedGateResult.Cancelled -> onCancelled()
            RefinedGateResult.Superseded -> onSuperseded()
        }
    }

    /**
     * 고친 문장을 정제문 필드(refinedRequirement, retentionAudit.rawRefinedRequirement)에 넣는다. 저장 형식과 contractVersion은 그대로다.
     * 사용자가 고친 문장은 그 자체가 결정이므로 사용자가 지운 식별자는 Stage 1~3 입력에 다시 붙이지 않는다(`missingBeforeFix`를 비운다.
     * [AnalyzeInputResolver.resolve]는 이 목록으로만 "빠진 식별자 줄"을 붙인다). 고치지 않은 경우는 이 함수를 거치지 않아 지금과 같다.
     */
    fun withEditedRefined(intent: ClarifyIntent, edited: String): ClarifyIntent {
        val retention = IdentifierRetentionChecker.checkRetention(
            refinedRequirement = edited,
            originalRequirement = intent.originalRequirement,
            userStatements = intent.userStatements
        )
        val audit = (intent.retentionAudit ?: RetentionAudit(rawRefinedRequirement = edited)).copy(
            rawRefinedRequirement = retention.rawRefinedRequirement,
            expectedIdentifiers = retention.expectedIdentifiers.toList().sorted(),
            missingBeforeFix = emptyList(),
            wasRetainedWithoutModification = true,
            scopeModifierDropped = retention.scopeModifierDropped
        )
        return intent.copy(refinedRequirement = retention.effectiveRefinedRequirement, retentionAudit = audit)
    }
}
