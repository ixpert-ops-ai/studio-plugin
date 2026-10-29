package net.ib.ixpert.ops.wuwagent.agent

import net.ib.ixpert.ops.wuwagent.client.OpenAIClient
import net.ib.ixpert.ops.wuwagent.model.ChatChoice
import net.ib.ixpert.ops.wuwagent.model.ChatCompletionResponse
import net.ib.ixpert.ops.wuwagent.model.ChatMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** B-78 1: 분석마다 새 취소 범위를 쓰고, 취소로 생긴 빈 응답(chunks=0)은 일시 오류로 재시도하지 않는다. */
class CancelScopeB78Test {

    @After
    fun cleanUp() {
        TaskCancellationToken.reset()
    }

    @Test
    fun c1_분석이_없을_때_눌린_취소가_다음_분석에_남지_않는다() {
        // 2026-10-28 14:51:59: 분석이 없을 때 /cancel이 들어갔다
        TaskCancellationToken.cancel()
        assertTrue(TaskCancellationToken.isCancelled.get())

        val idle = TaskCancellationToken.isCancelled
        val scope = TaskCancellationToken.beginScope()

        assertFalse("새 분석은 취소 상태가 아니다", TaskCancellationToken.isCancelled.get())
        assertFalse(scope.flag.get())
        assertTrue("이전 범위의 플래그는 그대로 취소 상태다(되돌리지 않았다)", idle.get())
        assertNotSame(idle, TaskCancellationToken.isCancelled)
    }

    @Test
    fun c2_진행_중인_분석의_취소는_그_분석의_범위에서만_보이고_다음_분석에는_없다() {
        val first = TaskCancellationToken.beginScope()
        TaskCancellationToken.cancel()
        assertTrue(first.flag.get())
        assertTrue(TaskCancellationToken.isCancelled.get())

        val second = TaskCancellationToken.beginScope()
        assertTrue("첫 분석의 범위는 취소된 채 남는다", first.flag.get())
        assertFalse(second.flag.get())
        assertFalse(TaskCancellationToken.isCancelled.get())
    }

    private fun emptyResponse() = ChatCompletionResponse("x", listOf(ChatChoice(0, ChatMessage("assistant", null), "stop")))
    private fun contentResponse() = ChatCompletionResponse("x", listOf(ChatChoice(0, ChatMessage("assistant", "답"), "stop")))

    @Test
    fun c3_취소로_생긴_빈_응답은_재시도하지_않고_취소가_아닌_빈_응답만_한_번_재시도한다() {
        assertTrue("취소가 아닌 빈 응답은 재시도", OpenAIClient.shouldRetryEmpty(transientRetried = false, response = emptyResponse(), cancelled = false))
        assertFalse("취소로 끊긴 빈 응답(chunks=0)은 재시도하지 않는다", OpenAIClient.shouldRetryEmpty(false, emptyResponse(), cancelled = true))
        assertFalse("이미 재시도했으면 다시 하지 않는다", OpenAIClient.shouldRetryEmpty(true, emptyResponse(), cancelled = false))
        assertFalse("내용이 있는 응답은 재시도하지 않는다", OpenAIClient.shouldRetryEmpty(false, contentResponse(), cancelled = false))
        assertEquals(false, OpenAIClient.shouldRetryEmpty(false, null, cancelled = false))
    }
}
