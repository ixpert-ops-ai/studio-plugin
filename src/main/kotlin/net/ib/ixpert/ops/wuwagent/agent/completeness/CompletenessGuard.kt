package net.ib.ixpert.ops.wuwagent.agent.completeness

import net.ib.ixpert.ops.wuwagent.agent.completeness.model.CompletenessReport

sealed class GuardOutcome {
    object Pass : GuardOutcome()
    data class Block(val report: CompletenessReport, val messages: List<String>) : GuardOutcome()
    data class Warn(val report: CompletenessReport, val messages: List<String>) : GuardOutcome()
}

object CompletenessGuard {
    fun check(report: CompletenessReport, overrides: Set<String> = emptySet()): GuardOutcome {
        val msgs = mutableListOf<String>()

        report.roleViolations.forEach { msgs += "[역할 누락/${it.role}] ${it.suggestion}" }

        report.companionViolations
            .filterNot { overrides.contains(it.overrideKey()) }
            .forEach {
                val action = if (it.isNewFileNeeded)
                    "신규 생성 대상으로 REQUIRED에 추가" else "기존 파일을 REQUIRED에 추가"
                msgs += "[필수 동반 누락] ${it.anchorPath} 의 ${it.companionKind} 를 ${action}하세요. " +
                        "(${it.result.note})"
            }

        if (msgs.isEmpty()) {
            return if (report.unclassifiedFiles.isNotEmpty()) {
                val warnMsgs = listOf("[경고] 메타그래프 미분류 파일 존재로 완결성 검사가 일부 제한되었습니다: ${report.unclassifiedFiles}")
                GuardOutcome.Warn(report, warnMsgs)
            } else {
                GuardOutcome.Pass
            }
        }
        
        return GuardOutcome.Block(report, msgs)
    }
}
