package net.ib.ixpert.ops.wuwagent.agent

import com.intellij.openapi.application.ApplicationManager
import net.ib.ixpert.ops.wuwagent.prompt.PromptManager
import net.ib.ixpert.ops.wuwagent.service.BuildContextService
import net.ib.ixpert.ops.wuwagent.service.EditorContextService
import net.ib.ixpert.ops.wuwagent.service.TestExecutionService
import net.ib.ixpert.ops.wuwagent.service.TestFileService
import net.ib.ixpert.ops.wuwagent.service.TypeContextService
import net.ib.ixpert.ops.wuwagent.ui.bridge.JcefBridge

/** JUnit 테스트 코드를 자동 생성하고, 파일 저장 → 테스트 실행 → 결과 리포트 반영까지 수행하는 Agent.
 *
 *  @param targetMessageId LLM 응답이 흘러간 webview 메시지 ID. 지정하면 "4. 단위 테스트 실행 결과"
 *                         섹션을 같은 메시지에 합쳐 전송한다. null이면 별도 메시지 버블로 전송(폴백). */
class GenerateTestAgent(private val targetMessageId: String? = null) : BaseAgent() {
    override fun execute(
        context: AgentContext,
        onSuccess: (String) -> Unit,
        onChunk: ((String) -> Unit)?,
        onError: (String) -> Unit
    ) {
        val editor = context.editor ?: run {
            onError("[상태 이상] 에디터 컨텍스트가 주어지지 않았습니다."); return
        }
        val scope = EditorContextService.extractCodeWithScope(editor, context.project)
        val methodAtCaret = if (!scope.isSelection) {
            EditorContextService.extractMethodAtCaret(editor, context.project)
        } else null

        // 전체 파일 텍스트에서 패키지·import 추출 (선택/메서드 모드 모두 전체 파일 기준)
        val sourcePackage = extractSourcePackage(scope.code)
        val sourceImports = extractSourceImports(scope.code)

        // LLM에 전달할 코드: 메서드 모드이면 package + import 선언을 앞에 붙여 LLM이 정확한 패키지를 인식하게 함
        val code = when {
            scope.isSelection -> scope.code
            methodAtCaret != null -> buildString {
                if (sourcePackage != null) append("package $sourcePackage\n")
                if (sourceImports.isNotBlank()) append("$sourceImports\n")
                append("\n")
                append(methodAtCaret.methodText)
            }
            else -> scope.code
        }
        if (code.isBlank()) { onError("[알림] 분석할 코드를 도출하지 못했습니다."); return }

        val targetMethodName = methodAtCaret?.methodName

        val fileName = EditorContextService.extractFileName(editor)
        val ext = fileName.substringAfterLast('.', "")
        val langHint = when (ext.lowercase()) {
            "kt", "kts" -> "Kotlin"
            "java"       -> "Java"
            "ts", "tsx"  -> "TypeScript"
            "js", "jsx"  -> "JavaScript"
            "py"         -> "Python"
            "go"         -> "Go"
            "rs"         -> "Rust"
            "swift"      -> "Swift"
            "dart"       -> "Dart"
            else         -> ext.ifBlank { "Unknown" }
        }

        val basePath = context.project.basePath
        val buildContext = basePath?.let { BuildContextService.detect(it) }
        val envBlock = buildEnvBlock(basePath, buildContext)
        // ignoreableFields는 전체 파일 기준으로 추출 (메서드 모드에서 code가 잘려 있어도 정확히 추출)
        val ignoreableFields = extractIgnoreableFields(scope.code)
        val basePrompt = PromptManager.loadPromptWithVars(
            "generate_test_prompt.md", mapOf(
                "PROJECT_ENV" to envBlock,
                "IGNOREABLE_FIELDS" to ignoreableFields.ifBlank { "(없음)" }
            )
        )
        val typeContext = basePath?.let { TypeContextService.buildTypeContext(it, code) }.orEmpty()
        val systemPrompt = buildString {
            append(basePrompt)
            append("\n\n[Source File: $fileName]\n[Source Language: $langHint]")
            if (sourcePackage != null)
                append("\n[Source Package: $sourcePackage — 생성되는 테스트 클래스의 package 선언을 반드시 이 값으로 작성하세요.]")
            if (sourceImports.isNotBlank())
                append("\n[Source Imports — 아래 import 구문을 테스트 코드에 그대로 사용하세요. 동일한 클래스명이 다른 패키지에 존재하더라도 반드시 아래 패키지를 사용해야 합니다.]\n$sourceImports")
            if (targetMethodName != null)
                append("\n[대상 범위: 메서드 단위 — `$targetMethodName` 메서드에 대한 단위 테스트만 작성하세요. 클래스의 다른 메서드는 무시합니다.]")
            if (ignoreableFields.isNotBlank())
                append("\n[테스트에서 무시할 필드 (@Value/@Autowired 설정값): $ignoreableFields]")
            if (typeContext.isNotBlank()) append(typeContext)
        }
        logger.info("GenerateTestAgent: 환경 컨텍스트 주입 → ${envBlock.replace("\n", " | ")}")
        logger.info("GenerateTestAgent: 소스 패키지 → ${sourcePackage ?: "(감지 실패)"}")
        logger.info("GenerateTestAgent: 무시 필드 목록 → $ignoreableFields")
        logger.info("GenerateTestAgent: 타입 컨텍스트 주입 (길이=${typeContext.length})")

        val wrappedOnSuccess: (String) -> Unit = { llmResponse ->
            onSuccess(llmResponse)
            runPostGenerationPipeline(context, fileName, code, systemPrompt, llmResponse)
        }

        callLlmStreamAsync(context.project, "WuwAgent: Generating Tests",
            systemPrompt, code, wrappedOnSuccess, onChunk, onError)
    }

