package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeedAmbiguityEvaluatorTest {

    @Test
    fun testScReturnEvaluatesToFalse_SingleBusinessPackageCluster() {
        // ScReturn 상위 105.0점 동점 3개 (Controller, Service, ServiceImpl)
        val candidates = listOf(
            mapOf(
                "className" to "ScReturnService",
                "path" to "src/main/java/com/samsungcardmall/api/bo/app/service/sc/delivery/ScReturnService.java",
                "matchScore" to 105.0
            ),
            mapOf(
                "className" to "ScReturnServiceImpl",
                "path" to "src/main/java/com/samsungcardmall/api/bo/app/service/sc/delivery/ScReturnServiceImpl.java",
                "matchScore" to 105.0
            ),
            mapOf(
                "className" to "ScReturnController",
                "path" to "src/main/java/com/samsungcardmall/api/bo/app/controller/sc/delivery/ScReturnController.java",
                "matchScore" to 105.0
            ),
            mapOf(
                "className" to "CounselService",
                "path" to "src/main/java/com/samsungcardmall/api/bo/app/service/cs/counsel/CounselService.java",
                "matchScore" to 75.0
            )
        )

        val isAmbiguous = SeedAmbiguityEvaluator.evaluate(candidates)
        assertFalse(isAmbiguous, "ScReturn은 상위 동점자 3개가 모두 동일 업무 패키지(samsungcardmall/delivery)이므로 모호성 FALSE여야 함")
    }

    @Test
    fun testChatEvaluatesToFalse_DominantSingleWinner() {
        // Chat 상위 단독 1위 (130.0 vs 75.0)
        val candidates = listOf(
            mapOf(
                "className" to "ChatWebSocketController",
                "path" to "member-market-api/src/main/java/com/membermarket/api/chat/ChatWebSocketController.java",
                "matchScore" to 130.0
            ),
            mapOf(
                "className" to "AdminService",
                "path" to "member-market-api/src/main/java/com/membermarket/api/admin/AdminService.java",
                "matchScore" to 75.0
            ),
            mapOf(
                "className" to "ChatService",
                "path" to "member-market-api/src/main/java/com/membermarket/api/chat/ChatService.java",
                "matchScore" to 55.0
            )
        )

        val isAmbiguous = SeedAmbiguityEvaluator.evaluate(candidates)
        assertFalse(isAmbiguous, "Chat은 단독 1위가 존재하므로 모호성 FALSE여야 함")
    }

    @Test
    fun testPDsbUseEvaluatesToTrue_MultiplePackagesTie() {
        // PDsbUse 상위 80.0점 동점 9개가 6개 업무 패키지에 분산된 현실 사례
        val candidates = listOf(
            mapOf(
                "className" to "PointMngtRequest",
                "path" to "src/main/java/com/samsungcardmall/api/bo/app/dto/request/cc/pointMngt/PointMngtRequest.java",
                "matchScore" to 80.0
            ),
            mapOf(
                "className" to "PointAssignRequest",
                "path" to "src/main/java/com/samsungcardmall/api/bo/app/dto/request/cc/pointAssign/PointAssignRequest.java",
                "matchScore" to 80.0
            ),
            mapOf(
                "className" to "WfrPointDlngServiceImpl",
                "path" to "src/main/java/com/samsungcardmall/api/bo/app/service/cc/point/WfrPointDlngServiceImpl.java",
                "matchScore" to 80.0
            ),
            mapOf(
                "className" to "PointAsnServiceImpl",
                "path" to "src/main/java/com/samsungcardmall/api/bo/app/service/cc/pointAssign/PointAsnServiceImpl.java",
                "matchScore" to 80.0
            ),
            mapOf(
                "className" to "PointReAsnServiceImpl",
                "path" to "src/main/java/com/samsungcardmall/api/bo/app/service/cc/pointAssign/PointReAsnServiceImpl.java",
                "matchScore" to 80.0
            ),
            mapOf(
                "className" to "PointAsnMngServiceImpl",
                "path" to "src/main/java/com/samsungcardmall/api/bo/app/service/cc/pointAssign/PointAsnMngServiceImpl.java",
                "matchScore" to 80.0
            ),
            mapOf(
                "className" to "SpcPointMngServiceImpl",
                "path" to "src/main/java/com/samsungcardmall/api/bo/app/service/cc/specialPoint/SpcPointMngServiceImpl.java",
                "matchScore" to 80.0
            ),
            mapOf(
                "className" to "PointServiceImpl",
                "path" to "src/main/java/com/samsungcardmall/api/bo/app/service/me/interfaces/PointServiceImpl.java",
                "matchScore" to 80.0
            ),
            mapOf(
                "className" to "PointAsnMngtServiceImpl",
                "path" to "src/main/java/com/samsungcardmall/api/bo/app/service/pt/pointAsnMngt/PointAsnMngtServiceImpl.java",
                "matchScore" to 80.0
            )
        )

        val isAmbiguous = SeedAmbiguityEvaluator.evaluate(candidates)
        assertTrue(isAmbiguous, "PDsbUse는 상위 80점 동점 9개가 복수 업무 패키지(cc/pointMngt, cc/pointAssign 등)로 분산되어 모호성 TRUE여야 함")
    }

    @Test
    fun testExtractBusinessPackageRobust_StripsLayerKeywords() {
        assertEquals("samsungcardmall/delivery", SeedAmbiguityEvaluator.extractBusinessPackageRobust("src/main/java/com/samsungcardmall/api/bo/app/controller/sc/delivery/ScReturnController.java"))
        assertEquals("samsungcardmall/delivery", SeedAmbiguityEvaluator.extractBusinessPackageRobust("src/main/java/com/samsungcardmall/api/bo/app/service/sc/delivery/ScReturnServiceImpl.java"))
        assertEquals("cc/pointMngt", SeedAmbiguityEvaluator.extractBusinessPackageRobust("src/main/java/com/samsungcardmall/api/bo/app/dto/request/cc/pointMngt/PointMngtRequest.java"))
        assertEquals("membermarket/chat", SeedAmbiguityEvaluator.extractBusinessPackageRobust("member-market-api/src/main/java/com/membermarket/api/chat/ChatWebSocketController.java"))
    }
}
