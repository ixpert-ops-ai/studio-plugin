package net.ib.ixpert.ops.wuwagent.service

import com.intellij.openapi.diagnostic.Logger
import java.io.File

object BuildContextService {
    private val logger = Logger.getInstance(BuildContextService::class.java)

    data class BuildContext(
        val buildTool: String,         // "Maven" | "Gradle" | "Unknown"
        val springBootVersion: String? // 예: "3.4.2", "2.7.18"
    ) {
        /** LLM 시스템 프롬프트에 덧붙일 컨텍스트 문자열 */
        fun toPromptHint(): String {
            val sb = StringBuilder()
            sb.append("[Build Tool: $buildTool]\n")
            if (springBootVersion != null) {
                sb.append("[Spring Boot Version: $springBootVersion]\n")
                val major = springBootVersion.substringBefore('.').toIntOrNull() ?: 0
                val minor = springBootVersion.substringAfter('.', "")
                    .substringBefore('.').toIntOrNull() ?: 0
                val use34Plus = (major > 3) || (major == 3 && minor >= 4)
                if (use34Plus) {
                    sb.append("[Mock Annotation Rule: Use @MockitoBean from `org.springframework.test.context.bean.override.mockito.MockitoBean` — @MockBean is removed in Spring Boot 3.4+]\n")
                } else {
                    sb.append("[Mock Annotation Rule: Use @MockBean from `org.springframework.boot.test.mock.mockito.MockBean` — project is Spring Boot < 3.4. Do NOT use `org.springframework.boot.test.mock.bean` (that package does not exist).]\n")
                }
            }
            return sb.toString().trimEnd()
        }
    }

    fun detect(projectBasePath: String): BuildContext {
        val pom = File("$projectBasePath/pom.xml")
        if (pom.exists()) {
            return BuildContext("Maven", extractSpringBootVersionFromPom(pom))
        }
        val gradleKts = File("$projectBasePath/build.gradle.kts")
        val gradle = File("$projectBasePath/build.gradle")
        val gradleFile = when {
            gradleKts.exists() -> gradleKts
            gradle.exists()    -> gradle
            else               -> null
        }
        if (gradleFile != null) {
            return BuildContext("Gradle", extractSpringBootVersionFromGradle(gradleFile))
        }
        return BuildContext("Unknown", null)
    }

    /** Maven `pom.xml` 에서 Spring Boot 버전을 찾는다. 우선순위: parent > spring-boot-dependencies BOM > property */
    private fun extractSpringBootVersionFromPom(pom: File): String? {
        return try {
            val content = pom.readText()

            // 1) <parent> 블록 내부에 spring-boot-starter-parent
            Regex(
                "<parent>[\\s\\S]*?<artifactId>\\s*spring-boot-starter-parent\\s*</artifactId>[\\s\\S]*?<version>\\s*([^<\\s]+)\\s*</version>[\\s\\S]*?</parent>"
            ).find(content)?.groupValues?.getOrNull(1)?.let { return resolveVersion(it, content) }

            // 2) spring-boot-dependencies BOM (dependencyManagement)
            Regex(
                "<artifactId>\\s*spring-boot-dependencies\\s*</artifactId>\\s*<version>\\s*([^<\\s]+)\\s*</version>"
            ).find(content)?.groupValues?.getOrNull(1)?.let { return resolveVersion(it, content) }

            // 3) spring-boot.version property
            Regex("<spring-boot\\.version>\\s*([^<\\s]+)\\s*</spring-boot\\.version>")
                .find(content)?.groupValues?.getOrNull(1)?.let { return it }

            null
        } catch (e: Exception) {
            logger.warn("BuildContextService: pom.xml 파싱 실패", e); null
        }
    }

    /** `${...}` 플레이스홀더면 property 섹션에서 해석 */
    private fun resolveVersion(raw: String, content: String): String? {
        if (!raw.startsWith("\${")) return raw
        val propName = raw.removeSurrounding("\${", "}")
        val escaped = Regex.escape(propName)
        return Regex("<$escaped>\\s*([^<\\s]+)\\s*</$escaped>")
            .find(content)?.groupValues?.getOrNull(1)
    }

    /** Gradle 빌드 파일에서 Spring Boot 플러그인/의존성 버전을 추출 */
    private fun extractSpringBootVersionFromGradle(file: File): String? {
        return try {
            val content = file.readText()
            // id("org.springframework.boot") version "3.4.2"  또는  id 'org.springframework.boot' version '3.4.2'
            Regex("""org\.springframework\.boot["']\s*\)?\s*version\s*["']([^"']+)["']""")
                .find(content)?.groupValues?.getOrNull(1)?.let { return it }
            // classpath 'org.springframework.boot:spring-boot-gradle-plugin:3.4.2'
            Regex("""spring-boot-gradle-plugin:([^"':\s]+)""")
                .find(content)?.groupValues?.getOrNull(1)?.let { return it }
            // springBootVersion = "3.4.2"
            Regex("""springBootVersion\s*=\s*["']([^"']+)["']""")
                .find(content)?.groupValues?.getOrNull(1)
        } catch (e: Exception) {
            logger.warn("BuildContextService: gradle 파일 파싱 실패", e); null
        }
    }
}
