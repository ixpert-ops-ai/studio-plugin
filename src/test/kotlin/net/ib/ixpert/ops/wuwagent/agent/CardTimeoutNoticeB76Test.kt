package net.ib.ixpert.ops.wuwagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** B-76 1: 확인 카드가 타임아웃으로 넘어갈 때 안내를 보낸다(Stage 3, 범위 선택, 힌트). 사용자의 건너뛰기에는 안내가 없다. */
class CardTimeoutNoticeB76Test {
    private val base = "src/main/kotlin/net/ib/ixpert/ops/wuwagent/"
    private fun src(rel: String) = File(base + rel).readText(Charsets.UTF_8)

    @Test
    fun t1_안내_문구() {
        assertEquals("응답이 없어 전체 후보로 진행했습니다.", CardTimeoutNotice.STAGE3)
        assertEquals("응답이 없어 전체 프로젝트로 진행했습니다.", CardTimeoutNotice.SCOPE)
        assertEquals("응답이 없어 힌트 없이 진행했습니다.", CardTimeoutNotice.L1)
    }

    @Test
    fun t2_프로젝트가_없으면_아무_일도_없다() {
        CardTimeoutNotice.send(null, CardTimeoutNotice.STAGE3)
    }

    @Test
    fun t3_타임아웃_분기에서만_안내를_보낸다() {
        val stage3 = src("agent/Stage3ConfirmationBridge.kt")
        val timeoutBranch = stage3.substringAfter("catch (e: java.util.concurrent.TimeoutException) {").substringBefore("} catch (e: Exception)")
        assertTrue(timeoutBranch.contains("CardTimeoutNotice.send(project, CardTimeoutNotice.STAGE3)"))
        val l1 = src("agent/L1ClarificationBridge.kt")
        assertTrue(l1.substringAfter("catch (e: java.util.concurrent.TimeoutException) {").substringBefore("} catch (e: Exception)").contains("CardTimeoutNotice.L1"))
        // 범위 선택: 건너뛰기(곧바로 null)는 안내하지 않고 5분을 채운 null만 안내한다
        val scope = src("agent/ScopeSelectionBridge.kt")
        assertTrue(scope.contains("result == null && System.currentTimeMillis() - startedMs >= 5 * 60 * 1000L - 1000L"))
        assertTrue(scope.contains("CardTimeoutNotice.send(project, CardTimeoutNotice.SCOPE)"))
        // 건너뛰기(cancel) 경로에는 안내가 없다
        assertTrue(!stage3.substringAfter("fun completeConfirmation").substringBefore("override fun requestConfirmation").contains("CardTimeoutNotice"))
    }

    @Test
    fun t4_웹뷰_진단_로그_분기가_있다() {
        val router = src("action/WebviewActionRouter.kt")
        assertTrue(router.contains("\"/uiLog\" -> {") && router.contains("[WebviewUi] "))
    }
}
