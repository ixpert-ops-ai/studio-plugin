package net.ib.ixpert.ops.wuwagent.service.metagraph

import com.intellij.psi.PsiJavaFile
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import org.junit.Test

class SpringAnnotationResolverLocalNameTest : LightJavaCodeInsightFixtureTestCase() {

    private val resolver = SpringAnnotationResolver()

    @Test
    fun testExplicitLocalNameAnnotation() {
        myFixture.addClass("package net.ib.annotation; public @interface LocalName { String value(); }")

        val javaCode = """
            package net.infobank.iss.survey;
            import net.ib.annotation.LocalName;
            
            @LocalName("설문 관리 서비스")
            public class SurveyService {
            }
        """.trimIndent()

        val psiFile = myFixture.configureByText("SurveyService.java", javaCode) as PsiJavaFile
        val psiClass = psiFile.classes.first()
        val node = resolver.resolve(psiClass, "src/main/java/net/infobank/iss/survey/SurveyService.java")

        assertEquals("설문 관리 서비스", node.localName)
    }

    @Test
    fun testCleanJavadocPhraseExtraction() {
        val javaCode = """
            package net.infobank.iss.batch;
            
            /**
             * 카카오 알림톡 채널 및 템플릿 정보 업데이트 배치
             * 매시각 정각 실행
             */
            public class AlimtalkTemplateBatchJob {
            }
        """.trimIndent()

        val psiFile = myFixture.configureByText("AlimtalkTemplateBatchJob.java", javaCode) as PsiJavaFile
        val psiClass = psiFile.classes.first()
        val node = resolver.resolve(psiClass, "src/main/java/net/infobank/iss/batch/AlimtalkTemplateBatchJob.java")

        assertEquals("카카오 알림톡 채널 및 템플릿 정보 업데이트 배치", node.localName)
    }

    @Test
    fun testSingleLineCommentExtraction() {
        val javaCode = """
            package net.infobank.iss.survey;
            
            // 설문 응답 결과 집계 서비스
            public class SurveyResultAggregator {
            }
        """.trimIndent()

        val psiFile = myFixture.configureByText("SurveyResultAggregator.java", javaCode) as PsiJavaFile
        val psiClass = psiFile.classes.first()
        val node = resolver.resolve(psiClass, "src/main/java/net/infobank/iss/survey/SurveyResultAggregator.java")

        assertEquals("설문 응답 결과 집계 서비스", node.localName)
    }

    @Test
    fun testMessyLongJavadocNoiseFiltering() {
        val javaCode = """
            package net.infobank.iss.common;
            
            /**
             * AES-256 암호화/복호화 유틸리티 클래스로서 MariaDB 호환 모드 사용 시 주의사항을 설명합니다.
             * block_encryption_mode = 'aes-256-ecb': encryptMariaDB(), decryptMariaDB() 사용 (32바이트 키)
             * String encrypted = aes256Cipher.encryptMariaDB("평문");
             * SELECT HEX(AES_ENCRYPT('평문데이터', UNHEX(SHA2('CARD_CIPHER_KEY', 256)))) AS encrypted_data;
             */
            public class AES256Cipher {
            }
        """.trimIndent()

        val psiFile = myFixture.configureByText("AES256Cipher.java", javaCode) as PsiJavaFile
        val psiClass = psiFile.classes.first()
        val node = resolver.resolve(psiClass, "src/main/java/net/infobank/iss/common/AES256Cipher.java")

        // Should filter out long/noisy description to prevent false positive matching
        assertNull("Messy long javadoc with >30 chars and boilerplate should be filtered out", node.localName)
    }

    @Test
    fun testBoilerplateFiltering() {
        val javaCode = """
            package net.infobank.iss.user;
            
            /**
             * @author 홍길동
             * @version 1.0
             * 2021년 05월 신규 작성된 사용자 관리 컨트롤러 클래스
             */
            public class UserController {
            }
        """.trimIndent()

        val psiFile = myFixture.configureByText("UserController.java", javaCode) as PsiJavaFile
        val psiClass = psiFile.classes.first()
        val node = resolver.resolve(psiClass, "src/main/java/net/infobank/iss/user/UserController.java")

        assertNull("Boilerplate comments with author/작성/클래스 should be filtered out", node.localName)
    }

    @Test
    fun testExtractionDeterminism() {
        val javaCode = """
            package net.infobank.iss.survey;
            
            /**
             * 고객 설문 발송 핸들러
             */
            public class SurveyDispatchHandler {
            }
        """.trimIndent()

        val psiFile = myFixture.configureByText("SurveyDispatchHandler.java", javaCode) as PsiJavaFile
        val psiClass = psiFile.classes.first()

        val node1 = resolver.resolve(psiClass, "src/main/java/net/infobank/iss/survey/SurveyDispatchHandler.java")
        val node2 = resolver.resolve(psiClass, "src/main/java/net/infobank/iss/survey/SurveyDispatchHandler.java")

        assertNotNull(node1.localName)
        assertEquals("Repeated resolution must produce identical localName", node1.localName, node2.localName)
        assertEquals("고객 설문 발송 핸들러", node1.localName)
    }
}
