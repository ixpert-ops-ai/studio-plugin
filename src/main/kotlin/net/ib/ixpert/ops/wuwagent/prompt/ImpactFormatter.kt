package net.ib.ixpert.ops.wuwagent.prompt

import net.ib.ixpert.ops.wuwagent.service.analysis.ImpactAnalyzer

/**
 * ImpactAnalysisResult → 프롬프트용 변수 맵 변환 유틸리티.
 */
object ImpactFormatter {

    /**
     * ImpactAnalysisResult를 프롬프트 변수 맵으로 변환합니다.
     */
    fun toPromptVariables(
        result: ImpactAnalyzer.ImpactAnalysisResult,
        code: String,
        language: String
    ): Map<String, String> {
        val langNames = mapOf(
            "java" to "Java", "JAVA" to "Java",
            "kotlin" to "Kotlin", "Kotlin" to "Kotlin",
            "javascript" to "JavaScript", "typescript" to "TypeScript"
        )
        val langName = langNames[language] ?: language

        return mapOf(
            "LANGUAGE" to langName,
            "LANGUAGE_ID" to language.lowercase(),
            "CODE" to code,
            "TARGET_SIGNATURE" to result.targetSignature,
            "TARGET_LOCATION" to result.targetLocation,
            "TARGET_TYPE" to result.targetType,
            "STRATEGY_NAME" to result.statistics.strategy.name,
            "STRATEGY_LABEL" to result.statistics.strategy.label,
            "DIRECT_COUNT" to result.statistics.directCallerCount.toString(),
            "INDIRECT_COUNT" to result.statistics.indirectCallerCount.toString(),
            "POLYMORPHIC_COUNT" to result.statistics.polymorphicCount.toString(),
            "DATA_FLOW_COUNT" to result.statistics.dataFlowCount.toString(),
            "TOTAL_AFFECTED" to result.statistics.totalAffected.toString(),
            "MAX_DEPTH" to result.statistics.maxDepthReached.toString(),
            "CALL_HIERARCHY" to result.callHierarchyTree,
            "LAYER_SUMMARY" to result.layerSummary.ifBlank { "(해당 없음)" },
            "POLYMORPHISM_INFO" to result.polymorphismInfo.ifBlank { "(해당 없음)" },
            "DATA_FLOW_INFO" to result.dataFlowInfo.ifBlank { "(해당 없음)" },
            "AFFECTED_SIGNATURES" to result.allAffectedSignatures
                .mapIndexed { i, sig -> "${i + 1}. $sig" }
                .joinToString("\n")
                .ifBlank { "(없음)" }
        )
    }

    /**
     * 분석 결과 요약 헤더를 생성합니다.
     */
    fun buildSummaryHeader(result: ImpactAnalyzer.ImpactAnalysisResult): String {
        return """영향도 분석 요약
━━━━━━━━━━━━━━━━━━━━━━━━
대상: ${result.targetSignature}
위치: ${result.targetLocation}
타입: ${result.targetType}
전략: ${result.statistics.strategy.label}

직접 호출처: ${result.statistics.directCallerCount}개
간접 호출처: ${result.statistics.indirectCallerCount}개
다형성 관련: ${result.statistics.polymorphicCount}개
데이터 흐름: ${result.statistics.dataFlowCount}개
총 영향 요소: ${result.statistics.totalAffected}개
최대 깊이: ${result.statistics.maxDepthReached}
━━━━━━━━━━━━━━━━━━━━━━━━"""
    }
}
