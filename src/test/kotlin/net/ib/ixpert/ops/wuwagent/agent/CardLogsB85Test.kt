package net.ib.ixpert.ops.wuwagent.agent

import net.ib.ixpert.ops.wuwagent.agent.CardWaitLog.Card
import net.ib.ixpert.ops.wuwagent.agent.CardWaitLog.Result
import net.ib.ixpert.ops.wuwagent.agent.clarify.ConstraintCacheLog
import net.ib.ixpert.ops.wuwagent.agent.clarify.RefinedConfirmationDecision
import net.ib.ixpert.ops.wuwagent.agent.clarify.Stage0ClarificationEngine
import net.ib.ixpert.ops.wuwagent.agent.clarify.Stage0GraphScanner
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.Stage0State
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.OllamaMessage
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.ExplorerTimingLog
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ArchitectureLayer
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FileNode
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.GraphStatistics
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.SpringFileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/**
 * B-85 7: 카드 결과·탐색기 카드 대기·제약 추출 Future 재사용 로그의 형식을 순수 함수로 고정한다(IDE·LLM 없음).
 */
class CardLogsB85Test {
    // ── [CardWait] ──
    @Test
    fun c1_theCardWaitLineHasAFixedFormat() {
        assertEquals("[CardWait] card=hint turn=2 result=skip waitedMs=134676", CardWaitLog.line(Card.HINT, 2, Result.SKIP, 134676))
        assertEquals("[CardWait] card=hint turn=1 result=timeout waitedMs=300011", CardWaitLog.line(Card.HINT, 1, Result.TIMEOUT, 300011))
        assertEquals("[CardWait] card=hint turn=3 result=submit waitedMs=4200", CardWaitLog.line(Card.HINT, 3, Result.SUBMIT, 4200))
        assertEquals("[CardWait] card=package turn=- result=skip waitedMs=80910", CardWaitLog.line(Card.PACKAGE, null, Result.SKIP, 80910))
        assertEquals("[CardWait] card=stage3 turn=- result=timeout waitedMs=300002", CardWaitLog.line(Card.STAGE3, null, Result.TIMEOUT, 300002))
        assertEquals("[CardWait] card=refine turn=- result=edit waitedMs=15000", CardWaitLog.line(Card.REFINE, null, Result.EDIT, 15000))
        assertEquals("[CardWait] card=refine turn=- result=cancel waitedMs=900", CardWaitLog.line(Card.REFINE, null, Result.CANCEL, 900))
        assertEquals("[CardWait] card=refine turn=- result=stale waitedMs=-", CardWaitLog.line(Card.REFINE, null, Result.STALE, null))
        // 모르는 값은 "-": 턴이 없거나 0 이하, 기다린 시간을 모르거나 음수
        assertEquals("[CardWait] card=hint turn=- result=none waitedMs=-", CardWaitLog.line(Card.HINT, 0, Result.NONE, -5))
        assertEquals("[CardWait] card=refine turn=- result=superseded waitedMs=0", CardWaitLog.line(Card.REFINE, null, Result.SUPERSEDED, 0))
        // 카드 이름과 결과 이름은 정해진 값뿐이다
        assertEquals(listOf("refine", "package", "hint", "stage3"), Card.values().map { it.label })
        assertEquals(listOf("submit", "edit", "skip", "cancel", "timeout", "stale", "superseded", "none"), Result.values().map { it.label })
    }

    @Test
    fun c2_theRefineDecisionsMapToTheLogResults() {
        assertEquals(Result.SUBMIT, CardWaitLog.refineResult(RefinedConfirmationDecision.Proceed))
        assertEquals(Result.EDIT, CardWaitLog.refineResult(RefinedConfirmationDecision.ProceedEdited("고친 문장")))
        assertEquals(Result.CANCEL, CardWaitLog.refineResult(RefinedConfirmationDecision.Cancel))
        assertEquals(Result.TIMEOUT, CardWaitLog.refineResult(RefinedConfirmationDecision.TimedOut))
        assertEquals(Result.SUPERSEDED, CardWaitLog.refineResult(RefinedConfirmationDecision.Superseded))
        assertEquals(Result.NONE, CardWaitLog.refineResult(null))
    }

    @Test
    fun c3_everyCardBridgeLogsItsResultAndTheStaleResponseIsLogged() {
        val dir = "src/main/kotlin/net/ib/ixpert/ops/wuwagent/agent/"
        fun src(name: String) = File(dir + name).readText(Charsets.UTF_8)
        // 힌트 카드는 턴 번호를 싣고 제출·건너뛰기·시간 초과·결정 없음 4가지를 남긴다
        val l1 = src("L1ClarificationBridge.kt")
        assertTrue(l1.contains("CardWaitLog.log(CardWaitLog.Card.HINT, turn, if (hint == null) CardWaitLog.Result.SKIP else CardWaitLog.Result.SUBMIT"))
        assertTrue(l1.contains("CardWaitLog.log(CardWaitLog.Card.HINT, turn, CardWaitLog.Result.TIMEOUT"))
        assertTrue(l1.contains("CardWaitLog.log(CardWaitLog.Card.HINT, turn, CardWaitLog.Result.NONE"))
        assertTrue(src("ScopeSelectionBridge.kt").contains("CardWaitLog.Card.PACKAGE"))
        val s3 = src("Stage3ConfirmationBridge.kt")
        assertTrue(s3.contains("CardWaitLog.Card.STAGE3") && s3.contains("CardWaitLog.Result.TIMEOUT"))
        val refine = src("RefinedRequirementConfirmationBridge.kt")
        assertTrue(refine.contains("CardWaitLog.log(CardWaitLog.Card.REFINE, null, CardWaitLog.refineResult(decision)"))
        assertTrue("오래된 응답은 stale로 남긴다", refine.contains("CardWaitLog.log(CardWaitLog.Card.REFINE, null, CardWaitLog.Result.STALE, null)"))
        // 기존 [CardTimeout]·[RefineGate] 줄은 그대로 남는다
        assertTrue(src("CardTimeoutNotice.kt").contains("[CardTimeout] 5분 응답 없음 → 자동 진행: \$text"))
        assertTrue(refine.contains("[RefineGate] stale response ignored"))
    }

