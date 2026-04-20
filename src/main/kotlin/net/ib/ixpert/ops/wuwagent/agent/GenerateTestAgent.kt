package net.ib.ixpert.ops.wuwagent.agent

import com.intellij.openapi.application.ApplicationManager
import net.ib.ixpert.ops.wuwagent.prompt.PromptManager
import net.ib.ixpert.ops.wuwagent.service.BuildContextService
import net.ib.ixpert.ops.wuwagent.service.EditorContextService
import net.ib.ixpert.ops.wuwagent.service.TestExecutionService
import net.ib.ixpert.ops.wuwagent.service.TestFileService
import net.ib.ixpert.ops.wuwagent.service.TypeContextService
import net.ib.ixpert.ops.wuwagent.ui.bridge.JcefBridge

/** JUnit 테스트 코드를 자동 생성하고, 파일 저장 → 테스트 실행 → 결과 리포트 반영까지 수행하는 Agent */
class GenerateTestAgent : BaseAgent() {
    override fun execute(
        context: AgentContext,
        onSuccess: (String) -> Unit,
        onChunk: ((String) -> Unit)?,
        onError: (String) -> Unit
    ) {
        val editor = context.editor ?: run {
            onError("[상태 이상] 에디터 컨텍스트가 주어지지 않았습니다."); return
        }
        val code = EditorContextService.extractCode(editor, context.project)
        if (code.isBlank()) { onError("[알림] 분석할 코드를 도출하지 못했습니다."); return }

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
        val basePrompt = PromptManager.loadPrompt("generate_test_prompt.md")
        val buildHint = basePath?.let { BuildContextService.detect(it).toPromptHint() }.orEmpty()
        val typeContext = basePath?.let { TypeContextService.buildTypeContext(it, code) }.orEmpty()
        val systemPrompt = buildString {
            append(basePrompt)
            append("\n\n[Source File: $fileName]\n[Source Language: $langHint]")
            if (buildHint.isNotBlank()) append("\n").append(buildHint)
            if (typeContext.isNotBlank()) append(typeContext)
        }
        logger.info("GenerateTestAgent: 빌드 컨텍스트 주입 → ${buildHint.replace("\n", " | ")}")
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

        val execMsgId = "testexec_${System.currentTimeMillis()}"

        if (buildTool == TestExecutionService.BuildTool.UNKNOWN) {
            val testFilePath = TestFileService.resolveTestFilePath(basePath, fileName, testCode)
            TestFileService.saveTestFile(testFilePath, testCode)
            logger.info("GenerateTestAgent: 테스트 파일 저장 (빌드 도구 없음) → $testFilePath")
            val skipMarkdown = "## ⚡ 테스트 실행 결과\n\n빌드 도구(Gradle/Maven)를 감지하지 못해 자동 실행을 건너뛰었습니다."
            ApplicationManager.getApplication().invokeLater {
                bridge.sendMessage("test_execution_start", "⚙️ 테스트 실행 준비 중...", execMsgId)
                bridge.sendMessage("testExecutionResult", skipMarkdown, execMsgId)
            }
            return
        }

        ApplicationManager.getApplication().invokeLater {
            bridge.sendMessage("test_execution_start", "⚙️ 테스트를 실행하고 있습니다...", execMsgId)
        }

        val testClassName = fileName.substringBeforeLast('.') + "Test"
        var currentResult = saveAndRun(basePath, fileName, testCode, testClassName, buildTool, attempt = 1)
        var currentCode = testCode
        var attempt = 1

        while (isCompileFailure(currentResult) && !TaskCancellationToken.isCancelled.get()) {
            logger.info("GenerateTestAgent: 컴파일 실패 감지 → 재시도 $attempt 실행")
            ApplicationManager.getApplication().invokeLater {
                bridge.sendMessage("test_execution_start", "🔁 컴파일 오류 감지 → 테스트를 자동 수정하고 있습니다... (시도 $attempt)", execMsgId)
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
                bridge.sendMessage("task_cancelled", "⛔ 테스트 생성이 취소되었습니다.", execMsgId)
            }
            return
        }

        val finalResult = currentResult

        logger.info("GenerateTestAgent: 테스트 실행 최종 완료 (total=${finalResult.total}, passed=${finalResult.passed}, failed=${finalResult.failed}, errors=${finalResult.errors})")
        if (!finalResult.errorMessage.isNullOrBlank()) {
            logger.warn("GenerateTestAgent: 테스트 실행 오류 메시지 →\n${finalResult.errorMessage}")
        }

        val reportMarkdown = formatResultAsMarkdown(finalResult, testClassName)
        ApplicationManager.getApplication().invokeLater {
            bridge.sendMessage("testExecutionResult", reportMarkdown, execMsgId)
        }
    }

    /** TestResult 를 Markdown 리포트로 변환 (요약표 + 실패 상세 + 실행 오류) */
    private fun formatResultAsMarkdown(r: TestExecutionService.TestResult, testClassName: String): String {
        val sb = StringBuilder()
        val durationSec = String.format("%.2f", r.durationMs / 1000.0)
        val status = when {
            r.total == 0 && !r.errorMessage.isNullOrBlank() -> "⚠️ 실행 불가"
            r.failed == 0 && r.errors == 0 && r.total > 0   -> "✅ 전체 통과"
            else                                             -> "❌ 일부 실패"
        }

        sb.appendLine("## ⚡ 테스트 실행 결과")
        sb.appendLine()
        sb.appendLine("**대상 클래스:** `$testClassName`  ")
        sb.appendLine("**상태:** $status")
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
        logger.info("GenerateTestAgent: [시도 $attempt] 테스트 파일 저장 → $testFilePath")
        return TestExecutionService.runTests(basePath, testClassName, buildTool)
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