    private fun runPostGenerationPipeline(
        context: AgentContext,
        fileName: String,
        sourceCode: String,
        systemPrompt: String,
        llmResponse: String
    ) {
        logger.info("GenerateTestAgent: 후처리 파이프라인 시작 (응답 길이=${llmResponse.length})")

        val basePath = context.project.basePath ?: run {
            logger.warn("GenerateTestAgent: 프로젝트 basePath 없음 — 파이프라인 중단")
            return
        }
        val bridge = JcefBridge.getInstance(context.project)

        val testCode = extractCodeBlock(llmResponse)
        if (testCode.isBlank()) {
            logger.warn("GenerateTestAgent: LLM 응답에서 코드 블록을 추출하지 못함 — 파이프라인 중단")
            return
        }
        logger.info("GenerateTestAgent: 코드 블록 추출 성공 (길이=${testCode.length})")

        val buildTool = TestExecutionService.detectBuildTool(basePath)
        logger.info("GenerateTestAgent: 빌드 도구 감지 = $buildTool")

        // attachMode=true: LLM 응답(섹션 1~3)에 섹션 4를 이어 붙여 같은 버블에 전송
        // attachMode=false: 별도 메시지 버블로 전송 (폴백 — targetMessageId가 없을 때)
        val attachMode = targetMessageId != null
        val finalMsgId = targetMessageId ?: "testexec_${System.currentTimeMillis()}"

        if (buildTool == TestExecutionService.BuildTool.UNKNOWN) {
            val testFilePath = TestFileService.resolveTestFilePath(basePath, fileName, testCode)
            TestFileService.saveTestFile(testFilePath, testCode)
            logger.info("GenerateTestAgent: 테스트 파일 저장 (빌드 도구 없음) → $testFilePath")
            val skipMarkdown = "## 4. 단위 테스트 실행 결과\n\n빌드 도구(Gradle/Maven)를 감지하지 못해 자동 실행을 건너뛰었습니다."
            val payload = if (attachMode) "$llmResponse\n\n$skipMarkdown" else skipMarkdown
            ApplicationManager.getApplication().invokeLater {
                if (!attachMode) bridge.sendMessage("test_execution_start", "⚙️ 테스트 실행 준비 중...", finalMsgId)
                bridge.sendMessage("testExecutionResult", payload, finalMsgId)
            }
            return
        }

        // attachMode이면 별도의 진행 중 버블을 만들지 않는다 (UI상 잠시 대기 후 섹션 4가 같은 버블에 채워짐)
        if (!attachMode) {
            ApplicationManager.getApplication().invokeLater {
                bridge.sendMessage("test_execution_start", "⚙️ 테스트를 실행하고 있습니다...", finalMsgId)
            }
        }

        val testClassName = fileName.substringBeforeLast('.') + "Test"
        var currentResult = saveAndRun(basePath, fileName, testCode, testClassName, buildTool, attempt = 1)
        var currentCode = testCode
        var attempt = 1

        val maxRetries = 3
        while (isCompileFailure(currentResult) && attempt <= maxRetries && !TaskCancellationToken.isCancelled.get()) {
            logger.info("GenerateTestAgent: 컴파일 실패 감지 → 재시도 $attempt 실행")
            if (!attachMode) {
                ApplicationManager.getApplication().invokeLater {
                    bridge.sendMessage("test_execution_start", "🔁 컴파일 오류 감지 → 테스트를 자동 수정하고 있습니다... (시도 $attempt)", finalMsgId)
                }
            }
            val retryCode = retryGenerate(systemPrompt, sourceCode, currentCode, currentResult.errorMessage.orEmpty())
            if (retryCode.isNullOrBlank()) {
                logger.warn("GenerateTestAgent: 재시도 코드 추출 실패 — 루프 중단")
                break
            }
            if (TaskCancellationToken.isCancelled.get()) break
            currentCode = retryCode
            currentResult = saveAndRun(basePath, fileName, retryCode, testClassName, buildTool, attempt = attempt + 1)
            attempt++
        }

        if (TaskCancellationToken.isCancelled.get()) {
            logger.info("GenerateTestAgent: 사용자 취소 — 파이프라인 중단 (시도 $attempt 후)")
            ApplicationManager.getApplication().invokeLater {
                bridge.sendMessage("task_cancelled", "⛔ 테스트 생성이 취소되었습니다.", finalMsgId)
            }
            return
        }

        val finalResult = currentResult

        logger.info("GenerateTestAgent: 테스트 실행 최종 완료 (total=${finalResult.total}, passed=${finalResult.passed}, failed=${finalResult.failed}, errors=${finalResult.errors})")
        if (!finalResult.errorMessage.isNullOrBlank()) {
            logger.warn("GenerateTestAgent: 테스트 실행 오류 메시지 →\n${finalResult.errorMessage}")
        }

        val testCaseInfoMap = parseTestCaseInfo(currentCode)
        val reportMarkdown = formatResultAsMarkdown(finalResult, testClassName, testCaseInfoMap)
        val payload = if (attachMode) "$llmResponse\n\n$reportMarkdown" else reportMarkdown
        ApplicationManager.getApplication().invokeLater {
            bridge.sendMessage("testExecutionResult", payload, finalMsgId)
        }
    }

