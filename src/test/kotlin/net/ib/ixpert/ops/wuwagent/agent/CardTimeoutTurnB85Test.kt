package net.ib.ixpert.ops.wuwagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B-85 2: 힌트 카드 시간 초과 안내(analyze_notice)에 turn 필드를 싣는다(IDE·LLM 없음).
 * 화면의 카드 payload turn과 같은 값이라 웹뷰 기록 줄의 "Turn N"과 일치한다.
 */
class CardTimeoutTurnB85Test {
    @Test
    fun t1_theNoticeCarriesTheTurnOnlyWhenItIsKnown() {
        assertEquals(mapOf("turn" to "2"), CardTimeoutNotice.metaFor(2))
        assertEquals(mapOf("turn" to "1"), CardTimeoutNotice.metaFor(1))
        assertEquals(emptyMap<String, String>(), CardTimeoutNotice.metaFor(null))
        assertEquals(emptyMap<String, String>(), CardTimeoutNotice.metaFor(0))
        assertEquals(emptyMap<String, String>(), CardTimeoutNotice.metaFor(-1))
    }

    @Test
    fun t2_onlyTheHintCardPassesATurnAndTheSameValueGoesToTheCardPayload() {
        val dir = "src/main/kotlin/net/ib/ixpert/ops/wuwagent/agent/"
        val l1 = File(dir + "L1ClarificationBridge.kt").readText(Charsets.UTF_8)
        assertTrue(l1.contains("CardTimeoutNotice.send(project, CardTimeoutNotice.L1, turn)"))
        assertTrue("카드 payload의 turn과 같은 값", l1.contains("\"turn\" to turn"))
        // 범위·Stage 3 카드는 Turn이 없다
        assertTrue(File(dir + "ScopeSelectionBridge.kt").readText(Charsets.UTF_8).contains("CardTimeoutNotice.send(project, CardTimeoutNotice.SCOPE)"))
        assertTrue(File(dir + "Stage3ConfirmationBridge.kt").readText(Charsets.UTF_8).contains("CardTimeoutNotice.send(project, CardTimeoutNotice.STAGE3)"))
        // 안내 문구 상수는 그대로다(웹뷰의 timeoutRecord가 이 문구로 기록을 만든다)
        assertEquals("응답이 없어 힌트 없이 진행했습니다.", CardTimeoutNotice.L1)
        // 메시지 전송에 메타 필드를 싣는다
        assertTrue(File(dir + "CardTimeoutNotice.kt").readText(Charsets.UTF_8).contains("metaFor(turn)"))
    }
}
