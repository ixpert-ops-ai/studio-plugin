package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*

/**
 * ImpactResult를 Markdown 형식으로 변환하는 포매터.
 */
object ImpactGraphFormatter {

    /**
     * 분석 결과를 아름다운 Markdown 리포트로 변환합니다.
     */
    fun format(result: ImpactResult): String {
        return buildString {
            appendLine("## 🔍 변경 영향도 분석 리포트 (MetaGraph)")
            appendLine()
            appendLine("- **대상 파일:** `${result.targetFile}`")
            appendLine("- **파급 규모:** 총 **${result.totalAffectedFiles}개** 파일에 파급 효과 예상")
            appendLine("- **영향 체인 내 최대 위험도:** ${formatRiskBadge(result.maxRiskInChain.changeRisk)} (${result.maxRiskInChain.riskScore}점)")
            appendLine()
            
            appendLine("### 1. 직접 영향 (Direct Impact - Depth 1)")
            if (result.directImpact.isEmpty()) {
                appendLine("*직접적인 영향이 감지되지 않았습니다.*")
            } else {
                appendLine("| 파일명 | 관계 유형 | 위험도 |")
                appendLine("| :--- | :--- | :--- |")
                result.directImpact.forEach { node ->
                    appendLine("| `${node.className}` | `${node.relationChain.last()}` | ${formatRiskBadge(node.riskAssessment.changeRisk)} |")
                }
            }
            appendLine()

            appendLine("### 2. 간접 영향 (Indirect Impact - Depth 2~3)")
            if (result.indirectImpact.isEmpty()) {
                appendLine("*간접적인 영향이 감지되지 않았습니다.*")
            } else {
                appendLine("| Depth | 파일명 | 전파 경로 | 위험도 |")
                appendLine("| :--- | :--- | :--- | :--- |")
                result.indirectImpact.sortedBy { it.depth }.forEach { node ->
                    appendLine("| ${node.depth} | `${node.className}` | `${node.relationChain.joinToString(" → ")}` | ${formatRiskBadge(node.riskAssessment.changeRisk)} |")
                }
            }
            appendLine()
            appendLine("---\n*💡 MetaGraph 분석은 파일 단위의 정적 의존성을 기반으로 합니다. 코드 수준의 상세 분석이 필요하면 해당 파일에서 `/explain`을 실행하세요.*")
        }
    }

    private fun formatRiskBadge(risk: ChangeRisk): String {
        return when (risk) {
            ChangeRisk.CRITICAL -> "🔴 **CRITICAL**"
            ChangeRisk.HIGH -> "🟠 **HIGH**"
            ChangeRisk.MEDIUM -> "🟡 **MEDIUM**"
            ChangeRisk.LOW -> "🟢 **LOW**"
            else -> "⚪ **UNKNOWN**"
        }
    }
}
