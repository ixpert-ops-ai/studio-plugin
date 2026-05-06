package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem

/**
 * 프로덕션 소스 경로를 테스트 소스 경로로 변환하고,
 * 기존 테스트 파일 존재 여부를 확인하는 유틸리티.
 *
 * 지원 패턴:
 *   src/main/java/.../{ClassName}.java → src/test/java/.../{ClassName}Test.java
 *   src/main/kotlin/.../{ClassName}.kt → src/test/kotlin/.../{ClassName}Test.kt
 */
class TestFileMapper(private val project: Project) {

    private val logger = Logger.getInstance(TestFileMapper::class.java)

    companion object {
        /** 테스트 클래스명 후보 접미사 */
        val TEST_SUFFIXES = listOf("Test", "Tests", "Spec")

        /** 소스→테스트 디렉토리 매핑 규칙 */
        val PATH_MAPPINGS = listOf(
            "src/main/java/" to "src/test/java/",
            "src/main/kotlin/" to "src/test/kotlin/",
            "src/main/groovy/" to "src/test/groovy/"
        )
    }

    /**
     * 프로덕션 파일 경로로부터 테스트 파일 정보를 반환.
     *
     * @param productionFilePath 프로젝트 루트 기준 상대 경로
     *        예: "src/main/java/net/infobank/iss/survey/service/SurveyServiceImpl.java"
     * @return TestFileInfo 또는 null (매핑 불가 시)
     */
    fun resolve(productionFilePath: String): TestFileInfo? {
        val cleanPath = productionFilePath.replace("//", "/")
        // 1. 소스→테스트 디렉토리 치환
        val (sourcePrefix, testPrefix) = PATH_MAPPINGS.firstOrNull { (src, _) ->
            cleanPath.startsWith(src)
        } ?: run {
            logger.warn("매핑 규칙에 해당하지 않는 경로: $cleanPath")
            return null
        }

        val relativePath = cleanPath.removePrefix(sourcePrefix)

        // 2. 파일 확장자 분리
        val lastDot = relativePath.lastIndexOf('.')
        if (lastDot < 0) return null
        val pathWithoutExt = relativePath.substring(0, lastDot)
        val extension = relativePath.substring(lastDot)  // ".java" 또는 ".kt"

        // 3. 기존 테스트 파일 탐색 (Test, Tests, Spec 순서)
        for (suffix in TEST_SUFFIXES) {
            val candidatePath = "$testPrefix${pathWithoutExt}$suffix$extension"
            val absolutePath = "${project.basePath}/$candidatePath".replace("//", "/")
            val virtualFile = LocalFileSystem.getInstance().findFileByPath(absolutePath)

            if (virtualFile != null && virtualFile.exists()) {
                logger.info("기존 테스트 파일 발견: $candidatePath")
                return TestFileInfo(
                    testFilePath = candidatePath,
                    exists = true,
                    productionFilePath = cleanPath,
                    testClassName = extractClassName(pathWithoutExt) + suffix
                )
            }
        }

        // 4. 기존 파일 없음 → 기본 "Test" 접미사로 신규 경로 생성
        val defaultTestPath = "$testPrefix${pathWithoutExt}Test$extension"
        logger.info("테스트 파일 미존재, 신규 경로 제안: $defaultTestPath")
        return TestFileInfo(
            testFilePath = defaultTestPath,
            exists = false,
            productionFilePath = cleanPath,
            testClassName = extractClassName(pathWithoutExt) + "Test"
        )
    }

    /**
     * 경로에서 클래스명만 추출.
     * "net/infobank/iss/survey/service/SurveyServiceImpl" → "SurveyServiceImpl"
     */
    private fun extractClassName(pathWithoutExt: String): String {
        return pathWithoutExt.substringAfterLast('/')
    }
}

/**
 * 테스트 파일 매핑 결과.
 */
data class TestFileInfo(
    val testFilePath: String,         // 프로젝트 루트 기준 상대 경로
    val exists: Boolean,              // 이미 존재하는지 여부
    val productionFilePath: String,   // 원본 프로덕션 파일 경로
    val testClassName: String         // 예: "SurveyServiceImplTest"
)
