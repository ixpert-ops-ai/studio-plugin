package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer

/**
 * PSI에서 추출된 메서드 시그니처.
 * annotations를 포함해야 LLM이 @Transactional, @Override 등 역할을 판단할 수 있음.
 */
data class MethodSignature(
    val name: String,
    val parameters: List<String>,      // 예: ["Long surveyId", "String format"]
    val returnType: String,            // 예: "List<SurveyDto>"
    val annotations: List<String>,     // 예: ["@Transactional", "@Override"]
    val accessModifier: String = "public"  // public, private, protected, package-private
)

/**
 * 시그니처 + 바디를 함께 담는 컨테이너.
 * 키워드 매칭으로 연관성이 확인된 메서드만 바디가 포함됨.
 */
data class MethodBody(
    val signature: MethodSignature,
    val body: String,                  // 메서드 전체 텍스트 (어노테이션 + 시그니처 + 중괄호 내용)
    val startLine: Int,                // 원본 파일 내 시작 줄 번호 (위치 힌트용)
    val endLine: Int
) {
    val lineCount: Int get() = endLine - startLine + 1
}

/**
 * PsiMethodExtractor의 최종 출력.
 * 프롬프트 변환(toPromptText)과 파이프라인 로직을 분리하기 위해 구조화.
 */
data class ClassSkeleton(
    val filePath: String,              // 프로젝트 루트 기준 상대 경로
    val packageName: String,
    val imports: List<String>,
    val classAnnotations: List<String>,  // @Service, @RestController 등
    val classDeclaration: String,      // 예: "public class SurveyServiceImpl implements SurveyService"
    val fields: List<String>,          // 예: ["@Autowired private SurveyDao surveyDao"]
    val allMethodSignatures: List<MethodSignature>,
    val relevantMethodBodies: List<MethodBody>,
    val isNewMethodRequired: Boolean   // 기존 메서드 수정이 아닌 신규 추가인 경우
) {

    /**
     * LLM 프롬프트용 텍스트 변환.
     * 프롬프트 포맷이 바뀌어도 추출 로직(PsiMethodExtractor)은 수정 불요.
     */
    fun toPromptText(): String = buildString {
        appendLine("// 파일: $filePath")
        appendLine("package $packageName;")
        appendLine()

        // import 목록
        if (imports.isNotEmpty()) {
            imports.forEach { appendLine(it) }
            appendLine()
        }

        // 클래스 어노테이션 + 선언
        classAnnotations.forEach { appendLine(it) }
        appendLine("$classDeclaration {")
        appendLine()

        // 필드
        if (fields.isNotEmpty()) {
            appendLine("    // === 필드 ===")
            fields.forEach { appendLine("    $it") }
            appendLine()
        }

        // 관련 메서드의 이름 목록 (비교용)
        val relevantMethodNames = relevantMethodBodies.map { it.signature.name }.toSet()

        // 메서드 시그니처 목록 (관련 메서드와 비관련 메서드 분리)
        appendLine("    // === 메서드 시그니처 목록 ===")
        appendLine("    // (아래는 이 클래스에 존재하는 메서드 시그니처입니다)")
        appendLine("    // (⚠️ 이 시그니처들을 보고 메서드 전체를 재작성하지 마세요)")
        
        // 관련 메서드 시그니처만 개별 표시
        allMethodSignatures.filter { it.name in relevantMethodNames }.forEach { sig ->
            val annotations = if (sig.annotations.isNotEmpty()) {
                sig.annotations.joinToString(" ") + " "
            } else ""
            appendLine("    // → ${annotations}${sig.accessModifier} ${sig.returnType} ${sig.name}(${sig.parameters.joinToString(", ")})  [관련 메서드 - 본문 아래 참조]")
        }

        // 비관련 메서드는 개수만 표시
        val unrelatedCount = allMethodSignatures.count { it.name !in relevantMethodNames }
        if (unrelatedCount > 0) {
            appendLine("    // → ... 외 ${unrelatedCount}개 기존 메서드 (변경 불필요, 시그니처 생략)")
        }
        appendLine()

        // 연관 메서드 바디 (키워드 매칭된 것만)
        if (relevantMethodBodies.isNotEmpty()) {
            appendLine("    // === 관련 메서드 본문 ===")
            relevantMethodBodies.forEach { method ->
                appendLine("    // 📍 위치: 라인 ${method.startLine}~${method.endLine}")
                if (method.lineCount > 50) { // PsiMethodExtractor.METHOD_BODY_LINE_LIMIT와 동기화
                    appendLine("    // ⚠️ 아래 메서드는 ${method.lineCount}줄로 매우 큽니다.")
                    appendLine("    // 이 메서드의 기존 변수 선언을 모방하거나 확장하지 마세요.")
                    appendLine("    // 오직 요구사항에 명시된 변경만 수행하세요.")
                }
                appendLine(method.body.prependIndent("    "))
                appendLine()
            }
        }

        if (isNewMethodRequired || relevantMethodBodies.isEmpty()) {
            appendLine("    // === 새 메서드를 여기에 추가하세요 ===")
        }

        appendLine("}")
    }
}
