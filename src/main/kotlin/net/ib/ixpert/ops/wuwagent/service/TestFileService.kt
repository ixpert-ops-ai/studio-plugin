package net.ib.ixpert.ops.wuwagent.service

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

object TestFileService {
    private val logger = Logger.getInstance(TestFileService::class.java)

    fun resolveTestFilePath(basePath: String, sourceFileName: String, testCode: String? = null): String {
        val ext = sourceFileName.substringAfterLast('.', "")
        val nameWithoutExt = sourceFileName.substringBeforeLast('.')

        if (ext in listOf("ts", "tsx", "js", "jsx")) {
            return "$basePath/src/__tests__/${nameWithoutExt}.test.$ext"
        }

        val testSuffix = when (ext) {
            "kt", "kts" -> "Test.kt"
            "java"       -> "Test.java"
            "py"         -> "_test.py"
            "go"         -> "_test.go"
            else         -> "Test.$ext"
        }

        val baseDir = when (ext) {
            "kt", "kts" -> "$basePath/src/test/kotlin"
            "java"       -> "$basePath/src/test/java"
            "py"         -> "$basePath/tests"
            "go"         -> basePath
            else         -> "$basePath/src/test"
        }

        // JVM 계열: 테스트 코드에서 package 선언 추출 → 디렉토리로 변환
        val packageDir = if (ext in listOf("java", "kt", "kts") && testCode != null) {
            extractPackagePath(testCode)
        } else null

        val testDir = if (packageDir != null) "$baseDir/$packageDir" else baseDir
        return "$testDir/${nameWithoutExt}$testSuffix"
    }

    /** 소스 코드의 `package com.foo.bar;` 선언을 추출하여 `com/foo/bar` 경로로 변환 */
    private fun extractPackagePath(code: String): String? {
        val match = Regex("^\\s*package\\s+([\\w.]+)\\s*;?", RegexOption.MULTILINE).find(code)
        return match?.groupValues?.get(1)?.replace(".", "/")
    }

    fun saveTestFile(testFilePath: String, code: String): VirtualFile? {
        return try {
            val file = File(testFilePath)
            file.parentFile?.mkdirs()
            file.writeText(code)
            val vFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(testFilePath)
            logger.info("TestFileService: 파일 저장 완료 → $testFilePath")
            vFile
        } catch (e: Exception) {
            logger.error("TestFileService: 파일 저장 실패 → $testFilePath", e)
            null
        }
    }
}
