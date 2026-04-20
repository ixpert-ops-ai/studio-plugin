package net.ib.ixpert.ops.wuwagent.service

import com.intellij.openapi.diagnostic.Logger
import org.w3c.dom.Element
import java.io.File
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

object TestExecutionService {
    private val logger = Logger.getInstance(TestExecutionService::class.java)

    enum class BuildTool { GRADLE, MAVEN, UNKNOWN }

    data class FailureDetail(val testName: String, val message: String)

    data class TestResult(
        val total: Int,
        val passed: Int,
        val failed: Int,
        val errors: Int,
        val durationMs: Long,
        val failures: List<FailureDetail>,
        val errorMessage: String? = null
    ) {
        fun toJson(): String {
            val failuresJson = failures.joinToString(",") { f ->
                """{"testName":"${f.testName.esc()}","message":"${f.message.esc()}"}"""
            }
            val errorPart = if (errorMessage != null) ""","errorMessage":"${errorMessage.esc()}"""" else ""
            return """{"total":$total,"passed":$passed,"failed":$failed,"errors":$errors,"durationMs":$durationMs,"failures":[$failuresJson]$errorPart}"""
        }

        private fun String.esc() = replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    fun detectBuildTool(projectBasePath: String): BuildTool = when {
        File("$projectBasePath/gradlew").exists() -> BuildTool.GRADLE
        File("$projectBasePath/pom.xml").exists()  -> BuildTool.MAVEN
        else                                        -> BuildTool.UNKNOWN
    }

    fun runTests(projectBasePath: String, testClassName: String, buildTool: BuildTool): TestResult {
        val command = when (buildTool) {
            BuildTool.GRADLE  -> listOf("./gradlew", "test", "--tests", "$testClassName*", "--rerun-tasks")
            // surefire 2.22.2 의 -Dtest=<Class> 는 JUnit5 @Nested 발견 실패 케이스가 있으므로 와일드카드 사용
            BuildTool.MAVEN   -> listOf("mvn", "test", "-Dtest=$testClassName*", "-q")
            BuildTool.UNKNOWN -> return TestResult(0, 0, 0, 0, 0, emptyList(), "빌드 도구를 감지할 수 없습니다.")
        }

        return try {
            logger.info("TestExecutionService: 실행 → ${command.joinToString(" ")}")
            val startTime = System.currentTimeMillis()

            val process = ProcessBuilder(command)
                .directory(File(projectBasePath))
                .redirectErrorStream(true)
                .start()

            val output = StringBuilder()
            val reader = process.inputStream.bufferedReader()
            val readerThread = Thread {
                try {
                    reader.forEachLine { line ->
                        synchronized(output) { output.appendLine(line) }
                    }
                } catch (_: Exception) { /* 프로세스 강제 종료 시 무시 */ }
            }
            readerThread.start()

            val finished = process.waitFor(120, TimeUnit.SECONDS)
            val durationMs = System.currentTimeMillis() - startTime

            if (!finished) {
                process.destroyForcibly()
                readerThread.join(2000)
                val tail = output.toString().lines().takeLast(30).joinToString("\n")
                return TestResult(0, 0, 0, 0, durationMs, emptyList(),
                    "테스트 실행 타임아웃 (120초 초과)\n--- 출력 일부 ---\n$tail")
            }

            readerThread.join(2000)
            val exitCode = process.exitValue()
            val fullOutput = output.toString()
            logger.info("TestExecutionService: 완료 (exit=$exitCode, ${durationMs}ms, outputLen=${fullOutput.length})")

            val result = parseJUnitXml(projectBasePath, buildTool, durationMs)
            // exitCode가 0이 아니고 XML도 비어있다면 컴파일 오류 등 → 출력 테일을 포함하여 반환
            if (exitCode != 0 && result.total == 0 && result.errorMessage != null) {
                val allLines = fullOutput.lines().filter { it.isNotBlank() }
                // 컴파일 오류 라인은 출력 앞부분에 위치하므로 전체에서 추출 (javac/Kotlin/IntelliJ 포맷 모두 커버)
                val errorKeywords = listOf(
                    "error:", "java:", "kotlin:",
                    "cannot find symbol", "cannot be applied", "incompatible types",
                    "argument lists differ", "Compilation failed", "COMPILATION ERROR",
                    "unresolved reference", "type mismatch", "has private access",
                    "required:", "found:", "reason:"
                )
                val fileLineRegex = Regex("""\.(java|kt):\d+""")
                val compileErrorLines = allLines.filter { line ->
                    errorKeywords.any { line.contains(it, ignoreCase = true) } ||
                    fileLineRegex.containsMatchIn(line)
                }.take(80).joinToString("\n")
                val tail = allLines.takeLast(40).joinToString("\n")
                logger.warn("TestExecutionService: 비정상 종료 → exit=$exitCode (컴파일 오류 라인=${compileErrorLines.lines().size}개)")
                val combined = buildString {
                    append(result.errorMessage)
                    if (compileErrorLines.isNotBlank()) append("\n--- 컴파일 오류 ---\n").append(compileErrorLines)
                    append("\n--- 빌드 출력 (마지막 40줄) ---\n").append(tail)
                }
                return result.copy(errorMessage = combined)
            }
            result
        } catch (e: Exception) {
            logger.error("TestExecutionService: 실행 오류", e)
            TestResult(0, 0, 0, 0, 0, emptyList(), "테스트 실행 중 오류: ${e.message}")
        }
    }

    private fun parseJUnitXml(projectBasePath: String, buildTool: BuildTool, durationMs: Long): TestResult {
        val xmlDir = when (buildTool) {
            BuildTool.GRADLE  -> File("$projectBasePath/build/test-results/test")
            BuildTool.MAVEN   -> File("$projectBasePath/target/surefire-reports")
            BuildTool.UNKNOWN -> return TestResult(0, 0, 0, 0, durationMs, emptyList(), "빌드 도구 미감지")
        }

        val xmlFiles = xmlDir.listFiles { f -> f.extension == "xml" }
        if (xmlFiles.isNullOrEmpty()) {
            return TestResult(0, 0, 0, 0, durationMs, emptyList(),
                "테스트 결과 XML 파일을 찾을 수 없습니다: ${xmlDir.path}")
        }

        var total = 0; var failed = 0; var errors = 0
        val failures = mutableListOf<FailureDetail>()
        val factory = DocumentBuilderFactory.newInstance()

        for (xmlFile in xmlFiles) {
            try {
                val doc = factory.newDocumentBuilder().parse(xmlFile)
                val suite = doc.documentElement
                total  += suite.getAttribute("tests").toIntOrNull()    ?: 0
                failed += suite.getAttribute("failures").toIntOrNull() ?: 0
                errors += suite.getAttribute("errors").toIntOrNull()   ?: 0

                val testCases = suite.getElementsByTagName("testcase")
                for (i in 0 until testCases.length) {
                    val tc = testCases.item(i) as Element
                    val name = tc.getAttribute("name")
                    val failNode = tc.getElementsByTagName("failure").item(0)
                        ?: tc.getElementsByTagName("error").item(0)
                    if (failNode != null) {
                        val msg = (failNode as Element).getAttribute("message")
                            .ifBlank { failNode.textContent.take(300) }
                        failures.add(FailureDetail(name, msg))
                    }
                }
            } catch (e: Exception) {
                logger.warn("TestExecutionService: XML 파싱 실패 → ${xmlFile.name}", e)
            }
        }

        return TestResult(total, total - failed - errors, failed, errors, durationMs, failures)
    }
}