    // ── [ExplorerTiming] ──
    @Test
    fun e1_theExplorerTimingLineShowsTheCardWaitOfEachTurn() {
        // 11-11 사례의 합계(총 550748ms, 카드 대기 434721ms): 한 카드가 아니라 Turn 1 시간 초과(5분)와 Turn 2 건너뛰기의 합이다
        val line = ExplorerTimingLog.line(550748, listOf(11548, 16045, 22644, 22445, 43099), listOf(300031, 134690, 0, 0, 0))
        assertEquals(
            "[ExplorerTiming] 총 550748ms, 턴별 LLM [11548, 16045, 22644, 22445, 43099]ms, 카드 대기(사용자) 434721ms, cardWaitByTurn=[300031, 134690, 0, 0, 0], 대기를 뺀 순수 116027ms (턴 5개)",
            line
        )
        // 카드를 띄우지 않은 탐색(브리지 없음)은 모두 0
        assertEquals(
            "[ExplorerTiming] 총 95417ms, 턴별 LLM [12206, 14528, 23423, 45125]ms, 카드 대기(사용자) 0ms, cardWaitByTurn=[0, 0, 0, 0], 대기를 뺀 순수 95417ms (턴 4개)",
            ExplorerTimingLog.line(95417, listOf(12206, 14528, 23423, 45125), listOf(0, 0, 0, 0))
        )
        val selector = File("src/main/kotlin/net/ib/ixpert/ops/wuwagent/service/metagraph/consumer/discovery/AgenticSeedSelector.kt").readText(Charsets.UTF_8)
        assertTrue(selector.contains("ExplorerTimingLog.line(explorerTotalMs, turnLlmMs, (1..turnLlmMs.size).map { cardWaitByTurnMs[it] ?: 0L })"))
    }

    // ── [ConstraintCache] future-reuse ──
    @Test
    fun f1_theFutureReuseLineHasAFixedFormat() {
        assertEquals("[ConstraintCache] future-reuse waitMs=0 done=true", ConstraintCacheLog.futureReuseLine(0, true))
        assertEquals("[ConstraintCache] future-reuse waitMs=21980 done=false", ConstraintCacheLog.futureReuseLine(21980, false))
        assertEquals("[ConstraintCache] future-reuse waitMs=0 done=true", ConstraintCacheLog.futureReuseLine(-3, true))
    }

    private class Fake : LLMClient {
        override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): OllamaChatResponse {
            val content = when {
                systemPrompt.contains("ConstraintKind") -> "[]"
                systemPrompt.contains("소프트웨어 요구사항 정제 전문가") -> "정제된 요구사항"
                else -> "{}"
            }
            return OllamaChatResponse(model = "fake", createdAt = "", message = OllamaMessage("assistant", content), done = true)
        }

        override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
    }

    @Test
    fun f2_oneExtractionShowsAsOneMissAndOneFutureReuseInTheLog() {
        val node = FileNode(path = "src/survey/SurveyServiceImpl.java", packageName = "p", className = "SurveyServiceImpl", fileType = SpringFileType.SERVICE,
            layer = ArchitectureLayer.BUSINESS, koreanComments = listOf("설문 서비스"))
        val g = ProjectGraph(
            generatedAt = java.time.Instant.now().toString(), projectRoot = "/test/root", frameworkType = FrameworkType.SPRING_MVC_MYBATIS,
            files = mapOf(node.path to node), relationships = emptyList(), resourceNodes = emptyList(), statistics = GraphStatistics()
        )
        val engine = Stage0ClarificationEngine(Stage0GraphScanner(g, minSpecificityScore = 1.0, proposalBudget = 10), g, llmClient = Fake())
        val state = Stage0State(originalRequirement = "설문 서비스 이메일 추가", userStatements = listOf("응답자 이메일 출처: 새 컬럼 추가해서 저장"))
        val old = System.out
        val buf = ByteArrayOutputStream()
        System.setOut(PrintStream(buf, true, "UTF-8"))
        try {
            val deferred = engine.buildClarifyIntentDeferred(state)
            deferred.complete(deferred.early.intent)
        } finally {
            System.setOut(old)
        }
        val lines = buf.toString("UTF-8").lines()
        assertEquals("추출 호출은 miss 한 번", 1, lines.count { it.startsWith("[ConstraintCache] miss") })
        val reuse = lines.filter { it.startsWith("[ConstraintCache] future-reuse") }
        assertEquals("확정 뒤 Future를 받은 줄은 한 번: $lines", 1, reuse.size)
        assertTrue(reuse.single(), Regex("^\\[ConstraintCache] future-reuse waitMs=\\d+ done=(true|false)$").matches(reuse.single()))
    }
}
