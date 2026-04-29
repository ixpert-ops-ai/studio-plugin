package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter

/**
 * 대형 파일(200줄 초과 또는 1500토큰 초과)에서 클래스 스켈레톤과
 * 연관 메서드 바디만 추출하는 유틸리티.
 *
 * 설계 원칙:
 * - PSI 읽기 전용 (Write Action 없음)
 * - 추출 결과는 ClassSkeleton 데이터 클래스로 반환 (프롬프트 변환과 분리)
 * - 키워드 매칭 임계값 0.3 이상인 메서드만 바디 포함
 */
class PsiMethodExtractor(private val project: Project) {

    private val logger = Logger.getInstance(PsiMethodExtractor::class.java)

    companion object {
        /** 대형 파일 판정 기준 */
        const val LINE_THRESHOLD = 200
        const val TOKEN_THRESHOLD = 1500

        /** 키워드 매칭 임계값: 키워드 히트 비율이 이 값 이상이면 연관 메서드로 판정 */
        const val KEYWORD_MATCH_THRESHOLD = 0.3

        /** 키워드 추출 시 제거할 한국어 조사/어미 패턴 */
        private val KOREAN_PARTICLE_REGEX = Regex("(을|를|의|에서|에|이|가|은|는|와|과|로|으로|하는|하여|하고|위한|통한|대한|된|할|함)$")

        /** 의미 없는 짧은 토큰 최소 길이 */
        const val MIN_KEYWORD_LENGTH = 2

        /** 메서드 본문 줄 수 제한: 이 값을 초과하면 본문을 축약함 */
        const val METHOD_BODY_LINE_LIMIT = 50
    }

    // ──────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────

    /**
     * 파일이 대형 파일 기준을 초과하는지 판정.
     * ImplementationPipeline의 Strategy 분기에서 호출.
     */
    fun isLargeFile(filePath: String): Boolean {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(
            project.basePath + "/" + filePath.replace("//", "/")
        ) ?: return false

        val content = String(virtualFile.contentsToByteArray(), Charsets.UTF_8)
        val lineCount = content.lines().size
        val estimatedTokens = content.length / 3  // 한국어/FQN 포함 보수적 추정

        val isLarge = lineCount > LINE_THRESHOLD || estimatedTokens > TOKEN_THRESHOLD
        if (isLarge) {
            logger.info("대형 파일 감지: $filePath (${lineCount}줄, ~${estimatedTokens}토큰)")
        }
        return isLarge
    }

    /**
     * 메인 추출 메서드.
     * @param filePath 프로젝트 루트 기준 상대 경로
     * @param taskDescription 2a에서 파싱된 TargetFileSpec.description
     * @param taskType "수정" 또는 "신규"
     * @return ClassSkeleton 또는 null (파일 미발견/파싱 실패 시)
     */
    fun extract(filePath: String, taskDescription: String, taskType: String): ClassSkeleton? {
        val psiFile = findPsiFile(filePath) ?: run {
            logger.warn("PsiFile을 찾을 수 없음: $filePath")
            return null
        }

        if (psiFile !is PsiJavaFile) {
            logger.warn("Java 파일이 아님: $filePath")
            return null
        }

        val psiClass = psiFile.classes.firstOrNull() ?: run {
            logger.warn("클래스를 찾을 수 없음: $filePath")
            return null
        }

        val keywords = extractKeywords(taskDescription)
        logger.info("추출된 키워드: $keywords (from: $taskDescription)")

        val allSignatures = psiClass.methods.map { extractSignature(it) }
        val relevantBodies = findRelevantMethods(psiClass, keywords)
        val isNewMethod = taskType.contains("신규") || taskType.lowercase().contains("new")

        return ClassSkeleton(
            filePath = filePath,
            packageName = psiFile.packageName,
            imports = psiFile.importList?.allImportStatements?.mapNotNull { it.text } ?: emptyList(),
            classAnnotations = psiClass.annotations.map { it.text },
            classDeclaration = buildClassDeclaration(psiClass),
            fields = psiClass.fields.map { formatField(it) },
            allMethodSignatures = allSignatures,
            relevantMethodBodies = relevantBodies,
            isNewMethodRequired = isNewMethod
        )
    }

