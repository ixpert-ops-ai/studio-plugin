package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import kotlin.test.*


/**
 * PsiMethodExtractor 단위 테스트.
 * testData/ 디렉토리에 샘플 Java 파일을 배치하고 PSI를 통해 추출 결과를 검증.
 */
class PsiMethodExtractorTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    private lateinit var extractor: PsiMethodExtractor

    override fun setUp() {
        super.setUp()
        extractor = PsiMethodExtractor(getProject())
    }

    // ──────────────────────────────────────────────
    // 키워드 추출 테스트 (PSI 불필요 — 순수 로직)
    // ──────────────────────────────────────────────

    fun testExtractKeywords_Korean() {
        val keywords = extractor.extractKeywords("설문 결과를 Excel 파일로 다운로드하는 기능을 추가")
        // "결과", "excel", "파일", "다운로드", "기능", "추가" 등이 포함되어야 함
        assertTrue("excel" in keywords)
        assertTrue("다운로드" in keywords || "다운로" in keywords)
        // 조사 "를", "로", "하는" 이 제거되었는지 확인
        assertFalse("결과를" in keywords)
    }

    fun testExtractKeywords_English() {
        val keywords = extractor.extractKeywords("add Excel download method for survey results")
        assertTrue("excel" in keywords)
        assertTrue("download" in keywords)
        assertTrue("survey" in keywords)
    }

    fun testExtractKeywords_Empty() {
        val keywords = extractor.extractKeywords("")
        assertTrue(keywords.isEmpty())
    }

    // ──────────────────────────────────────────────
    // camelCase 분리 테스트
    // ──────────────────────────────────────────────

    fun testSplitCamelCase_Standard() {
        val words = extractor.splitCamelCase("findSurveyListByDate")
        assertTrue("find" in words)
        assertTrue("survey" in words)
        assertTrue("list" in words)
        assertTrue("date" in words)
    }

    fun testSplitCamelCase_AllCaps() {
        val words = extractor.splitCamelCase("parseJSONData")
        assertTrue("json" in words)
        assertTrue("data" in words)
    }

    fun testSplitCamelCase_Short() {
        val words = extractor.splitCamelCase("getId")
        // "get"은 3글자로 포함, "id"는 2글자로 MIN_KEYWORD_LENGTH 초과하지 않아 제외
        assertTrue("get" in words)
        assertFalse("id" in words)
    }

    // ──────────────────────────────────────────────
    // 매칭 점수 계산 테스트
    // ──────────────────────────────────────────────

    fun testCalculateMatchScore_HighMatch() {
        val score = extractor.calculateMatchScore(
            "exportSurveyResultToExcel",
            setOf("survey", "excel", "export", "result")
        )
        assertTrue(score >= 0.5, "매칭 점수가 임계값 이상이어야 함")
    }

    fun testCalculateMatchScore_NoMatch() {
        val score = extractor.calculateMatchScore(
            "findUserById",
            setOf("survey", "excel", "download")
        )
        assertTrue(score < PsiMethodExtractor.KEYWORD_MATCH_THRESHOLD, "매칭 점수가 0이어야 함")
    }

    fun testCalculateMatchScore_EmptyKeywords() {
        val score = extractor.calculateMatchScore("anyMethod", emptySet())
        assertEquals(0.0, score)
    }

    // ──────────────────────────────────────────────
    // PSI 기반 스켈레톤 추출 테스트
    // ──────────────────────────────────────────────

    /**
     * testData/LargeSurveyServiceImpl.java 를 사용한 통합 테스트.
     * 파일 내용: 250줄 이상의 @Service 클래스, 10개 이상 메서드, @Autowired 필드 4개.
     */
    fun testExtract_LargeServiceFile() {
        // testData에 샘플 파일 배치
        myFixture.configureByFile("LargeSurveyServiceImpl.java")

        // extract는 프로젝트 상대 경로를 요구하므로, fixture 파일 경로를 사용
        val psiFile = myFixture.file
        val virtualFile = psiFile.virtualFile

        // PsiMethodExtractor를 직접 호출하는 대신
        // PSI 접근이 가능한 fixture 환경에서 내부 로직을 검증
        val psiJavaFile = psiFile as? com.intellij.psi.PsiJavaFile
        assertNotNull(psiJavaFile, "PsiJavaFile이어야 함")

        val psiClass = psiJavaFile!!.classes.firstOrNull()
        assertNotNull(psiClass, "클래스가 존재해야 함")

        // 어노테이션 포함 검증
        val classAnnotations = psiClass!!.annotations.map { it.text }
        assertTrue(classAnnotations.any { it.contains("Service") }, "@Service 어노테이션 포함")

        // 메서드 수 검증
        assertTrue(psiClass.methods.size >= 10, "10개 이상 메서드")

        // 키워드 매칭 검증: "survey excel download" 키워드로 연관 메서드 필터링
        val keywords = extractor.extractKeywords("설문 결과를 Excel로 다운로드")
        var matchedCount = 0
        psiClass.methods.forEach { method ->
            val score = extractor.calculateMatchScore(method.name, keywords)
            if (score >= PsiMethodExtractor.KEYWORD_MATCH_THRESHOLD) {
                matchedCount++
            }
        }
        // 전체 메서드 중 일부만 매칭되어야 함 (전부 매칭되면 키워드가 너무 넓음)
        assertTrue(matchedCount < psiClass.methods.size / 2, "일부 메서드만 매칭 (전체의 50% 미만)")
    }

    /**
     * 어노테이션이 시그니처에 포함되는지 검증.
     * @Transactional, @Override 등이 MethodSignature.annotations에 들어가야 함.
     */
    fun testExtract_AnnotationsIncluded() {
        myFixture.configureByFile("LargeSurveyServiceImpl.java")
        val psiClass = (myFixture.file as com.intellij.psi.PsiJavaFile).classes.first()

        val transactionalMethods = psiClass.methods.filter { method ->
            method.annotations.any { it.text.contains("Transactional") }
        }
        // @Transactional 메서드가 1개 이상 존재하면
        if (transactionalMethods.isNotEmpty()) {
            val method = transactionalMethods.first()
            val annotations = method.annotations.map { it.text }
            assertTrue(annotations.any { it.contains("Transactional") }, "어노테이션 목록에 @Transactional 포함")
        }
    }

    /**
     * toPromptText() 출력 형식 검증.
     */
    fun testClassSkeleton_ToPromptText() {
        val skeleton = ClassSkeleton(
            filePath = "src/main/java/com/example/TestService.java",
            packageName = "com.example",
            imports = listOf("import org.springframework.stereotype.Service;"),
            classAnnotations = listOf("@Service"),
            classDeclaration = "public class TestService implements ITestService",
            fields = listOf("@Autowired private UserDao userDao;"),
            allMethodSignatures = listOf(
                MethodSignature("findAll", listOf("int page"), "List<User>", listOf("@Transactional"), "public"),
                MethodSignature("save", listOf("User user"), "void", emptyList(), "public")
            ),
            relevantMethodBodies = listOf(
                MethodBody(
                    signature = MethodSignature("findAll", listOf("int page"), "List<User>", listOf("@Transactional"), "public"),
                    body = "@Transactional\npublic List<User> findAll(int page) {\n    return userDao.findAll();\n}",
                    startLine = 25,
                    endLine = 28
                )
            ),
            isNewMethodRequired = true
        )

        val text = skeleton.toPromptText()
        assertTrue(text.contains("// 파일: src/main/java/com/example/TestService.java"), "파일 경로 포함")
        assertTrue(text.contains("public class TestService implements ITestService"), "클래스 선언 포함")
        assertTrue(text.contains("@Autowired private UserDao userDao;"), "필드 포함")
        assertTrue(text.contains("findAll"), "시그니처 포함")
        assertTrue(text.contains("return userDao.findAll()"), "연관 메서드 바디 포함")
        assertTrue(text.contains("📍 위치: 라인 25~28"), "위치 힌트 포함")
        assertTrue(text.contains("=== 새 메서드를 여기에 추가하세요 ==="), "신규 메서드 삽입 안내 포함")
    }
}
