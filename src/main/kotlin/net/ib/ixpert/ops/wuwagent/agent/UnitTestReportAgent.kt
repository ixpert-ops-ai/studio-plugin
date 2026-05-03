package net.ib.ixpert.ops.wuwagent.agent

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import net.ib.ixpert.ops.wuwagent.prompt.PromptManager
import net.ib.ixpert.ops.wuwagent.service.EditorContextService
import java.io.File

/**
 * 대상 클래스/메서드 코드를 받아 단위 테스트 리포트(요약 → 매트릭스 → 코드 → 설계 결정 → 실행 결과 → 보충)를
 * 생성하는 Agent. 시스템 프롬프트로 unit-test-report-prompt.md 를 사용하고,
 * LLM 응답에서 테스트 코드 블록을 추출하여 src/test/java(또는 kotlin) 아래에 파일로 저장한다.
 */
class UnitTestReportAgent : BaseAgent() {
    override fun execute(
        context: AgentContext,
        onSuccess: (String) -> Unit,
        onChunk: ((String) -> Unit)?,
        onError: (String) -> Unit
    ) {
        val editor = context.editor ?: run {
            onError("[상태 이상] 에디터 컨텍스트가 주어지지 않았습니다.")
            return
        }
        val extraction = EditorContextService.extractCodeWithScope(editor, context.project)
        if (extraction.code.isBlank()) {
            onError("[알림] 단위 테스트 리포트를 생성할 코드를 찾지 못했습니다. 파일을 열거나 코드를 선택해 주세요.")
            return
        }

        val sourceVFile = editor.virtualFile
        val fileName = sourceVFile?.name ?: "(unknown)"
        val scopeText = if (extraction.isSelection) "선택 영역" else "전체 파일"
        val userRequest = context.payloadText.trim()

        val userMessage = buildString {
            append("# 입력\n")
            append("- 대상 클래스 (FQCN): ${userRequest.ifBlank { "(첨부된 코드에서 식별)" }}\n")
            append("- 대상 메서드 (시그니처): ${userRequest.ifBlank { "(첨부된 코드에서 식별)" }}\n\n")
            append("[대상 코드 — $fileName ($scopeText)]\n")
            append("```\n")
            append(extraction.code)
            append("\n```\n")
        }

        callLlmStreamAsync(
            context.project,
            "iXpert AI Assistant: Generating Unit Test Report",
            PromptManager.loadPrompt("unit-test-report-prompt.md"),
            userMessage,
            onSuccess = { fullResponse ->
                val saveResult = saveTestFile(context.project, sourceVFile?.path, fullResponse)
                val finalContent = if (saveResult != null) "$fullResponse\n\n---\n\n$saveResult" else fullResponse
                onSuccess(finalContent)
            },
            onChunk = onChunk,
            onError = onError
        )
    }

    /**
     * LLM 응답에서 테스트 코드 블록을 추출하여 적절한 경로에 저장한다.
     * @return 저장 결과 메시지 (성공 / 실패), 추출 실패 시 null
     */
    private fun saveTestFile(project: Project, sourceFilePath: String?, response: String): String? {
        val (testCode, language) = extractTestCodeBlock(response) ?: run {
            logger.warn("UnitTestReportAgent: 테스트 코드 블록을 추출하지 못했습니다.")
            return "📁 **자동 저장 실패**: 응답에서 테스트 코드 블록을 찾지 못했습니다."
        }

        val testFile = resolveTestFilePath(project, sourceFilePath, testCode, language) ?: run {
            logger.warn("UnitTestReportAgent: 테스트 파일 경로 산출 실패")
            return "📁 **자동 저장 실패**: 저장 경로를 결정하지 못했습니다 (src/main/java 또는 src/main/kotlin 구조 필요)."
        }

        return try {
            testFile.parentFile?.mkdirs()
            testFile.writeText(testCode, Charsets.UTF_8)
            ApplicationManager.getApplication().invokeLater {
                LocalFileSystem.getInstance().refreshAndFindFileByIoFile(testFile)
            }
            logger.info("UnitTestReportAgent: 테스트 파일 저장 → ${testFile.absolutePath} (${testCode.length}자)")
            "📁 **단위 테스트 파일 저장 완료**: `${testFile.absolutePath}`"
        } catch (e: Exception) {
            logger.error("UnitTestReportAgent: 테스트 파일 저장 실패", e)
            "📁 **자동 저장 실패**: ${e.message}"
        }
    }