    // ──────────────────────────────────────────────
    // PSI 파일 조회
    // ──────────────────────────────────────────────

    private fun findPsiFile(relativePath: String): PsiFile? {
        val absolutePath = project.basePath + "/" + relativePath.replace("//", "/")
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(absolutePath) ?: return null
        return PsiManager.getInstance(project).findFile(virtualFile)
    }

    // ──────────────────────────────────────────────
    // 메서드 시그니처 추출
    // ──────────────────────────────────────────────

    private fun extractSignature(method: PsiMethod): MethodSignature {
        return MethodSignature(
            name = method.name,
            parameters = method.parameterList.parameters.map { param ->
                formatParameter(param)
            },
            returnType = method.returnType?.presentableText ?: "void",
            annotations = method.annotations.map { it.text },
            accessModifier = extractAccessModifier(method)
        )
    }

    private fun formatParameter(param: PsiParameter): String {
        val annotations = param.annotations.joinToString(" ") { it.text }
        val prefix = if (annotations.isNotEmpty()) "$annotations " else ""
        return "${prefix}${param.type.presentableText} ${param.name}"
    }

    private fun extractAccessModifier(method: PsiMethod): String {
        return when {
            method.modifierList?.hasModifierProperty("public") == true -> "public"
            method.modifierList?.hasModifierProperty("protected") == true -> "protected"
            method.modifierList?.hasModifierProperty("private") == true -> "private"
            else -> "" // package-private
        }
    }

    // ──────────────────────────────────────────────
    // 클래스 선언부 구성
    // ──────────────────────────────────────────────

    private fun buildClassDeclaration(psiClass: PsiClass): String = buildString {
        // 접근 제한자
        if (psiClass.modifierList?.hasModifierProperty("public") == true) append("public ")
        if (psiClass.modifierList?.hasModifierProperty("abstract") == true) append("abstract ")

        // 클래스 종류
        if (psiClass.isInterface) {
            append("interface ")
        } else {
            append("class ")
        }

        append(psiClass.name ?: "Unknown")

        // 상속
        psiClass.extendsList?.referenceElements?.firstOrNull()?.let {
            append(" extends ${it.referenceName}")
        }

        // 구현 인터페이스
        val interfaces = psiClass.implementsList?.referenceElements
        if (interfaces != null && interfaces.isNotEmpty()) {
            val keyword = if (psiClass.isInterface) " extends " else " implements "
            append(keyword)
            append(interfaces.joinToString(", ") { it.referenceName ?: "Unknown" })
        }
    }

    // ──────────────────────────────────────────────
    // 필드 포맷팅
    // ──────────────────────────────────────────────

    private fun formatField(field: PsiField): String = buildString {
        // 어노테이션 (@Autowired, @Value 등)
        field.annotations.forEach { annotation ->
            append("${annotation.text} ")
        }
        // 접근 제한자
        if (field.modifierList?.hasModifierProperty("private") == true) append("private ")
        if (field.modifierList?.hasModifierProperty("protected") == true) append("protected ")
        if (field.modifierList?.hasModifierProperty("public") == true) append("public ")
        if (field.modifierList?.hasModifierProperty("static") == true) append("static ")
        if (field.modifierList?.hasModifierProperty("final") == true) append("final ")

        append("${field.type.presentableText} ${field.name};")
    }

    // ──────────────────────────────────────────────
    // 키워드 매칭 기반 연관 메서드 탐색
    // ──────────────────────────────────────────────

