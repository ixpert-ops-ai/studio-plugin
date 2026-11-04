package net.ib.ixpert.ops.wuwagent.agent.clarify

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.ClarifyIntent
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.OllamaMessage
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * B-84 0(D안): 웹뷰 경로(소스 질문 답 /clarify-utterance → 확정)를 모의한다. 확정할 때 카드는 정제문만 기다리고, 제약 추출은 같은 시각에 시작해 카드 응답 때 기다린다.
 * 추출 호출은 총 1회이고, 사용자가 카드에 일찍 응답해도(추출 진행 중) 새로 부르지 않는다.
 */
class DeferredIntentB84Test {
    private val root = File("C:/Workspace/HC_card_survey_admin/survey_admin")
    private val original = "기존 설문조사 상세 조회 기능에 응답자 이메일 정보도 같이 내려주도록 백엔드를 수정해주세요"
    private val answer = "응답자 이메일은 아직 수집하지 않아. 설문 응답 테이블에 이메일 컬럼을 새로 추가해서 저장하는 방향으로 해줘."

    /** 확정 때의 제약 추출(프롬프트에 "출처:" 접두가 있는 발화)만 센다. gate가 열려 있어야 그 호출이 끝난다. */
    private class GateClient(val gate: CountDownLatch, val failFirstConfirmExtraction: Boolean = false) : LLMClient {
        val confirmExtractionCalls = AtomicInteger()
        val allExtractionCalls = AtomicInteger()
        override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): OllamaChatResponse? {
            val text = if (systemPrompt.contains("\"kind\"")) {
                allExtractionCalls.incrementAndGet()
                if (userCode.contains("출처")) {
                    val n = confirmExtractionCalls.incrementAndGet()
                    gate.await(30, TimeUnit.SECONDS)
                    if (failFirstConfirmExtraction && n == 1) "[Error] 모의 실패"
                    else "[{\"kind\":\"NEW_MODULE\",\"value\":\"컬럼 추가\",\"rawStatement\":\"원문\",\"evidence\":\"이메일\"}]"
                } else "[]"
            } else "설문 응답 테이블에 이메일 컬럼을 추가한다."
            return OllamaChatResponse(model = "stub", createdAt = "", message = OllamaMessage("assistant", text), done = true)
        }
        override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
    }

    private fun graph(): ProjectGraph {
        val f = File(root, ".meta/project-graph.json")
        Assume.assumeTrue(f.isFile)
        return Gson().fromJson(f.readText(Charsets.UTF_8), ProjectGraph::class.java).normalizeLegacyCollections()
    }

    private fun engine(g: ProjectGraph, c: LLMClient) = Stage0ClarificationEngine(Stage0GraphScanner(g), g, c, deferTaskSummary = true, projectRoot = root)

    /** 웹뷰와 같은 경로로 소스 질문에 답한 상태를 만든다(번역기·추출 없이 답을 제약으로 기록하는 경로). */
    private fun answered(eng: Stage0ClarificationEngine) = eng.processUtterance(eng.initSession(original).state, answer).state

    private fun shape(i: ClarifyIntent) = i.constraints.map { listOf(it.kind.name, it.value, it.rawStatement) }

    private class Bridge(val decision: net.ib.ixpert.ops.wuwagent.agent.clarify.RefinedConfirmationDecision?, val onRequest: () -> Unit = {}) : RefinedRequirementConfirmationBridge {
        override fun requestConfirmation(originalRequirement: String, refinedRequirement: String): RefinedConfirmationDecision? { onRequest(); return decision }
    }

    @Test
    fun d1_확정은_정제문만_기다리고_추출은_응답_때_기다리며_호출은_총_1회다() {
        val g = graph()
        val gate = CountDownLatch(1) // 열기 전에는 확정 때의 제약 추출이 끝나지 않는다
        val client = GateClient(gate)
        val eng = engine(g, client)
        val st = answered(eng)
        val t0 = System.currentTimeMillis()
        val deferred = eng.buildClarifyIntentDeferred(st) // 추출이 gate에 막혀 있어도 돌아와야 한다
        val elapsed = System.currentTimeMillis() - t0
        assertTrue("추출이 막혀 있어도 정제문만 기다리고 돌아온다(${elapsed}ms)", elapsed < 10_000)
        assertTrue("정제문이 있다: ${deferred.early.intent.refinedRequirement}", deferred.early.intent.refinedRequirement.isNotBlank())
        assertEquals("카드로 보낼 때 제약은 소스 질문 답 제약뿐", 1, deferred.early.intent.constraints.size)

        // 카드 응답(이대로)이 추출 진행 중에 일찍 오는 경우
        val analyzed = AtomicReference<ClarifyIntent?>()
        val th = Thread {
            RefinedRequirementGate.handoff(
                intent = deferred.early.intent, bridge = Bridge(RefinedConfirmationDecision.Proceed),
                save = {}, analyze = { analyzed.set(it) }, onCancelled = {}, complete = { deferred.complete(it).intent }
            )
        }
        th.start()
        Thread.sleep(400)
        assertNull("추출이 끝나기 전에는 분석이 시작되지 않는다", analyzed.get())
        gate.countDown()
        th.join(15_000)
        assertFalse(th.isAlive)
        assertEquals("확정 때 제약 추출 호출 총 1회(응답 때 새로 부르지 않는다)", 1, client.confirmExtractionCalls.get())
        assertTrue("추출 결과가 분석 인텐트에 들어갔다: ${analyzed.get()?.constraints}", analyzed.get()!!.constraints.any { it.kind.name == "NEW_MODULE" })
    }

    @Test
    fun d2_추출이_먼저_끝난_뒤_응답해도_호출은_1회이고_순차_빌드와_제약이_같다() {
        val g = graph()
        val gate = CountDownLatch(1).also { it.countDown() }
        val client = GateClient(gate)
        val eng = engine(g, client)
        val st = answered(eng)
        val deferred = eng.buildClarifyIntentDeferred(st)
        Thread.sleep(300) // 추출이 끝날 시간
        val analyzed = AtomicReference<ClarifyIntent?>()
        RefinedRequirementGate.handoff(deferred.early.intent, Bridge(RefinedConfirmationDecision.Proceed), save = {}, analyze = { analyzed.set(it) }, onCancelled = {}, complete = { deferred.complete(it).intent })
        assertEquals(1, client.confirmExtractionCalls.get())
        // 같은 상태를 새 엔진이 순차(buildClarifyIntentChecked)로 만든 결과와 제약이 같다
        val fresh = GateClient(CountDownLatch(1).also { it.countDown() })
        val sequential = engine(g, fresh).buildClarifyIntentChecked(st).intent
        assertEquals(shape(sequential), shape(analyzed.get()!!))
    }

    @Test
    fun d3_수정한_정제문으로_분석해도_추출은_다시_부르지_않고_제약은_같다() {
        val g = graph()
        val client = GateClient(CountDownLatch(1).also { it.countDown() })
        val eng = engine(g, client)
        val st = answered(eng)
        val deferred = eng.buildClarifyIntentDeferred(st)
        val analyzed = AtomicReference<ClarifyIntent?>()
        val edited = "설문 응답 테이블에 이메일 컬럼을 추가하고 관리자 상세 조회에서 마스킹해 반환한다."
        RefinedRequirementGate.handoff(deferred.early.intent, Bridge(RefinedConfirmationDecision.ProceedEdited(edited)), save = {}, analyze = { analyzed.set(it) }, onCancelled = {}, complete = { deferred.complete(it).intent })
        assertEquals("수정한 문장이 분석 인텐트의 정제문", edited, analyzed.get()!!.refinedRequirement)
        assertEquals("추출 입력은 정제문과 무관하다: 호출 1회", 1, client.confirmExtractionCalls.get())
        assertTrue(analyzed.get()!!.constraints.any { it.kind.name == "NEW_MODULE" })
    }

    @Test
    fun d4_취소하면_complete를_부르지_않고_진행_중_추출은_정리되어_다음_확정이_새로_부른다() {
        val g = graph()
        val gate = CountDownLatch(1)
        val client = GateClient(gate)
        val eng = engine(g, client)
        val st = answered(eng)
        val deferred = eng.buildClarifyIntentDeferred(st)
        var cancelled = false
        var completed = false
        RefinedRequirementGate.handoff(deferred.early.intent, Bridge(RefinedConfirmationDecision.Cancel), save = {}, analyze = {}, onCancelled = { cancelled = true; deferred.cancel() }, complete = { completed = true; it })
        assertTrue(cancelled)
        assertFalse("취소에서는 추출을 기다리지 않는다", completed)
        gate.countDown()
        // 같은 상태로 다시 확정하면 취소된 Future를 쓰지 않고 새로 호출한다
        val again = eng.buildClarifyIntentDeferred(st)
        again.complete(again.early.intent)
        assertEquals("취소 뒤 재확정은 새 호출(총 2회)", 2, client.confirmExtractionCalls.get())
    }

    @Test
    fun d5_실패한_추출은_캐시하지_않아_다음_확정이_다시_부른다() {
        val g = graph()
        val client = GateClient(CountDownLatch(1).also { it.countDown() }, failFirstConfirmExtraction = true)
        val eng = engine(g, client)
        val st = answered(eng)
        val first = eng.buildClarifyIntentDeferred(st)
        val firstDone = first.complete(first.early.intent)
        assertEquals("1회차는 실패 → 기본 제약으로 대체", 1, client.confirmExtractionCalls.get())
        assertTrue("실패 안내가 있다", firstDone.constraintNotice != null)
        val second = eng.buildClarifyIntentDeferred(st)
        val secondDone = second.complete(second.early.intent)
        assertEquals("실패 결과는 캐시되지 않아 다시 호출", 2, client.confirmExtractionCalls.get())
        assertTrue(secondDone.intent.constraints.any { it.kind.name == "NEW_MODULE" })
    }
}
