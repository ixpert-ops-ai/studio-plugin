package net.ib.ixpert.ops.wuwagent.agent.clarify

import com.google.gson.JsonParser
import net.ib.ixpert.ops.wuwagent.agent.RefinedConfirmationRegistry
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.ClarifyIntent
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.RetentionAudit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * 분석 시작 전 정제문 확인(RefinedRequirementGate). 라우터가 쓰는 확인 → 저장 → 분석 흐름([RefinedRequirementGate.handoff])을
 * 모의 저장/분석으로 검사한다(IDE, LLM 없음).
 * g1~g2 브리지 없음/결정 없음은 지금 동작과 같다, g3~g5 고친 문장이 Stage 1 입력과 저장 값에 들어간다,
 * g6~g7 취소, g8~g9 빈 문장·같은 문장, g10~g12 대기 시간 초과·예외, g13 식별자 보존 재계산.
 */
class RefinedRequirementGateTest {

    private val original = "설문 발송 채널에 브랜드메시지 추가"
    private val refined = "SurveyServiceImpl만 수정하여 설문 발송 채널에 브랜드메시지를 추가하세요."

    private fun intent(
        orig: String = original,
        raw: String = refined,
        statements: List<String> = listOf("SurveyServiceImpl만 수정할 거야")
    ): ClarifyIntent {
        val retention = IdentifierRetentionChecker.checkRetention(raw, orig, statements)
        return ClarifyIntent(
            originalRequirement = orig,
            refinedRequirement = retention.effectiveRefinedRequirement,
            userStatements = statements,
            graphHash = "dummyHash",
            retentionAudit = RetentionAudit(
                rawRefinedRequirement = retention.rawRefinedRequirement,
                expectedIdentifiers = retention.expectedIdentifiers.toList().sorted(),
                missingBeforeFix = retention.missingIdentifiers,
                wasRetainedWithoutModification = retention.wasRetainedWithoutModification,
                scopeModifierDropped = retention.scopeModifierDropped
            )
        )
    }

    /** 라우터와 같은 호출: Stage 1이 받는 요구사항(primaryReq) = AnalyzeInputResolver.resolve의 effectiveRequirement. */
    private fun stage1Input(i: ClarifyIntent): String =
        AnalyzeInputResolver.resolve(rawInput = i.originalRequirement, inMemoryIntent = i).effectiveRequirement

    private class Recorder {
        val saved = ArrayList<ClarifyIntent>()
        val analyzed = ArrayList<ClarifyIntent>()
        var cancelled = 0
        val errors = ArrayList<String>() // 취소 때 error 형식 메시지가 나가면 여기 쌓인다(나가지 않아야 한다)
    }

    private class FakeBridge(var decision: RefinedConfirmationDecision?, var throws: Boolean = false) : RefinedRequirementConfirmationBridge {
        var requested = 0
        var hidden = 0
        var shownOriginal: String? = null
        var shownRefined: String? = null
        override fun requestConfirmation(originalRequirement: String, refinedRequirement: String): RefinedConfirmationDecision? {
            requested++
            shownOriginal = originalRequirement
            shownRefined = refinedRequirement
            if (throws) throw IllegalStateException("전송 실패")
            return decision
        }
        override fun hideConfirmation() { hidden++ }
    }

    private fun run(i: ClarifyIntent, bridge: RefinedRequirementConfirmationBridge?): Recorder {
        val r = Recorder()
        RefinedRequirementGate.handoff(i, bridge, { r.saved.add(it) }, { r.analyzed.add(it) }, { r.cancelled++ })
        return r
    }

    private fun savedJson(i: ClarifyIntent): String {
        val dir = Files.createTempDirectory("gate-test").toFile()
        try {
            return ClarifyIntentStore.saveIntent(dir, i).readText(Charsets.UTF_8)
        } finally {
            dir.deleteRecursively()
        }
    }

    // ── 브리지가 없거나 결정이 없으면 지금과 같다 ──

    @Test
    fun g1_브리지가_없으면_같은_객체로_저장하고_분석한다_Stage1_입력과_저장_값이_같다() {
        val i = intent()
        val before = stage1Input(i)
        val beforeJson = savedJson(i)

        val r = run(i, null)

        assertEquals(1, r.saved.size); assertEquals(1, r.analyzed.size); assertEquals(0, r.cancelled)
        assertSame(i, r.saved[0]); assertSame(i, r.analyzed[0])
        assertEquals(before, stage1Input(r.analyzed[0]))
        assertEquals(beforeJson, savedJson(r.saved[0]))
    }