    /** 테스트 메서드명 → (소스 메서드명, 테스트 케이스 설명) 매핑.
     *  테스트 코드에서 `@Test` 메서드와 그 위의 `// <케이스 설명>` 주석을 파싱한다. */
    private data class TestCaseInfo(val sourceMethod: String, val description: String?)

    private fun parseTestCaseInfo(testCode: String): Map<String, TestCaseInfo> {
        val result = mutableMapOf<String, TestCaseInfo>()
        val lines = testCode.lines()
        // public [static] [<T>] void <name>(  ← 라인 시작
        val methodPattern = Regex("""^\s*public\s+(?:static\s+)?(?:<[^>]+>\s+)?void\s+(\w+)\s*\(""")
        for (i in lines.indices) {
            val match = methodPattern.find(lines[i]) ?: continue
            val testMethodName = match.groupValues[1]
            // 메서드명 컨벤션: `메서드_조건_기댓값` → 첫 `_` 앞 토큰이 소스 메서드명
            val sourceMethod = testMethodName.substringBefore('_', testMethodName)
            // 위쪽으로 올라가며 가장 가까운 `//` 주석 줄을 찾는다 (어노테이션/빈줄은 건너뜀)
            var description: String? = null
            var j = i - 1
            while (j >= 0) {
                val trimmed = lines[j].trim()
                if (trimmed.isEmpty() || trimmed.startsWith("@")) { j--; continue }
                if (trimmed.startsWith("//")) {
                    description = trimmed.removePrefix("//").trim().ifBlank { null }
                }
                break
            }
            result[testMethodName] = TestCaseInfo(sourceMethod, description)
        }
        return result
    }