    /**
     * taskDescription에서 의미 있는 키워드를 추출.
     * 한국어 조사 제거 → 최소 길이 필터링 → 소문자 정규화.
     */
    internal fun extractKeywords(description: String): Set<String> {
        return description
            .split(Regex("[\\s,./()\\[\\]{}]+"))       // 공백 및 구분자로 분리
            .map { it.replace(KOREAN_PARTICLE_REGEX, "") }  // 한국어 조사 제거
            .filter { it.length > MIN_KEYWORD_LENGTH }       // 짧은 토큰 제거
            .map { it.lowercase() }                          // 소문자 정규화
            .toSet()
    }

    /**
     * camelCase 메서드명을 단어 단위로 분리.
     * 예: "findSurveyListByDate" → {"find", "survey", "list", "by", "date"}
     */
    internal fun splitCamelCase(name: String): Set<String> {
        return name
            .split(Regex("(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])"))
            .map { it.lowercase() }
            .filter { it.length > MIN_KEYWORD_LENGTH }
            .toSet()
    }

    /**
     * 메서드명과 키워드 집합 간의 매칭 점수를 계산.
     * @return 0.0 ~ 1.0 사이의 히트 비율
     */
    internal fun calculateMatchScore(methodName: String, keywords: Set<String>): Double {
        if (keywords.isEmpty()) return 0.0

        val methodWords = splitCamelCase(methodName)
        val hitCount = keywords.count { keyword ->
            methodWords.any { word -> word.contains(keyword) || keyword.contains(word) }
        }
        return hitCount.toDouble() / keywords.size
    }

    /**
     * 키워드 매칭 점수가 임계값 이상인 메서드들의 바디를 추출.
     */
    private fun findRelevantMethods(psiClass: PsiClass, keywords: Set<String>): List<MethodBody> {
        if (keywords.isEmpty()) {
            logger.info("키워드가 비어있어 연관 메서드 탐색 생략")
            return emptyList()
        }

        val document = psiClass.containingFile?.viewProvider?.document

        return psiClass.methods
            .mapNotNull { method ->
                    val score = calculateMatchScore(method.name, keywords)
                    logger.info("메서드 매칭 점수: ${method.name} = $score (임계값: $KEYWORD_MATCH_THRESHOLD)")
                    if (score >= KEYWORD_MATCH_THRESHOLD) {
                        logger.info("연관 메서드 발견: ${method.name} (score: ${"%.2f".format(score)})")

                        val startLine = document?.getLineNumber(method.textRange.startOffset)?.plus(1) ?: -1
                        val endLine = document?.getLineNumber(method.textRange.endOffset)?.plus(1) ?: -1
                        val fullBody = method.text
                        val lineCount = endLine - startLine + 1

                        val bodyToUse = if (lineCount > METHOD_BODY_LINE_LIMIT) {
                            logger.info("메서드 본문 축약 적용: ${method.name} (${lineCount}줄)")
                            buildTruncatedBody(method)
                        } else {
                            fullBody
                        }

                    MethodBody(
                        signature = extractSignature(method),
                        body = bodyToUse,
                        startLine = startLine,
                        endLine = endLine
                    )
                } else {
                    null
                }
            }
    }

    /**
     * 대형 메서드의 축약 본문을 생성합니다.
     * 첫 5줄과 마지막 5줄만 포함하고, 중간은 요약 주석으로 대체합니다.
     */
    private fun buildTruncatedBody(method: PsiMethod): String {
        val fullBody = method.text ?: return ""
        val lines = fullBody.lines()
        if (lines.size <= METHOD_BODY_LINE_LIMIT) return fullBody

        val header = lines.take(5).joinToString("\n")
        val footer = lines.takeLast(5).joinToString("\n")
        val omittedCount = lines.size - 10

        return """
            $header
            // ... (${omittedCount}줄 생략 - 기존 비즈니스 로직, 수정 불필요) ...
            $footer
        """.trimIndent()
    }
}