    @Test
    fun g2_결정이_없으면_브리지가_있어도_같은_객체로_진행한다() {
        val i = intent()
        val bridge = FakeBridge(null)
        val r = run(i, bridge)
        assertSame(i, r.analyzed[0]); assertSame(i, r.saved[0])
        assertEquals(1, bridge.requested); assertEquals(1, bridge.hidden)
        // "이대로 분석"도 같은 결과
        val r2 = run(i, FakeBridge(RefinedConfirmationDecision.Proceed))
        assertSame(i, r2.analyzed[0]); assertSame(i, r2.saved[0])
    }

    // ── 고친 문장 ──

    @Test
    fun g3_고친_문장이_Stage1_입력이_된다() {
        val i = intent()
        val edited = "SurveyServiceImpl만 수정하여 설문 발송 채널에 브랜드메시지를 추가하세요. 기존 알림톡 동작은 유지합니다."
        val r = run(i, FakeBridge(RefinedConfirmationDecision.ProceedEdited(edited)))

        assertEquals(1, r.analyzed.size)
        assertEquals(edited, stage1Input(r.analyzed[0]))
        assertNotSame(i, r.analyzed[0])
        // 원문, 발화, 제외 파일 등 나머지는 그대로
        assertEquals(i.originalRequirement, r.analyzed[0].originalRequirement)
        assertEquals(i.userStatements, r.analyzed[0].userStatements)
        assertEquals(i.excludedFiles, r.analyzed[0].excludedFiles)
        assertEquals(i.graphHash, r.analyzed[0].graphHash)
    }

    @Test
    fun g4_고친_문장이_같은_필드에_저장되고_저장_형식과_버전은_그대로다() {
        val i = intent()
        val edited = "SurveyServiceImpl만 수정하여 브랜드메시지 발송을 추가하세요."
        val r = run(i, FakeBridge(RefinedConfirmationDecision.ProceedEdited(edited)))

        val savedEdited = JsonParser.parseString(savedJson(r.saved[0])).asJsonObject
        val savedPlain = JsonParser.parseString(savedJson(i)).asJsonObject
        assertEquals(edited, savedEdited.get("refinedRequirement").asString)
        assertEquals(edited, savedEdited.getAsJsonObject("retentionAudit").get("rawRefinedRequirement").asString)
        assertEquals(savedPlain.keySet(), savedEdited.keySet())
        assertEquals(savedPlain.getAsJsonObject("retentionAudit").keySet(), savedEdited.getAsJsonObject("retentionAudit").keySet())
        assertEquals("1.2", savedEdited.get("contractVersion").asString)
        assertEquals(ClarifyIntent.CURRENT_INTENT_VERSION, r.saved[0].contractVersion)
        // 저장한 값과 분석에 넘긴 값은 같다
        assertSame(r.saved[0], r.analyzed[0])
    }

    @Test
    fun g5_카드에는_원문과_Stage1이_읽는_정제문이_보인다() {
        val i = intent()
        val bridge = FakeBridge(RefinedConfirmationDecision.Proceed)
        run(i, bridge)
        assertEquals(original, bridge.shownOriginal)
        assertEquals(i.retentionAudit!!.rawRefinedRequirement, bridge.shownRefined)
        assertEquals(RefinedRequirementGate.shownRefined(i), bridge.shownRefined)
    }

    // ── 취소 ──

    @Test
    fun g6_취소하면_저장도_분석도_없고_안내만_나간다() {
        val i = intent()
        val bridge = FakeBridge(RefinedConfirmationDecision.Cancel)
        val r = run(i, bridge)
        assertEquals(0, r.analyzed.size); assertEquals(0, r.saved.size)
        assertEquals(1, r.cancelled)
        assertTrue(r.errors.isEmpty())
        assertEquals(1, bridge.hidden)
    }

