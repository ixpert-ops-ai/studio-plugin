package net.ib.ixpert.ops.wuwagent.agent

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import net.ib.ixpert.ops.wuwagent.client.OllamaClient
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.*

/**
 * Phase 2c: 테스트 코드 자동 생성 파이프라인.
 *
 * 두 가지 실행 모드:
 * 1. /implement 후속 실행 — RequirementAnalysisPipeline 캐시 + contextChain 활용
 * 2. 독립 실행 (/test ClassName) — 지정 파일의 프로덕션 코드를 직접 로드
 */
class TestGenerationPipeline(
    private val project: Project,
    private val llmClient: OllamaClient
) {

    private val logger = Logger.getInstance(TestGenerationPipeline::class.java)
    private val testFileMapper = TestFileMapper(project)
    private val psiMethodExtractor = PsiMethodExtractor(project)
    private val graphLoader = project.getService(GraphLoader::class.java)

    companion object {
        /**
         * /implement에서 쌓인 컨텍스트 체인.
         * ImplementationPipeline이 실행 완료 후 여기에 결과를 저장.
         */
        var lastImplementContext: ImplementContext? = null
    }

    // ──────────────────────────────────────────────
    // 데이터 모델
    // ──────────────────────────────────────────────

    /**
     * /implement 실행 결과를 담는 컨텍스트.
     * ImplementationPipeline.execute() 완료 시 저장됨.
     */
    data class ImplementContext(
        val targetFiles: List<TargetFileSpec>,
        val contextChain: List<String>,         // [MODIFIED_SIGNATURES] 누적
        val generatedSnippets: Map<String, String>  // filePath → 생성된 코드 스니펫
    )

    /**
     * 테스트 생성 대상.
     */
    data class TestTarget(
        val targetFile: TargetFileSpec,
        val testFileInfo: TestFileInfo,
        val productionContext: String,   // 프로덕션 코드 또는 스켈레톤
        val modifiedSignatures: String,  // 해당 파일의 MODIFIED_SIGNATURES
        val changeRisk: String           // LOW, MEDIUM, HIGH, CRITICAL
    )

    // ──────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────

    /**
     * 메인 실행 메서드.
     * @param explicitTarget 독립 실행 시 클래스명 (예: "SurveyServiceImpl"), null이면 캐시 사용
     * @param onChunk 스트리밍 콜백 (WebView로 청크 전송)
     */
    fun execute(
        explicitTarget: String? = null,
        onChunk: (String) -> Unit
    ) {
        val testTargets = if (explicitTarget != null) {
            // 독립 실행 모드
            resolveExplicitTarget(explicitTarget)
        } else {
            // /implement 후속 실행 모드
            resolveFromImplementCache()
        }

        if (testTargets.isEmpty()) {
            onChunk("⚠️ 테스트를 생성할 대상 파일이 없습니다.\n")
            return
        }

        onChunk("🧪 테스트 코드 생성을 시작합니다. (대상: ${testTargets.size}개 파일)\n\n")

        testTargets.forEachIndexed { index, target ->
            onChunk("🔄 [${index + 1}/${testTargets.size}] ${target.testFileInfo.testClassName} 생성 중...\n")

            val systemPrompt = buildSystemPrompt(target)
            val userPrompt = buildUserPrompt(target)

            try {
                // LLM 호출 (스트리밍)
                llmClient.callChatApiStream(systemPrompt, userPrompt) { chunk ->
                    onChunk(chunk)
                }
            } catch (e: Exception) {
                logger.error("Error generating test for ${target.targetFile.path}", e)
                onChunk("\n\n> ❌ **테스트 생성 중 에러가 발생하여 이 파일을 건너뜁니다:** `${e.message}`\n\n")
            }
        }

        onChunk("\n✅ 테스트 코드 생성이 완료되었습니다.\n")
        onChunk("생성된 테스트 코드를 검토 후, 기존 테스트 클래스에 붙여넣거나 새 파일로 저장하세요.\n")
    }

    // ──────────────────────────────────────────────
    // 대상 파일 해석
    // ──────────────────────────────────────────────

    /**
     * /implement 캐시로부터 테스트 대상 목록을 구성.
     */
    private fun resolveFromImplementCache(): List<TestTarget> {
        val cache = lastImplementContext
        if (cache == null) {
            logger.warn("ImplementContext 캐시 없음")
            return emptyList()
        }

        return cache.targetFiles
            .filter { shouldGenerateTest(it) }
            .mapNotNull { targetFile ->
                val testFileInfo = testFileMapper.resolve(targetFile.path) ?: return@mapNotNull null
                val productionContext = loadProductionContext(targetFile, cache)
                val signatures = extractSignaturesForFile(targetFile.path, cache.contextChain)
                val changeRisk = resolveChangeRisk(targetFile.path)

                TestTarget(
                    targetFile = targetFile,
                    testFileInfo = testFileInfo,
                    productionContext = productionContext,
                    modifiedSignatures = signatures,
                    changeRisk = changeRisk
                )
            }
    }

    /**
     * 독립 실행 모드: 클래스명으로 파일을 찾아 테스트 대상 구성.
     */
    private fun resolveExplicitTarget(className: String): List<TestTarget> {
        val graph = graphLoader?.loadGraph() ?: run {
            logger.warn("ProjectGraph 로드 실패")
            return emptyList()
        }

        // className으로 FileNode 탐색
        val matchingEntry = graph.files.entries.firstOrNull { (_, node) ->
            node.className.equals(className, ignoreCase = true)
        } ?: run {
            logger.warn("클래스를 찾을 수 없음: $className")
            return emptyList()
        }

        val (filePath, fileNode) = matchingEntry
        val testFileInfo = testFileMapper.resolve(filePath) ?: return emptyList()

        // 프로덕션 코드 로드
        val productionContext = if (psiMethodExtractor.isLargeFile(filePath)) {
            val skeleton = psiMethodExtractor.extract(filePath, "전체 클래스 테스트", "수정")
            skeleton?.toPromptText() ?: "// 스켈레톤 추출 실패"
        } else {
            readFileContent(filePath) ?: "// 파일 읽기 실패"
        }

        return listOf(
            TestTarget(
                targetFile = TargetFileSpec(
                    order = 1,
                    path = filePath,
                    type = "수정",
                    description = "기존 클래스 테스트 생성"
                ),
                testFileInfo = testFileInfo,
                productionContext = productionContext,
                modifiedSignatures = "",  // 독립 실행이므로 없음
                changeRisk = fileNode.changeRisk?.name ?: "NOT_CALCULATED"
            )
        )
    }

    // ──────────────────────────────────────────────
    // 필터링 로직
    // ──────────────────────────────────────────────

    /**
     * 테스트 생성 대상 여부 판정.
     *
     * 제외 기준:
     * - 인터페이스 파일 (Impl 없는 순수 인터페이스)
     * - 단순 DTO (메서드가 getter/setter만 있는 경우)
     * - Entity 파일 (JPA Entity)
     *
     * 포함 기준:
     * - Service, Controller, Util/Common 계층의 구현 클래스
     */
    internal fun shouldGenerateTest(targetFile: TargetFileSpec): Boolean {
        val path = targetFile.path
        val graph = graphLoader?.loadGraph()
        val fileNode = graph?.files?.get(path)

        // 1. 인터페이스 제외 (파일명으로 추정 — Impl이 없는 Service.java 등)
        if (fileNode?.isInterface == true) {
            logger.info("인터페이스 제외: $path")
            return false
        }

        // 2. 순수 인터페이스 파일명 패턴 제외 (project-graph에 isInterface가 없는 경우 보조)
        val fileName = path.substringAfterLast('/')
        if (!fileName.contains("Impl") &&
            !fileName.contains("Controller") &&
            !fileName.contains("Util") &&
            (fileName.endsWith("Service.java") || fileName.endsWith("Dao.java"))) {
            logger.info("인터페이스 패턴 제외: $path")
            return false
        }

        // 3. 단순 DTO 제외
        if (fileNode != null) {
            val fileTypeStr = fileNode.fileType?.name ?: ""
            if (fileTypeStr.contains("DTO", ignoreCase = true) ||
                fileTypeStr.contains("ENTITY", ignoreCase = true)) {
                // DTO/Entity라도 비즈니스 로직이 있으면 포함할 수 있으나,
                // Phase 2c에서는 보수적으로 제외
                logger.info("DTO/Entity 제외: $path")
                return false
            }
        }

        // 4. 신규 파일 중 Util은 포함
        if (targetFile.type.contains("신규")) {
            val isUtil = path.lowercase().contains("util") || path.lowercase().contains("common")
            if (!isUtil) {
                // 신규 DTO 등은 제외
                val isServiceOrController = path.lowercase().let {
                    it.contains("service") || it.contains("controller")
                }
                return isServiceOrController
            }
        }

        // 5. 경로에 dto 가 있으면 보수적으로 제외
        if (path.contains("/dto/")) {
            logger.info("DTO 경로 제외: $path")
            return false
        }

        return true
    }

    // ──────────────────────────────────────────────
    // 컨텍스트 로드
    // ──────────────────────────────────────────────

    /**
     * 프로덕션 코드 컨텍스트를 로드.
     * /implement 캐시에 생성된 스니펫이 있으면 우선 사용,
     * 없으면 원본 파일을 PSI 또는 전문으로 로드.
     */
    private fun loadProductionContext(
        targetFile: TargetFileSpec,
        cache: ImplementContext
    ): String {
        // 1순위: /implement에서 생성된 코드 스니펫
        val generatedSnippet = cache.generatedSnippets[targetFile.path]
        if (!generatedSnippet.isNullOrBlank()) {
            return "/* === /implement에서 생성된 코드 === */\n$generatedSnippet"
        }

        // 2순위: 원본 파일 로드 (대형 파일이면 스켈레톤)
        return if (psiMethodExtractor.isLargeFile(targetFile.path)) {
            val skeleton = psiMethodExtractor.extract(
                targetFile.path, targetFile.description, targetFile.type
            )
            skeleton?.toPromptText() ?: "// 스켈레톤 추출 실패: ${targetFile.path}"
        } else {
            readFileContent(targetFile.path) ?: "// 파일 읽기 실패: ${targetFile.path}"
        }
    }

    /**
     * contextChain에서 특정 파일에 해당하는 MODIFIED_SIGNATURES를 추출.
     */
    private fun extractSignaturesForFile(filePath: String, contextChain: List<String>): String {
        val fileName = filePath.substringAfterLast('/')
        return contextChain
            .filter { it.contains(fileName, ignoreCase = true) }
            .joinToString("\n")
            .ifBlank { "// 변경 시그니처 정보 없음" }
    }

    /**
     * project-graph.json에서 ChangeRisk를 조회.
     */
    private fun resolveChangeRisk(filePath: String): String {
        val graph = graphLoader?.loadGraph() ?: return "NOT_CALCULATED"
        return graph.files[filePath]?.changeRisk?.name ?: "NOT_CALCULATED"
    }

    /**
     * 파일 전체 내용 읽기.
     */
    private fun readFileContent(filePath: String): String? {
        val absolutePath = "${project.basePath}/$filePath".replace("//", "/")
        val virtualFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .findFileByPath(absolutePath) ?: return null
        return String(virtualFile.contentsToByteArray(), Charsets.UTF_8)
    }

    // ──────────────────────────────────────────────
    // 프롬프트 구성
    // ──────────────────────────────────────────────

    /**
     * 테스트 전용 System Prompt.
     * ChangeRisk에 따라 테스트 강도를 차등 적용.
     */
    internal fun buildSystemPrompt(target: TestTarget): String {
        val riskInstruction = when (target.changeRisk) {
            "HIGH", "CRITICAL" -> """
                ⚠️ 이 클래스는 ChangeRisk가 ${target.changeRisk}인 고위험 클래스입니다.
                반드시 다음을 포함하세요:
                - Happy path 테스트 (정상 동작)
                - Edge case 테스트 (null 입력, 빈 리스트, 빈 문자열)
                - 예외 발생 테스트 (잘못된 파라미터, DB 오류 시뮬레이션)
                - 경계값 테스트 (0건, 1건, 대량 데이터)
            """.trimIndent()
            else -> """
                이 클래스는 일반적인 위험 수준입니다.
                Happy path 테스트를 중심으로 작성하되, 명백한 Edge case가 있다면 추가하세요.
            """.trimIndent()
        }

        val existingTestInstruction = if (target.testFileInfo.exists) {
            """
                기존 테스트 클래스(${target.testFileInfo.testClassName})가 이미 존재합니다.
                기존 테스트 메서드와 중복되지 않도록, 새로 추가/수정된 메서드에 대한 
                테스트 메서드만 작성하세요.
                @BeforeEach setUp 메서드는 기존 것을 재사용한다고 가정하고,
                필요한 추가 Mock 설정만 별도로 명시하세요.
            """.trimIndent()
        } else {
            """
                테스트 클래스가 존재하지 않으므로 새로 생성합니다.
                완전한 테스트 클래스 구조를 작성하세요:
                - package 선언
                - 필요한 import문 전체
                - 클래스 선언 (@ExtendWith(MockitoExtension.class))
                - @Mock 필드 (대상 클래스의 의존성)
                - @InjectMocks 필드 (테스트 대상 클래스)
                - @BeforeEach setUp() 메서드
                - 테스트 메서드들
            """.trimIndent()
        }

        return """
            당신은 Spring Boot 프로젝트의 시니어 테스트 엔지니어입니다.
            주어진 프로덕션 코드를 분석하여 단위 테스트 코드를 작성합니다.
            
            ## 테스트 프레임워크
            - JUnit 5 (org.junit.jupiter)
            - Mockito (org.mockito)
            - AssertJ (org.assertj.core.api.Assertions)
            - 필요 시: @WebMvcTest (Controller 테스트), @SpringBootTest (통합 테스트)
            
            ## 코드 작성 규칙
            1. **Given-When-Then** 패턴을 반드시 사용하세요. 각 섹션을 주석으로 구분합니다:
               // given
               // when  
               // then
            2. 테스트 메서드명은 한국어로 작성하세요: @Test void 설문결과_Excel_다운로드_성공()
            3. @Mock 대상은 프로덕션 클래스가 주입받는 의존성(필드)을 기반으로 정확히 설정하세요.
            4. verify()로 핵심 메서드 호출 여부를 검증하세요.
            5. 각 테스트 메서드 위에 // 📍 대상 메서드: {메서드명} 주석을 포함하세요.
            
            ## 출력 형식
            - 코드 블록 최상단에 // 📍 대상 테스트 클래스: ${target.testFileInfo.testClassName} 주석을 포함하세요.
            - 마크다운 java 코드 블록으로 감싸세요.
            - 코드 블록 이후에 [TEST_SUMMARY] 태그로 생성된 테스트 메서드 목록을 나열하세요.
            
            $existingTestInstruction
            
            $riskInstruction
        """.trimIndent()
    }

    /**
     * 테스트 User Prompt.
     * 프로덕션 코드 + 변경 시그니처 + 작업 설명을 조합.
     */
    internal fun buildUserPrompt(target: TestTarget): String = buildString {
        appendLine("## 테스트 대상 정보")
        appendLine("- **파일**: ${target.targetFile.path}")
        appendLine("- **테스트 클래스**: ${target.testFileInfo.testClassName}")
        appendLine("- **작업 내용**: ${target.targetFile.description}")
        appendLine("- **ChangeRisk**: ${target.changeRisk}")
        appendLine("- **테스트 파일 존재**: ${if (target.testFileInfo.exists) "예 (기존 파일에 추가)" else "아니오 (신규 생성)"}")
        appendLine()

        if (target.modifiedSignatures.isNotBlank() &&
            target.modifiedSignatures != "// 변경 시그니처 정보 없음") {
            appendLine("## 변경/추가된 메서드 시그니처")
            appendLine("```")
            appendLine(target.modifiedSignatures)
            appendLine("```")
            appendLine()
        }

        appendLine("## 프로덕션 코드")
        appendLine("```java")
        appendLine(target.productionContext)
        appendLine("```")
    }
}
