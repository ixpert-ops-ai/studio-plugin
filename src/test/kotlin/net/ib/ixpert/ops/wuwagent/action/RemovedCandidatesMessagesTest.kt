package net.ib.ixpert.ops.wuwagent.action

import com.google.gson.JsonParser
import net.ib.ixpert.ops.wuwagent.agent.RemovedCandidate
import net.ib.ixpert.ops.wuwagent.agent.RemovedSource
import net.ib.ixpert.ops.wuwagent.agent.RequirementAnalysisResult
import net.ib.ixpert.ops.wuwagent.agent.RestoreOutcome
import net.ib.ixpert.ops.wuwagent.agent.TargetFileSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B-58: 웹뷰로 보내는 빠진 후보 메시지(analyze_removed)와 되살리기 응답(analyze_restore)의 본문.
 * 순서는 백엔드 순서 그대로이고, 목록이 없거나 비면 메시지를 보내지 않으며, 필드가 없는 옛 결과도 그대로 동작한다.
 */
class RemovedCandidatesMessagesTest {

    private val list = listOf(
        RemovedCandidate("src/admin/AdminService.java", RemovedSource.USER_EXCLUDED, null),
        RemovedCandidate("src/org/OrganizationRepository.java", RemovedSource.STAGE3_EXPLICIT, 2, "조회 조건만 바뀌므로 수정 불필요"),
        RemovedCandidate("src/org/OrganizationDto.java", RemovedSource.STAGE3_IMPLICIT, 5)
    )

    // M1 직렬화: 순서 유지, source는 이름, stageRank와 reason이 없으면 생략
    @Test
    fun m1_payloadKeepsTheOrderAndFieldsOfTheCandidates() {
        val json = JsonParser.parseString(RemovedCandidatesMessages.analyzeRemovedPayload(list)!!).asJsonObject
        val arr = json.getAsJsonArray("candidates")
        assertEquals(list.map { it.path }, arr.map { it.asJsonObject.get("path").asString })
        assertEquals(listOf("USER_EXCLUDED", "STAGE3_EXPLICIT", "STAGE3_IMPLICIT"), arr.map { it.asJsonObject.get("source").asString })
        val first = arr[0].asJsonObject
        assertFalse("stageRank가 없으면 생략", first.has("stageRank"))
        assertFalse("reason이 없으면 생략", first.has("reason"))
        val second = arr[1].asJsonObject
        assertEquals(2, second.get("stageRank").asInt)
        assertEquals("조회 조건만 바뀌므로 수정 불필요", second.get("reason").asString)
    }

    // M2 목록이 없거나 비면 메시지를 보내지 않는다
    @Test
    fun m2_noMessageForAnEmptyOrMissingList() {
        assertNull(RemovedCandidatesMessages.analyzeRemovedPayload(null))
        assertNull(RemovedCandidatesMessages.analyzeRemovedPayload(emptyList()))
    }

    // M3 필드가 없는 옛 결과(JSON)를 읽어도 실패하지 않고 메시지도 없다
    @Test
    fun m3_oldResultWithoutTheFieldIsCompatible() {
        val old = """{"summary":"s","targetFiles":[{"order":1,"path":"a.java","type":"MODIFY","description":"d"}],"warnings":"","rawResponse":"r","suggestedNewFiles":[]}"""
        val parsed = com.google.gson.Gson().fromJson(old, RequirementAnalysisResult::class.java)
        assertNull(RemovedCandidatesMessages.analyzeRemovedPayload(parsed.removedCandidates))
        assertNull(RemovedCandidatesMessages.analyzeRemovedPayload(RequirementAnalysisResult("s", emptyList(), "", "r").removedCandidates))
    }

    // M4 되살리기 응답: 되살린 경로, 무시한 경로, 남은 목록(결과가 없으면 빈 목록)
    @Test
    fun m4_restoreReplyCarriesRestoredIgnoredAndTheRemainingList() {
        val result = RequirementAnalysisResult("s", listOf(TargetFileSpec(1, "a.java", "MODIFY", "d")), "", "r", removedCandidates = listOf(list[0], list[2]))
        val reply = JsonParser.parseString(RemovedCandidatesMessages.restoreReplyPayload(RestoreOutcome(listOf(list[1].path), listOf("x.java"), result))).asJsonObject
        assertEquals(listOf(list[1].path), reply.getAsJsonArray("restored").map { it.asString })
        assertEquals(listOf("x.java"), reply.getAsJsonArray("ignored").map { it.asString })
        assertEquals(listOf(list[0].path, list[2].path), reply.getAsJsonArray("removedCandidates").map { it.asJsonObject.get("path").asString })
        val none = JsonParser.parseString(RemovedCandidatesMessages.restoreReplyPayload(RestoreOutcome(emptyList(), listOf("x.java"), null))).asJsonObject
        assertEquals(0, none.getAsJsonArray("removedCandidates").size())
    }

    // M5 라우터: 스트리밍 종료 신호("chat") 뒤에 별도 메시지로 보내고, /restore는 requestId로 응답한다. 마크다운 조립 줄은 바뀌지 않는다.
    @Test
    fun m5_routerSendsTheListAfterTheStreamEndsAndRepliesWithTheRequestId() {
        val router = File("src/main/kotlin/net/ib/ixpert/ops/wuwagent/action/WebviewActionRouter.kt").readText()
        val analyze = router.substringAfter("private fun executeAnalyzePipeline(")
        val end = analyze.indexOf("""bridge.sendMessage("chat", "", messageId)""")
        val removed = analyze.indexOf("""bridge.sendMessage("analyze_removed", it, messageId)""")
        assertTrue("스트리밍 종료 신호가 있어야 한다", end >= 0)
        assertTrue("analyze_removed는 종료 신호 뒤에 보낸다", removed > end)
        assertTrue(router.contains("""payload["requestId"]"""))
        assertTrue(router.contains("""bridge.sendMessage("analyze_restore", RemovedCandidatesMessages.restoreReplyPayload(outcome), messageId)"""))
    }
}