    @Test
    fun g7_취소는_결과_종류가_Cancelled이고_error_메시지_형식을_쓰는_코드가_없다() {
        assertSame(RefinedGateResult.Cancelled, RefinedRequirementGate.apply(intent(), FakeBridge(RefinedConfirmationDecision.Cancel)))
        // 라우터의 취소 안내는 analyze_notice이며 error가 아니다(소스 확인)
        val router = File("src/main/kotlin/net/ib/ixpert/ops/wuwagent/action/WebviewActionRouter.kt").readText(Charsets.UTF_8)
        val helper = router.substringAfter("private fun continueAfterClarify(").substringBefore("private fun executeAnalyzePipeline(")
        assertTrue(helper.contains("\"analyze_notice\", \"분석을 취소했습니다.\""))
        assertFalse(helper.contains("sendMessage(\"error\""))
    }

    // ── 빈 문장, 같은 문장 ──

    @Test
    fun g8_빈_문장으로는_고치지_않은_것으로_보고_원래_정제문으로_진행한다() {
        val i = intent()
        for (blank in listOf("", "   ", "\n\t ")) {
            val r = run(i, FakeBridge(RefinedConfirmationDecision.ProceedEdited(blank)))
            assertSame(i, r.analyzed[0])
            assertTrue(stage1Input(r.analyzed[0]).isNotBlank())
            assertEquals(1, r.saved.size)
        }
    }

    @Test
    fun g9_보여_준_문장과_같거나_공백만_다르면_같은_객체로_진행한다() {
        val i = intent()
        val shown = RefinedRequirementGate.shownRefined(i)
        assertSame(i, run(i, FakeBridge(RefinedConfirmationDecision.ProceedEdited(shown))).analyzed[0])
        assertSame(i, run(i, FakeBridge(RefinedConfirmationDecision.ProceedEdited("  $shown \n"))).analyzed[0])
    }

    // ── 대기 시간 초과와 예외 ──

    @Test
    fun g10_대기_시간을_넘기면_결정_없음이고_이후_응답은_무시된다() {
        val registry = RefinedConfirmationRegistry()
        val future = registry.register("p")
        val started = System.nanoTime()
        val decision = registry.await("p", future, 1)
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertNull(decision)
        assertTrue("1초 안팎에서 끝나야 한다: ${elapsedMs}ms", elapsedMs in 900..4000)
        registry.complete("p", RefinedConfirmationDecision.Cancel) // 늦은 응답은 아무 일도 없다(예외 없이 끝난다)
    }

    @Test
    fun g11_응답과_취소는_대기를_바로_끝낸다() {
        val registry = RefinedConfirmationRegistry()
        val f1 = registry.register("a")
        registry.complete("a", RefinedConfirmationDecision.ProceedEdited("고친 문장"))
        assertEquals(RefinedConfirmationDecision.ProceedEdited("고친 문장"), registry.await("a", f1, 5))
        val f2 = registry.register("b")
        registry.cancel("b")
        assertSame(RefinedConfirmationDecision.Cancel, registry.await("b", f2, 5))
        val f3 = registry.register("c")
        registry.complete("c", null)
        assertNull(registry.await("c", f3, 5))
    }

    @Test
    fun g12_브리지가_예외를_던지면_정제문을_그대로_쓰고_카드를_내린다() {
        val i = intent()
        val bridge = FakeBridge(null, throws = true)
        val r = run(i, bridge)
        assertSame(i, r.analyzed[0]); assertSame(i, r.saved[0])
        assertEquals(1, bridge.hidden)
    }

    // ── 식별자 보존 재계산 ──

    @Test
    fun g13_고친_문장에서_식별자가_빠지면_빠진_식별자_줄이_Stage1_입력에_붙는다() {
        val i = intent()
        assertTrue(i.retentionAudit!!.missingBeforeFix.isEmpty())
        val edited = "설문 발송 채널에 브랜드메시지를 추가하세요." // SurveyServiceImpl이 빠짐
        val r = run(i, FakeBridge(RefinedConfirmationDecision.ProceedEdited(edited)))
        val audit = r.analyzed[0].retentionAudit!!
        assertEquals(edited, audit.rawRefinedRequirement)
        assertEquals(listOf("SurveyServiceImpl"), audit.missingBeforeFix)
        assertEquals("$edited\n\nSurveyServiceImpl", stage1Input(r.analyzed[0]))
        // 식별자를 되살려 쓰면 다시 비어 있다
        val r2 = run(i, FakeBridge(RefinedConfirmationDecision.ProceedEdited("SurveyServiceImpl에 브랜드메시지를 추가하세요.")))
        assertTrue(r2.analyzed[0].retentionAudit!!.missingBeforeFix.isEmpty())
    }
}