    /**
     * 응답 내 모든 코드 블록 중 `class XxxTest` 선언이 포함된 블록을 우선 선택.
     * 없으면 가장 긴 블록을 선택.
     * @return Pair(코드, 언어 힌트("java"/"kotlin"/null))
     */
    private fun extractTestCodeBlock(response: String): Pair<String, String?>? {
        val regex = Regex("```(\\w+)?\\n?([\\s\\S]*?)```")
        val matches = regex.findAll(response).toList()
        if (matches.isEmpty()) return null

        val testClassRegex = Regex("""class\s+\w+Test\b""")

        val preferred = matches.firstOrNull { testClassRegex.containsMatchIn(it.groupValues[2]) }
            ?: matches.maxByOrNull { it.groupValues[2].length }
            ?: return null

        val code = preferred.groupValues[2].trim()
        val lang = preferred.groupValues[1].lowercase().ifBlank { null }
        if (code.isBlank()) return null
        return code to lang
    }

    /**
     * 원본 파일 경로 기준으로 테스트 파일 경로를 산출한다.
     * - src/main/java/<pkg>/Foo.java   → src/test/java/<pkg>/FooTest.java
     * - src/main/kotlin/<pkg>/Foo.kt   → src/test/kotlin/<pkg>/FooTest.kt
     * - 응답 코드의 `package` 선언을 우선 사용 (불일치 시 그쪽을 따른다).
     * - 응답 코드의 `class XxxTest` 선언을 우선 사용 (없으면 원본 파일명 + Test).
     */
    private fun resolveTestFilePath(
        project: Project,
        sourceFilePath: String?,
        testCode: String,
        languageHint: String?
    ): File? {
        val basePath = project.basePath ?: return null

        val sourceIsKotlin = sourceFilePath?.endsWith(".kt") == true
        val isKotlin = languageHint == "kotlin" || (languageHint == null && sourceIsKotlin)
        val ext = if (isKotlin) "kt" else "java"
        val sourceRoot = if (isKotlin) "src/test/kotlin" else "src/test/java"

        // 1) 응답 코드 내 package 선언 추출
        val packageFromCode = Regex("""^\s*package\s+([\w.]+)\s*;?""", RegexOption.MULTILINE)
            .find(testCode)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }

        // 2) 응답 코드 내 class XxxTest 추출
        val classNameFromCode = Regex("""class\s+(\w+Test)\b""")
            .find(testCode)?.groupValues?.get(1)

        // 3) 원본 파일에서 패키지/클래스명 추출 (응답에 없을 때 fallback)
        val (fallbackPackage, fallbackClassName) = inferPackageAndClassFromSource(sourceFilePath)

        val pkg = packageFromCode ?: fallbackPackage ?: ""
        val className = classNameFromCode
            ?: fallbackClassName?.let { "${it}Test" }
            ?: return null

        val pkgDir = pkg.replace('.', '/')
        val relPath = listOf(sourceRoot, pkgDir, "$className.$ext")
            .filter { it.isNotBlank() }
            .joinToString("/")
        return File(basePath, relPath)
    }

    /**
     * 원본 소스 경로에서 패키지명과 클래스명을 추측.
     * src/main/(java|kotlin)/<pkg>/<Class>.<ext> 패턴을 찾는다.
     */
    private fun inferPackageAndClassFromSource(sourceFilePath: String?): Pair<String?, String?> {
        if (sourceFilePath.isNullOrBlank()) return null to null
        val normalized = sourceFilePath.replace('\\', '/')
        val pattern = Regex("""src/main/(?:java|kotlin)/(.+?)/(\w+)\.(java|kt)$""")
        val match = pattern.find(normalized) ?: return null to File(normalized).nameWithoutExtension.takeIf { it.isNotBlank() }
        val pkg = match.groupValues[1].replace('/', '.')
        val cls = match.groupValues[2]
        return pkg to cls
    }
}