    /** TestResult 를 Markdown 리포트로 변환 (대상 클래스/대상 메서드 → 케이스별 결과 → 전체 통계 → 실패 상세) */
    private fun formatResultAsMarkdown(
        r: TestExecutionService.TestResult,
        testClassName: String,
        caseInfo: Map<String, TestCaseInfo> = emptyMap()
    ): String {
        val sb = StringBuilder()
        val durationSec = String.format("%.2f", r.durationMs / 1000.0)

        // 소스 클래스명 = "FooTest" → "Foo"
        val sourceClassName = testClassName.removeSuffix("Test")
        // 대상 메서드 = 테스트 메서드명에서 추출한 소스 메서드명들의 distinct 목록
        val sourceMethods = caseInfo.values.map { it.sourceMethod }.distinct().filter { it.isNotBlank() }
        val sourceMethodsLabel = if (sourceMethods.isEmpty()) "—" else sourceMethods.joinToString(", ") { "`$it`" }

        sb.appendLine("## 4. 단위 테스트 실행 결과")
        sb.appendLine()
        sb.appendLine("**대상 클래스:** `$sourceClassName`  ")
        sb.appendLine("**대상 메서드:** $sourceMethodsLabel")
        sb.appendLine()

        if (r.cases.isNotEmpty()) {
            sb.appendLine("### 테스트 케이스별 결과")
            sb.appendLine()
            sb.appendLine("| 메서드 | 테스트 케이스 | 결과 |")
            sb.appendLine("|---|---|---|")
            r.cases.forEach { c ->
                val mark = when (c.status) {
                    TestExecutionService.CaseStatus.PASSED  -> "✅ 통과"
                    TestExecutionService.CaseStatus.FAILED  -> "❌ 실패"
                    TestExecutionService.CaseStatus.ERROR   -> "⚠️ 오류"
                    TestExecutionService.CaseStatus.SKIPPED -> "⏭ 건너뜀"
                }
                val description = caseInfo[c.name]?.description ?: "—"
                sb.appendLine("| `${c.name}` | $description | $mark |")
            }
            sb.appendLine()
        }

        sb.appendLine("### 전체 통계")
        sb.appendLine()
        sb.appendLine("| 전체 | 통과 | 실패 | 오류 | 소요 시간 |")
        sb.appendLine("|---|---|---|---|---|")
        sb.appendLine("| ${r.total} | ${r.passed} | ${r.failed} | ${r.errors} | ${durationSec}초 |")

        if (r.failures.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("### 실패/오류 상세")
            sb.appendLine()
            r.failures.forEach { f ->
                sb.appendLine("- **`${f.testName}`**")
                val msg = f.message.take(500).replace("\n", " ").trim()
                if (msg.isNotBlank()) sb.appendLine("  - $msg")
            }
        }

        if (!r.errorMessage.isNullOrBlank()) {
            sb.appendLine()
            sb.appendLine("### 실행 로그")
            sb.appendLine()
            sb.appendLine("```")
            sb.appendLine(r.errorMessage.take(1500))
            sb.appendLine("```")
        }
        return sb.toString()
    }

    private fun saveAndRun(
        basePath: String,
        fileName: String,
        testCode: String,
        testClassName: String,
        buildTool: TestExecutionService.BuildTool,
        attempt: Int
    ): TestExecutionService.TestResult {
        val testFilePath = TestFileService.resolveTestFilePath(basePath, fileName, testCode)
        TestFileService.saveTestFile(testFilePath, testCode)
        // testCode의 package 선언을 추출해 FQCN을 구성한다. 동일 simple-name이 다른 패키지에
        // 존재할 때 Maven Surefire가 모두 실행해 결과가 섞이는 것을 방지한다.
        val testPackage = extractSourcePackage(testCode)
        val qualifiedName = if (testPackage != null) "$testPackage.$testClassName" else testClassName
        logger.info("GenerateTestAgent: [시도 $attempt] 테스트 파일 저장 → $testFilePath (FQCN=$qualifiedName)")
        return TestExecutionService.runTests(basePath, qualifiedName, buildTool)
    }

