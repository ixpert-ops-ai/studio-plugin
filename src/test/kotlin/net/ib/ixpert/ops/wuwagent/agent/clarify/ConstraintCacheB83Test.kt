package net.ib.ixpert.ops.wuwagent.agent.clarify

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.OllamaMessage
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Assert.assertEquals
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * B-83 1: 제약 추출 캐시. 답변 직후(processTurn)의 추출 결과를 확정(buildClarifyIntentChecked) 때 재사용한다.
 * 캐시를 맞췄을 때와 아닐 때의 ClarifyIntent 제약이 같고, 발화가 추가되면 무효화(재호출)된다.
 */
class ConstraintCacheB83Test {
    private val root = File("C:/Workspace/HC_card_survey_admin/survey_admin")
    private val original = "기존 설문조사 상세 조회 기능에 응답자 이메일 정보도 같이 내려주도록 백엔드를 수정해주세요"
    private val answer1 = "응답자 이메일은 아직 수집하지 않아. 설문 응답 테이블에 이메일 컬럼을 새로 추가해서 저장하는 방향으로 해줘."
    private val answer2 = "관리자 설문 상세 화면용이고, 이메일은 앞 2자리와 도메인만 보이게 마스킹해줘."

    /** 제약 추출 호출만 센다(프롬프트에 "kind" 형식 지시가 있는 호출). 정제문 호출은 고정 문장으로 답한다. */
    private class CountingClient : LLMClient {
        val extractionCalls = AtomicInteger()
        override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): OllamaChatResponse? {
            val text = if (systemPrompt.contains("\"kind\"")) {
                extractionCalls.incrementAndGet()
                // 발화가 몇 개든 항상 같은 모양: evidence는 발화에 실제로 있는 단어(환각 가드 통과)
                "[{\"kind\":\"OTHER\",\"value\":\"요약\",\"rawStatement\":\"원문\",\"evidence\":\"이메일\"}]"
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

    private fun shape(i: net.ib.ixpert.ops.wuwagent.agent.clarify.model.ClarifyIntent) =
        i.constraints.map { listOf(it.kind.name, it.value, it.rawStatement, it.evidence) }

    @Test
    fun c1_발화가_하나면_확정_때_추출을_다시_부르지_않고_제약은_같다() {
        val g = graph()
        val cached = CountingClient()
        val e1 = engine(g, cached)
        val st0 = e1.initSession(original).state
        val turn = e1.processTurn(st0, Stage0ClarificationEngine.UserInput(userStatement = answer1))
        val afterTurn = cached.extractionCalls.get()
        val intentCached = e1.buildClarifyIntentChecked(turn.state).intent
        assertEquals("확정 때 제약 추출 호출 0회(캐시 재사용)", afterTurn, cached.extractionCalls.get())
        assertEquals(1, e1.extractionCacheHits)

        // 캐시가 없는 새 엔진(같은 상태)이 확정 때 추출을 직접 부른 결과와 같다
        val fresh = CountingClient()
        val intentFresh = engine(g, fresh).buildClarifyIntentChecked(turn.state).intent
        assertEquals("새 엔진은 확정 때 1회 호출", 1, fresh.extractionCalls.get())
        assertEquals(shape(intentFresh), shape(intentCached))
    }

    @Test
    fun c2_발화가_추가되면_캐시가_무효화되어_다시_부른다() {
        val g = graph()
        val c = CountingClient()
        val e = engine(g, c)
        val st0 = e.initSession(original).state
        val t1 = e.processTurn(st0, Stage0ClarificationEngine.UserInput(userStatement = answer1))
        val t2 = e.processTurn(t1.state, Stage0ClarificationEngine.UserInput(userStatement = answer2))
        val before = c.extractionCalls.get()
        e.buildClarifyIntentChecked(t2.state)
        // 확정 입력은 발화 2개 목록이라 어떤 이전 키와도 다르다
        assertEquals("발화 2개 목록은 새 호출 1회", before + 1, c.extractionCalls.get())
        // 같은 확정을 다시 하면 이번에는 재사용
        val mid = c.extractionCalls.get()
        e.buildClarifyIntentChecked(t2.state)
        assertEquals(mid, c.extractionCalls.get())
        // 원문 SR이 다른 세션(키의 원문 부분)이면 같은 발화여도 재사용하지 않는다
        val e2 = engine(g, CountingClient())
        e2.initSession("전혀 다른 요구사항 문장입니다 이메일")
        assertEquals(0, e2.extractionCacheHits)
    }
}
