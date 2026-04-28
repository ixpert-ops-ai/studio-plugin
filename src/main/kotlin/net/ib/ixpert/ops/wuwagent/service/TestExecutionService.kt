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

    enum class CaseStatus { PASSED, FAILED, ERROR, SKIPPED }

    data class TestCaseResult(val name: String, val status: CaseStatus)

    data class TestResult(
        val total: Int,
        val passed: Int,
        val failed: Int,
        val errors: Int,
        val durationMs: Long,
        val failures: List<FailureDetail>,
        val errorMessage: String? = null,
        val cases: List<TestCaseResult> = emptyList()
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

    /**
     * @param testClassName FQCN(`com.app.FooTest`) 또는 simple name(`FooTest`).
     *                      가능한 경우 FQCN을 권장 — simple name으로는 동명 클래스가 여러 패키지에 있을 때
     *                      Maven Surefire가 모두 실행하여 결과가 섞일 수 있다.
     */
    fun runTests(projectBasePath: String, testClassName: String, buildTool: BuildTool): TestResult {
        // 이전 실행의 누적 XML이 다른 클래스 결과로 오인되는 것을 방지하기 위해
        // 빌드 명령 실행 전에 reports 디렉토리의 모든 .xml 파일을 비운다.
        cleanReportXmls(projectBasePath, buildTool)

        // JUnit 4 단독 환경이므로 와일드카드 없이 정확한 클래스명만 매칭한다.
        val command = when (buildTool) {
            BuildTool.GRADLE  -> listOf("./gradlew", "test", "--tests", testClassName, "--rerun-tasks")
            BuildTool.MAVEN   -> listOf("mvn", "test", "-Dtest=$testClassName", "-q")
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

            val result = parseJUnitXml(projectBasePath, buildTool, durationMs, testClassName)
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

    private fun reportsDir(projectBasePath: String, buildTool: BuildTool): File? = when (buildTool) {
        BuildTool.GRADLE  -> File("$projectBasePath/build/test-results/test")
        BuildTool.MAVEN   -> File("$projectBasePath/target/surefire-reports")
        BuildTool.UNKNOWN -> null
    }

    /** 빌드 명령 실행 전에 reports 디렉토리의 누적 XML을 모두 제거한다. */
    private fun cleanReportXmls(projectBasePath: String, buildTool: BuildTool) {
        val xmlDir = reportsDir(projectBasePath, buildTool) ?: return
        if (!xmlDir.exists()) return
        val files = xmlDir.listFiles { f -> f.extension == "xml" } ?: return
        var deleted = 0
        files.forEach { if (it.delete()) deleted++ }
        logger.info("TestExecutionService: 사전 XML 정리 (deleted=$deleted/${files.size}, dir=${xmlDir.path})")
    }

    private fun parseJUnitXml(
        projectBasePath: String,
        buildTool: BuildTool,
        durationMs: Long,
        testClassName: String
    ): TestResult {
        val xmlDir = reportsDir(projectBasePath, buildTool)
            ?: return TestResult(0, 0, 0, 0, durationMs, emptyList(), "빌드 도구 미감지")

        val allXmlFiles = xmlDir.listFiles { f -> f.extension == "xml" }
        if (allXmlFiles.isNullOrEmpty()) {
            return TestResult(0, 0, 0, 0, durationMs, emptyList(),
                "테스트 결과 XML 파일을 찾을 수 없습니다: ${xmlDir.path}")
        }

        // surefire/Gradle 모두 `TEST-<FQCN>.xml` 또는 `<FQCN>.xml` 형태로 작성한다.
        // testClassName이 FQCN(`.` 포함)이면 정확히 매칭하고, simple name이면 suffix 매칭한다.
        val isFqcn = '.' in testClassName
        val xmlFiles = allXmlFiles.filter { f ->
            val base = f.nameWithoutExtension
            if (isFqcn) {
                base == testClassName || base == "TEST-$testClassName"
            } else {
                base == testClassName ||
                    base.endsWith(".$testClassName") ||  // package 포함: TEST-com.example.FooTest
                    base.endsWith("-$testClassName")     // package 없음: TEST-FooTest
            }
        }
        if (xmlFiles.isEmpty()) {
            logger.warn("TestExecutionService: 대상 클래스 XML 미발견 (testClassName=$testClassName, dir=${xmlDir.path}, total=${allXmlFiles.size}, names=${allXmlFiles.joinToString { it.name }})")
            return TestResult(0, 0, 0, 0, durationMs, emptyList(),
                "대상 테스트 클래스 `$testClassName` 의 결과 XML을 찾을 수 없습니다.")
        }
        logger.info("TestExecutionService: XML 필터링 (testClassName=$testClassName, matched=${xmlFiles.size}/${allXmlFiles.size}, kept=${xmlFiles.joinToString { it.name }})")

        var total = 0; var failed = 0; var errors = 0; var skipped = 0
        val failures = mutableListOf<FailureDetail>()
        val cases = mutableListOf<TestCaseResult>()
        val factory = DocumentBuilderFactory.newInstance()

        for (xmlFile in xmlFiles) {
            try {
                val doc = factory.newDocumentBuilder().parse(xmlFile)
                val suite = doc.documentElement
                total   += suite.getAttribute("tests").toIntOrNull()    ?: 0
                failed  += suite.getAttribute("failures").toIntOrNull() ?: 0
                errors  += suite.getAttribute("errors").toIntOrNull()   ?: 0
                skipped += suite.getAttribute("skipped").toIntOrNull()  ?: 0

                val testCases = suite.getElementsByTagName("testcase")
                for (i in 0 until testCases.length) {
                    val tc = testCases.item(i) as Element
                    val name = tc.getAttribute("name")
                    val failureNode = tc.getElementsByTagName("failure").item(0)
                    val errorNode   = tc.getElementsByTagName("error").item(0)
                    val skippedNode = tc.getElementsByTagName("skipped").item(0)

                    val status = when {
                        failureNode != null -> CaseStatus.FAILED
                        errorNode   != null -> CaseStatus.ERROR
                        skippedNode != null -> CaseStatus.SKIPPED
                        else                -> CaseStatus.PASSED
                    }
                    cases.add(TestCaseResult(name, status))

                    val failOrErr = failureNode ?: errorNode
                    if (failOrErr != null) {
                        val msg = (failOrErr as Element).getAttribute("message")
                            .ifBlank { failOrErr.textContent.take(300) }
                        failures.add(FailureDetail(name, msg))
                    }
                }
            } catch (e: Exception) {
                logger.warn("TestExecutionService: XML 파싱 실패 → ${xmlFile.name}", e)
            }
        }

        val passed = total - failed - errors - skipped
        return TestResult(total, passed, failed, errors, durationMs, failures, null, cases)
    }
}