    /** 컴파일 에러 여부 판정: XML 결과 없음 + errorMessage에 컴파일 오류 키워드 포함 */
    private fun isCompileFailure(result: TestExecutionService.TestResult): Boolean {
        val msg = result.errorMessage ?: return false
        if (result.total != 0) return false
        // javac / Kotlin / IntelliJ / Gradle 각 컴파일러가 내보내는 대표 패턴을 모두 커버
        val patterns = listOf(
            "COMPILATION ERROR", "compilation failure", "Compilation failed",
            "cannot find symbol", "cannot be applied", "incompatible types",
            "argument lists differ", "does not exist", "has private access",
            "is not abstract", "reference to .* is ambiguous",
            "error:", "java:", "kotlin:",
            "compileJava FAILED", "compileTestJava FAILED",
            "compileKotlin FAILED", "compileTestKotlin FAILED",
            "unresolved reference", "type mismatch"
        )
        val matched = patterns.any { pattern ->
            if (pattern.contains(".*")) Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(msg)
            else msg.contains(pattern, ignoreCase = true)
        }
        // 파일:라인번호 패턴 (예: Foo.java:154:21) — 컴파일러 공통 출력
        val fileLinePattern = Regex("""\.(java|kt):\d+(:\d+)?""").containsMatchIn(msg)
        return matched || fileLinePattern
    }

    /** 컴파일 오류를 피드백하여 LLM에 재생성 요청 (동기 호출, UI 스트리밍 없음) */
    private fun retryGenerate(
        originalSystemPrompt: String,
        sourceCode: String,
        failedTestCode: String,
        compileError: String
    ): String? {
        val retrySystemPrompt = originalSystemPrompt + "\n\n" +
            "[RETRY MODE — 직전 생성된 테스트가 컴파일에 실패했습니다. 아래 정보를 바탕으로 오류만 정확히 수정해 '전체 테스트 파일'을 다시 출력하세요.]\n" +
            "- 구조/스타일은 최대한 유지\n" +
            "- 컴파일 오류 메시지에 명시된 타입/메서드 시그니처를 우선 신뢰\n" +
            "- 코드 블록 하나만 출력 (설명/리포트 제외)"

        val userMessage = buildString {
            append("--- Original Source Code ---\n")
            append(sourceCode).append("\n\n")
            append("--- Previously Generated Test (compile failed) ---\n")
            append(failedTestCode).append("\n\n")
            append("--- Compilation Error (trim) ---\n")
            append(compileError.take(4000))
        }

        logger.info("GenerateTestAgent: 재시도 LLM 호출 시작 (userMsg 길이=${userMessage.length})")
        val response = try {
            ollamaClient.callChatApi(retrySystemPrompt, userMessage)
        } catch (e: Exception) {
            logger.error("GenerateTestAgent: 재시도 LLM 호출 실패", e); null
        } ?: return null

        val content = response.message?.content ?: return null
        if (content.startsWith("[Error]")) {
            logger.warn("GenerateTestAgent: 재시도 응답 에러 → $content")
            return null
        }
        val retryCode = extractCodeBlock(content)
        logger.info("GenerateTestAgent: 재시도 코드 추출 (길이=${retryCode.length})")
        return retryCode.ifBlank { null }
    }

    /**
     * 소스 코드에서 @Value / @Autowired(Properties 등 설정 타입) 필드명을 추출한다.
     * 테스트 클래스에 복사하지 말아야 할 필드 목록으로 프롬프트에 주입된다.
     */
    private fun extractIgnoreableFields(sourceCode: String): String {
        val valueFields = Regex(
            """@Value\s*\([^)]+\)\s*(?:private|protected|public)?\s*\S+\s+(\w+)\s*;""",
            RegexOption.MULTILINE
        ).findAll(sourceCode).map { it.groupValues[1] }

        // Properties/Environment 타입 필드도 무시 대상 (설정 주입 전용)
        val configFields = Regex(
            """@Autowired\s*(?:private|protected|public)?\s*(?:Properties|Environment)\s+(\w+)\s*;""",
            RegexOption.MULTILINE
        ).findAll(sourceCode).map { it.groupValues[1] }

        return (valueFields + configFields)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(", ")
    }

