package net.ib.ixpert.ops.wuwagent.agent.stage0

object DeficiencyDetector {

    fun detect(
        input: SrInput,
        quality: QualityIndicators,
        classification: SrClassification,
        archDecisions: List<ArchDecision>
    ): List<Deficiency> {
        val deficiencies = mutableListOf<Deficiency>()

        deficiencies.addAll(detectOmissions(input, quality, classification))
        deficiencies.addAll(detectAmbiguities(archDecisions))
        deficiencies.addAll(detectConflicts(input))

        return deficiencies
    }

    // ═══════════════════════════════════════════════
    // OMISSION 감지
    // ═══════════════════════════════════════════════

    private fun detectOmissions(
        input: SrInput,
        quality: QualityIndicators,
        classification: SrClassification
    ): List<Deficiency> {
        val result = mutableListOf<Deficiency>()
        val allTypes = listOf(classification.primary) + classification.secondary

        // 1) 기대 동작 누락 → BLOCKING
        if (!quality.hasExpectedBehavior) {
            result.add(Deficiency(
                type = DeficiencyType.OMISSION,
                severity = DeficiencySeverity.BLOCKING,
                field = "기대 동작",
                description = "변경 후 시스템이 어떻게 동작해야 하는지 명시되지 않았습니다.",
                suggestion = "'~되어야 한다', '~를 반환한다' 형태로 기대 동작을 추가해 주세요."
            ))
        }

        // 2) 설명 길이 부족 (50자 미만) → BLOCKING
        if (quality.descriptionLength < 50) {
            result.add(Deficiency(
                type = DeficiencyType.OMISSION,
                severity = DeficiencySeverity.BLOCKING,
                field = "설명 길이",
                description = "SR 설명이 ${quality.descriptionLength}자로 분석에 불충분합니다. (최소 50자)",
                suggestion = "배경, 현재 동작, 기대 동작을 포함하여 구체적으로 기술해 주세요."
            ))
        }

        // 3) DEEP 분석 필요 SR에서 파일 경로/기술 용어 없음 → WARNING
        val needsDeep = allTypes.any { it.analysisDepth == AnalysisDepth.DEEP }
        if (needsDeep && !quality.hasFilePaths && !quality.hasTechnicalTerms) {
            result.add(Deficiency(
                type = DeficiencyType.OMISSION,
                severity = DeficiencySeverity.WARNING,
                field = "기술 참조",
                description = "파일 경로나 클래스명/메서드명 등 기술 참조가 없습니다.",
                suggestion = "관련 파일명, API 경로, 테이블명 중 하나라도 명시하면 분석 정확도가 크게 향상됩니다."
            ))
        }

        // 4) 배치 SR인데 실행 주기 미명시 → WARNING
        if (allTypes.contains(SrType.BATCH_MODIFY)) {
            val text = "${input.title} ${input.description}"
            val hasCycleInfo = Regex("""(?:매일|매주|매월|cron|분마다|시간마다|\d+\s*[시분]|자정|00시)""")
                .containsMatchIn(text)
            if (!hasCycleInfo) {
                result.add(Deficiency(
                    type = DeficiencyType.OMISSION,
                    severity = DeficiencySeverity.WARNING,
                    field = "배치 실행 주기",
                    description = "배치/스케줄러 관련 SR이지만 실행 주기가 명시되지 않았습니다.",
                    suggestion = "실행 주기(예: 매일 00시, 매주 월요일)를 명시해 주세요."
                ))
            }
        }

        // 5) 인터페이스 SR인데 연동 대상 미명시 → WARNING
        if (allTypes.contains(SrType.INTERFACE_CHANGE)) {
            val text = "${input.title} ${input.description}"
            val hasTargetSystem = Regex("""(?:외부|시스템명|[A-Z]{2,}시스템|\w+API|\w+서버)""")
                .containsMatchIn(text)
            if (!hasTargetSystem) {
                result.add(Deficiency(
                    type = DeficiencyType.OMISSION,
                    severity = DeficiencySeverity.WARNING,
                    field = "연동 대상",
                    description = "인터페이스 변경 SR이지만 연동 대상 시스템이 불명확합니다.",
                    suggestion = "연동 대상 시스템명이나 API 엔드포인트를 명시해 주세요."
                ))
            }
        }

        return result
    }

    // ═══════════════════════════════════════════════
    // AMBIGUITY 감지 (아키텍처 결정 미해소)
    // ═══════════════════════════════════════════════

    private fun detectAmbiguities(archDecisions: List<ArchDecision>): List<Deficiency> {
        return archDecisions
            .filter { !it.resolved }
            .map { decision ->
                Deficiency(
                    type = DeficiencyType.AMBIGUITY,
                    severity = DeficiencySeverity.BLOCKING,
                    field = decision.topic,
                    description = "\"${decision.topic}\"에 대한 구현 방식이 명시되지 않아 해석이 분기됩니다. " +
                        "(감지 키워드: \"${decision.triggerKeyword}\")",
                    suggestion = "선택지: ${decision.options.joinToString(" / ")} 중 하나를 명시해 주세요."
                )
            }
    }

    // ═══════════════════════════════════════════════
    // CONFLICT 감지
    // ═══════════════════════════════════════════════

    private fun detectConflicts(input: SrInput): List<Deficiency> {
        val text = "${input.title} ${input.description}"
        return CONFLICT_PATTERNS.mapNotNull { pattern ->
            if (pattern.regex.containsMatchIn(text)) {
                Deficiency(
                    type = DeficiencyType.CONFLICT,
                    severity = DeficiencySeverity.BLOCKING,
                    field = "요건 모순",
                    description = pattern.description,
                    suggestion = pattern.suggestion
                )
            } else null
        }
    }

    private data class ConflictPattern(
        val regex: Regex,
        val description: String,
        val suggestion: String
    )

    private val CONFLICT_PATTERNS = listOf(
        ConflictPattern(
            regex = Regex("""(?:삭제|제거).*(?:하되|하면서|동시에).*(?:유지|보존|남겨)"""),
            description = "삭제/제거와 유지/보존이 동시에 요구됩니다.",
            suggestion = "삭제 대상과 보존 대상을 명확히 구분해 주세요."
        ),
        ConflictPattern(
            regex = Regex("""(?:비로그인|미인증).*(?:허용|접근\s*가능).*(?:제한|차단|불가)"""),
            description = "접근 허용과 제한이 동시에 명시되어 모순입니다.",
            suggestion = "허용 조건과 제한 조건을 분리해서 명시해 주세요."
        ),
        ConflictPattern(
            regex = Regex("""(?:동기|즉시).*(?:하면서|동시에).*(?:비동기|이벤트|나중에)"""),
            description = "동기 처리와 비동기 처리가 동시에 요구됩니다.",
            suggestion = "처리 방식을 하나로 명확히 결정해 주세요."
        )
    )
}
