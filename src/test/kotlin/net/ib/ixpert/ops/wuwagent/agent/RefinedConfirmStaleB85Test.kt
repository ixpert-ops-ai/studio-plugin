package net.ib.ixpert.ops.wuwagent.agent

import net.ib.ixpert.ops.wuwagent.agent.clarify.RefinedConfirmationDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B-85 1 F: 정제문 확인 카드 응답에 요청 식별자(rid + messageId)를 싣고, 지금 기다리는 요청과 다르면 무시한다(IDE·LLM 없음).
 */
class RefinedConfirmStaleB85Test {
    private val key = "proj1"

    @Test
    fun s1_aResponseWithTheCurrentRequestIdIsAccepted() {
        val r = RefinedConfirmationRegistry()
        val f = r.register(key, "180f0685:analyze_1")
        assertEquals("180f0685:analyze_1", r.pendingRequestId(key))
        assertTrue(r.completeIfCurrent(key, "180f0685:analyze_1", RefinedConfirmationDecision.Proceed))
        assertEquals(RefinedConfirmationDecision.Proceed, f.getNow(null))
        assertFalse("처리한 요청은 더 기다리지 않는다", r.isPending(key))
    }

    @Test
    fun s2_aResponseWithAnotherRequestIdIsIgnoredAndTheRequestKeepsWaiting() {
        val r = RefinedConfirmationRegistry()
        val f = r.register(key, "180f0685:analyze_2")
        assertFalse(r.completeIfCurrent(key, "180f0685:analyze_1", RefinedConfirmationDecision.Proceed))
        assertFalse("식별자가 없는 응답도 식별자가 있는 요청에는 무시된다", r.completeIfCurrent(key, null, RefinedConfirmationDecision.Cancel))
        assertFalse(f.isDone)
        assertTrue(r.isPending(key))
        // 맞는 응답은 그 뒤에도 처리된다
        assertTrue(r.completeIfCurrent(key, "180f0685:analyze_2", RefinedConfirmationDecision.ProceedEdited("고친 문장")))
        assertEquals(RefinedConfirmationDecision.ProceedEdited("고친 문장"), f.getNow(null))
    }

    @Test
    fun s3_aNewRequestSupersedesTheOldOneAndTheOldCardsResponseIsIgnored() {
        val r = RefinedConfirmationRegistry()
        val old = r.register(key, "rid:analyze_1")
        val new = r.register(key, "rid:analyze_2")
        assertEquals(RefinedConfirmationDecision.Superseded, old.getNow(null))
        // 이전 카드가 화면에 남아 눌려도 새 요청을 대신 끝내지 않는다
        assertFalse(r.completeIfCurrent(key, "rid:analyze_1", RefinedConfirmationDecision.Proceed))
        assertFalse(new.isDone)
        assertTrue(r.completeIfCurrent(key, "rid:analyze_2", RefinedConfirmationDecision.Proceed))
    }

    @Test
    fun s4_noPendingRequestMeansIgnored_andOldStyleRequestsDoNotCheckTheId() {
        val r = RefinedConfirmationRegistry()
        assertFalse("기다리는 요청이 없으면 무시", r.completeIfCurrent(key, "rid:analyze_1", RefinedConfirmationDecision.Proceed))
        // 식별자 없이 등록한 이전 형식은 어떤 응답이든 처리한다(이전 동작)
        val f = r.register(key)
        assertTrue(r.completeIfCurrent(key, "x", RefinedConfirmationDecision.Proceed))
        assertEquals(RefinedConfirmationDecision.Proceed, f.getNow(null))
        // 이전 방식의 complete·cancel·supersede도 그대로 동작한다
        val g = r.register(key)
        r.cancel(key)
        assertEquals(RefinedConfirmationDecision.Cancel, g.getNow(null))
        val h = r.register(key)
        r.supersede(key)
        assertEquals(RefinedConfirmationDecision.Superseded, h.getNow(null))
    }

    @Test
    fun s5_aTimedOutRequestIsRemovedOnlyIfItIsStillTheCurrentOne() {
        val r = RefinedConfirmationRegistry()
        val old = r.register(key, "rid:analyze_1")
        val decision = r.await(key, old, 0) // 0초: 바로 시간 초과
        assertEquals(RefinedConfirmationDecision.TimedOut, decision)
        assertFalse("시간 초과한 요청의 늦은 응답은 무시", r.completeIfCurrent(key, "rid:analyze_1", RefinedConfirmationDecision.Proceed))
        // 그 사이 새 요청이 등록돼 있었다면 오래된 요청의 정리가 새 요청을 지우지 않는다
        val newer = r.register(key, "rid:analyze_3")
        val orphan = java.util.concurrent.CompletableFuture<RefinedConfirmationDecision?>() // 등록된 적 없는(이미 대신 처리돼 지워진) 이전 요청의 대기
        assertEquals(RefinedConfirmationDecision.TimedOut, r.await(key, orphan, 0))
        assertTrue(r.isPending(key))
        assertEquals("rid:analyze_3", r.pendingRequestId(key))
        assertFalse(newer.isDone)
    }

    @Test
    fun s6_theRouterPassesTheRequestIdOnBothResponsesAndIntoTheBridge() {
        val router = File("src/main/kotlin/net/ib/ixpert/ops/wuwagent/action/WebviewActionRouter.kt").readText(Charsets.UTF_8)
        assertTrue(router.contains("completeConfirmation(projectId, requestId, editedText)"))
        assertTrue(router.contains("cancelConfirmation(projectId, requestId)"))
        assertTrue(router.contains("requestId = \"\${Integer.toHexString(intentBuild.intent.originalRequirement.hashCode())}:\$messageId\""))
    }
}
