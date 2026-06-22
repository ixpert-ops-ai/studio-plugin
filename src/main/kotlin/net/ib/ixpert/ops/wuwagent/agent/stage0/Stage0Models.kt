package net.ib.ixpert.ops.wuwagent.agent.stage0

// region 입력 모델
data class SrInput(
    val srId: String,
    val title: String,
    val description: String,
    val additionalContext: String? = null
)
// endregion

// region Phase 0-1 출력
data class QualityIndicators(
    val descriptionLength: Int,
    val hasFilePaths: Boolean,
    val filePathCount: Int,
    val hasCodeSnippets: Boolean,
    val hasTechnicalTerms: Boolean,
    val technicalTerms: List<String>,
    val hasExpectedBehavior: Boolean,
    val hasCurrentBehavior: Boolean
)

data class SrClassification(
    val primary: SrType,
    val secondary: List<SrType> = emptyList(),
    val confidence: Int,       // 0~100
    val reason: String,
    val classifiedBy: ClassificationMethod = ClassificationMethod.RULE
)

enum class ClassificationMethod { RULE, LLM, FALLBACK }

enum class SrType(val keywords: List<String>, val analysisDepth: AnalysisDepth) {
    FIELD_ADD(
        keywords = listOf("필드 추가", "컬럼 추가", "항목 추가", "입력란", "표시란", "비고란", "만료일"),
        analysisDepth = AnalysisDepth.SHALLOW
    ),
    CONDITION_CHANGE(
        keywords = listOf("조건 변경", "분기 수정", "코드값", "상태값 추가", "조건식", "포맷", "형식 변경"),
        analysisDepth = AnalysisDepth.SHALLOW
    ),
    NEW_FEATURE(
        keywords = listOf("신규", "새로운 기능", "신규 화면", "신규 API", "찜하기", "알림", "기능 추가"),
        analysisDepth = AnalysisDepth.DEEP
    ),
    INTERFACE_CHANGE(
        keywords = listOf("전문", "인터페이스", "연동", "외부 시스템", "API 버전", "스펙 변경", "전문 필드"),
        analysisDepth = AnalysisDepth.DEEP
    ),
    BATCH_MODIFY(
        keywords = listOf("배치", "정산", "집계", "마감", "스케줄러", "일괄", "cron", "탈퇴"),
        analysisDepth = AnalysisDepth.DEEP
    )
}

enum class AnalysisDepth { SHALLOW, DEEP }
// endregion

// region Phase 0-2 출력
enum class DeficiencyType { CONFLICT, OMISSION, AMBIGUITY }
enum class DeficiencySeverity { BLOCKING, WARNING, INFO }

data class Deficiency(
    val type: DeficiencyType,
    val severity: DeficiencySeverity,
    val field: String,
    val description: String,
    val suggestion: String
)

data class ArchDecision(
    val topic: String,
    val options: List<String>,
    val triggerKeyword: String,
    val resolved: Boolean = false,
    val selectedOption: String? = null
)
// endregion

// region Phase 0-3 출력
data class Stage0Question(
    val category: QuestionCategory,
    val priority: Priority,
    val question: String,
    val purpose: String,
    val relatedDeficiency: Deficiency? = null,
    val relatedDecision: ArchDecision? = null
)

enum class QuestionCategory {
    DEFICIENCY_RESOLUTION, ARCHITECTURE_DECISION, CONTEXTUAL, FALLBACK
}

enum class Priority { CRITICAL, HIGH, MEDIUM, LOW }
// endregion

// region Gate 출력 → Stage 1 전달
data class EnrichedSr(
    val originalSr: SrInput,
    val classification: SrClassification,
    val resolvedDecisions: List<ArchDecision>,
    val additionalContext: String,
    val analysisDepth: AnalysisDepth,
    val fileLimit: Int,
    val searchDirections: List<SearchDirection>,
    val warnings: List<String> = emptyList()
)

enum class SearchDirection {
    VERTICAL, HORIZONTAL, PATTERN_REFERENCE, EXTERNAL_BOUNDARY, SCHEDULER_ENTRY
}

data class GateEvaluation(
    val canProceed: Boolean,
    val blockingReasons: List<String>,
    val warnings: List<String>,
    val enrichedSr: EnrichedSr?,
    val unresolvedQuestions: List<Stage0Question> = emptyList()
)

data class Stage0Output(
    val sessionId: String,
    val gateEvaluation: GateEvaluation,
    val analysis: SrAnalysis
)
// endregion

// region 세션 관리
data class Stage0Session(
    val sessionId: String,
    val input: SrInput,
    val analysis: SrAnalysis,
    val questions: List<Stage0Question>,
    val answers: MutableMap<Stage0Question, String> = mutableMapOf(),
    val retryCount: Int = 0,
    val maxRetries: Int = 3,
    val createdAt: Long = System.currentTimeMillis()
)

data class SrAnalysis(
    val srInput: SrInput,
    val classification: SrClassification,
    val qualityIndicators: QualityIndicators,
    val deficiencies: List<Deficiency>,
    val architectureDecisions: List<ArchDecision>
)
// endregion