    /** 프로젝트 환경을 동적으로 탐지해 프롬프트용 마크다운 테이블을 반환한다. 탐지 실패 시 기본값 사용. */
    private fun buildEnvBlock(basePath: String?, buildContext: BuildContextService.BuildContext?): String {
        val buildTool = buildContext?.buildTool ?: "Maven"
        val javaVersion = basePath?.let { detectJavaVersion(it) } ?: "1.8"
        val junitVersion = basePath?.let { detectDependencyVersion(it, "junit") } ?: "4.13.2"
        val mockitoVersion = basePath?.let { detectDependencyVersion(it, "mockito-core") } ?: "2.28.2"
        val buildCmd = when (buildTool) {
            "Gradle" -> "Gradle (`./gradlew test`)"
            "Maven"  -> "Maven (`mvn test`)"
            else     -> "Maven (`mvn test`)"
        }
        val testDir = when (buildTool) {
            "Gradle" -> "`src/test/kotlin/` 또는 `src/test/java/` (소스와 동일 패키지)"
            else     -> "`src/test/java/` (소스와 동일 패키지)"
        }

        val sb = StringBuilder()
        sb.appendLine("| 항목 | 버전 |")
        sb.appendLine("|---|---|")
        sb.appendLine("| Java | $javaVersion |")
        if (buildContext?.springBootVersion != null) {
            sb.appendLine("| Spring Boot | ${buildContext.springBootVersion} |")
        }
        sb.appendLine("| JUnit | $junitVersion |")
        sb.appendLine("| Mockito | $mockitoVersion |")
        sb.appendLine("| 빌드 | $buildCmd |")
        sb.append("| 테스트 위치 | $testDir |")

        if (buildContext?.springBootVersion != null) {
            val major = buildContext.springBootVersion.substringBefore('.').toIntOrNull() ?: 0
            val minor = buildContext.springBootVersion.substringAfter('.', "").substringBefore('.').toIntOrNull() ?: 0
            val use34Plus = (major > 3) || (major == 3 && minor >= 4)
            sb.appendLine()
            if (use34Plus) {
                sb.append("\n> ⚠️ Spring Boot 3.4+: `@MockitoBean` (`org.springframework.test.context.bean.override.mockito`) 사용 — `@MockBean` 제거됨")
            } else {
                sb.append("\n> Spring Boot < 3.4: `@MockBean` (`org.springframework.boot.test.mock.mockito`) 사용")
            }
        }

        return sb.toString()
    }

    private fun detectJavaVersion(basePath: String): String? {
        return try {
            val pom = java.io.File("$basePath/pom.xml")
            if (!pom.exists()) return null
            val content = pom.readText()
            Regex("<java\\.version>\\s*([^<\\s]+)\\s*</java\\.version>").find(content)?.groupValues?.get(1)
                ?: Regex("<maven\\.compiler\\.source>\\s*([^<\\s]+)\\s*</maven\\.compiler\\.source>").find(content)?.groupValues?.get(1)
        } catch (e: Exception) {
            logger.warn("GenerateTestAgent: Java 버전 탐지 실패", e); null
        }
    }

    private fun detectDependencyVersion(basePath: String, artifactId: String): String? {
        return try {
            val pom = java.io.File("$basePath/pom.xml")
            if (!pom.exists()) return null
            val content = pom.readText()
            content.split("<dependency>").drop(1).firstNotNullOfOrNull { block ->
                val end = block.indexOf("</dependency>").takeIf { it >= 0 } ?: return@firstNotNullOfOrNull null
                val dep = block.substring(0, end)
                if (!dep.contains("<artifactId>$artifactId</artifactId>")) return@firstNotNullOfOrNull null
                Regex("<version>\\s*([^<\\s\$][^<]*)\\s*</version>").find(dep)?.groupValues?.get(1)
                    ?.takeIf { !it.startsWith("\${") }
            }
        } catch (e: Exception) {
            logger.warn("GenerateTestAgent: $artifactId 버전 탐지 실패", e); null
        }
    }

    /** 소스 파일 전체 텍스트에서 package 선언을 추출 */
    private fun extractSourcePackage(fullSourceCode: String): String? =
        Regex("^\\s*package\\s+([\\w.]+)\\s*;?", RegexOption.MULTILINE)
            .find(fullSourceCode)?.groupValues?.get(1)

    /** 소스 파일 전체 텍스트에서 import 선언 목록을 원문 그대로 추출 */
    private fun extractSourceImports(fullSourceCode: String): String =
        Regex("^\\s*import\\s+[\\w.*]+\\s*;?", RegexOption.MULTILINE)
            .findAll(fullSourceCode)
            .map { it.value.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")

    /** LLM 응답에서 가장 긴 코드 블록을 추출 (리포트에 예시 코드 블록이 섞여 있어도 실제 테스트 코드 우선) */
    private fun extractCodeBlock(response: String): String {
        val regex = Regex("```(?:\\w+)?\\s*\\n([\\s\\S]*?)```")
        return regex.findAll(response)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() }
            .maxByOrNull { it.length }
            ?: ""
    }
}
