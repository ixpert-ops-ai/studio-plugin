package net.ib.ixpert.ops.wuwagent.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * B-86 c (i)(ii): 패키지 카드는 제출·건너뛰기·시간 초과·오류 어느 경우든 끝에서 내려가고(Stage 3 카드의 finally와 같은 방식), 힌트 카드는 시간 초과 분기에서 바로 내려간다.
 * IDE·웹뷰 없음: 끝에서 한 번 부르는 동작은 hideAfter로, 브리지가 그것을 쓰는지는 소스로 고정한다.
 */
class CardHideB86Test {
    private fun counter() = java.util.concurrent.atomic.AtomicInteger()

    @Test
    fun h1_hideIsCalledOnceWhenTheCardEndsByAnswerSkipTimeoutErrorOrCancel() = runBlocking {
        // 제출(값을 돌려줌)
        val submit = counter()
        assertEquals("선택", ScopeSelectionBridge.hideAfter({ submit.incrementAndGet() }) { "선택" })
        assertEquals(1, submit.get())
        // 건너뛰기·시간 초과(null)
        val skip = counter()
        assertNull(ScopeSelectionBridge.hideAfter({ skip.incrementAndGet() }) { null as String? })
        assertEquals(1, skip.get())
        // 오류: 예외는 그대로 위로 올라가고 hide는 한 번 불린다
        val error = counter()
        try {
            ScopeSelectionBridge.hideAfter({ error.incrementAndGet() }) { throw IllegalStateException("전송 실패") }
            fail("예외가 올라와야 한다")
        } catch (e: IllegalStateException) {
            assertEquals("전송 실패", e.message)
        }
        assertEquals(1, error.get())
        // 코루틴 취소(사용자가 분석을 취소)
        val cancelled = counter()
        val job = launch { ScopeSelectionBridge.hideAfter({ cancelled.incrementAndGet() }) { delay(60_000); "끝나지 않음" } }
        delay(50)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(1, cancelled.get())
    }

    @Test
    fun h2_aFailingHideDoesNotChangeTheResultOrTheError() = runBlocking {
        assertEquals("값", ScopeSelectionBridge.hideAfter({ throw IllegalStateException("화면 정리 실패") }) { "값" })
        try {
            ScopeSelectionBridge.hideAfter({ throw IllegalStateException("화면 정리 실패") }) { throw CancellationException("취소") }
            fail("취소 예외가 올라와야 한다")
        } catch (e: CancellationException) {
            assertEquals("취소", e.message)
        }
    }

    @Test
    fun h3_theBridgesUseIt() {
        val dir = "src/main/kotlin/net/ib/ixpert/ops/wuwagent/agent/"
        val scope = File(dir + "ScopeSelectionBridge.kt").readText(Charsets.UTF_8)
        // 패키지 카드: 요청 전체를 hideAfter로 감싸고, 내리는 메시지는 웹뷰가 이미 처리하는 scopeSelection/hideTree다
        assertTrue(scope.contains("return hideAfter({ sendHideTree(project) }) { requestScopeSelectionBody(project, tree, config) }"))
        assertTrue(scope.contains("sendMessage(\"scopeSelection/hideTree\", \"\", \"system\")"))
        assertTrue("본문은 hideAfter 바깥에서 직접 부르지 않는다", scope.lines().count { it.contains("requestScopeSelectionBody(") } == 2) // 정의 1 + 호출 1
        // 힌트 카드: 시간 초과 분기에서 안내를 보낸 뒤 바로 내린다
        val l1 = File(dir + "L1ClarificationBridge.kt").readText(Charsets.UTF_8)
        val timeoutBranch = l1.substringAfter("catch (e: java.util.concurrent.TimeoutException) {").substringBefore("} catch (e: Exception) {")
        assertTrue(timeoutBranch, timeoutBranch.indexOf("CardTimeoutNotice.send(project, CardTimeoutNotice.L1, turn)") in 0 until timeoutBranch.indexOf("hideClarification()"))
        // Stage 3 카드: 시간 초과 분기에서도 안내를 보낸 뒤 바로 내리고(hide 1회), 기존 finally의 hide는 그대로 둔다(두 번째 hide는 웹뷰에서 멱등)
        val s3 = File(dir + "Stage3ConfirmationBridge.kt").readText(Charsets.UTF_8)
        val s3Timeout = s3.substringAfter("catch (e: java.util.concurrent.TimeoutException) {").substringBefore("} catch (e: Exception) {")
        assertTrue(s3Timeout, s3Timeout.indexOf("CardTimeoutNotice.send(project, CardTimeoutNotice.STAGE3)") in 0 until s3Timeout.indexOf("hideConfirmation()"))
        assertEquals("시간 초과 분기에서 hide는 정확히 한 번", 1, Regex("hideConfirmation\\(\\)").findAll(s3Timeout).count())
        assertTrue(File(dir + "RequirementAnalysisPipeline.kt").readText(Charsets.UTF_8).contains("confirmationBridge.hideConfirmation()"))
    }
}
